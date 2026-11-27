package im.majo.ctos;

import static org.junit.Assert.*;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Exercises the real attach callback with an injected host dispatcher, not Android's Looper. */
public final class SshPasswordConnectBridgeCompletionTest {
    private static final long NOW = 1_000, DEADLINE = 60_000;

    private static final class Invalidation implements VaultSession.Invalidation {
        boolean fail;
        int begins;
        @Override public int begin() {
            begins++;
            if (fail) throw new UnsatisfiedLinkError("fixture vault invalidation");
            return 0;
        }
        @Override public void end() { throw new AssertionError("no invalidation began"); }
    }

    private static final class Recorder implements MethodChannel.Result {
        int calls;
        String code;
        Object value;
        boolean throwBeforeReply, throwAfterReply;
        @Override public void success(Object result) {
            calls++;
            if (throwBeforeReply) throw new IllegalStateException("reply not sent by fixture");
            value = result;
            if (throwAfterReply) throw new IllegalStateException("reply already delivered");
        }
        @Override public void error(String error, String message, Object details) {
            calls++;
            if (throwBeforeReply) throw new IllegalStateException("reply not sent by fixture");
            code = error;
            if (throwAfterReply) throw new IllegalStateException("reply already delivered");
        }
        @Override public void notImplemented() { fail("unexpected notImplemented"); }
    }

    private static final class Fixture {
        final AppSecrets vault;
        final SshPasswordConnector connector;
        final SshPasswordConnectBridge bridge;
        final Recorder result = new Recorder();
        final CompletableFuture<String> completion = new CompletableFuture<>();
        final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

        Fixture() throws Exception { this(new Invalidation()); }

        Fixture(Invalidation invalidation) throws Exception {
            vault = new AppSecrets(new AppSecrets.Store() {
                @Override public byte[] read() { throw new AssertionError("unexpected store read"); }
                @Override public byte[] quickMaterial() { throw new AssertionError("unexpected store read"); }
                @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                    throw new AssertionError("unexpected store write");
                }
            }, () -> NOW);
            VaultSession session = new VaultSession(() -> NOW, invalidation);
            set(vault, "session", session);
            vault.attachSshValidator(manifest -> { });
            session.foreground(true); session.unlock(new byte[32]);
            connector = new SshPasswordConnector(vault, new SshPasswordProfile("example.invalid", 22, "user"),
                    "fixture-secret".getBytes(StandardCharsets.UTF_8), false, true,
                    DEADLINE, () -> { }, null, () -> NOW);
            // Host tests cannot construct Android's Handler or Activity. Invoke the
            // real bridge method/callback with only those dependencies substituted.
            bridge = allocate(SshPasswordConnectBridge.class);
            set(bridge, "vault", vault);
            set(bridge, "attacher", (SshPasswordConnectBridge.Attacher) (actual, mode, columns, rows) -> {
                assertSame(connector, actual);
                return completion;
            });
            set(bridge, "completionDispatcher", (SshPasswordConnectBridge.CompletionDispatcher) action -> {
                queue.add(action);
                return true;
            });
            set(bridge, "publicationLive", (java.util.function.Predicate<SshPasswordConnector>) actual -> true);
            AppSecretsBridge secrets = allocate(AppSecretsBridge.class);
            set(secrets, "vault", vault);
            set(secrets, "resumed", true);
            set(secrets, "sensitiveOwnerCount", 1);
            set(bridge, "secrets", secrets);
        }

        void attach() throws Exception {
            Method attach = SshPasswordConnectBridge.class.getDeclaredMethod("attach", String.class,
                    SshPasswordConnector.class, int.class, int.class, MethodChannel.Result.class);
            attach.setAccessible(true);
            attach.invoke(bridge, "request", connector, 80, 24, result);
        }

