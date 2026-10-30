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
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends FlutterActivity {
    private static final String ROOT_PREFERENCES = "root_access";
    private static final String AUTO_ROOT = "auto_start";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService terminalWorker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService pythonWorker = Executors.newSingleThreadExecutor();
    private PortablePackages portablePackages;
    private PythonRunner pythonRunner;
    private TaskHistoryStore taskHistoryStore;
    private ToolFiles toolFiles;
    private HftpBridge hftpBridge;
    private AppSecretsBridge appSecrets;
    private SshPasswordConnectBridge sshPasswordConnect;
    private final Object rootLock = new Object();
    private volatile boolean root;
    private EventChannel.EventSink terminalSink;
    private volatile int terminalFd = -1;
    private volatile int terminalGeneration;
    private SshTerminalState.Listener sshListener;
    private static final int SSH_NOTIFICATIONS = 87;
    private MethodChannel.Result notificationPermissionResult;
    private String terminalMode;

    /** Kept as the host factory contract; the foreground service owns its executor. */
    interface AuthenticatedSshFactory extends SshSessionOwner.Factory { }

    synchronized CompletableFuture<String> attachAuthenticatedSsh(AuthenticatedSshFactory factory, String mode,
            int columns, int rows) {
        if (destroyed || terminalFd >= 0) {
            CompletableFuture<String> result = new CompletableFuture<>();
            result.completeExceptionally(new IllegalStateException("Terminal owner is busy or closed"));
            return result;
        }
        return SshSessionService.attach(this, factory, mode, columns, rows);
    }

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
        appSecrets = new AppSecretsBridge(this);
        appSecrets.attachEvents(engine.getDartExecutor().getBinaryMessenger());
        sshPasswordConnect = new SshPasswordConnectBridge(this, appSecrets,
                engine.getDartExecutor().getBinaryMessenger(), this::attachAuthenticatedSsh);
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/terminal").setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override public void onListen(Object arguments, EventChannel.EventSink sink) {
                        if (destroyed) { sink.endOfStream(); return; }
                        SshSessionService.reconcile(MainActivity.this);
                        terminalSink = sink;
                        if (sshListener != null) SshSessionService.unsubscribe(sshListener);
                        sshListener = event -> {
                            if (!destroyed && terminalSink != null) terminalSink.success(event);
                        };
                        SshSessionService.subscribe(sshListener);
                        consumeSshIntent();
                    }
                    @Override public void onCancel(Object arguments) {
                        terminalSink = null;
                        if (sshListener != null) SshSessionService.unsubscribe(sshListener);
                        sshListener = null;
                    }
                });
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), "ctos/native").setMethodCallHandler(this::call);
    }

    private boolean autoRootEnabled() {
        return getSharedPreferences(ROOT_PREFERENCES, MODE_PRIVATE).getBoolean(AUTO_ROOT, true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (terminalSink != null) consumeSshIntent();
    }

    private void consumeSshIntent() {
        Intent intent = getIntent();
        if (intent != null && SshSessionService.SHOW.equals(intent.getAction())) {
            SshSessionService.focus(intent);
            intent.setAction(null);
        }
    }

    private void handleSshNotifications(MethodCall call, MethodChannel.Result result) {
        if (call.arguments != null && (!(call.arguments instanceof Map) || !((Map<?, ?>) call.arguments).isEmpty())) {
            result.error("PARAMETERS", "No notification parameters are accepted", null);
            return;
        }
        SshSessionService.createChannel(this);
        if (call.method.equals("ssh.requestNotifications") && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (notificationPermissionResult != null) {
                result.error("BUSY", "Notification permission request is pending", null);
                return;
            }
            android.content.SharedPreferences preferences = getSharedPreferences("ssh_notifications", MODE_PRIVATE);
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply();
                notificationPermissionResult = result;
                try { requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, SSH_NOTIFICATIONS); }
                catch (RuntimeException failure) {
                    notificationPermissionResult = null;
                    result.error("SSH_NOTIFICATIONS", "Notification permission could not be requested", null);
                }
                return;
            }
        }
        result.success(VaultCollections.map("enabled", SshSessionService.notificationsEnabled(this)));
    }

    private void handleSshTerminal(MethodCall call, MethodChannel.Result result) {
        try {
            Object value = call.argument("generation");
            if (!(value instanceof Integer) && !(value instanceof Long))
                throw new IllegalArgumentException("Terminal generation is required");
            long expected = ((Number) value).longValue();
            if (expected <= 0 || expected > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid terminal generation");
            CompletableFuture<Void> operation;
            switch (call.method) {
                case "terminalWrite": {
                    Object text = call.argument("text");
                    if (!(text instanceof String) || ((String) text).length() > SshTerminalSession.INPUT_LIMIT)
                        throw new IllegalArgumentException("Invalid terminal input");
                    byte[] bytes = ((String) text).getBytes(StandardCharsets.UTF_8);
                    try {
                        if (bytes.length > SshTerminalSession.INPUT_LIMIT)
                            throw new IllegalArgumentException("Terminal input is too large");
                        operation = SshSessionService.write((int) expected, bytes);
                    } finally { java.util.Arrays.fill(bytes, (byte) 0); }
                    break;
                }
                case "terminalResize":
                    operation = SshSessionService.resize((int) expected, sshDimension(call.argument("columns")),
                            sshDimension(call.argument("rows")));
                    break;
                case "terminalStop": operation = SshSessionService.stop((int) expected); break;
                default: result.notImplemented(); return;
            }
            operation.whenComplete((ignored, failure) -> main.post(() -> {
                if (failure == null) result.success(null);
                else result.error("CTOS", "SSH terminal is stale or closed", null);
            }));
        } catch (RuntimeException failure) { result.error("PARAMETERS", "Invalid terminal request", null); }
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
        if (call.method.equals("ssh.notificationStatus") || call.method.equals("ssh.requestNotifications")) {
            handleSshNotifications(call, result);
            return;
        }
        if (call.method.startsWith("terminal") && !call.method.equals("terminalStart")
                && SshSessionService.active()) {
            handleSshTerminal(call, result);
            return;
        }
        if (sshPasswordConnect != null && sshPasswordConnect.handle(call, result)) return;
        if (appSecrets != null && appSecrets.handle(call, result)) return;
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
                if (call.method.startsWith("terminal") && !call.method.equals("terminalStart")) {
                    Object expected = call.argument("generation");
                    if (expected != null && (!(expected instanceof Number)
                            || ((Number) expected).intValue() != terminalGeneration))
                        throw new IllegalStateException("Terminal generation is stale");
                }
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
                            String input = call.argument("text");
                            if (input == null) throw new IllegalArgumentException("Terminal input is required");
                            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
                            if (terminalFd < 0) throw new IllegalStateException("Terminal is closed");
                            Pty.write(terminalFd, bytes);
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

    private static int sshDimension(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("Invalid terminal dimensions");
        long number = ((Number) value).longValue();
        if (number < 1 || number > 4096) throw new IllegalArgumentException("Invalid terminal dimensions");
        return (int) number;
    }

    private static Map<String, Object> terminalData(int generation, byte[] bytes) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "data");
        event.put("generation", generation);
        event.put("bytes", bytes);
        return event;
    }

    private void publishTerminalState(int generation, String state, String mode, boolean remote,
            Long exitStatus, String reason) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "state");
        event.put("generation", generation);
        event.put("state", state);
        event.put("mode", mode);
        event.put("remote", remote);
        if (exitStatus != null) event.put("exitStatus", exitStatus);
        if (reason != null) event.put("reason", reason);
        main.post(() -> {
            if (!destroyed && generation == terminalGeneration && terminalSink != null)
                terminalSink.success(event);
        });
    }

    private void publishLocalData(int generation, byte[] bytes) {
        main.post(() -> {
            if (!destroyed && terminalSink != null && generation == terminalGeneration)
                terminalSink.success(terminalData(generation, bytes));
        });
    }

    private synchronized String startTerminal(boolean privileged, int columns, int rows) throws Exception {
        if (SshSessionService.active()) throw new IllegalStateException("SSH terminal is active or cleanup is pending");
        stopTerminal();
        if (destroyed) throw new IllegalStateException("Activity is closed");
        if (privileged && !root) throw new SecurityException("Root has not been authorized");
        String shellRc = "";
        String portableProblem = null;
        try { shellRc = portablePackages.shellRc(); }
        catch (Exception error) { portableProblem = error.toString(); }
        int[] process = Pty.start(privileged, columns, rows, getFilesDir().getAbsolutePath(), shellRc);
        int fd = process[0], pid = process[1], generation = SshTerminalState.nextGeneration();
        terminalGeneration = generation;
        String mode = privileged ? "root · PTY" : "app · PTY";
        terminalMode = mode;
        terminalFd = fd;
        publishTerminalState(generation, "running", mode, false, null, null);
        if (portableProblem != null) publishLocalData(generation,
                ("\r\n[bundled environment unavailable: " + portableProblem + "]\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        new Thread(() -> {
            String failure = null;
            try {
                while (generation == terminalGeneration && terminalFd == fd) {
                    byte[] bytes = Pty.read(fd);
                    if (bytes == null) break;
                    if (bytes.length != 0) publishLocalData(generation, bytes);
                }
            } catch (Exception error) { failure = "TERMINAL_IO"; }
            finally {
                synchronized (this) {
                    if (generation == terminalGeneration && terminalFd == fd) terminalFd = -1;
                    Pty.close(fd);
                }
            }
            int exit = Pty.waitFor(pid);
            publishTerminalState(generation, failure == null ? "closed" : "failed", mode, false,
                    (long) exit, failure);
        }, "ctos-terminal-output").start();
        return mode;
    }

    private synchronized void stopTerminal() {
        if (terminalFd >= 0) {
            terminalFd = -1;
            publishTerminalState(terminalGeneration, "closed", terminalMode, false, null, null);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (sshPasswordConnect != null && sshPasswordConnect.onActivityResult(requestCode, resultCode, data)) return;
        if (appSecrets != null && appSecrets.onActivityResult(requestCode, resultCode, data)) return;
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
        if (appSecrets != null) appSecrets.onStop();
        if (pythonRunner != null) pythonRunner.cancel(null);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        SshSessionService.reconcile(this);
        if (appSecrets != null) appSecrets.onResume();
    }

    @Override protected void onPause() {
        if (appSecrets != null) appSecrets.onPause();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SSH_NOTIFICATIONS && notificationPermissionResult != null) {
            MethodChannel.Result result = notificationPermissionResult;
            notificationPermissionResult = null;
            result.success(VaultCollections.map("enabled", SshSessionService.notificationsEnabled(this)));
        }
        if (hftpBridge != null) hftpBridge.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (sshPasswordConnect != null) sshPasswordConnect.close();
        if (appSecrets != null) appSecrets.close();
        if (sshListener != null) SshSessionService.unsubscribe(sshListener);
        sshListener = null;
        terminalSink = null;
        terminalWorker.execute(() -> {
            stopTerminal();
            terminalWorker.shutdown();
        });
        if (notificationPermissionResult != null) {
            notificationPermissionResult.error("SSH_CANCELLED", "Activity closed", null);
            notificationPermissionResult = null;
        }
        worker.shutdownNow();
        pythonRunner.close();
        toolFiles.close();
        hftpBridge.close();
        pythonWorker.shutdownNow();
        Collector.closeRoot();
        super.onDestroy();
    }
}
