package im.majo.ctos;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Uses only direct children of this freshly user-selected tree; never persists a grant. */
final class SafExportProvider implements VaultExportTransaction.Provider {
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private final ContentResolver resolver;
    private final Uri tree;
    private final String rootId, label;
    private final boolean create;
    SafExportProvider(ContentResolver resolver, Uri tree) throws Exception {
        if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)
                || tree.toString().length() > 4096) throw invalid();
        this.resolver = resolver; this.tree = tree; rootId = DocumentsContract.getTreeDocumentId(tree);
        VaultExportTransaction.Document root = inspect(uri(rootId));
        if (!root.directory) throw invalid();
        label = root.name + " (" + tree.getAuthority() + ")";
        if (label.length() > 1024) throw invalid();
        try (Cursor cursor = query(uri(rootId))) {
            if (!cursor.moveToFirst()) throw invalid();
            create = (cursor.getInt(column(cursor, 3)) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
        }
    }
    @Override public String tree() { return tree.toString(); }
    @Override public String location() { return label; }
    @Override public boolean canCreate() { return create; }
    @Override public List<VaultExportTransaction.Document> children() throws Exception {
        List<VaultExportTransaction.Document> values = new ArrayList<>();
        long started = SystemClock.elapsedRealtime();
        int metadataChars = 0;
        try (Cursor cursor = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId))) {
            while (cursor.moveToNext()) {
                if (values.size() >= VaultExportTransaction.MAX_CHILDREN || SystemClock.elapsedRealtime() - started > 3000) throw invalid();
                VaultExportTransaction.Document value = document(cursor);
                metadataChars += value.id.length() + value.name.length() + value.location.length();
                if (metadataChars > 524288) throw invalid();
                values.add(value);
            }
            if (cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw invalid();
        }
        return values;
    }
    private Cursor query(Uri uri) throws Exception {
        CancellationSignal signal = new CancellationSignal();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable cancel = signal::cancel;
        handler.postDelayed(cancel, 3000);
        try {
            Cursor result = resolver.query(uri, COLUMNS, null, null, null, signal);
            if (result == null) throw invalid();
            if (result.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) { result.close(); throw invalid(); }
            if (result.getColumnCount() < COLUMNS.length) { result.close(); throw invalid(); }
            for (String name : COLUMNS) if (result.getColumnIndex(name) < 0) { result.close(); throw invalid(); }
            return new CursorWrapper(result) {
                @Override public void close() { try { super.close(); } finally { handler.removeCallbacks(cancel); } }
            };
        } catch (Exception error) { handler.removeCallbacks(cancel); throw error; }
    }
    private VaultExportTransaction.Document document(Cursor cursor) throws Exception {
        if (cursor.isNull(column(cursor, 0)) || cursor.isNull(column(cursor, 1)) || cursor.isNull(column(cursor, 2)) || cursor.isNull(column(cursor, 3))) throw invalid();
        String id = cursor.getString(column(cursor, 0)), name = cursor.getString(column(cursor, 1));
        if (id.isEmpty() || id.length() > 2048 || name.length() > 256) throw invalid();
        int flags = cursor.getInt(column(cursor, 3));
        if ((!cursor.isNull(column(cursor, 4)) && cursor.getLong(column(cursor, 4)) < 0) || (!cursor.isNull(column(cursor, 5)) && cursor.getLong(column(cursor, 5)) < 0)) throw invalid();
        return new VaultExportTransaction.Document(id, name, (label == null ? tree.getAuthority() : label) + " / " + name,
                cursor.isNull(column(cursor, 4)) ? null : cursor.getLong(column(cursor, 4)), cursor.isNull(column(cursor, 5)) ? null : cursor.getLong(column(cursor, 5)),
                (flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
                (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0,
                (flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0,
                DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(column(cursor, 2))));
    }
    private static int column(Cursor cursor, int index) { return cursor.getColumnIndexOrThrow(COLUMNS[index]); }
    private VaultExportTransaction.Document inspect(Uri uri) throws Exception {
        if (uri == null || !tree.getAuthority().equals(uri.getAuthority())) throw invalid();
        try (Cursor cursor = query(uri)) {
            if (!cursor.moveToFirst()) throw invalid();
            VaultExportTransaction.Document result = document(cursor);
            if (!result.id.equals(DocumentsContract.getDocumentId(uri))) throw invalid();
            if (cursor.moveToNext()) throw invalid();
            return result;
        }
    }
    private Uri uri(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }
    @Override public VaultExportTransaction.Document create(String name) throws Exception {
        return inspect(DocumentsContract.createDocument(resolver, uri(rootId), "application/octet-stream", name));
    }
    @Override public VaultExportTransaction.Document rename(VaultExportTransaction.Document document, String name) throws Exception {
        return inspect(DocumentsContract.renameDocument(resolver, uri(document.id), name));
    }
    @Override public void delete(VaultExportTransaction.Document document) throws Exception {
        if (!DocumentsContract.deleteDocument(resolver, uri(document.id))) throw invalid();
    }
    private ParcelFileDescriptor open(VaultExportTransaction.Document document, String mode) throws Exception {
        CancellationSignal signal = new CancellationSignal();
        Handler handler = new Handler(Looper.getMainLooper()); Runnable cancel = signal::cancel;
        handler.postDelayed(cancel, 3000);
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri(document.id), mode, signal);
            if (descriptor == null) throw invalid(); return descriptor;
        } finally { handler.removeCallbacks(cancel); }
    }
    // Provider stream I/O/close can remain uninterruptible; the worker retains its lease until compensation ends.
    @Override public InputStream read(VaultExportTransaction.Document document) throws Exception { return new ParcelFileDescriptor.AutoCloseInputStream(open(document, "r")); }
    @Override public OutputStream write(VaultExportTransaction.Document document) throws Exception { return new ParcelFileDescriptor.AutoCloseOutputStream(open(document, "wt")); }
    private static VaultException invalid() { return new VaultException("VAULT_EXPORT_DIRECTORY", "The selected directory could not be fully verified"); }
}
