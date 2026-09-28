package im.majo.ctos;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One bounded subprocess; interactive Python lives separately in the PTY. */
public final class PythonRunner {
    private final Context context;
    private final PortablePackages packages;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final long timeoutMillis;
    private Process process;
    private String taskId;
    private boolean cancelled;
    private boolean timedOut;

    public PythonRunner(Context context, PortablePackages packages) {
        this(context, packages, 15000);
    }

    PythonRunner(Context context, PortablePackages packages, long timeoutMillis) {
        this.context = context.getApplicationContext();
        this.packages = packages;
        this.timeoutMillis = timeoutMillis;
    }

    public synchronized boolean claim(String id) {
        if (taskId != null) return false;
        taskId = id;
        cancelled = false;
        timedOut = false;
        return true;
    }

    public synchronized boolean busy() { return taskId != null; }

    public synchronized void cancel(String id) {
        if (taskId == null || (id != null && !id.equals(taskId))) return;
        cancelled = true;
        if (process != null) process.destroyForcibly();
    }

    public synchronized void close() { cancel(null); watchdog.shutdownNow(); }

    public JSONObject execute(String script, Object parameters) throws Exception {
        long startedAt = System.currentTimeMillis();
        long started = android.os.SystemClock.elapsedRealtime();
        ScheduledFuture<?> deadline = null;
        try {
            if (parameters != null && !(parameters instanceof java.util.Map))
                throw new IllegalArgumentException("Parameters must be a map");
            JSONObject values = parameters == null ? new JSONObject() : new JSONObject((java.util.Map<?, ?>) parameters);
            PortablePackages.Package mounted = packages.get("python");
            List<String> command = new ArrayList<>(Arrays.asList(mounted.tools.get("python3"), "-P", "-S", "-m", "ctos_workbench"));
            if (script == null) command.add("catalog");
            else {
                if (!script.matches("[a-z][a-z0-9_.]{0,63}")) throw new IllegalArgumentException("Invalid script ID");
                command.addAll(Arrays.asList("run", script));
            }
            File workspace = new File(context.getFilesDir(), "workbench");
            if (!workspace.isDirectory() && !workspace.mkdirs()) throw new IllegalStateException("Cannot create workspace");
            ToolFiles.cleanPartials(ToolFiles.store(context), "[a-f0-9]{32}\\.output\\.partial");
            JSONObject input = new JSONObject().put("params", values).put("workdir", workspace.getAbsolutePath())
                    .put("filesRoot", ToolFiles.store(context).getAbsolutePath())
                    .put("device", script == null ? new JSONObject() : DeviceSnapshot.collect(context));
            if ("network.interface_diagnose".equals(script)) {
                Object value = values.opt("interface_name");
                if (!(value instanceof String)) throw new IllegalArgumentException("Interface name is required");
                input.put("network", NetworkSnapshot.forInterface(context, (String) value));
            }
            byte[] bytes = input.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 32768) throw new IllegalArgumentException("Input exceeds 32 KiB");
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace);
            builder.environment().putAll(mounted.environment);
            synchronized (this) {
                if (cancelled) return stopped(script, startedAt, started);
                process = builder.start();
                Process launched = process;
                deadline = watchdog.schedule(() -> {
                    synchronized (PythonRunner.this) {
                        if (process != launched) return;
                        timedOut = true;
                        launched.destroyForcibly();
                    }
                }, timeoutMillis, TimeUnit.MILLISECONDS);
            }
            Process running = process;
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            Thread errors = new Thread(() -> {
                try (java.io.InputStream stderr = running.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stderr.read(buffer)) != -1) {
                        int retained = Math.min(count, 16384 - diagnostics.size());
                        if (retained > 0) diagnostics.write(buffer, 0, retained);
                    }
                } catch (java.io.IOException ignored) {
                    // Cancellation closes process streams; the task state records it.
                }
            }, "ctos-python-stderr");
            errors.setDaemon(true);
            errors.start();
            try (java.io.OutputStream stdin = process.getOutputStream()) { stdin.write(bytes); }
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            try (java.io.InputStream stdout = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stdout.read(buffer)) != -1) {
                    if (collected.size() + count > 262144) throw new IllegalStateException("Python output exceeds 256 KiB");
                    collected.write(buffer, 0, count);
                }
            }
            int exit = process.waitFor();
            errors.join(1000);
            synchronized (this) {
                if (cancelled || timedOut) return stopped(script, startedAt, started);
            }
            String output = new String(collected.toByteArray(), StandardCharsets.UTF_8);
            String warnings = new String(diagnostics.toByteArray(), StandardCharsets.UTF_8);
            if (exit != 0) throw new IllegalStateException("Python exited " + exit + ": " + warnings.substring(0, Math.min(warnings.length(), 4096)));
            JSONObject result = new JSONObject(output);
            if (script == null) result.put("packages", packages.manifests()).put("terminal", "python3").put("warnings", warnings);
            else result.put("taskId", taskId).put("startedAt", startedAt)
                    .put("durationMs", android.os.SystemClock.elapsedRealtime() - started)
                    .put("stderr", (result.optString("stderr") + warnings).substring(0,
                            Math.min(16384, result.optString("stderr").length() + warnings.length())));
            return result;
        } catch (Exception error) {
            synchronized (this) {
                if (cancelled || timedOut) return stopped(script, startedAt, started);
            }
            throw error;
        } finally {
            if (deadline != null) deadline.cancel(false);
            synchronized (this) {
                if (process != null) {
                    process.destroyForcibly();
                    try { process.waitFor(500, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                process = null;
                taskId = null;
            }
        }
    }

    private JSONObject stopped(String script, long startedAt, long started) throws Exception {
        if (script == null) throw new TimeoutException(cancelled ? "Python preparation cancelled" : "Python preparation timed out");
        return new JSONObject().put("script", script).put("taskId", taskId).put("environment", "App")
                .put("sdk", 2).put("state", cancelled ? "cancelled" : "timed_out")
                .put("startedAt", startedAt).put("durationMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("exitCode", cancelled ? 130 : 124).put("stdout", "").put("stderr", "")
                .put("data", JSONObject.NULL).put("truncated", false);
    }
}
