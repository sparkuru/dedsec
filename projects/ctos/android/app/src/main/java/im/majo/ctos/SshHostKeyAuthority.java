package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Host-key evidence and trust decisions.  Parsing and classification are
 * native-provider responsibilities; this class only consumes their bounded,
 * role-bound result and applies the approval policy.
 */
final class SshHostKeyAuthority {
    static final String PROOF_INVALID = "SSH_HOST_PROOF_INVALID";
    enum Classification { KNOWN, NEW, CHANGED, REVOKED, CERTIFICATE, INVALID }

    interface UnknownKeyIssuer {
        void requireApproved(Review review) throws VaultException;
    }

    /** A separate user decision: this capability never constitutes host proof. */
    interface ChangedKeyIssuer {
        void requireApproved(ChangedReview review) throws VaultException;
    }

    static final class Evidence {
        final SshRoleContext role;
        final Classification classification;
        final String keyType;
        final String fingerprint;
        final byte[] baseDigest;
        final byte[] replacementDigest;

        private Evidence(SshRoleContext role, Classification classification, String keyType,
                String fingerprint, byte[] baseDigest, byte[] replacementDigest) {
            this.role = role;
            this.classification = classification;
            this.keyType = keyType;
            this.fingerprint = fingerprint;
            this.baseDigest = baseDigest.clone();
            this.replacementDigest = replacementDigest.clone();
        }

        byte[] baseDigest() { return baseDigest.clone(); }
        byte[] replacementDigest() { return replacementDigest.clone(); }

        /** Only a native provider/role adapter should call this factory. */
        static Evidence fromNative(SshRoleContext role, Classification classification, String keyType,
                String fingerprint, byte[] baseDigest, byte[] replacementDigest)
                throws VaultException {
            if (role == null || classification == null || keyType == null
                    || !keyType.matches("[A-Za-z0-9@._+\\-]{1,64}")
                    || fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")
                    || baseDigest == null || replacementDigest == null
                    || baseDigest.length != 32 || replacementDigest.length != 32) {
                throw new VaultException("SSH_HOST_KEY_INVALID", "The native host-key evidence is invalid");
            }
            role.requireBound();
            return new Evidence(role, classification, keyType, fingerprint, baseDigest, replacementDigest);
        }

        boolean matches(SshRoleContext candidate) {
            return candidate != null && role == candidate
                    && hostMatches(candidate);
        }

        private boolean hostMatches(SshRoleContext candidate) {
            return candidate.host.equals(role.host) && candidate.port == role.port
                    && candidate.targetIdentity.equals(role.targetIdentity)
                    && candidate.connectionId.equals(role.connectionId)
                    && candidate.hop == role.hop;
        }
    }

    static final class Review {
        private final SshRoleContext role;
        final String roleBinding;
        final int hop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final Classification classification;
        final String keyType;
        final String fingerprint;
        final byte[] baseDigest;
        final byte[] replacementDigest;
        final String binding;

        private Review(Evidence evidence) {
            role = evidence.role;
            roleBinding = evidence.role.binding();
            hop = evidence.role.hop;
            connectionId = evidence.role.connectionId;
            targetIdentity = evidence.role.targetIdentity;
            host = evidence.role.host;
            port = evidence.role.port;
            classification = evidence.classification;
            keyType = evidence.keyType;
            fingerprint = evidence.fingerprint;
            baseDigest = evidence.baseDigest.clone();
            replacementDigest = evidence.replacementDigest.clone();
            binding = digest(roleBinding + '\0' + hop + '\0' + connectionId + '\0' + targetIdentity
                    + '\0' + host + '\0' + port + '\0' + classification.name() + '\0' + keyType
                    + '\0' + fingerprint + '\0'
                    + hex(baseDigest) + '\0' + hex(replacementDigest));
        }

        byte[] baseDigest() { return baseDigest.clone(); }
        byte[] replacementDigest() { return replacementDigest.clone(); }
        boolean belongsTo(SshRoleContext candidate) { return role == candidate; }
    }

    /** Exact old/new facts from the current candidate and its source snapshot. */
    static final class ChangedReview {
        private final SshRoleContext role;
        private final Evidence evidence;
        private final SshTrustProposal proposal;
        final String roleBinding, connectionId, targetIdentity, host, source, keyType, fingerprint, binding;
        final int hop, port;
        final java.util.List<SshHostTrustMaterial.Record> oldRecords;

