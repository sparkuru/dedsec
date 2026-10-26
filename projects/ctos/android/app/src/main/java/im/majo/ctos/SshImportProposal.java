package im.majo.ctos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Owned prospective file/identity change; no URI or frontend secret is retained. */
final class SshImportProposal implements AutoCloseable {
    final String kind, path, label, algorithm, fingerprint;
    final boolean replacing;
    final SshBundleProposal proposal;

    private SshImportProposal(String kind, String path, String label, String algorithm,
            String fingerprint, boolean replacing, SshBundleProposal proposal) {
        this.kind = kind; this.path = path; this.label = label; this.algorithm = algorithm;
        this.fingerprint = fingerprint; this.replacing = replacing; this.proposal = proposal;
    }

    static SshImportProposal create(VaultManifest source, String kind, String path, byte[] body,
            long deadline) throws IOException, VaultException {
        VaultManifest.path(path);
        if (!path.startsWith(".ssh/") || path.equals(SshConnectionMaterial.HOSTS)
                || path.equals(SshConnectionMaterial.MANUAL) || body.length == 0
                || body.length > VaultManifest.MAX_FILE_BYTES) throw parameters();
        List<VaultManifest.FileObject> changed = new ArrayList<>();
        List<VaultSshMetadata.KeyBinding> keys = new ArrayList<>(source.keyBindings);
        List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts);
        VaultManifest.FileObject previous = SshConnectionMaterial.file(source, path);
        String algorithm = "", fingerprint = "";
        boolean relationsChanged = false;
        try {
            if (SshConnectionMaterial.file(source, SshConnectionMaterial.HOSTS) == null)
                changed.add(SshConnectionMaterial.emptyHosts());
            if (kind.equals("privateKey")) {
                if (path.contains("${")) throw new VaultException("SSH_IMPORT_PATH", "Choose a literal managed key filename");
                if (path.endsWith(".pub") || path.equals(SshConnectionMaterial.ROOT)
                        || previous != null && previous.type != VaultManifest.FileType.PRIVATE_KEY) throw parameters();
                SshKeyMaterial.Result material = SshKeyMaterial.inspect(body, deadline);
                if (material.status != SshKeyMaterial.Status.PARSED) {
                    String code = material.status == SshKeyMaterial.Status.NEEDS_PASSPHRASE
                            ? "SSH_KEY_PASSPHRASE_REQUIRED" : material.status == SshKeyMaterial.Status.UNSUPPORTED
                            ? "SSH_KEY_UNSUPPORTED" : "SSH_KEY_INVALID";
                    throw new VaultException(code, "Choose a supported unencrypted private key");
                }
                algorithm = material.algorithm; fingerprint = material.fingerprint;
                byte[] blob = material.publicBlob(), line = material.publicLine();
                String identity;
                try {
                    identity = "ctos-key-" + SshConnectionMaterial.hex(VaultCodec.digest(blob));
                    changed.add(new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                            SshConnectionMaterial.id("private", path), path, identity, body, List.of()));
                    changed.add(new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                            SshConnectionMaterial.id("public", path), path + ".pub", identity, line, List.of()));
                } finally { VaultCodec.wipe(blob); VaultCodec.wipe(line); }
                String bindingId = SshConnectionMaterial.bindingId(path, identity);
                java.util.Set<String> oldBindings = new java.util.HashSet<>();
                for (VaultSshMetadata.KeyBinding old : keys)
                    if (old.privateFileId.equals(SshConnectionMaterial.id("private", path))) oldBindings.add(old.id);
                keys.removeIf(key -> oldBindings.contains(key.id));
                keys.add(new VaultSshMetadata.KeyBinding(bindingId, SshConnectionMaterial.id("private", path),
                        SshConnectionMaterial.id("public", path), List.of(), null));
                if (previous != null && !previous.identity.equals(identity))
                    accounts.removeIf(account -> account.authKind == VaultSshMetadata.AuthKind.KEY
                            && oldBindings.contains(account.credentialId));
                relationsChanged = true;
            } else if (kind.equals("config")) {
                if (previous != null && previous.type != VaultManifest.FileType.CONFIG) throw parameters();
                changed.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                        SshConnectionMaterial.id("config", path), path,
                        SshConnectionMaterial.id("config-owner", path), body, List.of()));
                try (VaultManifest temporary = candidate(source, changed, keys, accounts)) {
                    List<String> references = SshConnectionMaterial.references(temporary, path, deadline);
                    VaultManifest.FileObject config = changed.remove(changed.size() - 1);
                    VaultCodec.wipe(config.content);
                    changed.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                            SshConnectionMaterial.id("config", path), path,
                            SshConnectionMaterial.id("config-owner", path), body, references));
                }
            } else throw parameters();
            List<SshBundleProposal.Change> edits = new ArrayList<>();
            try {
                for (VaultManifest.FileObject file : changed) {
                    VaultManifest.FileObject old = SshConnectionMaterial.file(source, file.path);
                    if (old != null && old.type != file.type) throw parameters();
                    edits.add(old == null ? SshBundleProposal.Change.add(file) : SshBundleProposal.Change.replace(old, file));
                }
                SshBundleProposal.Relations relations = relationsChanged
                        ? new SshBundleProposal.Relations(source, keys, accounts) : null;
                return new SshImportProposal(kind, path, basename(path), algorithm, fingerprint,
                        previous != null, new SshBundleProposal(edits, relations));
            } finally { for (SshBundleProposal.Change edit : edits) edit.close(); }
        } finally { for (VaultManifest.FileObject file : changed) VaultCodec.wipe(file.content); }
    }

    private static VaultManifest candidate(VaultManifest source, List<VaultManifest.FileObject> changes,
            List<VaultSshMetadata.KeyBinding> keys, List<VaultSshMetadata.Account> accounts) throws VaultException {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        try {
            for (VaultManifest.Entry entry : source.entries) entries.add(new VaultManifest.Entry(entry.type,
                    entry.id, entry.identity, entry.label, entry.algorithm, entry.digits, entry.period, entry.secret));
            for (VaultManifest.FileObject file : source.files) {
                boolean replaced = false;
                for (VaultManifest.FileObject changed : changes) if (changed.path.equals(file.path)) replaced = true;
                if (!replaced) files.add(new VaultManifest.FileObject(file.type, file.id, file.path,
                        file.identity, file.content, file.references));
            }
            for (VaultManifest.FileObject file : changes) files.add(new VaultManifest.FileObject(file.type, file.id,
                    file.path, file.identity, file.content, file.references));
            return new VaultManifest(entries, files, keys, accounts);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    static String defaultPath(String kind, String name) throws VaultException {
        if (kind.equals("config")) return SshConnectionMaterial.ROOT;
        if (name == null || name.length() > 128 || !name.matches("[A-Za-z0-9_.-]{1,128}")
                || name.equals(".") || name.equals(".."))
            throw new VaultException("SSH_IMPORT_PATH", "Enter a managed filename for the private key");
        return ".ssh/" + name;
    }
    static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static VaultException parameters() { return new VaultException("PARAMETERS", "Check the SSH import type and path"); }
    @Override public void close() { proposal.close(); }
}
