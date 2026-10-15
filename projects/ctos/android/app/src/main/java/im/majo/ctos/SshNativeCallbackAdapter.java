package im.majo.ctos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Synchronous callback boundary for a future same-process native SSH event
 * loop.  A binding is created on the event-loop thread and every callback is
 * rejected unless it returns to that exact thread while its role and deadline
 * are still live.  This class is a contract seam, not an OpenSSH event loop or
 * the concrete managed config/known-hosts file and trust-CAS provider.
 */
final class SshNativeCallbackAdapter implements AutoCloseable {
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
    }

    private static final class Rejected extends Exception {
        final String code;
        Rejected(String code) { this.code = code; }
    }

    private static final class Invocation {
        final long serial;
        Invocation(long serial) { this.serial = serial; }
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
    private final SshHostKeyAuthority hostAuthority;
    private final SshManagedProvider managedProvider;
    private final SshKnownHostsCandidateHandoff candidateHandoff;
    private final SshRoleContext.Clock clock;
    private final NativeBinding nativeBinding;
    private final SshNativeRoleBootstrap.NativeRelease roleRelease;
    private final Thread eventLoopThread;
    private long nativeCallback;
    private long invocationSerial;
    private boolean inCallback;
    private boolean cancelled;
    private boolean closing;
    private boolean closed;
    private boolean roleReleased;
    private boolean nativeSessionOpening;
    private boolean nativeSessionOwned;
    private String failureCode;
    private SshHostKeyAuthority.Proof hostProof;
    private SshKexProof kexProof;

    private SshNativeCallbackAdapter(SshNativeRoleBootstrap.Issued issued,
            SshRoleRegistry registry, SshHostKeyAuthority hostAuthority,
            SshManagedProvider managedProvider, SshRoleContext.Clock clock, NativeBinding nativeBinding,
            SshKnownHostsCandidateHandoff candidateHandoff,
            SshNativeRoleBootstrap.NativeRelease roleRelease, long nativeCallback) {
        this.issued = issued;
        this.registry = registry;
        this.role = issued.role;
        this.hostAuthority = hostAuthority;
        this.managedProvider = managedProvider;
        this.candidateHandoff = candidateHandoff;
        this.clock = clock;
        this.nativeBinding = nativeBinding;
        this.roleRelease = roleRelease;
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
        if (issued == null || registry == null || issued.registry != registry || issued.role == null
                || hostAuthority == null || clock == null || nativeBinding == null
                || roleRelease == null || !issued.role.active()
                || candidateHandoff != null && (managedProvider == null
                || !managedProvider.ownsNativeCandidateProvider(candidateHandoff.provider()))) throw invalid();
        registry.requireOwned(issued.role);
        try {
            issued.role.requireActive(clock);
            SshNativeCallbackAdapter adapter = new SshNativeCallbackAdapter(issued, registry,
                    hostAuthority, managedProvider, clock, nativeBinding, candidateHandoff,
                    roleRelease, 0);
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

    private Invocation begin() throws Rejected {
        synchronized (stateLock) {
            if (Thread.currentThread() != eventLoopThread) {
                failureCode = "SSH_CALLBACK_THREAD";
                throw new Rejected(failureCode);
            }
            if (inCallback) {
                failureCode = "SSH_CALLBACK_REENTRANT";
                cancelled = true;
                throw new Rejected(failureCode);
            }
            if (closed) {
                failureCode = "SSH_CALLBACK_CLOSED";
                throw new Rejected(failureCode);
            }
            if (closing) throw new Rejected("SSH_CALLBACK_CLOSING");
            if (cancelled) throw new Rejected(failureCode == null
                    ? "SSH_CALLBACK_CANCELLED" : failureCode);
            try {
                role.requireActive(clock);
            } catch (VaultException failure) {
                failureCode = failure.code;
                cancelled = true;
                throw new Rejected(failure.code);
            } catch (RuntimeException | LinkageError failure) {
                failureCode = "SSH_CALLBACK_JAVA_EXCEPTION";
                cancelled = true;
                throw new Rejected(failureCode);
            }
            inCallback = true;
            return new Invocation(++invocationSerial);
        }
    }

    private boolean finish(Invocation invocation, String failure, Object value) {
        synchronized (stateLock) {
            if (!inCallback || invocation == null || invocation.serial != invocationSerial) {
                failureCode = "SSH_CALLBACK_REENTRANT";
                cancelled = true;
                inCallback = false;
                return false;
            }
            inCallback = false;
            if (failure != null) {
                failureCode = failure;
                cancelled = true;
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
                cancelled = true;
                hostProof = null;
                kexProof = null;
                return false;
            } catch (RuntimeException | LinkageError exception) {
                failureCode = "SSH_CALLBACK_JAVA_EXCEPTION";
                cancelled = true;
                hostProof = null;
                kexProof = null;
                return false;
            }
            if (value instanceof SshHostKeyAuthority.Proof) {
                hostProof = (SshHostKeyAuthority.Proof)value;
                kexProof = null;
            } else if (value instanceof SshKexProof) {
                kexProof = (SshKexProof)value;
            }
            failureCode = null;
            return true;
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
        try {
            invocation = begin();
            requireNativeBinding(nativeRoleToken, nativeHop, nativePort, nativeConnectionId,
                    nativeTargetIdentity, nativeHost);
            SshHostKeyAuthority.Classification nativeClassification = classification(classification);
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    nativeClassification, keyType, fingerprint, baseDigest, replacementDigest);
            if (nativeClassification == SshHostKeyAuthority.Classification.NEW) {
                if (managedProvider == null) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW",
                            "An exact unknown host-key review is required");
                }
                managedProposal = managedProvider.proposalFor(evidence);
            }
            HostVerification verification = verifyHost(evidence, managedProposal, true);
            byte[] result = verification.encode(issued.token);
            if (!finish(invocation, null, verification.proof)) {
                VaultCodec.wipe(result);
                return null;
            }
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
            if (managedProposal != null) managedProposal.close();
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
        } finally {
            /* JNI also clears its array; this covers direct test calls and the
             * begin/rejection paths before the receiver is reached. */
            VaultCodec.wipe(replacementBody);
        }
    }

    /** Native rollback hook for a candidate accepted before a late state check. */
    void abortNativeKnownHostsCandidate() {
        if (candidateHandoff != null) candidateHandoff.abort();
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
        try {
            invocation = begin();
            SshHostKeyAuthority.Classification nativeClassification = classification(classification);
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(role,
                    nativeClassification, keyType, fingerprint, baseDigest, replacementDigest);
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
            throw new VaultException("SSH_HOST_KEY_CHANGED",
                    "The native host evidence is bound to another hop");
        }
    }

    /** Called only after native observed the signed KEX and inbound NEWKEYS. */
    boolean onNativeKexComplete(long cycle, byte[] sessionDigest) {
        Invocation invocation = null;
        try {
            invocation = begin();
            SshHostKeyAuthority.Proof proof;
            synchronized (stateLock) { proof = hostProof; }
            if (proof == null) throw new VaultException("SSH_KEX_REQUIRED",
                    "Host-key proof is unavailable");
            SshKexProof result = SshKexProof.fromNative(role, proof, cycle, sessionDigest);
            return finish(invocation, null, result);
        } catch (Rejected rejected) {
            return false;
        } catch (VaultException failure) {
            return finish(invocation, failure.code, null);
        } catch (RuntimeException | LinkageError failure) {
            return finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
        }
    }

    /** The legacy boolean callback remains a separate availability contract. */
    boolean onNativeCredential(int kind) {
        Invocation invocation = null;
        try {
            invocation = begin();
            if (!validCredentialKind(kind)) {
                throw new VaultException("PARAMETERS", "The SSH credential kind is invalid");
            }
            synchronized (stateLock) {
                if (kexProof == null) throw new VaultException("SSH_KEX_REQUIRED",
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
        }
    }

    /**
     * Synchronous bounded credential handoff for the native event-loop
     * callback.  JNI copies the returned array into its caller-owned buffer
     * and wipes the managed array before dropping its local reference.
     */
    byte[] onNativeCredentialBytes(int kind) {
        Invocation invocation = null;
        byte[] result = null;
        try {
            invocation = begin();
            SshCredentialBridge.Kind typedKind = credentialKind(kind);
            SshHostKeyAuthority.Proof proof;
            SshKexProof signedKex;
            synchronized (stateLock) {
                proof = hostProof;
                signedKex = kexProof;
            }
            if (proof == null || signedKex == null) {
                throw new VaultException("SSH_KEX_REQUIRED",
                        "Signed KEX and NEWKEYS evidence is unavailable");
            }
            proof.requireMatches(role);
            signedKex.requireMatches(role, proof);
            if (managedProvider == null) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential provider is unavailable");
            }
            result = managedProvider.credentialBytes(role, proof, signedKex, typedKind);
            if (result == null || result.length == 0 || result.length > MAX_CREDENTIAL_BYTES) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential is outside its bounded handoff");
            }
            if (!finish(invocation, null, result)) {
                VaultCodec.wipe(result);
                return null;
            }
            return result;
        } catch (Rejected rejected) {
            if (result != null) VaultCodec.wipe(result);
            return null;
        } catch (VaultException failure) {
            if (result != null) VaultCodec.wipe(result);
            finish(invocation, failure.code, null);
            return null;
        } catch (RuntimeException | LinkageError failure) {
            if (result != null) VaultCodec.wipe(result);
            finish(invocation, "SSH_CALLBACK_JAVA_EXCEPTION", null);
            return null;
        }
    }

    void cancel() throws VaultException {
        synchronized (stateLock) {
            if (closed || closing) return;
            cancelled = true;
            if (failureCode == null) failureCode = "SSH_CALLBACK_CANCELLED";
        }
        if (candidateHandoff != null) candidateHandoff.abort();
        try {
            nativeBinding.cancel(nativeCallback);
        } catch (IOException | LinkageError | RuntimeException failure) {
            synchronized (stateLock) { failureCode = "SSH_CALLBACK_NATIVE"; }
            throw new VaultException("SSH_CALLBACK_NATIVE", "The SSH callback cancellation failed");
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
                failureCode = "SSH_CALLBACK_THREAD";
                throw new VaultException(failureCode,
                        "SSH callback cleanup must run on its event-loop thread");
            }
            if (inCallback) {
                failureCode = "SSH_CALLBACK_REENTRANT";
                throw new VaultException(failureCode,
                        "SSH callback cleanup cannot re-enter a callback");
            }
            if (closed) return;
            closing = false;
            cancelled = true;
            closed = true;
            hostProof = null;
            kexProof = null;
            nativeCallback = 0;
            if (failureCode == null) failureCode = "SSH_CALLBACK_CLOSING";
        }
        if (candidateHandoff != null) candidateHandoff.close();
    }

    /** Native role release is retried independently after native client close. */
    void releaseRoleAfterNativeClient() throws VaultException {
        synchronized (stateLock) {
            if (roleReleased) return;
            if (!closed || nativeSessionOpening || nativeSessionOwned) throw new VaultException("SSH_CALLBACK_CLEANUP",
                    "The native SSH client has not closed its callback");
            if (Thread.currentThread() != eventLoopThread) {
                failureCode = "SSH_CALLBACK_THREAD";
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
        synchronized (stateLock) {
            if (closed) return;
            if (Thread.currentThread() != eventLoopThread) {
                failureCode = "SSH_CALLBACK_THREAD";
                throw new VaultException(failureCode, "SSH callback cleanup must run on its event-loop thread");
            }
            if (inCallback) {
                failureCode = "SSH_CALLBACK_REENTRANT";
                throw new VaultException(failureCode, "SSH callback cleanup cannot re-enter a callback");
            }
            if (nativeSessionOpening || nativeSessionOwned) {
                failureCode = "SSH_CALLBACK_SESSION";
                throw new VaultException(failureCode,
                        "The native SSH session owns this callback binding");
            }
            closing = true;
            cancelled = true;
            if (failureCode == null) failureCode = "SSH_CALLBACK_CLOSING";
        }
        /* Closing is a cancellation boundary even when native cleanup needs a
         * retry; no later caller may install a managed candidate. */
        if (candidateHandoff != null) candidateHandoff.abort();
        try {
            nativeBinding.close(nativeCallback);
        } catch (IOException | LinkageError | RuntimeException failure) {
            synchronized (stateLock) {
                closing = false;
                failureCode = "SSH_CALLBACK_CLEANUP";
            }
            throw new VaultException("SSH_CALLBACK_CLEANUP",
                    "The SSH callback cleanup is unconfirmed");
        }
        nativeClientClosed();
    }

    String lastFailureCode() { synchronized (stateLock) { return failureCode; } }
    SshHostKeyAuthority.Proof hostProof() { synchronized (stateLock) { return hostProof; } }
    SshKexProof kexProof() { synchronized (stateLock) { return kexProof; } }
    Thread eventLoopThread() { return eventLoopThread; }
    long nativeCallbackToken() {
        synchronized (stateLock) { return nativeCallback; }
    }
    long roleToken() { return issued.token; }
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
