package im.majo.ctos;

import java.security.MessageDigest;
import java.util.UUID;

/** Opaque module-only capability. There is no MethodChannel constructor or serialization. */
final class VaultOperation {
    enum Consumer { TOTP, SSH }
    enum Purpose { TOTP_CODE, TOTP_LIST, TOTP_RENAME, TOTP_SAVE, TOTP_DELETE, SSH_CREDENTIAL, SSH_FILES, SSH_PRIVATE_KEY,
        SSH_SAVE, SSH_DELETE, SSH_TRUST_UPDATE, SSH_BUNDLE_WRITE, SSH_PRIVATE_KEY_BUNDLE }
    final String requestId = UUID.randomUUID().toString();
    final Consumer consumer;
    final Purpose purpose;
    final String resource;
    final String target;
    final String connectionId;
    final UUID vaultId;
    final long generation;
    final long epoch;
    final long deadline;
    final byte[] snapshotBinding;
    final AppSecrets.BundleContext bundleContext;
    private AppSecrets.TotpReadState totpReadState;
    private AppSecrets.SshPublicReadState sshPublicReadState;
    private AppSecrets.SshSecretReadState sshSecretReadState;
    private AppSecrets.SshEntryMutationState sshEntryMutationState;
    private AppSecrets.TotpMutationState totpMutationState;
    synchronized void attachTotpMutation(AppSecrets.TotpMutationState state) throws VaultException {
        if (totpMutationState != null) throw new VaultException("VAULT_OPERATION", "Operation already bound");
        totpMutationState = java.util.Objects.requireNonNull(state);
    }
    synchronized AppSecrets.TotpMutationState totpMutationState() { return totpMutationState; }
    private AppSecrets.SshTrustState sshTrustState;
    volatile boolean consumed;
    volatile boolean cancelled;
    private VaultSession.Revocation revocation;
    private String roleBinding;
    private int roleHop = -1;
    private String roleFingerprint;
    private boolean trustBound;
    private SshRoleContext trustRole;
    private String trustSnapshotRequestId;
    private UUID trustVaultId;
    private long trustGeneration;
    private long trustEpoch;
    private long trustSnapshotDeadline;
    private byte[] trustSnapshotBinding;
    private String trustProposalBinding;
    private String trustPath;
    private String trustObjectId;
    private VaultManifest.FileType trustObjectType;
    private String trustObjectIdentity;
    private String trustConnectionId;
    private String trustTargetIdentity;
    private String trustHost;
    private int trustPort;
    private SshHostKeyAuthority.Classification trustClassification;
    private String trustKeyType;
    private String trustFingerprint;
    private byte[] trustBaseDigest;
    private byte[] trustReplacementDigest;

    VaultOperation(Consumer consumer, Purpose purpose, String resource, String target,
            String connectionId, VaultEnvelope envelope, long epoch, long deadline) {
        this(consumer, purpose, resource, target, connectionId, envelope, epoch, deadline, null);
    }

    VaultOperation(Consumer consumer, Purpose purpose, String resource, String target,
            String connectionId, VaultEnvelope envelope, long epoch, long deadline,
            AppSecrets.BundleContext bundleContext) {
        this.consumer = consumer;
        this.purpose = purpose;
        this.resource = resource;
        this.target = target;
        this.connectionId = connectionId;
        vaultId = envelope.id;
        generation = envelope.generation;
        snapshotBinding = envelope.binding();
        this.epoch = epoch;
        this.deadline = deadline;
        this.bundleContext = bundleContext;
    }

