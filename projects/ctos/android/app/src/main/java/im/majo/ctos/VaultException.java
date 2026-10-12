package im.majo.ctos;

/** Stable, secret-free errors across the vault boundary. */
final class VaultException extends Exception {
    final String code;

    VaultException(String code, String message) {
        super(message);
        this.code = code;
    }

    static VaultException invalid() {
        return new VaultException("VAULT_CONTENT", "The backup contains unsupported or invalid content");
    }

    static VaultException authentication() {
        return new VaultException("VAULT_AUTHENTICATION", "Incorrect password or damaged file");
    }
}
