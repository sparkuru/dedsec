package im.majo.ctos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Synchronous callback boundary for a future same-process native SSH event
 * loop.  A binding is created on the event-loop thread and every callback is
 * rejected unless it returns to that exact thread while its role and deadline
 * are still live.  This class is a contract seam, not an OpenSSH event loop or
 * the concrete managed config/known-hosts file and trust-CAS provider.
 */
final class SshNativeCallbackAdapter implements AutoCloseable {
    /* A point-in-time native check for a future chain guard, not an effect permit. */
    private native boolean nativeCallbackAncestorProofsLive(long callback);
    /* Implemented only by the native test build, never by a release library. */
    private native boolean nativeProvisionalNewFixtureScope(long callback, int stage);
    private native boolean nativeSelectedEffectCopyBegin();
    private native void nativeSelectedEffectCopyEnd();
    private static volatile boolean jumpEffectFenceEnabled;
    private static int jumpEffectOwners;

    static synchronized void enableJumpEffectFence() throws VaultException {
        try {
            if (jumpEffectOwners == Integer.MAX_VALUE || !Native.effectAvailable()) throw new IllegalStateException();
            jumpEffectOwners++;
            jumpEffectFenceEnabled = true;
        } catch (LinkageError | RuntimeException unavailable) {
            throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect guard is unavailable");
        }
    }

    static synchronized void disableJumpEffectFence() {
        if (jumpEffectOwners <= 0) throw new IllegalStateException("Unbalanced SSH jump effect owner");
        if (--jumpEffectOwners == 0) jumpEffectFenceEnabled = false;
    }

    static long captureJumpEffectGeneration() throws VaultException {
        if (!jumpEffectFenceEnabled) throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect guard is unavailable");
        try {
            long generation = Native.effectSnapshot();
            if (generation > 0 && generation < 0xffffffffL) return generation;
        } catch (LinkageError | RuntimeException unavailable) {
            throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect guard is unavailable");
        }
        throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect guard is unavailable");
    }

    /** The caller holds the monitor protecting the state it is about to revoke. */
    static boolean beginJumpEffectInvalidation() {
        if (!jumpEffectFenceEnabled) return false;
        Native.effectInvalidateBegin();
        return true;
    }

    static void endJumpEffectInvalidation(boolean begun) {
        if (begun) Native.effectInvalidateEnd();
    }

    /* -1: confirmed pre-mutation failure; 0: no fence; 1: begun. An
     * exception cannot establish whether native changed the generation. */
    static int beginJumpCleanupInvalidation() {
        if (!jumpEffectFenceEnabled) return 0;
        return Native.effectInvalidateBeginCleanup();
    }

    /* These values mirror ssh_callback_adapter.h explicitly, not enum ordinals. */
    static final int HOST_KNOWN = 1;
    static final int HOST_NEW = 2;
    static final int HOST_CHANGED = 3;
    static final int HOST_REVOKED = 4;
    static final int HOST_CERTIFICATE = 5;
    static final int HOST_INVALID = 6;

    static final int CREDENTIAL_PASSWORD = 1;
    static final int CREDENTIAL_KEY_PASSPHRASE = 2;
    static final int CREDENTIAL_KEYBOARD_INTERACTIVE = 3;
    static final int CREDENTIAL_PRIVATE_KEY = 4;
    static final int MAX_CREDENTIAL_BYTES = VaultManifest.MAX_FILE_BYTES;
    static final int HOST_RESULT_MAGIC = 0x43534852; // CSHR
    static final int HOST_RESULT_VERSION = 1;
    static final int HOST_RESULT_APPROVED = 1;
    static final int MAX_HOST_RESULT_BYTES = 2048;

    interface NativeBinding {
        long open(SshNativeCallbackAdapter target, long role, int ownerUid, long deadline)
                throws IOException;
        void cancel(long callback) throws IOException;
        void close(long callback) throws IOException;
    }

    private static final class ProductionNativeBinding implements NativeBinding {
        @Override public long open(SshNativeCallbackAdapter target, long role, int ownerUid,
                long deadline) throws IOException {
            return Native.open(target, role, ownerUid, deadline);
        }

        @Override public void cancel(long callback) throws IOException { Native.cancel(callback); }

