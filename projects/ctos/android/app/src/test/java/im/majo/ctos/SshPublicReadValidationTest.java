package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Encrypted source and fake final-attach evidence; no JNI, device or production validator. */
public final class SshPublicReadValidationTest {
    private static final byte[] KEY = new byte[32];
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private VaultRequestPhase phase;
    private Runnable afterPublish;
    private Runnable beforePublish;
    private static final class Clock implements VaultSession.Clock {
        volatile long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        Runnable onRead;
        int reads;
        @Override public byte[] read() {
            reads++;
            if (onRead != null) onRead.run();
            return bytes == null ? null : bytes.clone();
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException { guard.beforeCommit(); bytes = next.clone(); }
    }
    private interface Action { void run() throws Exception; }
    private class Lease implements VaultSession.Revocation {
        volatile boolean active = true;
        int revokes;
        @Override public boolean active() { return active; }
        @Override public void revoke() {
            // Existing VaultSession.lock invokes its exact revoker under session; helper failure cleanup must not.
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
            assertFalse(Thread.holdsLock(vault.publicationLock)); active = false; revokes++;
        }
    }

    @Before public void setup() throws Exception {
        clock = new Clock(); store = new MemoryStore(); phase = new VaultRequestPhase(); afterPublish = null; beforePublish = null;
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "password",
                "target:test@example.invalid:22", "Synthetic", "", 0, 0, new byte[] {9, 8, 7});
        VaultManifest.FileObject root = file(VaultManifest.FileType.CONFIG, "config", new byte[] {1, 2},
                List.of("known_hosts", "key"));
        VaultManifest.FileObject hosts = file(VaultManifest.FileType.KNOWN_HOSTS, "known_hosts", new byte[] {3, 4}, List.of());
        VaultManifest.FileObject key = file(VaultManifest.FileType.PRIVATE_KEY, "key", new byte[] {5, 6}, List.of());
        VaultManifest.FileObject other = file(VaultManifest.FileType.PUBLIC_KEY, "other", new byte[] {7, 8}, List.of());
        try (VaultManifest manifest = new VaultManifest(List.of(entry), List.of(root, hosts, key, other))) {
            byte[] plain = manifest.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.session.unlock(KEY);
        vault.attachSshValidator(loan -> { });
    }
    private static VaultManifest.FileObject file(VaultManifest.FileType type, String path, byte[] content,
            List<String> references) {
        return new VaultManifest.FileObject(type, path, path, path, content, references);
    }
    private AppSecrets.EffectGuard origin() {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                assertFalse(Thread.holdsLock(vault.session)); assertFalse(Thread.holdsLock(vault.publicationLock));
                if (beforePublish != null) beforePublish.run();
                phase.publish(() -> { check(); publication.check(); });
                if (afterPublish != null) afterPublish.run();
            }
        };
    }
    private AppSecrets.SshPublicReadContext context() throws VaultException {
        return vault.newSshPublicReadContext(vault.session.epoch(), 900, origin());
    }
    private VaultOperation admit(AppSecrets.SshPublicReadContext context) throws VaultException {
        return vault.beginSshFiles("config", "connection", context);
    }
    private void read(VaultOperation operation) throws VaultException {
        vault.sshFileSnapshot(operation, "config", "connection", snapshot -> {
            detached(); assertEquals(3, snapshot.metadata.size()); assertEquals(2, snapshot.publicFiles.size());
            assertEquals("config", snapshot.publicFiles.get(0).path);
            assertEquals("known_hosts", snapshot.publicFiles.get(1).path);
            assertArrayEquals(new byte[] {1, 2}, snapshot.publicFiles.get(0).content);
            assertArrayEquals(new byte[] {3, 4}, snapshot.publicFiles.get(1).content);
            assertEquals(VaultManifest.FileType.PRIVATE_KEY, snapshot.metadata.get(2).type);
        });
    }
    private void detached() {
        assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
        assertFalse(Thread.holdsLock(vault.session)); assertFalse(Thread.holdsLock(vault.publicationLock));
    }
    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private byte[][] sourceBuffers() {
        Object work = field(vault, "sshPublicReadWork"); assertNotNull(work);
        return new byte[][] {(byte[]) field(work, "key"), (byte[]) field(work, "ciphertext")};
    }
    private static void wiped(VaultManifest loan) {
        assertNotNull(loan);
        for (VaultManifest.Entry entry : loan.entries) assertArrayEquals(new byte[entry.secret.length], entry.secret);
        for (VaultManifest.FileObject file : loan.files) assertArrayEquals(new byte[file.content.length], file.content);
    }
    private static void wiped(byte[][] buffers) {
        assertNotNull(buffers);
        for (byte[] bytes : buffers) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static Thread start(Action action, AtomicReference<Throwable> outcome) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable failure) { outcome.set(failure); } });
        thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) throws VaultException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new VaultException("VAULT_CANCELLED", "Interrupted"); }
    }
    private void invalidate(int kind, AppSecrets.SshPublicReadContext context, VaultOperation operation,
            AppSecrets.SshContentValidator validator) {
        switch (kind) {
            case 0: phase.cancel(); break;
            case 1: context.cancel(); break;
            case 2: vault.session.lock(); break;
            case 3: vault.session.foreground(false); break;
            case 4: clock.now = 900; break;
            case 5: vault.attachSshValidator(validator); break;
            case 6: vault.attachSshValidator(null); break;
            case 7: vault.contentRevision++; break;
            case 8: store.bytes[store.bytes.length - 1] ^= 1; break;
            case 9: vault.cancelOperation(operation); break;
            default: throw new AssertionError();
        }
    }
    private static String invalidationCode(int kind) {
        return kind <= 1 || kind == 9 ? "VAULT_CANCELLED" : kind == 3 ? "VAULT_FOREGROUND_REQUIRED"
                : kind == 4 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
    }

    @Test public void pausedAdmissionAndEffectAllowCancellationLockAndSourceRegistrationInvalidation() throws Exception {
        for (boolean effect : new boolean[] {false, true}) for (int kind = 0; kind < 10; kind++) {
            if (!effect && kind == 9) continue;
            setup(); final int invalidation = kind;
            AtomicBoolean armed = new AtomicBoolean(); AtomicReference<VaultManifest> retained = new AtomicReference<>();
            AtomicReference<byte[][]> captured = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AppSecrets.SshContentValidator validator = loan -> {
                detached();
                if (armed.get()) { retained.set(loan); captured.set(sourceBuffers()); entered.countDown(); await(release); }
            };
            vault.attachSshValidator(validator);
            AppSecrets.SshPublicReadContext context = context(); VaultOperation operation = effect ? admit(context) : null;
            armed.set(true); AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> { if (effect) read(operation); else admit(context); }, outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> mutatorError = new AtomicReference<>(); CountDownLatch changed = new CountDownLatch(1);
            Thread mutator = start(() -> {
                invalidate(invalidation, context, operation, validator);
                synchronized (vault) { changed.countDown(); }
            }, mutatorError);
            try { assertTrue("Invalidation blocked behind validator", changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); mutator.join(5000); }
            assertFalse(worker.isAlive()); assertFalse(mutator.isAlive()); assertNull(mutatorError.get());
            assertTrue(outcome.get() instanceof VaultException);
            assertEquals(invalidationCode(kind), ((VaultException) outcome.get()).code);
            wiped(retained.get()); wiped(captured.get());
            if (effect) { assertTrue(operation.consumed); rejects(kind == 1 || kind == 9 ? "VAULT_CANCELLED" : "VAULT_OPERATION", () -> read(operation)); }
        }
    }

    @Test public void validatorLoanIsIndependentAndAllThrowableFailuresWipeAndRelease() throws Exception {
        for (boolean effect : new boolean[] {false, true}) for (int kind = 0; kind < 5; kind++) {
            setup(); final int problem = kind; AtomicBoolean armed = new AtomicBoolean();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); AtomicReference<byte[][]> captured = new AtomicReference<>();
            vault.attachSshValidator(loan -> {
                detached(); if (!armed.get()) return; retained.set(loan); captured.set(sourceBuffers());
                switch (problem) {
                    case 0: loan.files.get(0).content[0] ^= 1; break;
                    case 1: loan.close(); break;
                    case 2: throw new VaultException("FIXTURE", "Validator");
                    case 3: throw new IllegalStateException("Validator");
                    case 4: throw new AssertionError("Validator");
                    default: throw new AssertionError();
                }
            });
            AppSecrets.SshPublicReadContext context = context(); VaultOperation operation = effect ? admit(context) : null;
            armed.set(true); byte[] before = store.bytes.clone(); Throwable failure = null;
            try { if (effect) read(operation); else admit(context); } catch (Throwable error) { failure = error; }
            assertNotNull(failure);
            if (kind <= 2) assertTrue(failure instanceof VaultException);
            if (kind == 3) assertTrue(failure instanceof IllegalStateException);
            if (kind == 4) assertTrue(failure instanceof AssertionError);
            wiped(retained.get()); wiped(captured.get()); assertArrayEquals(before, store.bytes);
            armed.set(false); read(admit(context()));
        }
    }

    @Test public void contextCreationIsMetadataOnlyAndOriginalBudgetNeverRenews() throws Exception {
        long epoch = vault.session.epoch(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread holder = start(() -> { synchronized (vault) { synchronized (vault.session) { entered.countDown(); await(release); } } }, outcome);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<AppSecrets.SshPublicReadContext> context = new AtomicReference<>();
        Thread creator = start(() -> { context.set(vault.newSshPublicReadContext(epoch, 80, origin())); done.countDown(); }, outcome);
        try { assertTrue("Context waited for core/session", done.await(2, TimeUnit.SECONDS)); }
        finally { release.countDown(); holder.join(5000); creator.join(5000); }
        assertNull(outcome.get()); assertEquals(0, store.reads); clock.now = 80;
        rejects("VAULT_EXPIRED", () -> admit(context.get())); clock.now = 79;
        VaultOperation operation = admit(vault.newSshPublicReadContext(epoch, 80, origin()));
        assertEquals(80, operation.deadline); clock.now = 80; rejects("VAULT_EXPIRED", () -> read(operation));
        rejects("VAULT_OPERATION", () -> read(operation)); clock.now = 0;
        vault.attachSshValidator(loan -> clock.now = 120000);
        rejects("VAULT_EXPIRED", () -> vault.beginSshFiles("config", "connection"));
    }

    @Test public void wrongOwnerBindingPurposeReplayAndPrivateRootNeverGrantAuthority() throws Exception {
        AppSecrets foreign = new AppSecrets(store, clock); AppSecrets.SshPublicReadContext context = context();
        rejects("VAULT_OPERATION", () -> foreign.beginSshFiles("config", "connection", context));
        VaultOperation operation = admit(context);
        rejects("VAULT_OPERATION", () -> admit(context));
        rejects("VAULT_OPERATION", () -> foreign.sshFiles(operation, "config", "connection", files -> fail()));
        rejects("VAULT_OPERATION", () -> vault.sshFiles(operation, "known_hosts", "connection", files -> fail()));
        rejects("VAULT_OPERATION", () -> vault.sshFiles(operation, "config", "wrong", files -> fail()));
        rejects("VAULT_OPERATION", () -> vault.totpList(operation, 59)); assertFalse(operation.consumed); read(operation);
        rejects("VAULT_OPERATION", () -> read(operation)); vault.cancelOperation(operation);
        rejects("VAULT_CANCELLED", () -> read(operation));
        rejects("VAULT_OPERATION", () -> vault.beginSshFiles("key", "connection"));
        rejects("VAULT_OPERATION", () -> vault.beginSshFiles("missing", "connection"));
    }

    @Test public void missingOrReplacedRegistrationCannotBeSubstituted() throws Exception {
        vault.attachSshValidator(null); AppSecrets.SshPublicReadContext missing = context();
        rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> admit(missing));
        AppSecrets.SshContentValidator validator = loan -> fail("Stale provider called");
        vault.attachSshValidator(validator); AppSecrets.SshPublicReadContext stale = context();
        vault.attachSshValidator(validator); rejects("VAULT_CHANGED", () -> admit(stale));
        vault.attachSshValidator(loan -> { }); VaultOperation operation = admit(context());
        vault.attachSshValidator(loan -> { }); rejects("VAULT_CHANGED", () -> read(operation));
        rejects("VAULT_OPERATION", () -> read(operation));
    }

    @Test public void busyEffectIsNonconsumingAndSlotRemainsHeldThroughReceiverCleanup() throws Exception {
        VaultOperation ready = admit(context()); VaultOperation worker = admit(context());
        AtomicReference<byte[]> body = new AtomicReference<>(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread reader = start(() -> vault.sshFileSnapshot(worker, "config", "connection", snapshot -> {
            detached(); body.set(snapshot.publicFiles.get(0).content); entered.countDown(); await(release);
        }), outcome);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            rejects("BUSY", () -> read(ready)); assertFalse(ready.consumed);
            AppSecrets.SshPublicReadContext busy = context(); rejects("BUSY", () -> admit(busy));
            rejects("VAULT_OPERATION", () -> admit(busy)); assertEquals(1, body.get()[0]);
        } finally { release.countDown(); reader.join(5000); }
        assertNull(outcome.get()); assertArrayEquals(new byte[2], body.get()); read(ready);
    }

    @Test public void finalRawStoreAndPublicationHooksRejectStaleAuthority() throws Exception {
        for (boolean effect : new boolean[] {false, true}) for (boolean publication : new boolean[] {false, true}) {
            for (int kind = 0; kind < 9; kind++) {
                // A raw external disk change after the final comparison is outside core's store serialization.
                if (publication && kind == 8) continue;
                setup(); final int invalidation = kind; AppSecrets.SshContentValidator validator = loan -> { };
                vault.attachSshValidator(validator); AppSecrets.SshPublicReadContext context = context();
                VaultOperation operation = effect ? admit(context) : null;
                Runnable invalidate = () -> invalidate(invalidation, context, operation, validator);
                if (publication) afterPublish = invalidate;
                else { AtomicInteger reads = new AtomicInteger(); store.onRead = () -> { if (reads.incrementAndGet() == 2) invalidate.run(); }; }
                rejects(invalidationCode(kind), () -> { if (effect) read(operation); else admit(context); });
                assertNull(field(vault, "sshPublicReadWork"));
                if (effect) assertTrue(operation.consumed);
            }
        }
    }

    @Test public void provisionalIssuanceAndClaimHooksThrowingAfterCallbackNeverLeakAuthority() throws Exception {
        for (boolean effect : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
            setup(); AppSecrets.SshPublicReadContext context = context(); VaultOperation operation = effect ? admit(context) : null;
            afterPublish = () -> { if (error) throw new AssertionError("Publication"); throw new IllegalStateException("Publication"); };
            try { if (effect) read(operation); else admit(context); fail(); }
            catch (AssertionError failure) { assertTrue(error); assertEquals("Publication", failure.getMessage()); }
            catch (IllegalStateException failure) { assertFalse(error); }
            afterPublish = null; assertNull(field(vault, "sshPublicReadWork"));
            if (effect) rejects("VAULT_OPERATION", () -> read(operation));
            read(admit(context()));
        }
        for (int i = 0; i < 70; i++) {
            AppSecrets.SshPublicReadContext cancelled = context(); afterPublish = cancelled::cancel;
            rejects("VAULT_CANCELLED", () -> admit(cancelled));
        }
        afterPublish = null; read(admit(context()));
    }

    @Test public void receiverRunsOutsideAllLocksAndAllThrowableExitsWipeBorrowedBodies() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            setup(); final int problem = kind; AtomicReference<SshFileSnapshot> retained = new AtomicReference<>();
            VaultOperation operation = admit(context());
            Throwable failure = null;
            try {
                vault.sshFileSnapshot(operation, "config", "connection", snapshot -> {
                    detached(); retained.set(snapshot);
                    try { rejects("BUSY", () -> admit(context())); } catch (Exception error) { throw new AssertionError(error); }
                    if (problem == 1) throw new VaultException("FIXTURE", "Receiver");
                    if (problem == 2) throw new IllegalStateException("Receiver");
                    if (problem == 3) throw new AssertionError("Receiver");
                });
            } catch (Throwable error) { failure = error; }
            if (kind == 0) assertNull(failure); else assertNotNull(failure);
            for (VaultManifest.FileObject file : retained.get().publicFiles)
                assertArrayEquals(new byte[file.content.length], file.content);
            rejects("VAULT_OPERATION", () -> read(operation)); read(admit(context()));
        }
    }

    @Test public void cancelAfterPublicClaimNeverConcurrentWipesReceiverAndRejectsReturn() throws Exception {
        AppSecrets.SshPublicReadContext context = context(); VaultOperation operation = admit(context);
        AtomicReference<byte[]> body = new AtomicReference<>(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = start(() -> vault.sshFiles(operation, "config", "connection", files -> {
            detached(); body.set(files.get(0).content); entered.countDown(); await(release); assertEquals(1, body.get()[0]);
        }), outcome);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try { context.cancel(); assertEquals(1, body.get()[0]); }
        finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(outcome.get() instanceof VaultException);
        assertEquals("VAULT_CANCELLED", ((VaultException) outcome.get()).code); assertArrayEquals(new byte[2], body.get());
    }

    @Test public void finalAttachRejectsEveryInvalidationAndWipesTemporaryLeaseOutsideLocks() throws Exception {
        for (boolean publication : new boolean[] {false, true}) for (int kind = 0; kind < 10; kind++) {
            if (publication && kind == 8) continue;
            setup(); final int invalidation = kind; AppSecrets.SshContentValidator validator = loan -> { };
            vault.attachSshValidator(validator); AppSecrets.SshPublicReadContext context = context();
            VaultOperation operation = admit(context); Lease lease = new Lease();
            try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
                if (publication) afterPublish = () -> invalidate(invalidation, context, operation, validator);
                else invalidate(kind, context, operation, validator);
                rejects(invalidationCode(kind), () -> vault.attachSshPublicRead(prepared, lease));
                if ((Boolean) field(prepared, "attachAttempted")) {
                    assertFalse("Accepted attach failure left an active local view", lease.active());
                } else if (lease.active()) lease.revoke(); // Pre-acceptance failures remain caller-owned.
                assertFalse(lease.active()); assertTrue(lease.revokes > 0);
            }
            assertNull(field(vault, "sshPublicReadWork"));
        }
    }

    @Test public void finalAttachIsOnceOwnedAndDoesNotReplaceOrRevokeAnotherActiveView() throws Exception {
        Lease existing = new Lease(); vault.session.attachRevocation(vault.session.epoch(), existing);
        VaultOperation operation = admit(context()); Lease temporary = new Lease();
        try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
            rejects("BUSY", () -> vault.attachSshPublicRead(prepared, temporary));
            assertFalse(temporary.active()); assertTrue(existing.active()); assertEquals(0, existing.revokes);
        }
        existing.revoke();
        AppSecrets.SshPublicReadContext context = context(); VaultOperation owner = admit(context); Lease lease = new Lease();
        AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(owner, "config", "connection");
        vault.attachSshPublicRead(prepared, lease); assertTrue(lease.active());
        rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(prepared, lease));
        assertTrue(lease.active()); assertEquals(0, lease.revokes);
        Lease replay = new Lease(); rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(prepared, replay));
        assertTrue(replay.active()); replay.revoke(); assertTrue(lease.active()); prepared.close();
        Lease afterCleanup = new Lease(); rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(prepared, afterCleanup));
        assertTrue(afterCleanup.active()); afterCleanup.revoke(); assertTrue(lease.active());
        context.cancel(); assertFalse(lease.active());
    }

    @Test public void finalAttachPostHookThrowableRevokesExactProvisionalLease() throws Exception {
        for (boolean error : new boolean[] {false, true}) {
            setup(); VaultOperation operation = admit(context()); Lease lease = new Lease();
            try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
                afterPublish = () -> { if (error) throw new AssertionError("Attach"); throw new IllegalStateException("Attach"); };
                try { vault.attachSshPublicRead(prepared, lease); fail(); }
                catch (AssertionError failure) { assertTrue(error); assertEquals("Attach", failure.getMessage()); }
                catch (IllegalStateException failure) { assertFalse(error); }
                assertFalse(lease.active()); assertTrue(operation.cancelled);
            }
        }
    }

    @Test public void rejectedNullForeignClosedAndAlreadyAttachedArgumentsKeepTheirExistingOwner() throws Exception {
        Lease existing = new Lease(); vault.session.attachRevocation(vault.session.epoch(), existing);
        AppSecrets foreign = new AppSecrets(store, clock);
        AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(admit(context()), "config", "connection");
        try {
            rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(null, existing));
            rejects("VAULT_OPERATION", () -> foreign.attachSshPublicRead(prepared, existing));
            rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(prepared, existing));
            assertTrue(existing.active()); assertEquals(0, existing.revokes);
        } finally { prepared.close(); }
        rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(prepared, existing));
        assertTrue(existing.active()); assertEquals(0, existing.revokes);
        assertSame(existing, vault.session.attachedRevocation(vault.session.epoch()));
    }

    @Test public void cancellationAfterOperationAndSessionBindRevokesWithoutWaitingForFinalClaim() throws Exception {
        AppSecrets.SshPublicReadContext context = context(); VaultOperation operation = admit(context);
        CountDownLatch bound = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Lease lease = new Lease() {
            @Override public boolean active() {
                if (checks.incrementAndGet() == 2) { bound.countDown();
                    try { await(cancelled); } catch (VaultException failure) { throw new AssertionError(failure); }
                }
                return super.active();
            }
        };
        AtomicReference<Throwable> outcome = new AtomicReference<>(), cancelError = new AtomicReference<>();
        try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
            Thread attacher = start(() -> vault.attachSshPublicRead(prepared, lease), outcome);
            assertTrue(bound.await(5, TimeUnit.SECONDS));
            Thread canceller = start(() -> { vault.cancelOperation(operation); cancelled.countDown(); }, cancelError);
            try { assertTrue("Cancellation waited for final attach locks", cancelled.await(2, TimeUnit.SECONDS)); }
            finally { cancelled.countDown(); attacher.join(5000); canceller.join(5000); }
            assertFalse(attacher.isAlive()); assertFalse(canceller.isAlive()); assertNull(cancelError.get());
            assertTrue(outcome.get() instanceof VaultException); assertFalse(lease.active());
            assertTrue(operation.cancelled);
        }
    }

    @Test public void synchronousDeliveryCannotBecomeNativePreparationOrAcquireASecondView() throws Exception {
        VaultOperation operation = admit(context());
        vault.sshFileSnapshot(operation, "config", "connection", snapshot -> {
            detached();
            try {
                rejects("VAULT_OPERATION", () -> vault.prepareSshPublicRead(operation, "config", "connection"));
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertNull(field(vault.session, "revocation"));
        });
    }

    @Test public void sessionLockBeforeFinalAttachClaimReturnsWithoutWaitingAndPreventsPublication() throws Exception {
        CountDownLatch claiming = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        AppSecrets.EffectGuard guard = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                if (publications.incrementAndGet() == 2) { detached(); claiming.countDown(); await(release); }
                phase.publish(publication);
            }
        };
        VaultOperation operation = admit(vault.newSshPublicReadContext(vault.session.epoch(), 900, guard));
        Lease lease = new Lease(); AtomicReference<Throwable> outcome = new AtomicReference<>(), lockError = new AtomicReference<>();
        try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
            Thread attacher = start(() -> vault.attachSshPublicRead(prepared, lease), outcome);
            assertTrue(claiming.await(5, TimeUnit.SECONDS)); CountDownLatch locked = new CountDownLatch(1);
            Thread locker = start(() -> { vault.session.lock(); locked.countDown(); }, lockError);
            try { assertTrue("Final attach preparation held session/core locks", locked.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); attacher.join(5000); locker.join(5000); }
            assertNull(lockError.get()); assertFalse(attacher.isAlive()); assertFalse(locker.isAlive());
            assertTrue(outcome.get() instanceof VaultException);
            assertEquals("VAULT_CHANGED", ((VaultException) outcome.get()).code); assertFalse(lease.active());
        }
    }

    @Test public void reentrantSecondAttachCannotConsumeOrRevokeFirstLocalOwner() throws Exception {
        VaultOperation operation = admit(context()); Lease second = new Lease();
        AtomicReference<AppSecrets.SshPublicReadPreparation> preparedRef = new AtomicReference<>();
        AtomicBoolean reentered = new AtomicBoolean();
        Lease first = new Lease() {
            @Override public boolean active() {
                if (reentered.compareAndSet(false, true)) {
                    try { rejects("VAULT_OPERATION", () -> vault.attachSshPublicRead(preparedRef.get(), second)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return super.active();
            }
        };
        try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
            preparedRef.set(prepared); vault.attachSshPublicRead(prepared, first);
            assertTrue(first.active()); assertTrue(second.active()); assertFalse(operation.cancelled);
        }
        second.revoke(); vault.cancelOperation(operation); assertFalse(first.active());
    }

    @Test public void mutationBetweenLastCoreReadAndPublicationFenceRejectsPublicAndNativeDelivery() throws Exception {
        for (boolean nativeLease : new boolean[] {false, true}) {
            setup(); VaultOperation operation = admit(context());
            beforePublish = () -> {
                detached();
                synchronized (vault.publicationLock) { vault.contentRevision++; }
            };
            if (nativeLease) {
                Lease lease = new Lease();
                try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(operation, "config", "connection")) {
                    rejects("VAULT_CHANGED", () -> vault.attachSshPublicRead(prepared, lease));
                    assertTrue((Boolean) field(prepared, "attachAttempted"));
                    assertFalse("Gap invalidation did not clean accepted native view", lease.active());
                }
            } else rejects("VAULT_CHANGED", () -> vault.sshFileSnapshot(operation, "config", "connection",
                    snapshot -> fail("Stale public loan delivered across core-release gap")));
            assertTrue(operation.consumed); assertNull(field(vault, "sshPublicReadWork"));
        }
    }
}
