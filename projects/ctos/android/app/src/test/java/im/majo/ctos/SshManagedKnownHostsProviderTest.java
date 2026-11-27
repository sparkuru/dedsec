package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Real managed known_hosts CAS evidence.  The fixture validator is deliberately
 * a test-only adapter; this class never represents an OpenSSH parser or caller.
 */
public final class SshManagedKnownHostsProviderTest {
    private static final char[] PASSWORD = "managed known hosts provider fixture".toCharArray();
    private static final String TARGET = "target:test@example.invalid:22";
    private static final String CONNECTION = "managed-connection";
    private static final String HOST = "example.invalid";
    private static final String ROOT = "config";
    private static final String PATH = "known_hosts";
    private static final String OBJECT_ID = "ssh-trust";
    private static final String OBJECT_IDENTITY = "fixture trust";
    private static final String KEY_TYPE = "ssh-ed25519";
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final byte[] OLD_CONTENT = bytes("example.invalid ssh-ed25519 OLD\n");
    private static final byte[] NEW_CONTENT = bytes("example.invalid ssh-ed25519 NEW\n");
    private static final byte[] STALE_CONTENT = bytes("example.invalid ssh-ed25519 STALE\n");

    private MemoryStore store;
    private TestClock clock;
    private AppSecrets vault;
    private SshRoleRegistry registry;
    private SshRoleContext role;
    private PublicView rootView;

    private static final class TestClock implements VaultSession.Clock, SshRoleContext.Clock {
        volatile long value = 1_000;
        Thread blockedThread;
        int callsBeforeBlock;
        Thread failingThread;
        int callsBeforeError;
        Error injectedError;
        CountDownLatch entered, release;

        void blockOnCall(int count) {
            blockedThread = Thread.currentThread(); callsBeforeBlock = count;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
        }

        void failOnCall(int count, Error error) {
            failingThread = Thread.currentThread(); callsBeforeError = count; injectedError = error;
        }

        @Override public long now() {
            if (Thread.currentThread() == failingThread && callsBeforeError > 0 && --callsBeforeError == 0)
                throw injectedError;
            if (Thread.currentThread() == blockedThread && callsBeforeBlock > 0 && --callsBeforeBlock == 0) {
                entered.countDown();
                awaitLatch(release);
            }
            return value;
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class PublicView implements SshRoleContext.PublicView, VaultSession.Revocation {
        volatile boolean active = true;
        @Override public boolean active() { return active; }
        @Override public void revoke() { active = false; }
        @Override public boolean revocationConfirmed() { return !active; }
    }

    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        byte[] quick;
        boolean fail;
        boolean uncertain;
        Runnable afterCommit;
        int reads;

        @Override public byte[] read() { reads++; return bytes == null ? null : bytes.clone(); }
        @Override public byte[] quickMaterial() { return quick == null ? null : quick.clone(); }

        @Override public void commit(byte[] next, byte[] material, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            if (fail) throw new IOException("Injected commit failure");
            guard.beforeCommit();
            bytes = next.clone();
            quick = material == null ? null : material.clone();
            if (afterCommit != null) afterCommit.run();
            if (uncertain) throw new VaultException("VAULT_COMMIT_UNCERTAIN",
                    "Fixture commit completed but its receipt is uncertain");
        }
    }

    private interface Operation { void run() throws Exception; }

    private static void rejects(String code, Operation operation) throws Exception {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (VaultException failure) {
            assertEquals(code, failure.code);
        }
    }

    @Before public void setUp() throws Exception {
        store = new MemoryStore();
        store.bytes = encryptedFixture(7, OLD_CONTENT);
        clock = new TestClock();
        vault = new AppSecrets(store, clock);
        // This no-op validator is test infrastructure only, not an OpenSSH validator.
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.unlock(PASSWORD, () -> { });
        registry = new SshRoleRegistry(clock);
        rootView = new PublicView();
        role = issueRole(0, CONNECTION, 8_001, 11, rootView);
    }

    @After public void tearDown() {
        if (registry != null) registry.close();
    }

    @Test public void newProviderCommitsExactBytesThroughRealAppSecretsCas() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            SshTrustProposal proposal = prepared.review();
            assertArrayEquals(NEW_CONTENT, proposal.newContent());
            assertEquals(PATH, proposal.path);
            assertEquals(OBJECT_ID, proposal.objectId);
            assertEquals(VaultManifest.FileType.KNOWN_HOSTS, proposal.objectType);
            assertEquals(OBJECT_IDENTITY, proposal.objectIdentity);

