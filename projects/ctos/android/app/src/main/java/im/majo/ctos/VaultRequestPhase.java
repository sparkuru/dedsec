package im.majo.ctos;

/** Tiny request mutex orders cancellation against effect claims; no file/crypto work runs under it. */
final class VaultRequestPhase {
    private volatile boolean cancelled;
    private volatile boolean committing;
    private volatile boolean exporting;

    synchronized void cancel() { cancelled = true; }
    boolean cancelled() { return cancelled; }
    boolean committing() { return committing; }

    synchronized void claimCommit(AppSecrets.EffectGuard permission) throws VaultException {
        requireActive();
        permission.check();
        committing = true;
    }

    synchronized void claimExport(AppSecrets.EffectGuard permission) throws VaultException {
        requireActive();
        permission.check();
        exporting = true;
    }

    synchronized void publish(AppSecrets.EffectGuard publication) throws VaultException {
        requireActive();
        publication.check();
    }

    VaultException interrupted(VaultException failure) {
        if (committing) return new VaultException("VAULT_COMMIT_UNCERTAIN",
                "A vault commit may have completed; the vault is locked. Reopen with the master password");
        if (exporting) return VaultExportWriter.uncertain();
        return failure;
    }

    private void requireActive() throws VaultException {
        if (cancelled) throw new VaultException("VAULT_CANCELLED", "Operation was cancelled");
    }
}
