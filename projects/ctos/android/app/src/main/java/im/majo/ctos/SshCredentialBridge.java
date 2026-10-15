package im.majo.ctos;

/**
 * Typed SSH secret bridge.  It does not parse prompts or terminal output and
 * cannot release a secret until the role, host proof and native KEX proof all
 * match the same vault operation.
 */
final class SshCredentialBridge {
    enum Kind { PASSWORD, KEY_PASSPHRASE, KEYBOARD_INTERACTIVE, PRIVATE_KEY }

    interface Receiver extends SshVaultAdapter.CredentialReceiver { }

    static final class Request implements AutoCloseable {
        final SshCredentialBridge owner;
        final VaultOperation operation;
        final SshRoleContext role;
        final SshHostKeyAuthority.Proof hostKey;
        final Kind kind;
        final String entryId;
        final String path;
        final String keyIdentity;
        private boolean closed;

        private Request(SshCredentialBridge owner, VaultOperation operation, SshRoleContext role,
                SshHostKeyAuthority.Proof hostKey, Kind kind, String entryId,
                String path, String keyIdentity) {
            this.owner = owner;
            this.operation = operation;
            this.role = role;
            this.hostKey = hostKey;
            this.kind = kind;
            this.entryId = entryId;
            this.path = path;
            this.keyIdentity = keyIdentity;
        }

        synchronized void requireOpen() throws VaultException {
            if (closed) throw new VaultException("VAULT_CANCELLED", "The SSH secret request is closed");
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.vault.cancelOperation(operation);
        }
    }

    private final AppSecrets vault;
    private final SshHostKeyAuthority hostAuthority;
    private final SshRoleContext.Clock clock;

    SshCredentialBridge(AppSecrets vault, SshHostKeyAuthority hostAuthority, SshRoleContext.Clock clock) {
        this.vault = java.util.Objects.requireNonNull(vault);
        this.hostAuthority = java.util.Objects.requireNonNull(hostAuthority);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    Request openCredential(SshRoleContext role, SshHostKeyAuthority.Evidence evidence,
            SshTrustProposal proposal, String entryId, Kind kind) throws VaultException {
        if (role == null || kind == null || kind == Kind.PRIVATE_KEY || entryId == null || entryId.isEmpty())
            throw new VaultException("PARAMETERS", "The SSH credential request is invalid");
        role.requireActive(clock);
        SshHostKeyAuthority.Proof hostKey = hostAuthority.requireVerified(role, evidence, proposal);
        return openCredential(role, hostKey, entryId, kind);
    }

    /**
     * Opens a one-shot request from an already verified host proof.  The
     * proof is deliberately used instead of reopening an SshTrustProposal:
     * NEW-host proposal bytes are closed as soon as the host callback returns.
     */
    Request openCredential(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            String entryId, Kind kind) throws VaultException {
        if (kind == null || kind == Kind.PRIVATE_KEY || entryId == null || entryId.isEmpty()
                || role == null || hostKey == null) {
            throw new VaultException("PARAMETERS", "The SSH credential request is invalid");
        }
        role.requireActive(clock);
        hostKey.requireMatches(role);
        if (hostKey.classification != SshHostKeyAuthority.Classification.KNOWN
                && hostKey.classification != SshHostKeyAuthority.Classification.NEW) {
            throw new VaultException("SSH_HOST_KEY_CHANGED", "The host-key proof cannot authorize credentials");
        }
        VaultManifest.EntryType expected = kind == Kind.KEY_PASSPHRASE
                ? VaultManifest.EntryType.SSH_KEY_PASSPHRASE : VaultManifest.EntryType.SSH_PASSWORD;
        VaultOperation operation = null;
        try {
            operation = vault.beginSshCredential(entryId, role.targetIdentity, role.connectionId, expected);
            operation.bindRole(role, hostKey);
            return new Request(this, operation, role, hostKey, kind, entryId, "", "");
        } catch (VaultException | RuntimeException | Error failure) {
            if (operation != null) vault.cancelOperation(operation);
            throw failure;
        }
    }

    Request openPrivateKey(SshRoleContext role, SshHostKeyAuthority.Evidence evidence,
            SshTrustProposal proposal, String path, String keyIdentity) throws VaultException {
        if (role == null || path == null || keyIdentity == null || path.isEmpty() || keyIdentity.isEmpty())
            throw new VaultException("PARAMETERS", "The SSH private-key request is invalid");
        role.requireActive(clock);
        SshHostKeyAuthority.Proof hostKey = hostAuthority.requireVerified(role, evidence, proposal);
        return openPrivateKey(role, hostKey, path, keyIdentity);
    }

    /** Opens a private-key request from the adapter's already bound host proof. */
    Request openPrivateKey(SshRoleContext role, SshHostKeyAuthority.Proof hostKey,
            String path, String keyIdentity) throws VaultException {
        if (role == null || hostKey == null || path == null || keyIdentity == null
                || path.isEmpty() || keyIdentity.isEmpty()) {
            throw new VaultException("PARAMETERS", "The SSH private-key request is invalid");
        }
        role.requireActive(clock);
        hostKey.requireMatches(role);
        if (hostKey.classification != SshHostKeyAuthority.Classification.KNOWN
                && hostKey.classification != SshHostKeyAuthority.Classification.NEW) {
            throw new VaultException("SSH_HOST_KEY_CHANGED", "The host-key proof cannot authorize credentials");
        }
        VaultOperation operation = null;
        try {
            operation = vault.beginSshPrivateKey(path, keyIdentity, role.targetIdentity, role.connectionId);
            operation.bindRole(role, hostKey);
            return new Request(this, operation, role, hostKey, Kind.PRIVATE_KEY, "", path, keyIdentity);
        } catch (VaultException | RuntimeException | Error failure) {
            if (operation != null) vault.cancelOperation(operation);
            throw failure;
        }
    }

    void release(Request request, SshKexProof kex, Receiver receiver) throws VaultException {
        if (request == null || request.owner != this || receiver == null)
            throw new VaultException("PARAMETERS", "The SSH secret request is invalid");
        try {
            request.requireOpen();
            if (kex == null)
                throw new VaultException("SSH_KEX_REQUIRED", "Signed SSH key exchange proof is required");
            final VaultOperation operation = request.operation;
            final AppSecrets.EffectGuard finalGuard = () -> {
                request.requireOpen();
                request.role.requireActive(clock);
                request.hostKey.requireMatches(request.role);
                kex.requireMatches(request.role, request.hostKey);
                operation.requireRole(request.role, request.hostKey);
            };
            // This first check gives callers a stable fail-closed result. The same
            // checks run again inside AppSecrets immediately before receiver use.
            finalGuard.check();
            if (request.kind == Kind.PRIVATE_KEY) {
                vault.sshPrivateKey(operation, request.path, request.keyIdentity,
                        request.role.targetIdentity, request.role.connectionId, finalGuard, receiver);
            } else {
                VaultManifest.EntryType expected = request.kind == Kind.KEY_PASSPHRASE
                        ? VaultManifest.EntryType.SSH_KEY_PASSPHRASE : VaultManifest.EntryType.SSH_PASSWORD;
                vault.sshCredential(operation, request.entryId, request.role.targetIdentity,
                        request.role.connectionId, expected, finalGuard, receiver);
            }
        } finally {
            request.close();
        }
    }
}