    synchronized void attachRevocation(VaultSession.Revocation owned) throws VaultException {
        if (cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (revocation != null) throw new VaultException("BUSY", "The operation already owns a file view");
        revocation = java.util.Objects.requireNonNull(owned);
    }

    void attachTotpRead(AppSecrets.TotpReadState state) {
        if (totpReadState != null) throw new IllegalStateException("Read state already attached");
        totpReadState = java.util.Objects.requireNonNull(state);
    }

    AppSecrets.TotpReadState totpReadState() { return totpReadState; }

    void attachSshPublicRead(AppSecrets.SshPublicReadState state) {
        if (sshPublicReadState != null) throw new IllegalStateException("Read state already attached");
        sshPublicReadState = java.util.Objects.requireNonNull(state);
    }

    AppSecrets.SshPublicReadState sshPublicReadState() { return sshPublicReadState; }

    void attachSshSecretRead(AppSecrets.SshSecretReadState state) {
        if (sshSecretReadState != null) throw new IllegalStateException("Read state already attached");
        sshSecretReadState = java.util.Objects.requireNonNull(state);
    }

    AppSecrets.SshSecretReadState sshSecretReadState() { return sshSecretReadState; }

    void attachSshEntryMutation(AppSecrets.SshEntryMutationState state) {
        if (sshEntryMutationState != null) throw new IllegalStateException("Mutation state already attached");
        sshEntryMutationState = java.util.Objects.requireNonNull(state);
    }

    AppSecrets.SshEntryMutationState sshEntryMutationState() { return sshEntryMutationState; }

    void attachSshTrust(AppSecrets.SshTrustState state) {
        if (sshTrustState != null) throw new IllegalStateException("Trust state already attached");
        sshTrustState = java.util.Objects.requireNonNull(state);
    }

    AppSecrets.SshTrustState sshTrustState() { return sshTrustState; }

    synchronized void bindRole(SshRoleContext role, SshHostKeyAuthority.Proof hostKey) throws VaultException {
        if (consumer != Consumer.SSH || role == null || hostKey == null || consumed || cancelled
                || roleBinding != null || !connectionId.equals(role.connectionId)
                || !target.equals(role.targetIdentity)) {
            throw new VaultException("VAULT_OPERATION", "Secret operation does not match its SSH role");
        }
        role.requireBound();
        hostKey.requireMatches(role);
        roleBinding = role.binding();
        roleHop = role.hop;
        roleFingerprint = hostKey.fingerprint;
    }

    synchronized void requireRole(SshRoleContext role, SshHostKeyAuthority.Proof hostKey)
            throws VaultException {
        if (consumer != Consumer.SSH || role == null || hostKey == null || cancelled
                || roleBinding == null || !roleBinding.equals(role.binding()) || roleHop != role.hop
                || !roleFingerprint.equals(hostKey.fingerprint) || !connectionId.equals(role.connectionId)
                || !target.equals(role.targetIdentity)) {
            throw new VaultException("VAULT_OPERATION", "Secret operation does not match its SSH role");
        }
        hostKey.requireMatches(role);
    }

    /**
     * Binds an SSH trust operation to the exact managed snapshot and host
     * evidence.  The binding is internal-only; it is never serialised or
     * accepted from a channel caller.
     */
    synchronized void bindTrust(SshManagedKnownHostsProvider.SnapshotReceipt snapshot,
            SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
        if (consumer != Consumer.SSH || purpose != Purpose.SSH_TRUST_UPDATE || consumed || cancelled
                || trustBound || snapshot == null || role == null || hostKey == null
                || evidence == null || proposal == null) {
            throw new VaultException("VAULT_OPERATION", "Secret operation does not match its trust binding");
        }
        snapshot.requireMatches(role, evidence, proposal);
        hostKey.requireMatches(role, evidence);
        if (!proposal.matchesEvidence(evidence) || !proposal.matchesManagedObject(
                snapshot.objectType, snapshot.objectId, snapshot.path, snapshot.objectIdentity)) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact managed trust object changed");
        }
        byte[] currentBinding = snapshot.binding();
        try {
            if (!vaultId.equals(snapshot.vaultId) || generation != snapshot.generation
                    || epoch != snapshot.epoch || !MessageDigest.isEqual(currentBinding, snapshotBinding)) {
                throw new VaultException("VAULT_CHANGED", "The managed trust snapshot changed");
            }
        } finally { VaultCodec.wipe(currentBinding); }
        trustBound = true;
        trustRole = role;
        trustSnapshotRequestId = snapshot.requestId;
        trustVaultId = snapshot.vaultId;
        trustGeneration = snapshot.generation;
        trustEpoch = snapshot.epoch;
        trustSnapshotDeadline = snapshot.deadline;
        trustSnapshotBinding = snapshot.binding();
        trustProposalBinding = proposal.binding;
        trustPath = proposal.path;
        trustObjectId = proposal.objectId;
        trustObjectType = proposal.objectType;
        trustObjectIdentity = proposal.objectIdentity;
        trustConnectionId = evidence.role.connectionId;
        trustTargetIdentity = evidence.role.targetIdentity;
        trustHost = evidence.role.host;
        trustPort = evidence.role.port;
        trustClassification = evidence.classification;
        trustKeyType = evidence.keyType;
        trustFingerprint = evidence.fingerprint;
        trustBaseDigest = evidence.baseDigest();
        trustReplacementDigest = evidence.replacementDigest();
    }

