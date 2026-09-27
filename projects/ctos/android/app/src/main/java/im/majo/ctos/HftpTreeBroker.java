package im.majo.ctos;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Random abstract socket is reachable only by this App UID and owned by one service. */
final class HftpTreeBroker implements Closeable {
    final String socketName = "ctos-hftp-" + UUID.randomUUID();
    final String directoryName;
    private final LocalServerSocket server;
    private final HftpDocuments documents;
    private final Set<LocalSocket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> new Thread(runnable, "ctos-hftp-document"));
    private volatile boolean closed;

    HftpTreeBroker(Context context, Uri tree, int maxUploadMiB) throws Exception {
        documents = new HftpDocuments(context, tree, maxUploadMiB * 1024 * 1024L);
        directoryName = documents.name();
        server = new LocalServerSocket(socketName);
        new Thread(this::accept, "ctos-hftp-tree").start();
    }

    private void accept() {
        while (!closed) {
            LocalSocket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(15000);
                if (socket.getPeerCredentials().getUid() != android.os.Process.myUid()) {
                    socket.close();
                    continue;
                }
                sockets.add(socket);
                LocalSocket accepted = socket;
                workers.execute(() -> handle(accepted));
            } catch (Exception error) {
                if (socket != null) { sockets.remove(socket); try { socket.close(); } catch (Exception ignored) { } }
                if (closed) return;
            }
        }
    }

    private void handle(LocalSocket socket) {
        try (LocalSocket connection = socket) {
            InputStream input = connection.getInputStream();
            OutputStream output = connection.getOutputStream();
            try {
                JSONObject request = new JSONObject(readLine(input));
                String[] parts = HftpDocuments.parts(request.getJSONArray("parts"));
                switch (request.getString("operation")) {
                    case "stat": reply(output, documents.stat(parts)); break;
                    case "list": reply(output, documents.listing(parts)); break;
                    case "read": documents.read(parts, output); break;
                    case "write": documents.create(parts, input, output, request.getLong("size"), false); break;
                    case "mkdir": documents.create(parts, input, output, 0, true); break;
                    default: throw new IllegalArgumentException("Unknown document operation");
                }
            } catch (Exception error) {
                if (error instanceof HftpDocuments.StreamingException) return;
                String code = error instanceof java.nio.file.FileAlreadyExistsException ? "exists"
                        : error instanceof HftpDocuments.QuotaException ? "quota" : "invalid";
                String reason = error instanceof SecurityException ? "Directory permission expired; select the directory again"
                        : code.equals("exists") ? "Destination exists" : code.equals("quota") ? "Directory entry limit reached"
                        : "Document operation failed or invalid path";
                reply(output, new JSONObject().put("ok", false).put("code", code).put("reason", reason));
            }
        } catch (Exception ignored) { /* Disconnects remove only this upload's owned partial document. */ }
        finally { sockets.remove(socket); }
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= 8192) throw new IllegalArgumentException("Document request too large");
            bytes.write(value);
        }
        if (value != '\n') throw new IllegalArgumentException("Incomplete document request");
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    static void reply(OutputStream output, JSONObject response) throws Exception {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 524288) throw new IllegalArgumentException("Document response too large");
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) { }
        for (LocalSocket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }
}
