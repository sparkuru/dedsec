package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
        long value = 1_000;
        @Override public long now() { return value; }
    }

    private static final class PublicView implements SshRoleContext.PublicView, VaultSession.Revocation {
        boolean active = true;
        @Override public boolean active() { return active; }
        @Override public void revoke() { active = false; }
    }

    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        byte[] quick;
        boolean fail;
        boolean uncertain;
        Runnable afterCommit;

        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
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
                "SSH_HOST_KEY_CHANGED");
        assertWriterRejectsChangedEvidence(KEY_TYPE, "SHA256:" + "B".repeat(43),
                SshHostKeyAuthority.Classification.NEW, OLD_CONTENT, NEW_CONTENT,
                "SSH_HOST_KEY_CHANGED");
        assertWriterRejectsChangedEvidence(KEY_TYPE, FINGERPRINT,
                SshHostKeyAuthority.Classification.NEW, "wrong base", NEW_CONTENT,
                "SSH_HOST_KEY_CHANGED");
        assertWriterRejectsChangedEvidence(KEY_TYPE, FINGERPRINT,
                SshHostKeyAuthority.Classification.NEW, OLD_CONTENT, "wrong replacement",
                "SSH_HOST_KEY_CHANGED");
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
            rejects("VAULT_SSH_VALIDATION_REQUIRED", prepared::commit);
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
        VaultOperation fileRequest = vault.beginSshFiles(ROOT, CONNECTION);
        fileRequest.attachRevocation(rootView);
        vault.session.attachRevocation(epoch, rootView);
        try (Prepared prepared = prepared(true, true, true)) {
            prepared.review();
            assertEquals(SshTrustProposal.CasStatus.COMMITTED, prepared.commit().status);
            assertEquals(epoch + 1, vault.session.epoch());
            assertTrue(rootView.active());
            role.requireActive(clock);
            rejects("VAULT_OPERATION", () -> vault.sshFiles(fileRequest, ROOT, CONNECTION, files -> fail("old operation")));
            vault.cancelOperation(fileRequest);
            assertFalse(rootView.active());
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
            rejects("SSH_FILE_VIEW", prepared::commit);
            assertFalse(rootView.active());
            assertFalse(vault.session.unlocked());
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
