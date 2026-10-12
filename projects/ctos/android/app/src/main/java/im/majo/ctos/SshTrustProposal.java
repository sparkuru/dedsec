package im.majo.ctos;

import java.nio.charset.StandardCharsets;

/** Exact native trust change; approval gates must validate host identity and reject unrelated rows. */
final class SshTrustProposal implements AutoCloseable {
    final String path;
    final String objectId;
    final String targetIdentity;
    final String connectionId;
    final String host;
    final int port;
    final String fingerprint;
    private final byte[] baseDigest;
    private final byte[] content;
    final String binding;
    private boolean closed;

    SshTrustProposal(String path, String objectId, String targetIdentity, String connectionId, String host,
            int port, String fingerprint, byte[] baseDigest, byte[] newContent) throws VaultException {
        VaultManifest.path(path);
        if (objectId.isEmpty() || connectionId.isEmpty() || targetIdentity.isEmpty() || port < 1 || port > 65535
                || !host.matches("[A-Za-z0-9.:%\\[\\]-]{1,253}")
                || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}") || baseDigest.length != 32
                || newContent.length > VaultManifest.MAX_FILE_BYTES) throw VaultException.invalid();
        this.path = path;
        this.objectId = objectId;
        this.targetIdentity = targetIdentity;
        this.connectionId = connectionId;
        this.host = host;
        this.port = port;
        this.fingerprint = fingerprint;
        this.baseDigest = baseDigest.clone();
        content = newContent.clone();
        String fields = path + '\0' + objectId + '\0' + targetIdentity + '\0' + connectionId + '\0'
                + host + '\0' + port + '\0' + fingerprint + '\0' + hex(baseDigest) + '\0' + hex(VaultCodec.digest(content));
        binding = hex(VaultCodec.digest(fields.getBytes(StandardCharsets.UTF_8)));
    }
    synchronized byte[] baseDigest() throws VaultException { requireOpen(); return baseDigest.clone(); }
    synchronized byte[] newContent() throws VaultException { requireOpen(); return content.clone(); }
    synchronized byte[] newDigest() throws VaultException { requireOpen(); return VaultCodec.digest(content); }
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
