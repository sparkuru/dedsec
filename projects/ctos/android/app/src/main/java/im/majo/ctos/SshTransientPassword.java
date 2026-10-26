package im.majo.ctos;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One selected password source. It never creates an entry or exposes a native/channel token. */
final class SshTransientPassword implements AutoCloseable {
    final String entryId = "once-" + UUID.randomUUID();
    private final AppSecrets vault;
    private final SshPasswordProfile profile;
    private final long deadline;
    private final AppSecrets.EffectGuard origin;
    private final SshRoleContext.Clock clock;
    private byte[] bytes;
    private SshRoleContext role;
    private SshAuthenticationSelection selection;
    private Thread owner;
    private boolean attempted, closed;

    SshTransientPassword(AppSecrets vault, SshPasswordProfile profile, byte[] password,
            long deadline, AppSecrets.EffectGuard origin, SshRoleContext.Clock clock) throws VaultException {
        if (vault == null || profile == null || password == null || password.length == 0
                || password.length > 8192 || origin == null || clock == null || deadline <= clock.now()
                || deadline - clock.now() > SshRoleContext.MAX_LIFETIME_MILLIS) throw invalid();
        for (byte value : password) if (value == 0) throw invalid();
        char[] decoded = new char[password.length];
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer target = CharBuffer.wrap(decoded);
            ByteBuffer input = ByteBuffer.wrap(password);
            // UNDERFLOW means complete input here, not an exception. Any error or overflow
            // is invalid; UTF-8 needs no more UTF-16 units than this byte-sized target.
            if (!decoder.decode(input, target, true).isUnderflow() || input.hasRemaining()) throw invalid();
            if (!decoder.flush(target).isUnderflow()) throw invalid();
        }
        finally { java.util.Arrays.fill(decoded, '\0'); }
        this.vault = vault; this.profile = profile; this.deadline = deadline;
        this.origin = origin; this.clock = clock; bytes = password.clone();
    }

    void bind(SshRoleContext candidate, SshAuthenticationSelection chosen) throws VaultException {
        try {
        origin.check();
        if (candidate == null || chosen == null) throw invalid();
        candidate.requireActive(clock);
        chosen.requireMatches(candidate, SshCredentialBridge.Kind.PASSWORD, clock);
        if (candidate.deadline > deadline || candidate.program != SshRoleContext.Program.CLIENT
                || candidate.hop != 0 || !profile.host.equals(candidate.host) || profile.port != candidate.port
                || !SshPasswordProfile.TARGET.equals(candidate.targetIdentity)
                || !profile.username.equals(chosen.username) || !entryId.equals(chosen.entryId)) throw invalid();
        synchronized (this) {
            if (closed || attempted || role != null) throw invalid();
            role = candidate; selection = chosen; owner = Thread.currentThread();
        }
        } catch (VaultException | RuntimeException | Error failure) { close(); throw failure; }
    }

    byte[] take(SshRoleContext candidate, SshHostKeyAuthority.Proof hostKey, SshKexProof kex,
            SshAuthenticationSelection chosen, AppSecrets.EffectGuard callbackGuard) throws VaultException {
        byte[][] output = new byte[1][];
        boolean delivered = false;
        try {
            if (callbackGuard == null || hostKey == null || kex == null) throw invalid();
            require(candidate, chosen);
            hostKey.requireMatches(candidate);
            kex.requireMatches(candidate, hostKey);
            synchronized (this) {
                if (attempted) throw invalid();
                attempted = true;
            }
            AppSecrets.EffectGuard guard = AppSecrets.boundGuard(callbackGuard, () -> {
                origin.check(); require(candidate, chosen);
                hostKey.requireMatches(candidate); kex.requireMatches(candidate, hostKey);
            });
            guard.check();
            vault.publishTransientSshPassword(candidate, guard, () -> {
                synchronized (this) {
                    if (closed || bytes.length == 0) throw invalid();
                    output[0] = bytes.clone();
                    VaultCodec.wipe(bytes);
                }
            });
            guard.check();
            delivered = true;
            return output[0];
        } finally {
            if (!delivered) VaultCodec.wipe(output[0]);
            close();
        }
    }

    private void require(SshRoleContext candidate, SshAuthenticationSelection chosen) throws VaultException {
        synchronized (this) {
            if (closed || role != candidate || selection != chosen || owner != Thread.currentThread()) throw invalid();
        }
        if (clock.now() >= deadline) throw invalid();
        candidate.requireActive(clock);
        chosen.requireMatches(candidate, SshCredentialBridge.Kind.PASSWORD, clock);
    }

    @Override public synchronized void close() { closed = true; VaultCodec.wipe(bytes); }

    private static VaultException invalid() {
        return new VaultException("SSH_CREDENTIAL_PROVIDER", "The one-use SSH password is unavailable");
    }
}
