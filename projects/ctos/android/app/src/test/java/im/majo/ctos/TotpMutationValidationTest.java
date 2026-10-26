package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Synthetic encrypted sources and bounded host ticket publication, without Android I/O. */
public final class TotpMutationValidationTest {
    private static final byte[] KEY = new byte[32], SEED = new byte[20];
    private Clock clock;
    private Store store;
    private AppSecrets vault;
    private TotpPreviews previews;
    private static final class Clock implements VaultSession.Clock { long value = 100; public long now() { return value; } }
    private static final class Store implements AppSecrets.Store {
        byte[] bytes, quick = {1, 2}; int writes, claims; boolean committing, forbidRead; Runnable beforeClaim;
        public byte[] read() { assertFalse(committing); assertFalse(forbidRead); return bytes.clone(); }
        public byte[] quickMaterial() { assertFalse(committing); assertFalse(forbidRead); return quick.clone(); }
        public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            committing = true;
            try { if (beforeClaim != null) beforeClaim.run(); guard.beforeCommit(); claims++; bytes = next.clone(); quick = wrapper.clone(); writes++; }
            finally { committing = false; }
        }
    }
    private interface Action { void run() throws Exception; }
    @Before public void setup() throws Exception { setup(false); }
    private void setup(boolean mixed) throws Exception {
        clock = new Clock(); store = new Store();
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "entry", "Issuer:account", "Old", "SHA1", 6, 30, SEED);
        VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "key", "key", "identity", new byte[] {3, 4}, List.of());
        try (VaultManifest source = new VaultManifest(List.of(entry), mixed ? List.of(file) : List.of())) {
            byte[] plain = source.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 4, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); VaultCodec.wipe(file.content); }
        }
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.session.unlock(KEY);
        if (mixed) vault.attachSshValidator(loan -> { });
        previews = new TotpPreviews(vault, clock);
    }
    private AppSecrets.TotpMutationOrigin origin() throws VaultException {
        return vault.newTotpMutationOrigin(vault.session.epoch(), 900, () -> { });
    }
    private VaultOperation admit(int kind, AppSecrets.TotpMutationContext context) throws VaultException {
        if (kind == 0) return vault.beginTotpRename("entry", context);
        if (kind == 1) return vault.beginTotpDelete("entry", context);
        return vault.beginTotpSave(kind == 3 ? "new" : "entry", context);
    }
    private void effect(int kind, VaultOperation operation, AppSecrets.EffectGuard guard) throws VaultException {
        if (kind == 0) vault.renameTotp(operation, "entry", "New", guard);
        else if (kind == 1) vault.deleteTotp(operation, "entry", guard);
        else if (kind == 2) vault.saveTotp(operation, "entry", "Issuer:replacement", "New", SEED, guard);
        else vault.addTotp(operation, "new", "Issuer:new", "New", SEED, guard);
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); } catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static void await(CountDownLatch latch) throws VaultException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static void wiped(VaultManifest loan) {
        for (VaultManifest.Entry entry : loan.entries) assertArrayEquals(new byte[entry.secret.length], entry.secret);
        for (VaultManifest.FileObject file : loan.files) assertArrayEquals(new byte[file.content.length], file.content);
    }
    private static TotpInput input() throws VaultException { return TotpInput.manual("Issuer", "account", "JBSWY3DPEHPK3PXP"); }
    @SuppressWarnings("unchecked") private static String candidate(Map<String, Object> preview) {
        return (String) ((List<Map<String, Object>>) preview.get("candidates")).get(0).get("candidateId");
    }

    @Test public void pureAllMutationsIgnoreProviderChurnAndPreserveWrapper() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            setup(); AppSecrets.TotpMutationOrigin origin = origin(); VaultOperation request = admit(kind, origin.context());
            vault.attachSshValidator(loan -> { throw new AssertionError("Pure TOTP must not call SSH"); });
            AtomicInteger claims = new AtomicInteger();
            effect(kind, request, new AppSecrets.EffectGuard() {
                public void check() { }
                public void beforeCommit() { claims.incrementAndGet(); }
            });
            assertEquals(1, store.writes); assertEquals(1, claims.get()); assertArrayEquals(new byte[] {1, 2}, store.quick);
            assertTrue(request.consumed); final int action = kind;
            rejects("VAULT_OPERATION", () -> effect(action, request, () -> { }));
        }
    }

    @Test public void mixedAllMutationStagesStayDetachedAndCancellable() throws Exception {
        for (int kind = 0; kind < 4; kind++) for (int stage = 0; stage < 3; stage++) for (int invalidation = 0; invalidation < 6; invalidation++) {
            setup(true); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
            AtomicReference<VaultManifest> retained = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            final int at = stage == 2 ? 2 : 1;
            AppSecrets.SshContentValidator validator = loan -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session)); assertFalse(Thread.holdsLock(vault.publicationLock));
                if (armed.get() && calls.incrementAndGet() == at) { retained.set(loan); entered.countDown(); await(release); }
            };
            vault.attachSshValidator(validator); AppSecrets.TotpMutationOrigin origin = origin();
            AppSecrets.TotpMutationContext context = origin.context();
            VaultOperation operation = stage == 0 ? null : admit(kind, context);
            final int action = kind; armed.set(true); AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { if (operation == null) admit(action, context); else effect(action, operation, () -> { }); }
                catch (Throwable failure) { outcome.set(failure); }
            });
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            final int change = invalidation; CountDownLatch changed = new CountDownLatch(1);
            Thread changer = new Thread(() -> {
                if (change == 0) origin.cancel();
                else if (change == 1) context.cancel();
                else if (change == 2) vault.lock();
                else if (change == 3) clock.value = 900;
                else if (change == 4) vault.attachSshValidator(validator);
                else store.bytes[store.bytes.length - 1] ^= 1;
                synchronized (vault) { changed.countDown(); }
            });
            changer.start();
            try { assertTrue(changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); changer.join(5000); }
            assertFalse(worker.isAlive()); assertTrue(outcome.get() instanceof VaultException); assertEquals(0, store.writes); wiped(retained.get());
        }
    }

    @Test public void sourceAndCandidateLoanMutationAndThrowablesNeverWrite() throws Exception {
        for (int stage = 0; stage < 3; stage++) for (int problem = 0; problem < 4; problem++) {
            setup(true); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); final int at = stage == 2 ? 2 : 1, failure = problem;
            vault.attachSshValidator(loan -> {
                if (!armed.get() || calls.incrementAndGet() != at) return;
                retained.set(loan);
                if (failure == 0) loan.files.get(0).content[0] ^= 1;
                else if (failure == 1) throw VaultException.invalid();
                else if (failure == 2) throw new IllegalStateException("Fixture");
                else throw new AssertionError("Fixture");
            });
            AppSecrets.TotpMutationContext context = origin().context(); VaultOperation operation = stage == 0 ? null : admit(0, context);
            armed.set(true); Throwable error = null;
            try { if (operation == null) admit(0, context); else effect(0, operation, () -> { }); } catch (Throwable caught) { error = caught; }
            assertNotNull(error); wiped(retained.get()); assertEquals(0, store.writes);
        }
    }

    @Test public void addOnlyWrongPurposeAndFinalClaimRemainExact() throws Exception {
        VaultOperation request = vault.beginTotpSave("entry", origin().context());
        rejects("VAULT_OPERATION", () -> vault.renameTotp(request, "entry", "New", () -> { })); assertFalse(request.consumed);
        rejects("VAULT_OPERATION", () -> vault.addTotp(request, "entry", "Issuer:new", "New", SEED, () -> { })); assertTrue(request.consumed);
        AppSecrets.TotpMutationOrigin origin = origin(); VaultOperation next = admit(0, origin.context()); store.beforeClaim = origin::cancel;
        rejects("VAULT_CANCELLED", () -> effect(0, next, () -> { })); assertEquals(0, store.writes);
    }

    @Test public void previewHandoffSurvivesOldRequestButNotOriginalExpiry() throws Exception {
        AtomicBoolean oldActive = new AtomicBoolean(true);
        AppSecrets.TotpMutationOrigin origin = vault.newTotpMutationOrigin(vault.session.epoch(), 900, () -> {
            if (!oldActive.get()) throw new VaultException("VAULT_CANCELLED", "Old request finished");
        });
        VaultOperation capture = vault.beginTotpSave("capture", origin.context());
        Map<String, Object> preview = previews.prepare("native-owner", "wire-request", List.of(input()), 0, origin);
        previews.publish((String) preview.get("previewId"), () -> { }); oldActive.set(false); vault.cancelOperation(capture);
        AtomicInteger claims = new AtomicInteger();
        String added = previews.commit((String) preview.get("previewId"), candidate(preview), new AppSecrets.EffectGuard() {
            public void check() { }
            public void beforeCommit() { claims.incrementAndGet(); }
        });
        assertNotNull(added); assertEquals(1, claims.get()); assertEquals(1, store.writes);
        setup(); AppSecrets.TotpMutationOrigin expiring = origin(); clock.value = 899;
        Map<String, Object> late = previews.prepare("owner", "request", List.of(input()), 0, expiring);
        previews.publish((String) late.get("previewId"), () -> { }); clock.value = 900;
        rejects("VAULT_EXPIRED", () -> previews.commit((String) late.get("previewId"), candidate(late), () -> { }));
        assertEquals(0, store.writes);
    }

    @Test public void failedPublicationCancelsTicketEvenAfterHandoffAndUnknownOwnerCannotCloseNewTicket() throws Exception {
        AppSecrets.TotpMutationOrigin origin = origin(); TotpInput input = input();
        Map<String, Object> preview = previews.prepare("owner", "request", List.of(input), 0, origin);
        try { previews.publish((String) preview.get("previewId"), () -> { throw new AssertionError("Transport"); }); fail(); }
        catch (AssertionError expected) { assertEquals("Transport", expected.getMessage()); }
        assertTrue(origin.cancelled); assertArrayEquals(new byte[input.seed.length], input.seed);
        Map<String, Object> next = previews.prepare("new-owner", "new-request", List.of(input()), 0, origin());
        previews.cancelOwner("owner"); previews.cancel((String) preview.get("previewId"));
        previews.publish((String) next.get("previewId"), () -> { });
        assertNotNull(previews.commit((String) next.get("previewId"), candidate(next), () -> { }));
    }

    @Test public void closeDuringPreviewAdmissionDoesNotWaitOrLeaveLateTicket() throws Exception {
        setup(true); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        vault.attachSshValidator(loan -> { assertFalse(Thread.holdsLock(previews)); entered.countDown(); await(release); });
        AppSecrets.TotpMutationOrigin origin = origin(); AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { previews.prepare("owner", "request", List.of(input()), 0, origin); } catch (Throwable failure) { outcome.set(failure); } });
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch closed = new CountDownLatch(1); Thread closer = new Thread(() -> { previews.close(); closed.countDown(); }); closer.start();
        try { assertTrue(closed.await(2, TimeUnit.SECONDS)); } finally { release.countDown(); worker.join(5000); closer.join(5000); }
        assertTrue(outcome.get() instanceof VaultException); assertTrue(origin.cancelled); assertEquals(0, store.writes);
    }

    @Test public void originalMixedRegistrationAndSourceBindingCannotRenewAcrossChildren() throws Exception {
        setup(true); AppSecrets.TotpMutationOrigin origin = origin(); VaultOperation first = vault.beginTotpSave("first", origin.context());
        vault.attachSshValidator(loan -> { });
        rejects("VAULT_CHANGED", () -> vault.beginTotpSave("second", origin.context()));
        rejects("VAULT_CHANGED", () -> vault.checkTotpPending(first));
        setup(); AppSecrets.TotpMutationOrigin pure = origin(); vault.beginTotpSave("first", pure.context());
        byte[] plain = VaultEnvelope.parse(store.bytes).decrypt(KEY);
        try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 4, new byte[16], KEY, plain).encode(); }
        finally { VaultCodec.wipe(plain); }
        rejects("VAULT_CHANGED", () -> vault.beginTotpSave("second", pure.context()));
    }
    @Test public void expiredCapabilitiesAreReclaimedBeforeSharedAdmissionCap() throws Exception {
        for (int i = 0; i < 64; i++) vault.beginTotpSave("abandoned-" + i);
        clock.value += 120001;
        assertNotNull(vault.beginTotpSave("fresh"));
    }

    @Test public void capturePlusSixteenCandidatesAreBoundedAndPublicationReadsNoStore() throws Exception {
        AppSecrets.TotpMutationOrigin origin = origin();
        VaultOperation capture = vault.beginTotpSave("capture", origin.context());
        java.util.ArrayList<TotpInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) inputs.add(input());
        Map<String, Object> preview = previews.prepare("owner", "request", inputs, 0, origin);
        rejects("VAULT_OPERATION", origin::context);
        vault.cancelOperation(capture); store.forbidRead = true;
        previews.publish((String) preview.get("previewId"), () -> { });
        store.forbidRead = false;
        assertNotNull(previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }));
    }

    @Test public void closeCannotWipeSelectedWorkerCandidateDuringValidation() throws Exception {
        setup(true); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<VaultManifest> retained = new AtomicReference<>();
        vault.attachSshValidator(loan -> {
            assertFalse(Thread.holdsLock(previews));
            if (armed.get() && calls.incrementAndGet() == 2) { retained.set(loan); entered.countDown(); await(release); }
        });
        TotpInput input = input(); byte[] expected = input.seed.clone();
        Map<String, Object> preview = previews.prepare("owner", "request", List.of(input), 0, origin());
        previews.publish((String) preview.get("previewId"), () -> { }); armed.set(true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }); }
            catch (Throwable failure) { outcome.set(failure); }
        });
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            previews.close(); assertArrayEquals(new byte[input.seed.length], input.seed);
            assertArrayEquals(expected, retained.get().entries.get(1).secret);
        } finally { release.countDown(); worker.join(5000); }
        assertTrue(outcome.get() instanceof VaultException); wiped(retained.get()); assertEquals(0, store.writes);
    }

    @Test public void losingConcurrentCommitCannotRetireReservedTicket() throws Exception {
        setup(true); AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        vault.attachSshValidator(loan -> { if (armed.get()) { entered.countDown(); await(release); } });
        AppSecrets.TotpMutationOrigin origin = origin();
        Map<String, Object> preview = previews.prepare("owner", "request", List.of(input()), 0, origin);
        previews.publish((String) preview.get("previewId"), () -> { }); armed.set(true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }); }
            catch (Throwable failure) { outcome.set(failure); }
        });
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            rejects("BUSY", () -> previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }));
            assertFalse(origin.cancelled);
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertNull(outcome.get()); assertEquals(1, store.writes);
    }

    @Test public void stalePrepareCannotEvictNewTicketOrCancelReusedCurrentOrigin() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            setup();
            AppSecrets.TotpMutationOrigin stale = vault.newTotpMutationOrigin(vault.session.epoch(), 200, () -> { });
            AppSecrets.TotpMutationOrigin current = origin();
            Map<String, Object> preview = previews.prepare("new-owner", "new-request", List.of(input()), 0, current);
            if (kind == 0) stale.cancel();
            else if (kind == 1) clock.value = 200;
            else { previews.publish((String) preview.get("previewId"), () -> { }); stale = current; }
            TotpInput rejected = input(); AppSecrets.TotpMutationOrigin incoming = stale;
            rejects(kind == 0 ? "VAULT_CANCELLED" : kind == 1 ? "VAULT_EXPIRED" : "BUSY",
                    () -> previews.prepare("late-owner", "late-request", List.of(rejected), 0, incoming));
            assertArrayEquals(new byte[rejected.seed.length], rejected.seed); assertFalse(current.cancelled);
            if (kind != 2) previews.publish((String) preview.get("previewId"), () -> { });
            assertNotNull(previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }));
            assertEquals(1, store.writes);
        }
    }

    @Test public void oldEpochForeignCoreAndPinnedRevisionCannotReplaceCurrentPreview() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            setup(); AppSecrets.TotpMutationOrigin stale = origin();
            if (kind == 0) { vault.session.lock(); vault.session.unlock(KEY); }
            else if (kind == 1) {
                AppSecrets other = new AppSecrets(store, clock); other.session.foreground(true); other.session.unlock(KEY);
                stale = other.newTotpMutationOrigin(other.session.epoch(), 900, () -> { });
            } else {
                vault.beginTotpSave("old-capture", stale.context());
                synchronized (vault.publicationLock) { vault.contentRevision++; }
            }
            AppSecrets.TotpMutationOrigin current = origin();
            Map<String, Object> preview = previews.prepare("owner", "request", List.of(input()), 0, current);
            TotpInput rejected = input(); AppSecrets.TotpMutationOrigin incoming = stale;
            rejects(kind == 1 ? "VAULT_OPERATION" : "VAULT_CHANGED",
                    () -> previews.prepare("stale-owner", "stale-request", List.of(rejected), 0, incoming));
            assertArrayEquals(new byte[rejected.seed.length], rejected.seed); assertFalse(current.cancelled);
            if (kind == 1) assertFalse(incoming.cancelled);
            previews.publish((String) preview.get("previewId"), () -> { });
            assertNotNull(previews.commit((String) preview.get("previewId"), candidate(preview), () -> { }));
            assertEquals(1, store.writes);
        }
    }

}
