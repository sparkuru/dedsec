package im.majo.ctos;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Immutable managed receipt. Expected public bytes are a declaration, not a parsed-key proof. */
final class SshAuthenticationSelection {
    static final int WIRE_MAGIC = 0x43534153; // CSAS
    static final int MAX_WIRE_BYTES = VaultManifest.MAX_FILE_BYTES + 2048;
    final SshRoleContext role;
    final String username;
    final SshCredentialBridge.Kind kind;
    final String entryId;
    final String path;
    final String keyIdentity;
    private final byte[] usernameBytes;
    private final byte[] publicKeyBlob;

    private SshAuthenticationSelection(SshRoleContext role, String username,
            SshCredentialBridge.Kind kind, String entryId, String path, String keyIdentity,
            byte[] publicKeyBlob, SshRoleContext.Clock clock) throws VaultException {
        if (role == null || clock == null) throw invalid();
        role.requireActive(clock);
        this.usernameBytes = strictText(username, 255);
        this.role = role;
        this.username = username;
        this.kind = kind;
        this.entryId = entryId;
        this.path = path;
        this.keyIdentity = keyIdentity;
        this.publicKeyBlob = publicKeyBlob.clone();
    }

    static SshAuthenticationSelection password(SshRoleContext role, String username,
            String entryId, SshRoleContext.Clock clock) throws VaultException {
        if (entryId == null || !entryId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        return new SshAuthenticationSelection(role, username, SshCredentialBridge.Kind.PASSWORD,
                entryId, "", "", new byte[0], clock);
    }

    static SshAuthenticationSelection privateKey(SshRoleContext role, String username,
            String path, String keyIdentity, byte[] publicKeyBlob, SshRoleContext.Clock clock)
            throws VaultException {
        return privateKey(role, username, path, keyIdentity, publicKeyBlob, "", clock);
    }

    static SshAuthenticationSelection privateKey(SshRoleContext role, String username,
            String path, String keyIdentity, byte[] publicKeyBlob, String passphraseEntryId,
            SshRoleContext.Clock clock) throws VaultException {
        if (passphraseEntryId == null || !passphraseEntryId.isEmpty()
                && !passphraseEntryId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        strictText(path, 512);
        strictText(keyIdentity, 512);
        try { VaultManifest.path(path); }
        catch (VaultException failure) { throw invalid(); }
        if (publicKeyBlob == null || publicKeyBlob.length == 0
                || publicKeyBlob.length > VaultManifest.MAX_FILE_BYTES) throw invalid();
        return new SshAuthenticationSelection(role, username, SshCredentialBridge.Kind.PRIVATE_KEY,
                passphraseEntryId, path, keyIdentity, publicKeyBlob, clock);
    }

    byte[] usernameBytes() { return usernameBytes.clone(); }
    byte[] publicKeyBlob() { return publicKeyBlob.clone(); }

    /** Canonical metadata only; this digestable record grants no credential authority. */
    byte[] encode(long roleToken, SshRoleContext.Clock clock) throws VaultException {
        if (roleToken <= 0) throw invalid();
        requireMatches(role, kind, clock);
        byte[] nonce = null;
        byte[] binding = null;
        byte[] entry = null;
        byte[] privatePath = null;
        byte[] identity = null;
        try {
            nonce = role.roleNonce();
            binding = role.connectionBinding();
            entry = entryId.getBytes(StandardCharsets.UTF_8);
            privatePath = path.getBytes(StandardCharsets.UTF_8);
            identity = keyIdentity.getBytes(StandardCharsets.UTF_8);
            int length = 108 + usernameBytes.length + entry.length + privatePath.length
                    + identity.length + publicKeyBlob.length;
            if (length > MAX_WIRE_BYTES) throw invalid();
            ByteBuffer wire = ByteBuffer.allocate(length);
            wire.putInt(WIRE_MAGIC).putShort((short) (kind == SshCredentialBridge.Kind.PRIVATE_KEY
                    && !entryId.isEmpty() ? 2 : 1)).putShort((short) 0)
                    .putLong(roleToken).putInt(role.hop).put(nonce).put(binding)
                    .putInt(kind == SshCredentialBridge.Kind.PASSWORD ? 1 : 4);
            for (byte[] field : new byte[][] { usernameBytes, entry, privatePath, identity, publicKeyBlob }) {
                wire.putInt(field.length).put(field);
            }
            return wire.array();
        } finally {
            VaultCodec.wipe(nonce);
            VaultCodec.wipe(binding);
            VaultCodec.wipe(entry);
            VaultCodec.wipe(privatePath);
            VaultCodec.wipe(identity);
        }
    }

    void requireMatches(SshRoleContext candidate, SshCredentialBridge.Kind requested,
            SshRoleContext.Clock clock) throws VaultException {
        if (candidate != role || requested != kind) throw invalid();
        role.requireActive(clock);
    }

    private static byte[] strictText(String text, int maximum) throws VaultException {
        if (text == null || text.isEmpty() || text.length() > maximum
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (encoded.remaining() > maximum) throw invalid();
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) { throw invalid(); }
    }

    static VaultException invalid() {
        return new VaultException("SSH_AUTH_SELECTION", "The SSH authentication selection does not match");
    }
}
