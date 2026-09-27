package im.majo.ctos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;

/** An explicitly started, visible App-only file server with its own process. */
public final class HftpService extends Service {
    public static final String CHANNEL = "ctos_hftp", STOP = "im.majo.ctos.HFTP_STOP";
    private static final int NOTIFICATION = 1602;
    private static JSONObject state = initial();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Process process;
    private boolean destroyed;

    private static JSONObject initial() {
        try { return new JSONObject().put("state", "stopped").put("reason", ""); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static synchronized String status() { return state.toString(); }
    public static synchronized boolean active() {
        return state.optString("state").equals("starting") || state.optString("state").equals("running");
    }
    private static synchronized void publish(JSONObject value) { state = value; }

    public static void createChannel(android.content.Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "HFTP 文件服务", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active()) return START_NOT_STICKY;
        try {
            String host = intent.getStringExtra("host");
            int port = intent.getIntExtra("port", -1);
            if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host)) || port < 1024 || port > 65535)
                throw new IllegalArgumentException("Invalid host or port");
            byte[] secret = new byte[24];
            new SecureRandom().nextBytes(secret);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            publish(new JSONObject().put("state", "starting").put("host", host).put("port", port)
                    .put("reason", "").put("password", password));
            createChannel(this);
            Notification notification = notification("正在准备文件服务");
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification);
            main.postDelayed(() -> {
                if (statusState().equals("starting")) fail("Service startup timed out");
            }, 15000);
            main.postDelayed(() -> fail("Five-hour session limit reached; start a new session manually"), 5 * 60 * 60 * 1000L);
            new Thread(() -> launch(host, port, password), "ctos-hftp").start();
        } catch (Exception error) { fail(error.getMessage()); }
        return START_NOT_STICKY;
    }

    private static synchronized String statusState() { return state.optString("state"); }

    private void launch(String host, int port, String password) {
        try {
            PortablePackages.Package mounted = new PortablePackages(this).get("python");
            java.io.File root = ToolFiles.library(this);
            ToolFiles.cleanPartials(root, "\\.[a-f0-9]{32}\\.partial");
            ProcessBuilder builder = new ProcessBuilder(mounted.tools.get("python3"), "-P", "-S", "-m", "ctos_tools.hftp").directory(root);
            builder.environment().putAll(mounted.environment);
            Process launched;
            synchronized (this) {
                if (destroyed) return;
                process = builder.start();
                launched = process;
            }
            Thread errors = new Thread(() -> drain(launched.getErrorStream()), "ctos-hftp-stderr");
            errors.setDaemon(true);
            errors.start();
            byte[] input = new JSONObject().put("root", root.getAbsolutePath()).put("host", host).put("port", port)
                    .put("password", password).toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream stdin = launched.getOutputStream()) { stdin.write(input); }
            JSONObject ready = new JSONObject(readLine(launched.getInputStream()));
            if (!ready.optBoolean("ready")) throw new IllegalStateException(ready.optString("reason", "Service did not become ready"));
            JSONObject running = new JSONObject().put("state", "running").put("host", host).put("port", ready.getInt("port"))
                    .put("password", password).put("user", "ctos").put("reason", "")
                    .put("startedAt", System.currentTimeMillis()).put("maxSessionHours", 5).put("urls", urls(host, port));
            main.post(() -> {
                synchronized (HftpService.this) {
                    if (destroyed || process != launched || !statusState().equals("starting")) return;
                    publish(running);
                    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification("文件服务运行中 · " + port));
                }
            });
            drain(launched.getInputStream());
            int exit = launched.waitFor();
            main.post(() -> { if (!destroyed) fail("Service process exited (" + exit + ")"); });
        } catch (Exception error) {
            String reason = error instanceof IllegalStateException ? error.getMessage() : error.getClass().getSimpleName();
            main.post(() -> { if (!destroyed) fail("Cannot start file service: " + reason); });
        }
    }

    private static JSONArray urls(String host, int port) throws Exception {
        JSONArray result = new JSONArray();
        result.put("http://127.0.0.1:" + port + "/");
        if (host.equals("0.0.0.0")) for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (java.net.InetAddress address : Collections.list(network.getInetAddresses()))
                if (address instanceof Inet4Address && !address.isLoopbackAddress())
                    result.put("http://" + address.getHostAddress() + ":" + port + "/");
        }
        return result;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 16, new Intent(this, HftpService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("ctOS HFTP").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private static String readLine(InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = stream.read()) != -1 && value != '\n') {
            if (bytes.size() >= 4096) throw new IllegalStateException("Invalid service response");
            bytes.write(value);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static void drain(InputStream stream) {
        try (InputStream input = stream) {
            byte[] buffer = new byte[4096];
            while (input.read(buffer) != -1) { /* No persistent access or credential logs. */ }
        } catch (java.io.IOException ignored) { /* Process cancellation closes its streams. */ }
    }

    private void fail(String reason) {
        try { publish(new JSONObject().put("state", "failed").put("reason", reason == null ? "Service stopped" : reason)); }
        catch (Exception ignored) { publish(initial()); }
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) { fail("Android background-service time limit reached"); }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        synchronized (this) {
            destroyed = true;
            if (process != null) {
                process.destroyForcibly();
                try { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            process = null;
        }
        if (!statusState().equals("failed")) publish(initial());
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
