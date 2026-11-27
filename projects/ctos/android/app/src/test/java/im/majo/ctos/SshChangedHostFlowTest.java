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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
        BooleanSupplier committedProviderReturned;
        Runnable afterCommittedProviderReturn;
        @Override public long now() {
            if (afterCommittedProviderReturn != null && committedProviderReturned.getAsBoolean()
                    && isManagedPostWriterCheck()) {
                Runnable hook = afterCommittedProviderReturn;
                afterCommittedProviderReturn = null;
                hook.run();
            }
            return value;
        }

        private boolean isManagedPostWriterCheck() {
            boolean inManagedWriteBack = false;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(SshManagedKnownHostsProvider.class.getName())
                        && frame.getMethodName().equals("write")) return false;
                if (frame.getClassName().equals(SshManagedProvider.class.getName())
                        && frame.getMethodName().equals("writeBack")) inManagedWriteBack = true;
            }
            return inManagedWriteBack;
        }
    }
    private static final class View implements SshRoleContext.PublicView, VaultSession.Revocation,
            SshNativeSession.ViewBinding {
        boolean active = true;
        @Override public synchronized boolean active() { return active; }
        @Override public synchronized void revoke() { active = false; }
        @Override public synchronized boolean revocationConfirmed() { return !active; }
        @Override public long token() { return 902; }
        @Override public synchronized void close() { active = false; }
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
        int closeFailures;
        int cancelFailures;
        int closeCalls;
        @Override public long open(SshNativeCallbackAdapter target, long role, int uid, long deadline) {
            return 901;
        }
        @Override public void cancel(long handle) throws IOException {
            if (cancelFailures > 0) {
                cancelFailures--;
                throw new IOException("injected callback cancellation failure");
            }
        }
        @Override public void close(long handle) throws IOException {
            closeCalls++;
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("injected callback close failure");
            }
        }
    }

    private Store store;
    private Clock clock;
    private View view;
    private AppSecrets vault;
    private SshRoleRegistry registry;
    private SshNativeRoleBootstrap.Issued issued;
    private SshHostKeyAuthority.ChangedReview approval;
    private SshHostKeyAuthority.Review newApproval;
    private BooleanSupplier fixtureCancelled = () -> false;
    private Runnable duringProofConfirmation;
    private SshManagedKnownHostsProvider provider;
    private SshPasswordConnector.TrustCommitState trustCommit;
    private SshNativeCallbackAdapter callback;
    private CallbackNative callbackNative;
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
        initialize(privateKey, false);
    }

    private void initialize(boolean privateKey, boolean allowNew) throws Exception {
        initialize(privateKey, allowNew, null);
    }

    private void initialize(boolean privateKey, boolean allowNew,
            SshPasswordConnector.TrustCommitState suppliedTrustCommit) throws Exception {
        approval = null; newApproval = null; persisted = false; published = false; strictPolicy = false;
        duringProofConfirmation = null;
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
            if (!allowNew || !review.belongsTo(issued.role) || !CONNECTION.equals(review.connectionId)
                    || !TARGET.equals(review.targetIdentity) || !HOST.equals(review.host)
                    || review.port != 22 || !KEY.equals(review.keyType)
                    || !FINGERPRINT.equals(review.fingerprint)) throw denied();
            newApproval = review;
        }, review -> {
            if (cancelDuringReview) { callback.cancel(); throw denied(); }
            if (expireDuringReview) { clock.value = issued.role.deadline; throw denied(); }
            if (revokeDuringReview) { view.revoke(); throw denied(); }
            if (strictPolicy) throw new VaultException("SSH_HOST_CHANGED_POLICY", "Strict host policy");
            if (expireWhileWaitingForApproval) {
                try (SshHostKeyReviewMailbox mailbox = new SshHostKeyReviewMailbox(issued.role, clock,
                        () -> SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline),
                        ticket -> {
                            assertEquals(transportDeadline, ticket.deadline);
                            java.util.Map<String, Object> event = SshPasswordConnectBridge.hostReviewEvent(CONNECTION, ticket, 29_000);
                            java.util.Map<String, Object> dismissal = SshPasswordConnectBridge.hostReviewDismissal(CONNECTION, ticket);
                            assertEquals("hostKeyChanged", event.get("type"));
                            assertEquals(issued.role.hop, event.get("hop"));
                            assertEquals(issued.role.host, event.get("host"));
                            assertEquals(issued.role.port, event.get("port"));
                            assertEquals(ticket.binding, event.get("binding"));
                            assertEquals("hostKeyChangedDismissed", dismissal.get("type"));
                            assertEquals(issued.role.hop, dismissal.get("hop"));
                            assertEquals(ticket.binding, dismissal.get("binding"));
                            clock.value = transportDeadline;
                            try {
                                SshPasswordConnector.hostKeyPending(ticket, null,
                                        () -> fail("changed review must check the transport deadline"),
                                        () -> SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline));
                                fail("changed review must expire at the transport deadline");
                            } catch (VaultException expected) { assertEquals("SSH_TIMED_OUT", expected.code); }
                        })) {
                    mailbox.requireChangedApproved(review, transportDeadline);
                }
            }
            SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
            approval = review;
        });
        SshVaultAdapter adapter = new SshVaultAdapter(vault,
                (connection, target) -> {
                    if (fixtureCancelled.getAsBoolean())
                        throw new VaultException("SSH_CANCELLED", "The SSH connection was cancelled");
                    if (!allowNew || newApproval == null || !CONNECTION.equals(connection)
                            || !TARGET.equals(target)) throw denied();
                }, proposal -> {
                    if (!allowNew) throw denied();
                    requireApprovedNew(proposal);
                },
                proposal -> { throw denied(); }, (review, proposal) -> {
                    SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
                    if (approval != review) throw denied();
                });
        SshManagedKnownHostsProvider.SnapshotReceipt receipt = SshManagedKnownHostsProvider.snapshotFor(
                adapter, issued.role, clock, CONFIG, SOURCE, "selected-trust", "selected trust");
        trustCommit = suppliedTrustCommit == null ? new SshPasswordConnector.TrustCommitState() : suppliedTrustCommit;
        provider = SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, receipt,
                new SshManagedKnownHostsProvider.TrustCommitObserver() {
                    @Override public void persisted() { persisted = true; trustCommit.persisted(); }
                    @Override public void proofPublished() throws VaultException {
                        if (duringProofConfirmation != null) duringProofConfirmation.run();
                        trustCommit.proofPublished(); published = true;
                    }
                    @Override public void newPersisted() { trustCommit.newPersisted(); }
                    @Override public void newProofPublished() throws VaultException {
                        if (duringProofConfirmation != null) duringProofConfirmation.run();
                        trustCommit.newProofPublished();
                    }
                });
        SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff();
        SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, clock),
                clock, provider, provider, null);
        callbackNative = new CallbackNative();
        callback = SshNativeCallbackAdapter.bind(issued, registry, authority, clock,
                callbackNative, (token, uid) -> { }, managed, handoff);
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

    private void useNewFixture() throws Exception {
        tearDown();
        initialize(false, true);
    }

    private void requireApprovedNew(SshTrustProposal proposal) throws VaultException {
        if (newApproval == null || !newApproval.belongsTo(issued.role)
                || !proposal.matchesRole(issued.role)
                || !proposal.matchesManagedObject(VaultManifest.FileType.KNOWN_HOSTS,
                        "selected-trust", SOURCE, "selected trust")
                || !newApproval.keyType.equals(proposal.keyType)
                || !newApproval.fingerprint.equals(proposal.fingerprint)) throw denied();
        byte[] approvedBase = newApproval.baseDigest(), approvedReplacement = newApproval.replacementDigest();
        byte[] actualBase = proposal.baseDigest(), content = proposal.newContent();
        byte[] actualReplacement = digest(content);
        try {
            if (!Arrays.equals(approvedBase, actualBase)
                    || !Arrays.equals(approvedReplacement, actualReplacement)) throw denied();
        } finally {
            VaultCodec.wipe(approvedBase); VaultCodec.wipe(approvedReplacement);
            VaultCodec.wipe(actualBase); VaultCodec.wipe(content); VaultCodec.wipe(actualReplacement);
        }
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
        duringProofConfirmation = () -> assertTrue("confirmation must hold the exact view lock", Thread.holdsLock(view));
        callback.confirmChangedNativeCompletion();
        assertTrue(published);
        assertNull(provider.invalidateUnacknowledgedChanged());
        byte[] credential = callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, selection);
        assertArrayEquals(text("fixture password"), credential);
        VaultCodec.wipe(credential);
        view.revoke();
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
        callback.cancel();
        assertFalse(callback.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        AtomicReference<Boolean> wrongThreadResult = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> wrongThreadResult.set(
                callback.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD)));
        wrongThread.start(); wrongThread.join(5000);
        assertFalse(wrongThread.isAlive());
        assertEquals(Boolean.FALSE, wrongThreadResult.get());
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        try { callback.requireAuthenticationSelectionLive(); fail("uncertain trust is not live"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void newTrustCancellationAfterRealWriterReturnsLocksVaultAndReportsUncertain()
            throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        CountDownLatch writerReturned = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
        Thread cancellingThread = new Thread(() -> {
            try {
                if (!writerReturned.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("the real managed writer did not return");
                callback.cancel();
            } catch (Throwable failure) {
                cancellationFailure.set(failure);
            } finally {
                cancelled.countDown();
            }
        }, "new-trust-cancel");
        clock.committedProviderReturned = () -> store.commits == 1;
        clock.afterCommittedProviderReturn = () -> {
            writerReturned.countDown();
            try {
                if (!cancelled.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("the cancellation did not complete");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        };
        cancellingThread.start();
        byte[] result;
        try {
            result = callback.onNativeHostEvidence(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                    SshNativeCallbackAdapter.HOST_NEW, KEY, FINGERPRINT, digest(OLD), digest(NEW));
        } finally {
            writerReturned.countDown();
            cancellingThread.join(10_000);
        }
        assertFalse("cancellation thread must terminate", cancellingThread.isAlive());
        assertNull("cancellation must succeed", cancellationFailure.get());
        assertNull("the post-writer cancellation window must be reached", clock.afterCommittedProviderReturn);
        assertNull("the pending host result must not be published", result);
        assertEquals(1, store.commits);
        assertArrayEquals(NEW, read(SOURCE));
        assertArrayEquals(OLD, read(OTHER));
        assertTrue(trustCommit.written());
        assertTrue(trustCommit.publicationUncertain());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse("a committed but unpublished trust write must lock the vault", vault.session.unlocked());
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(vault,
                "SSH_CANCELLED", trustCommit.publicationUncertain(), false));
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(vault,
                "SSH_SESSION_CANCELLED", trustCommit.publicationUncertain(), false));
    }

    @Test public void newTrustRevokedAfterRealWriterReturnsLocksVault() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        clock.committedProviderReturned = () -> store.commits == 1;
        clock.afterCommittedProviderReturn = view::revoke;
        assertNull(newEvidence());
        assertNull(clock.afterCommittedProviderReturn);
        assertEquals(1, store.commits);
        assertArrayEquals(NEW, read(SOURCE));
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
        assertTrue(trustCommit.publicationUncertain());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void newTrustNativeAbortBeforeStepAcknowledgementLocksVault() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertTrue(trustCommit.publicationUncertain());
        callback.abortNativeKnownHostsCandidate();
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        assertArrayEquals(NEW, read(SOURCE));
    }

    @Test public void newTrustInvalidKexCannotPublishProofOrHideCommittedWrite() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        assertFalse(callback.onNativeKexComplete(0, bytes(32, 9)));
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
        assertTrue(trustCommit.publicationUncertain());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void newTrustCancellationBeforeOwnerConfirmationRejectsProof() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
        trustCommit.cancellation.cancel();
        try { callback.confirmNewNativeCompletion(); fail("cancelled trust cannot be confirmed"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertTrue(trustCommit.publicationUncertain());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void changedTrustCancellationBeforeOwnerConfirmationRejectsProof() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        SshPasswordConnector.requireTransportDeadline(clock.now(), transportDeadline);
        trustCommit.cancellation.cancel();
        try { callback.confirmChangedNativeCompletion(); fail("cancelled trust cannot be confirmed"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertTrue(trustCommit.publicationUncertain());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void changedTrustRevokedViewBeforeConfirmationRejectsProof() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        view.revoke();
        try { callback.confirmChangedNativeCompletion(); fail("revoked view cannot confirm trust"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertTrue(trustCommit.publicationUncertain());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void changedTrustInvalidationCannotBeOvertakenByProviderConfirmation() throws Exception {
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertNotNull(provider.invalidateUnacknowledgedChanged());
        try { provider.changedProofPublished(); fail("invalidated trust cannot be acknowledged"); }
        catch (VaultException failure) { assertEquals("SSH_HOST_KEY_CAS", failure.code); }
        assertTrue(trustCommit.publicationUncertain());
        callback.abortNativeKnownHostsCandidate();
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void newTrustRevokedViewBeforeConfirmationRejectsProof() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        view.revoke();
        try { callback.confirmNewNativeCompletion(); fail("revoked view cannot confirm trust"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertTrue(trustCommit.publicationUncertain());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
    }

    @Test public void newTrustCredentialIsUnavailableBeforeNativeAcknowledgement() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        int readsBefore = store.reads;
        assertNull(callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, selection));
        assertEquals(readsBefore, store.reads);
        assertEquals("SSH_KEX_REQUIRED", callback.lastFailureCode());
        callback.abortNativeKnownHostsCandidate();
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
    }

    @Test public void newTrustCloseFailureRetainsUncertaintyAndCanBeRetried() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        callbackNative.closeFailures = 1;
        try { callback.close(); fail("the first native close must fail"); }
        catch (VaultException failure) { assertEquals("SSH_CALLBACK_CLEANUP", failure.code); }
        assertEquals(1, callbackNative.closeCalls);
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        callback.close();
        assertEquals(2, callbackNative.closeCalls);
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertTrue(trustCommit.publicationUncertain());
        assertArrayEquals(NEW, read(SOURCE));
    }

    @Test public void newTrustCancellationFailureDoesNotHideUncertainty() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        assertNotNull(newEvidence());
        callbackNative.cancelFailures = 1;
        try { callback.cancel(); fail("native cancellation must fail"); }
        catch (VaultException failure) { assertEquals("VAULT_COMMIT_UNCERTAIN", failure.code); }
        assertEquals("VAULT_COMMIT_UNCERTAIN", callback.lastFailureCode());
        assertFalse(vault.session.unlocked());
        assertNull(callback.hostProof());
        assertNull(callback.kexProof());
        assertTrue(trustCommit.publicationUncertain());
    }

    @Test public void newTrustConfirmedAfterKexKeepsOrdinaryCancellation() throws Exception {
        useNewFixture();
        SshNativeSession.NativeBinding nativeSession = new SshNativeSession.NativeBinding() {
            @Override public long open(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, byte[] hostKey, byte[] fingerprint, String path,
                    String host, String candidate) throws IOException { throw new IOException("unexpected pinned open"); }
            @Override public long openManaged(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, String path, String host, String candidate,
                    byte[] selection) { return 903; }
            @Override public SshNativeSession.StepResult step(long handle, boolean cancelled) {
                assertTrue(newCandidate());
                assertNotNull(newEvidence());
                assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
                assertTrue("the callback alone is not native step acknowledgement",
                        trustCommit.publicationUncertain());
                return new SshNativeSession.StepResult(0, 0, 0, 0, false, false, false, true);
            }
            @Override public void verifyHostKey(long handle, byte[] key) { throw new AssertionError(); }
            @Override public void close(long handle) { }
            @Override public void destroy(long handle) { }
        };
        SshNativeSession owner = SshNativeSession.openManaged(callback, view, 41,
                "new-candidate", 1024, nativeSession);
        try {
            assertTrue(SshPasswordConnector.keyExchangeStep(owner, callback, () -> { }, trustCommit::written).complete);
            assertTrue(trustCommit.publicationUncertain());
            duringProofConfirmation = () -> assertTrue("confirmation must hold the exact view lock", Thread.holdsLock(view));
            callback.confirmNewNativeCompletion();
            assertFalse(trustCommit.publicationUncertain());
            assertNull(provider.invalidateUnacknowledgedNew());
            byte[] selectionWire = selection.encode(TOKEN, clock);
            byte[] receipt = digest(selectionWire);
            byte[] credential = callback.onNativeSelectedCredentialBytes(
                    SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, receipt, 1, bytes(32, 9));
            try { assertArrayEquals(text("fixture password"), credential); }
            finally { VaultCodec.wipe(selectionWire); VaultCodec.wipe(receipt); VaultCodec.wipe(credential); }
            view.revoke();
            trustCommit.cancellation.cancel();
            owner.cancel();
            assertTrue(vault.session.unlocked());
            assertEquals("SSH_CANCELLED", SshPasswordConnectBridge.completionFailure(vault,
                    "SSH_CANCELLED", trustCommit.publicationUncertain(), false));
            assertEquals("SSH_SESSION_CANCELLED", SshPasswordConnectBridge.completionFailure(vault,
                    "SSH_SESSION_CANCELLED", trustCommit.publicationUncertain(), false));
            assertTrue(vault.session.unlocked());
            assertArrayEquals(NEW, read(SOURCE));
        } finally { owner.close(); }
    }

    @Test public void uncommittedNewAndKnownCancellationDoNotReportUncertain() throws Exception {
        useNewFixture();
        assertTrue(newCandidate());
        callback.cancel();
        assertNull(newEvidence());
        assertEquals(0, store.commits);
        assertFalse(trustCommit.written());
        assertFalse(trustCommit.publicationUncertain());
        assertEquals("SSH_CANCELLED", SshPasswordConnectBridge.completionFailure(vault,
                "SSH_CANCELLED", trustCommit.publicationUncertain(), false));
        assertTrue(vault.session.unlocked());

        useNewFixture();
        assertNotNull(callback.onNativeHostEvidence(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_KNOWN, KEY, FINGERPRINT, digest(OLD), digest(OLD)));
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        callback.confirmNewNativeCompletion();
        callback.cancel();
        assertEquals(0, store.commits);
        assertFalse(trustCommit.written());
        assertFalse(trustCommit.publicationUncertain());
        assertEquals("SSH_SESSION_CANCELLED", SshPasswordConnectBridge.completionFailure(vault,
                "SSH_SESSION_CANCELLED", trustCommit.publicationUncertain(), false));
        assertTrue(vault.session.unlocked());
    }

    @Test public void newTrustFailureFromSessionStepMapsToUncertainAtBridge() throws Exception {
        useNewFixture();
        final SshNativeSession[] owner = new SshNativeSession[1];
        SshNativeSession.NativeBinding nativeSession = new SshNativeSession.NativeBinding() {
            @Override public long open(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, byte[] hostKey, byte[] fingerprint, String path,
                    String host, String candidate) throws IOException { throw new IOException("unexpected pinned open"); }
            @Override public long openManaged(long role, int uid, long cb, long nativeView, int fd,
                    long deadline, int budget, String path, String host, String candidate,
                    byte[] selection) { return 903; }
            @Override public SshNativeSession.StepResult step(long handle, boolean cancelled) throws IOException {
                assertTrue(newCandidate());
                assertNotNull(newEvidence());
                assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
                try { owner[0].cancel(); }
                catch (VaultException failure) { throw new AssertionError(failure); }
                throw new IOException("native step did not complete");
            }
            @Override public void verifyHostKey(long handle, byte[] key) { throw new AssertionError(); }
            @Override public void close(long handle) { }
            @Override public void destroy(long handle) { }
        };
        owner[0] = SshNativeSession.openManaged(callback, view, 41, "new-candidate", 1024, nativeSession);
        try {
            try {
                SshPasswordConnector.keyExchangeStep(owner[0], callback, () -> { }, trustCommit::written);
                fail("a cancelled native step must not complete");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_CANCELLED", failure.code);
                assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(vault,
                        failure.code, trustCommit.publicationUncertain(), false));
            }
            assertFalse(vault.session.unlocked());
            assertNull(callback.hostProof());
            assertNull(callback.kexProof());
            assertArrayEquals(NEW, read(SOURCE));
        } finally { owner[0].close(); }
    }

    @Test public void screenLockWaitsForRealInFlightNewTrustCommitBeforeBridgeResult() throws Exception {
        tearDown();
        callback = null; provider = null; registry = null;
        CountDownLatch storeCommitted = new CountDownLatch(1), allowStoreReturn = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1), mapped = new CountDownLatch(1);
        SshPasswordConnector.TrustCommitState state = new SshPasswordConnector.TrustCommitState();
        AtomicReference<String> callbackFailure = new AtomicReference<>(), bridgeResult = new AtomicReference<>();
        SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
            @Override public boolean mayWriteTrust() { return true; }
            @Override public SshSessionOwner.Cancellation trustCancellation() { return state.cancellation; }
            @Override public SshNativeSession create(BooleanSupplier cancelled) throws Exception {
                fixtureCancelled = cancelled;
                initialize(false, true, state);
                store.afterCommit = () -> {
                    storeCommitted.countDown();
                    try {
                        if (!allowStoreReturn.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("the committed store was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                };
                if (!newCandidate() || newEvidence() != null)
                    throw new AssertionError("the cancelled NEW candidate must not publish a result");
                callbackFailure.set(callback.lastFailureCode());
                if (callback.hostProof() != null || callback.kexProof() != null)
                    throw new AssertionError("cancelled trust published a proof");
                // The session normally reports cancellation; the bridge must retain the durable-write state.
                throw new VaultException("SSH_SESSION_CANCELLED", "The native step was cancelled");
            }
            @Override public void close() throws Exception {
                SshChangedHostFlowTest.this.tearDown();
                callback = null; provider = null; registry = null;
            }
        };
        SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, new SshTerminalState(),
                Runnable::run, new SshSessionOwner.Lifecycle() {
                    @Override public void state(String value) { }
                    @Override public void released() { released.countDown(); }
                });
        owner.connected.whenComplete((mode, failure) -> {
            try {
                String code = failure instanceof VaultException ? ((VaultException)failure).code : "unexpected";
                bridgeResult.set(SshPasswordConnectBridge.completionFailure(vault, code,
                        state.publicationUncertain(), false));
            } finally { mapped.countDown(); }
        });
        owner.begin();
        try {
            assertTrue(storeCommitted.await(10, TimeUnit.SECONDS));
            assertEquals(1, store.commits);
            assertFalse("the provider has not returned its receipt", state.publicationUncertain());
            assertTrue(owner.stopForScreenLock(owner.id));
            assertFalse("the final connection result must await the in-flight write", owner.connected.isDone());
            allowStoreReturn.countDown();
            try { owner.connected.get(10, TimeUnit.SECONDS); fail("screen lock must stop the connection"); }
            catch (ExecutionException failure) {
                assertEquals("SSH_SCREEN_LOCKED", ((VaultException)failure.getCause()).code);
            }
            assertTrue(mapped.await(10, TimeUnit.SECONDS));
            assertEquals("VAULT_COMMIT_UNCERTAIN", callbackFailure.get());
            assertEquals("VAULT_COMMIT_UNCERTAIN", bridgeResult.get());
            assertTrue(state.publicationUncertain());
            assertFalse(vault.session.unlocked());
            assertArrayEquals(NEW, read(SOURCE));
            assertTrue(released.await(10, TimeUnit.SECONDS));
        } finally {
            allowStoreReturn.countDown();
            released.await(10, TimeUnit.SECONDS);
            fixtureCancelled = () -> false;
        }
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

    @Test public void proofFailureNeverHidesAnUnconfirmedChangedTrustWrite() throws Exception {
        assertEquals(SshHostKeyAuthority.PROOF_INVALID, SshPasswordConnectBridge.completionFailure(
                vault, SshHostKeyAuthority.PROOF_INVALID, false, false));
        assertTrue(vault.session.unlocked());
        assertTrue(candidate(records()));
        assertTrue(review(TOKEN, KEY, FINGERPRINT, digest(OLD), digest(NEW)));
        assertTrue(callback.onNativeKexComplete(1, bytes(32, 9)));
        assertTrue(persisted);
        assertFalse(published);
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(
                vault, SshHostKeyAuthority.PROOF_INVALID, persisted && !published, false));
        assertFalse(vault.session.unlocked());
    }

    @Test public void cancelledConnectionAfterCredentialWriteKeepsUncertainReceipt() throws Exception {
        assertEquals("VAULT_COMMIT_UNCERTAIN", SshPasswordConnectBridge.completionFailure(
                vault, "SSH_SESSION_CANCELLED", false, true));
        assertFalse(vault.session.unlocked());
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
    private boolean newCandidate() {
        return callback.onNativeKnownHostsCandidate(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_NEW, KEY, FINGERPRINT, digest(OLD), digest(NEW),
                "new-candidate", NEW.clone());
    }
    private byte[] newEvidence() {
        return callback.onNativeHostEvidence(TOKEN, 0, 22, CONNECTION, TARGET, HOST,
                SshNativeCallbackAdapter.HOST_NEW, KEY, FINGERPRINT, digest(OLD), digest(NEW));
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