        Runnable queued() throws Exception {
            Runnable result = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull("completion was not dispatched", result);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return (T)unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static VaultException failure(String code) {
        return new VaultException(code, "fixture failure");
    }

    @Test public void asynchronousFailureSurvivesEventErrorsAndIsDeliveredOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        byte[] held = (byte[])field(fixture.connector, "pendingSave");
        set(fixture.bridge, "sink", new EventChannel.EventSink() {
            @Override public void success(Object value) { throw new IllegalStateException("event failure"); }
            @Override public void error(String code, String message, Object details) { fail(); }
            @Override public void endOfStream() { fail(); }
        });
        fixture.attach();
        assertEquals(0, fixture.result.calls);
        fixture.completion.completeExceptionally(failure("SSH_SCREEN_LOCKED"));
        assertEquals(0, fixture.result.calls);
        Runnable callback = fixture.queued();
        callback.run(); callback.run();
        assertEquals(1, fixture.result.calls);
        assertEquals("SSH_SCREEN_LOCKED", fixture.result.code);
        assertNull(field(fixture.bridge, "active"));
        assertArrayEquals(new byte[held.length], held);
    }

    @Test public void admittedReplyIsNotRepeatedWhenResultThrowsAfterItsSideEffect() throws Exception {
        for (boolean success : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.result.throwAfterReply = true;
            fixture.attach();
            if (success) fixture.completion.complete("ssh");
            else fixture.completion.completeExceptionally(failure("SSH_SERVICE_START"));
            Runnable callback = fixture.queued();
            callback.run(); callback.run();
            assertEquals(1, fixture.result.calls);
            if (success) {
                assertEquals("connected", ((Map<?, ?>)fixture.result.value).get("status"));
                assertNotNull(field(fixture.bridge, "handedOff"));
            } else assertEquals("SSH_SERVICE_START", fixture.result.code);
        }
    }

    @Test public void admittedReplyIsNotRetriedWhenResultThrowsBeforeItsSideEffect() throws Exception {
        for (boolean success : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.result.throwBeforeReply = true;
            fixture.attach();
            Object attempt = field(fixture.bridge, "active");
            if (success) fixture.completion.complete("ssh");
            else fixture.completion.completeExceptionally(failure("SSH_SERVICE_START"));
            Runnable callback = fixture.queued();
            callback.run(); callback.run();
            assertEquals(1, fixture.result.calls);
            assertTrue(((java.util.concurrent.atomic.AtomicBoolean)field(attempt, "resultAdmitted")).get());
            // This recorder does not send a channel message or prove peer delivery.
            assertNull(fixture.result.value);
            assertNull(fixture.result.code);
        }
    }

    @Test public void vaultLockFailureCannotHideAnUncertainTrustReceipt() throws Exception {
        Invalidation invalidation = new Invalidation();
        Fixture fixture = new Fixture(invalidation);
        byte[] oldKey = (byte[])field(fixture.vault.session, "key");
        SshPasswordConnector.TrustCommitState receipt =
                (SshPasswordConnector.TrustCommitState)field(fixture.connector, "trustCommit");
        receipt.newPersisted(); // Synthetic receipt, not a real store write.
        set(fixture.connector, "started", true);
        invalidation.fail = true;
        int before = invalidation.begins;
        fixture.attach();
        fixture.completion.completeExceptionally(failure("SSH_SCREEN_LOCKED"));
        Runnable callback = fixture.queued(); callback.run(); callback.run();
        assertEquals(1, fixture.result.calls);
        assertEquals("VAULT_COMMIT_UNCERTAIN", fixture.result.code);
        assertEquals(before + 1, invalidation.begins);
        assertArrayEquals(new byte[oldKey.length], oldKey);
        assertFalse(fixture.vault.session.unlocked());
        assertTrue(receipt.publicationUncertain());
    }

