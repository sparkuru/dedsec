package im.majo.ctos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native-only exact file changes. Owners close both input changes and proposals after use. */
final class SshBundleProposal implements AutoCloseable {
    enum Action { ADD, REPLACE, DELETE }

    /** Nonsecret approval metadata; the digest binds the complete replacement, including its bytes. */
    static final class Summary {
        final Action action;
        final String path;
        final String objectId;
        final VaultManifest.FileType type;
        final String identity;
        final List<String> references;
        final String expectedObjectBinding;
        final String replacementObjectBinding;
        final VaultManifest.FileType expectedType;
        final String expectedObjectId;
        final String expectedIdentity;
        final List<String> expectedReferences;

        private Summary(Change change) {
            action = change.action;
            path = change.path;
            objectId = change.objectId;
            type = change.type;
            identity = change.identity;
            references = change.references;
            expectedObjectBinding = change.expected;
            replacementObjectBinding = change.replacement;
            expectedType = change.expectedType;
            expectedObjectId = change.expectedObjectId;
            expectedIdentity = change.expectedIdentity;
            expectedReferences = change.expectedReferences;
        }
    }

    static final class Change implements AutoCloseable {
        private final Action action;
        private final String path;
        private final String objectId;
        private final VaultManifest.FileType type;
        private final String identity;
        private final List<String> references;
        private final String expected;
        private final String replacement;
        private final byte[] content;
        private final VaultManifest.FileType expectedType;
        private final String expectedObjectId;
        private final String expectedIdentity;
        private final List<String> expectedReferences;
        private boolean closed;

        private Change(Action action, VaultManifest.FileObject old, VaultManifest.FileObject next)
                throws VaultException {
            VaultManifest.FileObject metadata = next == null ? old : next;
            validateFile(metadata);
            if (old != null) validateFile(old);
            if (old != null && next != null && !old.path.equals(next.path)) throw VaultException.invalid();
            this.action = action;
            path = metadata.path;
            objectId = metadata.id;
            type = metadata.type;
            identity = metadata.identity;
            references = VaultCollections.copyList(metadata.references);
            expected = old == null ? "" : objectBinding(old);
            content = next == null ? new byte[0] : next.content.clone();
            replacement = next == null ? "" : objectBinding(type, objectId, path, identity, references, content);
            expectedType = old == null ? null : old.type;
            expectedObjectId = old == null ? "" : old.id;
            expectedIdentity = old == null ? "" : old.identity;
            expectedReferences = old == null ? VaultCollections.list() : VaultCollections.copyList(old.references);
        }

        private Change(Change source) throws VaultException {
            synchronized (source) {
                source.requireOpen();
                action = source.action;
                path = source.path;
                objectId = source.objectId;
                type = source.type;
                identity = source.identity;
                references = source.references;
                expected = source.expected;
                replacement = source.replacement;
                content = source.content.clone();
                expectedType = source.expectedType;
                expectedObjectId = source.expectedObjectId;
                expectedIdentity = source.expectedIdentity;
                expectedReferences = source.expectedReferences;
            }
        }

        static Change add(VaultManifest.FileObject file) throws VaultException {
            return new Change(Action.ADD, null, file);
        }
        static Change replace(VaultManifest.FileObject old, VaultManifest.FileObject next) throws VaultException {
            if (old == null || next == null) throw VaultException.invalid();
            return new Change(Action.REPLACE, old, next);
        }
        static Change delete(VaultManifest.FileObject old) throws VaultException {
            return new Change(Action.DELETE, old, null);
        }
        private synchronized void requireOpen() throws VaultException { if (closed) throw expired(); }
        @Override public synchronized void close() { VaultCodec.wipe(content); closed = true; }
    }

    final String binding;
    private final List<Change> changes;
    private boolean closed;

    SshBundleProposal(List<Change> input) throws VaultException {
        if (input == null || input.isEmpty() || input.size() > VaultManifest.MAX_FILES) throw VaultException.invalid();
        List<Change> owned = new ArrayList<>();
        try {
            Set<String> paths = new HashSet<>();
            int total = 0;
            for (Change source : input) {
                if (source == null) throw VaultException.invalid();
                Change change = new Change(source);
                owned.add(change);
                if (!paths.add(change.path)) throw VaultException.invalid();
                total += change.content.length;
                if (total > VaultManifest.MAX_TOTAL_FILE_BYTES) throw VaultException.invalid();
            }
            owned.sort((left, right) -> left.path.compareTo(right.path));
            changes = VaultCollections.copyList(owned);
            binding = proposalBinding(changes);
        } catch (VaultException failure) {
            for (Change change : owned) change.close();
            throw failure;
        }
    }

