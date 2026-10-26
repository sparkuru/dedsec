package im.majo.ctos;

import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Supported managed connection graph, independent of any active connection authority. */
final class SshConnectionMaterial {
    static final String ROOT = ".ssh/config", HOSTS = ".ssh/known_hosts", MANUAL = ".ssh/ctos-manual";
    static final String MANUAL_ALIAS = "ctos-key";
    static final String KEY_TARGET_PREFIX = "target:ctos-key-";
    private static final java.util.Map<Thread, SshConfigEvaluator> PENDING = new java.util.HashMap<>();
    static final class CatalogScan {
        final SshConfigEvaluator.Catalog catalog;
        final SshConfigEvaluator.Usage usage;
        CatalogScan(SshConfigEvaluator.Catalog catalog, SshConfigEvaluator.Usage usage) {
            this.catalog = catalog; this.usage = usage;
        }
    }
    static final class Limits {
        final int opens, bytes, lines;
        Limits(int opens, int bytes, int lines) { this.opens = opens; this.bytes = bytes; this.lines = lines; }
    }

    static final class Snapshot implements AutoCloseable {
        final VaultManifest manifest;
        final long epoch, revision;
        final String binding;
        Snapshot(VaultManifest manifest, long epoch, long revision, String binding) throws VaultException {
            this.manifest = copy(manifest); this.epoch = epoch; this.revision = revision; this.binding = binding;
        }
        @Override public void close() { manifest.close(); }
    }

