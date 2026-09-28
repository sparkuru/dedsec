package im.majo.ctos;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends FlutterActivity {
    private static final String ROOT_PREFERENCES = "root_access";
    private static final String AUTO_ROOT = "auto_start";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ExecutorService terminalWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService pythonWorker = Executors.newSingleThreadExecutor();
    private PortablePackages portablePackages;
    private PythonRunner pythonRunner;
    private TaskHistoryStore taskHistoryStore;
    private ToolFiles toolFiles;
    private HftpBridge hftpBridge;
    private final Object rootLock = new Object();
    private volatile boolean root;
    private EventChannel.EventSink terminalSink;
    private volatile int terminalFd = -1;
    private volatile int terminalGeneration;
    private String exportText;
    private MethodChannel.Result exportResult;
    private volatile boolean destroyed;

    @Override public void configureFlutterEngine(FlutterEngine engine) {
        super.configureFlutterEngine(engine);
        portablePackages = new PortablePackages(this);
        pythonRunner = new PythonRunner(this, portablePackages);
        taskHistoryStore = new TaskHistoryStore(this);
        toolFiles = new ToolFiles(this);
        hftpBridge = new HftpBridge(this);
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/terminal").setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override public void onListen(Object arguments, EventChannel.EventSink sink) { terminalSink = sink; }
                    @Override public void onCancel(Object arguments) { terminalSink = null; }
                });
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/native").setMethodCallHandler(this::call);
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
        if (hftpBridge.handle(call, result)) return;
        if (call.method.equals("toolFilesClear") && pythonRunner.busy()) {
            result.error("FILES", "Wait for the current tool task before clearing files", null);
            return;
        }
        if (toolFiles.handle(call, result)) return;
        if (call.method.equals("pythonCancel")) {
            pythonRunner.cancel(call.argument("taskId"));
            result.success(null);
            return;
        }
        if (call.method.equals("pythonCatalog") || call.method.equals("pythonRun")) {
            if (call.method.equals("pythonRun") && !(call.argument("script") instanceof String)) {
                result.error("PARAMETERS", "A script ID is required", null);
                return;
            }
            Object requestedTaskId = call.argument("taskId");
            if (call.method.equals("pythonRun") && (!(requestedTaskId instanceof String)
                    || !((String) requestedTaskId).matches("[A-Za-z0-9_-]{1,128}"))) {
                result.error("PARAMETERS", "A task ID is required", null);
                return;
            }
            String taskId = call.method.equals("pythonRun") ? (String) requestedTaskId : "catalog";
            String script = call.method.equals("pythonRun") ? call.argument("script") : null;
            Object parameters = call.argument("params");
            if (taskId == null || !pythonRunner.claim(taskId)) {
                result.error("BUSY", "A Python task is already running", null);
                return;
            }
            pythonWorker.execute(() -> {
                try {
                    JSONObject envelope;
                    try {
                        envelope = pythonRunner.execute(script, parameters);
                    } catch (Exception error) {
                        if (!TaskHistoryStore.shouldPersist(script)) {
                            main.post(() -> result.error("PYTHON", error.toString(), null));
                            return;
                        }
                        envelope = failedTask(script, taskId, error);
                    }
                    if (TaskHistoryStore.shouldPersist(script)) {
                        try {
                            taskHistoryStore.appendResult(envelope, selectedInterface(parameters));
                        } catch (Exception error) {
                            envelope.put("historySaveError", error.toString());
                        }
                    }
                    String value = envelope.toString();
                    main.post(() -> result.success(value));
                } catch (Exception error) {
                    main.post(() -> result.error("PYTHON", error.toString(), null));
                }
            });
            return;
        }
        if (call.method.equals("export")) {
            if (exportResult != null) { result.error("BUSY", "An export is already open", null); return; }
            exportText = call.argument("text");
            exportResult = result;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "ctos-snapshot.json"), 81);
            return;
        }
        if (call.method.equals("taskHistoryList") || call.method.equals("taskHistoryClear")) {
            if (call.method.equals("taskHistoryClear") && pythonRunner.busy()) {
                result.error("HISTORY_BUSY", "Wait for the current read-only task before clearing history", null);
                return;
            }
            pythonWorker.execute(() -> {
                try {
                    if (call.method.equals("taskHistoryClear")) {
                        taskHistoryStore.clear();
                        main.post(() -> result.success(null));
                    } else {
                        String value = taskHistoryStore.listJson();
                        main.post(() -> result.success(value));
                    }
                } catch (Exception error) {
                    main.post(() -> result.error("HISTORY", error.toString(), null));
                }
            });
            return;
        }
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
                        if (kernel == null) kernel = NetworkSnapshot.apiInterfaces(data, "app / " +
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

    private static String selectedInterface(Object parameters) {
        if (!(parameters instanceof Map)) return null;
        Object value = ((Map<?, ?>) parameters).get("interface_name");
        return value instanceof String ? (String) value : null;
    }

    private static JSONObject failedTask(String script, String taskId, Exception error) throws Exception {
        return new JSONObject().put("script", script).put("taskId", taskId).put("environment", "App")
                .put("sdk", 2).put("state", "failed").put("startedAt", System.currentTimeMillis())
                .put("durationMs", 0).put("exitCode", 1).put("data", JSONObject.NULL)
                .put("stdout", "").put("stderr", error.toString()).put("truncated", false);
    }

    private static int dimension(Object value, int fallback) {
        return value instanceof Number ? Math.max(2, Math.min(500, ((Number) value).intValue())) : fallback;
    }

    private synchronized String startTerminal(boolean privileged, int columns, int rows) throws Exception {
        stopTerminal();
        if (destroyed) throw new IllegalStateException("Activity is closed");
        if (privileged && !root) throw new SecurityException("Root has not been authorized");
        String shellRc = "";
        String portableProblem = null;
        try { shellRc = portablePackages.shellRc(); }
        catch (Exception error) { portableProblem = error.toString(); }
        int[] process = Pty.start(privileged, columns, rows, getFilesDir().getAbsolutePath(), shellRc);
        int fd = process[0], pid = process[1], generation = terminalGeneration;
        terminalFd = fd;
        if (portableProblem != null) {
            String problem = portableProblem;
            main.post(() -> { if (terminalSink != null && generation == terminalGeneration)
                terminalSink.success(("\r\n[bundled environment unavailable: " + problem + "]\r\n")
                        .getBytes(StandardCharsets.UTF_8)); });
        }
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
        if (hftpBridge != null && hftpBridge.onActivityResult(requestCode, resultCode, data)) return;
        if (toolFiles != null && toolFiles.onActivityResult(requestCode, resultCode, data)) return;
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

    @Override protected void onStop() {
        if (pythonRunner != null) pythonRunner.cancel(null);
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hftpBridge != null) hftpBridge.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopTerminal();
        worker.shutdownNow();
        terminalWorker.shutdownNow();
        pythonRunner.close();
        toolFiles.close();
        hftpBridge.close();
        pythonWorker.shutdownNow();
        Collector.closeRoot();
        super.onDestroy();
    }
}