        private ChangedReview(Evidence evidence, SshTrustProposal proposal,
                java.util.List<SshHostTrustMaterial.Record> oldRecords) throws VaultException {
            if (evidence == null || evidence.classification != Classification.CHANGED || proposal == null
                    || !proposal.matchesChangedEvidence(evidence) || oldRecords == null
                    || oldRecords.isEmpty() || oldRecords.size() > SshHostTrustMaterial.MAX_ROWS)
                throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact changed host review is unavailable");
            role = evidence.role; this.evidence = evidence; this.proposal = proposal;
            roleBinding = role.binding(); hop = role.hop; connectionId = role.connectionId;
            targetIdentity = role.targetIdentity; host = role.host; port = role.port;
            source = proposal.path; keyType = evidence.keyType; fingerprint = evidence.fingerprint;
            VaultManifest.path(source);
            int previous = 0;
            StringBuilder facts = new StringBuilder(roleBinding).append('\0').append(hop)
                    .append('\0').append(connectionId).append('\0').append(targetIdentity)
                    .append('\0').append(host).append('\0').append(port).append('\0')
                    .append(source).append('\0').append(proposal.binding).append('\0')
                    .append(keyType).append('\0').append(fingerprint);
            for (SshHostTrustMaterial.Record row : oldRecords) {
                if (row == null || row.line <= previous || !row.reason.equals("none")
                        || !row.marker.equals("none")
                        || !(row.selector.equals("literal") || row.selector.equals("hashed")))
                    throw new VaultException("SSH_HOST_TRUST_BLOCKED", "The old host records cannot be replaced");
                previous = row.line;
                facts.append('\0').append(row.line).append('\0').append(row.algorithm)
                        .append('\0').append(row.fingerprint).append('\0').append(row.selector);
            }
            this.oldRecords = VaultCollections.copyList(oldRecords);
            binding = digest(facts.toString());
        }

        boolean belongsTo(SshRoleContext candidate) { return role == candidate; }

        byte[] baseDigest() { return evidence.baseDigest(); }
        byte[] replacementDigest() { return evidence.replacementDigest(); }

        void requireMatches(SshRoleContext candidate, Evidence actual, SshTrustProposal exact)
                throws VaultException {
            if (candidate != role || actual != evidence || exact != proposal
                    || !proposal.matchesChangedEvidence(actual))
                throw new VaultException(PROOF_INVALID, "The changed host review is no longer exact");
            candidate.requireBound();
        }
    }

    static final class Proof {
        final String roleBinding;
        final int hop;
        final String connectionId;
        final String targetIdentity;
        final String host;
        final int port;
        final String keyType;
        final String fingerprint;
        final Classification classification;
        private final boolean changedCommitted;
        private final byte[] baseDigest;
        private final byte[] replacementDigest;

        private Proof(Evidence evidence) {
            this(evidence, false);
        }

        private Proof(Evidence evidence, boolean changedCommitted) {
            roleBinding = evidence.role.binding();
            hop = evidence.role.hop;
            connectionId = evidence.role.connectionId;
            targetIdentity = evidence.role.targetIdentity;
            host = evidence.role.host;
            port = evidence.role.port;
            keyType = evidence.keyType;
            fingerprint = evidence.fingerprint;
            classification = evidence.classification;
            this.changedCommitted = changedCommitted;
            baseDigest = evidence.baseDigest.clone();
            replacementDigest = evidence.replacementDigest.clone();
        }

        boolean permitsCredentials() {
            return classification == Classification.KNOWN || classification == Classification.NEW
                    || classification == Classification.CHANGED && changedCommitted;
        }

        void requireMatches(SshRoleContext role) throws VaultException {
            if (role == null || !role.binding().equals(roleBinding) || role.hop != hop
                    || !role.connectionId.equals(connectionId) || !role.targetIdentity.equals(targetIdentity)
                    || !role.host.equals(host) || role.port != port)
                throw new VaultException(PROOF_INVALID, "The host-key proof is bound to another hop");
        }

