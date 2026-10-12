package im.majo.ctos;

/** TOTP only produces a current code; it never exports a seed or the vault key. */
final class TotpVaultAdapter {
    private final AppSecrets vault;
    TotpVaultAdapter(AppSecrets vault) { this.vault = vault; }
    void cancel(VaultOperation request) { vault.cancelOperation(request); }

    VaultOperation request(String entryId) throws VaultException { return vault.beginTotp(entryId); }
    String currentCode(VaultOperation request, String entryId) throws VaultException {
        return vault.totpCode(request, entryId, System.currentTimeMillis() / 1000);
    }

    VaultOperation saveRequest(String entryId) throws VaultException { return vault.beginTotpSave(entryId); }
    void save(VaultOperation request, String entryId, String issuerAccount, String label, byte[] seed,
            AppSecrets.EffectGuard guard) throws VaultException {
        vault.saveTotp(request, entryId, issuerAccount, label, seed, guard);
    }
    VaultOperation deleteRequest(String entryId) throws VaultException { return vault.beginTotpDelete(entryId); }
    void delete(VaultOperation request, String entryId, AppSecrets.EffectGuard guard) throws VaultException {
        vault.deleteTotp(request, entryId, guard);
    }
}