            SshTrustProposal.CasResult result = prepared.commit();
            assertEquals(SshTrustProposal.CasStatus.COMMITTED, result.status);
            assertEquals(role.binding(), result.roleBinding);
            assertEquals(role.hop, result.roleHop);
            assertEquals(CONNECTION, result.connectionId);
            assertEquals(TARGET, result.targetIdentity);
            assertEquals(HOST, result.host);
            assertEquals(22, result.port);
            assertEquals(KEY_TYPE, result.keyType);
            assertEquals(FINGERPRINT, result.fingerprint);
            assertArrayEquals(VaultCodec.digest(OLD_CONTENT), result.baseDigest());
            assertArrayEquals(VaultCodec.digest(NEW_CONTENT), result.replacementDigest());
            assertArrayEquals(NEW_CONTENT, readKnownHosts());
            assertFalse(Arrays.equals(OLD_CONTENT, store.bytes));
        }
    }

    @Test public void nativeCandidateHandoffTransfersOneBodyBeforeTheGuardedCas() throws Exception {
        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            byte[] transferred = NEW_CONTENT.clone();
            prepared.handoff.accept(prepared.evidence, "native-candidate-1", transferred);
            assertArrayEquals(new byte[transferred.length], transferred);

            try (SshTrustProposal proposal = prepared.managed.proposalFor(prepared.evidence)) {
                try (SshTrustProposal plain = new SshTrustProposal(role, PATH, OBJECT_ID,
                        VaultManifest.FileType.KNOWN_HOSTS, OBJECT_IDENTITY, TARGET, CONNECTION,
                        HOST, 22, KEY_TYPE, FINGERPRINT, VaultCodec.digest(OLD_CONTENT), NEW_CONTENT)) {
                    assertNotEquals("candidate identity must be part of the proposal binding",
                            plain.binding, proposal.binding);
                }
                SshHostKeyAuthority.Proof proof = prepared.authority.requireVerified(
                        role, prepared.evidence, proposal);
                SshTrustProposal.CasResult result = prepared.managed.writeBack(
                        prepared.evidence, proof, proposal);
                assertEquals(SshTrustProposal.CasStatus.COMMITTED, result.status);
                assertArrayEquals(VaultCodec.digest(NEW_CONTENT), result.replacementDigest());
                try {
                    proposal.requireOpen();
                    fail("a committed provider must close its proposal body");
                } catch (VaultException failure) {
                    assertEquals("VAULT_EXPIRED", failure.code);
                }
            }
        }
        assertArrayEquals(NEW_CONTENT, readKnownHosts());
    }

    @Test public void nativeCandidateHandoffRejectsIdentityDigestAndLifecycleReuse() throws Exception {
        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            byte[] transferred = NEW_CONTENT.clone();
            rejects("VAULT_CONTENT", () -> prepared.handoff.accept(
                    prepared.evidence, "invalid candidate", transferred));
            assertArrayEquals(new byte[transferred.length], transferred);
        }

        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            SshHostKeyAuthority.Evidence wrongBase = evidence(
                    SshHostKeyAuthority.Classification.NEW, digest("wrong base"), digest(NEW_CONTENT));
            byte[] transferred = NEW_CONTENT.clone();
            rejects("SSH_HOST_KEY_REVIEW", () -> prepared.handoff.accept(
                    wrongBase, "native-candidate-2", transferred));
            assertArrayEquals(new byte[transferred.length], transferred);
        }

        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            byte[] first = NEW_CONTENT.clone();
            prepared.handoff.accept(prepared.evidence, "native-candidate-3", first);
            assertArrayEquals(new byte[first.length], first);
            byte[] duplicate = NEW_CONTENT.clone();
            rejects("SSH_HOST_KEY_REVIEW", () -> prepared.handoff.accept(
                    prepared.evidence, "native-candidate-4", duplicate));
            assertArrayEquals(new byte[duplicate.length], duplicate);
        }

        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            prepared.handoff.close();
            byte[] closed = NEW_CONTENT.clone();
            rejects("SSH_HOST_KEY_REVIEW", () -> prepared.handoff.accept(
                    prepared.evidence, "native-candidate-5", closed));
            assertArrayEquals(new byte[closed.length], closed);
        }

        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            clock.value = role.deadline;
            byte[] expired = NEW_CONTENT.clone();
            rejects("SSH_ROLE_EXPIRED", () -> prepared.handoff.accept(
                    prepared.evidence, "native-candidate-6", expired));
            assertArrayEquals(new byte[expired.length], expired);
        }
    }

    @Test public void nativeCandidateHandoffNeverReportsFailedOrUncertainCasCommitted() throws Exception {
        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            store.fail = true;
            rejects("VAULT_IO", () -> commitNativeCandidate(prepared));
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        store.fail = false;
        try (NativeHandoffPrepared prepared = nativeHandoffPrepared()) {
            store.uncertain = true;
            rejects("VAULT_COMMIT_UNCERTAIN", () -> commitNativeCandidate(prepared));
            assertArrayEquals(NEW_CONTENT, readKnownHosts());
            assertFalse(vault.session.unlocked());
        }
    }

    @Test public void knownHostNeedsNoProposalAndDoesNotMutateManagedObject() throws Exception {
        SshHostKeyAuthority.Evidence evidence = evidence(SshHostKeyAuthority.Classification.KNOWN,
                VaultCodec.digest(OLD_CONTENT), VaultCodec.digest(NEW_CONTENT));
        SshHostKeyAuthority.Proof proof = new SshHostKeyAuthority().requireVerified(role, evidence, null);
        assertEquals(SshHostKeyAuthority.Classification.KNOWN, proof.classification);
        SshTrustProposal.CasResult result = SshTrustProposal.CasResult.notRequired(evidence);
        assertEquals(SshTrustProposal.CasStatus.NOT_REQUIRED, result.status);
        assertArrayEquals(OLD_CONTENT, readKnownHosts());
    }

    @Test public void exactObjectAndCandidateAreRequired() throws Exception {
        SshVaultAdapter adapter = adapter(true, true);
        rejects("VAULT_OPERATION", () -> SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock,
                ROOT, PATH, "wrong-object", OBJECT_IDENTITY));
        rejects("VAULT_OPERATION", () -> SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock,
                ROOT, "config", OBJECT_ID, OBJECT_IDENTITY));
        rejects("VAULT_OPERATION", () -> SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock,
                ROOT, PATH, OBJECT_ID, "wrong identity"));

        SshManagedKnownHostsProvider.SnapshotReceipt snapshot =
                SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock, ROOT, PATH,
                        OBJECT_ID, OBJECT_IDENTITY);
        try {
            rejects("SSH_HOST_KEY_REVIEW", () -> SshManagedKnownHostsProvider.bind(adapter, snapshot, null));
            SshHostKeyAuthority.Evidence wrongBase = evidence(SshHostKeyAuthority.Classification.NEW,
                    VaultCodec.digest(bytes("wrong base")), VaultCodec.digest(NEW_CONTENT));
            rejects("SSH_HOST_KEY_REVIEW", () -> SshManagedKnownHostsProvider.candidateFor(
                    snapshot, wrongBase, NEW_CONTENT));
            SshHostKeyAuthority.Evidence wrongReplacement = evidence(
                    SshHostKeyAuthority.Classification.NEW, VaultCodec.digest(OLD_CONTENT),
                    VaultCodec.digest(bytes("wrong replacement")));
            rejects("SSH_HOST_KEY_REVIEW", () -> SshManagedKnownHostsProvider.candidateFor(
                    snapshot, wrongReplacement, NEW_CONTENT));

            SshRoleContext otherRole = issueRole(1, "managed-hop-1", 8_002, 12);
            try {
                SshHostKeyAuthority.Evidence wrongHop = SshHostKeyAuthority.Evidence.fromNative(otherRole,
                        SshHostKeyAuthority.Classification.NEW, KEY_TYPE, FINGERPRINT,
                        VaultCodec.digest(OLD_CONTENT), VaultCodec.digest(NEW_CONTENT));
                rejects("SSH_HOST_KEY_REVIEW", () -> SshManagedKnownHostsProvider.candidateFor(
                        snapshot, wrongHop, NEW_CONTENT));
            } finally {
                registry.release(otherRole);
            }
        } finally {
            snapshot.close();
        }
        assertArrayEquals(OLD_CONTENT, readKnownHosts());
    }

    @Test public void wrongManagedObjectRoleAndProofFieldsFailClosed() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            SshTrustProposal exact = prepared.review();
            SshTrustProposal wrongType = new SshTrustProposal(role, PATH, OBJECT_ID,
                    VaultManifest.FileType.CONFIG, OBJECT_IDENTITY, TARGET, CONNECTION, HOST, 22,
                    KEY_TYPE, FINGERPRINT, VaultCodec.digest(OLD_CONTENT), NEW_CONTENT);
            try (SshTrustProposal unrelated = wrongType) {
                rejects("SSH_HOST_KEY_REVIEW", () -> prepared.adapter.trustRequest(role,
                        prepared.evidence, prepared.proof, prepared.snapshot, unrelated));
            }
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
            assertEquals(PATH, exact.path);
        }

        assertWriterRejectsChangedEvidence("ssh-rsa", FINGERPRINT,
                SshHostKeyAuthority.Classification.NEW, OLD_CONTENT, NEW_CONTENT,
                "SSH_HOST_PROOF_INVALID");
        assertWriterRejectsChangedEvidence(KEY_TYPE, "SHA256:" + "B".repeat(43),
                SshHostKeyAuthority.Classification.NEW, OLD_CONTENT, NEW_CONTENT,
                "SSH_HOST_PROOF_INVALID");
        assertWriterRejectsChangedEvidence(KEY_TYPE, FINGERPRINT,
                SshHostKeyAuthority.Classification.NEW, "wrong base", NEW_CONTENT,
                "SSH_HOST_PROOF_INVALID");
        assertWriterRejectsChangedEvidence(KEY_TYPE, FINGERPRINT,
                SshHostKeyAuthority.Classification.NEW, OLD_CONTENT, "wrong replacement",
                "SSH_HOST_PROOF_INVALID");
        assertWriterRejectsChangedEvidence(KEY_TYPE, FINGERPRINT,
                SshHostKeyAuthority.Classification.KNOWN, OLD_CONTENT, NEW_CONTENT,
                "SSH_HOST_KEY_CAS");
    }

    @Test public void staleBaseAndGenerationCannotCommitARealProviderProposal() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            rewriteVault(STALE_CONTENT, 7);
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(STALE_CONTENT, readKnownHosts());
        }

        store.bytes = encryptedFixture(7, OLD_CONTENT);
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            rewriteVault(STALE_CONTENT, 8);
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(STALE_CONTENT, readKnownHosts());
        }
    }

    @Test public void staleEpochAndDeadlineCannotCommit() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            vault.session.lock();
            vault.unlock(PASSWORD, () -> { });
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            clock.value = role.deadline;
            rejects("SSH_ROLE_EXPIRED", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }
    }

    @Test public void missingApprovalValidatorAndCandidateFailClosed() throws Exception {
        try (Prepared prepared = prepared(false, true, true)) {
            rejects("VAULT_TRUST_REVIEW", prepared::review);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, false, true)) {
            prepared.review();
            rejects("VAULT_TRUST_REVIEW", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            vault.attachSshValidator(null);
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        // Restore the test-only validator so the next assertion isolates a missing candidate.
        vault.attachSshValidator(manifest -> { });
        SshVaultAdapter adapter = adapter(true, true);
        SshManagedKnownHostsProvider.SnapshotReceipt snapshot =
                SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock, ROOT, PATH,
                        OBJECT_ID, OBJECT_IDENTITY);
        try {
            rejects("SSH_HOST_KEY_REVIEW", () -> SshManagedKnownHostsProvider.bind(adapter, snapshot, null));
        } finally {
            snapshot.close();
        }
        assertArrayEquals(OLD_CONTENT, readKnownHosts());
    }

    @Test public void cancellationCloseAndCommitFailureNeverReturnCommittedReceipt() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            VaultOperation request = prepared.adapter.trustRequest(role, prepared.evidence,
                    prepared.proof, prepared.snapshot, prepared.proposal);
            prepared.adapter.cancel(request);
            rejects("VAULT_OPERATION", () -> prepared.adapter.updateTrust(request, role,
                    prepared.evidence, prepared.proof, prepared.snapshot, prepared.proposal, () -> { }));
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            prepared.provider.close();
            rejects("VAULT_EXPIRED", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            rejects("SSH_HOST_KEY_CAS", () -> prepared.managed.writeBack(null,
                    prepared.proof, prepared.proposal));
            try {
                prepared.proposal.requireOpen();
                fail("an invalid write-back must close its proposal body");
            } catch (VaultException failure) {
                assertEquals("VAULT_EXPIRED", failure.code);
            }
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            store.fail = true;
            rejects("VAULT_IO", prepared::commit);
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }

        store.fail = false;
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            store.uncertain = true;
            rejects("VAULT_COMMIT_UNCERTAIN", prepared::commit);
            // The durable write happened, but no CasResult was allowed to escape.
            assertArrayEquals(NEW_CONTENT, readKnownHosts());
            assertFalse(vault.session.unlocked());
        }
    }

    private void assertWriterRejectsChangedEvidence(String keyType, String fingerprint,
            SshHostKeyAuthority.Classification classification, Object base, Object replacement,
            String code) throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            byte[] baseBytes = base instanceof byte[] ? (byte[])base : bytes((String)base);
            byte[] replacementBytes = replacement instanceof byte[]
                    ? (byte[])replacement : bytes((String)replacement);
            SshHostKeyAuthority.Evidence changed = evidence(classification,
                    VaultCodec.digest(baseBytes), VaultCodec.digest(replacementBytes), keyType, fingerprint);
            rejects(code, () -> prepared.managed.writeBack(changed, prepared.proof, prepared.proposal));
            assertArrayEquals(OLD_CONTENT, readKnownHosts());
        }
    }

    private SshTrustProposal.CasResult commitNativeCandidate(NativeHandoffPrepared prepared)
            throws Exception {
        byte[] transferred = NEW_CONTENT.clone();
        prepared.handoff.accept(prepared.evidence, "native-candidate-cas", transferred);
        try (SshTrustProposal proposal = prepared.managed.proposalFor(prepared.evidence)) {
            SshHostKeyAuthority.Proof proof = prepared.authority.requireVerified(
                    role, prepared.evidence, proposal);
            return prepared.managed.writeBack(prepared.evidence, proof, proposal);
        }
    }

    private final class NativeHandoffPrepared implements AutoCloseable {
        final SshVaultAdapter adapter;
        final SshHostKeyAuthority authority;
        final SshHostKeyAuthority.Evidence evidence;
        final SshManagedKnownHostsProvider.SnapshotReceipt snapshot;
        final SshManagedKnownHostsProvider provider;
        final SshKnownHostsCandidateHandoff handoff;
        final SshManagedProvider managed;

        NativeHandoffPrepared() throws Exception {
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = null;
            SshManagedKnownHostsProvider exactProvider = null;
            SshKnownHostsCandidateHandoff exactHandoff = null;
            try {
                adapter = adapter(true, true);
                authority = new SshHostKeyAuthority(review -> { });
                evidence = evidence(SshHostKeyAuthority.Classification.NEW,
                        digest(OLD_CONTENT), digest(NEW_CONTENT));
                receipt = SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock, ROOT, PATH,
                        OBJECT_ID, OBJECT_IDENTITY);
                exactProvider = SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, receipt);
                exactHandoff = exactProvider.nativeCandidateHandoff();
                snapshot = receipt;
                provider = exactProvider;
                handoff = exactHandoff;
                managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                        clock, provider, provider, null);
            } catch (Exception | Error failure) {
                if (exactHandoff != null) exactHandoff.close();
                if (exactProvider != null) exactProvider.close();
                else if (receipt != null) receipt.close();
                throw failure;
            }
        }

        @Override public void close() {
            handoff.close();
            provider.close();
        }
    }

    private final class Prepared implements AutoCloseable {
        final SshVaultAdapter adapter;
        final SshHostKeyAuthority authority;
        final SshHostKeyAuthority.Evidence evidence;
        final SshManagedKnownHostsProvider.SnapshotReceipt snapshot;
        final SshManagedKnownHostsProvider.KnownHostsCandidate candidate;
        final SshManagedKnownHostsProvider provider;
        final SshManagedProvider managed;
        SshTrustProposal proposal;
        SshHostKeyAuthority.Proof proof;

        Prepared(boolean reviewerApproval, boolean trustApproval, boolean hostApproval) throws Exception {
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = null;
            SshManagedKnownHostsProvider.KnownHostsCandidate exactCandidate = null;
            SshManagedKnownHostsProvider exactProvider = null;
            try {
                authority = new SshHostKeyAuthority(review -> {
                    if (!reviewerApproval) throw new VaultException("VAULT_TRUST_REVIEW",
                            "Fixture approval is unavailable");
                });
                adapter = new SshVaultAdapter(vault, (connection, target) -> {
                    if (!hostApproval) throw new VaultException("HOST_KEY", "Fixture host proof is unavailable");
                }, exact -> {
                    if (!trustApproval) throw new VaultException("VAULT_TRUST_REVIEW",
                            "Fixture proposal approval is unavailable");
                });
                evidence = evidence(SshHostKeyAuthority.Classification.NEW,
                        VaultCodec.digest(OLD_CONTENT), VaultCodec.digest(NEW_CONTENT));
                receipt = SshManagedKnownHostsProvider.snapshotFor(adapter, role, clock, ROOT, PATH,
                        OBJECT_ID, OBJECT_IDENTITY);
                exactCandidate = SshManagedKnownHostsProvider.candidateFor(receipt, evidence, NEW_CONTENT);
                exactProvider = SshManagedKnownHostsProvider.bind(adapter, receipt, exactCandidate);
                snapshot = receipt;
                candidate = exactCandidate;
                provider = exactProvider;
                managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                        clock, provider, provider, null);
            } catch (Exception | Error failure) {
                if (exactProvider != null) exactProvider.close();
                else if (exactCandidate != null) exactCandidate.close();
                else if (receipt != null) receipt.close();
                throw failure;
            }
        }

        SshTrustProposal review() throws VaultException {
            proposal = managed.proposalFor(evidence);
            proof = authority.requireVerified(role, evidence, proposal);
            return proposal;
        }

        SshTrustProposal.CasResult commit() throws VaultException {
            return managed.writeBack(evidence, proof, proposal);
        }

        @Override public void close() {
            if (proposal != null) proposal.close();
            provider.close();
        }
    }

    private Prepared prepared(boolean reviewerApproval, boolean trustApproval, boolean hostApproval)
            throws Exception {
        return new Prepared(reviewerApproval, trustApproval, hostApproval);
    }

    @Test public void exactOwnCasKeepsLoanButAdvancesEpochAndOldCancellationStillRevokes() throws Exception {
        long epoch = vault.session.epoch();
        AppSecrets.SshPublicReadContext origin = vault.newSshPublicReadContext(epoch,
                clock.value + 120000, () -> { });
        VaultOperation fileRequest = vault.beginSshFiles(ROOT, CONNECTION, origin);
        try (AppSecrets.SshPublicReadPreparation prepared = vault.prepareSshPublicRead(fileRequest, ROOT, CONNECTION)) {
            vault.attachSshPublicRead(prepared, rootView);
        }
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            assertEquals(SshTrustProposal.CasStatus.COMMITTED, prepared.commit().status);
            assertEquals(epoch + 1, vault.session.epoch());
            assertTrue(rootView.active());
            role.requireActive(clock);
            rejects("VAULT_OPERATION", () -> vault.sshFiles(fileRequest, ROOT, CONNECTION, files -> fail("old operation")));
            origin.cancel();
            assertFalse(rootView.active());
        }
    }

    @Test public void publicSnapshotRetainsOriginalRoleDeadlineAndNeverReplacesItsView() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        clock.value = role.deadline - 1;
        try (SshManagedKnownHostsProvider.SnapshotReceipt receipt =
                SshManagedKnownHostsProvider.snapshotFor(adapter(true, true), role, clock, ROOT, PATH,
                        OBJECT_ID, OBJECT_IDENTITY)) {
            assertEquals(role.deadline, receipt.deadline);
            assertTrue(rootView.active());
            assertTrue(vault.session.attachedRevocation(vault.session.epoch()) == rootView);
            clock.value = role.deadline;
            rejects("SSH_ROLE_EXPIRED", receipt::requireLive);
        }
        assertTrue(rootView.active());
    }

    @Test public void childTrustReceiptAndWireShareOneCurrentEncryptedVaultSnapshot() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        SshRoleContext child = issueRole(1, CONNECTION, 8_002, 21, rootView);
        SshVaultAdapter adapter = adapter(true, true);
        try (SshManagedKnownHostsProvider.ChildTrustSnapshot before =
                SshManagedKnownHostsProvider.snapshotForChild(adapter, child, clock, rootView,
                        ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
            SshManagedKnownHostsProvider.SnapshotReceipt old = before.receipt();
            assertEquals(child, old.role);
            assertEquals(child.deadline, old.deadline);
            byte[] wire = before.takeWire();
            try { assertArrayEquals(OLD_CONTENT, childTrustBody(wire, old)); }
            finally { VaultCodec.wipe(wire); }
            rejects("VAULT_EXPIRED", before::takeWire);

            try (Prepared firstHop = prepared(true, true, true)) {
                firstHop.review();
                assertEquals(SshTrustProposal.CasStatus.COMMITTED, firstHop.commit().status);
            }
            assertTrue(rootView.active());
            assertEquals(old.epoch + 1, vault.session.epoch());
            rejects("VAULT_CHANGED", before::requireCurrent);
            try (SshManagedKnownHostsProvider.ChildTrustSnapshot after =
                    SshManagedKnownHostsProvider.snapshotForChild(adapter, child, clock, rootView,
                            ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
                SshManagedKnownHostsProvider.SnapshotReceipt current = after.receipt();
                assertEquals(old.epoch + 1, current.epoch);
                assertEquals(old.generation, current.generation);
                assertFalse(Arrays.equals(old.binding(), current.binding()));
                assertArrayEquals(VaultCodec.digest(NEW_CONTENT), current.baseDigest());
                byte[] freshWire = after.takeWire();
                try { assertArrayEquals(NEW_CONTENT, childTrustBody(freshWire, current)); }
                finally { VaultCodec.wipe(freshWire); }
            }
        } finally { child.close(); }
    }

    @Test public void childTrustSnapshotRejectsWrongViewAndRevocation() throws Exception {
        PublicView other = new PublicView();
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        SshVaultAdapter adapter = adapter(true, true);
        rejects("VAULT_CHANGED", () -> SshManagedKnownHostsProvider.snapshotForChild(adapter, role,
                clock, other, ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY));
        try (SshManagedKnownHostsProvider.ChildTrustSnapshot source =
                SshManagedKnownHostsProvider.snapshotForChild(adapter, role, clock, rootView,
                        ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
            vault.lock();
            assertFalse(rootView.active());
            rejects("SSH_FILE_VIEW", source::requireCurrent);
            rejects("SSH_FILE_VIEW", source::takeWire);
        }
    }

    @Test public void nativeHandoffObserverMustAcceptTheReceiptRoleBeforeAnyWrite() throws Exception {
        SshRoleContext wrong = issueRole(1, CONNECTION, 8_002, 21, rootView);
        byte[] before = store.bytes.clone();
        java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();
        SshManagedKnownHostsProvider.TrustCommitObserver observer =
                new SshManagedKnownHostsProvider.TrustCommitObserver() {
                    @Override public void persisted() { written.incrementAndGet(); }
                    @Override public void requireRole(SshRoleContext actual) throws VaultException {
                        checked.incrementAndGet();
                        if (actual != wrong) throw new VaultException("SSH_ROLE_REQUIRED", "wrong fixture role");
                    }
                };
        try (SshManagedKnownHostsProvider.SnapshotReceipt receipt =
                SshManagedKnownHostsProvider.snapshotFor(adapter(true, true), role, clock,
                        ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
            rejects("SSH_ROLE_REQUIRED", () -> SshManagedKnownHostsProvider.bindForNativeHandoff(
                    adapter(true, true), receipt, observer));
            assertEquals(1, checked.get());
            assertEquals(0, written.get());
            assertArrayEquals(before, store.bytes);
        } finally { wrong.close(); VaultCodec.wipe(before); }
    }

    @Test public void childTrustReceiptChecksAllNativeClassificationsAndExactRole() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        SshRoleContext child = issueRole(1, CONNECTION, 8_002, 21, rootView);
        SshRoleContext wrong = issueRole(2, CONNECTION, 8_003, 31, rootView);
        try (SshManagedKnownHostsProvider.ChildTrustSnapshot source =
                SshManagedKnownHostsProvider.snapshotForChild(adapter(true, true), child, clock,
                        rootView, ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
            source.requireFor(child, PATH);
            rejects("SSH_HOST_KEY_REVIEW", () -> source.requireFor(wrong, PATH));
            rejects("SSH_HOST_KEY_REVIEW", () -> source.requireFor(child, "other"));
            for (SshHostKeyAuthority.Classification kind : new SshHostKeyAuthority.Classification[] {
                    SshHostKeyAuthority.Classification.KNOWN,
                    SshHostKeyAuthority.Classification.NEW,
                    SshHostKeyAuthority.Classification.CHANGED }) {
                SshHostKeyAuthority.Evidence exact = SshHostKeyAuthority.Evidence.fromNative(child, kind,
                        KEY_TYPE, FINGERPRINT, digest(OLD_CONTENT), digest(NEW_CONTENT));
                SshHostKeyAuthority.Evidence altered = SshHostKeyAuthority.Evidence.fromNative(child, kind,
                        KEY_TYPE, FINGERPRINT, digest(STALE_CONTENT), digest(NEW_CONTENT));
                source.requireEvidence(child, exact);
                rejects("SSH_HOST_KEY_REVIEW", () -> source.requireEvidence(child, altered));
                rejects("SSH_HOST_KEY_REVIEW", () -> source.requireEvidence(wrong, exact));
            }
            vault.lock();
            rejects("SSH_FILE_VIEW", source::requireCurrent);
        } finally { child.close(); wrong.close(); }
    }

    /** Real encrypted vault and production Java callback, with a fake native callback binding. */
    private final class FreshCallbackFixture implements AutoCloseable {
        final SshNativeRoleBootstrap.Issued issued;
        final SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        final SshManagedKnownHostsProvider provider;
        final SshNativeCallbackAdapter callback;
        final SshNativeCallbackAdapter.NativeSessionClaim claim;
        boolean closed;

        FreshCallbackFixture() throws Exception {
            vault.session.attachRevocation(vault.session.epoch(), rootView);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(encoded);
            output.writeInt(0x43535242); output.writeByte(1); output.writeByte(1); output.writeShort(0);
            output.writeLong(8_002); output.writeInt(8_002); output.writeInt(1001);
            output.writeInt(1); output.writeInt(22); output.writeLong(clock.value + 30_000);
            output.write(bytes(32, 21)); output.write(bytes(32, 22));
            for (String value : new String[] { CONNECTION, TARGET, HOST, "view-1" }) {
                byte[] field = bytes(value); output.writeShort(field.length); output.write(field);
            }
            output.flush();
            issued = SshNativeRoleBootstrap.issue(encoded.toByteArray(), registry, rootView, clock, ignored -> true);
            java.util.concurrent.atomic.AtomicReference<SshHostKeyAuthority.ChangedReview> approved =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> { }, approved::set);
            SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> { }, proposal -> { },
                    proposal -> { throw new VaultException("VAULT_TRUST_REVIEW", "fixture bundle refused"); },
                    (review, proposal) -> {
                        if (review != approved.get())
                            throw new VaultException("SSH_HOST_KEY_REVIEW", "wrong fixture review");
                    });
            source = SshManagedKnownHostsProvider.snapshotForChild(adapter, issued.role, clock, rootView,
                    ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY);
            provider = SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, source.receipt());
            SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff();
            SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                    clock, provider, provider, null);
            SshNativeCallbackAdapter.NativeBinding nativeBinding = new SshNativeCallbackAdapter.NativeBinding() {
                @Override public long open(SshNativeCallbackAdapter target, long role, int uid, long deadline) {
                    return 81;
                }
                @Override public void cancel(long token) { }
                @Override public void close(long token) { }
            };
            callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock, nativeBinding,
                    (token, uid) -> { }, managed, handoff);
            callback.bindChildTrustSnapshot(source, PATH);
            claim = callback.beginNativeSessionOpen();
            callback.finishNativeSessionOpen(claim, true);
            callback.activateChildTrustSnapshot(source, PATH);
        }

        byte[] evidence(int classification, byte[] base) {
            return callback.onNativeHostEvidence(issued.token, issued.role.hop, issued.role.port,
                    issued.role.connectionId, issued.role.targetIdentity, issued.role.host,
                    classification, KEY_TYPE, FINGERPRINT, base, digest(NEW_CONTENT));
        }

        void refuseCandidate(boolean changed) throws Exception {
            byte[] body = NEW_CONTENT.clone();
            byte[] rows = changed ? SshHostTrustMaterialTest.wire(OLD_CONTENT, false, null,
                    new int[][] { {1, 0, 0, 0} }) : null;
            boolean accepted = changed
                    ? callback.onNativeChangedHostCandidate(issued.token, 1, 22, CONNECTION, TARGET, HOST,
                        3, KEY_TYPE, FINGERPRINT, digest(OLD_CONTENT), digest(NEW_CONTENT), "candidate-1", body, rows)
                    : callback.onNativeKnownHostsCandidate(issued.token, 1, 22, CONNECTION, TARGET, HOST,
                        2, KEY_TYPE, FINGERPRINT, digest(OLD_CONTENT), digest(NEW_CONTENT), "candidate-1", body);
            assertFalse(accepted);
            assertArrayEquals(new byte[body.length], body);
            if (rows != null) assertArrayEquals(new byte[rows.length], rows);
            assertEquals("SSH_JUMP_EFFECT_GUARD", callback.lastFailureCode());
            // Native invokes this rollback after the Java candidate refusal.
            callback.abortNativeKnownHostsCandidate();
        }

        @Override public void close() throws Exception {
            if (closed) return;
            callback.nativeClientClosed(claim);
            callback.releaseRoleAfterNativeClient();
            source.close();
            rootView.revoke();
            closed = true;
        }
    }

    @Test public void freshCallbackRequiresSameNativeHostfileBeforeKnownProofOrKex() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] result = fixture.evidence(1, digest(OLD_CONTENT));
            assertTrue(result != null && result.length > 0);
            assertTrue(fixture.callback.hostProof() != null);
            boolean complete = fixture.callback.onNativeKexComplete(1, bytes(32, 7));
            assertTrue(fixture.callback.lastFailureCode(), complete);
            assertTrue(fixture.callback.kexProof() != null);
            VaultCodec.wipe(result);
        }
    }

    @Test public void freshCallbackRejectsChangedBaseBeforeAnyHostProofOrWrite() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] before = store.bytes.clone();
            assertTrue(fixture.evidence(1, digest(STALE_CONTENT)) == null);
            assertTrue(fixture.callback.hostProof() == null);
            assertArrayEquals(before, store.bytes);
            VaultCodec.wipe(before);
        }
    }

    @Test public void freshKnownProofExpiresBeforeKexWhenTheVaultLocks() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] result = fixture.evidence(1, digest(OLD_CONTENT));
            assertTrue(result != null);
            vault.lock();
            assertFalse(fixture.callback.onNativeKexComplete(1, bytes(32, 7)));
            assertTrue(fixture.callback.kexProof() == null);
            VaultCodec.wipe(result);
        }
    }

    @Test public void freshNewHostIsFencedBeforeCandidateOrVaultWrite() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] before = store.bytes.clone(), body = NEW_CONTENT.clone();
            assertFalse(fixture.callback.onNativeKnownHostsCandidate(fixture.issued.token, 1, 22,
                    CONNECTION, TARGET, HOST, 2, KEY_TYPE, FINGERPRINT,
                    digest(OLD_CONTENT), digest(NEW_CONTENT), "candidate-1", body));
            assertArrayEquals(new byte[body.length], body);
            assertEquals("SSH_JUMP_EFFECT_GUARD", fixture.callback.lastFailureCode());
            assertArrayEquals(before, store.bytes);
            assertTrue(fixture.callback.hostProof() == null);
            assertTrue(rootView.active());
            VaultCodec.wipe(before);
        }
    }

    @Test public void freshChangedHostIsFencedBeforeReviewOrVaultWrite() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] rows = SshHostTrustMaterialTest.wire(OLD_CONTENT, false, null,
                    new int[][] { {1, 0, 0, 0} });
            byte[] before = store.bytes.clone(), body = NEW_CONTENT.clone();
            assertFalse(fixture.callback.onNativeChangedHostCandidate(fixture.issued.token, 1, 22,
                    CONNECTION, TARGET, HOST, 3, KEY_TYPE, FINGERPRINT, digest(OLD_CONTENT),
                    digest(NEW_CONTENT), "candidate-1", body, rows));
            assertEquals("SSH_JUMP_EFFECT_GUARD", fixture.callback.lastFailureCode());
            assertArrayEquals(before, store.bytes);
            assertArrayEquals(new byte[body.length], body);
            assertArrayEquals(new byte[rows.length], rows);
            assertTrue(fixture.callback.hostProof() == null);
            assertTrue(rootView.active());
            VaultCodec.wipe(before);
        }
    }

    @Test public void jumpTrustRefusalReportsOnlyExactNewAndChangedAfterNativeRollback() throws Exception {
        for (boolean changed : new boolean[] { false, true }) {
            if (changed) rootView = new PublicView();
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                byte[] before = store.bytes.clone();
                assertFalse(fixture.callback.reportableJumpTrustRefusal());
                fixture.refuseCandidate(changed);
                int reads = store.reads;
                assertTrue(fixture.callback.reportableJumpTrustRefusal());
                assertEquals(reads, store.reads);
                assertTrue(fixture.callback.hostProof() == null && fixture.callback.kexProof() == null);
                assertArrayEquals(before, store.bytes);
                VaultCodec.wipe(before);
            }
        }
    }

    @Test public void jumpTrustRefusalRejectsWrongRoleOrHostfileEvidence() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            if (mode != 0) rootView = new PublicView();
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                byte[] body = NEW_CONTENT.clone();
                assertFalse(fixture.callback.onNativeKnownHostsCandidate(
                        mode == 0 ? fixture.issued.token + 1 : fixture.issued.token,
                        mode == 1 ? 2 : 1, 22, CONNECTION, TARGET, HOST, 2, KEY_TYPE, FINGERPRINT,
                        digest(mode == 2 ? STALE_CONTENT : OLD_CONTENT), digest(NEW_CONTENT), "candidate-1", body));
                fixture.callback.abortNativeKnownHostsCandidate();
                assertFalse(fixture.callback.reportableJumpTrustRefusal());
                assertTrue(fixture.callback.hostProof() == null);
            }
        }
    }

    @Test public void jumpTrustRefusalLosesToCancellationLockViewExpiryAndClosedSource() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            if (mode != 0) rootView = new PublicView();
            clock.value = 1_000;
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                fixture.refuseCandidate(false);
                assertTrue(fixture.callback.reportableJumpTrustRefusal());
                if (mode == 0) fixture.callback.cancel();
                else if (mode == 1) rootView.revoke();
                else if (mode == 2) clock.value = fixture.issued.role.deadline;
                else if (mode == 3) fixture.source.close();
                else if (mode == 4) fixture.close();
                else vault.lock();
                assertFalse(fixture.callback.reportableJumpTrustRefusal());
            }
        }
    }

    @Test public void jumpTrustRefusalDoesNotOverrideUncertainCommit() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            fixture.refuseCandidate(false);
            assertTrue(fixture.callback.reportableJumpTrustRefusal());
            java.lang.reflect.Field failure = SshNativeCallbackAdapter.class.getDeclaredField("failureCode");
            failure.setAccessible(true);
            failure.set(fixture.callback, "VAULT_COMMIT_UNCERTAIN");
            assertFalse(fixture.callback.reportableJumpTrustRefusal());
            assertEquals("VAULT_COMMIT_UNCERTAIN", fixture.callback.lastFailureCode());
        }
    }

    @Test public void jumpTrustRefusalChecksSourceWithoutHoldingCallbackLock() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            fixture.refuseCandidate(false);
            clock.blockOnCall(1);
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread cancel = new Thread(() -> {
                try { awaitLatch(clock.entered); fixture.callback.cancel(); }
                catch (Throwable failure) { problem.set(failure); }
                finally { clock.release.countDown(); }
            });
            cancel.start();
            assertFalse(fixture.callback.reportableJumpTrustRefusal());
            cancel.join(5_000);
            assertFalse(cancel.isAlive());
            if (problem.get() != null) throw new AssertionError(problem.get());
        }
    }

    @Test public void freshLegacyHostCallbackAndSelectedCredentialCannotBypassEffectFence() throws Exception {
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] before = store.bytes.clone();
            assertFalse(fixture.callback.onNativeHostKeyForTest(2, KEY_TYPE, FINGERPRINT,
                    digest(OLD_CONTENT), digest(NEW_CONTENT), null));
            assertEquals("SSH_JUMP_EFFECT_GUARD", fixture.callback.lastFailureCode());
            assertArrayEquals(before, store.bytes);
            VaultCodec.wipe(before);
        }
        rootView = new PublicView();
        try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
            byte[] result = fixture.evidence(1, digest(OLD_CONTENT));
            assertTrue(result != null);
            assertTrue(fixture.callback.onNativeKexComplete(1, bytes(32, 7)));
            int reads = store.reads;
            assertTrue(fixture.callback.onNativeSelectedCredentialBytes(1, bytes(32, 4), 1,
                    bytes(32, 7)) == null);
            assertEquals("SSH_JUMP_EFFECT_GUARD", fixture.callback.lastFailureCode());
            assertEquals(reads, store.reads);
            VaultCodec.wipe(result);
        }
    }

    @Test public void freshCredentialFenceRetainsLifecycleChangesAfterCallbackBegin() throws Exception {
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            if (lifecycle != 0) rootView = new PublicView();
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                byte[] before = store.bytes.clone();
                int reads = store.reads;
                clock.blockOnCall(3); // begin's two reads, then the effect guard outside stateLock
                int mode = lifecycle;
                AtomicReference<Throwable> problem = new AtomicReference<>();
                Thread change = new Thread(() -> {
                    try {
                        awaitLatch(clock.entered);
                        if (mode == 0) fixture.callback.cancel();
                        else if (mode == 1) clock.value = fixture.issued.role.deadline;
                        else rootView.revoke();
                    } catch (Throwable failure) { problem.set(failure); }
                    finally { clock.release.countDown(); }
                });
                change.start();
                assertTrue(fixture.callback.onNativeCredentialBytes(1) == null);
                change.join(5000);
                assertFalse(change.isAlive());
                if (problem.get() != null) throw new AssertionError(problem.get());
                assertEquals(lifecycle == 0 ? "SSH_CALLBACK_CANCELLED"
                        : lifecycle == 1 ? "SSH_ROLE_EXPIRED" : "SSH_FILE_VIEW",
                        fixture.callback.lastFailureCode());
                assertEquals(reads, store.reads);
                assertArrayEquals(before, store.bytes);
                assertTrue(fixture.callback.hostProof() == null);
                VaultCodec.wipe(before);
            }
            clock.value = 1_000;
        }
    }

    @Test public void callbackErrorAbortsExactCandidateAndReleasesInvocation() throws Exception {
        for (int entry = 0; entry < 3; entry++) {
            if (entry != 0) rootView = new PublicView();
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                byte[] before = store.bytes.clone(), body = NEW_CONTENT.clone();
                int reads = store.reads;
                AssertionError injected = new AssertionError("injected after callback begin");
                clock.failOnCall(3, injected);
                try {
                    if (entry == 0) fixture.callback.onNativeKnownHostsCandidate(fixture.issued.token, 1, 22,
                            CONNECTION, TARGET, HOST, 2, KEY_TYPE, FINGERPRINT,
                            digest(OLD_CONTENT), digest(NEW_CONTENT), "candidate-1", body);
                    else if (entry == 1) fixture.callback.onNativeHostKeyForTest(2, KEY_TYPE, FINGERPRINT,
                            digest(OLD_CONTENT), digest(NEW_CONTENT), null);
                    else fixture.callback.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD);
                    fail("the original Error must escape");
                } catch (AssertionError actual) { assertSame(injected, actual); }
                if (entry == 0) assertArrayEquals(new byte[body.length], body);
                if (entry < 2) assertFalse(fixture.provider.ownsChildSnapshot(fixture.source));
                assertEquals("SSH_CALLBACK_JAVA_EXCEPTION", fixture.callback.lastFailureCode());
                assertEquals(reads, store.reads);
                assertArrayEquals(before, store.bytes);
                assertTrue(fixture.callback.hostProof() == null);
                fixture.close(); // fails if the callback still owns an in-flight invocation
                VaultCodec.wipe(before);
                VaultCodec.wipe(body);
            }
        }
    }

    @Test public void freshCallbackRejectsWrongNewAndChangedBaseBeforeCandidates() throws Exception {
        for (boolean changed : new boolean[] { false, true }) {
            // Each failure retires its callback and leaves the encrypted vault unchanged.
            if (changed) rootView = new PublicView();
            try (FreshCallbackFixture fixture = new FreshCallbackFixture()) {
                byte[] before = store.bytes.clone(), body = NEW_CONTENT.clone(), old = bytes("old-records");
                boolean accepted = changed
                        ? fixture.callback.onNativeChangedHostCandidate(fixture.issued.token, 1, 22,
                            CONNECTION, TARGET, HOST, 3, KEY_TYPE, FINGERPRINT,
                            digest(STALE_CONTENT), digest(NEW_CONTENT), "candidate-1", body, old)
                        : fixture.callback.onNativeKnownHostsCandidate(fixture.issued.token, 1, 22,
                            CONNECTION, TARGET, HOST, 2, KEY_TYPE, FINGERPRINT,
                            digest(STALE_CONTENT), digest(NEW_CONTENT), "candidate-1", body);
                assertFalse(accepted);
                assertTrue(fixture.callback.hostProof() == null);
                assertArrayEquals(before, store.bytes);
                assertArrayEquals(new byte[body.length], body);
                if (changed) assertArrayEquals(new byte[old.length], old);
                VaultCodec.wipe(before);
            }
        }
    }

    @Test public void childTrustCancellationDoesNotWaitForVaultValidation() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch revoked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        vault.attachSshValidator(manifest -> {
            assertFalse(Thread.holdsLock(vault.session));
            assertFalse(Thread.holdsLock(vault.publicationLock));
            assertFalse(Thread.holdsLock(role));
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Fixture timeout");
            } catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        Thread reader = new Thread(() -> {
            try (SshManagedKnownHostsProvider.ChildTrustSnapshot ignored =
                    SshManagedKnownHostsProvider.snapshotForChild(adapter(true, true), role, clock,
                            rootView, ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
                outcome.set(new AssertionError("revoked snapshot was returned"));
            } catch (Throwable failure) { outcome.set(failure); }
        });
        reader.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread canceller = new Thread(() -> { rootView.revoke(); revoked.countDown(); });
        canceller.start();
        try { assertTrue("revocation waited for validation", revoked.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { release.countDown(); reader.join(5000); canceller.join(5000); }
        assertFalse(reader.isAlive());
        assertFalse(canceller.isAlive());
        assertTrue(outcome.get() instanceof VaultException);
        assertEquals("SSH_FILE_VIEW", ((VaultException)outcome.get()).code);
    }

    @Test public void childTrustWireHasAtMostOneConcurrentOwner() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        try (SshManagedKnownHostsProvider.ChildTrustSnapshot source =
                SshManagedKnownHostsProvider.snapshotForChild(adapter(true, true), role, clock,
                        rootView, ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY)) {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<byte[]> first = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<byte[]> second = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> unexpected = new java.util.concurrent.atomic.AtomicReference<>();
            Runnable attempt = () -> {
                try {
                    start.await();
                    byte[] result = source.takeWire();
                    if (!first.compareAndSet(null, result)) second.set(result);
                } catch (VaultException expected) {
                    if (!"VAULT_EXPIRED".equals(expected.code)) unexpected.set(expected);
                } catch (Throwable failure) { unexpected.set(failure); }
            };
            Thread one = new Thread(attempt), two = new Thread(attempt);
            one.start(); two.start(); start.countDown(); one.join(5000); two.join(5000);
            assertFalse(one.isAlive()); assertFalse(two.isAlive());
            assertTrue(unexpected.get() == null);
            assertTrue(first.get() != null);
            assertTrue(second.get() == null);
            VaultCodec.wipe(first.get());
        }
    }

    /** Decode the exact wire independently of the Java encoder's object graph. */
    private byte[] childTrustBody(byte[] wire,
            SshManagedKnownHostsProvider.SnapshotReceipt receipt) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(wire);
        assertEquals(0x43535056, input.getInt());
        assertEquals(1, input.getInt());
        assertEquals(receipt.vaultId, new UUID(input.getLong(), input.getLong()));
        assertEquals(receipt.generation, input.getLong());
        assertEquals(receipt.epoch, input.getLong());
        assertEquals(receipt.deadline, input.getLong());
        assertEquals(receipt.requestId, wireText(input));
        assertEquals(receipt.connectionId, wireText(input));
        assertEquals(receipt.rootPath, wireText(input));
        byte[] binding = new byte[32]; input.get(binding);
        assertArrayEquals(receipt.binding(), binding);
        int count = input.getInt();
        byte[] selected = null;
        for (int i = 0; i < count; i++) {
            int type = input.get() & 0xff;
            String id = wireText(input), path = wireText(input), identity = wireText(input);
            int declared = input.getInt();
            int refs = input.getInt();
            for (int ref = 0; ref < refs; ref++) wireText(input);
            byte[] body = new byte[input.getInt()]; input.get(body);
            if (path.equals(receipt.path)) {
                assertEquals(5, type);
                assertEquals(receipt.objectId, id);
                assertEquals(receipt.objectIdentity, identity);
                assertEquals(declared, body.length);
                assertArrayEquals(receipt.baseDigest(), VaultCodec.digest(body));
                assertTrue(selected == null);
                selected = body;
            }
        }
        assertFalse(input.hasRemaining());
        assertTrue(selected != null);
        return selected;
    }

    private static String wireText(ByteBuffer input) {
        byte[] bytes = new byte[input.getInt()]; input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test public void roleCloseViewRevokeAndOriginalExpiryDuringEitherValidationRejectSnapshot() throws Exception {
        for (int stage = 1; stage <= 2; stage++) for (int kind = 0; kind < 3; kind++) {
            clock.value = 1000;
            PublicView view = new PublicView();
            SshRoleContext exactRole = issueRole(0, "snapshot-" + stage + "-" + kind,
                    8_100 + stage * 3 + kind, 20 + stage * 3 + kind, view);
            java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
            final int at = stage, problem = kind;
            vault.attachSshValidator(loan -> {
                assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
                assertFalse(Thread.holdsLock(vault.publicationLock)); assertFalse(Thread.holdsLock(exactRole));
                if (validations.incrementAndGet() == at) {
                    if (problem == 0) exactRole.close();
                    else if (problem == 1) view.revoke();
                    else clock.value = exactRole.deadline;
                }
            });
            rejects(kind == 1 ? "SSH_FILE_VIEW" : "SSH_ROLE_EXPIRED", () ->
                    SshManagedKnownHostsProvider.snapshotFor(adapter(true, true), exactRole, clock,
                            ROOT, PATH, OBJECT_ID, OBJECT_IDENTITY));
            assertEquals(stage, validations.get());
            exactRole.close();
        }
    }

    @Test public void unboundTrustMutationStillRevokesAttachedLoan() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        try (Prepared prepared = prepared(true, true, true)) {
            SshTrustProposal proposal = prepared.review();
            VaultOperation request = vault.beginTrustUpdate(proposal);
            vault.updateTrust(request, proposal, () -> { });
            assertFalse(rootView.active());
        }
    }

    @Test public void wrongAttachedLoanCannotBePreservedOrWritten() throws Exception {
        PublicView other = new PublicView();
        vault.session.attachRevocation(vault.session.epoch(), other);
        byte[] before = store.bytes.clone();
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(before, store.bytes);
            assertTrue(rootView.active()); assertTrue(other.active());
        } finally { vault.lock(); VaultCodec.wipe(before); }
    }

    @Test public void lockAfterDurableOwnCasNeverRestoresLoanKeyOrHostReceipt() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            store.afterCommit = vault::lock;
            rejects("VAULT_COMMIT_UNCERTAIN", prepared::commit);
            assertFalse(rootView.active());
            assertFalse(vault.session.unlocked());
        }
    }

    @Test public void originalPreapprovalContextRejectsValidatorReregistration() throws Exception {
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            vault.attachSshValidator(loan -> { });
            byte[] before = store.bytes.clone();
            rejects("VAULT_CHANGED", prepared::commit);
            assertArrayEquals(before, store.bytes);
        }
    }

    @Test public void closeDuringDetachedTrustAdmissionReturnsBeforeValidatorAndCancels() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        vault.attachSshValidator(loan -> {
            if (!armed.get()) return;
            assertFalse(Thread.holdsLock(vault)); assertFalse(Thread.holdsLock(vault.session));
            entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fixture timeout"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review(); armed.set(true);
            byte[] before = store.bytes.clone();
            java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
            Thread writer = new Thread(() -> { try { prepared.commit(); } catch (Throwable failure) { outcome.set(failure); } });
            writer.start(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
            Thread closer = new Thread(() -> { prepared.provider.close(); closed.countDown(); });
            closer.start();
            try { assertTrue("Provider close waited for validator", closed.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { release.countDown(); writer.join(5000); closer.join(5000); }
            assertFalse(writer.isAlive()); assertFalse(closer.isAlive());
            assertTrue(outcome.get() instanceof VaultException); assertArrayEquals(before, store.bytes);
        }
    }

    @Test public void deliveredRealCommitRejectedByManagedRoleCheckLocksAndReportsUncertain() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            SshManagedProvider.TrustCasWriter lateClose = new SshManagedProvider.TrustCasWriter() {
                @Override public SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
                        SshTrustProposal proposal) throws VaultException { throw VaultException.invalid(); }
                @Override public SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
                        SshHostKeyAuthority.Proof proof, SshTrustProposal proposal) throws VaultException {
                    SshTrustProposal.CasResult result = prepared.provider.write(evidence, proof, proposal);
                    role.close();
                    return result;
                }
                @Override public VaultException invalidateCommittedResult() {
                    return prepared.provider.invalidateCommittedResult();
                }
            };
            SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, prepared.authority, clock),
                    clock, prepared.provider, lateClose, null);
            rejects("VAULT_COMMIT_UNCERTAIN", () -> managed.writeBack(prepared.evidence, prepared.proof, prepared.proposal));
            assertFalse(vault.session.unlocked()); assertFalse(rootView.active());
        }
    }

    @Test public void directRoleBoundCoreCannotContinueAfterFinalGuardClosesRole() throws Exception {
        vault.session.attachRevocation(vault.session.epoch(), rootView);
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            VaultOperation request = prepared.adapter.trustRequest(role, prepared.evidence, prepared.proof,
                    prepared.snapshot, prepared.proposal);
            java.util.concurrent.atomic.AtomicBoolean persisted = new java.util.concurrent.atomic.AtomicBoolean();
            store.afterCommit = () -> persisted.set(true);
            rejects("VAULT_COMMIT_UNCERTAIN", () -> vault.updateTrust(request, prepared.proposal, () -> {
                        if (persisted.get()) role.close();
                    }));
            assertTrue(persisted.get()); assertFalse(vault.session.unlocked()); assertFalse(rootView.active());
        }
    }

    private NativeHandoffPrepared nativeHandoffPrepared() throws Exception {
        return new NativeHandoffPrepared();
    }

    private SshVaultAdapter adapter(boolean hostApproval, boolean trustApproval) {
        return new SshVaultAdapter(vault, (connection, target) -> {
            if (!hostApproval) throw new VaultException("HOST_KEY", "Fixture host proof is unavailable");
        }, proposal -> {
            if (!trustApproval) throw new VaultException("VAULT_TRUST_REVIEW",
                    "Fixture proposal approval is unavailable");
        });
    }

    private SshHostKeyAuthority.Evidence evidence(SshHostKeyAuthority.Classification classification,
            byte[] base, byte[] replacement) throws VaultException {
        return evidence(classification, base, replacement, KEY_TYPE, FINGERPRINT);
    }

    private SshHostKeyAuthority.Evidence evidence(SshHostKeyAuthority.Classification classification,
            byte[] base, byte[] replacement, String keyType, String fingerprint) throws VaultException {
        return SshHostKeyAuthority.Evidence.fromNative(role, classification, keyType, fingerprint,
                base, replacement);
    }

    private static byte[] digest(String value) {
        return VaultCodec.digest(bytes(value));
    }

    private static byte[] digest(byte[] value) {
        return VaultCodec.digest(value);
    }

    private SshRoleContext issueRole(int hop, String connectionId, int ownerPid, int seed)
            throws VaultException {
        return issueRole(hop, connectionId, ownerPid, seed, new PublicView());
    }

    private SshRoleContext issueRole(int hop, String connectionId, int ownerPid, int seed, PublicView view)
            throws VaultException {
        return registry.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, ownerPid, hop,
                connectionId, TARGET, HOST, 22, "view-" + hop, bytes(32, seed), bytes(32, seed + 1),
                clock.value + 30_000), view);
    }

    private byte[] readKnownHosts() throws Exception {
        try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
            for (VaultManifest.FileObject file : verified.manifest.files) {
                if (file.path.equals(PATH)) return file.content.clone();
            }
        }
        throw new AssertionError("known_hosts fixture object is missing");
    }

    private void rewriteVault(byte[] content, long generation) throws Exception {
        VaultEnvelope current = VaultEnvelope.parse(store.bytes);
        byte[] key = VaultEnvelope.derive(PASSWORD, current.salt);
        byte[] plaintext = null;
        try (AppSecrets.Verified verified = AppSecrets.verify(store.bytes, PASSWORD)) {
            List<VaultManifest.FileObject> files = new ArrayList<>();
            for (VaultManifest.FileObject file : verified.manifest.files) {
                files.add(file.path.equals(PATH)
                        ? new VaultManifest.FileObject(file.type, file.id, file.path, file.identity,
                                content, file.references)
                        : new VaultManifest.FileObject(file.type, file.id, file.path, file.identity,
                                file.content, file.references));
            }
            try (VaultManifest next = new VaultManifest(verified.manifest.entries, files)) {
                plaintext = next.encode();
                store.bytes = VaultEnvelope.encrypt(current.id, generation, current.salt, key, plaintext).encode();
            }
        } finally {
            VaultCodec.wipe(key);
            VaultCodec.wipe(plaintext);
        }
    }

    private static byte[] encryptedFixture(long generation, byte[] content) throws Exception {
        byte[] salt = bytes(16, 7);
        byte[] key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = fixtureManifest(content)) {
            byte[] plaintext = manifest.encode();
            try {
                return VaultEnvelope.encrypt(UUID.randomUUID(), generation, salt, key, plaintext).encode();
            } finally {
                VaultCodec.wipe(plaintext);
            }
        } finally {
            VaultCodec.wipe(key);
        }
    }

    private static VaultManifest fixtureManifest(byte[] content) throws VaultException {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", TARGET, "fixture password", "", 0, 0, bytes("fixture password"));
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "ssh-config", ROOT, "fixture config", bytes("Host example.invalid\n"), List.of(PATH));
        VaultManifest.FileObject knownHosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                OBJECT_ID, PATH, OBJECT_IDENTITY, content, List.of());
        return new VaultManifest(List.of(password), List.of(config, knownHosts));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte)value);
        return result;
    }
}
