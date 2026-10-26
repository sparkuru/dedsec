package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Synthetic providers exercise ownership/lifecycle only; no SSH semantic admission claim. */
public final class SshPreviewValidationTest {
    private static final char[] PASSWORD = "synthetic preview password".toCharArray();
    private static byte[] source, target, totp;
    private MemoryStore store;
    private AppSecrets vault;
    private FakeClock clock;

    private static final class FakeClock implements VaultSession.Clock {
        volatile long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        Runnable onRead, beforeFinalGuard;
        @Override public byte[] read() {
            byte[] result = bytes == null ? null : bytes.clone();
            Runnable hook = onRead; onRead = null;
            if (hook != null) hook.run();
            return result;
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.check();
            if (beforeFinalGuard != null) beforeFinalGuard.run();
            guard.beforeCommit();
            bytes = next.clone();
        }
    }
    private static final class Pause {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void await() {
            entered.countDown();
            try { assertTrue("worker was never released", release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        void entered() throws Exception { assertTrue("provider was never reached", entered.await(10, TimeUnit.SECONDS)); }
    }
    private interface Checked { void run() throws Exception; }

    @BeforeClass public static void fixtures() throws Exception {
        source = encrypt("source", true); target = encrypt("target", true); totp = encrypt("totp", false);
    }
    @Before public void setup() throws Exception {
        store = new MemoryStore(); store.bytes = target.clone(); clock = new FakeClock();
        vault = new AppSecrets(store, clock); vault.session.foreground(true);
        vault.attachSshValidator(manifest -> { });
        SshRestoreTest.attachSyntheticReviewer(vault);
    }
    private AppSecrets.EffectGuard guard(VaultRequestPhase phase, long deadline) {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (phase.cancelled()) throw new VaultException("VAULT_CANCELLED", "synthetic cancellation");
                if (clock.now >= deadline) throw new VaultException("VAULT_EXPIRED", "synthetic deadline");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                phase.publish(() -> { check(); publication.check(); });
            }
        };
    }
    private AppSecrets.ImportPreview preview(String id) throws VaultException {
        return vault.preview(source, PASSWORD, PASSWORD, id, () -> { });
    }
    private FutureTask<AppSecrets.ImportPreview> startPreview(AppSecrets.EffectGuard guard) {
        return startPreview("paused", guard);
    }
    private FutureTask<AppSecrets.ImportPreview> startPreview(String ownerId, AppSecrets.EffectGuard guard) {
        FutureTask<AppSecrets.ImportPreview> task = new FutureTask<>(() ->
                vault.preview(source, PASSWORD, PASSWORD, ownerId, 1000, guard));
        new Thread(task, "synthetic-preview-owner").start();
        return task;
    }
    private static void failed(FutureTask<?> work, String code) throws Exception {
        try { work.get(10, TimeUnit.SECONDS); fail("unexpected preview success"); }
        catch (ExecutionException failure) {
            assertTrue(failure.getCause().toString(), failure.getCause() instanceof VaultException);
            assertEquals(code, ((VaultException) failure.getCause()).code);
        }
    }
    private static void rejects(String code, Checked action) throws Exception {
        try { action.run(); fail("unexpected success: " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static void wiped(byte[] bytes) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    private void unchanged() { assertArrayEquals(target, store.bytes); }

    @Test public void validatorsSeeBothActualAuthenticatedInputsOutsideLocksAndLoansAreWiped() throws Exception {
        VaultRequestPhase phase = new VaultRequestPhase();
        List<byte[]> bodies = new ArrayList<>(), secrets = new ArrayList<>();
        vault.attachSshValidator(manifest -> {
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
            assertFalse(Thread.holdsLock(vault.session));
            int index = bodies.size();
            assertArrayEquals((index == 0 ? "source" : "target").getBytes(StandardCharsets.UTF_8), manifest.files.get(0).content);
            bodies.add(manifest.files.get(0).content); secrets.add(manifest.entries.get(0).secret);
        });
        vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase));
            assertFalse(Thread.holdsLock(vault.session));
            assertArrayEquals("source".getBytes(StandardCharsets.UTF_8), s.files.get(0).content);
            assertArrayEquals("target".getBytes(StandardCharsets.UTF_8), t.files.get(0).content);
            bodies.add(s.files.get(0).content); bodies.add(t.files.get(0).content);
            return SshRestoreTest.syntheticReview(s, t);
        }, (review, consent) -> { });
        AppSecrets.ImportPreview pending = vault.preview(source, PASSWORD, PASSWORD, "actual", 1000, guard(phase, 1000));
        assertEquals(4, bodies.size()); for (byte[] body : bodies) wiped(body); for (byte[] secret : secrets) wiped(secret);
        assertArrayEquals("source".getBytes(StandardCharsets.UTF_8), pending.source.manifest.files.get(0).content);
        vault.importCommit(pending.id, pending.reviewBinding, true, () -> { });
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertArrayEquals("source".getBytes(StandardCharsets.UTF_8), restored.manifest.files.get(0).content);
        }
    }

    @Test public void validatorBodyAndCredentialMutationOnEitherSideRejectsAndWipes() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            AtomicInteger calls = new AtomicInteger(); byte[][] retained = new byte[1][]; final int variant = mode;
            vault.attachSshValidator(manifest -> {
                if (calls.getAndIncrement() == variant / 2) {
                    retained[0] = variant % 2 == 0 ? manifest.files.get(0).content : manifest.entries.get(0).secret;
                    retained[0][0] ^= 1;
                }
            });
            rejects("VAULT_CONTENT", () -> preview("mutated")); wiped(retained[0]); unchanged();
        }
    }

    @Test public void validatorAndReviewerErrorsWipeLoansAndReleaseInFlightSlot() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            byte[][] retained = new byte[2][]; AtomicInteger calls = new AtomicInteger(); final int variant = stage;
            AssertionError sentinel = new AssertionError("synthetic provider failure");
            vault.attachSshValidator(manifest -> {
                if (calls.getAndIncrement() == variant && variant < 2) {
                    retained[0] = manifest.files.get(0).content; retained[1] = manifest.entries.get(0).secret;
                    throw sentinel;
                }
            });
            vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
                retained[0] = s.files.get(0).content; retained[1] = t.entries.get(0).secret;
                throw sentinel;
            }, (review, consent) -> { });
            try { preview("error"); fail(); } catch (AssertionError failure) { assertSame(sentinel, failure); }
            wiped(retained[0]); wiped(retained[1]); unchanged();
            vault.attachSshValidator(manifest -> { }); SshRestoreTest.attachSyntheticReviewer(vault);
            AppSecrets.ImportPreview retry = preview("retry"); vault.cancelPreview(retry.id);
        }
    }

    @Test public void pausedSourceTargetAndReviewerAllowLockCancelAndRegistrationReplacement() throws Exception {
        for (int stage = 0; stage < 3; stage++) for (int action = 0; action < 4; action++) {
            setup(); Pause pause = new Pause(); VaultRequestPhase phase = new VaultRequestPhase();
            AtomicInteger calls = new AtomicInteger(); final int variant = stage;
            byte[][] loan = new byte[1][];
            AppSecrets.SshContentValidator validator = manifest -> {
                if (calls.getAndIncrement() == variant && variant < 2) {
                    loan[0] = manifest.files.get(0).content; pause.await();
                }
            };
            vault.attachSshValidator(validator);
            AppSecrets.SshRestoreReviewer reviewer = (s, t) -> {
                if (variant == 2) { loan[0] = t.files.get(0).content; pause.await(); }
                return SshRestoreTest.syntheticReview(s, t);
            };
            vault.attachSshRestoreReviewer("synthetic", "1", reviewer, (review, consent) -> { });
            FutureTask<AppSecrets.ImportPreview> work = startPreview(guard(phase, 1000));
            FutureTask<Void> invalidator = null;
            try {
                pause.entered(); final int operation = action;
                invalidator = new FutureTask<>(() -> {
                    if (operation == 0) vault.lock();
                    else if (operation == 1) { phase.cancel(); vault.invalidatePreviewRequest("paused"); }
                    else if (operation == 2) vault.attachSshValidator(validator);
                    else vault.attachSshRestoreReviewer("synthetic", "1", reviewer, (review, consent) -> { });
                    return null;
                });
                new Thread(invalidator, "synthetic-invalidator").start();
                invalidator.get(2, TimeUnit.SECONDS);
                assertFalse("cancellation must not wipe an active parser loan", Arrays.equals(new byte[loan[0].length], loan[0]));
            } finally { pause.release.countDown(); }
            failed(work, action == 1 ? "VAULT_CANCELLED" : "VAULT_CHANGED");
            if (invalidator != null) invalidator.get(2, TimeUnit.SECONDS);
            wiped(loan[0]); unchanged();
        }
    }

    @Test public void directRequestCancellationDoesNotWaitForPausedParser() throws Exception {
        Pause pause = new Pause(); vault.attachSshValidator(manifest -> pause.await());
        FutureTask<AppSecrets.ImportPreview> work = startPreview(() -> { });
        FutureTask<Void> cancellation = new FutureTask<>(() -> { vault.cancelRequest("paused"); return null; });
        try {
            pause.entered(); new Thread(cancellation, "synthetic-direct-cancellation").start();
            cancellation.get(2, TimeUnit.SECONDS);
        }
        finally { pause.release.countDown(); }
        cancellation.get(2, TimeUnit.SECONDS);
        failed(work, "VAULT_CANCELLED"); unchanged();
    }

    @Test public void concurrentAndCallbackReentryPreviewAreBusyWithoutDisturbingOwner() throws Exception {
        Pause pause = new Pause(); AtomicInteger calls = new AtomicInteger();
        vault.attachSshValidator(manifest -> {
            try { rejects("BUSY", () -> preview("paused")); } catch (Exception failure) { throw new AssertionError(failure); }
            if (calls.getAndIncrement() == 0) pause.await();
        });
        FutureTask<AppSecrets.ImportPreview> work = startPreview(() -> { });
        try { pause.entered(); rejects("BUSY", () -> preview("paused")); }
        finally { pause.release.countDown(); }
        AppSecrets.ImportPreview pending = work.get(10, TimeUnit.SECONDS);
        vault.importCommit(pending.id, pending.reviewBinding, true, () -> { });
        assertEquals(2, calls.get());
    }

    @Test public void rejectedOwnersCancellationCannotRevokeAnotherOwnersPausedPreview() throws Exception {
        // Bridges can share an external requestId; only their distinct private owner tokens reach the core.
        String firstOwner = UUID.randomUUID().toString(), rejectedOwner = UUID.randomUUID().toString();
        VaultRequestPhase firstPhase = new VaultRequestPhase(), rejectedPhase = new VaultRequestPhase();
        Pause pause = new Pause(); AtomicInteger calls = new AtomicInteger();
        vault.attachSshValidator(manifest -> { if (calls.getAndIncrement() == 0) pause.await(); });
        FutureTask<AppSecrets.ImportPreview> work = startPreview(firstOwner, guard(firstPhase, 1000));
        FutureTask<Void> cancellation = new FutureTask<>(() -> {
            rejectedPhase.cancel(); vault.cancelRequest(rejectedOwner); return null;
        });
        try {
            pause.entered();
            rejects("BUSY", () -> vault.preview(source, PASSWORD, PASSWORD, rejectedOwner, 1000, guard(rejectedPhase, 1000)));
            new Thread(cancellation, "synthetic-rejected-owner-cancellation").start(); cancellation.get(2, TimeUnit.SECONDS);
        } finally { pause.release.countDown(); }
        AppSecrets.ImportPreview pending = work.get(10, TimeUnit.SECONDS);
        assertEquals(firstOwner, pending.requestId); assertFalse(firstPhase.cancelled());
        AtomicInteger delivered = new AtomicInteger();
        vault.deliverPreview(pending, guard(firstPhase, 1000), delivered::incrementAndGet); assertEquals(1, delivered.get());
        vault.importCommit(pending.id, pending.reviewBinding, true, () -> { });
        assertFalse(Arrays.equals(target, store.bytes));
    }

    @Test public void originalAdmissionDeadlineStopsBeforeTargetAndReviewer() throws Exception {
        AtomicInteger validators = new AtomicInteger(), reviewers = new AtomicInteger();
        vault.attachSshValidator(manifest -> { validators.incrementAndGet(); clock.now = 1000; });
        vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
            reviewers.incrementAndGet(); return SshRestoreTest.syntheticReview(s, t);
        }, (review, consent) -> { });
        rejects("VAULT_EXPIRED", () -> vault.preview(source, PASSWORD, PASSWORD, "deadline", 1000, () -> { }));
        assertEquals(1, validators.get()); assertEquals(0, reviewers.get()); unchanged();
    }

    @Test public void legacyAdmissionDeadlineIsCapturedOnceAndSuccessfulReviewGetsSeparateLifetime() throws Exception {
        vault.attachSshValidator(manifest -> clock.now = 120000);
        rejects("VAULT_EXPIRED", () -> preview("legacy")); unchanged();
        clock.now = 0; vault.attachSshValidator(manifest -> clock.now = 999);
        AppSecrets.ImportPreview pending = vault.preview(source, PASSWORD, PASSWORD, "review", 1000, () -> { });
        assertEquals(120999, pending.deadline);
        clock.now = 1000;
        rejects("VAULT_EXPIRED", () -> vault.deliverPreview(pending, guard(new VaultRequestPhase(), 1000), () -> fail("late UI")));
        vault.importCommit(pending.id, pending.reviewBinding, true, () -> { });
        assertFalse(Arrays.equals(target, store.bytes));
    }

    @Test public void sameValidatorDetachReattachCannotRevivePublishedPreview() throws Exception {
        AppSecrets.SshContentValidator validator = manifest -> { }; vault.attachSshValidator(validator);
        AppSecrets.ImportPreview pending = preview("registration");
        vault.attachSshValidator(null); vault.attachSshValidator(validator);
        rejects("VAULT_EXPIRED", () -> vault.importCommit(pending.id, pending.reviewBinding, true, () -> { })); unchanged();
    }

    @Test public void lastPreviewStoreReadRechecksRegistrationCancellationLockAndDeadline() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            setup(); final int variant = mode; AppSecrets.SshContentValidator validator = manifest -> { };
            vault.attachSshValidator(validator);
            vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
                store.onRead = () -> {
                    if (variant == 0) { vault.attachSshValidator(null); vault.attachSshValidator(validator); }
                    else if (variant == 1) vault.invalidatePreviewRequest("final-read");
                    else if (variant == 2) vault.session.lock();
                    else clock.now = 1000;
                };
                return SshRestoreTest.syntheticReview(s, t);
            }, (review, consent) -> { });
            rejects(mode == 1 ? "VAULT_CANCELLED" : mode == 3 ? "VAULT_EXPIRED" : "VAULT_CHANGED",
                    () -> vault.preview(source, PASSWORD, PASSWORD, "final-read", 1000, () -> { }));
            unchanged();
        }
    }

    @Test public void finalCommitReadAndBeforeCommitRequireExactValidatorRegistration() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            setup(); AppSecrets.SshContentValidator validator = manifest -> { }; vault.attachSshValidator(validator);
            AppSecrets.ImportPreview pending = preview("commit");
            Runnable revoke = () -> { vault.attachSshValidator(null); vault.attachSshValidator(validator); };
            if (mode == 0) store.beforeFinalGuard = () -> store.onRead = revoke;
            else store.beforeFinalGuard = revoke;
            rejects("VAULT_EXPIRED",
                    () -> vault.importCommit(pending.id, pending.reviewBinding, true, () -> { }));
            unchanged();
        }
    }

    @Test public void queuedUiDeliveryRejectsCancelLockExpiryAndReplacementWithoutClearingNewPreview() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            setup(); AppSecrets.ImportPreview pending = preview("old");
            VaultRequestPhase phase = new VaultRequestPhase(); AppSecrets.ImportPreview replacement = null;
            if (mode == 0) phase.cancel();
            else if (mode == 1) vault.session.lock();
            else if (mode == 2) clock.now = pending.deadline;
            else if (mode == 3) replacement = preview("new");
            else vault.attachSshValidator(manifest -> { });
            rejects(mode == 0 ? "VAULT_CANCELLED" : mode == 1 ? "VAULT_CHANGED" : "VAULT_EXPIRED",
                    () -> vault.deliverPreview(pending, guard(phase, Long.MAX_VALUE), () -> fail("stale UI success")));
            vault.cancelPreview(pending.id);
            if (replacement != null) {
                AtomicInteger delivered = new AtomicInteger();
                vault.deliverPreview(replacement, () -> { }, delivered::incrementAndGet); assertEquals(1, delivered.get());
            }
            unchanged();
        }
    }

    @Test public void uiDeliveryAvoidsCoreMonitorStoreParserAndPhaseDuringCallback() throws Exception {
        AppSecrets.ImportPreview pending = preview("ui"); VaultRequestPhase phase = new VaultRequestPhase();
        Pause core = new Pause(); FutureTask<Void> holding = new FutureTask<>(() -> { synchronized (vault) { core.await(); } return null; });
        new Thread(holding, "synthetic-core-holder").start(); core.entered();
        store.onRead = () -> fail("main must not read store");
        FutureTask<Void> delivery = new FutureTask<>(() -> {
            vault.deliverPreview(pending, guard(phase, Long.MAX_VALUE), () -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(phase)); assertFalse(Thread.holdsLock(vault.session));
            }); return null;
        });
        try { new Thread(delivery, "synthetic-main-delivery").start(); delivery.get(2, TimeUnit.SECONDS); }
        finally { core.release.countDown(); }
        holding.get(2, TimeUnit.SECONDS);
        assertNotNull("store hook must remain unused", store.onRead);
    }

    @Test public void pureTotpNeedsNoProvidersAndCannotBypassSshTarget() throws Exception {
        vault.attachSshValidator(null); vault.attachSshRestoreReviewer("synthetic", "1", null, null);
        rejects("VAULT_SSH_VALIDATION_REQUIRED", () -> vault.preview(totp, PASSWORD, PASSWORD, "target-ssh", () -> { }));
        store.bytes = null;
        AppSecrets.ImportPreview pending = vault.preview(totp, PASSWORD, null, "totp", () -> { });
        assertEquals("", pending.reviewBinding); assertNull(pending.reviewMap());
        vault.importCommit(pending.id, true, () -> { });
        try (AppSecrets.Verified restored = AppSecrets.verify(store.bytes, PASSWORD)) {
            assertEquals(1, restored.manifest.entries.size()); assertTrue(restored.manifest.files.isEmpty());
        }
    }

    @Test public void failedNewWorkPreservesUnrelatedPublishedPreviewAndLockedStartNeedsFreshPasswords() throws Exception {
        boolean[] reject = {false};
        vault.attachSshValidator(manifest -> { if (reject[0]) throw VaultException.invalid(); });
        assertFalse(vault.session.unlocked());
        AppSecrets.ImportPreview old = preview("old"); reject[0] = true;
        rejects("VAULT_CONTENT", () -> preview("failed-new"));
        AtomicInteger delivered = new AtomicInteger();
        vault.deliverPreview(old, () -> { }, delivered::incrementAndGet); assertEquals(1, delivered.get());
        vault.importCommit(old.id, old.reviewBinding, true, () -> { });
        assertFalse(Arrays.equals(target, store.bytes));
    }

    @Test public void reviewerCannotMutateTargetWhileReturningOriginalCoverage() throws Exception {
        byte[][] retained = new byte[1][];
        vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
            SshRestoreReview review = SshRestoreTest.syntheticReview(s, t);
            retained[0] = t.entries.get(0).secret; retained[0][0] ^= 1;
            return review;
        }, (review, consent) -> { });
        rejects("VAULT_CONTENT", () -> preview("mutated-target")); wiped(retained[0]); unchanged();
    }

    @Test public void reviewDeadlineStartsAtPublicationAfterFinalStoreRead() throws Exception {
        vault.attachSshRestoreReviewer("synthetic", "1", (s, t) -> {
            store.onRead = () -> clock.now = 999;
            return SshRestoreTest.syntheticReview(s, t);
        }, (review, consent) -> { });
        AppSecrets.ImportPreview pending = vault.preview(source, PASSWORD, PASSWORD, "anchor", 1000, () -> { });
        assertEquals(120999, pending.deadline);
    }

    @Test public void winningDeliveryClaimIsOnceOnlyAndLaterCancellationStillRevokesCommit() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            setup(); AppSecrets.ImportPreview pending = preview("claim");
            VaultRequestPhase phase = new VaultRequestPhase(); Pause afterClaim = new Pause();
            AtomicInteger delivered = new AtomicInteger();
            FutureTask<Void> delivery = new FutureTask<>(() -> {
                vault.deliverPreview(pending, guard(phase, Long.MAX_VALUE), () -> {
                    afterClaim.await(); delivered.incrementAndGet();
                }); return null;
            });
            new Thread(delivery, "synthetic-claimed-delivery").start();
            try {
                afterClaim.entered();
                if (mode == 0) { phase.cancel(); vault.invalidatePreviewRequest("claim"); }
                else if (mode == 1) vault.session.lock();
                else preview("replacement");
            } finally { afterClaim.release.countDown(); }
            delivery.get(10, TimeUnit.SECONDS); assertEquals(1, delivered.get());
            rejects(mode == 1 ? "VAULT_CHANGED" : "VAULT_EXPIRED",
                    () -> vault.importCommit(pending.id, pending.reviewBinding, true, () -> { }));
            unchanged();
        }
        setup(); AppSecrets.ImportPreview pending = preview("once"); AtomicInteger count = new AtomicInteger();
        vault.deliverPreview(pending, () -> { }, count::incrementAndGet);
        rejects("VAULT_EXPIRED", () -> vault.deliverPreview(pending, () -> { }, count::incrementAndGet));
        assertEquals(1, count.get());
    }

    @Test public void exactBackgroundExpiryRejectsPublicationWithoutRevocationUnderPhase() throws Exception {
        vault.unlock(PASSWORD, () -> { }); AppSecrets.ImportPreview pending = preview("background");
        VaultRequestPhase phase = new VaultRequestPhase(); AtomicInteger revoked = new AtomicInteger();
        Pause revocation = new Pause();
        vault.session.attachRevocation(vault.session.epoch(), new VaultSession.Revocation() {
            @Override public boolean active() { return true; }
            @Override public void revoke() {
                assertFalse("native cleanup must never run under phase", Thread.holdsLock(phase)); revoked.incrementAndGet();
                revocation.await();
            }
        });
        vault.session.foreground(false); clock.now = 30000;
        FutureTask<Void> delivery = new FutureTask<>(() -> {
            rejects("VAULT_FOREGROUND_REQUIRED", () -> vault.deliverPreview(pending, guard(phase, Long.MAX_VALUE), () -> fail("background UI")));
            return null;
        });
        FutureTask<Void> lock = new FutureTask<>(() -> { vault.session.lock(); return null; });
        FutureTask<Void> cancellation = new FutureTask<>(() -> { phase.cancel(); return null; });
        try {
            new Thread(delivery, "synthetic-expired-delivery").start(); delivery.get(2, TimeUnit.SECONDS);
            assertEquals(0, revoked.get());
            new Thread(lock, "synthetic-blocking-revocation").start(); revocation.entered();
            new Thread(cancellation, "synthetic-phase-cancellation").start(); cancellation.get(2, TimeUnit.SECONDS);
        } finally { revocation.release.countDown(); }
        lock.get(2, TimeUnit.SECONDS); assertEquals(1, revoked.get());
    }

    @Test public void boundGuardPreservesParentPublicationFenceAndCancellation() throws Exception {
        VaultRequestPhase phase = new VaultRequestPhase(); AtomicInteger conditions = new AtomicInteger(), publications = new AtomicInteger();
        AppSecrets.EffectGuard bound = AppSecrets.boundGuard(guard(phase, 1000), () -> {
            assertTrue("bound condition must share the parent's claim", Thread.holdsLock(phase));
            conditions.incrementAndGet();
        });
        bound.publish(() -> {
            assertTrue("publication must retain the parent's fence", Thread.holdsLock(phase)); publications.incrementAndGet();
        });
        assertEquals(1, conditions.get()); assertEquals(1, publications.get());
        phase.cancel();
        rejects("VAULT_CANCELLED", () -> bound.publish(publications::incrementAndGet));
        assertEquals(1, conditions.get()); assertEquals(1, publications.get());
        assertFalse("preview publication must not claim a commit", phase.committing());
    }

    private static byte[] encrypt(String label, boolean ssh) throws Exception {
        VaultManifest.Entry entry = ssh
                ? new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "credential", "target:synthetic@example.invalid:22", "synthetic", "", 0, 0, label.getBytes(StandardCharsets.UTF_8))
                : new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "synthetic:account", "synthetic", "SHA1", 6, 30, new byte[20]);
        List<VaultManifest.FileObject> files = ssh ? Collections.singletonList(new VaultManifest.FileObject(
                VaultManifest.FileType.CONFIG, "config", "config", "synthetic config", label.getBytes(StandardCharsets.UTF_8), Collections.emptyList())) : Collections.emptyList();
        byte[] salt = VaultEnvelope.random(16), key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = new VaultManifest(Collections.singletonList(entry), files)) {
            byte[] plain = manifest.encode();
            try { return VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        } finally { VaultCodec.wipe(key); }
    }
}
