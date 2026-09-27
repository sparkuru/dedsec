package im.majo.ctos;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Operations are restricted to one persisted, local provider tree; no raw path guessing. */
final class HftpDocuments {
    private static final String[] COLUMNS = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS };
    private final Context context;
    private final ContentResolver resolver;
    private final Uri tree, root;
    private final String rootId;
    private final long maxUploadBytes;
    private final Object writes = new Object();
    private final java.util.Set<String> writing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static final class Entry {
        final Uri uri;
        final String id, name;
        final boolean directory;
        final long size;
        final int flags;
        Entry(Uri tree, Cursor cursor) {
            id = cursor.getString(0);
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            name = cursor.getString(1);
            directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
            size = cursor.isNull(3) ? -1 : cursor.getLong(3);
            flags = cursor.getInt(4);
        }
        JSONObject metadata() throws Exception {
            return new JSONObject().put("ok", true).put("exists", true).put("directory", directory).put("size", size);
        }
    }

    HftpDocuments(Context context, Uri tree, long maxUploadBytes) throws Exception {
        this.context = context;
        this.resolver = context.getContentResolver();
        HftpConfig.requirePermission(context, tree);
        this.tree = tree;
        this.rootId = DocumentsContract.getTreeDocumentId(tree);
        this.root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        this.maxUploadBytes = maxUploadBytes;
        Entry entry = query(root);
        if (!entry.directory || (entry.flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0)
            throw new IOException("Choose a writable directory in local storage");
    }

    String name() throws Exception { return query(root).name; }

    static String[] parts(JSONArray array) throws Exception {
        if (array == null || array.length() > 8) throw new IOException("Invalid path depth");
        String[] parts = new String[array.length()];
        for (int index = 0; index < parts.length; index++) {
            String name = array.getString(index);
            if (!validName(name)) throw new IOException("Invalid path name");
            parts[index] = name;
        }
        return parts;
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.startsWith(".") && !name.contains("/")
                && !name.contains("\\") && !name.contains("\0")
                && name.chars().noneMatch(value -> value < 32 || value == 127)
                && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255;
    }

    private void check(Entry entry) throws Exception {
        if ((entry.flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
            throw new IOException("Virtual files are not shared");
        if (entry.id.equals(rootId)) return;
        // ExternalStorage IDs have a volume/path shape; Downloads IDs are opaque (raw, msd, numeric).
        if (HftpConfig.EXTERNAL_STORAGE_AUTHORITY.equals(tree.getAuthority())
                && !entry.id.startsWith(rootId.endsWith(":") ? rootId : rootId + "/"))
            throw new IOException("Document is outside the selected directory");
        if (!isChild(entry.uri)) throw new IOException("Document is outside the selected directory");
    }

    private boolean isChild(Uri uri) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) return DocumentsContract.isChildDocument(resolver, root, uri);
        DocumentsContract.Path path = DocumentsContract.findDocumentPath(resolver, uri);
        return path != null && !path.getPath().isEmpty() && path.getPath().get(0).equals(rootId);
    }

    private Entry query(Uri uri) throws Exception {
        HftpConfig.requirePermission(context, tree);
        try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new FileNotFoundException("Directory or document is unavailable");
            Entry entry = new Entry(tree, cursor);
            check(entry);
            return entry;
        }
    }

    private List<Entry> children(Entry parent, int limit) throws Exception {
        if (!parent.directory) throw new IOException("Expected a directory");
        List<Entry> result = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id);
        try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("Directory is unavailable");
            while (cursor.moveToNext()) {
                Entry entry = new Entry(tree, cursor);
                if (!validName(entry.name) || entry.name.endsWith(".partial")
                        || writing.contains(parent.id + "\0" + entry.name)) continue;
                check(entry);
                result.add(entry);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private Entry resolve(String[] parts, boolean allowMissing) throws Exception {
        Entry current = query(root);
        for (int index = 0; index < parts.length; index++) {
            Entry next = null;
            // Scan at most 2001 entries; the broker does not recursively walk a user's tree.
            for (Entry child : children(current, 2001)) if (child.name.equals(parts[index])) { next = child; break; }
            if (next == null && allowMissing && index == parts.length - 1) return null;
            if (next == null) throw new FileNotFoundException("Document is unavailable");
            current = next;
        }
        return current;
    }

    JSONObject stat(String[] parts) throws Exception {
        Entry entry = resolve(parts, true);
        return entry == null ? new JSONObject().put("ok", true).put("exists", false) : entry.metadata();
    }

    JSONObject listing(String[] parts) throws Exception {
        List<Entry> entries = children(resolve(parts, false), 1000);
        entries.sort(java.util.Comparator.comparing((Entry entry) -> !entry.directory).thenComparing(entry -> entry.name));
        JSONArray values = new JSONArray();
        for (Entry entry : entries) values.put(new JSONObject().put("name", entry.name).put("directory", entry.directory));
        return new JSONObject().put("ok", true).put("entries", values);
    }

    void read(String[] parts, OutputStream output) throws Exception {
        Entry entry = resolve(parts, false);
        if (entry.directory || entry.size < 0 || entry.size > 1024 * 1024 * 1024L)
            throw new IOException("File is unavailable or exceeds the download limit");
        boolean streaming = false;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(entry.uri, "r")) {
            if (descriptor == null || !OsConstants.S_ISREG(Os.fstat(descriptor.getFileDescriptor()).st_mode))
                throw new IOException("Expected a regular local file");
            long size = descriptor.getStatSize();
            if (size < 0 || size > 1024 * 1024 * 1024L) throw new IOException("Invalid download size");
            // Once the framing header is sent, an error must close the stream rather than become file bytes.
            streaming = true;
            HftpTreeBroker.reply(output, new JSONObject().put("ok", true).put("size", size));
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { copy(input, output, size); }
        } catch (Exception error) {
            if (streaming) throw new StreamingException(error);
            throw error;
        }
    }

    void create(String[] parts, InputStream input, OutputStream output, long size, boolean directory) throws Exception {
        if (parts.length == 0 || size < 0 || size > maxUploadBytes || (directory && size != 0))
            throw new IOException("Invalid upload size or destination");
        synchronized (writes) {
            if (resolve(parts, true) != null) throw new java.nio.file.FileAlreadyExistsException("Destination exists");
            String[] parentParts = java.util.Arrays.copyOf(parts, parts.length - 1);
            Entry parent = resolve(parentParts, false);
            if (children(parent, 2001).size() >= 2000) throw new QuotaException();
            Uri temporary = null, target = null;
            String reservedName = null;
            try {
                String name = parts[parts.length - 1];
                if (directory) {
                    target = createExact(parent.uri, DocumentsContract.Document.MIME_TYPE_DIR, name);
                } else {
                    String temporaryName = ".ctos-upload-" + UUID.randomUUID() + ".partial";
                    temporary = createExact(parent.uri, "application/octet-stream", temporaryName);
                    HftpTreeBroker.reply(output, new JSONObject().put("ok", true));
                    try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(temporary, "w")) {
                        if (descriptor == null) throw new IOException("Cannot write upload document");
                        try (OutputStream file = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                            copy(input, file, size);
                            file.flush();
                            descriptor.getFileDescriptor().sync();
                        }
                    }
                    if (resolve(parts, true) != null) throw new java.nio.file.FileAlreadyExistsException("Destination exists");
                    // SAF cannot provide rename-no-replace. Reserve a newly created document instead.
                    reservedName = parent.id + "\0" + name;
                    writing.add(reservedName);
                    target = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", name);
                    if (target == null) throw new IOException("Cannot create upload destination");
                    if (!query(target).name.equals(name)) throw new java.nio.file.FileAlreadyExistsException("Provider changed destination name");
                    try (InputStream source = resolver.openInputStream(temporary);
                         ParcelFileDescriptor descriptor = resolver.openFileDescriptor(target, "w")) {
                        if (source == null || descriptor == null) throw new IOException("Cannot finish upload");
                        try (OutputStream file = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                            copy(source, file, size);
                            file.flush();
                            descriptor.getFileDescriptor().sync();
                        }
                    }
                }
                if (Thread.currentThread().isInterrupted()) throw new IOException("Service stopped");
                target = null;
                HftpTreeBroker.reply(output, new JSONObject().put("ok", true));
            } finally {
                deleteOwned(target);
                deleteOwned(temporary);
                if (reservedName != null) writing.remove(reservedName);
            }
        }
    }

    private Uri createExact(Uri parent, String mime, String name) throws Exception {
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) throw new IOException("Cannot create document");
        try {
            if (!query(created).name.equals(name)) throw new java.nio.file.FileAlreadyExistsException("Provider changed document name");
            return created;
        } catch (Exception error) { deleteOwned(created); throw error; }
    }

    private void deleteOwned(Uri uri) {
        if (uri == null) return;
        try { DocumentsContract.deleteDocument(resolver, uri); }
        catch (Exception ignored) { /* Never remove another entry to recover an owned upload. */ }
    }

    private static void copy(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Service stopped");
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count <= 0) throw new IOException("Incomplete transfer");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    static final class QuotaException extends IOException { }
    static final class StreamingException extends IOException {
        StreamingException(Exception cause) { super(cause); }
    }
}
