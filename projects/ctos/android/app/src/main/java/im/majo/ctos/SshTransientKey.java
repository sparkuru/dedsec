package im.majo.ctos;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.UUID;

/** One selected private-key phrase, never a saved entry or reusable key capability. */
final class SshTransientKey implements AutoCloseable {
    final String entryId = "once-key-" + UUID.randomUUID();
    private final AppSecrets vault;
    private final SshConnectionMaterial.Key key;
    private final SshConnectionMaterial.KeyAccount account;
    private final long deadline;
    private final AppSecrets.EffectGuard origin;
    private final SshRoleContext.Clock clock;
    private byte[] phrase, bodyDigest;
    private SshRoleContext role;
    private SshAuthenticationSelection selection;
    private Thread owner;
    private boolean attempted, closed;

    SshTransientKey(AppSecrets vault, SshPasswordProfile profile, SshConnectionMaterial.Key key,
            byte[] privateBody, byte[] phrase, long deadline, AppSecrets.EffectGuard origin,
            SshRoleContext.Clock clock) throws VaultException {
        SshSavedPassword.validateSecret(phrase);
        if (vault == null || profile == null || key == null || !key.encrypted || privateBody == null
                || origin == null || clock == null || deadline <= clock.now()
                || deadline - clock.now() > SshRoleContext.MAX_LIFETIME_MILLIS) throw invalid();
        this.vault = vault; this.key = key; account = new SshConnectionMaterial.KeyAccount(profile, key);
        this.deadline = deadline; this.origin = origin; this.clock = clock;
        this.phrase = phrase.clone(); bodyDigest = VaultCodec.digest(privateBody);
    }

    void bind(SshRoleContext candidate, SshAuthenticationSelection chosen) throws VaultException {
        try {
            origin.check(); candidate.requireActive(clock);
            chosen.requireMatches(candidate, SshCredentialBridge.Kind.PRIVATE_KEY, clock);
            byte[] expected = key.publicBlob(), actual = chosen.publicKeyBlob();
            try {
                if (candidate.deadline > deadline || candidate.program != SshRoleContext.Program.CLIENT
                        || candidate.hop != 0 || !account.target.equals(candidate.targetIdentity)
                        || !account.profile.host.equals(candidate.host) || account.profile.port != candidate.port
                        || !account.profile.username.equals(chosen.username) || !entryId.equals(chosen.entryId)
                        || !key.path.equals(chosen.path) || !key.identity.equals(chosen.keyIdentity)
                        || !MessageDigest.isEqual(expected, actual)) throw invalid();
            } finally { VaultCodec.wipe(expected); VaultCodec.wipe(actual); }
            synchronized (this) {
                if (closed || attempted || role != null) throw invalid();
                role = candidate; selection = chosen; owner = Thread.currentThread();
            }
        } catch (VaultException | RuntimeException | Error failure) { close(); throw failure; }
    }

    void requireSelection(SshAuthenticationSelection chosen) throws VaultException {
        synchronized (this) {
            if (closed || selection != chosen || role != chosen.role || owner != Thread.currentThread()) throw invalid();
        }
        if (clock.now() >= deadline) throw invalid();
        origin.check(); chosen.requireMatches(role, SshCredentialBridge.Kind.PRIVATE_KEY, clock);
    }

    byte[] take(SshRoleContext candidate, SshHostKeyAuthority.Proof hostKey, SshKexProof kex,
            SshAuthenticationSelection chosen, AppSecrets.EffectGuard callback) throws VaultException {
        byte[][] output = new byte[1][];
        AppSecrets.SshSecretReadContext context = null;
        VaultOperation operation = null;
        boolean delivered = false;
        try {
            if (callback == null || hostKey == null || kex == null || candidate != role) throw invalid();
            AppSecrets.EffectGuard guard = AppSecrets.boundGuard(callback, () -> {
                requireSelection(chosen); hostKey.requireMatches(candidate); kex.requireMatches(candidate, hostKey);
            });
            guard.check();
            synchronized (this) { if (attempted) throw invalid(); attempted = true; }
            context = vault.newTransientKeyReadContext(deadline, guard, chosen, this);
            operation = vault.beginSshPrivateKey(key.path, key.identity, candidate.targetIdentity, candidate.connectionId, context);
            operation.bindRole(candidate, hostKey);
            final VaultOperation exact = operation;
            AppSecrets.SshRoleGuard release = new AppSecrets.SshRoleGuard() {
                @Override public void check() throws VaultException { guard.check(); exact.requireRole(candidate, hostKey); }
                @Override public void publish(AppSecrets.EffectGuard copy) throws VaultException {
                    guard.publish(() -> { check(); copy.check(); });
                }
                @Override public void withRole(AppSecrets.EffectGuard copy) throws VaultException {
                    candidate.publishBound(clock, copy);
                }
            };
            vault.sshPrivateKey(operation, key.path, key.identity, candidate.targetIdentity, candidate.connectionId,
                    release, body -> {
                        byte[] actual = VaultCodec.digest(body);
                        try {
                            synchronized (this) {
                                if (closed || selection != chosen || role != candidate || owner != Thread.currentThread())
                                    throw invalid();
                                if (!MessageDigest.isEqual(bodyDigest, actual) || phrase == null || phrase.length == 0
                                        || body.length + phrase.length + 16 > VaultManifest.MAX_FILE_BYTES) throw invalid();
                                output[0] = new byte[16 + body.length + phrase.length];
                                ByteBuffer.wrap(output[0]).putInt(0x43534b42).putInt(1)
                                        .putInt(body.length).put(body).putInt(phrase.length).put(phrase);
                            }
                        } finally { VaultCodec.wipe(actual); }
                    });
            guard.check(); delivered = true; return output[0];
        } finally {
            vault.cancelOperation(operation); if (context != null) context.cancel();
            if (!delivered) VaultCodec.wipe(output[0]); close();
        }
    }

    @Override public synchronized void close() {
        closed = true; VaultCodec.wipe(phrase); VaultCodec.wipe(bodyDigest); phrase = bodyDigest = null;
    }
    private static VaultException invalid() {
        return new VaultException("SSH_CREDENTIAL_PROVIDER", "The one-use private-key passphrase is unavailable");
    }
}
