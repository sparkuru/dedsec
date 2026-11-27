package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

/** Host tests use an encrypted memory fixture and a controllable invalidator. */
public final class SshUncertainPreviewTest {
    private static final char[] PASSWORD = "test-only vault password".toCharArray();
    private static byte[] envelope;
    private static byte[] sshEnvelope;

    private static final class Store implements AppSecrets.Store {
        volatile byte[] bytes = envelope.clone();
        volatile Runnable afterGuard;
        @Override public byte[] read() { return bytes.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit();
            Runnable callback = afterGuard;
            if (callback != null) callback.run();
            bytes = next.clone();
        }
    }

    private static final class Invalidation implements VaultSession.Invalidation {
        final AtomicInteger begins = new AtomicInteger();
        volatile Error failure;
        volatile int result;
        @Override public int begin() {
            begins.incrementAndGet();
            if (failure != null) throw failure;
            return result;
        }
        @Override public void end() { throw new AssertionError("no native invalidation began"); }
    }

    private static final class Fixture {
        final Store store = new Store();
        final Invalidation invalidation = new Invalidation();
        final AppSecrets vault = new AppSecrets(store, () -> 1_000);
        final AppSecrets.ImportPreview preview;

        Fixture() throws Exception {
            set(vault, "session", new VaultSession(() -> 1_000, invalidation));
            vault.session.foreground(true);
            vault.unlock(PASSWORD, () -> { });
            preview = vault.preview(envelope, PASSWORD, PASSWORD, "preview", () -> { });
            assertNotWiped(preview.source.key);
            assertNotWiped(preview.source.plaintext);
        }
    }

