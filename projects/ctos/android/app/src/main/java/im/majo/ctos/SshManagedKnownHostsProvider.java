package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * One-shot provider for one exact managed KNOWN_HOSTS object.  This class is
 * intentionally package-private: the native callback receives only the
 * bounded proposal/CAS receipt, never a snapshot or candidate body.
 */
final class SshManagedKnownHostsProvider implements
        SshManagedProvider.TrustProposalSource, SshManagedProvider.TrustCasWriter, AutoCloseable {
    static final class SnapshotReceipt implements AutoCloseable {
        final SshRoleContext role;
        final String rootPath;
        final String path;
        final String objectId;
        final VaultManifest.FileType objectType;
        final String objectIdentity;
        final String connectionId;
        final java.util.UUID vaultId;
        final long generation;
        final long epoch;
        final long deadline;
        final String requestId;
        private final SshRoleContext.Clock clock;
        private byte[] snapshotBinding;
        private byte[] baseDigest;
        private boolean closed;

        private SnapshotReceipt(SshRoleContext role, String rootPath, String path, String objectId,
                String objectIdentity, SshFileSnapshot snapshot, byte[] baseDigest,
                SshRoleContext.Clock clock) throws VaultException {
            if (role == null || rootPath == null || path == null || objectId == null
                    || objectIdentity == null || snapshot == null || baseDigest == null
                    || clock == null || snapshot.publicFiles == null) throw VaultException.invalid();
            VaultManifest.path(rootPath);
            VaultManifest.path(path);
            if (objectId.isEmpty() || objectIdentity.isEmpty() || baseDigest.length != 32
                    || !snapshot.connectionId.equals(role.connectionId)
                    || !snapshot.rootPath.equals(rootPath) || !snapshot.requestId.matches(
                            "[A-Za-z0-9-]{1,128}")) throw VaultException.invalid();
            this.role = role;
            this.rootPath = rootPath;
            this.path = path;
            this.objectId = objectId;
            this.objectType = VaultManifest.FileType.KNOWN_HOSTS;
            this.objectIdentity = objectIdentity;
            connectionId = snapshot.connectionId;
            vaultId = snapshot.vaultId;
            generation = snapshot.generation;
            epoch = snapshot.epoch;
            deadline = snapshot.deadline;
            requestId = snapshot.requestId;
            snapshotBinding = snapshot.binding();
            this.baseDigest = baseDigest.clone();
            this.clock = clock;
        }

        synchronized void requireLive() throws VaultException {
            if (closed) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            role.requireActive(clock);
            long now = clock.now();
            if (now <= 0 || now >= deadline) {
                throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot expired");
            }
        }

        synchronized byte[] binding() throws VaultException {
            if (closed) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            return snapshotBinding.clone();
        }

        synchronized byte[] baseDigest() throws VaultException {
            if (closed) throw new VaultException("VAULT_EXPIRED", "The managed trust snapshot is closed");
            return baseDigest.clone();
        }

        synchronized void requireMatches(SshRoleContext candidateRole,
                SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
            requireLive();
            if (candidateRole != role || evidence == null || evidence.role != role
                    || evidence.classification != SshHostKeyAuthority.Classification.NEW
                    || !connectionId.equals(evidence.role.connectionId)
                    || !role.targetIdentity.equals(evidence.role.targetIdentity)
                    || !role.host.equals(evidence.role.host) || role.port != evidence.role.port
                    || proposal == null || !proposal.matchesManagedObject(objectType, objectId, path, objectIdentity)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust snapshot is not exact");
            }
            byte[] currentBase = evidence.baseDigest();
            try {
                if (!MessageDigest.isEqual(baseDigest, currentBase)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust base changed");
                }
            } finally {
                VaultCodec.wipe(currentBase);
            }
        }

        synchronized boolean matchesEvidence(SshHostKeyAuthority.Evidence evidence) throws VaultException {
            requireLive();
            if (evidence == null || evidence.role != role
                    || evidence.classification != SshHostKeyAuthority.Classification.NEW) return false;
            byte[] currentBase = evidence.baseDigest();
            try { return MessageDigest.isEqual(baseDigest, currentBase); }
            finally { VaultCodec.wipe(currentBase); }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            VaultCodec.wipe(snapshotBinding);
            VaultCodec.wipe(baseDigest);
        }
    }

    /**
     * Exact replacement supplied by a managed hostfile writer.  The bytes
     * are never derived from a fingerprint or formatted by this provider.
     */
    static final class KnownHostsCandidate implements AutoCloseable {
        final String candidateId;
        final String path;
        final String objectId;
        final VaultManifest.FileType objectType;
        final String objectIdentity;
        final String roleBinding;
        final int roleHop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final SshHostKeyAuthority.Classification classification;
        final String keyType;
        final String fingerprint;
        final String snapshotRequestId;
        final String snapshotRootPath;
        final String snapshotConnectionId;
        final java.util.UUID snapshotVaultId;
        final long snapshotGeneration;
        final long snapshotEpoch;
        final long snapshotDeadline;
        private byte[] baseDigest;
        private byte[] snapshotBinding;
        private byte[] replacementDigest;
        private byte[] content;
        final String binding;
        private boolean consumed;
        private boolean closed;

        private KnownHostsCandidate(SnapshotReceipt snapshot, SshHostKeyAuthority.Evidence evidence,
                String candidateId, byte[] exactReplacement) throws VaultException {
            if (snapshot == null || evidence == null || exactReplacement == null
                    || candidateId == null || !candidateId.matches("[A-Za-z0-9_-]{1,128}")
                    || exactReplacement.length == 0
                    || exactReplacement.length > VaultManifest.MAX_FILE_BYTES) throw VaultException.invalid();
            byte[] candidateDigest = null;
            byte[] evidenceBase = null;
            byte[] evidenceReplacement = null;
            try {
                snapshot.requireLive();
                if (!snapshot.matchesEvidence(evidence)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW",
                            "The managed trust candidate is not exact");
                }
                candidateDigest = VaultCodec.digest(exactReplacement);
                evidenceBase = evidence.baseDigest();
                evidenceReplacement = evidence.replacementDigest();
                if (!MessageDigest.isEqual(snapshot.baseDigest, evidenceBase)
                        || !MessageDigest.isEqual(candidateDigest, evidenceReplacement)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate digest changed");
                }
                this.candidateId = candidateId;
                path = snapshot.path;
                objectId = snapshot.objectId;
                objectType = snapshot.objectType;
                objectIdentity = snapshot.objectIdentity;
                roleBinding = evidence.role.binding();
                roleHop = evidence.role.hop;
                connectionId = evidence.role.connectionId;
                targetIdentity = evidence.role.targetIdentity;
                host = evidence.role.host;
                port = evidence.role.port;
                classification = evidence.classification;
                keyType = evidence.keyType;
                fingerprint = evidence.fingerprint;
                snapshotRequestId = snapshot.requestId;
                snapshotRootPath = snapshot.rootPath;
                snapshotConnectionId = snapshot.connectionId;
                snapshotVaultId = snapshot.vaultId;
                snapshotGeneration = snapshot.generation;
                snapshotEpoch = snapshot.epoch;
                snapshotDeadline = snapshot.deadline;
                snapshotBinding = snapshot.binding();
                baseDigest = snapshot.baseDigest();
                replacementDigest = candidateDigest.clone();
                content = exactReplacement.clone();
                String fields = candidateId + '\0' + snapshotRequestId + '\0' + roleBinding + '\0'
                        + roleHop + '\0' + path + '\0' + objectId + '\0' + objectType.name() + '\0'
                        + objectIdentity + '\0' + connectionId + '\0' + targetIdentity + '\0' + host
                        + '\0' + port + '\0' + classification.name() + '\0' + keyType + '\0'
                        + fingerprint + '\0' + hex(baseDigest) + '\0' + hex(replacementDigest);
                byte[] bindingBytes = fields.getBytes(StandardCharsets.UTF_8);
                try {
                    binding = hex(VaultCodec.digest(bindingBytes));
                } finally {
                    VaultCodec.wipe(bindingBytes);
                }
            } catch (VaultException | RuntimeException | Error failure) {
                // Construction can fail after the private body clone exists;
                // do not leave that partial object for eventual GC wiping.
                close();
                throw failure;
            } finally {
                VaultCodec.wipe(candidateDigest);
                VaultCodec.wipe(evidenceBase);
                VaultCodec.wipe(evidenceReplacement);
            }
        }

        synchronized void requireSnapshot(SnapshotReceipt snapshot) throws VaultException {
            if (closed || consumed || snapshot == null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            snapshot.requireLive();
            if (!snapshot.requestId.equals(snapshotRequestId)
                    || !snapshot.rootPath.equals(snapshotRootPath)
                    || !snapshot.connectionId.equals(snapshotConnectionId)
                    || !snapshot.vaultId.equals(snapshotVaultId)
                    || snapshot.generation != snapshotGeneration
                    || snapshot.epoch != snapshotEpoch
                    || snapshot.deadline != snapshotDeadline
                    || !snapshot.role.binding().equals(roleBinding) || snapshot.role.hop != roleHop
                    || !snapshot.path.equals(path) || !snapshot.objectId.equals(objectId)
                    || snapshot.objectType != objectType || !snapshot.objectIdentity.equals(objectIdentity)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            byte[] currentBinding = snapshot.binding();
            try {
                if (!MessageDigest.isEqual(snapshotBinding, currentBinding)) {
                    throw new VaultException("VAULT_CHANGED", "The managed trust snapshot changed");
                }
            } finally {
                VaultCodec.wipe(currentBinding);
            }
        }

        synchronized void requireMatches(SnapshotReceipt snapshot,
                SshHostKeyAuthority.Evidence evidence) throws VaultException {
            requireSnapshot(snapshot);
            if (evidence == null || evidence.role != snapshot.role
                    || evidence.classification != classification || !keyType.equals(evidence.keyType)
                    || !fingerprint.equals(evidence.fingerprint)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is not exact");
            }
            byte[] currentBase = evidence.baseDigest();
            byte[] currentReplacement = evidence.replacementDigest();
            try {
                if (!MessageDigest.isEqual(baseDigest, currentBase)
                        || !MessageDigest.isEqual(replacementDigest, currentReplacement)) {
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate digest changed");
                }
            } finally {
                VaultCodec.wipe(currentBase);
                VaultCodec.wipe(currentReplacement);
            }
        }

        synchronized byte[] consume() throws VaultException {
            if (closed || consumed || content == null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The managed trust candidate is unavailable");
            }
            consumed = true;
            return content.clone();
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            VaultCodec.wipe(baseDigest);
            VaultCodec.wipe(snapshotBinding);
            VaultCodec.wipe(replacementDigest);
            VaultCodec.wipe(content);
        }
    }

    private final SshVaultAdapter adapter;
    private final SnapshotReceipt snapshot;
    private KnownHostsCandidate candidate;
    private SshTrustProposal activeProposal;
    private VaultOperation activeRequest;
    private boolean closed;

    private SshManagedKnownHostsProvider(SshVaultAdapter adapter, SnapshotReceipt snapshot,
            KnownHostsCandidate candidate) throws VaultException {
        if (adapter == null || snapshot == null) throw VaultException.invalid();
        snapshot.requireLive();
        if (candidate != null) candidate.requireSnapshot(snapshot);
        this.adapter = adapter;
        this.snapshot = snapshot;
        this.candidate = candidate;
    }

    /** Create a provider owning exactly one candidate and its snapshot receipt. */
    static SshManagedKnownHostsProvider bind(SshVaultAdapter adapter, SnapshotReceipt snapshot,
            KnownHostsCandidate candidate) throws VaultException {
        if (candidate == null) throw new VaultException("SSH_HOST_KEY_REVIEW", "A managed trust candidate is required");
        return new SshManagedKnownHostsProvider(adapter, snapshot, candidate);
    }

    /**
     * Create the managed side of the separate native candidate handoff.  It
     * starts without a candidate and accepts exactly one through
     * SshKnownHostsCandidateHandoff; the normal proposal/CAS path remains the
     * only way to consume it.
     */
    static SshManagedKnownHostsProvider bindForNativeHandoff(SshVaultAdapter adapter,
            SnapshotReceipt snapshot) throws VaultException {
        return new SshManagedKnownHostsProvider(adapter, snapshot, null);
    }

    SshKnownHostsCandidateHandoff nativeCandidateHandoff() throws VaultException {
        synchronized (this) {
            if (closed || candidate != null || activeProposal != null) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The exact managed trust candidate is unavailable");
            }
            snapshot.requireLive();
            return new SshKnownHostsCandidateHandoff(this, snapshot);
        }
    }

    synchronized boolean acceptsNativeCandidate(KnownHostsCandidate next) throws VaultException {
        if (closed || next == null || candidate != null || activeProposal != null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW",
                    "The exact managed trust candidate is unavailable");
        }
        next.requireSnapshot(snapshot);
        candidate = next;
        return true;
    }

    synchronized void abortNativeHandoff() { failClosed(); }

    /**
     * Loans the exact managed file closure synchronously and retains only the
     * exact metadata, base digest and AppSecrets snapshot receipt.
     */
    static SnapshotReceipt snapshotFor(SshVaultAdapter adapter, SshRoleContext role,
            SshRoleContext.Clock clock, String rootPath, String path, String objectId,
            String objectIdentity) throws VaultException {
        if (adapter == null || role == null || clock == null || rootPath == null || path == null
                || objectId == null || objectIdentity == null) throw VaultException.invalid();
        VaultManifest.path(rootPath);
        VaultManifest.path(path);
        role.requireActive(clock);
        VaultOperation request = adapter.fileRequest(rootPath, role.connectionId);
        final SnapshotReceipt[] result = new SnapshotReceipt[1];
        try {
            adapter.useFileSnapshot(request, rootPath, role.connectionId, snapshot -> {
                role.requireActive(clock);
                VaultManifest.FileObject selected = null;
                for (VaultManifest.FileObject file : snapshot.publicFiles) {
                    if (file.path.equals(path) && file.id.equals(objectId)
                            && file.type == VaultManifest.FileType.KNOWN_HOSTS
                            && file.identity.equals(objectIdentity)) {
                        if (selected != null) {
                            throw new VaultException("VAULT_CONTENT", "The managed trust object is ambiguous");
                        }
                        selected = file;
                    }
                }
                if (selected == null) {
                    throw new VaultException("VAULT_OPERATION", "The exact managed trust object is unavailable");
                }
                byte[] digest = VaultCodec.digest(selected.content);
                try {
                    result[0] = new SnapshotReceipt(role, rootPath, path, objectId,
                            objectIdentity, snapshot, digest, clock);
                } finally {
                    VaultCodec.wipe(digest);
                }
            });
            if (result[0] == null) throw new VaultException("VAULT_OPERATION", "The managed snapshot is unavailable");
            return result[0];
        } catch (VaultException | RuntimeException | Error failure) {
            if (result[0] != null) result[0].close();
            throw failure;
        } finally {
            // The public snapshot was copied synchronously; do not retain the
            // consumed SSH_FILES operation while the trust review is pending.
            adapter.cancel(request);
        }
    }

    /** Build a candidate only from caller-supplied exact replacement bytes. */
    static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
            SshHostKeyAuthority.Evidence evidence, byte[] exactReplacement) throws VaultException {
        return candidateFor(snapshot, evidence, UUID.randomUUID().toString(), exactReplacement);
    }

    /** Build a candidate with the issuer's explicit one-shot identity. */
    static KnownHostsCandidate candidateFor(SnapshotReceipt snapshot,
            SshHostKeyAuthority.Evidence evidence, String candidateId, byte[] exactReplacement)
            throws VaultException {
        return new KnownHostsCandidate(snapshot, evidence, candidateId, exactReplacement);
    }

    @Override public synchronized SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence)
            throws VaultException {
        return proposalFor(evidence, null);
    }

    @Override public synchronized SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof proof) throws VaultException {
        if (closed || activeProposal != null || candidate == null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact managed trust candidate is unavailable");
        }
        snapshot.requireLive();
        if (proof != null) proof.requireMatches(snapshot.role, evidence);
        candidate.requireMatches(snapshot, evidence);
        byte[] replacement = candidate.consume();
        try {
            SshTrustProposal proposal = new SshTrustProposal(snapshot.role, snapshot.path,
                    snapshot.objectId, snapshot.objectType, snapshot.objectIdentity,
                    evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                    evidence.role.port, evidence.keyType, evidence.fingerprint,
                    candidate.binding, snapshot.baseDigest(), replacement);
            activeProposal = proposal;
            candidate.close();
            candidate = null;
            return proposal;
        } catch (VaultException | RuntimeException | Error failure) {
            candidate.close();
            candidate = null;
            snapshot.close();
            throw failure;
        } finally {
            VaultCodec.wipe(replacement);
        }
    }

    /** The old no-proof writer is intentionally unavailable for this provider. */
    @Override public synchronized SshTrustProposal.CasResult write(
            SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal) throws VaultException {
        throw new VaultException("SSH_HOST_KEY_CAS", "The role-bound managed trust writer is required");
    }

    @Override public synchronized SshTrustProposal.CasResult write(
            SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof proof,
            SshTrustProposal proposal) throws VaultException {
        if (closed || activeProposal == null || activeProposal != proposal || proof == null) {
            failClosed();
            throw new VaultException("SSH_HOST_KEY_CAS", "The exact managed trust writer is unavailable");
        }
        try {
            snapshot.requireLive();
            proof.requireMatches(snapshot.role, evidence);
            snapshot.requireMatches(snapshot.role, evidence, proposal);
            if (!proposal.matchesEvidence(evidence)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact unknown host-key review is required");
            }
            VaultOperation request = adapter.trustRequest(snapshot.role, evidence, proof, snapshot, proposal);
            activeRequest = request;
            adapter.updateTrust(request, snapshot.role, evidence, proof, snapshot, proposal, () -> { });
            SshTrustProposal.CasResult result = SshTrustProposal.CasResult.committed(evidence, proposal);
            activeRequest = null;
            activeProposal = null;
            closed = true;
            snapshot.close();
            return result;
        } catch (VaultException | RuntimeException | Error failure) {
            failClosed();
            throw failure;
        }
    }

    private synchronized void failClosed() {
        closed = true;
        if (candidate != null) candidate.close();
        candidate = null;
        snapshot.close();
        VaultOperation request = activeRequest;
        activeRequest = null;
        if (request != null) adapter.cancel(request);
        if (activeProposal != null) activeProposal.close();
        activeProposal = null;
    }

    @Override public synchronized void close() { failClosed(); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
