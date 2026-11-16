package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises the Java callback and exact-source CAS against an encrypted AppSecrets vault. */
public final class SshChangedHostFlowTest {
    private static final char[] PASSWORD = "changed trust test vault".toCharArray();
    private static final String HOST = "example.invalid";
    private static final String TARGET = "target:test@example.invalid:22";
    private static final String CONNECTION = "changed-connection";
    private static final String SOURCE = ".ssh/known_hosts";
    private static final String OTHER = "ssh/known_hosts";
    private static final String CONFIG = ".ssh/config";
    private static final String KEY_PATH = ".ssh/id_ed25519";
    private static final String KEY = "ssh-ed25519";
    private static final String FINGERPRINT = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(bytes(32, 7));
    private static final byte[] OLD = text("example.invalid ssh-ed25519 OLD\n");
    private static final byte[] NEW = text("example.invalid ssh-ed25519 NEW\n");
    private static final byte[] STALE = text("example.invalid ssh-ed25519 STALE\n");
    private static final byte[] PRIVATE_KEY = text("private key fixture\n");
    private static final long TOKEN = 31;

    private static final class Clock implements VaultSession.Clock, SshRoleContext.Clock {
        long value = 1_000;
        @Override public long now() { return value; }
    }
    private static final class View implements SshRoleContext.PublicView, VaultSession.Revocation,
            SshNativeSession.ViewBinding {
        boolean active = true;
        @Override public boolean active() { return active; }
        @Override public void revoke() { active = false; }
        @Override public long token() { return 902; }
        @Override public void close() { active = false; }
    }
    private static final class Store implements AppSecrets.Store {
        byte[] data;
        Runnable afterCommit;
        int commits;
        int reads;
        @Override public byte[] read() { reads++; return data.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard)
                throws IOException, VaultException {
            guard.beforeCommit();
            data = next.clone();
            commits++;
            if (afterCommit != null) afterCommit.run();
        }
    }
    private static final class CallbackNative implements SshNativeCallbackAdapter.NativeBinding {
        @Override public long open(SshNativeCallbackAdapter target, long role, int uid, long deadline) {
            return 901;
        }
        @Override public void cancel(long handle) { }
        @Override public void close(long handle) { }
    }

    private Store store;
    private Clock clock;
    private View view;
    private AppSecrets vault;
    private SshRoleRegistry registry;
    private SshNativeRoleBootstrap.Issued issued;
    private SshHostKeyAuthority.ChangedReview approval;
    private SshManagedKnownHostsProvider provider;
    private SshNativeCallbackAdapter callback;
    private SshAuthenticationSelection selection;
    private long transportDeadline;
    private boolean persisted;
    private boolean published;
    private boolean strictPolicy;
    private boolean cancelDuringReview;
    private boolean expireDuringReview;
    private boolean revokeDuringReview;
    private boolean expireWhileWaitingForApproval;

    @Before public void setUp() throws Exception {
        initialize(false);
    }