    /**
     * Rechecks the trust binding at the final effect guard.  AppSecrets marks
     * the operation consumed before invoking that guard, so this method
     * deliberately checks cancellation and identity but not consumed state.
     */
    synchronized void requireTrust(SshManagedKnownHostsProvider.SnapshotReceipt snapshot,
            SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
        if (consumer != Consumer.SSH || purpose != Purpose.SSH_TRUST_UPDATE || cancelled || !trustBound
                || snapshot == null || role == null || hostKey == null || evidence == null || proposal == null
                || !trustSnapshotRequestId.equals(snapshot.requestId)
                || !trustVaultId.equals(snapshot.vaultId) || trustGeneration != snapshot.generation
                || trustEpoch != snapshot.epoch || trustSnapshotDeadline != snapshot.deadline
                || !trustProposalBinding.equals(proposal.binding)
                || !trustPath.equals(proposal.path) || !trustObjectId.equals(proposal.objectId)
                || trustObjectType != proposal.objectType
                || trustObjectIdentity == null || !trustObjectIdentity.equals(proposal.objectIdentity)
                || !trustConnectionId.equals(evidence.role.connectionId)
                || !trustTargetIdentity.equals(evidence.role.targetIdentity)
                || !trustHost.equals(evidence.role.host) || trustPort != evidence.role.port
                || trustClassification != evidence.classification || !trustKeyType.equals(evidence.keyType)
                || !trustFingerprint.equals(evidence.fingerprint)) {
            throw new VaultException("VAULT_OPERATION", "Secret operation does not match its trust binding");
        }
        byte[] currentBinding = snapshot.binding();
        try {
            if (!MessageDigest.isEqual(trustSnapshotBinding, currentBinding)) {
                throw new VaultException("VAULT_CHANGED", "The managed trust snapshot changed");
            }
        } finally { VaultCodec.wipe(currentBinding); }
        snapshot.requireMatches(role, evidence, proposal);
        hostKey.requireMatches(role, evidence);
        if (!proposal.matchesEvidence(evidence)
                || !proposal.matchesManagedObject(snapshot.objectType, snapshot.objectId,
                        snapshot.path, snapshot.objectIdentity)) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact managed trust object changed");
        }
        byte[] currentBase = evidence.baseDigest();
        byte[] currentReplacement = evidence.replacementDigest();
        try {
            if (!MessageDigest.isEqual(trustBaseDigest, currentBase)
                    || !MessageDigest.isEqual(trustReplacementDigest, currentReplacement)) {
                throw new VaultException("SSH_HOST_KEY_CAS", "The host-key evidence digest changed");
            }
        } finally {
            VaultCodec.wipe(currentBase);
            VaultCodec.wipe(currentReplacement);
        }
    }

    synchronized SshRoleContext boundTrustRole() {
        return consumer == Consumer.SSH && purpose == Purpose.SSH_TRUST_UPDATE && trustBound
                && !cancelled ? trustRole : null;
    }

    void cancel() {
        VaultSession.Revocation owned;
        synchronized (this) { cancelled = true; owned = revocation; }
        // Revoke without holding the operation monitor; no AppSecrets/session call.
        if (owned != null) owned.revoke();
    }
}
