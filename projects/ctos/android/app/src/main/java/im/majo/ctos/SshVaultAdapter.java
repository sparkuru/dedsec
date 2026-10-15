package im.majo.ctos;

import java.util.List;

/** Native-only adapter. A native host-key gate is mandatory before asking for saved credentials. */
final class SshVaultAdapter {
    interface HostKeyGate { void requireVerified(String connectionId, String targetIdentity) throws VaultException; }
    interface TrustProposalGate { void requireApproved(SshTrustProposal exactProposal) throws VaultException; }
    interface ExactBundleApproval { void requireApproved(SshBundleProposal exactProposal) throws VaultException; }
    interface CredentialReceiver { void receive(byte[] credential) throws VaultException; }
    interface FileReceiver { void receive(List<VaultManifest.FileObject> files) throws VaultException; }
    interface FileSnapshotReceiver { void receive(SshFileSnapshot snapshot) throws VaultException; }
    private final AppSecrets vault;
    private final HostKeyGate hostKeyGate;
    private final TrustProposalGate trustGate;
    private final ExactBundleApproval bundleGate;

    SshVaultAdapter(AppSecrets vault, HostKeyGate hostKeyGate) {
        this(vault, hostKeyGate, proposal -> {
            throw new VaultException("VAULT_TRUST_REVIEW", "An exact host trust change requires approval");
        });
    }
    SshVaultAdapter(AppSecrets vault, HostKeyGate hostKeyGate, TrustProposalGate trustGate) {
        this(vault, hostKeyGate, trustGate, proposal -> {
            throw new VaultException("VAULT_TRUST_REVIEW", "Review and approve the exact managed file bundle");
        });
    }
    SshVaultAdapter(AppSecrets vault, HostKeyGate hostKeyGate, TrustProposalGate trustGate, ExactBundleApproval bundleGate) {
        this.vault = vault;
        this.hostKeyGate = hostKeyGate;
        this.trustGate = trustGate;
        this.bundleGate = java.util.Objects.requireNonNull(bundleGate);
    }

    void cancel(VaultOperation request) { vault.cancelOperation(request); }

    VaultOperation bundleRequest(SshBundleProposal proposal) throws VaultException {
        proposal.requireOpen();
        bundleGate.requireApproved(proposal);
        return vault.beginSshBundle(proposal);
    }

    void commitBundle(VaultOperation request, SshBundleProposal proposal, AppSecrets.EffectGuard guard)
            throws VaultException {
        proposal.requireOpen();
        bundleGate.requireApproved(proposal);
        vault.commitSshBundle(request, proposal, AppSecrets.boundGuard(guard, () -> {
            proposal.requireOpen();
            bundleGate.requireApproved(proposal);
        }));
    }

