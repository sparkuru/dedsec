package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Exact native trust change; approval gates must validate host identity and reject unrelated rows. */
final class SshTrustProposal implements AutoCloseable {
    enum CasStatus { NOT_REQUIRED, COMMITTED, FAILED }

    /**
     * Bounded non-secret receipt from the exact known_hosts CAS. The
     * replacement body stays inside the proposal and never crosses JNI.
     */
    static final class CasResult {
        final CasStatus status;
        final String roleBinding;
        final int roleHop;
        final String targetIdentity;
        final String connectionId;
        final String host;
        final int port;
        final SshHostKeyAuthority.Classification classification;
        final String keyType;
        final String fingerprint;
        final String proposalBinding;
        final String failureCode;
        private final byte[] baseDigest;
        private final byte[] replacementDigest;

        private CasResult(CasStatus status, String roleBinding, int roleHop, String targetIdentity,
                String connectionId, String host, int port, SshHostKeyAuthority.Classification classification,
                String keyType, String fingerprint, String proposalBinding, String failureCode,
                byte[] baseDigest, byte[] replacementDigest) throws VaultException {
            if (status == null || roleBinding == null || !roleBinding.matches("[0-9a-f]{64}")
                    || roleHop < 0 || roleHop >= SshRoleContext.MAX_HOPS
                    || targetIdentity == null || connectionId == null || host == null
                    || !targetIdentity.matches("[A-Za-z0-9_:.@+\\-/]{1,512}")
                    || !connectionId.matches("[A-Za-z0-9_-]{1,128}")
                    || !host.matches("[A-Za-z0-9.:%\\[\\]\\-]{1,253}")
                    || port < 1 || port > 65535
                    || classification == null
                    || keyType == null || !keyType.matches("[A-Za-z0-9@._+\\-]{1,64}")
                    || fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")
                    || proposalBinding == null
                    || status == CasStatus.NOT_REQUIRED && !proposalBinding.isEmpty()
                    || status != CasStatus.NOT_REQUIRED && !proposalBinding.matches("[0-9a-f]{64}")
                    || failureCode == null
                    || status == CasStatus.FAILED && !failureCode.matches("[A-Z0-9_]{1,64}")
                    || status != CasStatus.FAILED && !failureCode.isEmpty()
                    || baseDigest == null || replacementDigest == null
                    || baseDigest.length != 32 || replacementDigest.length != 32) {
                throw VaultException.invalid();
            }
            this.status = status;
            this.roleBinding = roleBinding;
            this.roleHop = roleHop;
            this.targetIdentity = targetIdentity;
            this.connectionId = connectionId;
            this.host = host;
            this.port = port;
            this.classification = classification;
            this.keyType = keyType;
            this.fingerprint = fingerprint;
            this.proposalBinding = proposalBinding;
            this.failureCode = failureCode;
            this.baseDigest = baseDigest.clone();
            this.replacementDigest = replacementDigest.clone();
        }

        static CasResult notRequired(SshHostKeyAuthority.Evidence evidence) throws VaultException {
            if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.KNOWN) {
                throw casFailure();
            }
            return forEvidence(CasStatus.NOT_REQUIRED, evidence, null, "");
        }

