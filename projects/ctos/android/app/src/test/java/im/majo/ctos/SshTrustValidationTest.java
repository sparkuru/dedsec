package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Encrypted synthetic trust sources; no Android durability or real host assumptions. */
public final class SshTrustValidationTest {
    private static final byte[] KEY = new byte[32], OLD = {1, 2, 3}, NEXT = {4, 5, 6};
    private Clock clock;
    private MemoryStore store;
    private AppSecrets vault;
    private SshTrustProposal proposal;
    private static final class Clock implements VaultSession.Clock {
        volatile long now = 100;
        @Override public long now() { return now; }
    }
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes, quick = {7, 8};
        boolean committing;
        int writes;
        Runnable beforeClaim, afterCommit;
        @Override public byte[] read() { assertFalse(committing); return bytes.clone(); }
        @Override public byte[] quickMaterial() { assertFalse(committing); return quick.clone(); }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            committing = true;
            try {
                if (beforeClaim != null) beforeClaim.run();
                guard.beforeCommit(); bytes = next.clone(); quick = wrapper.clone(); writes++;
                if (afterCommit != null) afterCommit.run();
            } finally { committing = false; }
        }
    }
    private interface Action { void run() throws Exception; }
    @Before public void setup() throws Exception {
        clock = new Clock(); store = new MemoryStore();
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Issuer:account",
                "Issuer", "SHA1", 6, 30, new byte[20]);
        VaultManifest.FileObject file = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "hosts", "known_hosts", "hosts-one", OLD, List.of());
        try (VaultManifest source = new VaultManifest(List.of(entry), List.of(file))) {
            byte[] plain = source.encode();
            try { store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 7, new byte[16], KEY, plain).encode(); }
            finally { VaultCodec.wipe(plain); }
        }
        vault = new AppSecrets(store, clock); vault.session.foreground(true); vault.session.unlock(KEY);
        vault.attachSshValidator(loan -> { });
        proposal = proposal(OLD);
    }
    private SshTrustProposal proposal(byte[] base) throws VaultException {
        return new SshTrustProposal("known_hosts", "hosts", "target:test@example.invalid:22", "connection",
                "example.invalid", 22, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", VaultCodec.digest(base), NEXT);
    }
    private AppSecrets.SshTrustContext context() throws VaultException {
        return vault.newSshTrustContext(vault.session.epoch(), 900, proposal, () -> { });
    }
    private VaultOperation admit(AppSecrets.SshTrustContext context) throws VaultException {
        return vault.beginTrustUpdate(proposal, context, () -> { });
    }
    private static Object field(Object owner, String name) {
        try { java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void wiped(byte[] bytes) { assertNotNull(bytes); assertArrayEquals(new byte[bytes.length], bytes); }
    private static void wiped(VaultManifest loan) {
        for (VaultManifest.Entry e : loan.entries) wiped(e.secret);
        for (VaultManifest.FileObject f : loan.files) wiped(f.content);
    }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static void await(CountDownLatch latch) throws VaultException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw VaultException.invalid(); }
    }
    private void detached() {
        assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
        assertFalse(Thread.holdsLock(vault.publicationLock));
    }

    @Test public void pausedAdmissionAndEffectLoansAllowCancellationAndWipeIndependentWork() throws Exception {
        for (int stage = 0; stage < 3; stage++) for (int kind = 0; kind < 8; kind++) {
            setup(); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
            AtomicReference<VaultManifest> retained = new AtomicReference<>();
            AtomicReference<byte[]> retainedKey = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            final int at = stage == 2 ? 2 : 1;
            AppSecrets.SshContentValidator validator = loan -> {
                detached();
                if (armed.get() && calls.incrementAndGet() == at) {
                    retained.set(loan); retainedKey.set((byte[]) field(field(vault, "sshTrustWork"), "key"));
                    entered.countDown(); await(release);
                }
            };
            vault.attachSshValidator(validator); AppSecrets.SshTrustContext context = context();
            final boolean effect = stage != 0;
            VaultOperation request = effect ? admit(context) : null;
            AtomicReference<Throwable> outcome = new AtomicReference<>(); armed.set(true);
            Thread worker = new Thread(() -> {
                try { if (effect) vault.updateTrust(request, proposal, () -> { }); else admit(context); }
                catch (Throwable failure) { outcome.set(failure); }
            });
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            final int invalidation = kind;
            CountDownLatch changed = new CountDownLatch(1);
            Thread changer = new Thread(() -> {
                switch (invalidation) {
                    case 0: context.cancel(); break;
                    case 1: vault.lock(); break;
                    case 2: clock.now = 900; break;
                    case 3: vault.attachSshValidator(validator); break;
                    case 4: vault.session.foreground(false); break;
                    case 5: proposal.close(); break;
                    case 6: store.bytes[store.bytes.length - 1] ^= 1; break;
                    case 7: vault.attachSshValidator(null); break;
                    default: throw new AssertionError();
                }
                synchronized (vault) { changed.countDown(); }
            });
            changer.start();
            try { assertTrue("Invalidation blocked on detached validator", changed.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); worker.join(5000); changer.join(5000); }
            assertFalse(worker.isAlive()); assertFalse(changer.isAlive());
            assertTrue(outcome.get() instanceof VaultException); assertEquals(0, store.writes);
            wiped(retained.get()); wiped(retainedKey.get()); assertNull(field(vault, "sshTrustWork"));
            if (effect) assertTrue(request.consumed);
            assertArrayEquals(new byte[] {4, 5, 6}, NEXT);
        }
    }

    @Test public void mutatedLoansAndAllThrowableKindsWipeWithoutWriting() throws Exception {
        for (int stage = 0; stage < 3; stage++) for (int kind = 0; kind < 5; kind++) {
            setup(); AtomicBoolean armed = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
            AtomicReference<VaultManifest> retained = new AtomicReference<>(); final int at = stage == 2 ? 2 : 1, problem = kind;
            vault.attachSshValidator(loan -> {
                detached(); if (!armed.get() || calls.incrementAndGet() != at) return;
                retained.set(loan);
                switch (problem) {
                    case 0: loan.files.get(0).content[0] ^= 1; break;
                    case 1: loan.close(); break;
                    case 2: throw VaultException.invalid();
                    case 3: throw new IllegalStateException("Fixture");
                    case 4: throw new AssertionError("Fixture");
                    default: throw new AssertionError();
                }
            });
            AppSecrets.SshTrustContext context = context(); VaultOperation request = stage == 0 ? null : admit(context);
            armed.set(true); Throwable outcome = null;
            try { if (request == null) admit(context); else vault.updateTrust(request, proposal, () -> { }); }
            catch (Throwable failure) { outcome = failure; }
            assertNotNull(outcome); wiped(retained.get()); assertEquals(0, store.writes); assertNull(field(vault, "sshTrustWork"));
        }
    }

    @Test public void exactCommitPreservesEnvelopeWrapperAndClaimsEffectOnlyOnce() throws Exception {
        VaultEnvelope before = VaultEnvelope.parse(store.bytes); byte[] quick = store.quick.clone();
        AtomicInteger contextClaims = new AtomicInteger(), effectClaims = new AtomicInteger();
        AppSecrets.SshTrustContext context = vault.newSshTrustContext(vault.session.epoch(), 900, proposal,
                new AppSecrets.EffectGuard() {
                    @Override public void check() { }
                    @Override public void beforeCommit() { contextClaims.incrementAndGet(); }
                });
        VaultOperation request = admit(context);
        vault.updateTrust(request, proposal, new AppSecrets.EffectGuard() {
            @Override public void check() { }
            @Override public void beforeCommit() { assertTrue(store.committing); effectClaims.incrementAndGet(); }
        });
        assertEquals(1, store.writes); assertEquals(1, effectClaims.get()); assertEquals(0, contextClaims.get());
        assertArrayEquals(quick, store.quick); VaultEnvelope after = VaultEnvelope.parse(store.bytes);
        assertEquals(before.id, after.id); assertEquals(before.generation, after.generation); assertArrayEquals(before.salt, after.salt);
        byte[] plain = after.decrypt(KEY);
        try (VaultManifest saved = VaultManifest.decode(plain)) {
            assertArrayEquals(NEXT, saved.files.get(0).content); assertArrayEquals(new byte[20], saved.entries.get(0).secret);
        } finally { VaultCodec.wipe(plain); }
        rejects("VAULT_OPERATION", () -> vault.updateTrust(request, proposal, () -> { }));
    }

    @Test public void approvalContextNeverRenewsRegistrationOrDeadline() throws Exception {
        AppSecrets.SshTrustContext context = context(); vault.attachSshValidator(loan -> { });
        rejects("VAULT_CHANGED", () -> admit(context));
        setup(); AppSecrets.SshTrustContext expired = context(); clock.now = 900;
        rejects("VAULT_EXPIRED", () -> admit(expired)); assertEquals(0, store.writes);
    }

    @Test public void baseMismatchIsAcceptedAtAdmissionButConsumedAndRejectedAtEffect() throws Exception {
        proposal = proposal(new byte[] {9}); VaultOperation request = admit(context());
        rejects("VAULT_CHANGED", () -> vault.updateTrust(request, proposal, () -> { }));
        assertTrue(request.consumed); assertEquals(0, store.writes);
    }

    @Test public void finalClaimInvalidationNeverPersists() throws Exception {
        AppSecrets.SshTrustContext context = context(); VaultOperation request = admit(context);
        store.beforeClaim = context::cancel;
        rejects("VAULT_CANCELLED", () -> vault.updateTrust(request, proposal, () -> { }));
        assertEquals(0, store.writes);
    }

    @Test public void confirmedPersistenceThenInvalidationIsAlwaysUncertainAndLocks() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            setup(); AppSecrets.SshTrustContext context = context(); VaultOperation request = admit(context);
            final int problem = kind;
            store.afterCommit = () -> {
                switch (problem) {
                    case 0: context.cancel(); break;
                    case 1: vault.lock(); break;
                    case 2: clock.now = 900; break;
                    case 3: vault.attachSshValidator(loan -> { }); break;
                    case 4: proposal.close(); break;
                    default: throw new AssertionError();
                }
            };
            rejects("VAULT_COMMIT_UNCERTAIN", () -> vault.updateTrust(request, proposal, () -> { }));
            assertEquals(1, store.writes); assertFalse(vault.session.unlocked()); assertNull(field(vault, "sshTrustWork"));
        }
    }

    @Test public void lastExternalPostCommitGuardCannotBypassAuthoritativeContinuationCheck() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            setup(); AppSecrets.SshTrustContext context = context(); VaultOperation request = admit(context);
            final int problem = kind;
            rejects("VAULT_COMMIT_UNCERTAIN", () -> vault.updateTrust(request, proposal, () -> {
                if (store.writes == 0) return;
                if (problem == 0) context.cancel();
                else if (problem == 1) vault.attachSshValidator(loan -> { });
                else synchronized (vault.publicationLock) { vault.contentRevision++; }
            }));
            assertEquals(1, store.writes); assertFalse(vault.session.unlocked());
        }
    }
    @Test public void exactProposalBindingAndBusyRejectionsDoNotConsumeOtherRequest() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        vault.attachSshValidator(loan -> {
            if (armed.get()) { entered.countDown(); await(release); }
        });
        VaultOperation first = admit(context()), second = admit(context());
        try (SshTrustProposal clone = proposal(OLD)) {
            assertEquals(proposal.binding, clone.binding);
            rejects("VAULT_OPERATION", () -> vault.updateTrust(second, clone, () -> { }));
            assertFalse(second.consumed);
        }
        AtomicReference<Throwable> outcome = new AtomicReference<>(); armed.set(true);
        Thread worker = new Thread(() -> {
            try { vault.updateTrust(first, proposal, () -> { }); }
            catch (Throwable failure) { outcome.set(failure); }
        });
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            rejects("BUSY", () -> vault.updateTrust(second, proposal, () -> { }));
            assertFalse(second.consumed); vault.cancelOperation(first);
        } finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(outcome.get() instanceof VaultException);
        armed.set(false); vault.updateTrust(second, proposal, () -> { });
        assertTrue(second.consumed); assertEquals(1, store.writes);
    }

    @Test public void actualCandidateRetainedFilesAndEntriesNeverAliasSourceOrCaller() throws Exception {
        byte[] seed = new byte[20]; java.util.Arrays.fill(seed, (byte) 3);
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Issuer:account",
                "Issuer", "SHA1", 6, 30, seed);
        VaultManifest.FileObject selected = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "hosts", "known_hosts", "hosts-one", OLD, List.of());
        VaultManifest.FileObject retained = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "key", "key", "key-one", new byte[] {9, 8, 7}, List.of());
        try (VaultManifest source = new VaultManifest(List.of(entry), List.of(selected, retained))) {
            java.lang.reflect.Method factory = AppSecrets.class.getDeclaredMethod("trustCandidate", VaultManifest.class,
                    SshTrustProposal.class, byte[].class, byte[].class);
            factory.setAccessible(true);
            try (VaultManifest candidate = (VaultManifest) factory.invoke(null, source, proposal, VaultCodec.digest(OLD), NEXT)) {
                assertNotSame(source.entries.get(0).secret, candidate.entries.get(0).secret);
                for (int i = 0; i < source.files.size(); i++) assertNotSame(source.files.get(i).content, candidate.files.get(i).content);
                source.close();
                assertArrayEquals(seed, candidate.entries.get(0).secret);
                assertArrayEquals(NEXT, candidate.files.get(0).content);
                assertArrayEquals(new byte[] {9, 8, 7}, candidate.files.get(1).content);
            }
            assertArrayEquals(new byte[] {4, 5, 6}, NEXT);
        }
    }

}
