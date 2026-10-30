package im.majo.ctos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owned exact deletion of one managed identity and its dependent local declarations. */
final class SshKeyDeletion implements AutoCloseable {
    final SshConnectionMaterial.Key key;
    final String publicPath;
    final int accountCount;
    final List<String> configPaths;
    final boolean removesManualConfig;
    final SshBundleProposal proposal;

    private SshKeyDeletion(SshConnectionMaterial.Key key, String publicPath, int accountCount,
            List<String> configPaths, boolean removesManualConfig, SshBundleProposal proposal) {
        this.key = key; this.publicPath = publicPath; this.accountCount = accountCount;
        this.configPaths = VaultCollections.copyList(configPaths);
        this.removesManualConfig = removesManualConfig; this.proposal = proposal;
    }

    static SshKeyDeletion create(VaultManifest source, String keyId) throws VaultException {
        SshConnectionMaterial.checkShape(source);
        SshConnectionMaterial.Key key = SshConnectionMaterial.key(source, keyId);
        VaultManifest.FileObject privateFile = SshConnectionMaterial.fileId(source, key.binding.privateFileId);
        VaultManifest.FileObject publicFile = SshConnectionMaterial.fileId(source, key.binding.publicFileId);
        Set<String> removed = new HashSet<>(List.of(privateFile.path, publicFile.path));
        VaultManifest.FileObject manual = SshConnectionMaterial.file(source, SshConnectionMaterial.MANUAL);
        boolean removeManual = manual != null && manual.references.contains(privateFile.path);
        if (removeManual) for (VaultManifest.FileObject file : source.files)
            if (!file.path.equals(SshConnectionMaterial.MANUAL)
                    && file.references.contains(SshConnectionMaterial.MANUAL)) removeManual = false;
        if (removeManual) removed.add(SshConnectionMaterial.MANUAL);
        List<String> affected = new ArrayList<>();
        List<VaultSshMetadata.KeyBinding> keys = new ArrayList<>(source.keyBindings);
        keys.removeIf(binding -> binding.id.equals(keyId));
        List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts);
        int before = accounts.size();
        accounts.removeIf(account -> account.authKind == VaultSshMetadata.AuthKind.KEY
                && account.credentialId.equals(keyId));
        Set<String> phrases = key.binding.passphraseEntryId == null ? Collections.emptySet()
                : Collections.singleton(key.binding.passphraseEntryId);
        List<SshBundleProposal.Change> changes = new ArrayList<>();
        try {
            for (VaultManifest.FileObject file : source.files) {
                if (removed.contains(file.path)) {
                    changes.add(SshBundleProposal.Change.delete(file));
                } else if (file.type == VaultManifest.FileType.CONFIG) {
                    List<String> references = new ArrayList<>(file.references);
                    if (!references.removeIf(removed::contains)) continue;
                    if (!file.path.equals(SshConnectionMaterial.MANUAL)) affected.add(file.path);
                    VaultManifest.FileObject replacement = new VaultManifest.FileObject(file.type, file.id,
                            file.path, file.identity, file.content, references);
                    try { changes.add(SshBundleProposal.Change.replace(file, replacement)); }
                    finally { VaultCodec.wipe(replacement.content); }
                }
            }
            Collections.sort(affected);
            return new SshKeyDeletion(key, publicFile.path, before - accounts.size(), affected, removeManual,
                    new SshBundleProposal(changes, new SshBundleProposal.Relations(source, keys, accounts), phrases));
        } finally { for (SshBundleProposal.Change change : changes) change.close(); }
    }

    java.util.Map<String, Object> metadata(String ticketId) {
        return VaultCollections.map("ticketId", ticketId, "binding", proposal.binding, "keyId", key.id,
                "label", SshImportProposal.basename(key.path), "path", key.path, "algorithm", key.algorithm,
                "fingerprint", key.fingerprint, "publicPath", publicPath,
                "hasSavedPassphrase", key.hasSavedPassphrase, "accountCount", accountCount,
                "configPaths", configPaths, "removesManualConfig", removesManualConfig);
    }

    @Override public void close() { proposal.close(); }
}
