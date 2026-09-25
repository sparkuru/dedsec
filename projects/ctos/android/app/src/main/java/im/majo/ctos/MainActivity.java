package im.majo.ctos;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import org.json.JSONObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends FlutterActivity {
    private static final String ROOT_PREFERENCES = "root_access";
    private static final String AUTO_ROOT = "auto_start";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ExecutorService terminalWorker = Executors.newSingleThreadExecutor();
    private final Object rootLock = new Object();
    private volatile boolean root;
    private volatile JSONObject systemSnapshot;
    private volatile long systemReceived;
    private String nonce;
    private EventChannel.EventSink terminalSink;
    private volatile int terminalFd = -1;
    private volatile int terminalGeneration;
    private String exportText;
    private MethodChannel.Result exportResult;
    private boolean registered;
    private volatile boolean destroyed;

    private final BroadcastReceiver bridge = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (nonce == null || !nonce.equals(intent.getStringExtra("nonce"))) return;
            if (Build.VERSION.SDK_INT >= 34 && getSentFromUid() != 1000) return;
            try {
                JSONObject data = new JSONObject(intent.getStringExtra("snapshot"));
                if (data.getInt("uid") != 1000) return;
                systemSnapshot = data;
                systemReceived = SystemClock.elapsedRealtime();
            } catch (Exception ignored) { }
        }
    };

    @Override public void configureFlutterEngine(FlutterEngine engine) {
        super.configureFlutterEngine(engine);
        ContextCompat.registerReceiver(this, bridge, new IntentFilter(SystemModule.RESULT),
                "android.permission.DUMP", main, ContextCompat.RECEIVER_EXPORTED);
        registered = true;
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/terminal").setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override public void onListen(Object arguments, EventChannel.EventSink sink) { terminalSink = sink; }
                    @Override public void onCancel(Object arguments) { terminalSink = null; }
                });
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/native").setMethodCallHandler(this::call);
    }

    private void requestSystemSnapshot() {
        nonce = UUID.randomUUID().toString();
        sendBroadcast(new Intent(SystemModule.QUERY).setPackage("android").putExtra("nonce", nonce));
    }

    private boolean autoRootEnabled() {
        return getSharedPreferences(ROOT_PREFERENCES, MODE_PRIVATE).getBoolean(AUTO_ROOT, true);
    }

    private void setAutoRootEnabled(boolean enabled) {
        getSharedPreferences(ROOT_PREFERENCES, MODE_PRIVATE).edit().putBoolean(AUTO_ROOT, enabled).apply();
    }

    private JSONObject authorizeRoot() throws Exception {
        synchronized (rootLock) {
            if (root) return new JSONObject().put("root", true).put("exit", 0).put("output", "uid=0 (active)");
            root = false;
            try {
                JSONObject probe = Collector.authorizeRoot();
                if (destroyed) { Collector.closeRoot(); throw new IllegalStateException("Activity is closed"); }
                root = probe.getInt("exit") == 0 && probe.getString("output").contains("uid=0(");
                setAutoRootEnabled(root);
                return probe.put("root", root);
            } catch (Exception error) {
                root = false;
                if (!destroyed) setAutoRootEnabled(false);
                throw error;
            }
        }
    }

    private void call(MethodCall call, MethodChannel.Result result) {
        if (call.method.equals("export")) {
            if (exportResult != null) { result.error("BUSY", "An export is already open", null); return; }
            exportText = call.argument("text");
            exportResult = result;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "ctos-snapshot.json"), 81);
            return;
        }
        if (call.method.equals("snapshot")) requestSystemSnapshot();
        ExecutorService executor = call.method.startsWith("terminal") ? terminalWorker : worker;
        executor.execute(() -> {
            try {
                Object value;
                switch (call.method) {
                    case "rootAuto": {
                        value = autoRootEnabled() ? authorizeRoot().put("attempted", true).toString()
                                : new JSONObject().put("root", root).put("attempted", false).toString();
                        break;
                    }
                    case "root": {
                        value = authorizeRoot().toString();
                        break;
                    }
                    case "snapshot": {
                        JSONObject data = NetworkSnapshot.collect(this);
                        long age = SystemClock.elapsedRealtime() - systemReceived;
                        boolean live = systemSnapshot != null && age < 10000;
                        data.put("moduleActive", live);
                        data.put("module", live ? systemSnapshot : JSONObject.NULL);
                        data.put("moduleAgeMs", systemSnapshot == null ? -1 : age);
                        JSONObject kernel = null;
                        if (root) {
                            try { kernel = Collector.interfaces(true); }
                            catch (Exception error) {
                                root = false;
                                Collector.closeRoot();
                                setAutoRootEnabled(false);
                                data.put("rootError", "Root session ended; authorize Root again");
                            }
                        }
                        if (kernel == null) kernel = NetworkSnapshot.apiInterfaces(
                                live ? systemSnapshot : data, (live ? "Vector / " : "app / ") +
                                        (Build.VERSION.SDK_INT >= 31 ? "TrafficStats" : "procfs"));
                        data.put("root", root).put("kernel", kernel);
                        value = data.toString();
                        break;
                    }
                    case "connections": {
                        try { value = Collector.connections(this, root).toString(); }
                        catch (Exception error) {
                            if (root) {
                                root = false;
                                Collector.closeRoot();
                                setAutoRootEnabled(false);
                            }
                            throw error;
                        }
                        break;
                    }
                    case "deviceSnapshot": value = DeviceSnapshot.collect(this).toString(); break;
                    case "terminalStart": value = startTerminal(Boolean.TRUE.equals(call.argument("root")),
                            dimension(call.argument("columns"), 80), dimension(call.argument("rows"), 24)); break;
                    case "terminalWrite": {
                        synchronized (this) {
                            if (terminalFd < 0) throw new IllegalStateException("Terminal is closed");
                            String input = call.argument("text");
                            Pty.write(terminalFd, input.getBytes(StandardCharsets.UTF_8));
                        }
                        value = null;
                        break;
                    }
                    case "terminalResize": {
                        synchronized (this) {
                            if (terminalFd >= 0) Pty.resize(terminalFd,
                                    dimension(call.argument("columns"), 80), dimension(call.argument("rows"), 24));
                        }
                        value = null;
                        break;
                    }
                    case "terminalStop": stopTerminal(); value = null; break;
                    default: main.post(result::notImplemented); return;
                }
                main.post(() -> result.success(value));
            } catch (Exception error) {
                main.post(() -> result.error("CTOS", error.toString(), null));
            }
        });
    }

    private static int dimension(Object value, int fallback) {
        return value instanceof Number ? Math.max(2, Math.min(500, ((Number) value).intValue())) : fallback;
    }

    private synchronized String startTerminal(boolean privileged, int columns, int rows) throws Exception {
        stopTerminal();
        if (destroyed) throw new IllegalStateException("Activity is closed");
        if (privileged && !root) throw new SecurityException("Root has not been authorized");
        int[] process = Pty.start(privileged, columns, rows, getFilesDir().getAbsolutePath());
        int fd = process[0], pid = process[1], generation = terminalGeneration;
        terminalFd = fd;
        new Thread(() -> {
            try {
                while (generation == terminalGeneration) {
                    byte[] bytes = Pty.read(fd);
                    if (bytes == null) break;
                    if (bytes.length == 0) continue;
                    main.post(() -> { if (terminalSink != null && generation == terminalGeneration) terminalSink.success(bytes); });
                }
            } catch (Exception error) {
                main.post(() -> { if (terminalSink != null && generation == terminalGeneration)
                    terminalSink.success(("\r\n" + error + "\r\n").getBytes(StandardCharsets.UTF_8)); });
            } finally {
                synchronized (this) {
                    if (generation == terminalGeneration) terminalFd = -1;
                    Pty.close(fd);
                }
            }
            int exit = Pty.waitFor(pid);
            main.post(() -> { if (terminalSink != null && generation == terminalGeneration)
                terminalSink.success(("\r\n[session exited: " + exit + "]\r\n").getBytes(StandardCharsets.UTF_8)); });
        }, "ctos-terminal-output").start();
        return privileged ? "root · PTY" : "app · PTY";
    }

    private synchronized void stopTerminal() {
        terminalGeneration++;
        terminalFd = -1;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 81 || exportResult == null) return;
        try {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                try (java.io.OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                    stream.write(exportText.getBytes(StandardCharsets.UTF_8));
                }
                exportResult.success(true);
            } else exportResult.success(false);
        } catch (Exception error) { exportResult.error("EXPORT", error.toString(), null); }
        exportResult = null;
        exportText = null;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopTerminal();
        if (registered) unregisterReceiver(bridge);
        worker.shutdownNow();
        terminalWorker.shutdownNow();
        Collector.closeRoot();
        super.onDestroy();
    }
}
