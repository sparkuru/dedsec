package im.majo.ctos;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Document capabilities confined to explicit App-private stores. */
public final class ToolFiles {
    private static final int PICK = 82, SAVE = 83, SHARE = 84;
    public static final long MAX_FILE = 32L * 1024 * 1024, MAX_STORE = 128L * 1024 * 1024;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MethodChannel.Result pending;
    private File exportFile;
    private boolean closed;

    public ToolFiles(Activity activity) { this.activity = activity; }

    public static File store(android.content.Context context) throws Exception {
        return directory(new File(context.getFilesDir(), "tool-files"));
    }

    public static File library(android.content.Context context) throws Exception {
        return directory(new File(context.getFilesDir(), "hftp-share"));
    }

    public static void cleanPartials(File root, String pattern) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : paths.filter(path -> path.getFileName().toString().matches(pattern))
                    .limit(2000).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(path);
        }
    }

    private static File directory(File root) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create private store");
        return root;
    }

    public boolean handle(MethodCall call, MethodChannel.Result result) {
        try {
            if (call.method.equals("hftpClearShare")) {
                if (HftpService.active() || HftpBridge.preparing() || pending != null)
                    throw new IllegalStateException("Stop HFTP and close the document picker first");
                File root = library(activity);
                try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                    java.util.List<java.nio.file.Path> owned = paths.filter(path -> !path.equals(root.toPath()))
                            .sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
                    for (java.nio.file.Path path : owned) Files.delete(path);
                }
                result.success(null);
                return true;
            }
            if (call.method.equals("toolFilesInfo")) {
                result.success(new JSONObject().put("bytes", used(store(activity)))
                        .put("limit", MAX_STORE).put("shareBytes", used(library(activity))).toString());
                return true;
            }
            if (call.method.equals("toolFilesClear")) {
                if (pending != null) throw new IllegalStateException("A document operation is open");
                File[] entries = store(activity).listFiles();
                if (entries != null) for (File entry : entries) {
                    if (entry.getName().matches("[a-f0-9]{32}\\.(input|output)(\\.partial)?") && !entry.delete())
                        throw new IllegalStateException("Cannot remove private tool file");
                }
                result.success(null);
                return true;
            }
            if (!call.method.equals("toolFilePick") && !call.method.equals("toolFileExport")
                    && !call.method.equals("hftpImport")) return false;
            if (call.method.equals("hftpImport") && (HftpService.active() || HftpBridge.preparing()))
                throw new IllegalStateException("Stop HFTP before importing library files");
            if (pending != null) throw new IllegalStateException("A document operation is already open");
            if (call.method.equals("toolFileExport")) {
                String token = call.argument("token");
                if (token == null || !token.matches("[a-f0-9]{32}\\.output")) throw new IllegalArgumentException("Invalid output token");
                exportFile = new File(store(activity), token);
                if (!exportFile.isFile() || !exportFile.getCanonicalFile().getParentFile().equals(store(activity).getCanonicalFile()))
                    throw new IllegalStateException("Output is unavailable");
                pending = result;
                String name = call.argument("name");
                activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, safeName(name)), SAVE);
            } else {
                pending = result;
                activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), call.method.equals("hftpImport") ? SHARE : PICK);
            }
        } catch (Exception error) {
            if (pending == result) {
                pending = null;
                exportFile = null;
            }
            result.error("FILES", error.getMessage(), null);
        }
        return true;
    }

    public boolean onActivityResult(int request, int response, Intent data) {
        if ((request != PICK && request != SAVE && request != SHARE) || pending == null) return false;
        MethodChannel.Result result = pending;
        File source = exportFile;
        if (response != Activity.RESULT_OK || data == null || data.getData() == null) {
            pending = null;
            exportFile = null;
            result.success(null);
            return true;
        }
        Uri uri = data.getData();
        worker.execute(() -> {
            Object value;
            try {
                if (request == SAVE) {
                    try (InputStream input = Files.newInputStream(source.toPath());
                         OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new IllegalStateException("Cannot open destination");
                        transfer(input, output, MAX_FILE);
                    }
                    value = true;
                } else value = importFile(uri, request == SHARE).toString();
                activity.runOnUiThread(() -> { if (!closed) result.success(value); finish(); });
            } catch (Exception error) {
                activity.runOnUiThread(() -> { if (!closed) result.error("FILES", error.getMessage(), null); finish(); });
            }
        });
        return true;
    }

    private JSONObject importFile(Uri uri, boolean share) throws Exception {
        String name = "file.bin";
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = safeName(cursor.getString(0));
        }
        File root = share ? library(activity) : store(activity);
        File[] entries = root.listFiles();
        if (entries != null && entries.length >= 2000) throw new IllegalStateException("Private store entry limit reached");
        String token = UUID.randomUUID().toString().replace("-", "");
        File output = new File(root, share ? token.substring(0, 8) + "-" + name : token + ".input");
        File temporary = new File(root, output.getName() + ".partial");
        long bytes;
        try {
            long capacity = Math.min(MAX_FILE, MAX_STORE - used(root));
            if (capacity <= 0) throw new IllegalStateException("Private store is full");
            try (InputStream input = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream stream = new FileOutputStream(temporary)) {
                if (input == null) throw new IllegalStateException("Cannot open selected file");
                bytes = transfer(input, stream, capacity);
                stream.getFD().sync();
            }
            Files.move(temporary.toPath(), output.toPath());
        } finally { temporary.delete(); }
        return new JSONObject().put("token", output.getName()).put("name", name).put("bytes", bytes);
    }

    private static long transfer(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[8192];
        long length = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            length += count;
            if (length > limit) throw new IllegalStateException("File or private store size limit exceeded");
            output.write(buffer, 0, count);
        }
        return length;
    }

    static long used(File root) {
        long bytes = 0;
        File[] entries = root.listFiles();
        if (entries != null) for (File entry : entries) bytes += entry.isDirectory() ? used(entry) : entry.length();
        return bytes;
    }

    private static String safeName(String name) {
        if (name == null) return "file.bin";
        String safe = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return safe.substring(0, Math.min(100, safe.length())).isBlank() ? "file.bin" : safe.substring(0, Math.min(100, safe.length()));
    }

    private void finish() { pending = null; exportFile = null; }
    public void close() { closed = true; worker.shutdown(); finish(); }
}