        static CasResult committed(SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal)
                throws VaultException {
            if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW
                    || proposal == null || !proposal.matchesEvidence(evidence)) throw casFailure();
            return forEvidence(CasStatus.COMMITTED, evidence, proposal, "");
        }

        static CasResult failed(SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal,
                String failureCode) throws VaultException {
            if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW
                    || proposal == null || !proposal.matchesEvidence(evidence)
                    || failureCode == null || !failureCode.matches("[A-Z0-9_]{1,64}")) {
                throw casFailure();
            }
            return forEvidence(CasStatus.FAILED, evidence, proposal, failureCode);
        }

        private static CasResult forEvidence(CasStatus status, SshHostKeyAuthority.Evidence evidence,
                SshTrustProposal proposal, String failureCode) throws VaultException {
            if (evidence == null || evidence.role == null) throw casFailure();
            if ((status == CasStatus.NOT_REQUIRED
                    && evidence.classification != SshHostKeyAuthority.Classification.KNOWN)
                    || (status != CasStatus.NOT_REQUIRED
                    && evidence.classification != SshHostKeyAuthority.Classification.NEW)) {
                throw casFailure();
            }
            evidence.role.requireBound();
            String proposalBinding = proposal == null ? "" : proposal.binding;
            byte[] evidenceBase = evidence.baseDigest();
            byte[] evidenceReplacement = evidence.replacementDigest();
            byte[] base = evidenceBase;
            byte[] replacement = evidenceReplacement;
            try {
                if (proposal != null) {
                    base = proposal.baseDigest();
                    replacement = proposal.newDigest();
                }
                return new CasResult(status, evidence.role.binding(), evidence.role.hop,
                        evidence.role.targetIdentity, evidence.role.connectionId, evidence.role.host,
                        evidence.role.port, evidence.classification, evidence.keyType,
                        evidence.fingerprint, proposalBinding, failureCode, base, replacement);
            } finally {
                if (base != evidenceBase) VaultCodec.wipe(evidenceBase);
                if (replacement != evidenceReplacement) VaultCodec.wipe(evidenceReplacement);
                VaultCodec.wipe(base);
                VaultCodec.wipe(replacement);
            }
        }

        byte[] baseDigest() { return baseDigest.clone(); }
        byte[] replacementDigest() { return replacementDigest.clone(); }

        boolean matches(SshHostKeyAuthority.Evidence evidence, SshTrustProposal proposal,
                CasStatus expected) throws VaultException {
            if (evidence == null || proposal == null || !proposal.matchesEvidence(evidence)) return false;
            byte[] evidenceBase = evidence.baseDigest();
            byte[] evidenceReplacement = evidence.replacementDigest();
            byte[] proposalBase = proposal.baseDigest();
            byte[] proposalReplacement = proposal.newDigest();
            try {
                return matches(evidence, proposal.binding, proposalBase, proposalReplacement, expected)
                        && MessageDigest.isEqual(baseDigest, evidenceBase)
                        && MessageDigest.isEqual(replacementDigest, evidenceReplacement);
            } finally {
                VaultCodec.wipe(evidenceBase);
                VaultCodec.wipe(evidenceReplacement);
                VaultCodec.wipe(proposalBase);
                VaultCodec.wipe(proposalReplacement);
            }
        }

        /**
         * Validates a receipt after the writer has closed the proposal body.
         * The caller captured these non-secret proposal digests while it was
         * open; the body itself is never retained for receipt validation.
         */
        boolean matches(SshHostKeyAuthority.Evidence evidence, String expectedProposalBinding,
                byte[] expectedBaseDigest, byte[] expectedReplacementDigest, CasStatus expected)
                throws VaultException {
            if (evidence == null || expectedProposalBinding == null || expectedBaseDigest == null
                    || expectedReplacementDigest == null || expectedBaseDigest.length != 32
                    || expectedReplacementDigest.length != 32 || status != expected
                    || !roleBinding.equals(evidence.role.binding()) || roleHop != evidence.role.hop
                    || !targetIdentity.equals(evidence.role.targetIdentity)
                    || !connectionId.equals(evidence.role.connectionId) || !host.equals(evidence.role.host)
                    || port != evidence.role.port || classification != evidence.classification
                    || !keyType.equals(evidence.keyType) || !fingerprint.equals(evidence.fingerprint)
                    || !proposalBinding.equals(expectedProposalBinding) || !failureCode.isEmpty()) return false;
            byte[] evidenceBase = evidence.baseDigest();
            byte[] evidenceReplacement = evidence.replacementDigest();
            try {
                return MessageDigest.isEqual(baseDigest, evidenceBase)
                        && MessageDigest.isEqual(replacementDigest, evidenceReplacement)
                        && MessageDigest.isEqual(baseDigest, expectedBaseDigest)
                        && MessageDigest.isEqual(replacementDigest, expectedReplacementDigest);
            } finally {
                VaultCodec.wipe(evidenceBase);
                VaultCodec.wipe(evidenceReplacement);
            }
        }

        private static VaultException casFailure() {
            return new VaultException("SSH_HOST_KEY_CAS", "The exact host-key write-back failed");
        }
    }

    final String path;
    final String objectId;
    final VaultManifest.FileType objectType;
    /** Exact manifest identity; null is retained only for legacy test seams. */
    final String objectIdentity;
    final String targetIdentity;
    final String connectionId;
    final String host;
    final int port;
    /** Native host-key type; null is retained only for the legacy non-role API. */
    final String keyType;
    final String fingerprint;
    final String roleBinding;
    final int roleHop;
    private final byte[] baseDigest;
    private final byte[] content;
    private final byte[] replacementDigest;
    final String binding;
    private boolean closed;

    SshTrustProposal(String path, String objectId, String targetIdentity, String connectionId, String host,
            int port, String fingerprint, byte[] baseDigest, byte[] newContent) throws VaultException {
        this(null, -1, path, objectId, VaultManifest.FileType.KNOWN_HOSTS, null,
                targetIdentity, connectionId, host, port, fingerprint, null, null,
                baseDigest, newContent);
    }

    /** Production provider proposals must carry the exact role and hop. */
    SshTrustProposal(SshRoleContext role, String path, String objectId, String targetIdentity,
            String connectionId, String host, int port, String keyType, String fingerprint,
            byte[] baseDigest, byte[] newContent) throws VaultException {
        this(roleBinding(role), roleHop(role), path, objectId, VaultManifest.FileType.KNOWN_HOSTS, null,
                targetIdentity, connectionId, host, port, fingerprint, keyType, null,
                baseDigest, newContent);
    }

    /** Production provider proposals must carry the exact managed object identity and type. */
    SshTrustProposal(SshRoleContext role, String path, String objectId,
            VaultManifest.FileType objectType, String objectIdentity, String targetIdentity,
            String connectionId, String host, int port, String keyType, String fingerprint,
            byte[] baseDigest, byte[] newContent) throws VaultException {
        this(roleBinding(role), roleHop(role), path, objectId, objectType, objectIdentity,
                targetIdentity, connectionId, host, port, fingerprint, keyType, null,
                baseDigest, newContent);
    }

    /** Production native candidates carry their one-shot identity in the CAS binding. */
    SshTrustProposal(SshRoleContext role, String path, String objectId,
            VaultManifest.FileType objectType, String objectIdentity, String targetIdentity,
            String connectionId, String host, int port, String keyType, String fingerprint,
            String candidateBinding, byte[] baseDigest, byte[] newContent) throws VaultException {
        this(roleBinding(role), roleHop(role), path, objectId, objectType, objectIdentity,
                targetIdentity, connectionId, host, port, fingerprint, keyType, candidateBinding,
                baseDigest, newContent);
    }

    /** Compatibility constructor; role-bound provider proposals must use the key-type overload. */
    SshTrustProposal(SshRoleContext role, String path, String objectId, String targetIdentity,
            String connectionId, String host, int port, String fingerprint, byte[] baseDigest,
            byte[] newContent) throws VaultException {
        this(roleBinding(role), roleHop(role), path, objectId, VaultManifest.FileType.KNOWN_HOSTS, null,
                targetIdentity, connectionId, host, port, fingerprint, null, null,
                baseDigest, newContent);
    }

    private SshTrustProposal(String roleBinding, int roleHop, String path, String objectId,
            VaultManifest.FileType objectType, String objectIdentity, String targetIdentity,
            String connectionId, String host, int port, String fingerprint, String keyType,
            String candidateBinding, byte[] baseDigest, byte[] newContent) throws VaultException {
        if (path == null) throw VaultException.invalid();
        VaultManifest.path(path);
        if (objectId == null || objectType == null || connectionId == null || targetIdentity == null
                || fingerprint == null
                || baseDigest == null || newContent == null || objectId.isEmpty() || connectionId.isEmpty()
                || targetIdentity.isEmpty() || host == null || port < 1 || port > 65535
                || !host.matches("[A-Za-z0-9.:%\\[\\]-]{1,253}")
                || !connectionId.matches("[A-Za-z0-9_-]{1,128}")
                || !targetIdentity.matches("[A-Za-z0-9_:.@+\\-/]{1,512}")
                || objectIdentity != null && (objectIdentity.isEmpty()
                        || objectIdentity.getBytes(StandardCharsets.UTF_8).length > 512
                        || objectIdentity.indexOf('\0') >= 0)
                || keyType != null && !keyType.matches("[A-Za-z0-9@._+\\-]{1,64}")
                || candidateBinding != null && !candidateBinding.matches("[0-9a-f]{64}")
                || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}") || baseDigest.length != 32
                || newContent.length > VaultManifest.MAX_FILE_BYTES
                || roleBinding != null && (!roleBinding.matches("[0-9a-f]{64}")
                        || roleHop < 0 || roleHop >= SshRoleContext.MAX_HOPS)) throw VaultException.invalid();
        this.path = path;
        this.objectId = objectId;
        this.objectType = objectType;
        this.objectIdentity = objectIdentity;
        this.targetIdentity = targetIdentity;
        this.connectionId = connectionId;
        this.host = host;
        this.port = port;
        this.keyType = keyType;
        this.fingerprint = fingerprint;
        this.roleBinding = roleBinding;
        this.roleHop = roleHop;
        byte[] baseCopy = null;
        byte[] contentCopy = null;
        byte[] contentDigest = null;
        byte[] bindingBytes = null;
        byte[] bindingDigest = null;
        try {
            baseCopy = baseDigest.clone();
            contentCopy = newContent.clone();
            contentDigest = VaultCodec.digest(contentCopy);
            String fields = (roleBinding == null ? "" : roleBinding) + '\0' + roleHop + '\0'
                    + path + '\0' + objectId + '\0' + objectType.name() + '\0'
                    + (objectIdentity == null ? "" : objectIdentity) + '\0'
                    + targetIdentity + '\0' + connectionId + '\0'
                    + host + '\0' + port + '\0' + (keyType == null ? "" : keyType)
                    + '\0' + fingerprint;
            if (candidateBinding != null) fields += '\0' + candidateBinding;
            fields += '\0' + hex(baseCopy) + '\0' + hex(contentDigest);
            bindingBytes = fields.getBytes(StandardCharsets.UTF_8);
            bindingDigest = VaultCodec.digest(bindingBytes);
            String computedBinding = hex(bindingDigest);
            this.baseDigest = baseCopy;
            this.content = contentCopy;
            this.replacementDigest = contentDigest;
            this.binding = computedBinding;
            baseCopy = null;
            contentCopy = null;
            contentDigest = null;
        } finally {
            // The proposal only takes ownership after every derived binding
            // value has been computed successfully.
            VaultCodec.wipe(baseCopy);
            VaultCodec.wipe(contentCopy);
            VaultCodec.wipe(contentDigest);
            VaultCodec.wipe(bindingBytes);
            VaultCodec.wipe(bindingDigest);
        }
    }

    private static String roleBinding(SshRoleContext role) throws VaultException {
        if (role == null) throw VaultException.invalid();
        role.requireBound();
        return role.binding();
    }

    private static int roleHop(SshRoleContext role) throws VaultException {
        if (role == null) throw VaultException.invalid();
        role.requireBound();
        return role.hop;
    }

    boolean matchesRole(SshRoleContext role) {
        return roleBinding != null && role != null && roleBinding.equals(role.binding())
                && roleHop == role.hop;
    }

    boolean matchesManagedObject(VaultManifest.FileType type, String id, String path, String identity) {
        return objectType == type && objectId.equals(id) && this.path.equals(path)
                && objectIdentity != null && objectIdentity.equals(identity);
    }

    /** Exact proposal/evidence binding shared by authority and CAS validation. */
    synchronized boolean matchesEvidence(SshHostKeyAuthority.Evidence evidence) throws VaultException {
        requireOpen();
        if (evidence == null || evidence.role == null || !matchesRole(evidence.role)
                || evidence.classification != SshHostKeyAuthority.Classification.NEW
                || objectType != VaultManifest.FileType.KNOWN_HOSTS
                || !objectId.matches("[A-Za-z0-9_-]{1,128}")
                || !targetIdentity.equals(evidence.role.targetIdentity)
                || !connectionId.equals(evidence.role.connectionId) || !host.equals(evidence.role.host)
                || port != evidence.role.port || keyType == null || !keyType.equals(evidence.keyType)
                || !fingerprint.equals(evidence.fingerprint)) return false;
        byte[] evidenceBase = evidence.baseDigest();
        byte[] evidenceReplacement = evidence.replacementDigest();
        try {
            return MessageDigest.isEqual(baseDigest, evidenceBase)
                    && MessageDigest.isEqual(replacementDigest, evidenceReplacement);
        } finally {
            VaultCodec.wipe(evidenceBase);
            VaultCodec.wipe(evidenceReplacement);
        }
    }

    synchronized byte[] baseDigest() throws VaultException { requireOpen(); return baseDigest.clone(); }
    synchronized byte[] newContent() throws VaultException { requireOpen(); return content.clone(); }
    synchronized byte[] newDigest() throws VaultException { requireOpen(); return replacementDigest.clone(); }
    synchronized void requireOpen() throws VaultException {
        if (closed) throw new VaultException("VAULT_EXPIRED", "Trust proposal expired");
    }
    // The request owner must close on completion/cancellation/lock; a closed proposal cannot authorize a write.
    @Override public synchronized void close() { VaultCodec.wipe(content); closed = true; }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
