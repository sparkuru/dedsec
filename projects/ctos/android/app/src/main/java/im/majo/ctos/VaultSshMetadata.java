package im.majo.ctos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit declarations only; resolving an ID proves no material, account or trust authority. */
final class VaultSshMetadata {
    enum AuthKind { PASSWORD, KEY }

    static final class KeyBinding {
        final String id;
        final String privateFileId;
        final String publicFileId;
        final List<String> certificateFileIds;
        final String passphraseEntryId;

        KeyBinding(String id, String privateFileId, String publicFileId,
                List<String> certificateFileIds, String passphraseEntryId) throws VaultException {
            VaultManifest.identifier(id);
            VaultManifest.identifier(privateFileId);
            optionalIdentifier(publicFileId);
            optionalIdentifier(passphraseEntryId);
            this.certificateFileIds = VaultManifest.boundedList(certificateFileIds, VaultManifest.MAX_FILES);
            Set<String> certificates = new HashSet<>();
            for (String certificateId : this.certificateFileIds) {
                VaultManifest.identifier(certificateId);
                if (!certificates.add(certificateId)) throw VaultException.invalid();
            }
            this.id = id;
            this.privateFileId = privateFileId;
            this.publicFileId = publicFileId;
            this.passphraseEntryId = passphraseEntryId;
        }
    }

    static final class Account {
        final String id;
        final String targetIdentity;
        final String host;
        final int port;
        final String username;
        final AuthKind authKind;
        final String credentialId;

        Account(String id, String targetIdentity, String host, int port, String username,
                AuthKind authKind, String credentialId) throws VaultException {
            VaultManifest.identifier(id);
            VaultManifest.identifier(credentialId);
            if (targetIdentity == null
                    || !targetIdentity.matches("target:[A-Za-z0-9_@.:%+/\\[\\]-]{1,500}")
                    || host == null || !host.matches("[A-Za-z0-9.:%\\[\\]-]{1,253}")
                    || port < 1 || port > 65535 || authKind == null) throw VaultException.invalid();
            byte[] encodedUsername = VaultCodec.sshText(username, 255);
            VaultCodec.wipe(encodedUsername);
            this.id = id;
            this.targetIdentity = targetIdentity;
            this.host = host;
            this.port = port;
            this.username = username;
            this.authKind = authKind;
            this.credentialId = credentialId;
        }
    }

    private VaultSshMetadata() { }

    static void validate(List<VaultManifest.Entry> entries, List<VaultManifest.FileObject> files,
            List<KeyBinding> keyBindings, List<Account> accounts, Set<String> ids) throws VaultException {
        if (keyBindings.size() > VaultManifest.MAX_KEY_BINDINGS
                || accounts.size() > VaultManifest.MAX_ACCOUNTS) throw VaultException.invalid();
        Map<String, VaultManifest.Entry> entriesById = new HashMap<>();
        Map<String, VaultManifest.FileObject> filesById = new HashMap<>();
        Map<String, KeyBinding> bindingsById = new HashMap<>();
        for (VaultManifest.Entry entry : entries) entriesById.put(entry.id, entry);
        for (VaultManifest.FileObject file : files) filesById.put(file.id, file);
        Set<String> selectedPrivates = new HashSet<>();
        Set<String> selectedPassphrases = new HashSet<>();
        for (KeyBinding binding : keyBindings) {
            if (!ids.add(binding.id) || !selectedPrivates.add(binding.privateFileId)) throw VaultException.invalid();
            VaultManifest.FileObject privateFile = requireFile(filesById, binding.privateFileId,
                    VaultManifest.FileType.PRIVATE_KEY);
            if (binding.publicFileId != null) {
                requireFile(filesById, binding.publicFileId, VaultManifest.FileType.PUBLIC_KEY);
            }
            for (String certificateId : binding.certificateFileIds) {
                requireFile(filesById, certificateId, VaultManifest.FileType.CERTIFICATE);
            }
            if (binding.passphraseEntryId != null) {
                VaultManifest.Entry phrase = entriesById.get(binding.passphraseEntryId);
                if (!selectedPassphrases.add(binding.passphraseEntryId) || phrase == null
                        || phrase.type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                        || !("key:" + privateFile.identity).equals(phrase.identity)) throw VaultException.invalid();
            }
            bindingsById.put(binding.id, binding);
        }
        Set<String> targets = new HashSet<>();
        for (Account account : accounts) {
            if (!ids.add(account.id) || !targets.add(account.targetIdentity)) throw VaultException.invalid();
            if (account.authKind == AuthKind.PASSWORD) {
                VaultManifest.Entry password = entriesById.get(account.credentialId);
                if (password == null || password.type != VaultManifest.EntryType.SSH_PASSWORD
                        || !password.identity.equals(account.targetIdentity)) throw VaultException.invalid();
            } else if (!bindingsById.containsKey(account.credentialId)) throw VaultException.invalid();
        }
    }

    private static VaultManifest.FileObject requireFile(Map<String, VaultManifest.FileObject> files,
            String id, VaultManifest.FileType type) throws VaultException {
        VaultManifest.FileObject file = files.get(id);
        if (file == null || file.type != type) throw VaultException.invalid();
        return file;
    }

    private static void optionalIdentifier(String value) throws VaultException {
        if (value != null) VaultManifest.identifier(value);
    }
}
