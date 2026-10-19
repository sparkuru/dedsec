package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;

/** Focused production-contract tests; no real device, account, or SSH endpoint is used. */
public final class SshProductionBridgeTest {
    private static final char[] PASSWORD = "production bridge fixture password".toCharArray();
    private static final String TARGET = "target:test@example.invalid:22";
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private MemoryStore store;
    private AppSecrets vault;
    private TestClock clock;
    private SshRoleRegistry registry;
    private SshRoleContext role;
    private SshHostKeyAuthority.Evidence knownEvidence;
    private SshHostKeyAuthority.Proof knownProof;
    private SshKexProof kex;

    private static final class TestClock implements VaultSession.Clock, SshRoleContext.Clock {
        long value = 1_000;
        int reads;
        Runnable onRead;
        @Override public long now() {
            reads++;
            if (onRead != null) onRead.run();
            return value;
        }
    }

    private static final class PublicView implements SshRoleContext.PublicView {
        boolean active = true;
        @Override public boolean active() { return active; }
    }

    private static final class CallbackNative implements SshNativeCallbackAdapter.NativeBinding {
        int closeCalls;
        @Override public long open(SshNativeCallbackAdapter target, long token, int uid, long deadline) {
            assertNotNull(target);
            assertTrue(token > 0 && uid >= 0 && deadline > 0);
            return 901;
        }
        @Override public void cancel(long callback) { assertEquals(901, callback); }
        @Override public void close(long callback) { assertEquals(901, callback); closeCalls++; }
    }