    synchronized void requireOpen() throws VaultException { if (closed) throw expired(); }

    synchronized List<Summary> summary() throws VaultException {
        requireOpen();
        List<Summary> result = new ArrayList<>();
        for (Change change : changes) result.add(new Summary(change));
        return VaultCollections.copyList(result);
    }

    /** Only changed known_hosts content is available to the native trust reviewer, never private keys. */
    synchronized byte[] knownHostsContent(String path) throws VaultException {
        requireOpen();
        for (Change change : changes) if (change.path.equals(path)
                && change.type == VaultManifest.FileType.KNOWN_HOSTS && change.action != Action.DELETE) {
            return change.content.clone();
        }
        throw new VaultException("VAULT_OPERATION", "No replacement trust content for this resource");
    }

    synchronized VaultManifest applyTo(VaultManifest previous) throws VaultException {
        requireOpen();
        Map<String, VaultManifest.FileObject> old = new HashMap<>();
        for (VaultManifest.FileObject file : previous.files) old.put(file.path, file);
        Map<String, Change> edits = new HashMap<>();
        for (Change change : changes) {
            VaultManifest.FileObject existing = old.get(change.path);
            if (change.action == Action.ADD ? existing != null : existing == null
                    || !change.expected.equals(objectBinding(existing))) {
                throw new VaultException("VAULT_CHANGED", "Managed file changed; review the bundle again");
            }
            edits.put(change.path, change);
        }
        List<VaultManifest.FileObject> files = new ArrayList<>();
        try {
            for (VaultManifest.FileObject file : previous.files) if (!edits.containsKey(file.path)) {
                files.add(new VaultManifest.FileObject(file.type, file.id, file.path, file.identity, file.content, file.references));
            }
            for (Change change : changes) if (change.action != Action.DELETE) {
                files.add(new VaultManifest.FileObject(change.type, change.objectId, change.path,
                        change.identity, change.content, change.references));
            }
            // Whole-library validation rejects dangling references and associated key-passphrase entries.
            return new VaultManifest(previous.entries, files);
        } catch (VaultException failure) {
            for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
            throw failure;
        }
    }

    @Override public synchronized void close() {
        for (Change change : changes) change.close();
        closed = true;
    }

    private static void validateFile(VaultManifest.FileObject file) throws VaultException {
        if (file == null || file.type == null || file.id == null || !file.id.matches("[A-Za-z0-9_-]{1,128}")
                || file.path == null || file.identity == null || file.identity.isEmpty()
                || file.identity.getBytes(StandardCharsets.UTF_8).length > 512 || file.identity.indexOf('\0') >= 0
                || file.content == null || file.content.length > VaultManifest.MAX_FILE_BYTES
                || file.references == null || file.references.size() > VaultManifest.MAX_FILES
                || new HashSet<>(file.references).size() != file.references.size()
                || file.type != VaultManifest.FileType.CONFIG && !file.references.isEmpty()) throw VaultException.invalid();
        VaultManifest.path(file.path);
        for (String path : file.references) {
            if (path == null) throw VaultException.invalid();
            VaultManifest.path(path);
        }
    }

    static String objectBinding(VaultManifest.FileObject file) throws VaultException {
        validateFile(file);
        return objectBinding(file.type, file.id, file.path, file.identity, file.references, file.content);
    }

    private static String objectBinding(VaultManifest.FileType type, String id, String path, String identity,
            List<String> references, byte[] content) {
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer(); DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(type.ordinal());
            VaultCodec.writeText(out, id);
            VaultCodec.writeText(out, path);
            VaultCodec.writeText(out, identity);
            out.writeInt(references.size());
            for (String ref : references) VaultCodec.writeText(out, ref);
            VaultCodec.writeBytes(out, VaultCodec.digest(content));
            return digestBuffer(buffer);
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static String proposalBinding(List<Change> changes) {
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer(); DataOutputStream out = new DataOutputStream(buffer)) {
            VaultCodec.writeText(out, "ctos-ssh-bundle-v1");
            out.writeInt(changes.size());
            for (Change change : changes) {
                out.writeInt(change.action.ordinal());
                VaultCodec.writeText(out, change.path);
                VaultCodec.writeText(out, change.expected);
                VaultCodec.writeText(out, change.replacement);
            }
            return digestBuffer(buffer);
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static String digestBuffer(VaultCodec.SecretBuffer buffer) {
        byte[] encoded = buffer.toByteArray();
        try {
            StringBuilder text = new StringBuilder();
            for (byte value : VaultCodec.digest(encoded)) text.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return text.toString();
        } finally { VaultCodec.wipe(encoded); }
    }

    private static VaultException expired() { return new VaultException("VAULT_EXPIRED", "Bundle proposal expired"); }
}
