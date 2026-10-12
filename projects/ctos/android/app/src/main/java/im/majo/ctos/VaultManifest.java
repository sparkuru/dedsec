package im.majo.ctos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Flat, bounded typed format; links, device paths and URI grants have no representation. */
final class VaultManifest implements AutoCloseable {
    static final int MAX_ENTRIES = 1024;
    static final int MAX_FILES = 128;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_TOTAL_FILE_BYTES = 8 * 1024 * 1024;
    enum EntryType { TOTP, SSH_PASSWORD, SSH_KEY_PASSPHRASE }
    enum FileType { CONFIG, PRIVATE_KEY, PUBLIC_KEY, CERTIFICATE, KNOWN_HOSTS }

    static final class Entry {
        final EntryType type;
        final String id;
        final String identity;
        final String label;
        final String algorithm;
        final int digits;
        final int period;
        final byte[] secret;

        Entry(EntryType type, String id, String identity, String label, String algorithm,
                int digits, int period, byte[] secret) {
            this.type = type;
            this.id = id;
            this.identity = identity;
            this.label = label;
            this.algorithm = algorithm;
            this.digits = digits;
            this.period = period;
            this.secret = secret.clone();
        }
    }

    static final class FileObject {
        final FileType type;
        final String id;
        final String path;
        final String identity;
        final byte[] content;
        final List<String> references;

        FileObject(FileType type, String id, String path, String identity, byte[] content,
                List<String> references) {
            this.type = type;
            this.id = id;
            this.path = path;
            this.identity = identity;
            this.content = content.clone();
            this.references = VaultCollections.copyList(references);
        }
    }

    final List<Entry> entries;
    final List<FileObject> files;

    VaultManifest(List<Entry> entries, List<FileObject> files) throws VaultException {
        this.entries = VaultCollections.copyList(entries);
        this.files = VaultCollections.copyList(files);
        validate();
    }

    static VaultManifest empty() throws VaultException { return new VaultManifest(VaultCollections.list(), VaultCollections.list()); }