    private static final class CallbackRelease implements SshNativeRoleBootstrap.NativeRelease {
        int calls;
        @Override public void release(long token, int uid) {
            assertTrue(token > 0 && uid >= 0);
            calls++;
        }
    }

    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes;
        Runnable beforeRead;
        @Override public byte[] read() {
            if (beforeRead != null) {
                Runnable action = beforeRead;
                beforeRead = null;
                action.run();
            }
            return bytes == null ? null : bytes.clone();
        }
        @Override public byte[] quickMaterial() { return new byte[0]; }
        @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit();
            bytes = envelope.clone();
        }
    }

    @Before public void setUp() throws Exception {
        store = new MemoryStore();
        byte[] salt = new byte[16];
        byte[] key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = fixtureManifest()) {
            byte[] plain = manifest.encode();
            try {
                store.bytes = VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode();
            } finally {
                VaultCodec.wipe(plain);
            }
        } finally {
            VaultCodec.wipe(key);
        }

        clock = new TestClock();
        vault = new AppSecrets(store, clock);
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.unlock(PASSWORD, () -> { });

        registry = new SshRoleRegistry(clock);
        role = issueRole(0, "connection-0", 2_101, 1);
        knownEvidence = evidence(role, SshHostKeyAuthority.Classification.KNOWN,
                digest("known-hosts-before"), digest("known-hosts-after"));
        knownProof = new SshHostKeyAuthority().requireVerified(role, knownEvidence, null);
        kex = SshKexProof.fromNative(role, knownProof, 1, bytes(32, 7));
    }

    @Test public void roleRegistryBindsOwnedHopsAndRejectsReplay() throws Exception {
        try {
            issueRole(0, "connection-replay", 2_101, 2);
            fail("same owner pid and hop must not be reusable");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REPLAY", failure.code);
        }

        SshRoleContext second = issueRole(1, "connection-1", 2_102, 3);
        assertEquals(2, registry.size());
        assertNotNull(second.binding());
        assertFalse(role.binding().equals(second.binding()));
        try {
            knownProof.requireMatches(second);
            fail("a host proof must not cross hops");
        } catch (VaultException failure) {
            assertEquals("SSH_HOST_KEY_CHANGED", failure.code);
        }
        registry.release(second);
        assertEquals(1, registry.size());
    }

    @Test public void roleRegistryAllowsDistinctHopsOnOneConnection() throws Exception {
        SshRoleContext second = registry.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.JUMP,
                2_102, 1, "connection-0", TARGET, "example.invalid", 22, "view-1",
                bytes(32, 9), bytes(32, 2), clock.value + 30_000), new PublicView());
        assertEquals(2, registry.size());
        assertFalse(role.binding().equals(second.binding()));
        registry.release(second);
    }

    @Test public void expiredRoleDoesNotConsumeHopCapacity() throws Exception {
        clock.value = role.deadline;
        for (int hop = 0; hop < SshRoleContext.MAX_HOPS; hop++) {
            issueRole(hop, "replacement-" + hop, 3_100 + hop, 20 + hop);
        }
        assertEquals(SshRoleContext.MAX_HOPS, registry.size());
    }

    @Test public void hostTrustRequiresExactReviewForUnknownKeysAndFailsClosed() throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            assertEquals(role.binding(), review.roleBinding);
            assertEquals(TARGET, review.targetIdentity);
            assertEquals(FINGERPRINT, review.fingerprint);
            assertArrayEquals(digest("known-hosts-before"), review.baseDigest());
            assertArrayEquals(digest("example.invalid ssh-ed25519 AAAA\n"), review.replacementDigest());
        });
        byte[] base = digest("known-hosts-before");
        byte[] replacement = "example.invalid ssh-ed25519 AAAA\n".getBytes(StandardCharsets.US_ASCII);
        SshHostKeyAuthority.Evidence unknown = evidence(role, SshHostKeyAuthority.Classification.NEW,
                base, digest(replacement));

        try {
            authority.requireVerified(role, unknown, null);
            fail("new host keys require an exact proposal");
        } catch (VaultException failure) {
            assertEquals("SSH_HOST_KEY_REVIEW", failure.code);
        }
        try (SshTrustProposal proposal = new SshTrustProposal(role, "known_hosts", "trust", TARGET,
                "connection-0", "example.invalid", 22, "ssh-ed25519", FINGERPRINT, base, replacement)) {
            SshHostKeyAuthority.Proof proof = authority.requireVerified(role, unknown, proposal);
            assertEquals(SshHostKeyAuthority.Classification.NEW, proof.classification);
        }
        try (SshTrustProposal wrongHost = new SshTrustProposal(role, "known_hosts", "trust", TARGET,
                "connection-0", "other.example.invalid", 22, "ssh-ed25519", FINGERPRINT, base, replacement)) {
            try {
                authority.requireVerified(role, unknown, wrongHost);
                fail("review must bind the exact host");
            } catch (VaultException failure) {
                assertEquals("SSH_HOST_KEY_REVIEW", failure.code);
            }
        }
        SshRoleContext otherHop = issueRole(1, "connection-0", 2_102, 8);
        try (SshTrustProposal wrongHop = new SshTrustProposal(otherHop, "known_hosts", "trust", TARGET,
                "connection-0", "example.invalid", 22, "ssh-ed25519", FINGERPRINT, base, replacement)) {
            try {
                authority.requireVerified(role, unknown, wrongHop);
                fail("review must bind the exact hop");
            } catch (VaultException failure) {
                assertEquals("SSH_HOST_KEY_REVIEW", failure.code);
            }
        } finally {
            registry.release(otherHop);
        }

        for (SshHostKeyAuthority.Classification classification : new SshHostKeyAuthority.Classification[] {
                SshHostKeyAuthority.Classification.CHANGED,
                SshHostKeyAuthority.Classification.REVOKED,
                SshHostKeyAuthority.Classification.CERTIFICATE,
                SshHostKeyAuthority.Classification.INVALID }) {
            try {
                authority.requireVerified(role, evidence(role, classification, base, digest(replacement)), null);
                fail("classification must fail closed: " + classification);
            } catch (VaultException failure) {
                String expected = classification == SshHostKeyAuthority.Classification.CHANGED
                        ? "SSH_HOST_KEY_CHANGED"
                        : classification == SshHostKeyAuthority.Classification.REVOKED
                        ? "SSH_HOST_KEY_REVOKED"
                        : classification == SshHostKeyAuthority.Classification.CERTIFICATE
                        ? "SSH_HOST_KEY_CERTIFICATE" : "SSH_HOST_KEY_INVALID";
                assertEquals(expected, failure.code);
            }
        }
    }

    @Test public void credentialBridgeReleasesTypedSecretsOnlyAfterKex() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        try {
            bridge.openCredential(role, knownEvidence, null, "password",
                    SshCredentialBridge.Kind.KEY_PASSPHRASE);
            fail("typed key passphrase requests cannot select a password entry");
        } catch (VaultException failure) {
            assertEquals("VAULT_OPERATION", failure.code);
        }

        SshCredentialBridge.Request password = bridge.openCredential(role, knownEvidence, null,
                "password", SshCredentialBridge.Kind.PASSWORD);
        try {
            bridge.release(password, null, ignored -> fail("credential reached receiver without KEX"));
            fail("missing KEX must be rejected");
        } catch (VaultException failure) {
            assertEquals("SSH_KEX_REQUIRED", failure.code);
        }
        try {
            password.requireOpen();
            fail("a rejected request must be closed");
        } catch (VaultException failure) {
            assertEquals("VAULT_CANCELLED", failure.code);
        }

        String[] received = new String[1];
        SshCredentialBridge.Request validPassword = bridge.openCredential(role, knownEvidence, null,
                "password", SshCredentialBridge.Kind.PASSWORD);
        bridge.release(validPassword, kex, credential -> received[0] = new String(credential, StandardCharsets.UTF_8));
        assertEquals("fixture password", received[0]);
        try {
            validPassword.requireOpen();
            fail("a released request must be closed");
        } catch (VaultException failure) {
            assertEquals("VAULT_CANCELLED", failure.code);
        }

        String[] privateKey = new String[1];
        SshCredentialBridge.Request key = bridge.openPrivateKey(role, knownEvidence, null,
                "keys/key", "test-key-identity");
        bridge.release(key, kex, credential -> privateKey[0] = new String(credential, StandardCharsets.UTF_8));
        assertEquals("private key fixture", privateKey[0]);
    }

    @Test public void managedProviderBindsNewProposalAndHandsOffPasswordAndPrivateKeyBytes()
            throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> { });
        SshCredentialBridge bridge = new SshCredentialBridge(vault, authority, clock);
        final SshTrustProposal[] proposalHolder = new SshTrustProposal[1];
        byte[] base = digest("known-hosts-before");
        byte[] replacement = bytes("example.invalid ssh-ed25519 AAAA\n");
        SshManagedProvider provider = new SshManagedProvider(bridge, clock, evidence -> {
            SshTrustProposal proposal = new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint, evidence.baseDigest(), replacement);
            proposalHolder[0] = proposal;
            return proposal;
        }, (evidence, proposal) -> SshTrustProposal.CasResult.committed(evidence, proposal),
        (managedBridge, managedRole, hostKey, kind) -> {
            if (kind == SshCredentialBridge.Kind.PRIVATE_KEY) {
                return managedBridge.openPrivateKey(managedRole, hostKey,
                        "keys/key", "test-key-identity");
            }
            return managedBridge.openCredential(managedRole, hostKey, "password", kind);
        });

        SshRoleRegistry callbackRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(
                callbackRecord(2, "callback-connection", 31_002, clock.value + 30_000),
                callbackRegistry, new PublicView(), clock, ignored -> true);
        CallbackNative nativeBinding = new CallbackNative();
        CallbackRelease roleRelease = new CallbackRelease();
        SshNativeCallbackAdapter adapter = SshNativeCallbackAdapter.bind(issued,
                callbackRegistry, authority, clock, nativeBinding, roleRelease, provider);
        try {
            assertTrue(adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(replacement)));
            assertNotNull(adapter.hostProof());
            try {
                proposalHolder[0].requireOpen();
                fail("the exact NEW-host proposal must be closed after host verification");
            } catch (VaultException failure) {
                assertEquals("VAULT_EXPIRED", failure.code);
            }
            assertTrue(adapter.onNativeKexComplete(1, bytes(32, 7)));

            byte[] password = adapter.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD);
            assertArrayEquals(bytes("fixture password"), password);
            VaultCodec.wipe(password);

            byte[] privateKey = adapter.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY);
            assertArrayEquals(bytes("private key fixture"), privateKey);
            VaultCodec.wipe(privateKey);
            password = adapter.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD);
            assertArrayEquals(bytes("fixture password"), password);
            VaultCodec.wipe(password);
        } finally {
            adapter.close();
            callbackRegistry.close();
        }
        assertEquals(1, nativeBinding.closeCalls);
        assertEquals(1, roleRelease.calls);
    }

    @Test public void nativeHostEvidenceReturnsBoundedKnownAndApprovedNewCasResults()
            throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            assertEquals(TARGET, review.targetIdentity);
            assertEquals(FINGERPRINT, review.fingerprint);
        });
        SshCredentialBridge bridge = new SshCredentialBridge(vault, authority, clock);
        byte[] replacement = bytes("example.invalid ssh-ed25519 AAAA\n");
        byte[] base = digest("known-hosts-before");
        final SshTrustProposal[] proposalHolder = new SshTrustProposal[1];
        final int[] writes = new int[1];
        SshManagedProvider provider = new SshManagedProvider(bridge, clock, evidence -> {
            SshTrustProposal proposal = new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint, base, replacement);
            proposalHolder[0] = proposal;
            return proposal;
        }, (evidence, proposal) -> {
            writes[0]++;
            return SshTrustProposal.CasResult.committed(evidence, proposal);
        }, null);

        SshRoleRegistry callbackRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(
                callbackRecord(2, "callback-connection", 31_002, clock.value + 30_000),
                callbackRegistry, new PublicView(), clock, ignored -> true);
        CallbackNative nativeBinding = new CallbackNative();
        CallbackRelease roleRelease = new CallbackRelease();
        SshNativeCallbackAdapter adapter = SshNativeCallbackAdapter.bind(issued,
                callbackRegistry, authority, clock, nativeBinding, roleRelease, provider);
        try {
            HostResultWire known = decodeHostResult(adapter.onNativeHostEvidence(issued.token,
                    issued.role.hop, issued.role.port, issued.role.connectionId,
                    issued.role.targetIdentity, issued.role.host,
                    SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519", FINGERPRINT,
                    base, digest("known-hosts-after")));
            assertEquals(SshNativeCallbackAdapter.HOST_KNOWN, known.classification);
            assertEquals("ssh-ed25519", known.keyType);
            assertEquals(0, known.casStatus);
            assertEquals("", known.proposalBinding);
            assertArrayEquals(base, known.baseDigest);

            HostResultWire approved = decodeHostResult(adapter.onNativeHostEvidence(issued.token,
                    issued.role.hop, issued.role.port, issued.role.connectionId,
                    issued.role.targetIdentity, issued.role.host,
                    SshNativeCallbackAdapter.HOST_NEW, "ssh-ed25519", FINGERPRINT,
                    base, digest(replacement)));
            assertEquals(SshNativeCallbackAdapter.HOST_NEW, approved.classification);
            assertEquals("ssh-ed25519", approved.keyType);
            assertEquals(1, approved.casStatus);
            assertEquals(64, approved.proposalBinding.length());
            assertArrayEquals(base, approved.baseDigest);
            assertArrayEquals(digest(replacement), approved.replacementDigest);
            assertEquals(1, writes[0]);
            try {
                proposalHolder[0].requireOpen();
                fail("the exact proposal must be closed after the native result is encoded");
            } catch (VaultException failure) {
                assertEquals("VAULT_EXPIRED", failure.code);
            }
        } finally {
            adapter.close();
            callbackRegistry.close();
        }
    }

    @Test public void casResultRejectsClassificationAndKeyTypeSubstitution() throws Exception {
        byte[] base = digest("known-hosts-before");
        byte[] replacement = bytes("example.invalid ssh-ed25519 AAAA\n");
        SshHostKeyAuthority.Evidence unknown = evidence(role, SshHostKeyAuthority.Classification.NEW,
                base, digest(replacement));
        SshHostKeyAuthority.Evidence known = evidence(role, SshHostKeyAuthority.Classification.KNOWN,
                base, digest(replacement));
        SshHostKeyAuthority.Evidence rsa = SshHostKeyAuthority.Evidence.fromNative(role,
                SshHostKeyAuthority.Classification.NEW, "ssh-rsa", FINGERPRINT,
                base, digest(replacement));
        try (SshTrustProposal proposal = new SshTrustProposal(role, "known_hosts", "ssh-trust",
                TARGET, "connection-0", "example.invalid", 22, "ssh-ed25519", FINGERPRINT,
                base, replacement);
             SshTrustProposal rsaProposal = new SshTrustProposal(role, "known_hosts", "ssh-trust",
                     TARGET, "connection-0", "example.invalid", 22, "ssh-rsa", FINGERPRINT,
                     base, replacement)) {
            SshTrustProposal.CasResult committed = SshTrustProposal.CasResult.committed(unknown, proposal);
            assertEquals(SshHostKeyAuthority.Classification.NEW, committed.classification);
            assertEquals("ssh-ed25519", committed.keyType);
            assertFalse(committed.matches(rsa, rsaProposal, SshTrustProposal.CasStatus.COMMITTED));
            assertFalse(committed.matches(known, proposal, SshTrustProposal.CasStatus.COMMITTED));
            try {
                SshTrustProposal.CasResult.notRequired(unknown);
                fail("NEW host evidence must never produce CAS_NOT_REQUIRED");
            } catch (VaultException failure) {
                assertEquals("SSH_HOST_KEY_CAS", failure.code);
            }
            try {
                SshTrustProposal.CasResult.committed(known, proposal);
                fail("KNOWN evidence must never produce CAS_COMMITTED");
            } catch (VaultException failure) {
                assertEquals("SSH_HOST_KEY_CAS", failure.code);
            }
            assertFalse(proposal.matchesEvidence(rsa));
        }
    }

    @Test public void nativeHostEvidenceRejectsWrongHopAndDigestMismatch() throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> { });
        SshCredentialBridge bridge = new SshCredentialBridge(vault, authority, clock);
        byte[] base = digest("known-hosts-before");
        byte[] replacement = bytes("example.invalid ssh-ed25519 AAAA\n");
        final SshTrustProposal[] proposalHolder = new SshTrustProposal[1];
        SshManagedProvider provider = new SshManagedProvider(bridge, clock, evidence -> {
            SshTrustProposal proposal = new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint, base, replacement);
            proposalHolder[0] = proposal;
            return proposal;
        }, (evidence, proposal) -> SshTrustProposal.CasResult.committed(evidence, proposal), null);

        SshRoleRegistry wrongHopRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued wrongHopIssued = SshNativeRoleBootstrap.issue(
                callbackRecord(2, "callback-connection", 31_002, clock.value + 30_000),
                wrongHopRegistry, new PublicView(), clock, ignored -> true);
        SshNativeCallbackAdapter wrongHop = SshNativeCallbackAdapter.bind(wrongHopIssued,
                wrongHopRegistry, authority, clock, new CallbackNative(), new CallbackRelease(), provider);
        try {
            assertEquals(null, wrongHop.onNativeHostEvidence(wrongHopIssued.token,
                    wrongHopIssued.role.hop + 1, wrongHopIssued.role.port,
                    wrongHopIssued.role.connectionId, wrongHopIssued.role.targetIdentity,
                    wrongHopIssued.role.host, SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(replacement)));
            assertEquals("SSH_HOST_KEY_CHANGED", wrongHop.lastFailureCode());
        } finally {
            wrongHop.close();
            wrongHopRegistry.close();
        }

        SshRoleRegistry digestRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued digestIssued = SshNativeRoleBootstrap.issue(
                callbackRecord(3, "callback-connection-digest", 31_003, clock.value + 30_000),
                digestRegistry, new PublicView(), clock, ignored -> true);
        SshNativeCallbackAdapter digestAdapter = SshNativeCallbackAdapter.bind(digestIssued,
                digestRegistry, authority, clock, new CallbackNative(), new CallbackRelease(), provider);
        try {
            assertEquals(null, digestAdapter.onNativeHostEvidence(digestIssued.token,
                    digestIssued.role.hop, digestIssued.role.port, digestIssued.role.connectionId,
                    digestIssued.role.targetIdentity, digestIssued.role.host,
                    SshNativeCallbackAdapter.HOST_NEW, "ssh-ed25519", FINGERPRINT,
                    digest("different-base"), digest(replacement)));
            assertEquals("SSH_HOST_KEY_REVIEW", digestAdapter.lastFailureCode());
            assertNotNull(proposalHolder[0]);
            try {
                proposalHolder[0].requireOpen();
                fail("a digest-mismatched proposal must be closed");
            } catch (VaultException failure) {
                assertEquals("VAULT_EXPIRED", failure.code);
            }
        } finally {
            digestAdapter.close();
            digestRegistry.close();
        }
    }

    @Test public void nativeHostEvidenceRequiresProviderApprovalAndExactCasSuccess() throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority();
        byte[] base = digest("known-hosts-before");
        byte[] replacement = bytes("example.invalid ssh-ed25519 AAAA\n");

        SshRoleRegistry noProviderRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued noProviderIssued = SshNativeRoleBootstrap.issue(
                callbackRecord(4, "callback-no-provider", 31_004, clock.value + 30_000),
                noProviderRegistry, new PublicView(), clock, ignored -> true);
        SshNativeCallbackAdapter noProvider = SshNativeCallbackAdapter.bind(noProviderIssued,
                noProviderRegistry, authority, clock, new CallbackNative(), new CallbackRelease());
        try {
            assertEquals(null, noProvider.onNativeHostEvidence(noProviderIssued.token,
                    noProviderIssued.role.hop, noProviderIssued.role.port,
                    noProviderIssued.role.connectionId, noProviderIssued.role.targetIdentity,
                    noProviderIssued.role.host, SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(replacement)));
            assertEquals("SSH_HOST_KEY_REVIEW", noProvider.lastFailureCode());
        } finally {
            noProvider.close();
            noProviderRegistry.close();
        }

        SshCredentialBridge bridge = new SshCredentialBridge(vault, authority, clock);
        final SshTrustProposal[] proposalHolder = new SshTrustProposal[1];
        SshManagedProvider rejectedApprovalProvider = new SshManagedProvider(bridge, clock, evidence -> {
            SshTrustProposal proposal = new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint, evidence.baseDigest(), replacement);
            proposalHolder[0] = proposal;
            return proposal;
        }, (evidence, proposal) -> SshTrustProposal.CasResult.committed(evidence, proposal), null);
        SshRoleRegistry approvalRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued approvalIssued = SshNativeRoleBootstrap.issue(
                callbackRecord(5, "callback-no-approval", 31_005, clock.value + 30_000),
                approvalRegistry, new PublicView(), clock, ignored -> true);
        SshNativeCallbackAdapter noApproval = SshNativeCallbackAdapter.bind(approvalIssued,
                approvalRegistry, authority, clock, new CallbackNative(), new CallbackRelease(),
                rejectedApprovalProvider);
        try {
            assertEquals(null, noApproval.onNativeHostEvidence(approvalIssued.token,
                    approvalIssued.role.hop, approvalIssued.role.port,
                    approvalIssued.role.connectionId, approvalIssued.role.targetIdentity,
                    approvalIssued.role.host, SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(replacement)));
            assertEquals("SSH_HOST_KEY_REVIEW", noApproval.lastFailureCode());
        } finally {
            noApproval.close();
            approvalRegistry.close();
        }

        final int[] writes = new int[1];
        SshHostKeyAuthority approvedAuthority = new SshHostKeyAuthority(review -> { });
        SshCredentialBridge approvedBridge = new SshCredentialBridge(vault, approvedAuthority, clock);
        SshManagedProvider casFailureProvider = new SshManagedProvider(approvedBridge, clock, evidence -> {
            SshTrustProposal proposal = new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint, evidence.baseDigest(), replacement);
            proposalHolder[0] = proposal;
            return proposal;
        }, (evidence, proposal) -> {
            writes[0]++;
            return SshTrustProposal.CasResult.failed(evidence, proposal, "CAS_STALE");
        }, null);
        SshRoleRegistry casRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued casIssued = SshNativeRoleBootstrap.issue(
                callbackRecord(6, "callback-cas-failure", 31_006, clock.value + 30_000),
                casRegistry, new PublicView(), clock, ignored -> true);
        SshNativeCallbackAdapter casAdapter = SshNativeCallbackAdapter.bind(casIssued,
                casRegistry, approvedAuthority, clock, new CallbackNative(), new CallbackRelease(),
                casFailureProvider);
        try {
            assertEquals(null, casAdapter.onNativeHostEvidence(casIssued.token,
                    casIssued.role.hop, casIssued.role.port, casIssued.role.connectionId,
                    casIssued.role.targetIdentity, casIssued.role.host,
                    SshNativeCallbackAdapter.HOST_NEW, "ssh-ed25519", FINGERPRINT,
                    base, digest(replacement)));
            assertEquals("SSH_HOST_KEY_CAS", casAdapter.lastFailureCode());
            assertEquals(1, writes[0]);
            try {
                proposalHolder[0].requireOpen();
                fail("a CAS failure must close the proposal");
            } catch (VaultException failure) {
                assertEquals("VAULT_EXPIRED", failure.code);
            }
        } finally {
            casAdapter.close();
            casRegistry.close();
        }
    }

    @Test public void roleExpiryAndKexMismatchPreventRelease() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshCredentialBridge.Request expired = bridge.openCredential(role, knownEvidence, null,
                "password", SshCredentialBridge.Kind.PASSWORD);
        clock.value = role.deadline;
        try {
            bridge.release(expired, kex, ignored -> fail("expired role reached receiver"));
            fail("expired role must not release a credential");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_EXPIRED", failure.code);
        }

        clock.value = 1_000;
        SshRoleContext otherRole = issueRole(1, "connection-2", 2_102, 4);
        SshHostKeyAuthority.Evidence otherEvidence = evidence(otherRole, SshHostKeyAuthority.Classification.KNOWN,
                digest("other-before"), digest("other-after"));
        SshHostKeyAuthority.Proof otherProof = new SshHostKeyAuthority().requireVerified(otherRole, otherEvidence, null);
        SshKexProof otherKex = SshKexProof.fromNative(otherRole, otherProof, 1, bytes(32, 8));
        SshCredentialBridge.Request mismatched = bridge.openCredential(role, knownEvidence, null,
                "password", SshCredentialBridge.Kind.PASSWORD);
        try {
            bridge.release(mismatched, otherKex, ignored -> fail("cross-hop KEX reached receiver"));
            fail("cross-hop KEX must be rejected");
        } catch (VaultException failure) {
            assertEquals("SSH_KEX_REQUIRED", failure.code);
        }
    }

    @Test public void managedProviderRejectsWrongKindAndNullRoleRequests() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshManagedProvider provider = new SshManagedProvider(bridge, clock, null,
                (managedBridge, managedRole, hostKey, kind) -> managedBridge.openPrivateKey(
                        managedRole, hostKey, "keys/key", "test-key-identity"));
        try {
            provider.credentialBytes(role, knownProof, kex, SshCredentialBridge.Kind.PASSWORD);
            fail("a private-key request must not satisfy a password callback");
        } catch (VaultException failure) {
            assertEquals("SSH_CREDENTIAL_PROVIDER", failure.code);
        }

        try {
            bridge.openCredential(null, knownEvidence, null, "password", SshCredentialBridge.Kind.PASSWORD);
            fail("null role must be rejected before host verification");
        } catch (VaultException failure) {
            assertEquals("PARAMETERS", failure.code);
        }
        try {
            bridge.openPrivateKey(null, knownEvidence, null, "keys/key", "test-key-identity");
            fail("null role must be rejected before host verification");
        } catch (VaultException failure) {
            assertEquals("PARAMETERS", failure.code);
        }
    }

    private final class SelectedSetup implements AutoCloseable {
        final SshRoleRegistry callbackRegistry = new SshRoleRegistry(clock);
        final PublicView view = new PublicView();
        final SshNativeRoleBootstrap.Issued issued;
        final SshNativeCallbackAdapter adapter;
        final SshAuthenticationSelection selection;
        final CallbackNative nativeBinding = new CallbackNative();
        SshNativeCallbackAdapter.NativeSessionClaim claim;
        boolean owned;
        int sourceCalls;

        SelectedSetup(boolean key) throws Exception {
            SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> { });
            SshManagedProvider provider = new SshManagedProvider(
                    new SshCredentialBridge(vault, authority, clock), clock,
                    evidence -> new SshTrustProposal(evidence.role, "known_hosts", "ssh-trust",
                            evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                            evidence.role.port, evidence.keyType, evidence.fingerprint, evidence.baseDigest(),
                            bytes("example.invalid ssh-ed25519 AAAA\n")),
                    (evidence, proposal) -> SshTrustProposal.CasResult.committed(evidence, proposal),
                    (bridge, selectedRole, proof, kind) -> {
                        sourceCalls++;
                        return kind == SshCredentialBridge.Kind.PRIVATE_KEY
                                ? bridge.openPrivateKey(selectedRole, proof, "keys/key", "test-key-identity")
                                : bridge.openCredential(selectedRole, proof, "password", kind);
                    });
            issued = SshNativeRoleBootstrap.issue(
                    callbackRecord(2, "selected-connection", 31_002, clock.value + 30_000),
                    callbackRegistry, view, clock, ignored -> true);
            adapter = SshNativeCallbackAdapter.bind(issued, callbackRegistry, authority, clock,
                    nativeBinding, new CallbackRelease(), provider);
            selection = key ? SshAuthenticationSelection.privateKey(issued.role, " 显式User ",
                    "keys/other", "other-key-identity", bytes("expected public declaration"), clock)
                    : SshAuthenticationSelection.password(issued.role, " 显式User ", "password-other", clock);
        }

        void prove(boolean unknown) throws Exception {
            assertTrue(adapter.onNativeHostKey(unknown ? SshNativeCallbackAdapter.HOST_NEW
                    : SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519", FINGERPRINT,
                    digest("known-hosts-before"), digest("example.invalid ssh-ed25519 AAAA\n")));
            assertTrue(adapter.onNativeKexComplete(1, bytes(32, 7)));
        }

        void claim(boolean success) throws Exception {
            claim = adapter.beginNativeSessionOpen();
            adapter.finishNativeSessionOpen(claim, success);
            owned = success;
        }

        byte[] receipt() throws Exception {
            byte[] wire = selection.encode(issued.token, clock);
            try { return VaultCodec.digest(wire); }
            finally { VaultCodec.wipe(wire); }
        }

        @Override public void close() throws Exception {
            try {
                if (owned) {
                    adapter.nativeClientClosed(claim);
                    owned = false;
                }
                adapter.close();
            }
            finally { callbackRegistry.close(); }
        }
    }

    @Test public void nativeSelectedCredentialsMatchExactReceiptForBothKindsAndHostPolicies()
            throws Exception {
        for (boolean key : new boolean[] { false, true }) {
            for (boolean unknown : new boolean[] { false, true }) {
                try (SelectedSetup setup = new SelectedSetup(key)) {
                    setup.adapter.selectAuthentication(setup.selection);
                    byte[] receipt = setup.receipt();
                    byte[] digest = bytes(32, 7);
                    setup.claim(true);
                    setup.prove(unknown);
                    byte[] result = setup.adapter.onNativeSelectedCredentialBytes(key ? 4 : 1,
                            receipt, 1, digest);
                    assertArrayEquals(bytes(key ? "other private fixture" : "other password fixture"), result);
                    assertArrayEquals(setup.receipt(), receipt);
                    assertArrayEquals(bytes(32, 7), digest);
                    VaultCodec.wipe(result);
                    assertEquals(0, setup.sourceCalls);
                    assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(key ? 4 : 1,
                            receipt, 1, digest));
                    assertEquals("SSH_AUTH_SELECTION", setup.adapter.lastFailureCode());
                }
            }
        }
    }

    @Test public void nativeSelectedCredentialRejectsMetadataAndOwnershipWithoutLegacyFallback()
            throws Exception {
        for (int mode = 0; mode < 16; mode++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                if (mode != 3) setup.adapter.selectAuthentication(setup.selection);
                byte[] receipt = setup.receipt();
                byte[] digest = bytes(32, 7);
                long cycle = 1;
                int kind = 1;
                if (mode == 1) setup.claim(false);
                else if (mode == 2) setup.claim = setup.adapter.beginNativeSessionOpen();
                else if (mode != 0) setup.claim(true);
                if (mode == 5) assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                        "ssh-ed25519", FINGERPRINT, digest("before"), digest("after")));
                else if (mode != 4) setup.prove(false);
                if (mode == 6) receipt[0] ^= 1;
                if (mode == 7) digest[0] ^= 1;
                if (mode == 8) cycle = 2;
                if (mode == 9) kind = 4;
                if (mode == 10) kind = 2;
                if (mode == 11) receipt = null;
                if (mode == 12) digest = new byte[33];
                if (mode == 13) cycle = 0;
                if (mode == 14) receipt = new byte[31];
                if (mode == 15) digest = new byte[32];
                assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(kind, receipt, cycle, digest));
                if (mode == 2) setup.adapter.finishNativeSessionOpen(setup.claim, false);
                assertEquals(mode == 4 || mode == 5 || mode == 7 || mode == 8 || mode == 15
                        ? "SSH_KEX_REQUIRED" : "SSH_AUTH_SELECTION", setup.adapter.lastFailureCode());
                assertEquals(0, setup.sourceCalls);
            }
        }
    }

    @Test public void selectedNativeClaimPermanentlyDisablesManagedObjectDowngrade() throws Exception {
        for (boolean success : new boolean[] { false, true }) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                setup.claim(success);
                setup.prove(false);
                assertEquals(null, setup.adapter.onNativeCredentialBytes(1, setup.selection));
                assertEquals("SSH_AUTH_SELECTION", setup.adapter.lastFailureCode());
                assertEquals(0, setup.sourceCalls);
            }
        }
    }

    @Test public void selectedManagedCredentialsAreAlsoOneShotAndProviderFailureCannotRetry()
            throws Exception {
        for (boolean failProvider : new boolean[] { false, true }) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                setup.prove(false);
                if (failProvider) vault.session.lock();
                byte[] result = setup.adapter.onNativeCredentialBytes(1, setup.selection);
                if (failProvider) assertEquals(null, result);
                else assertArrayEquals(bytes("other password fixture"), result);
                VaultCodec.wipe(result);
                assertEquals(null, setup.adapter.onNativeCredentialBytes(1, setup.selection));
                assertEquals(0, setup.sourceCalls);
            }
        }
    }

    @Test public void nativeSelectedCallbackCopiesMetadataBeforeProviderEntry() throws Exception {
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.adapter.selectAuthentication(setup.selection);
            byte[] receipt = setup.receipt();
            byte[] digest = bytes(32, 7);
            setup.claim(true);
            setup.prove(false);
            store.beforeRead = () -> { Arrays.fill(receipt, (byte)0); Arrays.fill(digest, (byte)0); };
            byte[] result = setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, digest);
            assertArrayEquals(bytes("other password fixture"), result);
            VaultCodec.wipe(result);
            assertArrayEquals(new byte[32], receipt);
            assertArrayEquals(new byte[32], digest);
        }
    }

    @Test public void nativeSelectedWrongThreadDoesNotConsumeOwnersAttempt() throws Exception {
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.adapter.selectAuthentication(setup.selection);
            byte[] receipt = setup.receipt();
            setup.claim(true);
            setup.prove(false);
            Thread thread = new Thread(() -> assertEquals(null,
                    setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7))));
            thread.start();
            thread.join();
            assertEquals("SSH_CALLBACK_THREAD", setup.adapter.lastFailureCode());
            byte[] result = setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7));
            assertArrayEquals(bytes("other password fixture"), result);
            VaultCodec.wipe(result);
        }
    }

    @Test public void nativeSelectedProofAndViewChangesFailWithoutSecretReturn() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                byte[] receipt = setup.receipt();
                setup.claim(true);
                setup.prove(false);
                if (mode == 0) assertTrue(setup.adapter.onNativeKexComplete(2, bytes(32, 8)));
                else if (mode == 1) store.beforeRead = () -> setup.view.active = false;
                else store.beforeRead = () -> assertFalse(setup.adapter.onNativeHostKey(
                        SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519", FINGERPRINT,
                        digest("before"), digest("after")));
                assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7)));
                assertEquals(mode == 0 ? "SSH_KEX_REQUIRED" : mode == 1 ? "SSH_FILE_VIEW"
                        : "SSH_CALLBACK_REENTRANT", setup.adapter.lastFailureCode());
                assertEquals(0, setup.sourceCalls);
            }
        }
    }

    @Test public void selectedEffectGuardRunsAgainInsideAppSecretsBeforeReceiverAndClosesRequest()
            throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "user", "password", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selection);
        int[] calls = new int[1];
        try {
            bridge.release(request, kex, selection, () -> {
                if (++calls[0] == 2) request.close();
                request.requireOpen();
            }, ignored -> fail("cancelled final effect must not invoke receiver"));
            fail("final guard must run inside AppSecrets");
        } catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
        assertEquals(2, calls[0]);
        try { request.requireOpen(); fail("request remains closed"); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
    }

    @Test public void nativeSelectedCancellationAtEveryClockBoundaryDiscardsBytes() throws Exception {
        int boundaryCount;
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.adapter.selectAuthentication(setup.selection);
            byte[] receipt = setup.receipt();
            setup.claim(true);
            setup.prove(false);
            int start = clock.reads;
            byte[] result = setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7));
            boundaryCount = clock.reads - start;
            assertArrayEquals(bytes("other password fixture"), result);
            VaultCodec.wipe(result);
        }
        assertTrue(boundaryCount > 2);
        for (int boundary = 1; boundary <= boundaryCount; boundary++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                byte[] receipt = setup.receipt();
                setup.claim(true);
                setup.prove(false);
                int target = clock.reads + boundary;
                clock.onRead = () -> {
                    if (clock.reads != target) return;
                    clock.onRead = null;
                    try { setup.adapter.cancel(); }
                    catch (VaultException failure) { throw new AssertionError(failure); }
                };
                assertEquals("cancel boundary " + boundary, null,
                        setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7)));
                assertEquals("SSH_CALLBACK_CANCELLED", setup.adapter.lastFailureCode());
                assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7)));
            } finally { clock.onRead = null; }
        }
    }

    @Test public void selectedFinishRetainsReentryGuardThroughFinalRoleClockChecks() throws Exception {
        int boundaryCount;
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.adapter.selectAuthentication(setup.selection);
            byte[] receipt = setup.receipt();
            setup.claim(true);
            setup.prove(false);
            int start = clock.reads;
            byte[] result = setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7));
            boundaryCount = clock.reads - start;
            assertArrayEquals(bytes("other password fixture"), result);
            VaultCodec.wipe(result);
        }
        for (int boundary = boundaryCount - 1; boundary <= boundaryCount; boundary++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                byte[] receipt = setup.receipt();
                setup.claim(true);
                setup.prove(false);
                int target = clock.reads + boundary;
                clock.onRead = () -> {
                    if (clock.reads != target) return;
                    clock.onRead = null;
                    assertFalse(setup.adapter.onNativeKexComplete(2, bytes(32, 8)));
                };
                assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7)));
                assertEquals("SSH_CALLBACK_REENTRANT", setup.adapter.lastFailureCode());
                assertEquals(null, setup.adapter.hostProof());
                assertEquals(null, setup.adapter.kexProof());
            } finally { clock.onRead = null; }
        }
    }

    @Test public void selectedEscapingErrorsPreserveErrorAndReleaseInvocationForOwnerCleanup()
            throws Exception {
        int boundaryCount;
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.adapter.selectAuthentication(setup.selection);
            byte[] receipt = setup.receipt();
            setup.claim(true);
            setup.prove(false);
            int start = clock.reads;
            byte[] result = setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7));
            boundaryCount = clock.reads - start;
            assertArrayEquals(bytes("other password fixture"), result);
            VaultCodec.wipe(result);
        }
        // Skip begin's two role-clock reads: no invocation exists until begin returns.
        // This includes guard checks and both final finish-clock reads after secret copy.
        for (int boundary = 3; boundary <= boundaryCount; boundary++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                byte[] receipt = setup.receipt();
                setup.claim(true);
                setup.prove(false);
                AssertionError fault = new AssertionError("synthetic selected callback failure");
                int target = clock.reads + boundary;
                clock.onRead = () -> {
                    if (clock.reads != target) return;
                    clock.onRead = null;
                    throw fault;
                };
                try {
                    setup.adapter.onNativeSelectedCredentialBytes(1, receipt, 1, bytes(32, 7));
                    fail("Error must propagate without returning a secret");
                } catch (AssertionError caught) { assertTrue(caught == fault); }
                assertEquals("SSH_CALLBACK_JAVA_EXCEPTION", setup.adapter.lastFailureCode());
                assertEquals(null, setup.adapter.hostProof());
                assertEquals(null, setup.adapter.kexProof());
                setup.close();
                assertEquals(0, setup.callbackRegistry.size());
            } finally { clock.onRead = null; }
        }
    }

    @Test public void selectedBridgeReceiverErrorWipesBorrowedSecretAndClosesRequest() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, "user", "password", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selection);
        byte[][] retained = new byte[1][];
        AssertionError fault = new AssertionError("synthetic receiver failure");
        try {
            bridge.release(request, kex, selection, null, secret -> {
                retained[0] = secret;
                throw fault;
            });
            fail("receiver Error must propagate");
        } catch (AssertionError caught) { assertTrue(caught == fault); }
        assertNotNull(retained[0]);
        assertArrayEquals(new byte[bytes("fixture password").length], retained[0]);
        try { request.requireOpen(); fail("request must close even after Error"); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
    }

    @Test public void selectionSetupIsOneShotOwnerOnlyAndClaimFailureDoesNotReopenIt() throws Exception {
        try (SelectedSetup setup = new SelectedSetup(false)) {
            java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
            Thread wrongOwner = new Thread(() -> {
                try { setup.adapter.selectAuthentication(setup.selection); }
                catch (VaultException failure) { error.set(failure.code); }
            });
            wrongOwner.start();
            wrongOwner.join();
            assertEquals("SSH_CALLBACK_THREAD", error.get());
            setup.adapter.selectAuthentication(setup.selection);
            try { setup.adapter.selectAuthentication(setup.selection); fail("one-shot"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            setup.prove(false);
            byte[] bytes = setup.adapter.onNativeCredentialBytes(
                    SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, setup.selection);
            assertArrayEquals(bytes("other password fixture"), bytes);
            VaultCodec.wipe(bytes);
        }
        try (SelectedSetup setup = new SelectedSetup(false)) {
            SshNativeCallbackAdapter.NativeSessionClaim claim = setup.adapter.beginNativeSessionOpen();
            setup.adapter.finishNativeSessionOpen(claim, false);
            try { setup.adapter.selectAuthentication(setup.selection); fail("failed claim stays locked"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
        }
        try (SelectedSetup setup = new SelectedSetup(false)) {
            setup.prove(false);
            try { setup.adapter.selectAuthentication(setup.selection); fail("late selection"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
        }
        try (SelectedSetup setup = new SelectedSetup(false)) {
            SshRoleContext other = SshRoleContext.issue(new SshRoleContext.Bootstrap(
                    setup.issued.role.program, setup.issued.role.ownerPid, setup.issued.role.hop,
                    setup.issued.role.connectionId, setup.issued.role.targetIdentity, setup.issued.role.host,
                    setup.issued.role.port, setup.issued.role.publicViewId, bytes(32, 71), bytes(32, 72),
                    setup.issued.role.deadline), setup.view, clock);
            assertEquals(setup.issued.role.binding(), other.binding());
            try {
                setup.adapter.selectAuthentication(SshAuthenticationSelection.password(other,
                        " 显式User ", "password-other", clock));
                fail("same digest different exact role");
            } catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            setup.adapter.selectAuthentication(setup.selection);
        }
    }

    @Test public void selectedCallbacksReleaseOnlyFixedSameKindResourceAfterKnownAndNewProofs()
            throws Exception {
        for (boolean key : new boolean[] { false, true }) {
            for (boolean unknown : new boolean[] { false, true }) {
                try (SelectedSetup setup = new SelectedSetup(key)) {
                    setup.adapter.selectAuthentication(setup.selection);
                    setup.prove(unknown);
                    byte[] result = setup.adapter.onNativeCredentialBytes(key
                            ? SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY
                            : SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, setup.selection);
                    assertArrayEquals(bytes(key ? "other private fixture" : "other password fixture"), result);
                    VaultCodec.wipe(result);
                    assertEquals(0, setup.sourceCalls);
                }
            }
        }
    }

    @Test public void selectedCallbacksRejectMissingForeignAndUnsupportedReceiptsWithoutLegacySource()
            throws Exception {
        for (int mode = 0; mode < 7; mode++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                setup.prove(false);
                SshAuthenticationSelection receipt = setup.selection;
                int kind = SshNativeCallbackAdapter.CREDENTIAL_PASSWORD;
                if (mode == 1) receipt = null;
                if (mode == 2) receipt = SshAuthenticationSelection.password(setup.issued.role,
                        setup.selection.username, setup.selection.entryId, clock);
                if (mode == 3) kind = SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY;
                if (mode == 4) kind = SshNativeCallbackAdapter.CREDENTIAL_KEY_PASSPHRASE;
                if (mode == 5) kind = SshNativeCallbackAdapter.CREDENTIAL_KEYBOARD_INTERACTIVE;
                if (mode == 6) kind = 999;
                byte[] result = mode == 0 ? setup.adapter.onNativeCredentialBytes(kind)
                        : setup.adapter.onNativeCredentialBytes(kind, receipt);
                assertTrue(result == null);
                assertEquals("SSH_AUTH_SELECTION", setup.adapter.lastFailureCode());
                assertEquals(0, setup.sourceCalls);
            }
        }
    }

    @Test public void selectedCallbackStillRequiresHostAndKexAndLiveRole() throws Exception {
        for (int stage = 0; stage < 5; stage++) {
            try (SelectedSetup setup = new SelectedSetup(false)) {
                setup.adapter.selectAuthentication(setup.selection);
                if (stage == 1) assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                        "ssh-ed25519", FINGERPRINT, digest("before"), digest("after")));
                if (stage >= 2) setup.prove(false);
                if (stage == 2) clock.value = setup.issued.role.deadline;
                if (stage == 3) setup.view.active = false;
                if (stage == 4) setup.adapter.cancel();
                assertTrue(setup.adapter.onNativeCredentialBytes(
                        SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, setup.selection) == null);
                assertEquals(stage < 2 ? "SSH_KEX_REQUIRED" : stage == 2 ? "SSH_ROLE_EXPIRED"
                        : stage == 3 ? "SSH_FILE_VIEW" : "SSH_CALLBACK_CANCELLED",
                        setup.adapter.lastFailureCode());
                assertEquals(0, setup.sourceCalls);
            }
            clock.value = 1_000;
        }
    }

    @Test public void selectedRequestCannotBypassReceiptAndAlwaysClosesAndWipes() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshAuthenticationSelection selected = SshAuthenticationSelection.password(role, "user", "password", clock);
        for (int mode = 0; mode < 3; mode++) {
            SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selected);
            try {
                if (mode == 0) bridge.release(request, kex, ignored -> fail("legacy release bypass"));
                else if (mode == 1) bridge.release(request, kex,
                        SshAuthenticationSelection.password(role, "user", "password", clock),
                        ignored -> fail("foreign receipt"));
                else {
                    byte[][] retained = new byte[1][];
                    bridge.release(request, kex, selected, secret -> retained[0] = secret);
                    assertArrayEquals(new byte[bytes("fixture password").length], retained[0]);
                    try { request.requireOpen(); fail("successful selected request must close"); }
                    catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
                    continue;
                }
                fail("receipt must match exactly");
            } catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            try { request.requireOpen(); fail("request must close"); }
            catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
        }
        SshCredentialBridge.Request cancelled = bridge.openSelection(role, knownProof, selected);
        cancelled.close();
        try { bridge.release(cancelled, kex, selected, ignored -> fail("cancelled request")); fail("cancelled"); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
    }

    @Test public void selectedResourcesStillRequireVaultTypeIdentityAndEpoch() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        for (SshAuthenticationSelection invalid : new SshAuthenticationSelection[] {
                SshAuthenticationSelection.password(role, "user", "passphrase", clock),
                SshAuthenticationSelection.privateKey(role, "user", "keys/key", "other-key-identity",
                        bytes("declared public"), clock),
                SshAuthenticationSelection.privateKey(role, "user", "config", "fixture config",
                        bytes("declared public"), clock) }) {
            try { bridge.openSelection(role, knownProof, invalid); fail("wrong resource type or identity"); }
            catch (VaultException failure) { assertEquals("VAULT_OPERATION", failure.code); }
        }
        SshAuthenticationSelection selected = SshAuthenticationSelection.password(role, "user", "password", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selected);
        vault.session.lock();
        try {
            bridge.release(request, kex, selected, ignored -> fail("revoked vault epoch"));
            fail("vault lock must invalidate selected request");
        } catch (VaultException failure) { assertTrue(failure.code.startsWith("VAULT_")); }
        try { request.requireOpen(); fail("revoked request closes"); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
    }

    @Test public void selectedFinalEffectGuardRepeatsRoleLivenessAfterInitialGuard() throws Exception {
        int[] calls = new int[1];
        boolean[] armed = new boolean[1];
        SshRoleContext.Clock effectClock = () -> {
            if (armed[0] && ++calls[0] == 5) clock.value = role.deadline;
            return clock.value;
        };
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), effectClock);
        SshAuthenticationSelection selected = SshAuthenticationSelection.password(role, "user", "password", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selected);
        armed[0] = true;
        try {
            bridge.release(request, kex, selected, ignored -> fail("role expired inside final guard"));
            fail("final guard must repeat liveness");
        } catch (VaultException failure) { assertEquals("SSH_ROLE_EXPIRED", failure.code); }
        assertEquals(6, calls[0]);
        try { request.requireOpen(); fail("failed final guard must close"); }
        catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
    }

    private static final class HostResultWire {
        final long role;
        final int hop;
        final int port;
        final int classification;
        final int casStatus;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final String keyType;
        final String fingerprint;
        final String proposalBinding;
        final byte[] baseDigest;
        final byte[] replacementDigest;

        HostResultWire(long role, int hop, int port, int classification, int casStatus,
                String connectionId, String targetIdentity, String host, String keyType,
                String fingerprint, String proposalBinding, byte[] baseDigest,
                byte[] replacementDigest) {
            this.role = role;
            this.hop = hop;
            this.port = port;
            this.classification = classification;
            this.casStatus = casStatus;
            this.connectionId = connectionId;
            this.targetIdentity = targetIdentity;
            this.host = host;
            this.keyType = keyType;
            this.fingerprint = fingerprint;
            this.proposalBinding = proposalBinding;
            this.baseDigest = baseDigest;
            this.replacementDigest = replacementDigest;
        }
    }

    private static HostResultWire decodeHostResult(byte[] wire) throws IOException {
        assertNotNull(wire);
        assertTrue(wire.length <= 2_048);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(wire))) {
            assertEquals(0x43534852, input.readInt());
            assertEquals(1, input.readUnsignedByte());
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedShort());
            long role = input.readLong();
            int hop = input.readInt();
            int port = input.readInt();
            int classification = input.readInt();
            int casStatus = input.readInt();
            String connectionId = readHostField(input, false);
            String targetIdentity = readHostField(input, false);
            String host = readHostField(input, false);
            String keyType = readHostField(input, false);
            String fingerprint = readHostField(input, false);
            String proposalBinding = readHostField(input, true);
            byte[] baseDigest = input.readNBytes(32);
            byte[] replacementDigest = input.readNBytes(32);
            assertEquals(32, baseDigest.length);
            assertEquals(32, replacementDigest.length);
            assertEquals(0, input.available());
            return new HostResultWire(role, hop, port, classification, casStatus,
                    connectionId, targetIdentity, host, keyType, fingerprint,
                    proposalBinding, baseDigest, replacementDigest);
        }
    }

    private static String readHostField(DataInputStream input, boolean allowEmpty) throws IOException {
        int length = input.readUnsignedShort();
        if (!allowEmpty) assertTrue(length > 0);
        byte[] value = input.readNBytes(length);
        assertEquals(length, value.length);
        return new String(value, StandardCharsets.US_ASCII);
    }

    private SshRoleContext issueRole(int hop, String connectionId, int ownerPid, int seed) throws Exception {
        return registry.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT, ownerPid, hop,
                connectionId, TARGET, "example.invalid", 22, "view-" + hop,
                bytes(32, seed), bytes(32, seed + 1), clock.value + 30_000), new PublicView());
    }

    private static SshHostKeyAuthority.Evidence evidence(SshRoleContext context,
            SshHostKeyAuthority.Classification classification, byte[] base, byte[] replacement)
            throws VaultException {
        return SshHostKeyAuthority.Evidence.fromNative(context, classification, "ssh-ed25519",
                FINGERPRINT, base, replacement);
    }

    @Test public void selectedEncryptedKeyUsesOneExactResourceBundleAndWipesBorrowedBytes() throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role, "user", "keys/key",
                "test-key-identity", bytes("expected public"), "passphrase", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selection);
        assertEquals(VaultOperation.Purpose.SSH_PRIVATE_KEY_BUNDLE, request.operation.purpose);
        assertEquals(TARGET, request.operation.target);
        assertEquals("connection-0", request.operation.connectionId);
        assertEquals("keys/key\0test-key-identity\0passphrase", request.operation.resource);
        byte[][] borrowed = {null};
        bridge.release(request, kex, selection, value -> {
            borrowed[0] = value;
            java.nio.ByteBuffer wire = java.nio.ByteBuffer.wrap(value);
            assertEquals(0x43534b42, wire.getInt()); assertEquals(1, wire.getInt());
            byte[] key = new byte[wire.getInt()]; wire.get(key);
            byte[] passphrase = new byte[wire.getInt()]; wire.get(passphrase);
            try {
                assertArrayEquals(bytes("private key fixture"), key);
                assertArrayEquals(bytes("fixture passphrase"), passphrase);
                assertEquals(0, wire.remaining());
            } finally { VaultCodec.wipe(key); VaultCodec.wipe(passphrase); }
        });
        assertNotNull(borrowed[0]); assertArrayEquals(new byte[borrowed[0].length], borrowed[0]);
        try { bridge.release(request, kex, selection, value -> fail("no second receiver")); fail("one-shot request"); }
        catch (VaultException expected) { assertEquals("VAULT_CANCELLED", expected.code); }
    }

    @Test public void encryptedBundleRejectsWrongEntryKeyPurposeRemoteAndConnection() throws Exception {
        for (String entry : new String[]{"password", "missing"}) {
            try { vault.beginSshPrivateKeyBundle("keys/key", "test-key-identity", entry, TARGET, "connection-0");
                fail("wrong entry type or identity"); }
            catch (VaultException expected) { assertEquals("VAULT_OPERATION", expected.code); }
        }
        try { vault.beginSshPrivateKeyBundle("keys/other", "other-key-identity", "passphrase", TARGET, "connection-0");
            fail("passphrase cannot cross keys"); }
        catch (VaultException expected) { assertEquals("VAULT_OPERATION", expected.code); }
        for (int mode = 0; mode < 4; mode++) {
            VaultOperation operation = mode == 0
                    ? vault.beginSshPrivateKey("keys/key", "test-key-identity", TARGET, "connection-0")
                    : vault.beginSshPrivateKeyBundle("keys/key", "test-key-identity", "passphrase", TARGET, "connection-0");
            try {
                vault.sshPrivateKeyBundle(operation, "keys/key", "test-key-identity",
                        mode == 3 ? "password" : "passphrase", mode == 1 ? "key:test-key-identity" : TARGET,
                        mode == 2 ? "other-connection" : "connection-0", () -> { }, value -> fail("no receiver"));
                fail("resource and purpose must be exact");
            } catch (VaultException expected) { assertEquals("VAULT_OPERATION", expected.code); }
            finally { vault.cancelOperation(operation); }
        }
    }

    private void cancelRoleAtFinalStoreRead(boolean key, boolean encrypted) throws Exception {
        SshCredentialBridge bridge = new SshCredentialBridge(vault, new SshHostKeyAuthority(), clock);
        SshAuthenticationSelection selection = key
                ? SshAuthenticationSelection.privateKey(role, "user", "keys/key", "test-key-identity",
                        bytes("expected public"), encrypted ? "passphrase" : "", clock)
                : SshAuthenticationSelection.password(role, "user", "password", clock);
        SshCredentialBridge.Request request = bridge.openSelection(role, knownProof, selection);
        int[] guards = {0};
        try {
            bridge.release(request, kex, selection, () -> {
                if (++guards[0] == 2) store.beforeRead = role::close;
            }, value -> fail("a final store read revoked this role before receiver"));
            fail("final store read must revoke release");
        } catch (VaultException expected) { assertEquals("SSH_ROLE_EXPIRED", expected.code); }
        assertEquals(2, guards[0]);
    }

    @Test public void passwordFinalStoreReadRechecksRoleBeforeReceiver() throws Exception {
        cancelRoleAtFinalStoreRead(false, false);
    }

    @Test public void privateKeyFinalStoreReadRechecksRoleBeforeReceiver() throws Exception {
        cancelRoleAtFinalStoreRead(true, false);
    }

    @Test public void encryptedBundleFinalStoreReadRechecksRoleBeforeReceiver() throws Exception {
        cancelRoleAtFinalStoreRead(true, true);
    }

    private static VaultManifest fixtureManifest() throws VaultException {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", TARGET, "fixture password", "", 0, 0, bytes("fixture password"));
        VaultManifest.Entry passphrase = new VaultManifest.Entry(VaultManifest.EntryType.SSH_KEY_PASSPHRASE,
                "passphrase", "key:test-key-identity", "fixture passphrase", "", 0, 0,
                bytes("fixture passphrase"));
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "ssh-config", "config", "fixture config", bytes("Host example.invalid\n"),
                Arrays.asList("keys/key", "known_hosts"));
        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "ssh-private", "keys/key", "test-key-identity", bytes("private key fixture"), List.of());
        VaultManifest.FileObject knownHosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "ssh-trust", "known_hosts", "fixture trust", bytes("example.invalid ssh-ed25519 AAAA\n"), List.of());
        VaultManifest.Entry otherPassword = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password-other", TARGET, "other password", "", 0, 0, bytes("other password fixture"));
        VaultManifest.FileObject otherKey = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "ssh-other-private", "keys/other", "other-key-identity", bytes("other private fixture"), List.of());
        return new VaultManifest(Arrays.asList(password, passphrase, otherPassword),
                Arrays.asList(config, key, knownHosts, otherKey));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static byte[] digest(String value) { return VaultCodec.digest(bytes(value)); }
    private static byte[] digest(byte[] value) { return VaultCodec.digest(value); }

    private static byte[] callbackRecord(int hop, String connectionId, int ownerPid,
            long deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x43535242);
        output.writeByte(1);
        output.writeByte(1);
        output.writeShort(0);
        output.writeLong(31 + hop);
        output.writeInt(ownerPid);
        output.writeInt(1001);
        output.writeInt(hop);
        output.writeInt(22);
        output.writeLong(deadline);
        output.write(bytes(32, 4 + hop));
        output.write(bytes(32, 5 + hop));
        text(output, connectionId);
        text(output, TARGET);
        text(output, "example.invalid");
        text(output, "view-callback-" + hop);
        output.flush();
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }
}