        /** Revalidate the complete evidence, not only the role coordinates. */
        void requireMatches(SshRoleContext role, Evidence evidence) throws VaultException {
            requireMatches(role);
            if (evidence == null || evidence.role != role || evidence.classification != classification
                    || !keyType.equals(evidence.keyType) || !fingerprint.equals(evidence.fingerprint)) {
                throw new VaultException(PROOF_INVALID, "The host-key proof is bound to another key");
            }
            byte[] currentBase = evidence.baseDigest();
            byte[] currentReplacement = evidence.replacementDigest();
            try {
                if (!MessageDigest.isEqual(baseDigest, currentBase)
                        || !MessageDigest.isEqual(replacementDigest, currentReplacement)) {
                    throw new VaultException(PROOF_INVALID, "The host-key proof digest changed");
                }
            } finally {
                VaultCodec.wipe(currentBase);
                VaultCodec.wipe(currentReplacement);
            }
        }
    }

    private final UnknownKeyIssuer unknownKeyIssuer;
    private final ChangedKeyIssuer changedKeyIssuer;

    SshHostKeyAuthority() {
        this(review -> { throw new VaultException("SSH_HOST_KEY_REVIEW", "An exact host-key review is required"); });
    }

    SshHostKeyAuthority(UnknownKeyIssuer unknownKeyIssuer) {
        this(unknownKeyIssuer, review -> {
            throw new VaultException("SSH_HOST_KEY_CHANGED", "The host key changed");
        });
    }

    SshHostKeyAuthority(UnknownKeyIssuer unknownKeyIssuer, ChangedKeyIssuer changedKeyIssuer) {
        this.unknownKeyIssuer = java.util.Objects.requireNonNull(unknownKeyIssuer);
        this.changedKeyIssuer = java.util.Objects.requireNonNull(changedKeyIssuer);
    }

    ChangedReview requireChangedApproval(SshRoleContext role, Evidence evidence,
            SshTrustProposal proposal, java.util.List<SshHostTrustMaterial.Record> oldRecords)
            throws VaultException {
        if (role == null || evidence == null || !evidence.matches(role))
            throw new VaultException(PROOF_INVALID, "The host-key evidence is bound to another hop");
        ChangedReview review = new ChangedReview(evidence, proposal, oldRecords);
        changedKeyIssuer.requireApproved(review);
        review.requireMatches(role, evidence, proposal);
        return review;
    }

    /** Only the exact provider's post-write, one-shot receipt can promote CHANGED. */
    Proof committedChanged(SshRoleContext role, Evidence evidence, SshTrustProposal proposal,
            SshManagedKnownHostsProvider.ChangedCommitReceipt receipt) throws VaultException {
        if (role == null || evidence == null || !evidence.matches(role)
                || evidence.classification != Classification.CHANGED || receipt == null)
            throw new VaultException(PROOF_INVALID, "The committed changed host proof is unavailable");
        receipt.claimProof(evidence, proposal);
        return new Proof(evidence, true);
    }

    Proof requireVerified(SshRoleContext role, Evidence evidence, SshTrustProposal proposal)
            throws VaultException {
        if (role == null || evidence == null || !evidence.matches(role))
            throw new VaultException(PROOF_INVALID, "The host-key evidence is bound to another hop");
        switch (evidence.classification) {
            case KNOWN:
                if (proposal != null) throw new VaultException("SSH_HOST_KEY_REVIEW", "A known key cannot authorize a trust rewrite");
                return new Proof(evidence);
            case NEW:
                if (proposal == null || !matches(proposal, evidence))
                    throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact unknown host-key review is required");
                Review review = new Review(evidence);
                unknownKeyIssuer.requireApproved(review);
                return new Proof(evidence);
            case CHANGED:
                throw new VaultException("SSH_HOST_KEY_CHANGED", "The host key changed");
            case REVOKED:
                throw new VaultException("SSH_HOST_KEY_REVOKED", "The host key is revoked");
            case CERTIFICATE:
                throw new VaultException("SSH_HOST_KEY_CERTIFICATE", "The host certificate needs a trusted issuer");
            case INVALID:
            default:
                throw new VaultException("SSH_HOST_KEY_INVALID", "The host-key evidence is invalid");
        }
    }

    private static boolean matches(SshTrustProposal proposal, Evidence evidence) throws VaultException {
        /* NEW-host approval is never valid for an unbound or merely
         * role-compatible proposal.  The proposal owns the full target and
         * digest comparison so authority and the CAS writer share one model. */
        return proposal.matchesEvidence(evidence);
    }

    private static String digest(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] digest = VaultCodec.digest(bytes);
        try { return hex(digest); }
        finally { VaultCodec.wipe(bytes); VaultCodec.wipe(digest); }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