    @Test public void screenAndServiceRequireAnActualCredentialWriteForUncertainMapping() throws Exception {
        for (String reason : new String[]{"SSH_SCREEN_LOCKED", "SSH_SERVICE_START"}) {
            for (boolean written : new boolean[]{false, true}) {
                Fixture fixture = new Fixture();
                set(fixture.connector, "started", true);
                set(fixture.connector, "durableWrite", written); // Synthetic receipt only.
                fixture.attach();
                fixture.completion.completeExceptionally(failure(reason));
                fixture.queued().run();
                assertEquals(1, fixture.result.calls);
                assertEquals(written ? "VAULT_COMMIT_UNCERTAIN" : reason, fixture.result.code);
                assertEquals(!written, fixture.vault.session.unlocked());
            }
        }
    }

    @Test public void writtenAutomaticCredentialAlsoLocksForAScreenOrServiceFailure() throws Exception {
        for (String reason : new String[]{"SSH_SCREEN_LOCKED", "SSH_SERVICE_START"}) {
            Fixture fixture = new Fixture();
            set(fixture.connector, "started", true);
            set(fixture.connector, "automaticCredentialWritten", true); // Synthetic receipt only.
            fixture.attach();
            fixture.completion.completeExceptionally(failure(reason));
            fixture.queued().run();
            assertEquals("VAULT_COMMIT_UNCERTAIN", fixture.result.code);
            assertFalse(fixture.vault.session.unlocked());
        }
    }

