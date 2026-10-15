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
        @Override public long now() { return value; }
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
        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
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
        return new VaultManifest(Arrays.asList(password, passphrase), Arrays.asList(config, key, knownHosts));
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
