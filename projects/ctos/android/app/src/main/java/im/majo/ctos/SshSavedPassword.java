package im.majo.ctos;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical saved-account declarations; possession of metadata grants no credential access. */
final class SshSavedPassword {
    static final String TARGET_PREFIX = "target:ctos-password-";
    static final String ALL_ID = "ctos-password-all";
    final SshPasswordProfile profile;
    final String entryId, accountId, target;

    SshSavedPassword(SshPasswordProfile profile) {
        this.profile = profile;
        byte[] host = profile.host.getBytes(StandardCharsets.UTF_8);
        byte[] user = profile.username.getBytes(StandardCharsets.UTF_8);
        byte[] framed = ByteBuffer.allocate(16 + host.length + user.length).putInt(0x43535057)
                .putInt(host.length).put(host).putInt(profile.port).putInt(user.length).put(user).array();
        byte[] digest = VaultCodec.digest(framed);
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            entryId = "ctos-password-secret-" + hex;
            accountId = "ctos-password-account-" + hex;
            target = TARGET_PREFIX + hex;
        } finally { VaultCodec.wipe(host); VaultCodec.wipe(user); VaultCodec.wipe(framed); VaultCodec.wipe(digest); }
    }

    VaultSshMetadata.Account account() throws VaultException {
        return new VaultSshMetadata.Account(accountId, target, profile.host, profile.port, profile.username,
                VaultSshMetadata.AuthKind.PASSWORD, entryId);
    }

    static void validateSecret(byte[] bytes) throws VaultException {
        if (bytes == null || bytes.length == 0 || bytes.length > 8192) throw SshPasswordProfile.parameters();
        for (byte value : bytes) if (value == 0) throw SshPasswordProfile.parameters();
        char[] characters = new char[bytes.length];
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer input = ByteBuffer.wrap(bytes);
            CharBuffer output = CharBuffer.wrap(characters);
            if (!decoder.decode(input, output, true).isUnderflow() || input.hasRemaining()
                    || !decoder.flush(output).isUnderflow()) throw SshPasswordProfile.parameters();
        } finally { java.util.Arrays.fill(characters, '\0'); }
    }

    static void checkAccounts(VaultManifest manifest) throws VaultException {
        Set<String> passwords = new HashSet<>();
        for (VaultSshMetadata.Account account : manifest.accounts) {
            if (account.authKind == VaultSshMetadata.AuthKind.KEY) continue;
            SshSavedPassword expected = new SshSavedPassword(new SshPasswordProfile(account.host, account.port, account.username));
            if (account.authKind != VaultSshMetadata.AuthKind.PASSWORD || !expected.accountId.equals(account.id)
                    || !expected.entryId.equals(account.credentialId) || !expected.target.equals(account.targetIdentity)
                    || !passwords.add(account.credentialId)) throw SshPasswordProfile.unsupported();
        }
        for (VaultManifest.Entry entry : manifest.entries) {
            if (entry.type == VaultManifest.EntryType.TOTP) continue;
            if (entry.type != VaultManifest.EntryType.SSH_PASSWORD || !passwords.remove(entry.id))
                throw SshPasswordProfile.unsupported();
            validateSecret(entry.secret);
        }
        if (!passwords.isEmpty()) throw SshPasswordProfile.unsupported();
    }

    boolean present(List<VaultSshMetadata.Account> accounts) {
        for (VaultSshMetadata.Account account : accounts)
            if (accountId.equals(account.id) && target.equals(account.targetIdentity)
                    && entryId.equals(account.credentialId) && profile.host.equals(account.host)
                    && profile.port == account.port && profile.username.equals(account.username)
                    && account.authKind == VaultSshMetadata.AuthKind.PASSWORD) return true;
        return false;
    }

    static void requireSelection(VaultManifest manifest, SshAuthenticationSelection selection) throws VaultException {
        if (selection == null) throw SshAuthenticationSelection.invalid();
        SshRoleContext role = selection.role;
        SshSavedPassword expected = new SshSavedPassword(new SshPasswordProfile(role.host, role.port, selection.username));
        if (selection.kind != SshCredentialBridge.Kind.PASSWORD || !expected.entryId.equals(selection.entryId)
                || !expected.target.equals(role.targetIdentity) || !expected.present(manifest.accounts))
            throw SshAuthenticationSelection.invalid();
    }

    static VaultManifest candidate(VaultManifest source, SshSavedPassword selected, boolean all,
            VaultManifest.Entry replacement) throws VaultException {
        SshPasswordProfile.checkShape(source);
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        List<VaultSshMetadata.Account> accounts = new ArrayList<>();
        try {
            for (VaultManifest.Entry old : source.entries) {
                if (old.type == VaultManifest.EntryType.SSH_PASSWORD && (all || old.id.equals(selected.entryId))) continue;
                entries.add(new VaultManifest.Entry(old.type, old.id, old.identity, old.label,
                        old.algorithm, old.digits, old.period, old.secret));
            }
            for (VaultSshMetadata.Account account : source.accounts)
                if (account.authKind != VaultSshMetadata.AuthKind.PASSWORD
                        || !all && !account.id.equals(selected.accountId)) accounts.add(account);
            if (replacement != null) {
                if (all || !replacement.id.equals(selected.entryId) || !replacement.identity.equals(selected.target)
                        || replacement.type != VaultManifest.EntryType.SSH_PASSWORD) throw VaultException.invalid();
                validateSecret(replacement.secret);
                entries.add(new VaultManifest.Entry(replacement.type, replacement.id, replacement.identity,
                        replacement.label, replacement.algorithm, replacement.digits, replacement.period, replacement.secret));
                accounts.add(selected.account());
            }
            for (VaultManifest.FileObject file : source.files)
                files.add(new VaultManifest.FileObject(file.type, file.id, file.path, file.identity, file.content, file.references));
            return new VaultManifest(entries, files, source.keyBindings, accounts);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }
}
