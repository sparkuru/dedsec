package im.majo.ctos;

import static org.junit.Assert.*;

import io.flutter.plugin.common.MethodChannel;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SshConnectorRetirementTest {
    private static final long NOW = 1_000, DEADLINE = 60_000;
    private static final SshRoleContext.Clock CLOCK = () -> NOW;

    private AppSecrets vault() throws Exception {
        AppSecrets vault = new AppSecrets(new AppSecrets.Store() {
            @Override public byte[] read() { throw new AssertionError("unexpected store read"); }
            @Override public byte[] quickMaterial() { throw new AssertionError("unexpected store read"); }
            @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                throw new AssertionError("unexpected store write");
            }
        }, () -> NOW);
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.session.unlock(new byte[32]);
        return vault;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static byte[] secret() { return "test-secret".getBytes(StandardCharsets.UTF_8); }

    private static void awaitBlockedOn(Thread thread, Object monitor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
            Thread.yield();
        assertEquals("thread did not reach the controlled monitor", Thread.State.BLOCKED, thread.getState());
        Class<?> management = Class.forName("java.lang.management.ManagementFactory");
        Object bean = management.getMethod("getThreadMXBean").invoke(null);
        Object info = Class.forName("java.lang.management.ThreadMXBean")
                .getMethod("getThreadInfo", long.class).invoke(bean, thread.getId());
        assertNotNull(info);
        Object lock = Class.forName("java.lang.management.ThreadInfo").getMethod("getLockInfo").invoke(info);
        assertNotNull(lock);
        int identity = (Integer)Class.forName("java.lang.management.LockInfo")
                .getMethod("getIdentityHashCode").invoke(lock);
        assertEquals(System.identityHashCode(monitor), identity);
    }

    private static SshPasswordConnector connector(AppSecrets vault, int kind,
            AppSecrets.EffectGuard guard) throws Exception {
        byte[] input = secret();
        SshPasswordProfile profile = new SshPasswordProfile("example.invalid", 22, "user");
        switch (kind) {
            case 0:
                return new SshPasswordConnector(vault, profile, input, false, true,
                        DEADLINE, guard, null, CLOCK);
            case 1:
                return new SshPasswordConnector(vault, profile, input, false, false,
                        DEADLINE, guard, null, CLOCK);
            case 2:
                return new SshPasswordConnector(vault, profile, "key", "", "", "user", input,
                        false, false, DEADLINE, guard, null, CLOCK);
            case 3:
                return new SshPasswordConnector(vault, "target", "", "user", input,
                        false, false, DEADLINE, guard, null, CLOCK);
            default: throw new AssertionError("unknown test connector");
        }
    }

    @Test public void retirementWipesEachConstructorOwnedSecretAndDeniesLaterCreate() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            AppSecrets vault = vault();
            SshPasswordConnector connector = connector(vault, kind, () -> { });
            String name = kind == 0 ? "pendingSave" : kind == 2 ? "pendingKeyPassphrase"
                    : kind == 3 ? "pendingConfigPassword" : "password";
            Object material = field(connector, name);
            byte[] held = kind == 1 ? (byte[])field(material, "bytes") : (byte[])material;
            assertArrayEquals(secret(), held);
            assertTrue(connector.retireIfUnstarted());
            assertArrayEquals(new byte[held.length], held);
            assertNull(field(connector, name));
            assertTrue(connector.retireIfUnstarted());
            try { connector.create(() -> false); fail("retired connector entered create"); }
            catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
            assertFalse(connector.started());
            assertNull(field(connector, "owner"));
        }
    }

    @Test public void enteredCreateRetainsItsMaterialUntilTheOriginalOwnerCleansUp() throws Exception {
        AppSecrets vault = vault();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        AppSecrets.EffectGuard guard = () -> {
            if (!block.get()) return;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("create barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            throw new VaultException("SSH_CANCELLED", "fixture stop");
        };
        SshPasswordConnector connector = connector(vault, 0, guard);
        byte[] held = (byte[])field(connector, "pendingSave");
        AtomicReference<Throwable> outcome = new AtomicReference<>(), cleanup = new AtomicReference<>();
        block.set(true);
        Thread owner = new Thread(() -> {
            try { connector.create(() -> false); outcome.set(new AssertionError("fixture create succeeded")); }
            catch (Throwable failure) { outcome.set(failure); }
            finally { try { connector.close(); } catch (Throwable failure) { cleanup.set(failure); } }
        });
        owner.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(connector.retireIfUnstarted());
            assertArrayEquals(secret(), held);
        } finally {
            release.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive());
        assertTrue(outcome.get() instanceof VaultException);
        assertEquals("SSH_CANCELLED", ((VaultException)outcome.get()).code);
        assertNull(cleanup.get());
        assertArrayEquals(new byte[held.length], held);
    }

    @Test public void retirementDoesNotReleaseAnOwnerAlreadyWaitingToEnterTheFactory() throws Exception {
        AppSecrets vault = vault();
        SshPasswordConnector connector = connector(vault, 0, () -> { });
        byte[] held = (byte[])field(connector, "pendingSave");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch ownerReleased = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("factory barrier timed out");
                return connector.create(cancelled);
            }
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() { return connector.trustCancellation(); }
            @Override public void cancellationAction(Runnable action) { connector.cancellationAction(action); }
            @Override public void close() throws Exception { closes.incrementAndGet(); connector.close(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { ownerReleased.countDown(); }
                });
        owner.begin();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(connector.started());
            assertTrue(connector.retireIfUnstarted());
            assertArrayEquals(new byte[held.length], held);
            assertEquals(0, closes.get());
            assertFalse(owner.connected.isDone());
            assertEquals(1, ownerReleased.getCount());
        } finally { release.countDown(); }
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("retired factory connected"); }
        catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof VaultException);
            assertEquals("SSH_CANCELLED", ((VaultException)expected.getCause()).code);
        }
        assertTrue(ownerReleased.await(5, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }

    @Test public void concurrentCancelAndRetirementDoNotRaceThePasswordReference() throws Exception {
        AppSecrets vault = vault();
        SshPasswordConnector connector = connector(vault, 1, () -> { });
        Object password = field(connector, "password");
        byte[] held = (byte[])field(password, "bytes");
        AtomicInteger notifications = new AtomicInteger();
        connector.cancellationAction(notifications::incrementAndGet);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread retire = new Thread(() -> {
            try { if (!connector.retireIfUnstarted()) throw new AssertionError("retire lost"); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        });
        Thread cancel = new Thread(() -> {
            try { connector.cancel(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        });
        synchronized (password) {
            retire.start();
            awaitBlockedOn(retire, password);
            cancel.start();
            awaitBlockedOn(cancel, connector);
        }
        retire.join(5000);
        cancel.join(5000);
        assertFalse(retire.isAlive());
        assertFalse(cancel.isAlive());
        assertNull(failure.get());
        assertNull(field(connector, "password"));
        assertArrayEquals(new byte[held.length], held);
        assertEquals(1, notifications.get());
    }

    @Test public void stopBeforeConnectorAdmissionRetiresWhileOwnerKeepsItsFactory() throws Exception {
        AppSecrets vault = vault();
        SshPasswordConnector connector = connector(vault, 0, () -> { });
        byte[] held = (byte[])field(connector, "pendingSave");
        CountDownLatch factoryEntered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CountDownLatch connectorReturned = new CountDownLatch(1), ownerReleased = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        SshSessionOwner.Cancellation cancellation = connector.trustCancellation();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                factoryEntered.countDown();
                if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("factory barrier timed out");
                try { return connector.create(cancelled); }
                finally { connectorReturned.countDown(); }
            }
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
            @Override public void cancellationAction(Runnable action) { connector.cancellationAction(action); }
            @Override public void close() throws Exception { closes.incrementAndGet(); connector.close(); }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String state) { }
                    @Override public void released() { ownerReleased.countDown(); }
                });
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        Thread cancel = new Thread(() -> {
            try { connector.cancel(); } catch (Throwable failure) { cancelFailure.set(failure); }
        });
        owner.begin();
        try {
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
            synchronized (cancellation) {
                cancel.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!cancellation.stopRequested() && System.nanoTime() < deadline) Thread.yield();
                assertTrue(cancellation.stopRequested());
                proceed.countDown();
                assertTrue(connectorReturned.await(5, TimeUnit.SECONDS));
                assertFalse(connector.started());
                assertArrayEquals(new byte[held.length], held);
                assertEquals(0, closes.get());
                assertEquals(1, ownerReleased.getCount());
            }
        } finally { proceed.countDown(); }
        cancel.join(5000);
        assertFalse(cancel.isAlive());
        assertNull(cancelFailure.get());
        try { owner.connected.get(5, TimeUnit.SECONDS); fail("stopped factory connected"); }
        catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof VaultException);
            assertEquals("SSH_CANCELLED", ((VaultException)expected.getCause()).code);
        }
        assertTrue(ownerReleased.await(5, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }

    @Test public void constructorFailurePreservesTheOriginalErrorWithoutNativeCancellation() throws Exception {
        AppSecrets vault = vault();
        Field enabled = SshNativeCallbackAdapter.class.getDeclaredField("jumpEffectFenceEnabled");
        enabled.setAccessible(true);
        boolean previous = enabled.getBoolean(null);
        try {
            enabled.setBoolean(null, true);
            for (int kind = 0; kind < 4; kind++) {
                VaultException original = new VaultException("FIXTURE_FAILURE", "fixture constructor failure");
                try { connector(vault, kind, () -> { throw original; }); fail("constructor succeeded"); }
                catch (VaultException expected) { assertSame(original, expected); }
            }
        } finally { enabled.setBoolean(null, previous); }
    }

    @Test public void unstartedCancelWipesItsMaterialWhenNativeInvalidationThrows() throws Exception {
        AppSecrets vault = vault();
        SshPasswordConnector connector = connector(vault, 0, () -> { });
        byte[] held = (byte[])field(connector, "pendingSave");
        Field enabled = SshNativeCallbackAdapter.class.getDeclaredField("jumpEffectFenceEnabled");
        enabled.setAccessible(true);
        boolean previous = enabled.getBoolean(null);
        try {
            enabled.setBoolean(null, true);
            try { connector.cancel(); fail("native invalidation unexpectedly succeeded"); }
            catch (LinkageError expected) { /* The local JNI library is intentionally unavailable. */ }
            assertArrayEquals(new byte[held.length], held);
            assertNull(field(connector, "pendingSave"));
            assertTrue(connector.trustCancellation().invalidationUnconfirmed());
            try { connector.create(() -> false); fail("cancelled connector entered create"); }
            catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        } finally { enabled.setBoolean(null, previous); }
    }

    @Test public void synchronousAttachLinkageFailureRetiresTheExactUnstartedConnector() throws Exception {
        AppSecrets vault = vault();
        SshPasswordConnector connector = connector(vault, 0, () -> { });
        byte[] held = (byte[])field(connector, "pendingSave");
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        // Android's Handler constructor is unavailable in the host unit-test JVM.
        Object bridge = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, SshPasswordConnectBridge.class);
        Field attacherField = SshPasswordConnectBridge.class.getDeclaredField("attacher");
        attacherField.setAccessible(true);
        attacherField.set(bridge, (SshPasswordConnectBridge.Attacher)(actual, mode, columns, rows) -> {
            assertSame(connector, actual);
            throw new UnsatisfiedLinkError("fixture attach failure");
        });
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> code = new AtomicReference<>();
        MethodChannel.Result result = new MethodChannel.Result() {
            @Override public void success(Object value) { fail("attach unexpectedly succeeded"); }
            @Override public void error(String reason, String message, Object details) {
                calls.incrementAndGet(); code.set(reason);
            }
            @Override public void notImplemented() { fail("attach was not implemented"); }
        };
        java.lang.reflect.Method attach = SshPasswordConnectBridge.class.getDeclaredMethod("attach",
                String.class, SshPasswordConnector.class, int.class, int.class, MethodChannel.Result.class);
        attach.setAccessible(true);
        attach.invoke(bridge, "request", connector, 80, 24, result);
        assertEquals(1, calls.get());
        assertEquals("SSH_FAILED", code.get());
        assertNull(field(bridge, "active"));
        assertArrayEquals(new byte[held.length], held);
        try { connector.create(() -> false); fail("retired connector entered create"); }
        catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
    }
}