    @Test public void liveHandoffDenialKeepsTheFirstOrdinaryScreenOrServiceStop() throws Exception {
        for (int source = 0; source < 3; source++) {
            Fixture fixture = new Fixture();
            set(fixture.bridge, "publicationLive", (java.util.function.Predicate<SshPasswordConnector>) actual ->
                    !actual.trustCancellation().stopRequested());
            SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
                @Override public SshSessionOwner.Cancellation trustCancellation() {
                    return fixture.connector.trustCancellation();
                }
                @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) {
                    throw new AssertionError("factory must not start");
                }
                @Override public void close() { }
            };
            CountDownLatch released = new CountDownLatch(1);
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                    Runnable::run, new SshSessionOwner.Lifecycle() {
                        @Override public void state(String value) { }
                        @Override public void released() { released.countDown(); }
                    });
            fixture.attach();
            fixture.completion.complete("ssh");
            if (source == 0) fixture.connector.trustCancellation().cancel();
            else if (source == 1) assertTrue(owner.stopForScreenLock(owner.id));
            else owner.rejectStart();
            fixture.queued().run();
            assertEquals(1, fixture.result.calls);
            assertEquals(source == 0 ? "SSH_CANCELLED" : source == 1 ? "SSH_SCREEN_LOCKED"
                    : "SSH_SERVICE_START", fixture.result.code);
            if (source != 0) assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void confirmedTrustDoesNotBecomeUncertainAfterOrdinaryStop() throws Exception {
        Fixture fixture = new Fixture();
        SshPasswordConnector.TrustCommitState receipt =
                (SshPasswordConnector.TrustCommitState)field(fixture.connector, "trustCommit");
        receipt.newPersisted(); receipt.newProofPublished(); // Synthetic receipt, no store write.
        set(fixture.connector, "started", true);
        set(fixture.bridge, "publicationLive", (java.util.function.Predicate<SshPasswordConnector>) actual ->
                !actual.trustCancellation().stopRequested());
        fixture.attach();
        fixture.completion.complete("ssh");
        fixture.connector.trustCancellation().cancel();
        fixture.queued().run();
        assertEquals("SSH_CANCELLED", fixture.result.code);
        assertFalse(receipt.publicationUncertain());
        assertTrue(fixture.vault.session.unlocked());
    }

    @Test public void synchronousUnstartedAttachFailureRetiresAndRepliesDespiteEventError() throws Exception {
        Fixture fixture = new Fixture();
        byte[] held = (byte[])field(fixture.connector, "pendingSave");
        set(fixture.bridge, "attacher", (SshPasswordConnectBridge.Attacher) (factory, mode, columns, rows) -> {
            throw new UnsatisfiedLinkError("fixture attach failure");
        });
        set(fixture.bridge, "sink", new EventChannel.EventSink() {
            @Override public void success(Object value) { throw new IllegalStateException("event failure"); }
            @Override public void error(String code, String message, Object details) { fail(); }
            @Override public void endOfStream() { fail(); }
        });
        fixture.attach();
        assertEquals(1, fixture.result.calls);
        assertEquals("SSH_FAILED", fixture.result.code);
        assertArrayEquals(new byte[held.length], held);
        try { fixture.connector.create(() -> false); fail(); }
        catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
    }

    @Test public void failedMainDispatchStillRepliesWithoutInventingOwnerRelease() throws Exception {
        Fixture fixture = new Fixture();
        set(fixture.bridge, "completionDispatcher",
                (SshPasswordConnectBridge.CompletionDispatcher) action -> false);
        set(fixture.connector, "started", true);
        fixture.attach();
        fixture.completion.complete("ssh");
        assertEquals(1, fixture.result.calls);
        assertEquals("SSH_FAILED", fixture.result.code);
        assertNotNull(field(fixture.bridge, "active"));
        assertTrue(fixture.connector.trustCancellation().stopRequested());
    }

    @Test public void cancellationExceptionDoesNotDropTheHandoffFailure() throws Exception {
        Fixture fixture = new Fixture();
        set(fixture.connector, "started", true);
        set(fixture.bridge, "publicationLive", (java.util.function.Predicate<SshPasswordConnector>) actual -> false);
        SshSessionOwner.Cancellation cancellation = fixture.connector.trustCancellation();
        // Model an unresolved native invalidation without invoking JNI or retrying it.
        set(cancellation, "invalidationUnconfirmed", true);
        fixture.attach();
        Object attempt = field(fixture.bridge, "active");
        fixture.completion.complete("ssh");
        Runnable callback = fixture.queued(); callback.run(); callback.run();
        assertEquals(1, fixture.result.calls);
        assertEquals("SSH_CANCELLED", fixture.result.code);
        assertTrue(cancellation.stopRequested());
        assertTrue(cancellation.invalidationUnconfirmed());
        assertSame(fixture.connector, field(attempt, "connector"));
    }

    @Test public void admittedFactoryTrustReceiptIsFixedBeforeActualBridgeDelivery() throws Exception {
        Fixture fixture = new Fixture();
        SshPasswordConnector.TrustCommitState receipt =
                (SshPasswordConnector.TrustCommitState)field(fixture.connector, "trustCommit");
        CountDownLatch entered = new CountDownLatch(1), allowReturn = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() {
                return fixture.connector.trustCancellation();
            }
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                set(fixture.connector, "started", true);
                entered.countDown();
                try {
                    if (!allowReturn.await(5, TimeUnit.SECONDS)) throw new AssertionError("factory timeout");
                    receipt.newPersisted(); // Synthetic write outcome, after the controlled barrier.
                    throw failure("SSH_SESSION_CANCELLED");
                } catch (Exception | Error failure) {
                    workerFailure.set(failure);
                    throw failure;
                }
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String value) { }
                    @Override public void released() { released.countDown(); }
                });
        set(fixture.bridge, "attacher", (SshPasswordConnectBridge.Attacher) (actual, mode, columns, rows) -> {
            assertSame(fixture.connector, actual);
            owner.begin();
            return owner.connected;
        });
        try {
            fixture.attach();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(owner.stopForScreenLock(owner.id));
            assertEquals(0, fixture.result.calls);
            assertTrue(fixture.queue.isEmpty());
            assertEquals(0, closes.get());
            allowReturn.countDown();
            fixture.queued().run();
            assertEquals(1, fixture.result.calls);
            assertEquals("VAULT_COMMIT_UNCERTAIN", fixture.result.code);
            assertTrue(receipt.publicationUncertain());
            assertTrue(workerFailure.get() instanceof VaultException);
            assertTrue(released.await(5, TimeUnit.SECONDS));
        } finally { allowReturn.countDown(); }
    }
}
