package im.majo.ctos;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-owned user consent; persistent process and document broker belong to the Service. */
public final class HftpBridge {
    private static final int PERMISSION = 90, DIRECTORY = 91;
    private static Object preparingOwner;
    private final Object ownerToken = new Object();
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MethodChannel.Result pending;
    private HftpConfig config;
    private volatile boolean closed;
    private String candidateUri = "";

    public HftpBridge(Activity activity) { this.activity = activity; }
    public static synchronized boolean preparing() { return preparingOwner != null; }

    private void claim(MethodChannel.Result result) {
        synchronized (HftpBridge.class) {
            if (preparingOwner != null || HftpService.active()) throw new IllegalStateException("Stop HFTP and close its directory picker first");
            preparingOwner = ownerToken;
        }
        pending = result;
    }

    private void finish(Object value, Exception error) {
        MethodChannel.Result result = pending;
        pending = null;
        synchronized (HftpBridge.class) { if (preparingOwner == ownerToken) preparingOwner = null; }
        if (closed || result == null) return;
        if (error == null) result.success(value);
        else result.error("HFTP", error.getMessage(), null);
    }

    public boolean handle(MethodCall call, MethodChannel.Result result) {
        if (call.method.equals("hftpStatus")) { result.success(HftpService.status()); return true; }
        if (call.method.equals("hftpClearLogs")) { result.success(HftpService.clearLogs()); return true; }
        if (call.method.equals("hftpStop")) {
            if (pending != null) result.error("HFTP", "Wait for the pending directory operation", null);
            else { HftpService.requestStop(activity); result.success(null); }
            return true;
        }
        if (!call.method.equals("hftpStart") && !call.method.equals("hftpConfig")
                && !call.method.equals("hftpPickDirectory") && !call.method.equals("hftpUseDefaultDirectory")) return false;
        try {
            if (call.method.equals("hftpConfig")) { result.success(HftpConfig.load(activity).json().toString()); return true; }
            claim(result);
            config = HftpConfig.load(activity);
            if (call.method.equals("hftpPickDirectory")) {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                Uri initial = config.treeUri.isEmpty()
                        ? DocumentsContract.buildDocumentUri(HftpConfig.EXTERNAL_STORAGE_AUTHORITY, "primary:Download")
                        : Uri.parse(config.treeUri);
                picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                activity.startActivityForResult(picker, DIRECTORY);
            } else if (call.method.equals("hftpUseDefaultDirectory")) {
                HftpConfig previous = config;
                HftpConfig next = previous.directory("", HftpConfig.DEFAULT_DIRECTORY);
                next.save(activity);
                release(previous.treeUri, "");
                finish(next.json().toString(), null);
            } else {
                String host = call.argument("host"), port = call.argument("port"), limit = call.argument("maxUploadMiB");
                String tree = call.argument("treeUri");
                boolean rootRelay = rootRequested(call.argument("rootRelay"));
                if (tree == null || !tree.equals(config.treeUri)) throw new IllegalArgumentException("Select the shared directory before starting HFTP");
                HftpConfig requested = new HftpConfig(host == null ? config.host : host,
                        Integer.parseInt(port == null ? "7888" : port), Integer.parseInt(limit == null ? "32" : limit), tree, config.directoryName, rootRelay);
                worker.execute(() -> validateStart(requested));
            }
        } catch (Exception error) {
            if (pending == result) finish(null, error);
            else result.error("HFTP", error.getMessage(), null);
        }
        return true;
    }

    private void validateStart(HftpConfig requested) {
        try {
            HftpConfig checked = requested;
            if (!requested.treeUri.isEmpty()) {
                HftpDocuments documents = new HftpDocuments(activity, Uri.parse(requested.treeUri), requested.maxUploadMiB * 1024 * 1024L);
                checked = requested.directory(requested.treeUri, documents.name());
            }
            HftpConfig accepted = checked;
            main.post(() -> {
                if (closed || pending == null) return;
                config = accepted;
                HftpService.createChannel(activity);
                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION);
                else start();
            });
        } catch (Exception error) { main.post(() -> finish(null, error)); }
    }

    public boolean onActivityResult(int request, int resultCode, Intent data) {
        if (request != DIRECTORY) return false;
        if (pending == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) { finish(null, null); return true; }
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            HftpConfig.validateTree(uri);
            if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                throw new SecurityException("Read and write directory permission is required");
            activity.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            candidateUri = uri.toString();
        } catch (Exception error) { finish(null, error); return true; }
        HftpConfig previous = config;
        worker.execute(() -> {
            try {
                HftpDocuments documents = new HftpDocuments(activity, uri, previous.maxUploadMiB * 1024 * 1024L);
                HftpConfig next = previous.directory(uri.toString(), documents.name());
                main.post(() -> {
                    if (!live()) return;
                    try {
                        next.save(activity);
                        candidateUri = "";
                        release(previous.treeUri, next.treeUri);
                        finish(next.json().toString(), null);
                    } catch (Exception error) { discardCandidate(); finish(null, error); }
                });
            } catch (Exception error) {
                main.post(() -> { if (live()) { discardCandidate(); finish(null, error); } });
            }
        });
        return true;
    }

    private boolean live() {
        synchronized (HftpBridge.class) { return !closed && pending != null && preparingOwner == ownerToken; }
    }

    static boolean rootRequested(Object argument) {
        if (argument != null && !(argument instanceof Boolean)) throw new IllegalArgumentException("rootRelay must be a boolean");
        return Boolean.TRUE.equals(argument);
    }

    private void discardCandidate() {
        release(candidateUri, config == null ? "" : config.treeUri);
        candidateUri = "";
    }

    private void release(String previous, String retained) {
        if (previous.isEmpty() || previous.equals(retained)) return;
        try { activity.getContentResolver().releasePersistableUriPermission(Uri.parse(previous),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
        catch (SecurityException ignored) { /* A revoked grant has already been released by Android. */ }
    }

    public void onRequestPermissionsResult(int request, int[] results) {
        if (request != PERMISSION || pending == null) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            finish(null, new SecurityException("HFTP needs notification permission to remain visibly active"));
            return;
        }
        start();
    }

    private void start() {
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(HftpService.CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE)
                throw new IllegalStateException("Enable HFTP notifications in Android settings before starting");
            config.save(activity);
            activity.startForegroundService(new Intent(activity, HftpService.class).putExtra("host", config.host).putExtra("port", config.port)
                    .putExtra("maxUploadMiB", config.maxUploadMiB).putExtra("treeUri", config.treeUri).putExtra("directoryName", config.directoryName).putExtra("rootRelay", config.rootRelay));
            finish(config.json().put("state", "starting").put("reason", "").put("logs", new org.json.JSONArray()).toString(), null);
        } catch (Exception error) { finish(null, error); }
    }

    public void close() {
        closed = true;
        discardCandidate();
        worker.shutdownNow();
        finish(null, null);
    }
}
