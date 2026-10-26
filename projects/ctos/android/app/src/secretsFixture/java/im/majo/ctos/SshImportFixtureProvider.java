package im.majo.ctos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Fixture-only, same-UID, one-read input. Never writes a private file to storage. */
public final class SshImportFixtureProvider extends ContentProvider {
    static final String AUTHORITY = "im.majo.ctos.secretsfixture.ssh-import";
    private static final Object LOCK = new Object();
    private static Payload current;

    private static final class Payload {
        final Uri uri = Uri.parse("content://" + AUTHORITY + "/" + UUID.randomUUID());
        final String name;
        final int size;
        byte[] body;
        boolean opened;
        volatile boolean cancelled;
        ParcelFileDescriptor writer;
        Thread thread;
        Payload(String name, byte[] body) { this.name = name; this.body = body.clone(); size = body.length; }
    }

    static Uri seed(String name, byte[] body) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}") || body == null
                || body.length == 0 || body.length > VaultManifest.MAX_FILE_BYTES)
            throw new IllegalArgumentException("Invalid fixture import");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("Fixture input is occupied");
            current = new Payload(name, body);
            return current.uri;
        }
    }

    static void clear() throws IOException {
        Payload payload;
        synchronized (LOCK) {
            payload = current; current = null;
            if (payload == null) return;
            payload.cancelled = true;
            VaultCodec.wipe(payload.body); payload.body = null;
        }
        // Poll waits are at most 50ms; the only writer closes its own descriptor before join ends.
        if (payload.thread != null) {
            try { payload.thread.join(1500); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Fixture cleanup interrupted"); }
            if (payload.thread.isAlive()) throw new IOException("Fixture writer did not stop");
        }
    }

    @Override public boolean onCreate() { return true; }

    private static Payload require(Uri uri) throws FileNotFoundException {
        if (Binder.getCallingUid() != Process.myUid() || current == null || !current.uri.equals(uri)
                || current.cancelled) throw new FileNotFoundException("Fixture input unavailable");
        return current;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        synchronized (LOCK) {
            Payload payload = require(uri);
            if (!"r".equals(mode) || payload.opened) throw new FileNotFoundException("Fixture input unavailable");
            ParcelFileDescriptor[] pipe = null;
            byte[] owned = null;
            try {
                pipe = ParcelFileDescriptor.createPipe();
                payload.opened = true;
                payload.writer = pipe[1];
                owned = payload.body; payload.body = null;
                byte[] writing = owned;
                payload.thread = new Thread(() -> write(payload, writing), "ssh-import-fixture");
                payload.thread.setDaemon(true);
                payload.thread.start();
                return pipe[0];
            } catch (IOException | RuntimeException | Error failure) {
                VaultCodec.wipe(owned);
                payload.cancelled = true;
                if (pipe != null) for (ParcelFileDescriptor descriptor : pipe)
                    try { descriptor.close(); } catch (IOException ignored) { }
                throw new FileNotFoundException("Fixture pipe unavailable");
            }
        }
    }

    private static void write(Payload payload, byte[] owned) {
        try (ParcelFileDescriptor descriptor = payload.writer) {
            int offset = 0;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            StructPollfd ready = new StructPollfd();
            ready.fd = descriptor.getFileDescriptor();
            ready.events = (short)OsConstants.POLLOUT;
            StructPollfd[] poll = {ready};
            while (offset < owned.length && !payload.cancelled && SystemClock.elapsedRealtime() < deadline) {
                try {
                    ready.revents = 0;
                    int remaining = (int)Math.max(1, Math.min(50, deadline - SystemClock.elapsedRealtime()));
                    if (Os.poll(poll, remaining) == 0) continue;
                    if (payload.cancelled || SystemClock.elapsedRealtime() >= deadline
                            || (ready.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) return;
                    if ((ready.revents & OsConstants.POLLOUT) == 0) continue;
                    // Android's Linux pipe POLLOUT promises room for PIPE_BUF. This private pipe
                    // has exactly one writer; its reader can only increase that room. Each write
                    // is <=512 (the POSIX minimum PIPE_BUF), so it cannot wait for a slow reader.
                    int written = Os.write(ready.fd, owned, offset, Math.min(512, owned.length - offset));
                    if (written <= 0) return;
                    offset += written;
                }
                catch (ErrnoException failure) {
                    if (failure.errno != OsConstants.EAGAIN && failure.errno != OsConstants.EINTR) return;
                }
            }
        } catch (IOException ignored) { /* The production reader may cancel its request. */ }
        finally { VaultCodec.wipe(owned); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        synchronized (LOCK) {
            try {
                Payload payload = require(uri);
                String[] columns = projection == null
                        ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
                MatrixCursor cursor = new MatrixCursor(columns, 1);
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = payload.name;
                    else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = payload.size;
                }
                cursor.addRow(values); return cursor;
            } catch (FileNotFoundException failure) { throw new IllegalArgumentException("Fixture input unavailable"); }
        }
    }
    @Override public String getType(Uri uri) {
        synchronized (LOCK) {
            try { require(uri); return "application/octet-stream"; }
            catch (FileNotFoundException failure) { throw new IllegalArgumentException("Fixture input unavailable"); }
        }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