    private void initialize(boolean privateKey) throws Exception {
        approval = null; persisted = false; published = false; strictPolicy = false;
        cancelDuringReview = false; expireDuringReview = false; revokeDuringReview = false;
        expireWhileWaitingForApproval = false;
        store = new Store();
        store.data = encryptedFixture(7, OLD);
        clock = new Clock();
        transportDeadline = 20_000;
        vault = new AppSecrets(store, clock);
        // The real native known_hosts parser is covered separately by native protocol tests.
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.unlock(PASSWORD, () -> { });
        view = new View();
        vault.session.attachRevocation(vault.session.epoch(), view);
        registry = new SshRoleRegistry(clock);
        issued = SshNativeRoleBootstrap.issue(roleWire(), registry, view, clock, ignored -> true);
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "NEW approval is unavailable");
        }, review -> {
            if (cancelDuringReview) { callback.cancel(); throw denied(); }
            if (expireDuringReview) { clock.value = issued.role.deadline; throw denied(); }
            if (revokeDuringReview) { view.revoke(); throw denied(); }
            if (strictPolicy) throw new VaultException("SSH_HOST_CHANGED_POLICY", "Strict host policy");
            if (expireWhileWaitingForApproval) {
                try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(issued.role, clock,
                        () -> SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline),
                        ticket -> { assertEquals(transportDeadline, ticket.deadline); clock.value = transportDeadline; })) {
                    mailbox.requireChangedApproved(review, transportDeadline);
                }
            }
            SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
            approval = review;
        });
        SshVaultAdapter adapter = new SshVaultAdapter(vault,
                (connection, target) -> { throw denied(); }, proposal -> { throw denied(); },
                proposal -> { throw denied(); }, (review, proposal) -> {
                    SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
                    if (approval != review) throw denied();
                });
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                adapter, issued.role, clock, CONFIG, SOURCE, "selected-trust", "selected trust");
        provider = SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, receipt,
                new SshManagedKnownHostsProvider.ChangedCommitObserver() {
                    @Override public void persisted() { persisted = true; }
                    @Override public void proofPublished() { published = true; }
                });
        SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff();
        SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                clock, provider, provider, null);
        callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock,
                new CallbackNative(), (token, uid) -> { }, managed, handoff);
        selection = privateKey
                ? SshAuthenticationSelection.privateKey(issued.role, "test", KEY_PATH,
                        "selected key", text("public key fixture"), clock).withKeyboardContinuation(clock)
                : SshAuthenticationSelection.password(issued.role, "test", "password", clock);
        callback.selectAuthentication(selection);
    }

    private void usePrivateKeyFixture() throws Exception {
        tearDown();
        initialize(true);
    }

    @After public void tearDown() throws Exception {
        if (callback != null) callback.close();
        if (provider != null) provider.close();
        if (registry != null) registry.close();
    }

    @Test public void signedCompletionReplacesOnlyChosenSourceAndRetainsItsViewAcrossOwnEpoch()
            throws Exception {
        long oldEpoch = vault.session.epoch();
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals(SOURCE, approval.source);
        assertEquals(2, approval.oldRecords.size());
        assertEquals(approval.oldRecords.get(0).fingerprint, approval.oldRecords.get(1).fingerprint);
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        assertArrayEquals(OLD, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertEquals(0, store.commits);

        byte[] sessionDigest = bytes(32, 9);
        assertTrue(callback.onNativeKexComplete(13, sessionDigest));
        Arrays.fill(sessionDigest, (byte) 0);
        assertArrayEquals(NEW, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertEquals(1, store.commits);
        assertEquals(oldEpoch + 1, vault.session.epoch());
        assertTrue(view.active());
        assertTrue(persisted);
        assertFalse(published);
        assertEquals(SshHostKeyAuthority.Classification.CHANGED, callback.hostProof().classification);
        assertTrue(callback.hostProof().permitsCredentials());
        assertEquals(13, callback.kexProof().cycle);
        assertArrayEquals(bytes(32, 9), callback.kexProof().sessionIdDigest());
        callback.confirmChangedNativeCompletion();
        assertTrue(published);
        byte[] credential = callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, selection);
        assertArrayEquals(text("fixture password"), credential);
        VaultCodec.wipe(credential);
        callback.cancel();
        assertTrue("ordinary cancellation after native completion leaves the known commit intact",
                vault.session.unlocked());
        assertEquals("SSH_CANCELLED", SshPasswordConnectBridge.completionFailure(vault, "SSH_CANCELLED",
                persisted && !published, false));
        assertTrue(vault.session.unlocked());
    }

    @Test public void cancellationBeforeCasLeavesBothSourcesUntouched() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        callback.cancel();
        assertFalse(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertEquals(0, store.commits);
        assertArrayEquals(OLD, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertFalse(persisted);
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void cancellationDuringReviewOutranksTheFollowingReviewFailure() throws Exception {
        cancelDuringReview = true;
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals("SSH_CALLBACK_CANCELLED", callback.lastFailureCode());
        assertEquals(0, store.commits);
        assertNull(callback.hostProof());
    }

    @Test public void expiryDuringReviewOutranksTheFollowingReviewFailure() throws Exception {
        expireDuringReview = true;
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals("SSH_ROLE_EXPIRED", callback.lastFailureCode());
        assertEquals(0, store.commits);
        assertNull(callback.hostProof());
    }

    @Test public void viewRevocationDuringReviewOutranksTheFollowingReviewFailure() throws Exception {
        revokeDuringReview = true;
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals("SSH_FILE_VIEW", callback.lastFailureCode());
        assertEquals(0, store.commits);
        assertNull(callback.hostProof());
    }

    @Test public void cancellationDuringRealCommitLocksVaultAndReportsUncertain() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        store.afterCommit = () -> {
            try { callback.cancel(); }
            catch (VaultException failure) { throw new AssertionError(failure); }
        };
        assertFalse(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertEquals(1, store.commits);
        assertArrayEquals(NEW, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertFalse(vault.session.unlocked());
        assertTrue(persisted);
        assertFalse(published);
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(vault, "SSH_CANCELLED",
                persisted && !published, false));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void nativeAbortAfterJavaCommitBeforeStepAcknowledgementLocksVault() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(2, bytes(32, 9)));
        assertTrue(persisted);
        assertFalse(published);
        callback.abortNativeKnownHostsCandidate();
        assertFalse(vault.session.unlocked());
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        assertArrayEquals(NEW, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
    }

    @Test public void selectedCredentialCannotBeReadBeforeNativeAcknowledgement() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(2, bytes(32, 9)));
        int readsBefore = store.reads;
        assertNull(callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, selection));
        assertEquals(readsBefore, store.reads);
        assertEquals("SSH_KEX_REQUIRED", callback.lastFailureCode());
        callback.abortNativeKnownHostsCandidate();
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
    }

    @Test public void selectedPrivateKeyAndKeyboardContinuationUseCommittedChangedProofOnce() throws Exception {
        usePrivateKeyFixture();
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(3, bytes(32, 9)));
        callback.confirmChangedNativeCompletion();
        byte[] key = callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY, selection);
        assertArrayEquals(PRIVATE_KEY, key);
        VaultCodec.wipe(key);
        callback.requireKeyboardContinuation();
        int reads = store.reads;
        assertNull(callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY, selection));
        assertEquals(reads, store.reads);
        assertArrayEquals(NEW, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
    }

    @Test public void selectedPrivateKeyIsNotReadBeforeNativeAcknowledgement() throws Exception {
        usePrivateKeyFixture();
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(3, bytes(32, 9)));
        int reads = store.reads;
        assertNull(callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY, selection));
        assertEquals(reads, store.reads);
        callback.abortNativeKnownHostsCandidate();
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
    }

    @Test public void changedSourceDuringReviewFailsCasWithoutWritingAnotherObject() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        replaceFixtureSource(STALE);
        assertFalse(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertEquals(0, store.commits);
        assertArrayEquals(STALE, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void shorterTransportDeadlineExpiresBeforeCasWithoutAWrite() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        clock.value = transportDeadline;
        assertFalse(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertEquals(0, store.commits);
        assertArrayEquals(OLD, read(SOURCE));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void shorterTransportDeadlineExpiresWhileReviewIsDisplayedWithoutAWrite() throws Exception {
        expireWhileWaitingForApproval = true;
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals("SSH_TIMED_OUT", callback.lastFailureCode());
        assertNull(approval);
        assertEquals(0, store.commits);
        assertArrayEquals(OLD, read(SOURCE));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void completedNonChangedTransportKeepsItsOriginalDeadlineBehavior() throws Exception {
        clock.value = transportDeadline;
        SshPasswordConnector.requireChangedCompletionDeadline(false, clock, transportDeadline);
        try {
            SshPasswordConnector.requireChangedCompletionDeadline(true, clock, transportDeadline);
            fail("an unconfirmed changed-host write must respect the original transport deadline");
        } catch (VaultException failure) { assertEquals("SSH_TIMED_OUT", failure.code); }
    }

    @Test public void forgedReviewRoleCannotWrite() throws Exception {
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN + 1, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals(0, store.commits);
        assertArrayEquals(OLD, read(SOURCE));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void forgedReviewKeyCannotWrite() throws Exception {
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, "ssh-rsa", FINGERPRINT, digest(OLD), digest(NEW)));
        assertEquals(0, store.commits);
        assertNull(callback.hostProof());
    }

    @Test public void forgedReviewBaseCannotWrite() throws Exception {
        assertTrue(candidate(records()));
        assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(STALE), digest(NEW)));
        assertEquals(0, store.commits);
        assertNull(callback.hostProof());
    }

    @Test public void forgedReplacementDigestCannotCreateReview() throws Exception {
        assertFalse(callback.onNativeChangedHostCandidate(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_CHANGED, KEY, FINGERPRINT, digest(OLD), digest(STALE),
                "candidate-1", NEW.clone(), records()));
        assertNull(approval);
        assertEquals(0, store.commits);
    }

    @Test public void invalidCycleCannotPublishOrWrite() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertFalse(callback.onNativeKexComplete(0, bytes(32, 9)));
        assertEquals(0, store.commits);
        assertArrayEquals(OLD, read(SOURCE));
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void emptyRecordsNeverCreateReview() throws Exception {
        byte[] wire = SshHostTrustMaterialTest.wire(OLD, false, null, new int[0][0]);
        assertFalse(candidate(wire));
        assertNull(approval);
        assertEquals(0, store.commits);
    }

    @Test public void protectedRecordsNeverCreateReview() throws Exception {
        byte[] wire = SshHostTrustMaterialTest.wire(OLD, false, null, new int[][] {{1, 1, 0, 2}});
        assertFalse(candidate(wire));
        assertNull(approval);
        assertEquals(0, store.commits);
    }

    @Test public void noncanonicalOfferedFingerprintNeverCreatesReview() throws Exception {
        assertFalse(callback.onNativeChangedHostCandidate(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_CHANGED, KEY, "SHA256:" + "A".repeat(42) + "B",
                digest(OLD), digest(NEW), "candidate-1", NEW.clone(), records()));
        assertNull(approval);
        assertEquals(0, store.commits);
    }

    @Test public void strictPolicySurvivesNativeSessionFailureWithoutOfferingChangedReview() throws Exception {
        assertStrictPolicyResult(false, "SSH_HOST_CHANGED_POLICY");
    }

    @Test public void realCancellationOutranksStrictPolicyAtSessionBoundary() throws Exception {
        assertStrictPolicyResult(true, "SSH_SESSION_CANCELLED");
    }

    private void assertStrictPolicyResult(boolean cancel, String expected) throws Exception {
        strictPolicy = true;
        final SshNativeSession[] owner = new SshNativeSession[1];
        SshNativeSession.NativeBinding nativeSession = new SshNativeSession.NativeBinding() {
            @Override public long open(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, byte[] hostKey, byte[] fingerprint, String path,
                    String host, String candidate) throws IOException { throw new IOException("unexpected pinned open"); }
            @Override public long openManaged(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, String path, String host, String candidate,
                    byte[] selection) { return 903; }
            @Override public SshNativeSession.StepResult step(long handle, boolean cancelled) throws IOException {
                assertTrue(candidate(records()));
                assertFalse(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
                if (cancel) {
                    try { owner[0].cancel(); }
                    catch (VaultException failure) { throw new AssertionError(failure); }
                }
                throw new IOException("native rejected the changed-host callback");
            }
            @Override public void verifyHostKey(long handle, byte[] key) { throw new AssertionError(); }
            @Override public void close(long handle) { }
            @Override public void destroy(long handle) { }
        };
        owner[0] = SshNativeSession.openManaged(callback, view, 41, "candidate-1", 1024, nativeSession);
        try {
            try { owner[0].step(); fail("expected strict policy failure"); }
            catch (VaultException failure) { assertEquals(expected, failure.code); }
            assertNull(approval);
            assertNull(callback.hostProof());
            assertEquals(0, store.commits);
        } finally { owner[0].close(); }
    }

    private boolean candidate(byte[] rows) {
        return callback.onNativeChangedHostCandidate(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_CHANGED, KEY, FINGERPRINT, digest(OLD), digest(NEW),
                "candidate-1", NEW.clone(), rows);
    }
    private boolean review(long token, String key, String fingerprint, byte[] base, byte[] replacement) {
        return callback.onNativeChangedHostReview(token, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_CHANGED, key, fingerprint, base, replacement);
    }
    private static byte[] records() {
        return SshHostTrustMaterialTest.wire(OLD, false, null,
                new int[][] {{1, 0, 0, 0}, {2, 0, 0, 0}});
    }
    private byte[] read(String path) throws Exception {
        try (AppSecrets.Verified verified = AppSecrets.verify(store.data, PASSWORD)) {
            for (VaultManifest.FileObject file : verified.manifest.files)
                if (file.path.equals(path)) return file.content.clone();
        }
        throw new AssertionError("missing fixture source");
    }
    private void replaceFixtureSource(byte[] content) throws Exception {
        VaultEnvelope current = VaultEnvelope.parse(store.data);
        byte[] key = VaultEnvelope.derive(PASSWORD, current.salt);
        try (VaultManifest manifest = manifest(content)) {
            byte[] plaintext = manifest.encode();
            try { store.data = VaultEnvelope.encrypt(current.id, current.generation + 1,
                    current.salt, key, plaintext).encode(); }
            finally { VaultCodec.wipe(plaintext); }
        } finally { VaultCodec.wipe(key); }
    }
    private static byte[] encryptedFixture(long generation, byte[] selected) throws Exception {
        byte[] salt = bytes(16, 7), key = VaultEnvelope.derive(PASSWORD, salt);
        try (VaultManifest manifest = manifest(selected)) {
            byte[] plaintext = manifest.encode();
            try { return VaultEnvelope.encrypt(UUID.randomUUID(), generation, salt, key, plaintext).encode(); }
            finally { VaultCodec.wipe(plaintext); }
        } finally { VaultCodec.wipe(key); }
    }
    private static VaultManifest manifest(byte[] selected) throws VaultException {
        VaultManifest.Entry password = new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD,
                "password", TARGET, "fixture password", "", 0, 0, text("fixture password"));
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "config", CONFIG, "fixture config", text("Host example.invalid\n"), List.of(SOURCE, OTHER, KEY_PATH));
        VaultManifest.FileObject chosen = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "selected-trust", SOURCE, "selected trust", selected, List.of());
        VaultManifest.FileObject other = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "other-trust", OTHER, "other trust", OLD, List.of());
        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "selected-key", KEY_PATH, "selected key", PRIVATE_KEY, List.of());
        return new VaultManifest(List.of(password), List.of(config, chosen, other, key));
    }
    private static byte[] roleWire() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(0x43535242); out.writeByte(1); out.writeByte(1); out.writeShort(0);
        out.writeLong(TOKEN); out.writeInt(31_000); out.writeInt(1001); out.writeInt(0); out.writeInt(22);
        out.writeLong(31_000); out.write(bytes(32, 4)); out.write(bytes(32, 5));
        writeText(out, CONNECTION); writeText(out, TARGET); writeText(out, HOST); writeText(out, "view-changed");
        out.flush();
        return buffer.toByteArray();
    }
    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] encoded = text(value);
        out.writeShort(encoded.length); out.write(encoded);
    }
    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] bytes(int length, int value) { byte[] result = new byte[length]; Arrays.fill(result, (byte)value); return result; }
    private static byte[] digest(byte[] value) { return VaultCodec.digest(value); }
    private static VaultException denied() { return new VaultException("SSH_HOST_KEY_REVIEW", "Approval required"); }
}
