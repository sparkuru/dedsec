package im.majo.ctos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical private-key phrase ownership; metadata never grants a credential read. */
final class SshKeyPassphrase {
    static final String ALL_ID = "ctos-key-phrase-all";
    final String keyId, entryId, identity;

    SshKeyPassphrase(SshConnectionMaterial.Key key) {
        keyId = key.id;
        entryId = "ctos-key-phrase-" + SshConnectionMaterial.hex(VaultCodec.digest(keyId.getBytes(StandardCharsets.US_ASCII)));
        identity = "key:" + key.identity;
    }

    static byte[] bytes(String value, boolean empty) throws VaultException {
        if (value == null || value.length() > 8192 || !empty && value.isEmpty()) throw SshPasswordProfile.parameters();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw SshPasswordProfile.parameters();
            if (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))))
                throw SshPasswordProfile.parameters();
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length != 0) SshSavedPassword.validateSecret(bytes);
            return bytes;
        } catch (Throwable failure) { VaultCodec.wipe(bytes); throw failure; }
    }

    static VaultManifest.Entry entry(VaultManifest manifest, VaultSshMetadata.KeyBinding binding) throws VaultException {
        if (binding.passphraseEntryId == null) return null;
        SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(manifest, binding);
        SshKeyPassphrase expected = new SshKeyPassphrase(key);
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(binding.passphraseEntryId)) {
            if (entry.type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE || !entry.id.equals(expected.entryId)
                    || !entry.identity.equals(expected.identity) || !key.encrypted) throw SshConnectionMaterial.unsupported();
            SshSavedPassword.validateSecret(entry.secret);
            return entry;
        }
        throw SshConnectionMaterial.unsupported();
    }

    static void check(VaultManifest manifest) throws VaultException {
        Set<String> consumed = new HashSet<>();
        for (VaultSshMetadata.KeyBinding binding : manifest.keyBindings) {
            VaultManifest.Entry phrase = entry(manifest, binding);
            if (phrase != null && !consumed.add(phrase.id)) throw SshConnectionMaterial.unsupported();
        }
        for (VaultManifest.Entry entry : manifest.entries)
            if (entry.type == VaultManifest.EntryType.SSH_KEY_PASSPHRASE && !consumed.remove(entry.id))
                throw SshConnectionMaterial.unsupported();
        if (!consumed.isEmpty()) throw SshConnectionMaterial.unsupported();
    }

    static VaultManifest candidate(VaultManifest source, String keyId, boolean all, VaultManifest.Entry replacement)
            throws VaultException {
        SshConnectionMaterial.checkShape(source);
        SshConnectionMaterial.Key key = all ? null : SshConnectionMaterial.key(source, keyId);
        SshKeyPassphrase selected = key == null ? null : new SshKeyPassphrase(key);
        if (replacement != null && (all || !key.encrypted || !replacement.id.equals(selected.entryId)
                || !replacement.identity.equals(selected.identity)
                || replacement.type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE)) throw VaultException.invalid();
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        List<VaultSshMetadata.KeyBinding> bindings = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        try {
            for (VaultSshMetadata.KeyBinding binding : source.keyBindings) {
                boolean change = all || binding.id.equals(keyId);
                if (change && binding.passphraseEntryId != null) removed.add(binding.passphraseEntryId);
                bindings.add(change ? new VaultSshMetadata.KeyBinding(binding.id, binding.privateFileId,
                        binding.publicFileId, binding.certificateFileIds, replacement == null ? null : replacement.id) : binding);
            }
            for (VaultManifest.Entry old : source.entries) if (!removed.contains(old.id))
                entries.add(new VaultManifest.Entry(old.type, old.id, old.identity, old.label,
                        old.algorithm, old.digits, old.period, old.secret));
            if (replacement != null) entries.add(new VaultManifest.Entry(replacement.type, replacement.id,
                    replacement.identity, replacement.label, replacement.algorithm, replacement.digits,
                    replacement.period, replacement.secret));
            for (VaultManifest.FileObject file : source.files) files.add(new VaultManifest.FileObject(file.type,
                    file.id, file.path, file.identity, file.content, file.references));
            return new VaultManifest(entries, files, bindings, source.accounts);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    /** Display/shape classification only. Native policy validates the actual envelope before admission. */
    static boolean encrypted(byte[] body) {
        if (starts(body, "-----BEGIN RSA PRIVATE KEY-----") || starts(body, "-----BEGIN EC PRIVATE KEY-----")) {
            byte[] marker = "Proc-Type: 4,ENCRYPTED".getBytes(StandardCharsets.US_ASCII);
            for (int start = 0; start + marker.length <= Math.min(body.length, 256); start++) {
                boolean found = true;
                for (int i = 0; i < marker.length; i++) if (body[start + i] != marker[i]) { found = false; break; }
                if (found) return true;
            }
            return false;
        }
        String start = "-----BEGIN OPENSSH PRIVATE KEY-----";
        if (!starts(body, start)) return false;
        byte[] decoded = null;
        try (VaultCodec.SecretBuffer compact = new VaultCodec.SecretBuffer()) {
            for (int i = start.length(); i < body.length && body[i] != '-'; i++)
                if (body[i] != '\r' && body[i] != '\n') compact.write(body[i]);
            byte[] encoded = compact.toByteArray();
            try { decoded = Base64.getDecoder().decode(encoded); } finally { VaultCodec.wipe(encoded); }
            byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);
            if (decoded.length < magic.length + 4 || !Arrays.equals(magic, Arrays.copyOf(decoded, magic.length))) return false;
            ByteBuffer input = ByteBuffer.wrap(decoded); input.position(magic.length);
            int count = input.getInt();
            if (count < 1 || count > input.remaining()) return false;
            byte[] cipher = new byte[count]; input.get(cipher);
            return !Arrays.equals(cipher, "none".getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException invalid) { return false; }
        finally { VaultCodec.wipe(decoded); }
    }

    private static boolean starts(byte[] body, String prefix) {
        if (body.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (body[i] != prefix.charAt(i)) return false;
        return true;
    }
}