    static VaultManifest decode(byte[] bytes) throws VaultException {
        List<Entry> entries = new ArrayList<>();
        List<FileObject> files = new ArrayList<>();
        boolean successful = false;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (bytes.length > VaultEnvelope.MAX_PLAINTEXT || in.readInt() != 1) throw VaultException.invalid();
            int count = count(in, MAX_ENTRIES);
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedByte();
                if (type >= EntryType.values().length) throw VaultException.invalid();
                String id = VaultCodec.readText(in, 128);
                String identity = VaultCodec.readText(in, 512);
                String label = VaultCodec.readText(in, 512);
                String algorithm = VaultCodec.readText(in, 16);
                int digits = in.readInt();
                int period = in.readInt();
                byte[] secret = VaultCodec.readBytes(in, 8192);
                try { entries.add(new Entry(EntryType.values()[type], id, identity, label, algorithm,
                        digits, period, secret)); }
                finally { VaultCodec.wipe(secret); }
            }
            count = count(in, MAX_FILES);
            int total = 0;
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedByte();
                if (type >= FileType.values().length) throw VaultException.invalid();
                String id = VaultCodec.readText(in, 128);
                String path = VaultCodec.readText(in, 512);
                String identity = VaultCodec.readText(in, 512);
                byte[] digest = new byte[32];
                in.readFully(digest);
                byte[] content = VaultCodec.readBytes(in, MAX_FILE_BYTES);
                total += content.length;
                try {
                    if (total > MAX_TOTAL_FILE_BYTES || !MessageDigest.isEqual(digest,
                            VaultCodec.digest(content))) throw VaultException.invalid();
                    int refCount = count(in, MAX_FILES);
                    List<String> refs = new ArrayList<>();
                    for (int j = 0; j < refCount; j++) refs.add(VaultCodec.readText(in, 512));
                    files.add(new FileObject(FileType.values()[type], id, path, identity, content, refs));
                } finally { VaultCodec.wipe(content); }
            }
            if (in.available() != 0) throw VaultException.invalid();
            VaultManifest result = new VaultManifest(entries, files);
            successful = true;
            return result;
        } catch (IOException error) { throw VaultException.invalid(); }
        finally {
            if (!successful) {
                for (Entry entry : entries) VaultCodec.wipe(entry.secret);
                for (FileObject file : files) VaultCodec.wipe(file.content);
            }
        }
    }

    byte[] encode() throws VaultException {
        validate();
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer()) {
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(1);
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                out.writeByte(entry.type.ordinal());
                VaultCodec.writeText(out, entry.id);
                VaultCodec.writeText(out, entry.identity);
                VaultCodec.writeText(out, entry.label);
                VaultCodec.writeText(out, entry.algorithm);
                out.writeInt(entry.digits);
                out.writeInt(entry.period);
                VaultCodec.writeBytes(out, entry.secret);
            }
            out.writeInt(files.size());
            for (FileObject file : files) {
                out.writeByte(file.type.ordinal());
                VaultCodec.writeText(out, file.id);
                VaultCodec.writeText(out, file.path);
                VaultCodec.writeText(out, file.identity);
                out.write(VaultCodec.digest(file.content));
                VaultCodec.writeBytes(out, file.content);
                out.writeInt(file.references.size());
                for (String ref : file.references) VaultCodec.writeText(out, ref);
            }
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > VaultEnvelope.MAX_PLAINTEXT) {
                VaultCodec.wipe(bytes);
                throw VaultException.invalid();
            }
            return bytes;
        } catch (IOException error) { throw new AssertionError(error); }
    }

    void validate() throws VaultException {
        if (entries.size() > MAX_ENTRIES || files.size() > MAX_FILES) throw VaultException.invalid();
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            identifier(entry.id);
            if (!ids.add(entry.id) || entry.identity.isEmpty() || utf8(entry.identity) > 512
                    || utf8(entry.label) > 512 || entry.secret.length == 0 || entry.secret.length > 8192
                    || entry.identity.indexOf('\0') >= 0 || entry.label.indexOf('\0') >= 0) throw VaultException.invalid();
            if (entry.type == EntryType.TOTP) {
                if (entry.secret.length < 10 || entry.secret.length > 128
                        || !entry.algorithm.equals("SHA1") || entry.digits != 6
                        || entry.period != 30) throw VaultException.invalid();
            } else if (!entry.algorithm.isEmpty() || entry.digits != 0 || entry.period != 0
                    || !entry.identity.matches("(?:target|key):[A-Za-z0-9_@.:%+/\\[\\]-]{1,500}")) {
                throw VaultException.invalid();
            }
        }
        Map<String, FileObject> paths = new HashMap<>();
        int total = 0;
        Map<String, Integer> heights = new HashMap<>();
        for (FileObject file : files) {
            identifier(file.id);
            path(file.path);
            if (!ids.add(file.id) || paths.put(file.path, file) != null || file.content.length > MAX_FILE_BYTES
                    || file.identity.isEmpty() || utf8(file.identity) > 512 || file.identity.indexOf('\0') >= 0
                    || file.references.size() > MAX_FILES || new HashSet<>(file.references).size() != file.references.size()) {
                throw VaultException.invalid();
            }
            total += file.content.length;
        }
        if (total > MAX_TOTAL_FILE_BYTES) throw VaultException.invalid();
        for (FileObject file : files) {
            for (String ref : file.references) {
                path(ref);
                if (!paths.containsKey(ref)) throw VaultException.invalid();
            }
            if (file.type != FileType.CONFIG && !file.references.isEmpty()) throw VaultException.invalid();
            if (height(file, paths, new HashSet<>(), heights) > 8) throw VaultException.invalid();
        }
        for (Entry entry : entries) {
            if (entry.type == EntryType.SSH_KEY_PASSPHRASE) {
                boolean found = false;
                for (FileObject file : files) if (file.type == FileType.PRIVATE_KEY
                        && ("key:" + file.identity).equals(entry.identity)) found = true;
                if (!found) throw VaultException.invalid();
            }
        }
    }


    private static int height(FileObject file, Map<String, FileObject> paths, Set<String> visiting,
            Map<String, Integer> heights) throws VaultException {
        Integer cached = heights.get(file.path);
        if (cached != null) return cached;
        if (!visiting.add(file.path) || visiting.size() > 9) throw VaultException.invalid();
        int height = 0;
        for (String ref : file.references) height = Math.max(height, 1 + height(paths.get(ref), paths, visiting, heights));
        visiting.remove(file.path);
        heights.put(file.path, height);
        return height;
    }

    static void path(String value) throws VaultException {
        if (value.isEmpty() || utf8(value) > 512 || value.startsWith("/") || value.contains("\\")
                || value.contains(":") || value.contains("%") || value.contains("~")
                || value.contains("*") || value.contains("?") || value.contains("[")
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw VaultException.invalid();
        String[] parts = value.split("/", -1);
        if (parts.length > 8) throw VaultException.invalid();
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw VaultException.invalid();
    }

    private static void identifier(String id) throws VaultException {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw VaultException.invalid();
    }

    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static int count(DataInputStream in, int maximum) throws IOException, VaultException {
        int value = in.readInt();
        if (value < 0 || value > maximum) throw VaultException.invalid();
        return value;
    }

    @Override public void close() {
        for (Entry entry : entries) VaultCodec.wipe(entry.secret);
        for (FileObject file : files) VaultCodec.wipe(file.content);
    }
}