    static final class Key {
        final String id, path, identity, algorithm, fingerprint;
        final VaultSshMetadata.KeyBinding binding;
        private final byte[] publicBlob;
        Key(VaultManifest manifest, VaultSshMetadata.KeyBinding binding) throws VaultException {
            this.binding = binding; id = binding.id;
            VaultManifest.FileObject privateFile = fileId(manifest, binding.privateFileId);
            VaultManifest.FileObject publicFile = fileId(manifest, binding.publicFileId);
            path = privateFile.path; identity = privateFile.identity;
            String line = new String(publicFile.content, StandardCharsets.US_ASCII);
            int separator = line.indexOf(' ');
            if (separator < 1 || !line.endsWith("\n") || line.indexOf(' ', separator + 1) >= 0) throw unsupported();
            algorithm = line.substring(0, separator);
            try { publicBlob = Base64.getDecoder().decode(line.substring(separator + 1, line.length() - 1)); }
            catch (IllegalArgumentException invalid) { throw unsupported(); }
            if (publicBlob.length == 0 || publicBlob.length > VaultManifest.MAX_FILE_BYTES) throw unsupported();
            byte[] digest = VaultCodec.digest(publicBlob);
            try { fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest); }
            finally { VaultCodec.wipe(digest); }
        }
        byte[] publicBlob() { return publicBlob.clone(); }
    }

    static final class KeyAccount {
        final String id, target;
        final SshPasswordProfile profile;
        final Key key;
        KeyAccount(SshPasswordProfile profile, Key key) {
            this.profile = profile; this.key = key;
            byte[][] fields = {profile.host.getBytes(StandardCharsets.UTF_8),
                    profile.username.getBytes(StandardCharsets.UTF_8), key.id.getBytes(StandardCharsets.UTF_8),
                    key.identity.getBytes(StandardCharsets.UTF_8)};
            int length = 8;
            for (byte[] field : fields) length += 4 + field.length;
            byte[] encoded = new byte[length];
            try {
                ByteBuffer wire = ByteBuffer.wrap(encoded).putInt(0x43534b41).putInt(profile.port);
                for (byte[] field : fields) wire.putInt(field.length).put(field);
                String hash = hex(VaultCodec.digest(encoded));
                id = "ctos-key-account-" + hash; target = KEY_TARGET_PREFIX + hash;
            } finally { VaultCodec.wipe(encoded); for (byte[] field : fields) VaultCodec.wipe(field); }
        }
        VaultSshMetadata.Account declaration() throws VaultException {
            return new VaultSshMetadata.Account(id, target, profile.host, profile.port, profile.username,
                    VaultSshMetadata.AuthKind.KEY, key.id);
        }
        boolean matches(VaultSshMetadata.Account value) {
            return value.authKind == VaultSshMetadata.AuthKind.KEY && id.equals(value.id)
                    && target.equals(value.targetIdentity) && key.id.equals(value.credentialId)
                    && profile.host.equals(value.host) && profile.port == value.port
                    && profile.username.equals(value.username);
        }
    }

    static boolean extended(VaultManifest manifest) {
        if (!manifest.keyBindings.isEmpty()) return true;
        for (VaultManifest.FileObject file : manifest.files) if (file.path.startsWith(".ssh/")) return true;
        for (VaultSshMetadata.Account account : manifest.accounts)
            if (account.authKind == VaultSshMetadata.AuthKind.KEY) return true;
        return false;
    }

    static void checkShape(VaultManifest manifest) throws VaultException {
        manifest.validate();
        SshSavedPassword.checkAccounts(manifest);
        Set<String> keyFiles = new HashSet<>(), publicFiles = new HashSet<>();
        for (VaultSshMetadata.KeyBinding binding : manifest.keyBindings) {
            VaultManifest.FileObject key = fileId(manifest, binding.privateFileId);
            VaultManifest.FileObject pub = fileId(manifest, binding.publicFileId);
            if (binding.passphraseEntryId != null || !binding.certificateFileIds.isEmpty()
                    || !binding.id.equals(bindingId(key.path, key.identity))
                    || key.type != VaultManifest.FileType.PRIVATE_KEY || pub.type != VaultManifest.FileType.PUBLIC_KEY
                    || !key.path.startsWith(".ssh/") || key.path.contains("${") || !pub.path.equals(key.path + ".pub")
                    || !key.id.equals(id("private", key.path)) || !pub.id.equals(id("public", key.path))
                    || !pub.identity.equals(key.identity) || !keyFiles.add(key.id) || !publicFiles.add(pub.id)) throw unsupported();
        }
        for (VaultManifest.FileObject file : manifest.files) {
            if (file.path.equals(SshPasswordProfile.CONFIG_PATH) || file.path.equals(SshPasswordProfile.HOSTS_PATH)) continue;
            if (!file.path.startsWith(".ssh/")) throw unsupported();
            switch (file.type) {
                case CONFIG:
                    if (!file.id.equals(id("config", file.path)) || !file.identity.equals(id("config-owner", file.path)))
                        throw unsupported();
                    break;
                case KNOWN_HOSTS:
                    if (!file.path.equals(HOSTS) || !file.id.equals(id("hosts", HOSTS))
                            || !file.identity.equals(id("hosts-owner", HOSTS)) || !file.references.isEmpty()) throw unsupported();
                    break;
                case PRIVATE_KEY: if (!keyFiles.contains(file.id)) throw unsupported(); break;
                case PUBLIC_KEY: if (!publicFiles.contains(file.id)) throw unsupported(); break;
                default: throw unsupported();
            }
        }
        for (VaultSshMetadata.Account account : manifest.accounts) if (account.authKind == VaultSshMetadata.AuthKind.KEY) {
            Key key = key(manifest, account.credentialId);
            if (!new KeyAccount(new SshPasswordProfile(account.host, account.port, account.username), key).matches(account))
                throw unsupported();
        }
    }

    static void requireSelection(VaultManifest manifest, SshAuthenticationSelection selection) throws VaultException {
        if (selection == null || selection.kind != SshCredentialBridge.Kind.PRIVATE_KEY) throw SshAuthenticationSelection.invalid();
        Key selected = null;
        for (VaultSshMetadata.KeyBinding binding : manifest.keyBindings) {
            Key key = new Key(manifest, binding);
            if (key.path.equals(selection.path) && key.identity.equals(selection.keyIdentity)) selected = key;
        }
        if (selected == null) throw SshAuthenticationSelection.invalid();
        KeyAccount account = new KeyAccount(new SshPasswordProfile(selection.role.host, selection.role.port,
                selection.username), selected);
        boolean declared = false;
        for (VaultSshMetadata.Account candidate : manifest.accounts) if (account.matches(candidate)) declared = true;
        byte[] expected = selected.publicBlob(), actual = selection.publicKeyBlob();
        try {
            if (!declared || !account.target.equals(selection.role.targetIdentity)
                    || !MessageDigest.isEqual(expected, actual)) throw SshAuthenticationSelection.invalid();
        } finally { VaultCodec.wipe(expected); VaultCodec.wipe(actual); }
    }

    static Key key(VaultManifest manifest, String bindingId) throws VaultException {
        for (VaultSshMetadata.KeyBinding binding : manifest.keyBindings)
            if (binding.id.equals(bindingId)) return new Key(manifest, binding);
        throw new VaultException("SSH_KEY_MISSING", "Import and choose a private key");
    }

    static VaultManifest.FileObject fileId(VaultManifest manifest, String id) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) if (file.id.equals(id)) return file;
        throw unsupported();
    }
    static VaultManifest.FileObject file(VaultManifest manifest, String path) {
        for (VaultManifest.FileObject file : manifest.files) if (file.path.equals(path)) return file;
        return null;
    }

    static VaultManifest copy(VaultManifest source) throws VaultException {
        List<VaultManifest.Entry> entries = new ArrayList<>();
        List<VaultManifest.FileObject> files = new ArrayList<>();
        try {
            for (VaultManifest.Entry entry : source.entries) entries.add(new VaultManifest.Entry(entry.type,
                    entry.id, entry.identity, entry.label, entry.algorithm, entry.digits, entry.period, entry.secret));
            for (VaultManifest.FileObject file : source.files) files.add(new VaultManifest.FileObject(file.type,
                    file.id, file.path, file.identity, file.content, file.references));
            return new VaultManifest(entries, files, source.keyBindings, source.accounts);
        } catch (Throwable failure) {
            for (VaultManifest.Entry entry : entries) VaultCodec.wipe(entry.secret);
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    static byte[] detached(VaultManifest manifest) {
        int length = 24;
        byte[][] names = new byte[manifest.files.size()][];
        for (int i = 0; i < names.length; i++) {
            VaultManifest.FileObject file = manifest.files.get(i);
            names[i] = file.path.getBytes(StandardCharsets.UTF_8);
            length += 12 + names[i].length + (file.type == VaultManifest.FileType.CONFIG ? file.content.length : 0);
        }
        byte[] result = new byte[length];
        try {
            ByteBuffer wire = ByteBuffer.wrap(result).putInt(0x43534449).putInt(1).putInt(names.length)
                    .putInt(SshConfigEvaluator.MAX_OPENS).putInt(SshConfigEvaluator.MAX_READ_BYTES).putInt(SshConfigEvaluator.MAX_LINES);
            for (int i = 0; i < names.length; i++) {
                VaultManifest.FileObject file = manifest.files.get(i);
                wire.putInt(file.type.ordinal() + 1).putInt(names[i].length).put(names[i]);
                wire.putInt(file.type == VaultManifest.FileType.CONFIG ? file.content.length : 0);
                if (file.type == VaultManifest.FileType.CONFIG) wire.put(file.content);
            }
            return result;
        } catch (Throwable failure) { VaultCodec.wipe(result); throw failure; }
        finally { for (byte[] name : names) VaultCodec.wipe(name); }
    }

    static SshConfigEvaluator.DirectPlan plan(VaultManifest manifest, String root, String alias,
            String user, long deadline, AppSecrets.EffectGuard guard) throws IOException, VaultException {
        return plan(manifest, root, alias, user, deadline, guard, null);
    }
    static SshConfigEvaluator.DirectPlan plan(VaultManifest manifest, String root, String alias,
            String user, long deadline, AppSecrets.EffectGuard guard, Limits limits) throws IOException, VaultException {
        retryCleanup();
        byte[] source = detached(manifest);
        SshConfigEvaluator evaluator = null;
        try {
            if (limits != null) {
                if (limits.opens < 1 || limits.bytes < 1 || limits.lines < 1)
                    throw new VaultException("SSH_CONFIG_LIMIT", "The SSH catalog evaluation budget is exhausted");
                ByteBuffer.wrap(source).putInt(12, limits.opens).putInt(16, limits.bytes).putInt(20, limits.lines);
            }
            String[] local = SshNativeRoleBootstrap.systemIdentity();
            if (local == null || local.length != 2) throw unsupported();
            evaluator = SshConfigEvaluator.prepareDirectDetached(() -> check(guard));
            retain(evaluator);
            int index = index(manifest, root);
            evaluator.loadDetached(source, index, deadline, new SshConfigEvaluator.Request(alias, user, "",
                    local[0], local[1], "", "", 0, Process.myUid()));
            evaluator.run();
            SshConfigEvaluator.DirectPlan result = evaluator.directPlan();
            guard.check();
            return result;
        } finally { VaultCodec.wipe(source); if (evaluator != null) closeEvaluator(evaluator); }
    }

    static List<String> references(VaultManifest manifest, String root, long deadline) throws IOException, VaultException {
        retryCleanup();
        byte[] source = detached(manifest);
        SshConfigEvaluator evaluator = null;
        try {
            evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("SSH configuration expired");
            });
            retain(evaluator);
            evaluator.loadDetached(source, index(manifest, root), deadline, null);
            evaluator.run();
            SshConfigEvaluator.ResourceAudit audit = evaluator.resourceAudit(1024 * 1024, 4096);
            return references(manifest, index(manifest, root) + 1, audit);
        } finally { VaultCodec.wipe(source); if (evaluator != null) closeEvaluator(evaluator); }
    }

    static CatalogScan catalog(VaultManifest manifest, String root, long deadline,
            AppSecrets.EffectGuard guard) throws IOException, VaultException {
        retryCleanup();
        byte[] source = detached(manifest);
        SshConfigEvaluator evaluator = null;
        try {
            evaluator = SshConfigEvaluator.prepareDetached(() -> check(guard), true);
            retain(evaluator);
            evaluator.loadDetached(source, index(manifest, root), deadline, null);
            evaluator.run();
            CatalogScan result = new CatalogScan(evaluator.catalog(), evaluator.usage());
            guard.check();
            return result;
        } finally { VaultCodec.wipe(source); if (evaluator != null) closeEvaluator(evaluator); }
    }

    static List<String> references(VaultManifest manifest, int parent, SshConfigEvaluator.ResourceAudit audit)
            throws VaultException {
        Set<String> references = new java.util.LinkedHashSet<>();
        if (file(manifest, HOSTS) != null) references.add(HOSTS);
        for (SshConfigEvaluator.Edge edge : audit.audit.edges)
            if (edge.parent == parent) references.add(manifest.files.get(edge.child - 1).path);
        for (SshConfigEvaluator.Resource resource : audit.resources) if (resource.parent == parent) {
            if (resource.state == SshConfigEvaluator.Resource.STATIC)
                references.add(manifest.files.get(resource.target - 1).path);
            else if (resource.state != SshConfigEvaluator.Resource.DISABLED)
                throw new VaultException("SSH_CONFIG_DEPENDENCY", "Import the referenced SSH file or edit its managed path");
        }
        return new ArrayList<>(references);
    }

    static void retryCleanup() throws IOException {
        SshConfigEvaluator evaluator;
        synchronized (PENDING) { evaluator = PENDING.get(Thread.currentThread()); }
        if (evaluator != null) closeEvaluator(evaluator);
    }
    private static void retain(SshConfigEvaluator evaluator) {
        synchronized (PENDING) { PENDING.put(Thread.currentThread(), evaluator); }
    }
    private static void closeEvaluator(SshConfigEvaluator evaluator) throws IOException {
        evaluator.close();
        synchronized (PENDING) { PENDING.remove(Thread.currentThread(), evaluator); }
    }

    static int index(VaultManifest manifest, String path) throws VaultException {
        for (int i = 0; i < manifest.files.size(); i++) if (manifest.files.get(i).path.equals(path)) return i;
        throw new VaultException("SSH_CONFIG_MISSING", "Import an SSH config first");
    }

    static VaultManifest.FileObject emptyHosts() {
        return new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, id("hosts", HOSTS), HOSTS,
                id("hosts-owner", HOSTS), new byte[0], List.of());
    }

    static SshBundleProposal connectionProposal(VaultManifest source, SshPasswordProfile profile,
            Key key, boolean manual) throws VaultException {
        List<SshBundleProposal.Change> changes = new ArrayList<>();
        List<VaultManifest.FileObject> temporary = new ArrayList<>();
        try {
            if (file(source, HOSTS) == null) {
                VaultManifest.FileObject hosts = emptyHosts(); temporary.add(hosts);
                changes.add(SshBundleProposal.Change.add(hosts));
            }
            if (manual) {
                String user = quoted(profile.username), identity = quoted("~/" + key.path);
                byte[] body = ("# ctOS manual private-key connection\nHost " + MANUAL_ALIAS + "\n    HostName "
                        + profile.host + "\n    Port " + profile.port + "\n    User " + user
                        + "\n    IdentityFile " + identity + "\n    IdentitiesOnly yes\n"
                        + "    UserKnownHostsFile ~/.ssh/known_hosts\n").getBytes(StandardCharsets.UTF_8);
                VaultManifest.FileObject config;
                try { config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, id("config", MANUAL),
                        MANUAL, id("config-owner", MANUAL), body, List.of(HOSTS, key.path)); }
                finally { VaultCodec.wipe(body); }
                temporary.add(config);
                VaultManifest.FileObject old = file(source, MANUAL);
                if (old == null) changes.add(SshBundleProposal.Change.add(config));
                else if (!SshBundleProposal.objectBinding(old).equals(SshBundleProposal.objectBinding(config)))
                    changes.add(SshBundleProposal.Change.replace(old, config));
            }
            KeyAccount selected = new KeyAccount(profile, key);
            boolean present = false;
            for (VaultSshMetadata.Account account : source.accounts) if (selected.matches(account)) present = true;
            SshBundleProposal.Relations relations = null;
            if (!present) {
                List<VaultSshMetadata.Account> accounts = new ArrayList<>(source.accounts);
                accounts.add(selected.declaration());
                relations = new SshBundleProposal.Relations(source, source.keyBindings, accounts);
            }
            return changes.isEmpty() && relations == null ? null : new SshBundleProposal(changes, relations);
        } finally {
            for (VaultManifest.FileObject file : temporary) VaultCodec.wipe(file.content);
            for (SshBundleProposal.Change change : changes) change.close();
        }
    }
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    static String id(String kind, String path) {
        byte[] encoded = ("ctos-managed-" + kind + "\0" + path).getBytes(StandardCharsets.UTF_8);
        try { return "ctos-" + kind + "-" + hex(VaultCodec.digest(encoded)); }
        finally { VaultCodec.wipe(encoded); }
    }
    static String bindingId(String path, String identity) { return id("binding", path + "\0" + identity); }
    static String configBinding(Snapshot source, String alias) {
        byte[] encoded = ("ctos-config-selection-v1\0" + source.binding + "\0" + source.epoch
                + "\0" + source.revision + "\0" + alias).getBytes(StandardCharsets.UTF_8);
        try { return hex(VaultCodec.digest(encoded)); }
        finally { VaultCodec.wipe(encoded); }
    }
    static String hex(byte[] digest) {
        try {
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return text.toString();
        } finally { VaultCodec.wipe(digest); }
    }
    static VaultException unsupported() {
        return new VaultException("SSH_PROFILE_UNSUPPORTED", "The managed SSH graph is unsupported or inconsistent");
    }
    private static void check(AppSecrets.EffectGuard guard) throws IOException {
        try { guard.check(); } catch (VaultException failure) { throw new IOException("SSH request expired", failure); }
    }
}
