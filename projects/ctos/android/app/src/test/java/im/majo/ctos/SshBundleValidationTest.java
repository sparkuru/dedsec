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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Synthetic lifecycle proof; the fixture validator does not certify SSH semantics. */
public final class SshBundleValidationTest {
    private static final byte[] KEY = new byte[32];
    private static final AppSecrets.EffectGuard OPEN = () -> { };
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private SshVaultAdapter adapter;
    private byte[] original;
    private final Object phase = new Object();

    private static final class Clock implements VaultSession.Clock {
        volatile long now;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes, wrapper = {7, 8, 9};
        Runnable onRead, onQuick;
        boolean fail, committing;
        int writes;
        @Override public byte[] read() {
            assertFalse("AtomicFile must not be reopened during its active write", committing);
            if (onRead != null) onRead.run();
            return bytes.clone();
        }
        @Override public byte[] quickMaterial() {
            if (onQuick != null) onQuick.run();
            return wrapper.clone();
        }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            if (fail) throw new IOException("fixture failure");
            committing = true;
            try {
                guard.beforeCommit();
                bytes = next.clone(); wrapper = quick.clone(); writes++;
            } finally { committing = false; }
        }
    }

    @Before public void setup() throws Exception {
        clock = new Clock(); store = new MemoryStore();
        try (VaultManifest manifest = source()) {
            byte[] plain = manifest.encode();
            try { original = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        store.bytes = original.clone();
        vault = new AppSecrets(store, clock);
        vault.session.foreground(true); vault.session.unlock(KEY);
        vault.attachSshValidator(manifest -> { });
        adapter = adapter(proposal -> { });
    }

    @Test public void sourceAndCandidatePausesAllowCancelLockRegistrationAndDeadlineInBothPhases() throws Exception {
        for (boolean effect : new boolean[] {false, true}) {
            for (boolean candidate : new boolean[] {false, true}) {
                for (int invalidation = 0; invalidation < 7; invalidation++) {
                    if (!effect && invalidation == 6) continue;
                    setup();
                    pausedInvalidation(effect, candidate, invalidation);
                }
            }
        }
    }

    private void pausedInvalidation(boolean effect, boolean candidate, int invalidation) throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<VaultManifest> retained = new AtomicReference<>();
        AppSecrets.SshContentValidator validator = manifest -> {
            assertFalse(Thread.holdsLock(vault));
            assertFalse(Thread.holdsLock(vault.session));
            assertFalse(Thread.holdsLock(phase));
            if (!armed.get()) return;
            if (calls.incrementAndGet() == (candidate ? 2 : 1)) {
                retained.set(manifest); entered.countDown(); await(release);
            }
        };
        vault.attachSshValidator(validator);
        AppSecrets.BundleContext context = vault.newBundleContext(900, OPEN);
        try (SshBundleProposal proposal = proposal()) {
            VaultOperation operation = effect ? adapter.bundleRequest(proposal, context) : null;
            armed.set(true);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> {
                if (effect) adapter.commitBundle(operation, proposal, OPEN);
                else adapter.bundleRequest(proposal, context);
            }, outcome);
            assertTrue("validator entered", entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> mutatorFailure = new AtomicReference<>();
            CountDownLatch changed = new CountDownLatch(1);
            Thread mutator = start(() -> {
                switch (invalidation) {
                    case 0: context.cancel(); break;
                    case 1: vault.session.lock(); break;
                    case 2: vault.attachSshValidator(validator); break;
                    case 3: vault.attachSshValidator(manifest -> { }); break;
                    case 4: clock.now = 900; break;
                    case 5: vault.session.foreground(false); break;
                    case 6: adapter.cancel(operation); break;
                    default: throw new AssertionError();
                }
                // A separate thread really acquired the core while the parser was paused.
                synchronized (vault) { changed.countDown(); }
            }, mutatorFailure);
            try { assertTrue("invalidation must not wait for parser", changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); mutator.join(5000); }
            assertFalse(worker.isAlive()); assertFalse(mutator.isAlive()); assertNull(mutatorFailure.get());
            String expected = invalidation == 0 || invalidation == 6 ? "VAULT_CANCELLED"
                    : invalidation == 4 ? "VAULT_EXPIRED"
                    : invalidation == 5 ? "VAULT_FOREGROUND_REQUIRED" : "VAULT_CHANGED";
            assertCode(expected, outcome.get());
            assertWiped(retained.get()); assertOriginal();
            if (effect) assertTrue(operation.consumed);
            else rejects("VAULT_OPERATION", () -> adapter.bundleRequest(proposal, context));
            // The old owner releases its slot only after the retained loan was wiped.
            armed.set(false); clock.now = 0; vault.session.foreground(true); vault.session.unlock(KEY);
            vault.attachSshValidator(manifest -> { });
            assertNotNull(adapter.bundleRequest(proposal));
        }
    }

    @Test public void contextCanCancelDuringApprovalBeforeAnyOperationExists() throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(OPEN);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        SshVaultAdapter approving = adapter(proposal -> {
            if (first.getAndSet(false)) { entered.countDown(); await(release); }
        });
        try (SshBundleProposal proposal = proposal()) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> approving.bundleRequest(proposal, context), outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            context.cancel();
            rejects("BUSY", () -> adapter.bundleRequest(proposal));
            release.countDown(); worker.join(5000);
            assertFalse(worker.isAlive()); assertCode("VAULT_CANCELLED", outcome.get()); assertOriginal();
            rejects("VAULT_OPERATION", () -> adapter.bundleRequest(proposal, context));
        }
    }

    @Test public void queueAndApprovalUseOriginalDeadlineWithoutRenewal() throws Exception {
        try (SshBundleProposal proposal = proposal()) {
            AppSecrets.BundleContext expired = vault.newBundleContext(80, OPEN);
            clock.now = 80;
            rejects("VAULT_EXPIRED", () -> adapter.bundleRequest(proposal, expired));
            clock.now = 0;
            AppSecrets.BundleContext explicit = vault.newBundleContext(80, OPEN);
            rejects("VAULT_EXPIRED", () -> adapter(value -> clock.now = 80).bundleRequest(proposal, explicit));
            clock.now = 0;
            rejects("VAULT_EXPIRED", () -> adapter(value -> clock.now = 120000).bundleRequest(proposal));
            clock.now = 0;
            AppSecrets.BundleContext live = vault.newBundleContext(80, OPEN);
            VaultOperation operation = adapter.bundleRequest(proposal, live);
            assertSame(live, operation.bundleContext); assertEquals(80, operation.deadline);
            clock.now = 80;
            rejects("VAULT_EXPIRED", () -> adapter.commitBundle(operation, proposal, OPEN));
            assertOriginal();
        }
    }

    @Test public void contextIsOwnedSingleUseAndOriginalGuardRemainsActiveAfterIssuance() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AppSecrets.BundleContext context = vault.newBundleContext(100, () -> {
            if (cancelled.get()) throw new VaultException("VAULT_CANCELLED", "fixture origin cancelled");
        });
        AppSecrets foreign = new AppSecrets(store, clock);
        foreign.session.foreground(true); foreign.session.unlock(KEY); foreign.attachSshValidator(manifest -> { });
        try (SshBundleProposal proposal = proposal()) {
            rejects("VAULT_OPERATION", () -> foreign.beginSshBundle(proposal, context, OPEN));
            VaultOperation operation = adapter.bundleRequest(proposal, context);
            rejects("VAULT_OPERATION", () -> adapter.bundleRequest(proposal, context));
            cancelled.set(true);
            rejects("VAULT_CANCELLED", () -> adapter.commitBundle(operation, proposal, OPEN));
            assertOriginal();
        }
    }

    @Test public void metadataPublicationUsesOriginalFenceAndRejectsLastReadInvalidation() throws Exception {
        AtomicInteger publications = new AtomicInteger();
        AppSecrets.EffectGuard owner = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                synchronized (phase) { publications.incrementAndGet(); publication.check(); }
            }
        };
        vault.attachSshValidator(manifest -> {
            assertFalse(Thread.holdsLock(phase)); assertFalse(Thread.holdsLock(vault));
        });
        try (SshBundleProposal proposal = proposal()) {
            AppSecrets.BundleContext context = vault.newBundleContext(owner);
            adapter.bundleRequest(proposal, context);
            assertEquals(1, publications.get());
            AtomicInteger reads = new AtomicInteger();
            AppSecrets.BundleContext revoked = vault.newBundleContext(owner);
            store.onRead = () -> { if (reads.incrementAndGet() == 2) revoked.cancel(); };
            rejects("VAULT_CANCELLED", () -> adapter.bundleRequest(proposal, revoked));
            assertEquals(1, publications.get()); assertOriginal();
        }
    }

    @Test public void originalPublicationFenceCanRevokeRegistrationProposalOrContext() throws Exception {
        for (int invalidation = 0; invalidation < 3; invalidation++) {
            setup();
            try (SshBundleProposal proposal = proposal()) {
                final int action = invalidation;
                AtomicReference<AppSecrets.BundleContext> origin = new AtomicReference<>();
                AppSecrets.EffectGuard owner = new AppSecrets.EffectGuard() {
                    @Override public void check() { }
                    @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                        synchronized (phase) {
                            if (action == 0) origin.get().cancel();
                            if (action == 1) vault.attachSshValidator(manifest -> { });
                            if (action == 2) proposal.close();
                            publication.check();
                        }
                    }
                };
                origin.set(vault.newBundleContext(owner));
                rejects(action == 0 ? "VAULT_CANCELLED" : action == 1 ? "VAULT_CHANGED" : "VAULT_EXPIRED",
                        () -> adapter.bundleRequest(proposal, origin.get()));
                assertOriginal();
            }
        }
    }

    @Test public void publicationRechecksExactApprovalOutsideFenceAndRemovesProvisionalOperation() throws Exception {
        AtomicBoolean approved = new AtomicBoolean(true);
        AppSecrets.EffectGuard origin = new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                synchronized (phase) { approved.set(false); publication.check(); }
            }
        };
        SshVaultAdapter exact = adapter(proposal -> {
            assertFalse(Thread.holdsLock(phase));
            assertFalse(Thread.holdsLock(vault.session));
            if (!approved.get()) throw new VaultException("VAULT_TRUST_REVIEW", "fixture approval revoked");
        });
        try (SshBundleProposal proposal = proposal()) {
            AppSecrets.BundleContext context = vault.newBundleContext(origin);
            rejects("VAULT_TRUST_REVIEW", () -> exact.bundleRequest(proposal, context));
            java.lang.reflect.Field pending = AppSecrets.class.getDeclaredField("operations");
            pending.setAccessible(true);
            assertTrue(((java.util.Map<?, ?>) pending.get(vault)).isEmpty());
            rejects("VAULT_OPERATION", () -> adapter.bundleRequest(proposal, context));
            assertNotNull(adapter.bundleRequest(proposal));
            assertOriginal();
        }
    }

    @Test public void failedAdmissionAndEffectsCleanEveryThrowableAndEffectNeverReplays() throws Exception {
        for (boolean effect : new boolean[] {false, true}) {
            for (boolean candidate : new boolean[] {false, true}) {
                for (int kind = 0; kind < (effect ? 4 : 3); kind++) {
                    setup();
                    AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
                    List<VaultManifest> loans = new ArrayList<>();
                    final int failure = kind;
                    vault.attachSshValidator(manifest -> {
                        loans.add(manifest);
                        if (!armed.get() || calls.incrementAndGet() != (candidate ? 2 : 1)) return;
                        if (failure == 0) throw new VaultException("VAULT_CONTENT", "fixture invalid");
                        if (failure == 1) throw new IllegalStateException("fixture runtime");
                        if (failure == 2) throw new AssertionError("fixture error");
                    });
                    try (SshBundleProposal proposal = proposal()) {
                        AppSecrets.BundleContext context = vault.newBundleContext(OPEN);
                        VaultOperation operation = effect ? adapter.bundleRequest(proposal, context) : null;
                        armed.set(true); store.fail = kind == 3;
                        Throwable failureSeen = null;
                        try {
                            if (effect) adapter.commitBundle(operation, proposal, OPEN);
                            else adapter.bundleRequest(proposal, context);
                        } catch (Throwable expected) { failureSeen = expected; }
                        assertNotNull(failureSeen);
                        if (kind == 0) assertCode("VAULT_CONTENT", failureSeen);
                        if (kind == 1) assertTrue(failureSeen instanceof IllegalStateException);
                        if (kind == 2) assertTrue(failureSeen instanceof AssertionError);
                        if (kind == 3) assertCode("VAULT_IO", failureSeen);
                        for (VaultManifest loan : loans) assertWiped(loan);
                        if (effect) rejects("VAULT_OPERATION", () -> adapter.commitBundle(operation, proposal, OPEN));
                        else rejects("VAULT_OPERATION", () -> adapter.bundleRequest(proposal, context));
                        assertOriginal();
                        armed.set(false); store.fail = false;
                        adapter.commitBundle(adapter.bundleRequest(proposal), proposal, OPEN);
                        assertEquals(1, store.writes);
                    }
                }
            }
        }
    }

    @Test public void loansAreIndependentWipedAndMutationCannotApproveSourceOrCandidate() throws Exception {
        for (boolean effect : new boolean[] {false, true}) {
            for (boolean candidate : new boolean[] {false, true}) {
                for (boolean entry : new boolean[] {false, true}) {
                    setup();
                    AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
                    List<VaultManifest> loans = new ArrayList<>();
                    vault.attachSshValidator(manifest -> {
                        loans.add(manifest);
                        assertEquals(1, manifest.entries.get(0).secret[0]);
                        int stage = calls.getAndIncrement() % 2;
                        assertEquals(stage + 1, manifest.files.size());
                        if (armed.get() && stage == (candidate ? 1 : 0)) {
                            byte[] target = entry ? manifest.entries.get(0).secret : manifest.files.get(0).content;
                            target[0] ^= 1;
                        }
                    });
                    try (SshBundleProposal proposal = proposal()) {
                        VaultOperation operation = effect ? adapter.bundleRequest(proposal) : null;
                        armed.set(true);
                        rejects("VAULT_CONTENT", () -> {
                            if (effect) adapter.commitBundle(operation, proposal, OPEN);
                            else adapter.bundleRequest(proposal);
                        });
                        for (VaultManifest loan : loans) assertWiped(loan);
                        assertOriginal();
                        armed.set(false);
                        calls.set(0);
                        adapter.commitBundle(adapter.bundleRequest(proposal), proposal, OPEN);
                        try (VaultManifest committed = decode(store.bytes)) {
                            assertEquals(1, committed.entries.get(0).secret[0]);
                            assertArrayEquals(bytes("source config"), committed.files.get(0).content);
                        }
                    }
                }
            }
        }
    }

    @Test public void candidateOwnsEverySecretAndFileInBothCloseOrders() throws Exception {
        for (boolean candidateFirst : new boolean[] {false, true}) {
            VaultManifest source = source();
            try (SshBundleProposal proposal = proposal()) {
                VaultManifest candidate = proposal.applyTo(source);
                assertNotSame(source.entries.get(0), candidate.entries.get(0));
                assertNotSame(source.entries.get(0).secret, candidate.entries.get(0).secret);
                assertNotSame(source.files.get(0).content, candidate.files.get(0).content);
                if (candidateFirst) { candidate.close(); assertEquals(1, source.entries.get(0).secret[0]); }
                else { source.close(); assertEquals(1, candidate.entries.get(0).secret[0]); }
                source.close(); candidate.close(); assertWiped(source); assertWiped(candidate);
            }
        }
    }

    @Test public void finalReadQuickMaterialAndCallerClaimRecheckEveryOriginalBoundary() throws Exception {
        for (int seam = 0; seam < 3; seam++) {
            for (int invalidation = 0; invalidation < 5; invalidation++) {
                // Core serialization owns the ciphertext through AtomicFile commit. A
                // caller guard may revoke metadata, but may not rewrite that same file.
                if (seam == 2 && invalidation == 4) continue;
                setup();
                try (SshBundleProposal proposal = proposal()) {
                    AppSecrets.BundleContext context = vault.newBundleContext(100, OPEN);
                    VaultOperation operation = adapter.bundleRequest(proposal, context);
                    final int action = invalidation;
                    Runnable revoke = () -> {
                        if (action == 0) context.cancel();
                        if (action == 1) vault.session.lock();
                        if (action == 2) vault.attachSshValidator(manifest -> { });
                        if (action == 3) clock.now = 100;
                        if (action == 4) store.bytes[store.bytes.length - 1] ^= 1;
                    };
                    AtomicBoolean once = new AtomicBoolean();
                    Runnable revokeOnce = () -> { if (!once.getAndSet(true)) revoke.run(); };
                    if (seam == 0) {
                        // The first read captures; the next read is the final unchanged fence.
                        AtomicInteger reads = new AtomicInteger();
                        store.onRead = () -> { if (reads.incrementAndGet() == 2) revokeOnce.run(); };
                    }
                    if (seam == 1) store.onQuick = revokeOnce;
                    final int boundary = seam;
                    AppSecrets.EffectGuard guard = new AppSecrets.EffectGuard() {
                        @Override public void check() { }
                        @Override public void beforeCommit() { if (boundary == 2) revokeOnce.run(); }
                    };
                    String code = action == 0 ? "VAULT_CANCELLED" : action == 3 ? "VAULT_EXPIRED" : "VAULT_CHANGED";
                    rejects(code, () -> adapter.commitBundle(operation, proposal, guard));
                    assertEquals(0, store.writes); assertArrayEquals(new byte[] {7, 8, 9}, store.wrapper);
                    if (action != 4) assertArrayEquals(original, store.bytes);
                    assertTrue(operation.consumed);
                }
            }
        }
    }

    @Test public void concurrentAndSynchronousReentrantBundlesAreBusyUntilCleanup() throws Exception {
        AtomicBoolean armed = new AtomicBoolean(); AtomicInteger nested = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (SshBundleProposal proposal = proposal()) {
            vault.attachSshValidator(manifest -> {
                if (!armed.get()) return;
                try { rejects("BUSY", () -> adapter.bundleRequest(proposal)); nested.incrementAndGet(); }
                catch (Exception error) { throw new AssertionError(error); }
                entered.countDown(); await(release);
            });
            VaultOperation operation = adapter.bundleRequest(proposal);
            armed.set(true); AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = start(() -> adapter.commitBundle(operation, proposal, OPEN), outcome);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                rejects("BUSY", () -> adapter.bundleRequest(proposal));
                rejects("BUSY", () -> adapter.commitBundle(operation, proposal, OPEN));
                assertTrue(operation.consumed);
            } finally { release.countDown(); worker.join(5000); }
            assertFalse(worker.isAlive()); assertNull(outcome.get()); assertEquals(2, nested.get());
            rejects("VAULT_OPERATION", () -> adapter.commitBundle(operation, proposal, OPEN));
        }
    }

    private SshVaultAdapter adapter(SshVaultAdapter.ExactBundleApproval approval) {
        return new SshVaultAdapter(vault, (connection, target) -> { }, trust -> { }, approval);
    }
    private static VaultManifest source() throws VaultException {
        byte[] secret = new byte[20]; Arrays.fill(secret, (byte) 1);
        return new VaultManifest(Collections.singletonList(new VaultManifest.Entry(VaultManifest.EntryType.TOTP,
                "totp", "Fixture:account", "Fixture", "SHA1", 6, 30, secret)), Collections.singletonList(
                new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, "config", "ssh/config", "fixture source",
                        bytes("source config"), Collections.emptyList())));
    }
    private static SshBundleProposal proposal() throws VaultException {
        VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "trust", "ssh/known_hosts", "fixture candidate", bytes("candidate trust"), Collections.emptyList());
        try (SshBundleProposal.Change change = SshBundleProposal.Change.add(file)) {
            return new SshBundleProposal(Collections.singletonList(change));
        } finally { VaultCodec.wipe(file.content); }
    }
    private static VaultManifest decode(byte[] ciphertext) throws VaultException {
        byte[] plain = VaultEnvelope.parse(ciphertext).decrypt(KEY);
        try { return VaultManifest.decode(plain); } finally { VaultCodec.wipe(plain); }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private void assertOriginal() { assertArrayEquals(original, store.bytes); assertEquals(0, store.writes); }
    private static void assertWiped(VaultManifest manifest) {
        assertNotNull(manifest);
        for (VaultManifest.Entry entry : manifest.entries) assertArrayEquals(new byte[entry.secret.length], entry.secret);
        for (VaultManifest.FileObject file : manifest.files) assertArrayEquals(new byte[file.content.length], file.content);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("fixture release", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private interface Action { void run() throws Exception; }
    private static Thread start(Action action, AtomicReference<Throwable> outcome) {
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable failure) { outcome.set(failure); } });
        worker.start(); return worker;
    }
    private static void assertCode(String expected, Throwable failure) {
        assertTrue("expected " + expected + ", got " + failure, failure instanceof VaultException);
        assertEquals(expected, ((VaultException) failure).code);
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
}
