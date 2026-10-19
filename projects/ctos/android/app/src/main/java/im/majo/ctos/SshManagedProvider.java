package im.majo.ctos;

/**
 * Package-private managed SSH provider seam.  It owns the mapping from a
 * native hop to an exact trust proposal and to one typed vault request; it is
 * never a prompt, terminal-output, or UI provider.
 */
final class SshManagedProvider {
    interface TrustProposalSource {
        SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence) throws VaultException;

        /** Optional proof-aware overload for a concrete managed provider. */
        default SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
                SshHostKeyAuthority.Proof proof) throws VaultException {
            return proposalFor(evidence);
        }
    }

    /** Performs the one exact known_hosts CAS selected by the approved proposal. */
    interface TrustCasWriter {
        SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
                SshTrustProposal proposal) throws VaultException;

        /** Optional proof-aware overload for a concrete managed provider. */
        default SshTrustProposal.CasResult write(SshHostKeyAuthority.Evidence evidence,
                SshHostKeyAuthority.Proof proof, SshTrustProposal proposal) throws VaultException {
            return write(evidence, proposal);
        }
    }

    /**
     * Selects a request using the adapter's already verified host proof.  A
     * source must use the proof-bound SshCredentialBridge overloads rather
     * than trying to retain or recreate a closed NEW-host proposal.
     */
    interface CredentialRequestSource {
        SshCredentialBridge.Request open(SshCredentialBridge bridge, SshRoleContext role,
                SshHostKeyAuthority.Proof hostKey, SshCredentialBridge.Kind kind)
                throws VaultException;
    }

    private final SshCredentialBridge credentialBridge;
    private final SshRoleContext.Clock clock;
    private final TrustProposalSource trustSource;
    private final TrustCasWriter casWriter;
    private final CredentialRequestSource credentialSource;

    SshManagedProvider(SshCredentialBridge credentialBridge, SshRoleContext.Clock clock,
            TrustProposalSource trustSource, CredentialRequestSource credentialSource) {
        this(credentialBridge, clock, trustSource, null, credentialSource);
    }

    SshManagedProvider(SshCredentialBridge credentialBridge, SshRoleContext.Clock clock,
            TrustProposalSource trustSource, TrustCasWriter casWriter,
            CredentialRequestSource credentialSource) {
        this.credentialBridge = java.util.Objects.requireNonNull(credentialBridge);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.trustSource = trustSource;
        this.casWriter = casWriter;
        this.credentialSource = credentialSource;
    }

    /** The native candidate receiver and proposal/CAS writer must be one object. */
    boolean ownsNativeCandidateProvider(SshManagedKnownHostsProvider provider) {
        return provider != null && trustSource == provider && casWriter == provider;
    }

    /** Returns one exact NEW-host proposal; the adapter closes it after review. */
    SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence) throws VaultException {
        return proposalFor(evidence, null);
    }

    SshTrustProposal proposalFor(SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof proof) throws VaultException {
        if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "An exact unknown host-key review is required");
        }
        evidence.role.requireActive(clock);
        if (trustSource == null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "An exact unknown host-key review is required");
        }
        SshTrustProposal proposal = trustSource.proposalFor(evidence, proof);
        if (proposal == null) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "An exact unknown host-key review is required");
        }
        try {
            if (!proposal.matchesEvidence(evidence)) {
                proposal.close();
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "An exact unknown host-key review is required");
            }
            return proposal;
        } catch (VaultException | RuntimeException | Error failure) {
            proposal.close();
            throw failure;
        }
    }

    /**
     * Commits exactly the proposal selected for this NEW host.  A provider
     * without a CAS writer is intentionally unavailable: approval alone is
     * never an implicit trust or write-back.
     */
    SshTrustProposal.CasResult writeBack(SshHostKeyAuthority.Evidence evidence,
            SshTrustProposal proposal) throws VaultException {
        return writeBack(evidence, null, proposal);
    }

    SshTrustProposal.CasResult writeBack(SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof proof, SshTrustProposal proposal) throws VaultException {
        if (proposal == null) {
            throw new VaultException("SSH_HOST_KEY_CAS", "The exact host-key write-back is unavailable");
        }
        try {
            if (evidence == null || evidence.classification != SshHostKeyAuthority.Classification.NEW) {
                throw new VaultException("SSH_HOST_KEY_CAS",
                        "The exact host-key write-back is unavailable");
            }
            evidence.role.requireActive(clock);
            if (proof != null) proof.requireMatches(evidence.role, evidence);
            if (!proposal.matchesEvidence(evidence)) {
                throw new VaultException("SSH_HOST_KEY_REVIEW",
                        "The exact unknown host-key review is required");
            }
            if (casWriter == null) {
                throw new VaultException("SSH_HOST_KEY_CAS", "The exact host-key write-back is unavailable");
            }
            SshTrustProposal.CasResult result = casWriter.write(evidence, proof, proposal);
            evidence.role.requireActive(clock);
            if (result == null || !result.matches(evidence, proposal,
                    SshTrustProposal.CasStatus.COMMITTED)) {
                throw new VaultException("SSH_HOST_KEY_CAS", "The exact host-key write-back failed");
            }
            return result;
        } finally {
            // The receipt is validated while the proposal is open; only then
            // may the body be wiped.  This also closes it on every rejection.
            proposal.close();
        }
    }

    /**
     * Releases exactly one bounded typed secret through AppSecrets.  The
     * proof checks happen before request selection and are repeated by
     * SshCredentialBridge immediately before the receiver is called.
     */
    byte[] credentialBytes(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            SshKexProof kex, SshCredentialBridge.Kind kind) throws VaultException {
        if (role == null || hostKey == null || kex == null || kind == null) {
            throw new VaultException("SSH_CREDENTIAL_PROVIDER", "The managed SSH credential provider is unavailable");
        }
        role.requireActive(clock);
        hostKey.requireMatches(role);
        kex.requireMatches(role, hostKey);
        if (credentialSource == null) {
            throw new VaultException("SSH_CREDENTIAL_PROVIDER", "The managed SSH credential provider is unavailable");
        }

        return releaseBytes(credentialSource.open(credentialBridge, role, hostKey, kind), kex, kind, null, null);
    }

    byte[] credentialBytes(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            SshKexProof kex, SshCredentialBridge.Kind kind, SshAuthenticationSelection selection)
            throws VaultException {
        return credentialBytes(role, hostKey, kex, kind, selection, null);
    }

    byte[] credentialBytes(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            SshKexProof kex, SshCredentialBridge.Kind kind, SshAuthenticationSelection selection,
            AppSecrets.EffectGuard effectGuard) throws VaultException {
        if (selection == null) throw SshAuthenticationSelection.invalid();
        selection.requireMatches(role, kind, clock);
        if (hostKey == null || kex == null) {
            throw new VaultException("SSH_KEX_REQUIRED", "Signed SSH key exchange proof is required");
        }
        hostKey.requireMatches(role);
        kex.requireMatches(role, hostKey);
        return releaseBytes(credentialBridge.openSelection(role, hostKey, selection), kex, kind,
                selection, effectGuard);
    }

    private byte[] releaseBytes(SshCredentialBridge.Request request, SshKexProof kex,
            SshCredentialBridge.Kind kind, SshAuthenticationSelection selection,
            AppSecrets.EffectGuard effectGuard) throws VaultException {
        byte[] result = null;
        final byte[][] handoff = new byte[1][];
        try {
            if (request == null) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential request is unavailable");
            }
            if (request.kind != kind) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential kind does not match the native request");
            }
            credentialBridge.release(request, kex, selection, effectGuard, credential -> {
                if (credential == null || credential.length == 0
                        || credential.length > VaultManifest.MAX_FILE_BYTES) {
                    throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                            "The managed SSH credential is outside its bounded handoff");
                }
                handoff[0] = credential.clone();
            });
            result = handoff[0];
            if (result == null) {
                throw new VaultException("SSH_CREDENTIAL_PROVIDER",
                        "The managed SSH credential is unavailable");
            }
            return result;
        } catch (VaultException | RuntimeException | Error failure) {
            if (result != null) VaultCodec.wipe(result);
            if (handoff[0] != null && handoff[0] != result) VaultCodec.wipe(handoff[0]);
            throw failure;
        } finally {
            if (request != null) request.close();
        }
    }
}