    @BeforeClass public static void prepareFixture() throws Exception {
        byte[] salt = new byte[16];
        byte[] key = VaultEnvelope.derive(PASSWORD, salt);
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp1",
                "Example:test", "Example", "SHA1", 6, 30,
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        try (VaultManifest manifest = new VaultManifest(List.of(entry), List.of())) {
            byte[] plaintext = manifest.encode();
            try { envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plaintext).encode(); }
            finally { VaultCodec.wipe(plaintext); }
        }
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "config1", "config", "test-config", "Host example.invalid\n".getBytes(StandardCharsets.UTF_8), List.of());
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(config))) {
            byte[] plaintext = manifest.encode();
            try { sshEnvelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plaintext).encode(); }
            finally { VaultCodec.wipe(plaintext); }
        } finally { VaultCodec.wipe(key); }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertWiped(byte[] bytes) { assertArrayEquals(new byte[bytes.length], bytes); }
    private static void assertNotWiped(byte[] bytes) { assertFalse(Arrays.equals(new byte[bytes.length], bytes)); }
    private static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static String uncertain(AppSecrets vault) {
        return SshPasswordConnectBridge.completionFailure(vault, "SSH_SCREEN_LOCKED", false, true);
    }

    @Test public void uncertainResultRetiresPreviewWithoutDiscardingOtherOperations() throws Exception {
        Fixture fixture = new Fixture();
        VaultOperation operation = new TotpVaultAdapter(fixture.vault).request("totp1");
        assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
        assertFalse(fixture.vault.session.unlocked());
        assertNull(field(fixture.vault, "preview"));
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
        assertSame(operation, ((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
    }

    @Test public void uncertainNativeFailureRetiresPreviewButKeepsExactOwners() throws Exception {
        Fixture fixture = new Fixture();
        VaultOperation operation = new TotpVaultAdapter(fixture.vault).request("totp1");
        byte[] sessionKey = (byte[])field(fixture.vault.session, "key");
        VaultSession.Revocation owner = new VaultSession.Revocation() {
            @Override public boolean active() { return true; }
            @Override public void revoke() { throw new AssertionError("begin failure must prevent revoke"); }
        };
        fixture.vault.session.attachRevocation(fixture.vault.session.epoch(), owner);
        fixture.invalidation.failure = new UnsatisfiedLinkError("fixture begin failure");
        int before = fixture.invalidation.begins.get();
        assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
        assertEquals(before + 1, fixture.invalidation.begins.get());
        assertWiped(sessionKey);
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
        assertSame(owner, field(fixture.vault.session, "revocation"));
        assertSame(operation, ((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
        try { fixture.vault.session.lock(); fail("unknown invalidation must not be repeated"); }
        catch (IllegalStateException expected) { assertEquals(before + 1, fixture.invalidation.begins.get()); }
    }

    @Test public void statusCleanupRetainsOperationsWhileRevocationIsUnconfirmed() throws Exception {
        Fixture fixture = new Fixture();
        VaultOperation operation = new TotpVaultAdapter(fixture.vault).request("totp1");
        VaultSession.Revocation owner = new VaultSession.Revocation() {
            @Override public boolean active() { return true; }
            @Override public void revoke() { throw new AssertionError("begin failure must prevent revoke"); }
        };
        fixture.vault.session.attachRevocation(fixture.vault.session.epoch(), owner);
        fixture.invalidation.failure = new UnsatisfiedLinkError("fixture begin failure");
        int before = fixture.invalidation.begins.get();
        assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
        fixture.vault.cleanupInvalidatedContexts();
        assertSame(owner, field(fixture.vault.session, "revocation"));
        assertSame(operation, ((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
        assertEquals(before + 1, fixture.invalidation.begins.get());
    }

    @Test public void statusCleanupRetainsOperationsUntilKnownUnstartedRevocationIsRetried() throws Exception {
        Fixture fixture = new Fixture();
        VaultOperation operation = new TotpVaultAdapter(fixture.vault).request("totp1");
        fixture.invalidation.result = -1;
        assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
        fixture.vault.cleanupInvalidatedContexts();
        assertSame(operation, ((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
        fixture.invalidation.result = 0;
        fixture.vault.session.lock();
        fixture.vault.cleanupInvalidatedContexts();
        assertNull(((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
    }

    @Test(timeout = 30000) public void statusCleanupWaitsForAnInFlightFailedRevocation() throws Exception {
        Fixture fixture = new Fixture();
        VaultOperation operation = new TotpVaultAdapter(fixture.vault).request("totp1");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread cleanup = new Thread(() -> {
            started.countDown();
            try { fixture.vault.cleanupInvalidatedContexts(); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        fixture.invalidation.failure = new UnsatisfiedLinkError("fixture begin failure");
        synchronized (fixture.vault.session) {
            cleanup.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cleanup.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.BLOCKED, cleanup.getState());
            try { fixture.vault.session.lock(); fail("fixture failure must be observed"); }
            catch (UnsatisfiedLinkError expected) { }
        }
        cleanup.join(10_000);
        assertFalse(cleanup.isAlive());
        assertNull(failure.get());
        assertSame(operation, ((Map<?, ?>)field(fixture.vault, "operations")).get(operation.requestId));
    }

    @Test public void statusCleanupSamplesItsClockWithoutVaultOrSessionMonitor() throws Exception {
        Fixture fixture = new Fixture();
        set(fixture.vault, "clock", (VaultSession.Clock) () -> {
            assertFalse(Thread.holdsLock(fixture.vault));
            assertFalse(Thread.holdsLock(fixture.vault.session));
            return 1_000;
        });
        fixture.vault.cleanupInvalidatedContexts();
        assertSame(fixture.preview, field(fixture.vault, "preview"));
    }

    @Test public void previewCloseFailureDoesNotReplaceTheUncertainResultOrNativeFailure() throws Exception {
        Fixture fixture = new Fixture();
        VaultManifest original = fixture.preview.source.manifest;
        set(fixture.preview.source, "manifest", null); // Fail only after the Java secret arrays are wiped.
        Error nativeFailure = new UnsatisfiedLinkError("fixture begin failure");
        fixture.invalidation.failure = nativeFailure;
        try {
            try { fixture.vault.lockForUncertainSshResult(); fail("injected failures must be reported"); }
            catch (UnsatisfiedLinkError failure) {
                assertSame(nativeFailure, failure);
                assertEquals(1, failure.getSuppressed().length);
                assertTrue(failure.getSuppressed()[0] instanceof NullPointerException);
            }
            assertWiped(fixture.preview.source.key);
            assertWiped(fixture.preview.source.plaintext);
            assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
        } finally {
            set(fixture.preview.source, "manifest", original);
            fixture.preview.close();
        }
    }

    @Test public void reentrantUncertainResultDoesNotEraseAnImportStillUsingItsPreview() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger callbacks = new AtomicInteger();
        fixture.store.afterGuard = () -> {
            callbacks.incrementAndGet();
            assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
            assertNotWiped(fixture.preview.source.key);
            assertNotWiped(fixture.preview.source.plaintext);
        };
        fixture.vault.importCommit(fixture.preview.id, false, () -> { });
        assertEquals(1, callbacks.get());
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
        assertNull(field(fixture.vault, "preview"));
    }

    @Test public void reentrantCleanupDefersPreviewRetirementToTheImportOwner() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.afterGuard = () -> {
            assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
            fixture.vault.cleanupInvalidatedContexts();
            assertNotWiped(fixture.preview.source.key);
            assertNotWiped(fixture.preview.source.plaintext);
        };
        fixture.vault.importCommit(fixture.preview.id, false, () -> { });
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
        assertNull(field(fixture.vault, "preview"));
    }

    @Test public void reentrantPreviewCannotReplaceAPreviewUsedByImporter() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<String> rejected = new AtomicReference<>();
        fixture.store.afterGuard = () -> {
            try {
                fixture.vault.preview(envelope, PASSWORD, PASSWORD, "reentrant-preview", () -> { });
                fail("an active import must retain its preview");
            } catch (VaultException failure) { rejected.set(failure.code); }
            assertNotWiped(fixture.preview.source.key);
            assertNotWiped(fixture.preview.source.plaintext);
        };
        fixture.vault.importCommit(fixture.preview.id, false, () -> { });
        assertEquals("BUSY", rejected.get());
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
    }

    @Test public void nestedImportCannotConsumeTheSamePreview() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<String> rejected = new AtomicReference<>();
        fixture.store.afterGuard = () -> {
            fixture.store.afterGuard = null;
            try {
                fixture.vault.importCommit(fixture.preview.id, false, () -> { });
                fail("the same preview cannot be imported recursively");
            } catch (VaultException failure) { rejected.set(failure.code); }
            assertNotWiped(fixture.preview.source.key);
            assertNotWiped(fixture.preview.source.plaintext);
        };
        fixture.vault.importCommit(fixture.preview.id, false, () -> { });
        assertEquals("BUSY", rejected.get());
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
    }

    @Test(timeout = 30000) public void anotherThreadsImportKeepsItsPreviewUntilItsOwnFinally() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch enteredStore = new CountDownLatch(1), finishStore = new CountDownLatch(1);
        CountDownLatch resultReturned = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        fixture.store.afterGuard = () -> { enteredStore.countDown(); waitFor(finishStore); };
        Thread importer = new Thread(() -> {
            try { fixture.vault.importCommit(fixture.preview.id, false, () -> { }); }
            catch (Throwable failure) { failed.set(failure); }
        });
        Thread locker = new Thread(() -> {
            try { result.set(uncertain(fixture.vault)); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
            finally { resultReturned.countDown(); }
        });
        importer.start();
        try {
            assertTrue(enteredStore.await(10, TimeUnit.SECONDS));
            locker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fixture.vault.session.unlocked() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(fixture.vault.session.unlocked());
            assertFalse("result waits for the import owner", resultReturned.await(100, TimeUnit.MILLISECONDS));
            assertNotWiped(fixture.preview.source.key);
            assertNotWiped(fixture.preview.source.plaintext);
        } finally {
            finishStore.countDown();
            importer.join(10_000);
            locker.join(10_000);
        }
        assertNull(failed.get());
        assertFalse(importer.isAlive());
        assertFalse(locker.isAlive());
        assertEquals("VAULT_COMMIT_UNCERTAIN", result.get());
        assertWiped(fixture.preview.source.key);
        assertWiped(fixture.preview.source.plaintext);
    }

    @Test(timeout = 30000) public void previewUnderConstructionRetainsItsOwnLoanUntilTheWorkerExits() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<byte[]> loan = new AtomicReference<>();
        AtomicReference<Throwable> workerResult = new AtomicReference<>();
        fixture.vault.attachSshValidator(manifest -> {
            loan.set(manifest.files.get(0).content);
            entered.countDown();
            waitFor(release);
        });
        Thread worker = new Thread(() -> {
            try {
                fixture.vault.preview(sshEnvelope, PASSWORD, PASSWORD, "constructing", () -> { });
                workerResult.set(new AssertionError("invalidated preview was published"));
            } catch (Throwable failure) { workerResult.set(failure); }
        });
        worker.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertNotWiped(loan.get());
            assertEquals("VAULT_COMMIT_UNCERTAIN", uncertain(fixture.vault));
            assertWiped(fixture.preview.source.key);
            assertNotWiped(loan.get());
        } finally {
            release.countDown();
            worker.join(10_000);
        }
        assertFalse(worker.isAlive());
        assertTrue(workerResult.get() instanceof VaultException);
        assertEquals("VAULT_CHANGED", ((VaultException)workerResult.get()).code);
        assertWiped(loan.get());
    }

    @Test(timeout = 30000) public void aLaterPreviewIsNotRetiredByAnEarlierUncertainResult() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch monitorHeld = new CountDownLatch(1), replace = new CountDownLatch(1);
        AtomicReference<AppSecrets.ImportPreview> newer = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        Thread publisher = new Thread(() -> {
            try {
                synchronized (fixture.vault) {
                    monitorHeld.countDown();
                    waitFor(replace);
                    fixture.vault.unlock(PASSWORD, () -> { });
                    newer.set(fixture.vault.preview(envelope, PASSWORD, PASSWORD, "new-preview", () -> { }));
                }
            } catch (Throwable failure) { failed.set(failure); }
        });
        Thread locker = new Thread(() -> {
            try { result.set(uncertain(fixture.vault)); }
            catch (Throwable failure) { failed.compareAndSet(null, failure); }
        });
        publisher.start();
        try {
            assertTrue(monitorHeld.await(10, TimeUnit.SECONDS));
            locker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fixture.vault.session.unlocked() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(fixture.vault.session.unlocked());
        } finally {
            replace.countDown();
            publisher.join(20_000);
            locker.join(20_000);
        }
        assertNull(failed.get());
        assertFalse(publisher.isAlive());
        assertFalse(locker.isAlive());
        assertEquals("VAULT_COMMIT_UNCERTAIN", result.get());
        assertNotNull(newer.get());
        assertSame(newer.get(), field(fixture.vault, "preview"));
        assertTrue(fixture.vault.session.unlocked());
        assertWiped(fixture.preview.source.key);
        assertNotWiped(newer.get().source.key);
        assertNotWiped(newer.get().source.plaintext);
        fixture.vault.cancelPreview(newer.get().id);
    }
}
