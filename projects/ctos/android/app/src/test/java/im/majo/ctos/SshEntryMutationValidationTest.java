package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Real encrypted synthetic mutation sources. Does not model Android AtomicFile durability. */
public final class SshEntryMutationValidationTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] SECRET = {6, 7, 8};
    private static final String TARGET = "target:test@example.invalid:22";
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private SshVaultAdapter adapter;
    private VaultRequestPhase phase;
    private Runnable beforePublish, afterPublish, hostAction;
    private AtomicInteger claims;

    private static final class Clock implements VaultSession.Clock {
        volatile long now = 100;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes, quick = {4, 5};
        Runnable onRead, onQuick, beforeClaim, afterCommit;
        int reads, writes;
        boolean committing, ioFailure, uncertain;
        @Override public byte[] read() {
            assertFalse("No AtomicFile read after startWrite", committing);
            reads++; if (onRead != null) onRead.run(); return bytes.clone();
        }
        @Override public byte[] quickMaterial() {
            assertFalse("No wrapper read after startWrite", committing);
            if (onQuick != null) onQuick.run(); return quick.clone();
        }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            committing = true;
            try {
                if (beforeClaim != null) beforeClaim.run();
                guard.beforeCommit();
                if (ioFailure) throw new IOException("Synthetic pre-finish failure");
                bytes = next.clone(); quick = wrapper.clone(); writes++;
                if (afterCommit != null) afterCommit.run();
                if (uncertain) throw new VaultException("VAULT_COMMIT_UNCERTAIN", "Synthetic uncertain finish");
            } finally { committing = false; }
        }
    }
    private interface Action { void run() throws Exception; }

    @Before public void setup() throws Exception {
        clock = new Clock(); store = new MemoryStore(); phase = new VaultRequestPhase(); claims = new AtomicInteger();
        beforePublish = null; afterPublish = null; hostAction = null;
        installSource(true, true);
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.session.unlock(KEY);
        vault.attachSshValidator(loan -> { });
        adapter = new SshVaultAdapter(vault, (connection, target) -> {
            assertEquals("connection", connection); assertEquals(TARGET, target);
            if (hostAction != null) hostAction.run();
        });
    }

    private void installSource(boolean ssh, boolean files) throws Exception {
        VaultManifest.Entry totp = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Issuer:account",
                "Issuer", "SHA1", 6, 30, new byte[20]);
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "password",
                TARGET, "Old label", "", 0, 0, new byte[] {9, 8, 7});
        VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "key", "key", "key-one", new byte[] {1, 2, 3}, List.of());
        try (VaultManifest source = new VaultManifest(ssh ? List.of(totp, password) : List.of(totp),
                files ? List.of(file) : List.of())) {
            byte[] plain = source.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 7, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(password.secret); VaultCodec.wipe(file.content); }
    }

    private AppSecrets.EffectGuard origin() {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "Synthetic cancellation");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                assertFalse(Thread.holdsLock(vault.publicationLock));
                if (beforePublish != null) beforePublish.run();
                phase.publish(() -> { check(); publication.check(); });
                if (afterPublish != null) afterPublish.run();
            }
            @Override public void beforeCommit() throws VaultException {
                assertTrue(store.committing); assertTrue(Thread.holdsLock(vault));
                claims.incrementAndGet(); phase.claimCommit(this);
            }
        };
    }
    private AppSecrets.SshEntryMutationContext context() throws VaultException {
        return adapter.mutationContext(900, origin());
    }
    private VaultOperation admit(boolean delete, AppSecrets.SshEntryMutationContext context) throws VaultException {
        return delete ? adapter.deleteRequest("password", TARGET, context)
                : adapter.saveRequest("password", TARGET, "connection", context);
    }
    private void mutate(boolean delete, VaultOperation operation) throws VaultException {
        mutate(delete, operation, origin());
    }
    private void mutate(boolean delete, VaultOperation operation, AppSecrets.EffectGuard guard) throws VaultException {
        if (delete) adapter.delete(operation, "password", TARGET, guard);
        else adapter.save(operation, "password", TARGET, "connection", "New label", false, SECRET, guard);
    }
    private void detached() {
        assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
        assertFalse(Thread.holdsLock(vault.publicationLock)); assertFalse(Thread.holdsLock(phase));
    }
    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private byte[][] sourceBuffers() {
        Object work = field(vault, "sshEntryMutationWork"); assertNotNull(work);
        return new byte[][] {(byte[]) field(work, "key"), (byte[]) field(work, "ciphertext")};
    }
    private static void wiped(byte[] bytes) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    private static void wiped(VaultManifest loan) {
        for (VaultManifest.Entry entry : loan.entries) wiped(entry.secret);
        for (VaultManifest.FileObject file : loan.files) wiped(file.content);
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
    private void invalidate(int kind, AppSecrets.SshEntryMutationContext context, VaultOperation operation,
            AppSecrets.SshContentValidator validator) {
        switch (kind) {
            case 0: phase.cancel(); break;
            case 1: context.cancel(); break;
            case 2: vault.session.lock(); break;
            case 3: vault.session.foreground(false); break;
            case 4: clock.now = 900; break;
            case 5: vault.attachSshValidator(validator); break;
            case 6: vault.attachSshValidator(null); break;
            case 7: synchronized (vault.publicationLock) { vault.contentRevision++; } break;
            case 8: store.bytes[store.bytes.length - 1] ^= 1; break;
            case 9: vault.cancelOperation(operation); break;
            default: throw new AssertionError();
        }
    }
    private static String invalidationCode(int kind) {
        return kind <= 1 || kind == 9 ? "VAULT_CANCELLED" : kind == 3 ? "VAULT_FOREGROUND_REQUIRED"
                : kind == 4 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
    }

    @Test public void pausedAdmissionSourceAndEffectSourceCandidateStayCancellable() throws Exception {
        for (boolean delete : new boolean[] {false, true}) for (int stage = 0; stage < 3; stage++)
                for (int kind = 0; kind < 10; kind++) {
            if (stage == 0 && kind == 9) continue;
            setup(); final int pauseAt = stage == 2 ? 2 : 1, invalidation = kind;
            AtomicInteger calls = new AtomicInteger(); AtomicBoolean armed = new AtomicBoolean();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); AtomicReference<byte[][]> buffers = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AppSecrets.SshContentValidator validator = loan -> {
                detached();
                if (armed.get() && calls.incrementAndGet() == pauseAt) {
                    retained.set(loan); buffers.set(sourceBuffers()); entered.countDown(); await(release);
                }
            };
            vault.attachSshValidator(validator); AppSecrets.SshEntryMutationContext context = context();
            boolean effect = stage != 0; VaultOperation operation = effect ? admit(delete, context) : null;
            armed.set(true); AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> { if (effect) mutate(delete, operation); else admit(delete, context); }, outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> changeError = new AtomicReference<>(); CountDownLatch changed = new CountDownLatch(1);
            Thread changer = start(() -> {
                invalidate(invalidation, context, operation, validator);
                synchronized (vault) { changed.countDown(); }
            }, changeError);
            try { assertTrue("Invalidation waited for validator", changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); changer.join(5000); }
            assertFalse(worker.isAlive()); assertFalse(changer.isAlive()); assertNull(changeError.get());
            assertTrue(outcome.get() instanceof VaultException);
            assertEquals(invalidationCode(kind), ((VaultException) outcome.get()).code);
            assertEquals(0, store.writes); assertEquals(0, claims.get()); wiped(retained.get());
            for (byte[] bytes : buffers.get()) wiped(bytes);
            if (effect) assertTrue(operation.consumed);
            assertNull(field(vault, "sshEntryMutationWork")); assertArrayEquals(new byte[] {6, 7, 8}, SECRET);
        }
    }

    @Test public void sourceAndCandidateLoansCannotChangeSavedBytesAndAllFailuresWipe() throws Exception {
        for (boolean delete : new boolean[] {false, true}) for (int stage = 0; stage < 3; stage++)
                for (int problem = 0; problem < 5; problem++) {
            setup(); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); AtomicReference<byte[][]> buffers = new AtomicReference<>();
            final int pauseAt = stage == 2 ? 2 : 1, failureKind = problem;
            vault.attachSshValidator(loan -> {
                detached(); if (!armed.get() || calls.incrementAndGet() != pauseAt) return;
                retained.set(loan); buffers.set(sourceBuffers());
                switch (failureKind) {
                    case 0: loan.entries.get(0).secret[0] ^= 1; break;
                    case 1: loan.close(); break;
                    case 2: throw new VaultException("FIXTURE", "Validator");
                    case 3: throw new IllegalStateException("Validator");
                    case 4: throw new AssertionError("Validator");
                    default: throw new AssertionError();
                }
            });
            AppSecrets.SshEntryMutationContext context = context(); boolean effect = stage != 0;
            VaultOperation operation = effect ? admit(delete, context) : null;
            armed.set(true); byte[] original = store.bytes.clone(); Throwable outcome = null;
            try { if (effect) mutate(delete, operation); else admit(delete, context); } catch (Throwable failure) { outcome = failure; }
            assertNotNull(outcome); wiped(retained.get()); for (byte[] bytes : buffers.get()) wiped(bytes);
            assertArrayEquals(original, store.bytes); assertArrayEquals(new byte[] {4, 5}, store.quick); assertEquals(0, store.writes);
            assertNull(field(vault, "sshEntryMutationWork")); armed.set(false); mutate(delete, admit(delete, context()));
        }
    }

    @Test public void candidateOwnsUnchangedEntriesFilesAndReplacementInBothCloseOrders() throws Exception {
        Method builder = AppSecrets.class.getDeclaredMethod("sshEntryCandidate", VaultManifest.class,
                String.class, String.class, VaultManifest.Entry.class); builder.setAccessible(true);
        for (boolean closeSourceFirst : new boolean[] {false, true}) {
            VaultEnvelope envelope = VaultEnvelope.parse(store.bytes); byte[] plain = envelope.decrypt(KEY);
            VaultManifest source = VaultManifest.decode(plain); VaultCodec.wipe(plain);
            VaultManifest.Entry replacement = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                    "password", TARGET, "Replacement", "", 0, 0, SECRET);
            try (VaultManifest candidate = (VaultManifest) builder.invoke(null, source, "password", TARGET, replacement)) {
                for (VaultManifest.Entry entry : candidate.entries)
                    for (VaultManifest.Entry old : source.entries) assertNotSame(old.secret, entry.secret);
                assertNotSame(replacement.secret, candidate.entries.get(1).secret);
                assertNotSame(source.files.get(0).content, candidate.files.get(0).content);
                if (closeSourceFirst) {
                    source.close(); assertArrayEquals(SECRET, candidate.entries.get(1).secret);
                    assertArrayEquals(new byte[] {1, 2, 3}, candidate.files.get(0).content);
                } else {
                    candidate.close(); assertArrayEquals(new byte[] {9, 8, 7}, source.entries.get(1).secret);
                    assertArrayEquals(new byte[] {1, 2, 3}, source.files.get(0).content);
                }
                assertArrayEquals(SECRET, replacement.secret);
            } finally { source.close(); VaultCodec.wipe(replacement.secret); }
        }
    }

    @Test public void firstAndLastSshCandidateAlwaysValidatedAndWrapperAndEnvelopeIdentityPreserved() throws Exception {
        for (boolean delete : new boolean[] {false, true}) {
            setup(); installSource(delete, false); AtomicInteger calls = new AtomicInteger();
            vault.attachSshValidator(loan -> { detached(); calls.incrementAndGet(); });
            VaultEnvelope before = VaultEnvelope.parse(store.bytes);
            mutate(delete, admit(delete, context()));
            // First SSH: source is pure TOTP, so only candidate; last SSH: admission source + effect source + candidate.
            assertEquals(delete ? 3 : 1, calls.get()); assertEquals(1, store.writes); assertEquals(1, claims.get());
            assertArrayEquals(new byte[] {4, 5}, store.quick);
            VaultEnvelope after = VaultEnvelope.parse(store.bytes);
            assertEquals(before.id, after.id); assertEquals(before.generation, after.generation); assertArrayEquals(before.salt, after.salt);
            byte[] plain = after.decrypt(KEY);
            try (VaultManifest result = VaultManifest.decode(plain)) {
                assertEquals(delete ? 1 : 2, result.entries.size()); assertEquals(0, result.files.size());
                assertArrayEquals(new byte[20], result.entries.get(0).secret);
                if (!delete) assertArrayEquals(SECRET, result.entries.get(1).secret);
            } finally { VaultCodec.wipe(plain); }
        }
    }

    @Test public void defaultContextPrecedesHostGateAndExplicitBudgetCannotRenew() throws Exception {
        hostAction = () -> clock.now = 120100;
        rejects("VAULT_EXPIRED", () -> adapter.saveRequest("password", TARGET, "connection"));
        assertEquals(0, store.reads);
        setup(); AppSecrets.SshEntryMutationContext context = adapter.mutationContext(101, origin());
        assertEquals(0, store.reads); VaultOperation operation = admit(false, context);
        assertEquals(101, operation.deadline); clock.now = 101;
        rejects("VAULT_EXPIRED", () -> mutate(false, operation)); assertEquals(0, store.writes);
        rejects("VAULT_OPERATION", () -> admit(false, context));
    }

    @Test public void callerSecretIsCapturedBeforeSlowHostGateAndNeverOwnedByCandidate() throws Exception {
        VaultOperation operation = admit(false, context()); byte[] supplied = SECRET.clone();
        hostAction = () -> supplied[0] = 42;
        adapter.save(operation, "password", TARGET, "connection", "New label", false, supplied, origin());
        assertEquals(42, supplied[0]);
        byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
        try (VaultManifest result = VaultManifest.decode(plain)) {
            assertArrayEquals(SECRET, result.entries.get(1).secret);
            assertArrayEquals(new byte[] {1, 2, 3}, result.files.get(0).content);
        } finally { VaultCodec.wipe(plain); }
    }

    @Test public void wrongBindingsCrossTypeAndReplayCannotConsumeUnrelatedCapability() throws Exception {
        VaultOperation operation = admit(false, context());
        rejects("VAULT_OPERATION", () -> vault.saveSsh(operation, "wrong", TARGET, "connection", "Label", false, SECRET, origin()));
        rejects("VAULT_OPERATION", () -> vault.saveSsh(operation, "password", TARGET, "wrong", "Label", false, SECRET, origin()));
        rejects("VAULT_OPERATION", () -> vault.deleteSsh(operation, "password", TARGET, origin()));
        AppSecrets foreign = new AppSecrets(store, clock);
        rejects("VAULT_OPERATION", () -> foreign.saveSsh(operation, "password", TARGET, "connection", "Label", false, SECRET, origin()));
        assertFalse(operation.consumed); mutate(false, operation);
        rejects("VAULT_OPERATION", () -> mutate(false, operation)); operation.cancel();
        rejects("VAULT_CANCELLED", () -> mutate(false, operation));
        VaultOperation crossType = vault.beginSshSave("totp", TARGET, "connection");
        rejects("VAULT_OPERATION", () -> vault.saveSsh(crossType, "totp", TARGET, "connection", "Label", false, SECRET, origin()));
        assertTrue(crossType.consumed);
        String other = "target:other@example.invalid:22";
        VaultOperation crossTarget = vault.beginSshSave("password", other, "connection");
        rejects("VAULT_OPERATION", () -> vault.saveSsh(crossTarget, "password", other, "connection", "Label", false, SECRET, origin()));
        assertTrue(crossTarget.consumed); assertEquals(1, store.writes);
    }

    @Test public void busyAndRecursiveReadsPreserveUnacceptedOperationAndCloseWorkOnError() throws Exception {
        AtomicBoolean armed = new AtomicBoolean(); AtomicReference<VaultOperation> waiting = new AtomicReference<>();
        AtomicInteger rejected = new AtomicInteger();
        vault.attachSshValidator(loan -> {
            detached(); if (!armed.get()) return;
            try { mutate(true, waiting.get()); fail(); }
            catch (VaultException failure) { assertEquals("BUSY", failure.code); rejected.incrementAndGet(); }
        });
        VaultOperation other = admit(false, context()); waiting.set(admit(true, context()));
        armed.set(true); mutate(false, other); assertEquals(2, rejected.get()); assertFalse(waiting.get().consumed);
        armed.set(false); rejects("VAULT_OPERATION", () -> mutate(true, waiting.get()));
        assertNull(field(vault, "sshEntryMutationWork"));
    }

    @Test public void finalReadWrapperAndBeforeCommitInvalidationsPreventWritesWithoutTransactionRereads() throws Exception {
        for (boolean delete : new boolean[] {false, true}) for (int seam = 0; seam < 4; seam++) {
            setup(); AppSecrets.SshEntryMutationContext context = context(); VaultOperation operation = admit(delete, context);
            if (seam == 0) {
                int finalRead = store.reads + 2;
                store.onRead = () -> { if (store.reads == finalRead) context.cancel(); };
            } else if (seam == 1) store.onQuick = context::cancel;
            else if (seam == 2) store.beforeClaim = context::cancel;
            else store.beforeClaim = () -> { synchronized (vault.publicationLock) { vault.contentRevision++; } };
            rejects(seam == 3 ? "VAULT_CHANGED" : "VAULT_CANCELLED", () -> mutate(delete, operation));
            assertEquals(0, store.writes); assertEquals(0, claims.get()); assertTrue(operation.consumed);
            assertArrayEquals(new byte[] {4, 5}, store.quick); assertNull(field(vault, "sshEntryMutationWork"));
        }
    }

    @Test public void effectOwnsExactlyOneClaimAndPostPersistenceLockCannotResurrectSession() throws Exception {
        for (boolean delete : new boolean[] {false, true}) {
            setup(); AppSecrets.EffectGuard original = origin();
            AppSecrets.SshEntryMutationContext context = adapter.mutationContext(900, original);
            VaultOperation operation = admit(delete, context); assertEquals(0, claims.get());
            store.afterCommit = vault.session::lock;
            mutate(delete, operation, original);
            assertEquals(1, claims.get()); assertTrue(phase.committing()); assertEquals(1, store.writes);
            assertFalse(vault.session.unlocked()); assertArrayEquals(new byte[] {4, 5}, store.quick);
            assertEquals("VAULT_COMMIT_UNCERTAIN", phase.interrupted(new VaultException("VAULT_CANCELLED", "Late")).code);
        }
    }

    @Test public void ioUncertainAndPublicationHookFailuresRetainTheirDistinctContracts() throws Exception {
        for (boolean uncertain : new boolean[] {false, true}) {
            setup(); VaultOperation operation = admit(false, context()); byte[] before = store.bytes.clone();
            store.uncertain = uncertain; store.ioFailure = !uncertain;
            rejects(uncertain ? "VAULT_COMMIT_UNCERTAIN" : "VAULT_IO", () -> mutate(false, operation));
            assertEquals(uncertain ? 1 : 0, store.writes); assertTrue(operation.consumed);
            if (uncertain) assertFalse(vault.session.unlocked()); else assertArrayEquals(before, store.bytes);
            assertArrayEquals(new byte[] {4, 5}, store.quick); assertNull(field(vault, "sshEntryMutationWork"));
        }
        setup(); afterPublish = () -> { throw new AssertionError("Publication failure"); };
        AppSecrets.SshEntryMutationContext context = context();
        try { admit(false, context); fail(); } catch (AssertionError expected) { assertEquals("Publication failure", expected.getMessage()); }
        VaultOperation issued = (VaultOperation) field(context, "issued"); assertNotNull(issued); assertTrue(issued.cancelled);
        assertNull(field(vault, "sshEntryMutationWork")); assertEquals(0, store.writes);
    }
}