        @Override public void close(long callback) throws IOException { Native.close(callback); }
    }

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }

        static native long open(SshNativeCallbackAdapter target, long role, int ownerUid,
                long deadline) throws IOException;
        static native void cancel(long callback) throws IOException;
        static native void close(long callback) throws IOException;
        static native boolean effectAvailable();
        static native long effectSnapshot();
        static native void effectInvalidateBegin();
        static native void effectInvalidateEnd();
        static native int effectInvalidateBeginCleanup();
    }

    private static final class Rejected extends Exception {
        final String code;
        Rejected(String code) { this.code = code; }
    }

    private static final class Invocation {
        final long serial;
        Invocation(long serial) { this.serial = serial; }
    }

    private static final class PendingChanged implements AutoCloseable {
        final SshHostKeyAuthority.Evidence evidence;
        final java.util.List<SshHostTrustMaterial.Record> oldRecords;
        SshTrustProposal proposal;
        SshHostKeyAuthority.ChangedReview review;
        ChangedAuthorization authorization;

        PendingChanged(SshHostKeyAuthority.Evidence evidence,
                java.util.List<SshHostTrustMaterial.Record> oldRecords) {
            this.evidence = evidence; this.oldRecords = oldRecords;
        }

        boolean matches(SshHostKeyAuthority.Evidence candidate) {
            if (candidate == null || candidate.role != evidence.role
                    || candidate.classification != SshHostKeyAuthority.Classification.CHANGED
                    || !candidate.keyType.equals(evidence.keyType)
                    || !candidate.fingerprint.equals(evidence.fingerprint)) return false;
            byte[] leftBase = evidence.baseDigest(), rightBase = candidate.baseDigest();
            byte[] leftReplacement = evidence.replacementDigest(), rightReplacement = candidate.replacementDigest();
            try {
                return MessageDigest.isEqual(leftBase, rightBase)
                        && MessageDigest.isEqual(leftReplacement, rightReplacement);
            } finally {
                VaultCodec.wipe(leftBase); VaultCodec.wipe(rightBase);
                VaultCodec.wipe(leftReplacement); VaultCodec.wipe(rightReplacement);
            }
        }

        @Override public void close() {
            if (authorization != null) authorization.close();
            if (proposal != null) proposal.close();
        }
    }

    /* Test-only signed milestone. This object is not a host proof or a write permit. */
    private static final class PendingProvisionalFixture implements AutoCloseable {
        final SshHostKeyAuthority.Evidence evidence;
        final SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        final SshKnownHostsCandidateHandoff handoff;
        final String candidateId;
        final Object candidateIdentity;
        final long candidateSerial;
        long reviewSerial;
        long cycle;
        byte[] sessionDigest;
        boolean signedReviewIssued;
        SignedNewFixtureReview signedReview;
        SshManagedKnownHostsProvider.FixtureCandidateReservation reservation;

        PendingProvisionalFixture(SshHostKeyAuthority.Evidence evidence,
                SshManagedKnownHostsProvider.ChildTrustSnapshot source,
                SshKnownHostsCandidateHandoff handoff, String candidateId,
                Object candidateIdentity, long candidateSerial) {
            this.evidence = evidence;
            this.source = source;
            this.handoff = handoff;
            this.candidateId = candidateId;
            this.candidateIdentity = candidateIdentity;
            this.candidateSerial = candidateSerial;
        }

        boolean matches(SshHostKeyAuthority.Evidence actual) {
            return actual != null && actual.role == evidence.role
                    && actual.classification == SshHostKeyAuthority.Classification.NEW
                    && actual.keyType.equals(evidence.keyType)
                    && actual.fingerprint.equals(evidence.fingerprint)
                    && MessageDigest.isEqual(actual.baseDigest, evidence.baseDigest)
                    && MessageDigest.isEqual(actual.replacementDigest, evidence.replacementDigest);
        }

        @Override public void close() {
            if (reservation != null) reservation.revoke();
            if (signedReview != null) signedReview.close();
            VaultCodec.wipe(sessionDigest);
            VaultCodec.wipe(evidence.baseDigest);
            VaultCodec.wipe(evidence.replacementDigest);
        }
    }

    /** Only the real test JNI child may issue this non-authorizing review. */
    static final class SignedNewFixtureReview implements AutoCloseable {
        private final SshNativeCallbackAdapter owner;
        private final PendingProvisionalFixture pending;
        private final SshNativeSession.SignedNewFixtureReady observation;
        private final byte[] baseDigest, replacementDigest, sessionDigest;
        private boolean claimed, closed;
        final String binding, connectionId, targetIdentity, host, keyType, fingerprint;
        final int hop, port;
        final long deadline, cycle;

        private SignedNewFixtureReview(SshNativeCallbackAdapter owner, PendingProvisionalFixture pending,
                SshNativeSession.SignedNewFixtureReady observation, long cycle, byte[] baseDigest,
                byte[] replacementDigest, byte[] sessionDigest,
                SshManagedKnownHostsProvider.SnapshotReceipt receipt) {
            this.owner = owner;
            this.pending = pending;
            this.observation = observation;
            SshRoleContext role = pending.evidence.role;
            connectionId = role.connectionId; targetIdentity = role.targetIdentity;
            host = role.host; port = role.port; hop = role.hop;
            keyType = pending.evidence.keyType; fingerprint = pending.evidence.fingerprint;
            deadline = observation.deadline();
            this.cycle = cycle;
            this.baseDigest = baseDigest;
            this.replacementDigest = replacementDigest;
            this.sessionDigest = sessionDigest;
            byte[] unique = (java.util.UUID.randomUUID() + "\0" + role.binding() + "\0"
                    + connectionId + "\0" + targetIdentity + "\0" + host + "\0" + port + "\0"
                    + hop + "\0" + keyType + "\0" + fingerprint + "\0" + pending.candidateId + "\0"
                    + cycle + "\0" + receipt.vaultId + "\0" + receipt.generation + "\0"
                    + receipt.epoch + "\0" + receipt.path + "\0" + receipt.objectId + "\0"
                    + receipt.objectIdentity + "\0" + receipt.requestId + "\0"
                    + SshConnectionMaterial.hex(baseDigest.clone()) + "\0"
                    + SshConnectionMaterial.hex(replacementDigest.clone()) + "\0"
                    + SshConnectionMaterial.hex(sessionDigest.clone())).getBytes(StandardCharsets.UTF_8);
            byte[] digest = null;
            try { digest = VaultCodec.digest(unique); binding = SshConnectionMaterial.hex(digest); }
            finally { VaultCodec.wipe(unique); VaultCodec.wipe(digest); }
        }

        void requireClaimant(SshRoleContext role) throws VaultException {
            if (Thread.currentThread() != owner.eventLoopThread || role != pending.evidence.role)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review owner changed");
            synchronized (owner.stateLock) {
                if (closed || claimed || pending.signedReview != this)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review was consumed");
            }
        }

        void claim(SshRoleContext role) throws VaultException {
            requireClaimant(role);
            try { requireLive(); }
            catch (VaultException | RuntimeException | Error failure) { close(); throw failure; }
            synchronized (owner.stateLock) {
                if (closed || claimed || pending.signedReview != this)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review was consumed");
                claimed = true;
            }
            try { requireLive(); }
            catch (VaultException | RuntimeException | Error failure) { close(); throw failure; }
        }

        void requireLive() throws VaultException { owner.requireSignedNewFixtureLive(pending, observation, this); }

        void requireSession(SshNativeSession session) throws VaultException {
            if (!observation.belongsTo(session))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review session changed");
        }

        void requireLiveFor(SshNativeSession session) throws VaultException {
            requireSession(session);
            requireLive();
        }

        boolean matchesFixtureCandidate(SshNativeSession session,
                SshManagedKnownHostsProvider.ChildTrustSnapshot source, SshHostKeyAuthority.Evidence evidence,
                String candidateId, Object candidateIdentity) {
            return observation.belongsTo(session) && pending.source == source && pending.evidence == evidence
                    && pending.candidateId.equals(candidateId) && pending.candidateIdentity == candidateIdentity;
        }

        SshManagedKnownHostsProvider.FixtureCandidateReservation reserveFixtureCandidate(
                SshHostKeyReviewMailbox.SignedFixtureApproval approval, SshNativeSession child)
                throws VaultException {
            requireSession(child);
            if (approval == null) throw new VaultException("SSH_HOST_KEY_REVIEW", "Fixture approval is unavailable");
            SshHostKeyReviewMailbox.SignedFixtureApproval.Claim claim = approval.claim(this, child);
            requireLiveFor(child);
            SshManagedKnownHostsProvider.FixtureCandidateReservation reserved =
                    pending.handoff.provider().reserveProvisionalFixture(pending.source,
                            pending.evidence, pending.candidateId, pending.candidateIdentity, this, child, claim);
            try {
                synchronized (owner.stateLock) {
                    owner.requireSignedNewFixtureState(pending, this);
                    if (pending.reservation != null)
                        throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate was reserved");
                    pending.reservation = reserved;
                }
                approval.requireActive();
                reserved.requireHeldFor(child);
                return reserved;
            } catch (VaultException | RuntimeException | Error failure) {
                reserved.revoke();
                throw failure;
            }
        }

        @Override public void close() {
            synchronized (owner.stateLock) {
                if (closed) return;
                closed = true;
                if (pending.reservation != null) pending.reservation.revoke();
                VaultCodec.wipe(baseDigest); VaultCodec.wipe(replacementDigest); VaultCodec.wipe(sessionDigest);
            }
        }
    }

    /** Signed-only, exact CHANGED capability; cannot be constructed by callers or used for credentials. */
    static final class ChangedAuthorization implements AutoCloseable {
        private final SshNativeCallbackAdapter owner;
        private final PendingChanged pending;
        private final long cycle;
        private final byte[] sessionDigest;
        private boolean closed;

        private ChangedAuthorization(SshNativeCallbackAdapter owner, PendingChanged pending,
                long cycle, byte[] sessionDigest) throws VaultException {
            if (owner == null || pending == null || pending.review == null || cycle <= 0
                    || sessionDigest == null || sessionDigest.length != 32)
                throw new VaultException("SSH_KEX_REQUIRED", "Signed KEX evidence is unavailable");
            int nonzero = 0;
            for (byte value : sessionDigest) nonzero |= value;
            if (nonzero == 0) throw new VaultException("SSH_KEX_REQUIRED", "The KEX session binding is empty");
            this.owner = owner; this.pending = pending; this.cycle = cycle;
            this.sessionDigest = sessionDigest.clone();
        }

        SshHostKeyAuthority.ChangedReview review() { return pending.review; }
        long cycle() { return cycle; }

        byte[] sessionDigest() throws VaultException {
            synchronized (owner.stateLock) {
                if (closed) throw new VaultException("SSH_KEX_REQUIRED", "The KEX binding expired");
                return sessionDigest.clone();
            }
        }

        void requireMatches(SshRoleContext role, SshHostKeyAuthority.Evidence evidence,
                SshTrustProposal proposal) throws VaultException {
            synchronized (owner.stateLock) {
                if (closed || Thread.currentThread() != owner.eventLoopThread || owner.cancelled
                        || owner.closing || owner.closed || !owner.inCallback
                        || owner.pendingChanged != pending || pending.authorization != this
                        || pending.evidence != evidence || pending.proposal != proposal || cycle <= 0)
                    throw new VaultException("SSH_HOST_KEY_CAS", "The signed changed-host authorization expired");
            }
            role.requireActive(owner.clock);
            pending.review.requireMatches(role, evidence, proposal);
        }

        @Override public void close() {
            synchronized (owner.stateLock) { closed = true; VaultCodec.wipe(sessionDigest); }
        }
    }

    private static final class HostKexCompletion {
        final SshHostKeyAuthority.Proof host;
        final SshKexProof kex;
        HostKexCompletion(SshHostKeyAuthority.Proof host, SshKexProof kex) {
            this.host = host; this.kex = kex;
        }
    }

    /** Private capability held by exactly one native session owner. */
    static final class NativeSessionClaim {
        private final SshNativeCallbackAdapter owner;
        private boolean opening;
        private boolean owned;

        private NativeSessionClaim(SshNativeCallbackAdapter owner) {
            this.owner = owner;
            this.opening = true;
        }
    }

    private static final class HostVerification {
        final SshHostKeyAuthority.Evidence evidence;
        final SshHostKeyAuthority.Proof proof;
        final SshTrustProposal.CasResult cas;

        HostVerification(SshHostKeyAuthority.Evidence evidence,
                SshHostKeyAuthority.Proof proof, SshTrustProposal.CasResult cas) {
            this.evidence = evidence;
            this.proof = proof;
            this.cas = cas;
        }

        byte[] encode(long roleToken) throws VaultException {
            if (roleToken <= 0 || cas == null) throw new VaultException("SSH_HOST_KEY_CAS",
                    "The exact host-key result is unavailable");
            byte[] base = cas.baseDigest();
            byte[] replacement = cas.replacementDigest();
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
                DataOutputStream output = new DataOutputStream(bytes);
                output.writeInt(HOST_RESULT_MAGIC);
                output.writeByte(HOST_RESULT_VERSION);
                output.writeByte(HOST_RESULT_APPROVED);
                output.writeShort(0);
                output.writeLong(roleToken);
                output.writeInt(evidence.role.hop);
                output.writeInt(evidence.role.port);
                output.writeInt(classificationTag(evidence.classification));
                output.writeInt(casStatus(cas.status));
                writeField(output, evidence.role.connectionId, false);
                writeField(output, evidence.role.targetIdentity, false);
                writeField(output, evidence.role.host, false);
                writeField(output, evidence.keyType, false);
                writeField(output, evidence.fingerprint, false);
                writeField(output, cas.proposalBinding, true);
                output.write(base);
                output.write(replacement);
                output.flush();
                byte[] result = bytes.toByteArray();
                if (result.length > MAX_HOST_RESULT_BYTES) {
                    VaultCodec.wipe(result);
                    throw new VaultException("SSH_HOST_KEY_CAS",
                            "The exact host-key result is outside its bound");
                }
                return result;
            } catch (IOException | RuntimeException failure) {
                throw new VaultException("SSH_HOST_KEY_CAS",
                        "The exact host-key result is unavailable");
            } finally {
                VaultCodec.wipe(base);
                VaultCodec.wipe(replacement);
            }
        }

        private static void writeField(DataOutputStream output, String value, boolean allowEmpty)
                throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            if ((!allowEmpty && bytes.length == 0) || bytes.length > 0xffff) {
                throw new IOException("field bound");
            }
            output.writeShort(bytes.length);
            output.write(bytes);
            VaultCodec.wipe(bytes);
        }
    }

    private final Object stateLock = new Object();
    private final SshRoleContext role;
    private final SshRoleRegistry registry;
    private final SshNativeRoleBootstrap.Issued issued;
    private SshHostKeyAuthority hostAuthority;
    private SshManagedProvider managedProvider;
    private volatile SshKnownHostsCandidateHandoff candidateHandoff;
    private final SshRoleContext.Clock clock;
    private final NativeBinding nativeBinding;
    private final SshNativeRoleBootstrap.NativeRelease roleRelease;
    private final Thread eventLoopThread;
    private final SshSessionOwner.Cancellation effectCancellation;
    private long nativeCallback;
    private long invocationSerial;
    private boolean inCallback;
    private boolean cancelled;
    private Throwable cancellationInvalidationUnconfirmed;
    private boolean externallyCancelled;
    private boolean closing;
    private boolean closed;
    /* 0: not attempted, 1: outcome unconfirmed, 2: confirmed. */
    private int nativeCloseNewInvalidation;
    private Throwable nativeCloseNewFailure;
    private boolean nativeCloseCleanupComplete;
    private boolean roleReleased;
    private boolean nativeSessionOpening;
    private boolean nativeSessionOwned;
    private NativeSessionClaim establishedClaim;
    private String failureCode;
    private boolean authenticationSetupLocked;
    private boolean selectedNativeClaimIssued;
    private boolean selectedCredentialAttempted;
    private SshAuthenticationSelection authenticationSelection;
    private SshHostKeyAuthority.Proof hostProof;
    private SshKexProof kexProof;
    private PendingChanged pendingChanged;
    private PendingProvisionalFixture pendingProvisionalFixture;
    private boolean changedAwaitingNativeAcknowledgement;
    private boolean newAwaitingNativeAcknowledgement;
    private SshManagedKnownHostsProvider.ChildTrustSnapshot childTrustSource;
    private SshHostKeyAuthority.Evidence childTrustEvidence;
    private SshNativeSession.JumpChildOwner childEffectOwner;
    private boolean childTrustOpened;

    private SshNativeCallbackAdapter(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshManagedProvider managedProvider, SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshKnownHostsCandidateHandoff candidateHandoff,
            SshNativeRoleBootstrap.NativeRelease roleRelease, long nativeCallback,
            SshSessionOwner.Cancellation effectCancellation) {
        this.issued = issued;
        this.registry = registry;
        this.role = issued.role;
        this.hostAuthority = hostAuthority;
        this.managedProvider = managedProvider;
        this.candidateHandoff = candidateHandoff;
        this.clock = clock;
        this.nativeBinding = nativeBinding;
        this.roleRelease = roleRelease;
        this.effectCancellation = effectCancellation;
        eventLoopThread = Thread.currentThread();
        this.nativeCallback = nativeCallback;
    }

    /** Production binding: JNI must be entered from the owned event-loop thread. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock) throws VaultException {
        return bind(issued, registry, hostAuthority, clock,
                new ProductionNativeBinding(), SshNativeRoleBootstrap.productionRelease(), null);
    }

    /** Production binding with an explicitly owned managed provider. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, SshManagedProvider managedProvider) throws VaultException {
        return bind(issued, registry, hostAuthority, clock,
                new ProductionNativeBinding(), SshNativeRoleBootstrap.productionRelease(), managedProvider);
    }

    /** Production binding with the provider's exact one-shot candidate receiver. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, SshManagedProvider managedProvider,
            SshKnownHostsCandidateHandoff candidateHandoff) throws VaultException {
        return bind(issued, registry, hostAuthority, clock, new ProductionNativeBinding(),
                SshNativeRoleBootstrap.productionRelease(), managedProvider, candidateHandoff);
    }

    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, SshManagedProvider managedProvider,
            SshKnownHostsCandidateHandoff candidateHandoff,
            SshSessionOwner.Cancellation effectCancellation) throws VaultException {
        return bind(issued, registry, hostAuthority, clock, new ProductionNativeBinding(),
                SshNativeRoleBootstrap.productionRelease(), managedProvider, candidateHandoff, effectCancellation);
    }

    /** Host/JVM contract seam; it does not weaken the callback state machine. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshNativeRoleBootstrap.NativeRelease roleRelease) throws VaultException {
        return bind(issued, registry, hostAuthority, clock, nativeBinding, roleRelease, null);
    }

    /** Host/JVM seam with an explicit provider; all lifecycle guards remain identical. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshNativeRoleBootstrap.NativeRelease roleRelease, SshManagedProvider managedProvider)
            throws VaultException {
        return bind(issued, registry, hostAuthority, clock, nativeBinding, roleRelease,
                managedProvider, null);
    }

    /** Host/JVM seam with the separate one-shot candidate receiver. */
    static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshNativeRoleBootstrap.NativeRelease roleRelease, SshManagedProvider managedProvider,
            SshKnownHostsCandidateHandoff candidateHandoff) throws VaultException {
        return bind(issued, registry, hostAuthority, clock, nativeBinding, roleRelease,
                managedProvider, candidateHandoff, new SshSessionOwner.Cancellation());
    }

    private static SshNativeCallbackAdapter bind(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshNativeRoleBootstrap.NativeRelease roleRelease, SshManagedProvider managedProvider,
            SshKnownHostsCandidateHandoff candidateHandoff,
            SshSessionOwner.Cancellation effectCancellation) throws VaultException {
        if (issued == null || registry == null || issued.registry != registry || issued.role == null
                || hostAuthority == null || clock == null || nativeBinding == null
                || roleRelease == null || effectCancellation == null || !issued.role.active()
                || candidateHandoff != null && (managedProvider == null
                || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider()))) throw invalid();
        registry.requireOwned(issued.role);
        try {
            issued.role.requireActive(clock);
            SshNativeCallbackAdapter adapter = new SshNativeCallbackAdapter(issued, registry,
                    hostAuthority, managedProvider, clock, nativeBinding, candidateHandoff,
                    roleRelease, 0, effectCancellation);
            long callback = nativeBinding.open(adapter, issued.token, issued.ownerUid,
                    issued.role.deadline);
            if (callback <= 0) throw nativeUnavailable();
            adapter.nativeCallback = callback;
            return adapter;
        } catch (VaultException failure) {
            throw failure;
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw nativeUnavailable();
        }
    }

    /** JNI calls only this lock-free admission while a native effect permit is held. */
    private boolean onNativeEffectAdmission() { return effectCancellation.admitTerminalIo(); }

    void requireLocalEffectAdmission() throws VaultException {
        if (!effectCancellation.admitTerminalIo())
            throw new VaultException("SSH_SESSION_CANCELLED", "The SSH connection was cancelled");
    }

    private Invocation begin() throws Rejected {
        return begin(false);
    }

    private Invocation begin(boolean provisionalFixture) throws Rejected {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) {
                preserveUncertainFailure("SSH_CALLBACK_THREAD");
                throw new Rejected(failureCode);
            }
            if (inCallback) {
                preserveUncertainFailure("SSH_CALLBACK_REENTRANT");
                markCancelled();
                throw new Rejected(failureCode);
            }
            if (closed) {
                preserveUncertainFailure("SSH_CALLBACK_CLOSED");
                throw new Rejected(failureCode);
            }
            if (closing) throw new Rejected("SSH_CALLBACK_CLOSING");
            if (cancelled) throw new Rejected(failureCode == null
                    ? "SSH_CALLBACK_CANCELLED" : failureCode);
            if (establishedClaim != null) throw new Rejected("SSH_CALLBACK_TRANSPORT_ONLY");
            if (!provisionalFixture && pendingProvisionalFixture != null) {
                preserveUncertainFailure("SSH_JUMP_EFFECT_GUARD");
                markCancelled();
                throw new Rejected(failureCode);
            }
            try {
                role.requireActive(clock);
            } catch (VaultException failure) {
                preserveUncertainFailure(failure.code);
                markCancelled();
                throw new Rejected(failure.code);
            } catch (RuntimeException | LinkageError failure) {
                preserveUncertainFailure("SSH_CALLBACK_JAVA_EXCEPTION");
                markCancelled();
                throw new Rejected(failureCode);
            }
            authenticationSetupLocked = true;
            inCallback = true;
            return new Invocation(++invocationSerial);
        }
    }

    private boolean finish(Invocation invocation, String failure, Object value) {
        synchronized (stateLock) {
            if (!inCallback || invocation == null || invocation.serial != invocationSerial) {
                if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = "SSH_CALLBACK_REENTRANT";
                markCancelled();
                inCallback = false;
                return false;
            }
            try {
                if (failure != null) {
                    failureCode = completedFailure(failure);
                    markCancelled();
                    hostProof = null;
                    kexProof = null;
                    return false;
                }
                if ("VAULT_COMMIT_UNCERTAIN".equals(failureCode)) {
                    markCancelled();
                    hostProof = null;
                    kexProof = null;
                    return false;
                }
                if (cancelled || closing || closed) {
                    if (failureCode == null) failureCode = "SSH_CALLBACK_CANCELLED";
                    hostProof = null;
                    kexProof = null;
                    return false;
                }
                try {
                    role.requireActive(clock);
                } catch (VaultException expired) {
                    failureCode = expired.code;
                    markCancelled();
                    hostProof = null;
                    kexProof = null;
                    return false;
                } catch (RuntimeException | LinkageError exception) {
                    failureCode = "SSH_CALLBACK_JAVA_EXCEPTION";
                    markCancelled();
                    hostProof = null;
                    kexProof = null;
                    return false;
                }
                if (cancelled || closing || closed) {
                    if (failureCode == null) failureCode = "SSH_CALLBACK_CANCELLED";
                    hostProof = null;
                    kexProof = null;
                    return false;
                }
                if (value instanceof SshHostKeyAuthority.Proof) {
                    hostProof = (SshHostKeyAuthority.Proof)value;
                    kexProof = null;
                } else if (value instanceof SshKexProof) {
                    kexProof = (SshKexProof)value;
                } else if (value instanceof HostKexCompletion) {
                    HostKexCompletion completion = (HostKexCompletion)value;
                    hostProof = completion.host;
                    kexProof = completion.kex;
                }
                failureCode = null;
                return true;
            } catch (Error error) {
                abortInvocation(invocation);
                throw error;
            } finally {
                inCallback = false;
            }
        }
    }

    /** Called under stateLock: an unconfirmed durable write outranks lifecycle and ordinary failures. */
    private String completedFailure(String failure) {
        if ("VAULT_COMMIT_UNCERTAIN".equals(failureCode)
                || "VAULT_COMMIT_UNCERTAIN".equals(failure)) return "VAULT_COMMIT_UNCERTAIN";
        if (childTrustSource == null) return failure;
        if (externallyCancelled) return "SSH_CALLBACK_CANCELLED";
        if (cancelled && failureCode != null) return failureCode;
        try { role.requireActive(clock); }
        catch (VaultException lifecycle) { return lifecycle.code; }
        catch (RuntimeException | LinkageError unavailable) { return "SSH_CALLBACK_JAVA_EXCEPTION"; }
        return failure;
    }

    private void preserveUncertainFailure(String failure) {
        if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = failure;
    }

    /** stateLock is held; an admitted effect never acquires it after its permit. */
    private void markCancelled() {
        if (cancellationInvalidationUnconfirmed != null)
            throw new IllegalStateException("SSH callback cancellation is unconfirmed",
                    cancellationInvalidationUnconfirmed);
        if (cancelled) return;
        try {
            boolean begun = beginJumpEffectInvalidation();
            try { cancelled = true; }
            finally { endJumpEffectInvalidation(begun); }
        } catch (RuntimeException | Error failure) {
            // A void JNI failure cannot tell us whether its effect fence changed.
            // Keep the exact owner and do not try a second begin or end.
            cancellationInvalidationUnconfirmed = failure;
            closing = true;
            // These Java-only digests have no bearing on handoff ownership or
            // the unconfirmed native fence and can be erased independently.
            discardProvisionalFixture(false);
            throw failure;
        }
    }

    /** An escaping Error must not strand callback ownership and block cleanup. */
    private void abortInvocation(Invocation invocation) {
        synchronized (stateLock) {
            if (invocation == null || !inCallback || invocation.serial != invocationSerial) return;
            if (!cancelled || failureCode == null) failureCode = "SSH_CALLBACK_JAVA_EXCEPTION";
            try { markCancelled(); }
            finally {
                hostProof = null;
                kexProof = null;
                inCallback = false;
            }
        }
    }

    /**
     * Typed native callback.  The request carries the native role token and
     * all hop metadata; the returned wire value carries only bounded metadata,
     * digests, and the exact proposal/CAS binding.  Proposal bodies never
     * cross JNI.
     */
    byte[] onNativeHostEvidence(long nativeRoleToken, int nativeHop, int nativePort,
            String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest) {
        Invocation invocation = null;
        SshTrustProposal managedProposal = null;
        boolean attemptedNew = false;
        boolean finished = false;
        byte[] result = null;
        try {
            invocation = begin();
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            SshHostKeyAuthority.Classification nativeClassification = classification(classification);
            attemptedNew = nativeClassification == SshHostKeyAuthority.Classification.NEW;
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    nativeClassification, keyType, fingerprint, baseDigest, replacementDigest);
            requireChildTrustEvidence(evidence);
            requireChildHostClassification(evidence);
            if (nativeClassification == SshHostKeyAuthority.Classification.NEW) {
                if (managedProvider == null) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW",
                            "An exact unknown host-key review is required");
                }
                managedProposal = managedProvider.proposalFor(evidence);
            }
            HostVerification verification = verifyHost(evidence, managedProposal, true);
            result = verification.encode(issued.token);
            if (!finish(invocation, null, verification.proof)) {
                if (attemptedNew) invalidateUnacknowledgedNew();
                VaultCodec.wipe(result);
                return null;
            }
            finished = true;
            if (attemptedNew && candidateHandoff != null) {
                synchronized (stateLock) {
                    if (cancelled || closing || closed || hostProof != verification.proof)
                        throw new VaultException("SSH_CALLBACK_CANCELLED", "The new-host result was cancelled");
                    newAwaitingNativeAcknowledgement = true;
                }
            }
            return result;
        } catch (Rejected rejected) {
            return null;
        } catch (VaultException failure) {
            VaultCodec.wipe(result);
            failHostEvidence(invocation, finished, attemptedNew, failure.code);
            return null;
        } catch (RuntimeException | LinkageError failure) {
            VaultCodec.wipe(result);
            failHostEvidence(invocation, finished, attemptedNew, "SSH_CALLBACK_JAVA_EXCEPTION");
            return null;
        } catch (Error failure) {
            VaultCodec.wipe(result);
            if (attemptedNew) invalidateUnacknowledgedNew();
            abortInvocation(invocation);
            throw failure;
        } finally {
            if (managedProposal != null) managedProposal.close();
        }
    }

    private void failHostEvidence(Invocation invocation, boolean finished, boolean attemptedNew, String code) {
        VaultException uncertain = attemptedNew ? invalidateUnacknowledgedNew() : null;
        if (uncertain != null) code = uncertain.code;
        if (!finished) {
            finish(invocation, code, null);
            return;
        }
        synchronized (stateLock) {
            markCancelled();
            if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = code;
            hostProof = null;
            kexProof = null;
        }
    }

    /**
     * Dedicated native-to-managed known_hosts body handoff.  This method only
     * acknowledges installation of one managed candidate; it never returns a
     * CSHR record or a COMMITTED result.  The guarded CAS remains on the
     * existing onNativeHostEvidence path.
     */
    boolean onNativeKnownHostsCandidate(long nativeRoleToken, int nativeHop, int nativePort,
            String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest, String candidateId, byte[] replacementBody) {
        Invocation invocation = null;
        try {
            invocation = begin();
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            if (candidateHandoff == null || managedProvider == null
                    || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider())) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The managed known_hosts candidate receiver is unavailable");
            }
            SshHostKeyAuthority.Classification nativeClassification = classification(classification);
            if (nativeClassification != SshHostKeyAuthority.Classification.NEW) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "Only an exact NEW host candidate may be handed off");
            }
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    nativeClassification, keyType, fingerprint, baseDigest, replacementDigest);
            requireChildTrustEvidence(evidence);
            requireChildHostClassification(evidence);
            candidateHandoff.accept(evidence, candidateId, replacementBody);
            if (!finish(invocation, null, null)) {
                candidateHandoff.abort();
                return false;
            }
            return true;
        } catch (Rejected rejected) {
            if (candidateHandoff != null) candidateHandoff.abort();
            return false;
        } catch (VaultException failure) {
            if (candidateHandoff != null) candidateHandoff.abort();
            finish(invocation, failure.code, null);
            return false;
        } catch (RuntimeException | LinkageError failure) {
            if (candidateHandoff != null) candidateHandoff.abort();
            finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
            return false;
        } catch (Error failure) {
            abortCandidateAfterError(invocation, failure, false);
            throw failure;
        } finally {
            /* JNI also clears its array; this covers direct test calls and the
             * begin/rejection paths before the receiver is reached. */
            VaultCodec.wipe(replacementBody);
        }
    }

    /* These three methods are reachable only through an explicitly armed test
     * JNI target. The native scope check is absent from release libraries. */
    private boolean onNativeProvisionalNewCandidateForFixture(long nativeRoleToken, int nativeHop,
            int nativePort, String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest, String candidateId, byte[] replacementBody) {
        Invocation invocation = null;
        SshHostKeyAuthority.Evidence evidence = null;
        SshKnownHostsCandidateHandoff handoff = null;
        boolean attempted = false, installed = false;
        try {
            invocation = begin(true);
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            if (classification != HOST_NEW || !SshHostTrustMaterial.canonicalFingerprint(fingerprint))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate is invalid");
            evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.NEW, keyType, fingerprint, baseDigest, replacementDigest);
            SshManagedKnownHostsProvider.ChildTrustSnapshot source =
                    requireProvisionalFixtureScope(invocation, null, 1, evidence);
            synchronized (stateLock) { handoff = candidateHandoff; }
            attempted = true;
            handoff.accept(evidence, candidateId, replacementBody);
            requireProvisionalFixtureScope(invocation, null, 1, evidence);
            Object candidateIdentity = handoff.provider().requireProvisionalFixtureCandidate(
                    source, evidence, candidateId, null);
            PendingProvisionalFixture pending = new PendingProvisionalFixture(
                    evidence, source, handoff, candidateId, candidateIdentity, invocation.serial);
            synchronized (stateLock) {
                if (cancelled || closing || closed || !inCallback || pendingProvisionalFixture != null
                        || childTrustSource != source || candidateHandoff != handoff || childTrustEvidence != null)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture candidate expired");
                childTrustEvidence = evidence;
                pendingProvisionalFixture = pending;
                installed = true;
            }
            requireProvisionalFixtureScope(invocation, pending, 1, evidence);
            if (!finish(invocation, null, null)) {
                discardProvisionalFixture(true);
                return false;
            }
            return true;
        } catch (Rejected rejected) {
            if (installed) discardProvisionalFixture(true);
            else if (attempted && handoff != null) handoff.abort();
            return false;
        } catch (VaultException failure) {
            if (installed) discardProvisionalFixture(true);
            else if (attempted && handoff != null) handoff.abort();
            return finishChangedFailure(invocation, failure.code);
        } catch (RuntimeException | LinkageError failure) {
            if (installed) discardProvisionalFixture(true);
            else if (attempted && handoff != null) handoff.abort();
            return finishChangedFailure(invocation, "SSH_CALLBACK_JAVA_EXCEPTION");
        } catch (Error failure) {
            try {
                if (installed) discardProvisionalFixture(true);
                else if (attempted && handoff != null) handoff.abort();
            } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            try { abortInvocation(invocation); }
            finally {
                if (!installed && evidence != null) {
                    VaultCodec.wipe(evidence.baseDigest);
                    VaultCodec.wipe(evidence.replacementDigest);
                }
                VaultCodec.wipe(replacementBody);
            }
        }
    }

    private boolean onNativeProvisionalNewReviewForFixture(long nativeRoleToken, int nativeHop,
            int nativePort, String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest) {
        Invocation invocation = null;
        SshHostKeyAuthority.Evidence actual = null;
        try {
            invocation = begin(true);
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            if (classification != HOST_NEW || !SshHostTrustMaterial.canonicalFingerprint(fingerprint))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review is invalid");
            actual = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.NEW, keyType, fingerprint, baseDigest, replacementDigest);
            PendingProvisionalFixture pending;
            synchronized (stateLock) { pending = pendingProvisionalFixture; }
            if (pending == null || !pending.matches(actual))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review changed");
            requireProvisionalFixtureScope(invocation, pending, 2, actual);
            synchronized (stateLock) {
                if (pendingProvisionalFixture != pending || pending.reviewSerial != 0
                        || pending.candidateSerial >= invocation.serial || cancelled || closing || closed)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review expired");
                pending.reviewSerial = invocation.serial;
            }
            if (!finish(invocation, null, null)) { discardProvisionalFixture(true); return false; }
            return true;
        } catch (Rejected rejected) {
            discardProvisionalFixture(true); return false;
        } catch (VaultException failure) {
            discardProvisionalFixture(true); return finishChangedFailure(invocation, failure.code);
        } catch (RuntimeException | LinkageError failure) {
            discardProvisionalFixture(true); return finishChangedFailure(invocation, "SSH_CALLBACK_JAVA_EXCEPTION");
        } catch (Error failure) {
            try { discardProvisionalFixture(true); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            try { abortInvocation(invocation); }
            finally {
                if (actual != null) { VaultCodec.wipe(actual.baseDigest); VaultCodec.wipe(actual.replacementDigest); }
            }
        }
    }

    private boolean onNativeProvisionalNewKexForFixture(long cycle, byte[] sessionDigest) {
        Invocation invocation = null;
        try {
            invocation = begin(true);
            PendingProvisionalFixture pending;
            synchronized (stateLock) { pending = pendingProvisionalFixture; }
            int nonzero = 0;
            if (sessionDigest != null) for (byte value : sessionDigest) nonzero |= value;
            if (pending == null || cycle <= 0 || sessionDigest == null || sessionDigest.length != 32 || nonzero == 0)
                throw new VaultException("SSH_KEX_REQUIRED", "The fixture KEX binding is unavailable");
            requireProvisionalFixtureScope(invocation, pending, 3, pending.evidence);
            synchronized (stateLock) {
                if (pendingProvisionalFixture != pending || pending.reviewSerial <= pending.candidateSerial
                        || pending.reviewSerial >= invocation.serial || pending.cycle != 0
                        || cancelled || closing || closed)
                    throw new VaultException("SSH_KEX_REQUIRED", "The fixture KEX binding expired");
                pending.sessionDigest = sessionDigest.clone();
                pending.cycle = cycle;
            }
            if (!finish(invocation, null, null)) { discardProvisionalFixture(true); return false; }
            return true;
        } catch (Rejected rejected) {
            discardProvisionalFixture(true); return false;
        } catch (VaultException failure) {
            discardProvisionalFixture(true); return finishChangedFailure(invocation, failure.code);
        } catch (RuntimeException | LinkageError failure) {
            discardProvisionalFixture(true); return finishChangedFailure(invocation, "SSH_CALLBACK_JAVA_EXCEPTION");
        } catch (Error failure) {
            try { discardProvisionalFixture(true); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            try { abortInvocation(invocation); }
            finally { VaultCodec.wipe(sessionDigest); }
        }
    }

    private SshManagedKnownHostsProvider.ChildTrustSnapshot requireProvisionalFixtureScope(
            Invocation invocation, PendingProvisionalFixture pending, int stage,
            SshHostKeyAuthority.Evidence evidence) throws VaultException {
        SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        SshKnownHostsCandidateHandoff handoff;
        SshManagedProvider provider;
        long callback;
        synchronized (stateLock) {
            source = childTrustSource; handoff = candidateHandoff; provider = managedProvider;
            callback = nativeCallback;
            if (role.hop != 1 || source == null || handoff == null || provider == null
                    || childEffectOwner == null || !childEffectOwner.ownsCallback(this)
                    || !childTrustOpened || !nativeSessionOwned || callback <= 0
                    || invocation == null || !inCallback || invocation.serial != invocationSerial
                    || cancelled || closing || closed || hostProof != null || kexProof != null
                    || pendingChanged != null || newAwaitingNativeAcknowledgement
                    || changedAwaitingNativeAcknowledgement || pendingProvisionalFixture != pending
                    || (pending == null ? childTrustEvidence != null
                            : pending.source != source || pending.handoff != handoff
                            || childTrustEvidence != pending.evidence))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture scope is unavailable");
        }
        if (!provider.ownsNativeCandidateProvider(handoff.provider())
                || !handoff.provider().ownsChildSnapshot(source)
                || !nativeProvisionalNewFixtureScope(callback, stage))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture scope is not exact");
        // These checks observe candidate identity at two instants; they do not
        // order a trust admission atomically against consumption or revocation.
        if (pending != null) handoff.provider().requireProvisionalFixtureCandidate(
                source, pending.evidence, pending.candidateId, pending.candidateIdentity);
        source.requireEvidence(role, evidence);
        if (!nativeProvisionalNewFixtureScope(callback, stage))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture scope expired");
        if (pending != null) handoff.provider().requireProvisionalFixtureCandidate(
                source, pending.evidence, pending.candidateId, pending.candidateIdentity);
        synchronized (stateLock) {
            if (source != childTrustSource || handoff != candidateHandoff || provider != managedProvider
                    || callback != nativeCallback || pendingProvisionalFixture != pending
                    || !childTrustOpened || !nativeSessionOwned || !inCallback
                    || invocation.serial != invocationSerial || cancelled || closing || closed
                    || childEffectOwner == null || !childEffectOwner.ownsCallback(this))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture scope changed");
        }
        return source;
    }

    /** Test-only callbacks must have returned before a real session may query native readiness. */
    void requireSignedNewFixturePending() throws VaultException {
        if (Thread.currentThread() != eventLoopThread)
            throw new VaultException("SSH_CALLBACK_THREAD", "The fixture review requires its owner thread");
        synchronized (stateLock) {
            requireSignedNewFixtureState(pendingProvisionalFixture, null);
            if (pendingProvisionalFixture.signedReviewIssued)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review was already issued");
        }
    }

    SignedNewFixtureReview claimSignedNewFixtureReview(SshNativeSession.SignedNewFixtureReady observation)
            throws VaultException {
        if (Thread.currentThread() != eventLoopThread || observation == null)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review is unavailable");
        PendingProvisionalFixture pending;
        synchronized (stateLock) { pending = pendingProvisionalFixture; }
        requireSignedNewFixtureLive(pending, observation, null);
        byte[] base = null, replacement = null, session = null;
        SignedNewFixtureReview review = null;
        try {
            long cycle;
            synchronized (stateLock) {
                requireSignedNewFixtureState(pending, null);
                if (pending.signedReviewIssued)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review was already issued");
                cycle = pending.cycle;
                base = pending.evidence.baseDigest();
                replacement = pending.evidence.replacementDigest();
                session = pending.sessionDigest.clone();
            }
            SshManagedKnownHostsProvider.SnapshotReceipt receipt = pending.source.receipt();
            // Randomness, hashing and source access must not delay callback cancellation.
            review = new SignedNewFixtureReview(this, pending, observation, cycle,
                    base, replacement, session, receipt);
            base = replacement = session = null;
            synchronized (stateLock) {
                requireSignedNewFixtureState(pending, null);
                if (pending.signedReviewIssued || !observation.matches(
                        childEffectOwner, pending.source, pending.candidateId))
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review owner changed");
                pending.signedReviewIssued = true;
                pending.signedReview = review;
            }
            review.requireLive();
            return review;
        } catch (VaultException | RuntimeException | Error failure) {
            if (review != null) review.close();
            throw failure;
        } finally {
            VaultCodec.wipe(base); VaultCodec.wipe(replacement); VaultCodec.wipe(session);
        }
    }

    private void requireSignedNewFixtureLive(PendingProvisionalFixture pending,
            SshNativeSession.SignedNewFixtureReady observation, SignedNewFixtureReview review)
            throws VaultException {
        if (pending == null || observation == null)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The fixture review is unavailable");
        synchronized (stateLock) {
            requireSignedNewFixtureState(pending, review);
            if (!observation.matches(childEffectOwner, pending.source, pending.candidateId))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The signed fixture owner changed");
        }
        // Never hold the callback or mailbox monitor while accessing the session or vault.
        observation.requireLive(this);
        pending.source.requireEvidence(role, pending.evidence);
        pending.handoff.provider().requireProvisionalFixtureCandidate(
                pending.source, pending.evidence, pending.candidateId, pending.candidateIdentity);
        role.requireActive(clock);
        long now = clock.now();
        if (now <= 0 || now >= observation.deadline())
            throw new VaultException("SSH_TIMED_OUT", "The fixture review deadline expired");
        synchronized (stateLock) {
            requireSignedNewFixtureState(pending, review);
            if (!observation.matches(childEffectOwner, pending.source, pending.candidateId))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The signed fixture owner changed");
        }
        observation.requireLive(this);
    }

    /** Caller holds stateLock; only metadata is inspected here. */
    private void requireSignedNewFixtureState(PendingProvisionalFixture pending,
            SignedNewFixtureReview review) throws VaultException {
        if (pending == null || pendingProvisionalFixture != pending || inCallback || nativeSessionOpening
                || !nativeSessionOwned || cancelled || closing || closed || childEffectOwner == null
                || !childEffectOwner.ownsCallback(this) || childTrustSource != pending.source
                || !childTrustOpened || candidateHandoff != pending.handoff
                || managedProvider == null || !managedProvider.ownsNativeCandidateProvider(pending.handoff.provider())
                || childTrustEvidence != pending.evidence
                || pending.evidence.role != role || pending.evidence.classification != SshHostKeyAuthority.Classification.NEW
                || pending.reviewSerial <= pending.candidateSerial || pending.cycle != 1
                || pending.sessionDigest == null || pending.sessionDigest.length != 32
                || hostProof != null || kexProof != null || pendingChanged != null
                || newAwaitingNativeAcknowledgement || changedAwaitingNativeAcknowledgement)
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The signed fixture state is unavailable");
        if (review != null && (pending.signedReview != review || review.closed
                || review.pending != pending || review.owner != this || review.observation == null
                || !review.observation.matches(childEffectOwner, pending.source, pending.candidateId)
                || review.cycle != pending.cycle
                || !MessageDigest.isEqual(review.sessionDigest, pending.sessionDigest)
                || !MessageDigest.isEqual(review.baseDigest, pending.evidence.baseDigest)
                || !MessageDigest.isEqual(review.replacementDigest, pending.evidence.replacementDigest)))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The signed fixture review changed");
    }

    private void discardProvisionalFixture(boolean abortHandoff) {
        PendingProvisionalFixture pending;
        synchronized (stateLock) {
            pending = pendingProvisionalFixture;
            pendingProvisionalFixture = null;
            if (pending != null) {
                if (childTrustEvidence == pending.evidence) childTrustEvidence = null;
                pending.close();
            }
        }
        if (abortHandoff && pending != null) pending.handoff.abort();
    }

    /** A CHANGED candidate is held without host proof until the same key has completed signed KEX. */
    boolean onNativeChangedHostCandidate(long nativeRoleToken, int nativeHop, int nativePort,
            String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest, String candidateId, byte[] replacementBody, byte[] oldRecords) {
        Invocation invocation = null;
        try {
            invocation = begin();
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            if (candidateHandoff == null || managedProvider == null
                    || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider())
                    || classification != HOST_CHANGED || !SshHostTrustMaterial.canonicalFingerprint(fingerprint))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The changed-host candidate is unavailable");
            synchronized (stateLock) {
                if (pendingChanged != null || hostProof != null || kexProof != null)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "A host decision already exists");
            }
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.CHANGED, keyType, fingerprint, baseDigest, replacementDigest);
            requireChildTrustEvidence(evidence);
            requireChildHostClassification(evidence);
            java.util.List<SshHostTrustMaterial.Record> rows = SshHostTrustMaterial.changedRecords(oldRecords, baseDigest);
            candidateHandoff.acceptChanged(evidence, candidateId, replacementBody);
            synchronized (stateLock) { pendingChanged = new PendingChanged(evidence, rows); }
            if (!finish(invocation, null, null)) { abortChangedCandidate(); return false; }
            return true;
        } catch (Rejected rejected) {
            abortChangedCandidate(); return false;
        } catch (VaultException failure) {
            abortChangedCandidate(); return finishChangedFailure(invocation, failure.code);
        } catch (IOException failure) {
            abortChangedCandidate(); return finishChangedFailure(invocation, "SSH_HOST_TRUST_FAILED");
        } catch (RuntimeException | LinkageError failure) {
            abortChangedCandidate(); return finishChangedFailure(invocation, "SSH_CALLBACK_JAVA_EXCEPTION");
        } catch (Error failure) {
            abortChangedCandidate(); abortInvocation(invocation); throw failure;
        } finally {
            VaultCodec.wipe(replacementBody); VaultCodec.wipe(oldRecords);
        }
    }

    /** The returned boolean authorizes only one provisional native key pin, not a trust write. */
    boolean onNativeChangedHostReview(long nativeRoleToken, int nativeHop, int nativePort,
            String nativeConnectionId, String nativeTargetIdentity, String nativeHost,
            int classification, String keyType, String fingerprint, byte[] baseDigest,
            byte[] replacementDigest) {
        Invocation invocation = null;
        try {
            invocation = begin();
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            if (classification != HOST_CHANGED || candidateHandoff == null || managedProvider == null
                    || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider()))
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The changed-host review is unavailable");
            PendingChanged pending;
            synchronized (stateLock) { pending = pendingChanged; }
            SshHostKeyAuthority.Evidence actual = SshHostKeyAuthority.Evidence.fromNative(role,
                    SshHostKeyAuthority.Classification.CHANGED, keyType, fingerprint, baseDigest, replacementDigest);
            requireChildTrustEvidence(actual);
            requireChildHostClassification(actual);
            if (pending == null || pending.proposal != null || !pending.matches(actual))
                throw new VaultException(SshHostKeyAuthority.PROOF_INVALID, "The changed-host review binding changed");
            pending.proposal = candidateHandoff.provider().changedProposalFor(pending.evidence);
            pending.review = hostAuthority.requireChangedApproval(role, pending.evidence,
                    pending.proposal, pending.oldRecords);
            if (!finish(invocation, null, null)) { abortChangedCandidate(); return false; }
            return true;
        } catch (Rejected rejected) {
            abortChangedCandidate(); return false;
        } catch (VaultException failure) {
            abortChangedCandidate(); return finishChangedFailure(invocation, failure.code);
        } catch (RuntimeException | LinkageError failure) {
            abortChangedCandidate(); return finishChangedFailure(invocation, "SSH_CALLBACK_JAVA_EXCEPTION");
        } catch (Error failure) {
            abortChangedCandidate(); abortInvocation(invocation); throw failure;
        }
    }

    private void abortChangedCandidate() {
        PendingChanged previous;
        synchronized (stateLock) { previous = pendingChanged; pendingChanged = null; }
        if (previous != null) previous.close();
        if (candidateHandoff != null) candidateHandoff.abort();
    }

    private void abortCandidateAfterError(Invocation invocation, Error failure, boolean invalidateNew) {
        if (invalidateNew) {
            try { invalidateUnacknowledgedNew(); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        }
        try { if (candidateHandoff != null) candidateHandoff.abort(); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        finally { abortInvocation(invocation); }
    }

    /** Cancellation or expired role wins over an ordinary changed-review failure, never over uncertainty. */
    private boolean finishChangedFailure(Invocation invocation, String code) {
        synchronized (stateLock) {
            if (!"VAULT_COMMIT_UNCERTAIN".equals(code)
                    && !"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) {
                if (externallyCancelled) code = "SSH_CALLBACK_CANCELLED";
                else {
                    try { role.requireActive(clock); }
                    catch (VaultException lifecycle) { code = lifecycle.code; }
                    catch (RuntimeException | LinkageError failure) { code = "SSH_CALLBACK_JAVA_EXCEPTION"; }
                }
            }
            return finish(invocation, code, null);
        }
    }

    /** Native rollback hook for a candidate accepted before a late state check. */
    void abortNativeKnownHostsCandidate() {
        synchronized (stateLock) {
            if (cancellationInvalidationUnconfirmed != null)
                throw new IllegalStateException("SSH callback cancellation is unconfirmed",
                        cancellationInvalidationUnconfirmed);
        }
        invalidateUnacknowledgedNew();
        invalidateUnacknowledgedChanged();
        discardProvisionalFixture(true);
        abortChangedCandidate();
        synchronized (stateLock) {
            // Cancellation may have become unconfirmed while the handoff's
            // independent cleanup ran without the callback monitor.
            if (cancellationInvalidationUnconfirmed != null)
                throw new IllegalStateException("SSH callback cancellation is unconfirmed",
                        cancellationInvalidationUnconfirmed);
        }
    }

    /** Called only by the native callback glue; no proposal travels over JNI. */
    boolean onNativeHostKey(int classification, String keyType, String fingerprint,
            byte[] baseDigest, byte[] replacementDigest) {
        return onNativeHostKey(classification, keyType, fingerprint, baseDigest, replacementDigest, null);
    }

    /* Test-only exact proposal path. Production JNI supplies proposals only
     * through the managed provider seam. */
    boolean onNativeHostKeyForTest(int classification, String keyType, String fingerprint,
            byte[] baseDigest, byte[] replacementDigest, SshTrustProposal proposal) {
        return onNativeHostKey(classification, keyType, fingerprint, baseDigest, replacementDigest, proposal);
    }

    private boolean onNativeHostKey(int classification, String keyType, String fingerprint,
            byte[] baseDigest, byte[] replacementDigest, SshTrustProposal proposal) {
        Invocation invocation = null;
        SshTrustProposal managedProposal = proposal;
        boolean attemptedNew = false;
        try {
            invocation = begin();
            SshHostKeyAuthority.Classification nativeClassification = classification(classification);
            attemptedNew = nativeClassification == SshHostKeyAuthority.Classification.NEW;
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    nativeClassification, keyType, fingerprint, baseDigest, replacementDigest);
            requireChildTrustEvidence(evidence);
            requireChildHostClassification(evidence);
            if (managedProposal == null && nativeClassification == SshHostKeyAuthority.Classification.NEW
                    && managedProvider != null) {
                managedProposal = managedProvider.proposalFor(evidence);
            }
            HostVerification verification = verifyHost(evidence, managedProposal,
                    managedProvider != null);
            return finish(invocation, null, verification.proof);
        } catch (Rejected rejected) {
            return false;
        } catch (VaultException failure) {
            return finish(invocation, failure.code, null);
        } catch (RuntimeException | LinkageError failure) {
            return finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
        } catch (Error failure) {
            if (attemptedNew) abortCandidateAfterError(invocation, failure, true);
            else abortInvocation(invocation);
            throw failure;
        } finally {
            if (managedProposal != null && managedProposal != proposal) managedProposal.close();
        }
    }

    private HostVerification verifyHost(SshHostKeyAuthority.Evidence evidence,
            SshTrustProposal proposal, boolean casRequired) throws VaultException {
        SshHostKeyAuthority.Proof proof = hostAuthority.requireVerified(role, evidence, proposal);
        SshTrustProposal.CasResult cas = null;
        if (evidence.classification == SshHostKeyAuthority.Classification.NEW && casRequired) {
            if (managedProvider == null) {
                throw new VaultException("SSH_HOST_KEY_CAS",
                        "The exact host-key write-back is unavailable");
            }
            cas = managedProvider.writeBack(evidence, proof, proposal);
        } else if (evidence.classification != SshHostKeyAuthority.Classification.NEW) {
            cas = SshTrustProposal.CasResult.notRequired(evidence);
        }
        return new HostVerification(evidence, proof, cas);
    }

    private void requireNativeBinding(long nativeRoleToken, int nativeHop, int nativePort,
            String nativeConnectionId, String nativeTargetIdentity, String nativeHost)
            throws VaultException {
        if (nativeRoleToken <= 0 || nativeHop < 0 || nativeHop >= SshRoleContext.MAX_HOPS
                || nativePort < 1 || nativePort > 65535
                || !identifier(nativeConnectionId, 128)
                || !text(nativeTargetIdentity, 512) || !host(nativeHost)) {
            throw new VaultException("SSH_HOST_KEY_INVALID", "The native host binding is invalid");
        }
        if (nativeRoleToken != issued.token || nativeHop != role.hop || nativePort != role.port
                || !nativeConnectionId.equals(role.connectionId)
                || !nativeTargetIdentity.equals(role.targetIdentity)
                || !nativeHost.equals(role.host)) {
            throw new VaultException(SshHostKeyAuthority.PROOF_INVALID,
                    "The native host evidence is bound to another hop");
        }
    }

    /** A fresh native view must come from this provider's exact same-read receipt. */
    void bindChildTrustSnapshot(SshManagedKnownHostsProvider.ChildTrustSnapshot source, String path)
            throws VaultException {
        if (source == null || candidateHandoff == null || managedProvider == null
                || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider())
                || !candidateHandoff.provider().ownsChildSnapshot(source))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust provider is not exact");
        source.requireFor(role, path);
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread || closed || closing || cancelled || inCallback
                    || nativeSessionOpening || nativeSessionOwned || hostProof != null || kexProof != null
                    || pendingChanged != null
                    || childTrustOpened || childTrustSource != null && childTrustSource != source)
                throw new VaultException("SSH_CALLBACK_SESSION", "The child trust binding is unavailable");
            childTrustSource = source;
        }
        source.requireCurrent();
    }

    void requireChildTrustSnapshot(SshManagedKnownHostsProvider.ChildTrustSnapshot source, String path)
            throws VaultException {
        if (source == null || candidateHandoff == null || managedProvider == null
                || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider())
                || !candidateHandoff.provider().ownsChildSnapshot(source))
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust provider is not exact");
        source.requireFor(role, path);
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread || closed || closing || cancelled
                    || childTrustOpened || childTrustSource != source)
                throw new VaultException("SSH_CALLBACK_SESSION", "The child trust binding is unavailable");
        }
    }

    void activateChildTrustSnapshot(SshManagedKnownHostsProvider.ChildTrustSnapshot source, String path)
            throws VaultException {
        source.requireFor(role, path);
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread || closed || closing || cancelled
                    || inCallback || !nativeSessionOwned || childTrustOpened || childTrustSource != source)
                throw new VaultException("SSH_CALLBACK_SESSION", "The child trust binding is unavailable");
            childTrustOpened = true;
        }
        source.requireCurrent();
    }

    void requireNoChildTrustSnapshot() throws VaultException {
        synchronized (stateLock) {
            if (childTrustSource != null)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust source must be consumed");
        }
    }

    private void requireChildTrustEvidence(SshHostKeyAuthority.Evidence evidence) throws VaultException {
        SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        synchronized (stateLock) {
            source = childTrustSource;
            if (source == null) return;
            if (!childTrustOpened || !inCallback || cancelled || closing || closed)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust source is unavailable");
        }
        source.requireEvidence(role, evidence);
        synchronized (stateLock) {
            if (source != childTrustSource || !childTrustOpened || !inCallback || cancelled || closing || closed)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust source changed");
            childTrustEvidence = evidence;
        }
    }

    /** Until a real ancestor-effect guard exists, child authentication and trust writes stay fenced. */
    boolean hasChildTrustSource() {
        synchronized (stateLock) { return childTrustSource != null; }
    }

    void bindChildEffectOwner(SshNativeSession.JumpChildOwner owner) throws VaultException {
        synchronized (stateLock) {
            if (owner == null || !owner.ownsCallback(this) || childTrustSource == null
                    || childEffectOwner != null || inCallback || cancelled || closing || closed)
                throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect owner is unavailable");
            childEffectOwner = owner;
        }
    }

    void unbindChildEffectOwner(SshNativeSession.JumpChildOwner owner) {
        synchronized (stateLock) { if (childEffectOwner == owner) childEffectOwner = null; }
    }

    /* An unadopted owner must settle its exact provider before dropping its
     * source and the effect fence. Never hold stateLock across that cleanup. */
    void closeUnadoptedChildTrust(SshManagedKnownHostsProvider.ChildTrustSnapshot source)
            throws VaultException {
        SshKnownHostsCandidateHandoff handoff;
        synchronized (stateLock) {
            if (source == null || childTrustSource != source || candidateHandoff == null
                    || nativeSessionOpening || nativeSessionOwned || inCallback)
                throw new VaultException("SSH_SESSION_CLEANUP", "The child trust cleanup is unconfirmed");
            handoff = candidateHandoff;
        }
        handoff.close();
    }

    void requireSelectedSavedChild(SshNativeSession.JumpChildOwner owner) throws VaultException {
        synchronized (stateLock) {
            if ("VAULT_COMMIT_UNCERTAIN".equals(failureCode))
                throw new VaultException(failureCode, "The trust commit result is uncertain");
            if (owner == null || childEffectOwner != owner || childTrustSource == null || !childTrustOpened
                    || childTrustEvidence == null
                    || childTrustEvidence.classification != SshHostKeyAuthority.Classification.KNOWN
                    || hostProof == null || kexProof == null || changedAwaitingNativeAcknowledgement
                    || newAwaitingNativeAcknowledgement
                    || cancelled || closing || closed || managedProvider == null || candidateHandoff == null
                    || !managedProvider.usesSavedPassword(role, authenticationSelection, candidateHandoff.provider()))
                throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The selected saved password is unavailable");
            role.requireActive(clock);
            hostProof.requireMatches(role);
            kexProof.requireMatches(role, hostProof);
        }
    }

    void requirePlainSavedPasswordSelection() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (managedProvider == null || candidateHandoff == null || hostProof == null || kexProof == null
                    || !managedProvider.usesSavedPassword(role, authenticationSelection, candidateHandoff.provider()))
                throw SshAuthenticationSelection.invalid();
            hostProof.requireMatches(role);
            kexProof.requireMatches(role, hostProof);
        }
    }

    void requireChildEffectGuard() throws VaultException {
        synchronized (stateLock) {
            if (childTrustSource == null) return;
        }
        // Do not hold the callback lock while checking the role the first time.
        // Recheck both under the lock before choosing the denial code.
        role.requireActive(clock);
        synchronized (stateLock) {
            if ("VAULT_COMMIT_UNCERTAIN".equals(failureCode))
                throw new VaultException(failureCode, "The trust commit result is uncertain");
            if (cancelled || closing || closed) throw new VaultException(
                    externallyCancelled || failureCode == null ? "SSH_CALLBACK_CANCELLED" : failureCode,
                    "The SSH callback binding is no longer active");
            role.requireActive(clock);
            throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump effect guard is unavailable");
        }
    }

    private void requireChildHostClassification(SshHostKeyAuthority.Evidence evidence) throws VaultException {
        if (evidence != null && (evidence.classification == SshHostKeyAuthority.Classification.NEW
                || evidence.classification == SshHostKeyAuthority.Classification.CHANGED))
            requireChildEffectGuard();
    }

    /** Called only after native observed the signed KEX and inbound NEWKEYS. */
    boolean onNativeKexComplete(long cycle, byte[] sessionDigest) {
        Invocation invocation = null;
        PendingChanged changed = null;
        SshManagedKnownHostsProvider changedProvider = null;
        boolean finished = false;
        try {
            invocation = begin();
            SshHostKeyAuthority.Proof proof;
            synchronized (stateLock) { proof = hostProof; changed = pendingChanged; }
            SshHostKeyAuthority.Evidence sourceEvidence;
            SshManagedKnownHostsProvider.ChildTrustSnapshot source;
            synchronized (stateLock) { sourceEvidence = childTrustEvidence; source = childTrustSource; }
            if (source != null && (sourceEvidence == null
                    || sourceEvidence.classification != SshHostKeyAuthority.Classification.NEW)) {
                if (sourceEvidence == null)
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The child trust evidence is unavailable");
                requireChildTrustEvidence(sourceEvidence);
            }
            if (changed != null) {
                requireChildEffectGuard();
                if (proof != null || changed.review == null || changed.proposal == null
                        || changed.authorization != null || candidateHandoff == null || managedProvider == null
                        || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider()))
                    throw new VaultException("SSH_HOST_KEY_CAS", "The changed-host authorization is unavailable");
                changedProvider = candidateHandoff.provider();
                ChangedAuthorization authorization = new ChangedAuthorization(this, changed, cycle, sessionDigest);
                synchronized (stateLock) { changed.authorization = authorization; }
                authorization.requireMatches(role, changed.evidence, changed.proposal);
                SshManagedKnownHostsProvider.ChangedCommitReceipt receipt = changedProvider.writeChanged(
                        changed.evidence, authorization, changed.proposal);
                SshTrustProposal.CasResult cas = SshTrustProposal.CasResult.changedCommitted(
                        changed.evidence, changed.proposal, receipt);
                if (!cas.matchesChanged(changed.evidence, changed.proposal))
                    throw new VaultException("SSH_HOST_KEY_CAS", "The changed-host commit receipt changed");
                proof = hostAuthority.committedChanged(role, changed.evidence, changed.proposal, receipt);
                byte[] frozenDigest = authorization.sessionDigest();
                SshKexProof result;
                try { result = SshKexProof.fromNative(role, proof, authorization.cycle(), frozenDigest); }
                finally { VaultCodec.wipe(frozenDigest); }
                boolean accepted = finish(invocation, null, new HostKexCompletion(proof, result));
                finished = true;
                if (!accepted) {
                    failChangedCompletion(null, true, "SSH_CALLBACK_CANCELLED", changedProvider);
                    abortChangedCandidate();
                    return false;
                }
                synchronized (stateLock) {
                    if (cancelled || closing || closed || pendingChanged != changed
                            || hostProof != proof || kexProof != result)
                        throw new VaultException("SSH_CALLBACK_CANCELLED", "The changed-host result was cancelled");
                    changedAwaitingNativeAcknowledgement = true;
                    pendingChanged = null;
                }
                changed.close();
                return true;
            }
            if (proof == null) throw new VaultException("SSH_KEX_REQUIRED",
                    "Host-key proof is unavailable");
            SshKexProof result = SshKexProof.fromNative(role, proof, cycle, sessionDigest);
            boolean accepted = finish(invocation, null, result);
            if (!accepted) invalidateUnacknowledgedNew();
            return accepted;
        } catch (Rejected rejected) {
            if (changed != null) abortChangedCandidate();
            else invalidateUnacknowledgedNew();
            return false;
        } catch (VaultException failure) {
            if (changed != null) {
                boolean result = failChangedCompletion(invocation, finished, failure.code, changedProvider);
                abortChangedCandidate();
                return result;
            }
            VaultException uncertain = invalidateUnacknowledgedNew();
            return finish(invocation, uncertain == null ? failure.code : uncertain.code, null);
        } catch (RuntimeException | LinkageError failure) {
            if (changed != null) {
                boolean result = failChangedCompletion(invocation, finished, "SSH_CALLBACK_JAVA_EXCEPTION", changedProvider);
                abortChangedCandidate();
                return result;
            }
            VaultException uncertain = invalidateUnacknowledgedNew();
            return finish(invocation, uncertain == null ? "SSH_CALLBACK_JAVA_EXCEPTION" : uncertain.code, null);
        } catch (Error failure) {
            if (changed != null) {
                failChangedCompletion(invocation, finished, "SSH_CALLBACK_JAVA_EXCEPTION", changedProvider);
                abortChangedCandidate();
            } else {
                invalidateUnacknowledgedNew();
                abortInvocation(invocation);
            }
            throw failure;
        }
    }

    private boolean failChangedCompletion(Invocation invocation, boolean finished, String code,
            SshManagedKnownHostsProvider provider) {
        VaultException uncertain = provider == null ? null : provider.invalidateCommittedResult();
        if (uncertain != null) code = uncertain.code;
        if (!finished) return finishChangedFailure(invocation, code);
        synchronized (stateLock) {
            markCancelled();
            if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = code;
            hostProof = null;
            kexProof = null;
        }
        return false;
    }

    /** Called by the owner only after the native step itself returned complete successfully. */
    void confirmChangedNativeCompletion() throws VaultException {
        try {
            synchronized (stateLock) {
                if (!changedAwaitingNativeAcknowledgement) return;
                if (Thread.currentThread() != eventLoopThread || cancelled || closing || closed
                        || hostProof == null || kexProof == null || candidateHandoff == null)
                    throw new VaultException("VAULT_COMMIT_UNCERTAIN", "The changed-host result was not confirmed");
                SshManagedKnownHostsProvider provider = candidateHandoff.provider();
                role.publishBound(clock, provider::changedProofPublished);
                changedAwaitingNativeAcknowledgement = false;
            }
        } catch (VaultException failure) {
            VaultException uncertain = invalidateUnacknowledgedChanged();
            throw uncertain == null ? failure : uncertain;
        } catch (RuntimeException | LinkageError failure) {
            VaultException uncertain = invalidateUnacknowledgedChanged();
            if (uncertain != null) throw uncertain;
            throw failure;
        }
    }

    /** NEW remains pending until the owning native KEX step itself completed. */
    void confirmNewNativeCompletion() throws VaultException {
        try {
            synchronized (stateLock) {
                if (!newAwaitingNativeAcknowledgement) return;
                if (Thread.currentThread() != eventLoopThread || cancelled || closing || closed
                        || hostProof == null || kexProof == null || candidateHandoff == null)
                    throw new VaultException("VAULT_COMMIT_UNCERTAIN", "The new-host result was not confirmed");
                SshManagedKnownHostsProvider provider = candidateHandoff.provider();
                role.publishBound(clock, provider::newProofPublished);
                newAwaitingNativeAcknowledgement = false;
            }
        } catch (VaultException failure) {
            VaultException uncertain = invalidateUnacknowledgedNew();
            throw uncertain == null ? failure : uncertain;
        } catch (RuntimeException | LinkageError failure) {
            VaultException uncertain = invalidateUnacknowledgedNew();
            if (uncertain != null) throw uncertain;
            throw failure;
        }
    }

    private VaultException invalidateUnacknowledgedNew() {
        SshKnownHostsCandidateHandoff handoff = candidateHandoff;
        VaultException uncertain = handoff == null ? null : handoff.provider().invalidateUnacknowledgedNew();
        if (uncertain == null) return null;
        synchronized (stateLock) {
            markCancelled();
            failureCode = uncertain.code;
            hostProof = null;
            kexProof = null;
        }
        return uncertain;
    }

    private VaultException invalidateUnacknowledgedChanged() {
        SshKnownHostsCandidateHandoff handoff = candidateHandoff;
        VaultException uncertain = handoff == null ? null : handoff.provider().invalidateUnacknowledgedChanged();
        if (uncertain == null) return null;
        synchronized (stateLock) {
            markCancelled();
            failureCode = uncertain.code;
            hostProof = null;
            kexProof = null;
        }
        return uncertain;
    }

    /** The legacy boolean callback remains a separate availability contract. */
    boolean onNativeCredential(int kind) {
        Invocation invocation = null;
        try {
            invocation = begin();
            requireChildEffectGuard();
            if (!validCredentialKind(kind)) {
                throw new VaultException("PARAMETERS", "The SSH credential kind is invalid");
            }
            synchronized (stateLock) {
                if (kexProof == null || changedAwaitingNativeAcknowledgement || newAwaitingNativeAcknowledgement) throw new VaultException("SSH_KEX_REQUIRED",
                        "Signed KEX and NEWKEYS evidence is unavailable");
            }
            throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                    "The production SSH credential provider is unavailable");
        } catch (Rejected rejected) {
            return false;
        } catch (VaultException failure) {
            return finish(invocation, failure.code, null);
        } catch (RuntimeException | LinkageError failure) {
            return finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
        } catch (Error failure) {
            abortInvocation(invocation);
            throw failure;
        }
    }

    /** Freezes metadata on the owner before any callback or native session claim. */
    void selectAuthentication(SshAuthenticationSelection selection) throws VaultException {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) {
                throw new VaultException("SSH_CALLBACK_THREAD",
                        "SSH authentication selection must run on the event-loop thread");
            }
            if (closed) throw new VaultException("SSH_CALLBACK_CLOSED", "The SSH callback binding is closed");
            if (closing) throw new VaultException("SSH_CALLBACK_CLOSING", "The SSH callback binding is closing");
            if (cancelled) throw new VaultException("VAULT_COMMIT_UNCERTAIN".equals(failureCode)
                    ? failureCode : "SSH_CALLBACK_CANCELLED", "The SSH callback binding is cancelled");
            if (selection == null || authenticationSelection != null || authenticationSetupLocked
                    || inCallback || nativeSessionOpening || nativeSessionOwned || managedProvider == null) {
                throw SshAuthenticationSelection.invalid();
            }
            selection.requireMatches(role, selection.kind, clock);
            authenticationSelection = selection;
        }
    }

    /**
     * Synchronous bounded credential handoff for the native event-loop
     * callback.  JNI copies the returned array into its caller-owned buffer
     * and wipes the managed array before dropping its local reference.
     */
    byte[] onNativeCredentialBytes(int kind) {
        return credentialBytes(kind, null, false, false, null, 0, null);
    }

    byte[] onNativeCredentialBytes(int kind, SshAuthenticationSelection selection) {
        return credentialBytes(kind, selection, true, false, null, 0, null);
    }

    /** Only the native selected route carries the exact retained CSAS/KEX binding. */
    byte[] onNativeSelectedCredentialBytes(int kind, byte[] receipt, long cycle, byte[] digest) {
        return credentialBytes(kind, null, true, true, receipt, cycle, digest);
    }

    private byte[] credentialBytes(int kind, SshAuthenticationSelection selection, boolean selectedCall,
            boolean nativeSelectedCall, byte[] receipt, long cycle, byte[] digest) {
        Invocation invocation = null;
        byte[] result = null;
        byte[] receiptCopy = null;
        byte[] digestCopy = null;
        byte[] expectedDigest = null;
        byte[] wire = null;
        byte[] expectedReceipt = null;
        boolean handedOff = false;
        try {
            invocation = begin();
            SshNativeSession.JumpChildOwner protectedOwner = null;
            synchronized (stateLock) {
                if (childTrustSource != null) protectedOwner = childEffectOwner;
            }
            if (hasChildTrustSource()) {
                if (protectedOwner == null || !nativeSelectedCall || kind != CREDENTIAL_PASSWORD)
                    requireChildEffectGuard();
                protectedOwner.requireProtectedEffect();
                if (!nativeCallbackAncestorProofsLive(nativeCallbackToken()))
                    throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump callback proof is unavailable");
            }
            SshAuthenticationSelection frozen;
            synchronized (stateLock) {
                frozen = authenticationSelection;
                if (nativeSelectedCall ? !nativeSessionOwned || !selectedNativeClaimIssued
                        : selectedCall && selectedNativeClaimIssued) {
                    throw SshAuthenticationSelection.invalid();
                }
            }
            if (selectedCall || frozen != null) {
                if (!selectedCall || frozen == null || !nativeSelectedCall && selection != frozen
                        || (kind != CREDENTIAL_PASSWORD && kind != CREDENTIAL_PRIVATE_KEY)) {
                    throw SshAuthenticationSelection.invalid();
                }
            }
            if (nativeSelectedCall) {
                if (receipt == null || receipt.length != 32 || digest == null || digest.length != 32
                        || cycle <= 0) throw SshAuthenticationSelection.invalid();
                receiptCopy = receipt.clone();
                digestCopy = digest.clone();
                wire = frozen.encode(issued.token, clock);
                try {
                    expectedReceipt = MessageDigest.getInstance("SHA-256").digest(wire);
                } catch (NoSuchAlgorithmException unavailable) {
                    throw SshAuthenticationSelection.invalid();
                }
                if (!MessageDigest.isEqual(receiptCopy, expectedReceipt)) {
                    throw SshAuthenticationSelection.invalid();
                }
            }
            SshCredentialBridge.Kind typedKind = credentialKind(kind);
            if (frozen != null) frozen.requireMatches(role, typedKind, clock);
            SshHostKeyAuthority.Proof proof;
            SshKexProof signedKex;
            synchronized (stateLock) {
                if (changedAwaitingNativeAcknowledgement || newAwaitingNativeAcknowledgement) throw new VaultException("SSH_KEX_REQUIRED",
                        "The changed-host KEX has not completed natively");
                proof = hostProof;
                signedKex = kexProof;
            }
            if (proof == null || signedKex == null) {
                throw new VaultException("SSH_KEX_REQUIRED",
                        "Signed KEX and NEWKEYS evidence is unavailable");
            }
            proof.requireMatches(role);
            signedKex.requireMatches(role, proof);
            if (nativeSelectedCall) {
                expectedDigest = signedKex.sessionIdDigest();
                if (signedKex.cycle != cycle || !MessageDigest.isEqual(expectedDigest, digestCopy)) {
                    throw new VaultException("SSH_KEX_REQUIRED", "The native KEX binding changed");
                }
            }
            if (managedProvider == null) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential provider is unavailable");
            }
            final Invocation capturedInvocation = invocation;
            final boolean protectedChild = protectedOwner != null;
            final AppSecrets.EffectGuard selectedGuard = frozen == null ? null
                    : new AppSecrets.EffectGuard() {
                        @Override public void check() throws VaultException {
                            requireSelectedInvocation(capturedInvocation, frozen, proof, signedKex, nativeSelectedCall);
                        }
                        @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                            synchronized (stateLock) { check(); publication.check(); }
                        }
                    };
            final AppSecrets.EffectGuard effectGuard = selectedGuard == null ? null : protectedChild
                    ? new AppSecrets.SshSecretCopyGuard() {
                        @Override public void check() throws VaultException { selectedGuard.check(); }
                        @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                            selectedGuard.publish(publication);
                        }
                        @Override public void copySecret(AppSecrets.EffectGuard receiver) throws VaultException {
                            if (!nativeSelectedEffectCopyBegin())
                                throw new VaultException("SSH_JUMP_EFFECT_GUARD", "The jump credential effect was revoked");
                            try { receiver.check(); }
                            finally { nativeSelectedEffectCopyEnd(); }
                        }
                    } : new AppSecrets.SshStopCopyGuard() {
                        @Override public void check() throws VaultException { selectedGuard.check(); }
                        @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                            selectedGuard.publish(publication);
                        }
                        @Override public void copySecret(AppSecrets.EffectGuard receiver) throws VaultException {
                            if (!effectCancellation.admitTerminalIo())
                                throw new VaultException("SSH_CALLBACK_CANCELLED", "The SSH callback was cancelled");
                            receiver.check();
                        }
                    };
            if (effectGuard != null) {
                effectGuard.check();
                synchronized (stateLock) {
                    if (selectedCredentialAttempted) throw SshAuthenticationSelection.invalid();
                    selectedCredentialAttempted = true;
                }
            }
            result = frozen == null
                    ? managedProvider.credentialBytes(role, proof, signedKex, typedKind)
                    : managedProvider.credentialBytes(role, proof, signedKex, typedKind, frozen, effectGuard);
            if (result == null || result.length == 0 || result.length > MAX_CREDENTIAL_BYTES) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential is outside its bounded handoff");
            }
            if (effectGuard != null) effectGuard.check();
            if (!finish(invocation, null, result)) {
                return null;
            }
            handedOff = true;
            return result;
        } catch (Rejected rejected) {
            return null;
        } catch (VaultException failure) {
            finish(invocation, failure.code, null);
            return null;
        } catch (RuntimeException | LinkageError failure) {
            finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
            return null;
        } finally {
            if (!handedOff) {
                VaultCodec.wipe(result);
                abortInvocation(invocation);
            }
            VaultCodec.wipe(receiptCopy);
            VaultCodec.wipe(digestCopy);
            VaultCodec.wipe(expectedDigest);
            VaultCodec.wipe(expectedReceipt);
            VaultCodec.wipe(wire);
        }
    }

    private void requireSelectedInvocation(Invocation invocation, SshAuthenticationSelection selection,
            SshHostKeyAuthority.Proof proof, SshKexProof signedKex, boolean nativeSelectedCall)
            throws VaultException {
        synchronized (stateLock) {
            role.requireActive(clock);
            selection.requireMatches(role, selection.kind, clock);
            proof.requireMatches(role);
            signedKex.requireMatches(role, proof);
            if (Thread.currentThread() != eventLoopThread || !inCallback || invocation == null
                    || invocation.serial != invocationSerial || authenticationSelection != selection
                    || hostProof != proof || kexProof != signedKex || changedAwaitingNativeAcknowledgement
                    || newAwaitingNativeAcknowledgement
                    || nativeSelectedCall && (!nativeSessionOwned || !selectedNativeClaimIssued
                    || nativeCallback <= 0)
                    || !nativeSelectedCall && selectedNativeClaimIssued) {
                throw SshAuthenticationSelection.invalid();
            }
            if (cancelled || closing || closed) throw new VaultException(
                    failureCode == null ? "SSH_CALLBACK_CANCELLED" : failureCode,
                    "The SSH callback binding is no longer active");
        }
    }

    void cancel() throws VaultException {
        synchronized (stateLock) {
            if (cancellationInvalidationUnconfirmed != null)
                throw unconfirmedCallbackCleanup(cancellationInvalidationUnconfirmed);
            externallyCancelled = true;
            if (closed || closing) return;
            markCancelled();
            if (failureCode == null) failureCode = "SSH_CALLBACK_CANCELLED";
        }
        invalidateUnacknowledgedNew();
        invalidateUnacknowledgedChanged();
        discardProvisionalFixture(true);
        abortChangedCandidate();
        try {
            nativeBinding.cancel(nativeCallback);
        } catch (IOException | LinkageError | RuntimeException failure) {
            String code;
            synchronized (stateLock) {
                if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = "SSH_CALLBACK_NATIVE";
                code = failureCode;
            }
            throw new VaultException(code, "The SSH callback cancellation failed");
        }
    }

    @Override public void close() throws VaultException {
        closeNative();
        releaseRoleAfterNativeClient();
    }

    NativeSessionClaim beginNativeSessionOpen() throws VaultException {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) {
                throw new VaultException("SSH_CALLBACK_THREAD",
                        "SSH session ownership must begin on the callback event-loop thread");
            }
            if (closed || closing || cancelled) {
                throw new VaultException("SSH_CALLBACK_CLOSING",
                        "The SSH callback binding is not available for a session");
            }
            if (nativeSessionOpening || nativeSessionOwned) {
                throw new VaultException("SSH_CALLBACK_SESSION",
                        "The SSH callback binding already has a native session");
            }
            role.requireActive(clock);
            authenticationSetupLocked = true;
            if (authenticationSelection != null) selectedNativeClaimIssued = true;
            nativeSessionOpening = true;
            return new NativeSessionClaim(this);
        }
    }

    void finishNativeSessionOpen(NativeSessionClaim claim, boolean owned) {
        synchronized (stateLock) {
            if (claim == null || claim.owner != this || !claim.opening
                    || !nativeSessionOpening) return;
            claim.opening = false;
            nativeSessionOpening = false;
            if (owned) {
                claim.owned = true;
                nativeSessionOwned = true;
            }
        }
    }

    /**
     * Records that the owning native SSH client already closed this callback.
     * The native client performs callback release as part of its own close
     * order, so this method must never call Native.close a second time.
     */
    void nativeClientClosed() throws VaultException {
        markNativeClientClosed();
    }

    void nativeClientClosed(NativeSessionClaim claim) throws VaultException {
        synchronized (stateLock) {
            if (claim == null || claim.owner != this || !claim.owned || !nativeSessionOwned) {
                throw new VaultException("SSH_CALLBACK_SESSION",
                        "The native session does not own this callback binding");
            }
        }
        markNativeClientClosed();
        synchronized (stateLock) {
            claim.owned = false;
            nativeSessionOwned = false;
        }
    }

    private void markNativeClientClosed() throws VaultException {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) {
                preserveUncertainFailure("SSH_CALLBACK_THREAD");
                throw new VaultException(failureCode,
                        "SSH callback cleanup must run on its event-loop thread");
            }
            if (inCallback) {
                preserveUncertainFailure("SSH_CALLBACK_REENTRANT");
                throw new VaultException(failureCode,
                        "SSH callback cleanup cannot re-enter a callback");
            }
            if (cancellationInvalidationUnconfirmed != null)
                throw unconfirmedCallbackCleanup(cancellationInvalidationUnconfirmed);
            if (!closed) {
                closing = false;
                markCancelled();
                closed = true;
                hostProof = null;
                kexProof = null;
                nativeCallback = 0;
                if (failureCode == null) failureCode = "SSH_CALLBACK_CLOSING";
            }
            if (nativeCloseCleanupComplete) return;
        }
        try {
            invalidateNewForNativeClose();
            discardProvisionalFixture(true);
            if (candidateHandoff != null) candidateHandoff.close();
        } catch (RuntimeException | LinkageError failure) {
            throw unconfirmedCallbackCleanup(failure);
        }
        synchronized (stateLock) { nativeCloseCleanupComplete = true; }
    }

    private static VaultException unconfirmedCallbackCleanup(Throwable cause) {
        VaultException result = new VaultException("SSH_CALLBACK_CLEANUP", "The SSH callback cleanup is unconfirmed");
        if (cause != null) result.initCause(cause);
        return result;
    }

    private void invalidateNewForNativeClose() throws VaultException {
        synchronized (stateLock) {
            if (nativeCloseNewInvalidation == 2) return;
            if (nativeCloseNewInvalidation != 0) throw unconfirmedCallbackCleanup(nativeCloseNewFailure);
            nativeCloseNewInvalidation = 1;
        }
        try { invalidateUnacknowledgedNew(); }
        catch (RuntimeException | Error failure) {
            synchronized (stateLock) { nativeCloseNewFailure = failure; }
            throw failure;
        }
        synchronized (stateLock) { nativeCloseNewInvalidation = 2; }
    }

    /** Native role release is retried independently after native client close. */
    void releaseRoleAfterNativeClient() throws VaultException {
        synchronized (stateLock) {
            if (roleReleased) return;
            if (!closed || !nativeCloseCleanupComplete || nativeSessionOpening || nativeSessionOwned)
                throw new VaultException("SSH_CALLBACK_CLEANUP",
                    "The native SSH client has not closed its callback");
            if (Thread.currentThread() != eventLoopThread) {
                preserveUncertainFailure("SSH_CALLBACK_THREAD");
                throw new VaultException(failureCode,
                        "SSH callback cleanup must run on its event-loop thread");
            }
        }
        try {
            SshNativeRoleBootstrap.release(issued, registry, roleRelease);
        } catch (VaultException failure) {
            throw failure;
        }
        synchronized (stateLock) { roleReleased = true; }
    }

    private void closeNative() throws VaultException {
        boolean alreadyClosed;
        synchronized (stateLock) {
            alreadyClosed = closed;
            if (Thread.currentThread() != eventLoopThread) {
                preserveUncertainFailure("SSH_CALLBACK_THREAD");
                throw new VaultException(failureCode, "SSH callback cleanup must run on its event-loop thread");
            }
            if (inCallback) {
                preserveUncertainFailure("SSH_CALLBACK_REENTRANT");
                throw new VaultException(failureCode, "SSH callback cleanup cannot re-enter a callback");
            }
            if (cancellationInvalidationUnconfirmed != null)
                throw unconfirmedCallbackCleanup(cancellationInvalidationUnconfirmed);
            if (!alreadyClosed && (nativeSessionOpening || nativeSessionOwned)) {
                preserveUncertainFailure("SSH_CALLBACK_SESSION");
                throw new VaultException(failureCode,
                        "The native SSH session owns this callback binding");
            }
            if (!alreadyClosed) {
                closing = true;
                markCancelled();
                if (failureCode == null) failureCode = "SSH_CALLBACK_CLOSING";
            }
        }
        if (alreadyClosed) { markNativeClientClosed(); return; }
        /* Closing is a cancellation boundary even when native cleanup needs a
         * retry; no later caller may install a managed candidate. */
        try {
            invalidateNewForNativeClose();
            discardProvisionalFixture(true);
            if (candidateHandoff != null) candidateHandoff.abort();
        } catch (RuntimeException | LinkageError failure) {
            throw unconfirmedCallbackCleanup(failure);
        }
        try {
            nativeBinding.close(nativeCallback);
        } catch (IOException | LinkageError | RuntimeException failure) {
            synchronized (stateLock) {
                closing = false;
                if (!"VAULT_COMMIT_UNCERTAIN".equals(failureCode)) failureCode = "SSH_CALLBACK_CLEANUP";
            }
            throw new VaultException("SSH_CALLBACK_CLEANUP",
                    "The SSH callback cleanup is unconfirmed");
        }
        nativeClientClosed();
    }

    String lastFailureCode() { synchronized (stateLock) { return failureCode; } }
    boolean externallyCancelled() { synchronized (stateLock) { return externallyCancelled; } }

    /** Exposes only an internal proof failure still bound to this live, uncancelled role. */
    boolean reportableHostProofFailure() {
        synchronized (stateLock) {
            if (externallyCancelled || !cancelled || closing || closed || inCallback
                    || !SshHostKeyAuthority.PROOF_INVALID.equals(failureCode)) return false;
            try { role.requireActive(clock); return true; }
            catch (VaultException | RuntimeException | LinkageError unavailable) { return false; }
        }
    }

    /** Only the exact rejected child evidence is reportable; this grants no trust authority. */
    boolean reportableJumpTrustRefusal() {
        final SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        final SshHostKeyAuthority.Evidence evidence;
        synchronized (stateLock) {
            source = childTrustSource;
            evidence = childTrustEvidence;
            if (!jumpTrustRefusalState(source, evidence)) return false;
        }
        // The native rollback closes the candidate receipt; the source keeps
        // its original role/view/epoch only for this metadata-only diagnosis.
        // Never acquire the vault/session locks while holding stateLock.
        try { source.requireRefusalContext(role); }
        catch (VaultException | RuntimeException | LinkageError unavailable) { return false; }
        synchronized (stateLock) {
            if (!jumpTrustRefusalState(source, evidence) || !source.refusalContextAvailable()) return false;
            try { role.requireActive(clock); return true; }
            catch (VaultException | RuntimeException | LinkageError unavailable) { return false; }
        }
    }

    /** Called only with stateLock; evidence was admitted through requireChildTrustEvidence. */
    private boolean jumpTrustRefusalState(SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshHostKeyAuthority.Evidence evidence) {
        return source != null && childTrustSource == source && childTrustOpened && nativeSessionOwned
                && evidence != null && childTrustEvidence == evidence && evidence.matches(role)
                && (evidence.classification == SshHostKeyAuthority.Classification.NEW
                    || evidence.classification == SshHostKeyAuthority.Classification.CHANGED)
                && cancelled && !externallyCancelled && !closing && !closed && !inCallback
                && "SSH_JUMP_EFFECT_GUARD".equals(failureCode);
    }

    /** Only this fixed policy denial is exposed; cancellation and invalid role/view still win. */
    boolean changedPolicyRejected() {
        synchronized (stateLock) {
            if (externallyCancelled || !cancelled || !"SSH_HOST_CHANGED_POLICY".equals(failureCode)) return false;
            try { role.requireActive(clock); return true; }
            catch (VaultException | RuntimeException | LinkageError unavailable) { return false; }
        }
    }
    SshHostKeyAuthority.Proof hostProof() { synchronized (stateLock) { return hostProof; } }
    SshKexProof kexProof() { synchronized (stateLock) { return kexProof; } }
    Thread eventLoopThread() { return eventLoopThread; }
    long nativeCallbackToken() {
        synchronized (stateLock) { return nativeCallback; }
    }
    long roleToken() { return issued.token; }
    boolean ownsIssued(SshNativeRoleBootstrap.Issued candidate) { return issued == candidate; }
    int roleHop() { return role.hop; }

    /** The exact provider remains associated after its confirmed one-shot write closes it. */
    boolean ownsTrustProvider(SshManagedKnownHostsProvider provider) {
        synchronized (stateLock) {
            return !closed && !closing && !cancelled && provider != null && candidateHandoff != null
                    && candidateHandoff.provider() == provider && managedProvider != null
                    && managedProvider.ownsNativeCandidateProvider(provider);
        }
    }

    void requireKeyboardContinuation() throws VaultException {
        keyboardContinuationProof();
    }

    /** Low byte is original CSAS kind; bit 8 selects the v4 automatic receipt. */
    int keyboardContinuationProof() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null) throw SshAuthenticationSelection.invalid();
            int original = authenticationSelection.kind == SshCredentialBridge.Kind.PASSWORD ? 1
                    : authenticationSelection.kind == SshCredentialBridge.Kind.PRIVATE_KEY ? 4
                    : authenticationSelection.kind == SshCredentialBridge.Kind.KEYBOARD_INTERACTIVE ? 3 : 0;
            if (authenticationSelection.automaticDiscovery && (original == 3 || original == 4)
                    && (authenticationSelection.automaticMethods & 2) != 0) return 0x100 | original;
            if (authenticationSelection.continueKeyboardInteractive && (original == 1 || original == 4)) return original;
            throw SshAuthenticationSelection.invalid();
        }
    }

    boolean authenticationSelectionAllows(int method, boolean discovered) throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null || method <= 0 || (method & (method - 1)) != 0 || method > 4)
                throw SshAuthenticationSelection.invalid();
            return !authenticationSelection.automaticDiscovery
                    || discovered && (authenticationSelection.automaticMethods & method) != 0;
        }
    }

    void requireDiscoveredKeyboardSelection() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null || !authenticationSelection.automaticDiscovery
                    || authenticationSelection.kind != SshCredentialBridge.Kind.PRIVATE_KEY
                    || (authenticationSelection.automaticMethods & 2) == 0)
                throw SshAuthenticationSelection.invalid();
        }
    }

    int requireDiscoveredPasswordSelection() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null || !authenticationSelection.automaticDiscovery
                    || (authenticationSelection.automaticMethods & 4) == 0)
                throw SshAuthenticationSelection.invalid();
            if (authenticationSelection.kind == SshCredentialBridge.Kind.PRIVATE_KEY) return 4;
            if (authenticationSelection.kind == SshCredentialBridge.Kind.KEYBOARD_INTERACTIVE) return 3;
            throw SshAuthenticationSelection.invalid();
        }
    }

    void requireAutomaticSelection() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null || !authenticationSelection.automaticDiscovery)
                throw SshAuthenticationSelection.invalid();
        }
    }

    void requireAutomaticKeySelection() throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (authenticationSelection == null || !authenticationSelection.automaticDiscovery
                    || authenticationSelection.kind != SshCredentialBridge.Kind.PRIVATE_KEY
                    || (authenticationSelection.automaticMethods & 1) == 0)
                throw SshAuthenticationSelection.invalid();
        }
    }

    /** Only this adapter may export its frozen plan; session callers supply no wire. */
    byte[] authenticationSelectionWire() throws VaultException {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) throw new VaultException("SSH_CALLBACK_THREAD",
                    "SSH selection export must run on the event-loop thread");
            if (closed) throw new VaultException("SSH_CALLBACK_CLOSED", "The SSH callback binding is closed");
            if (closing) throw new VaultException("SSH_CALLBACK_CLOSING", "The SSH callback binding is closing");
            if (cancelled) throw new VaultException("SSH_CALLBACK_CANCELLED", "The SSH callback binding is cancelled");
            if (inCallback || nativeSessionOpening || nativeSessionOwned) {
                throw new VaultException("SSH_CALLBACK_SESSION", "The SSH callback binding is already in use");
            }
            role.requireActive(clock);
            if (authenticationSelection == null) return null;
            authenticationSelection.requireMatches(role, authenticationSelection.kind, clock);
            return authenticationSelection.encode(issued.token, clock);
        }
    }
    int ownerUid() { return issued.ownerUid; }
    long deadline() { return role.deadline; }
    String host() { return role.host; }
    boolean ownsPublicView(SshRoleContext.PublicView candidate) {
        synchronized (stateLock) { return role.ownsPublicView(candidate); }
    }

    void requireNativeSessionLive() throws VaultException {
        synchronized (stateLock) {
            if (closed) throw new VaultException("SSH_CALLBACK_CLOSED",
                    "The SSH callback binding is closed");
            role.requireActive(clock);
        }
    }

    /** Metadata reads additionally reject adapter cancellation without changing legacy step guards. */
    void requireAuthenticationSelectionLive() throws VaultException {
        synchronized (stateLock) {
            if (closed) throw new VaultException("SSH_CALLBACK_CLOSED", "The SSH callback binding is closed");
            if (closing) throw new VaultException("SSH_CALLBACK_CLOSING", "The SSH callback binding is closing");
            if (cancelled) throw new VaultException("VAULT_COMMIT_UNCERTAIN".equals(failureCode)
                    ? failureCode : "SSH_CALLBACK_CANCELLED", "The SSH callback binding is cancelled");
            if (establishedClaim != null)
                throw new VaultException("SSH_CALLBACK_TRANSPORT_ONLY", "Authentication has finished");
            role.requireActive(clock);
        }
    }

    /** The displayed prompt must describe this callback's actual frozen selection. */
    void requireAuthenticationSelection(SshAuthenticationSelection selection) throws VaultException {
        synchronized (stateLock) {
            requireAuthenticationSelectionLive();
            if (selection == null || authenticationSelection != selection)
                throw SshAuthenticationSelection.invalid();
            selection.requireMatches(role, selection.kind, clock);
        }
    }

    /** Called only after the owning native client accepted fresh SUCCESS-to-shell promotion. */
    void retainEstablishedSession(NativeSessionClaim claim) throws VaultException {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread || claim == null || claim.owner != this
                    || !claim.owned || !nativeSessionOwned || nativeSessionOpening || inCallback
                    || establishedClaim != null || authenticationSelection == null)
                throw new VaultException("SSH_CALLBACK_SESSION", "The SSH session does not own this callback");
            requireEstablishedState(claim, false);
            role.retainEstablishedTransport();
            establishedClaim = claim;
            // Native now owns only the exact transport claim; never retain a vault/Activity provider.
            hostAuthority = null;
            managedProvider = null;
            candidateHandoff = null;
            authenticationSelection = null;
            hostProof = null;
            kexProof = null;
        }
    }

    /** Revocation-only metadata check; it grants no native entry or renewed secret/file rights. */
    void requireEstablishedSessionBound(NativeSessionClaim claim) throws VaultException {
        synchronized (stateLock) {
            if (establishedClaim != claim)
                throw new VaultException("SSH_CALLBACK_SESSION", "The SSH session was not established");
            requireEstablishedState(claim, true);
        }
    }

    private void requireEstablishedState(NativeSessionClaim claim, boolean established) throws VaultException {
        if (closed) throw new VaultException("SSH_CALLBACK_CLOSED", "The SSH callback binding is closed");
        if (closing) throw new VaultException("SSH_CALLBACK_CLOSING", "The SSH callback binding is closing");
        if (cancelled) throw new VaultException("SSH_CALLBACK_CANCELLED", "The SSH callback binding is cancelled");
        if (claim == null || claim.owner != this || !claim.owned || !nativeSessionOwned)
            throw new VaultException("SSH_CALLBACK_SESSION", "The SSH session does not own this callback");
        if (established) role.requireEstablishedTransport();
        else role.requireBound();
    }

    private static SshHostKeyAuthority.Classification classification(int value) throws VaultException {
        switch (value) {
            case HOST_KNOWN: return SshHostKeyAuthority.Classification.KNOWN;
            case HOST_NEW: return SshHostKeyAuthority.Classification.NEW;
            case HOST_CHANGED: return SshHostKeyAuthority.Classification.CHANGED;
            case HOST_REVOKED: return SshHostKeyAuthority.Classification.REVOKED;
            case HOST_CERTIFICATE: return SshHostKeyAuthority.Classification.CERTIFICATE;
            case HOST_INVALID: return SshHostKeyAuthority.Classification.INVALID;
            default: throw new VaultException("SSH_HOST_KEY_INVALID", "The native host-key classification is invalid");
        }
    }

    private static int classificationTag(SshHostKeyAuthority.Classification value) {
        switch (value) {
            case KNOWN: return HOST_KNOWN;
            case NEW: return HOST_NEW;
            case CHANGED: return HOST_CHANGED;
            case REVOKED: return HOST_REVOKED;
            case CERTIFICATE: return HOST_CERTIFICATE;
            case INVALID:
            default: return HOST_INVALID;
        }
    }

    private static int casStatus(SshTrustProposal.CasStatus value) throws VaultException {
        if (value == SshTrustProposal.CasStatus.NOT_REQUIRED) return 0;
        if (value == SshTrustProposal.CasStatus.COMMITTED) return 1;
        throw new VaultException("SSH_HOST_KEY_CAS", "The exact host-key write-back failed");
    }

    private static boolean identifier(String value, int maximum) {
        return value != null && value.length() > 0 && value.length() <= maximum
                && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static boolean text(String value, int maximum) {
        return value != null && value.length() > 0 && value.length() <= maximum
                && value.matches("[A-Za-z0-9_:.@+\\-/]{1,512}");
    }

    private static boolean host(String value) {
        return value != null && value.matches("[A-Za-z0-9.:%\\[\\]\\-]{1,253}");
    }

    private static boolean validCredentialKind(int value) {
        return value >= CREDENTIAL_PASSWORD && value <= CREDENTIAL_PRIVATE_KEY;
    }

    private static SshCredentialBridge.Kind credentialKind(int value) throws VaultException {
        switch (value) {
            case CREDENTIAL_PASSWORD: return SshCredentialBridge.Kind.PASSWORD;
            case CREDENTIAL_KEY_PASSPHRASE: return SshCredentialBridge.Kind.KEY_PASSPHRASE;
            case CREDENTIAL_KEYBOARD_INTERACTIVE: return SshCredentialBridge.Kind.KEYBOARD_INTERACTIVE;
            case CREDENTIAL_PRIVATE_KEY: return SshCredentialBridge.Kind.PRIVATE_KEY;
            default: throw new VaultException("PARAMETERS", "The SSH credential kind is invalid");
        }
    }

    private static VaultException invalid() {
        return new VaultException("SSH_CALLBACK_REQUIRED", "The SSH callback binding is invalid");
    }

    private static VaultException nativeUnavailable() {
        return new VaultException("SSH_CALLBACK_NATIVE", "The SSH callback transport is unavailable");
    }
}