    VaultOperation credentialRequest(String entryId, String targetIdentity, String connectionId) throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        return vault.beginSshCredential(entryId, targetIdentity, connectionId);
    }

    void useCredential(VaultOperation request, String entryId, String targetIdentity, String connectionId,
            CredentialReceiver receiver) throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        vault.sshCredential(request, entryId, targetIdentity, connectionId,
                () -> hostKeyGate.requireVerified(connectionId, targetIdentity), receiver);
    }

    VaultOperation fileRequest(String rootPath, String connectionId) throws VaultException {
        return vault.beginSshFiles(rootPath, connectionId);
    }

    void useFiles(VaultOperation request, String rootPath, String connectionId, FileReceiver receiver) throws VaultException {
        vault.sshFiles(request, rootPath, connectionId, receiver);
    }

    void useFileSnapshot(VaultOperation request, String rootPath, String connectionId, FileSnapshotReceiver receiver)
            throws VaultException {
        vault.sshFileSnapshot(request, rootPath, connectionId, receiver);
    }

    SshPublicFileLease openPublicFiles(VaultOperation request, String rootPath, String connectionId)
            throws VaultException {
        return vault.sshPublicFiles(request, rootPath, connectionId);
    }

    VaultOperation privateKeyRequest(String path, String keyIdentity, String targetIdentity, String connectionId)
            throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        return vault.beginSshPrivateKey(path, keyIdentity, targetIdentity, connectionId);
    }

    void usePrivateKey(VaultOperation request, String path, String keyIdentity, String targetIdentity,
            String connectionId, CredentialReceiver receiver) throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        vault.sshPrivateKey(request, path, keyIdentity, targetIdentity, connectionId,
                () -> hostKeyGate.requireVerified(connectionId, targetIdentity), receiver);
    }

    VaultOperation saveRequest(String entryId, String targetIdentity, String connectionId) throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        return vault.beginSshSave(entryId, targetIdentity, connectionId);
    }

    void save(VaultOperation request, String entryId, String targetIdentity, String connectionId, String label,
            boolean keyPassphrase, byte[] credential, AppSecrets.EffectGuard guard) throws VaultException {
        hostKeyGate.requireVerified(connectionId, targetIdentity);
        vault.saveSsh(request, entryId, targetIdentity, connectionId, label, keyPassphrase, credential, AppSecrets.boundGuard(guard, () -> {
            hostKeyGate.requireVerified(connectionId, targetIdentity);
        }));
    }

    VaultOperation deleteRequest(String entryId, String targetIdentity) throws VaultException {
        return vault.beginSshDelete(entryId, targetIdentity);
    }
    void delete(VaultOperation request, String entryId, String targetIdentity, AppSecrets.EffectGuard guard)
            throws VaultException { vault.deleteSsh(request, entryId, targetIdentity, guard); }

    VaultOperation trustRequest(SshTrustProposal proposal) throws VaultException {
        proposal.requireOpen();
        hostKeyGate.requireVerified(proposal.connectionId, proposal.targetIdentity);
        trustGate.requireApproved(proposal);
        return vault.beginTrustUpdate(proposal);
    }
    void updateTrust(VaultOperation request, SshTrustProposal proposal, AppSecrets.EffectGuard guard) throws VaultException {
        proposal.requireOpen();
        hostKeyGate.requireVerified(proposal.connectionId, proposal.targetIdentity);
        trustGate.requireApproved(proposal);
        vault.updateTrust(request, proposal, AppSecrets.boundGuard(guard, () -> {
            hostKeyGate.requireVerified(proposal.connectionId, proposal.targetIdentity);
            trustGate.requireApproved(proposal);
        }));
    }

    /**
     * Role/proof-bound trust admission for the concrete managed known_hosts
     * provider.  The legacy overload above remains a compatibility seam for
     * existing package tests; it is not sufficient for the production path.
     */
    VaultOperation trustRequest(SshRoleContext role, SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof hostKey, SshManagedKnownHostsProvider.SnapshotReceipt snapshot,
            SshTrustProposal proposal) throws VaultException {
        requireTrustBinding(role, evidence, hostKey, snapshot, proposal);
        VaultOperation request = null;
        try {
            request = vault.beginTrustUpdate(proposal);
            request.bindRole(role, hostKey);
            request.bindTrust(snapshot, role, hostKey, evidence, proposal);
            return request;
        } catch (VaultException | RuntimeException | Error failure) {
            if (request != null) vault.cancelOperation(request);
            throw failure;
        }
    }

    /**
     * Final guarded effect for the concrete provider.  Every exact field is
     * checked before AppSecrets consumes the operation and again immediately
     * before its store commit guard runs.
     */
    void updateTrust(VaultOperation request, SshRoleContext role,
            SshHostKeyAuthority.Evidence evidence, SshHostKeyAuthority.Proof hostKey,
            SshManagedKnownHostsProvider.SnapshotReceipt snapshot, SshTrustProposal proposal,
            AppSecrets.EffectGuard guard) throws VaultException {
        requireTrustBinding(role, evidence, hostKey, snapshot, proposal);
        if (request == null) throw new VaultException("VAULT_OPERATION", "Secret operation is unavailable");
        request.requireTrust(snapshot, role, hostKey, evidence, proposal);
        vault.updateTrust(request, proposal, AppSecrets.boundGuard(guard, () -> {
            requireTrustBinding(role, evidence, hostKey, snapshot, proposal);
            request.requireTrust(snapshot, role, hostKey, evidence, proposal);
        }));
    }

    private void requireTrustBinding(SshRoleContext role, SshHostKeyAuthority.Evidence evidence,
            SshHostKeyAuthority.Proof hostKey, SshManagedKnownHostsProvider.SnapshotReceipt snapshot,
            SshTrustProposal proposal) throws VaultException {
        if (role == null || evidence == null || hostKey == null || snapshot == null || proposal == null
                || evidence.classification != SshHostKeyAuthority.Classification.NEW) {
            throw new VaultException("SSH_HOST_KEY_CAS", "The exact managed host-key CAS is unavailable");
        }
        role.requireBound();
        snapshot.requireLive();
        snapshot.requireMatches(role, evidence, proposal);
        hostKey.requireMatches(role, evidence);
        if (!proposal.matchesEvidence(evidence)) {
            throw new VaultException("SSH_HOST_KEY_REVIEW", "The exact unknown host-key review is required");
        }
        hostKeyGate.requireVerified(evidence.role.connectionId, evidence.role.targetIdentity);
        trustGate.requireApproved(proposal);
    }
}
