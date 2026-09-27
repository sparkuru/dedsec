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
import java.util.Collections;

/** An explicitly started, visible App-only file server with its own process. */
public final class HftpService extends Service {
    public static final String CHANNEL = "ctos_hftp", STOP = "im.majo.ctos.HFTP_STOP";
    private static final int NOTIFICATION = 1602;
    private static JSONObject state = initial();
    private static final HftpLogs logs = new HftpLogs();
    private static HftpService current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Process process;
    private HftpTreeBroker broker;
    private RootOperationAdapter relay;
    private android.net.ConnectivityManager.NetworkCallback wifiCallback;
    private boolean destroyed;
    private boolean stopRequested;
    private boolean failureRequested;
    private Object session;

    private static JSONObject initial() {
        try { return new JSONObject().put("state", "stopped").put("reason", "").put("rootRelay", false).put("relayReady", false); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static synchronized String status() {
        try { return new JSONObject(state.toString()).put("logs", logs.snapshot())
                .put("closing", current != null && (state.optString("state").equals("failed") || state.optString("state").equals("stopping"))).toString(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static synchronized String clearLogs() { logs.clear(); return status(); }
    public static synchronized boolean active() {
        return current != null || state.optString("state").equals("starting")
                || state.optString("state").equals("running") || state.optString("state").equals("stopping");
    }

    /** Fence callbacks before Android asynchronously delivers onDestroy. */
    public static void requestStop(android.content.Context context) {
        HftpService service;
        synchronized (HftpService.class) { service = current; }
        if (service != null) service.beginStop();
        context.stopService(new Intent(context, HftpService.class));
        if (service == null) clearStopped();
    }

    private static synchronized void clearStopped() {
        if (current != null) return;
        try {
            state.put("state", "stopped").put("reason", "").put("relayReady", false).put("urls", new JSONArray());
        } catch (Exception ignored) { state = initial(); }
    }
    private static synchronized void publish(Object session, JSONObject value) {
        if (logs.owns(session)) {
            value.remove("logs");
            value.remove("closing");
            state = value;
        }
    }

    public static void createChannel(android.content.Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "HFTP 文件服务", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            if (session == null) clearStopped();
            else beginStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active()) {
            HftpService existing;
            synchronized (HftpService.class) {
                existing = current;
                if (existing != null && existing != this)
                    logs.append(existing.session, "INFO", "Start ignored while the previous HFTP session is closing");
            }
            if (existing != this) {
                // A newly created FGS must be promoted even when its work is rejected.
                promote("正在结束上次文件服务");
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        Object started = new Object();
        session = started;
        synchronized (HftpService.class) { current = this; }
        logs.begin(started);
        try {
            String host = intent.getStringExtra("host");
            int port = intent.getIntExtra("port", -1);
            if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host)) || port < 1024 || port > 65535)
                throw new IllegalArgumentException("Invalid host or port");
            String tree = intent.getStringExtra("treeUri");
            HftpConfig config = new HftpConfig(host, port, intent.getIntExtra("maxUploadMiB", 32),
                    tree == null ? "" : tree, intent.getStringExtra("directoryName"), intent.getBooleanExtra("rootRelay", false));
            publish(started, config.json().put("state", "starting").put("reason", ""));
            String provider = config.treeUri.isEmpty() ? "App private library" : android.net.Uri.parse(config.treeUri).getAuthority();
            logs.append(started, "INFO", "Preparing HFTP; bind " + host + ":" + port + "; upload limit "
                    + config.maxUploadMiB + " MiB; directory provider " + provider);
            android.net.ConnectivityManager networks = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities capabilities = networks.getNetworkCapabilities(networks.getActiveNetwork());
            if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))
                logs.append(started, "WARN", "VPN is active; LAN availability may be restricted");
            promote(config.rootRelay ? "正在准备 Root Wi-Fi 中继" : "正在准备文件服务");
            main.postDelayed(() -> {
                if (live(started, null) && statusState().equals("starting")) fail(started, "Service startup timed out");
            }, 15000);
            main.postDelayed(() -> { if (live(started, null)) fail(started, "Five-hour session limit reached; start a new session manually"); }, 5 * 60 * 60 * 1000L);
            new Thread(() -> launch(started, config), "ctos-hftp").start();
        } catch (Exception error) { fail(started, "Cannot prepare file service: " + error.getClass().getSimpleName()); }
        return START_NOT_STICKY;
    }

    private static synchronized String statusState() { return state.optString("state"); }

    private void promote(String text) {
        createChannel(this);
        Notification notification = notification(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, notification);
    }

    private synchronized boolean live(Object owner, Process launched) {
        return !destroyed && !stopRequested && !failureRequested && session == owner && logs.owns(owner) && (launched == null || process == launched);
    }

    private synchronized void beginStop() {
        if (session == null || stopRequested || !logs.owns(session)) return;
        stopRequested = true;
        logs.append(session, "INFO", "Stop requested; closing owned HFTP processes and listener");
        try { publish(session, new JSONObject(status()).put("state", "stopping").put("reason", "").put("relayReady", false)); }
        catch (Exception ignored) { publish(session, initial()); }
    }

    private void launch(Object owner, HftpConfig config) {
        try {
            ToolExecutionContext appExecution = ToolExecutionContext.declare("hftp", ToolExecutionContext.Requirement.APP);
            if (appExecution.requirement != ToolExecutionContext.Requirement.APP) throw new IllegalStateException("Python requires the App execution context");
            RootOperationAdapter.WifiTarget wifi = config.rootRelay ? RootOperationAdapter.WifiTarget.select(this) : null;
            if (wifi != null) watchWifi(owner, wifi);
            PortablePackages.Package mounted = new PortablePackages(this).get("python");
            java.io.File root = ToolFiles.library(this);
            JSONObject parameters = new JSONObject().put("host", config.rootRelay ? "127.0.0.1" : config.host).put("port", config.rootRelay ? 0 : config.port)
                    .put("maxUploadMiB", config.maxUploadMiB);
            String directoryName = config.directoryName;
            if (config.treeUri.isEmpty()) {
                ToolFiles.cleanPartials(root, "\\.[a-f0-9]{32}\\.partial");
                parameters.put("root", root.getAbsolutePath());
            } else {
                HftpTreeBroker prepared = new HftpTreeBroker(this, android.net.Uri.parse(config.treeUri), config.maxUploadMiB);
                synchronized (this) {
                    if (!live(owner, null)) { prepared.close(); return; }
                    broker = prepared;
                }
                parameters.put("broker", prepared.socketName);
                directoryName = prepared.directoryName;
            }
            ProcessBuilder builder = new ProcessBuilder(mounted.tools.get("python3"), "-P", "-S", "-m", "ctos_tools.hftp").directory(root);
            builder.environment().putAll(mounted.environment);
            Process launched;
            synchronized (this) {
                if (!live(owner, null)) return;
                process = builder.start();
                launched = process;
            }
            Thread errors = new Thread(() -> drainErrors(owner, launched), "ctos-hftp-stderr");
            errors.setDaemon(true);
            errors.start();
            byte[] input = parameters.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream stdin = launched.getOutputStream()) { stdin.write(input); }
            JSONObject ready = new JSONObject(readLine(launched.getInputStream()));
            if (!ready.optBoolean("ready")) {
                String error = ready.optString("reason", "ServiceError");
                if (!error.matches("[A-Za-z][A-Za-z0-9]{0,47}")) error = "ServiceError";
                int code = ready.optInt("errno", 0);
                final String reason = "Python startup failed: " + error + (code > 0 && code <= 4096 ? " (errno " + code + ")" : "");
                main.post(() -> { if (live(owner, launched)) fail(owner, reason); });
                return;
            }
            int boundPort = ready.getInt("port");
            if (boundPort < 1024 || boundPort > 65535 || (!config.rootRelay && boundPort != config.port)) throw new IllegalStateException("Unexpected listening port");
            JSONArray addresses;
            if (config.rootRelay) {
                logs.append(owner, "INFO", "App Python backend ready on 127.0.0.1:" + boundPort + "; requesting Root network capability");
                RootOperationAdapter owned;
                synchronized (this) {
                    if (!live(owner, launched)) return;
                    owned = new RootOperationAdapter(this, ToolExecutionContext.declare("hftp.networkRelay", ToolExecutionContext.Requirement.ROOT),
                            wifi, config.port, boundPort, (level, message) -> { if (live(owner, launched)) logs.append(owner, level, message); },
                            reason -> main.post(() -> { if (live(owner, launched)) fail(owner, reason); }));
                    relay = owned;
                }
                owned.awaitReady();
                if (!wifiValid(wifi)) throw new RootOperationAdapter.RelayException("Wi-Fi network changed during Root relay startup");
                addresses = new JSONArray().put("http://" + wifi.address + ":" + config.port + "/");
            } else addresses = urls(config.host, boundPort);
            JSONObject running = config.json().put("state", "running").put("port", config.port)
                    .put("directoryName", directoryName).put("reason", "")
                    .put("startedAt", System.currentTimeMillis()).put("maxSessionHours", 5).put("urls", addresses)
                    .put("relayReady", config.rootRelay).put("backendPort", boundPort);
            main.post(() -> {
                synchronized (HftpService.this) {
                    if (!live(owner, launched) || !statusState().equals("starting")) return;
                    publish(owner, running);
                    logs.append(owner, "INFO", config.rootRelay ? "Root Wi-Fi listener active; Python and file operations remain App-owned" : "Listening on " + config.host + ":" + boundPort);
                    for (int index = 0; index < addresses.length(); index++) logs.append(owner, "INFO", "Access URL " + addresses.optString(index));
                    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification((config.rootRelay ? "Root Wi-Fi 中继运行中 · " : "文件服务运行中 · ") + config.port));
                }
            });
            consumeEvents(owner, launched);
            int exit = launched.waitFor();
            main.post(() -> { if (live(owner, launched)) fail(owner, "Service process exited (" + exit + ")"); });
        } catch (Exception error) {
            String reason = error instanceof RootOperationAdapter.RelayException ? error.getMessage() : error.getClass().getSimpleName();
            main.post(() -> { if (live(owner, null)) fail(owner, "Cannot start file service: " + reason); });
        }
    }

    private boolean wifiValid(RootOperationAdapter.WifiTarget wifi) {
        android.net.ConnectivityManager manager = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(wifi.network);
        return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && wifi.present(manager.getLinkProperties(wifi.network));
    }

    private void watchWifi(Object owner, RootOperationAdapter.WifiTarget wifi) {
        android.net.ConnectivityManager manager = getSystemService(android.net.ConnectivityManager.class);
        android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onLost(android.net.Network network) {
                if (network.equals(wifi.network) && live(owner, null)) fail(owner, "Root relay stopped because Wi-Fi was lost");
            }
            @Override public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties properties) {
                if (network.equals(wifi.network) && !wifi.present(properties) && live(owner, null)) fail(owner, "Root relay stopped because the Wi-Fi address changed");
            }
            @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities capabilities) {
                if (network.equals(wifi.network) && (!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) && live(owner, null)) fail(owner, "Root relay stopped because the Wi-Fi network changed");
            }
        };
        synchronized (this) {
            if (!live(owner, null)) return;
            manager.registerNetworkCallback(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main);
            wifiCallback = callback;
        }
    }

    private static JSONArray urls(String host, int port) throws Exception {
        JSONArray result = new JSONArray();
        if (host.equals("0.0.0.0")) {
            java.util.List<NetworkInterface> networks = Collections.list(NetworkInterface.getNetworkInterfaces());
            networks.sort(java.util.Comparator.comparing((NetworkInterface network) -> !network.getName().startsWith("wlan")));
            for (NetworkInterface network : networks) {
                String name = network.getName();
                if (!network.isUp() || network.isLoopback() || network.isVirtual() || network.isPointToPoint()
                        || name.startsWith("tun") || name.startsWith("tap") || name.startsWith("wg") || name.startsWith("ppp")) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses()))
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        result.put("http://" + address.getHostAddress() + ":" + port + "/");
            }
        }
        result.put("http://127.0.0.1:" + port + "/");
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
        if (value == -1 && bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private void drainErrors(Object owner, Process launched) {
        boolean reported = false;
        try (InputStream input = launched.getErrorStream()) {
            byte[] buffer = new byte[4096];
            while (input.read(buffer) != -1) {
                if (!reported && live(owner, launched)) logs.append(owner, "WARN", "Python stderr output suppressed (raw diagnostics are not exposed)");
                reported = true;
            }
        } catch (java.io.IOException ignored) { /* Process cancellation closes its streams. */ }
    }

    private void consumeEvents(Object owner, Process launched) throws Exception {
        boolean reported = false;
        try (InputStream input = launched.getInputStream()) {
            String line;
            while ((line = readLine(input)) != null) {
                if (!live(owner, launched)) return;
                try { logs.event(owner, new JSONObject(line)); }
                catch (org.json.JSONException invalid) {
                    if (!reported) logs.append(owner, "WARN", "Unrecognized Python output suppressed");
                    reported = true;
                }
            }
        }
    }

    private synchronized void fail(Object owner, String reason) {
        if (!live(owner, null)) return;
        failureRequested = true;
        logs.append(owner, "ERROR", reason);
        try { publish(owner, new JSONObject(status()).put("state", "failed").put("reason", reason).put("relayReady", false)); }
        catch (Exception ignored) { publish(owner, initial()); }
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) { fail(session, "Android background-service time limit reached"); }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (!statusState().equals("failed")) beginStop();
        RootOperationAdapter closingRelay;
        Process closingProcess;
        HftpTreeBroker closingBroker;
        Object closingSession;
        synchronized (this) {
            destroyed = true;
            if (wifiCallback != null) {
                try { getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(wifiCallback); }
                catch (IllegalArgumentException ignored) { /* Already removed by Android. */ }
                wifiCallback = null;
            }
            closingRelay = relay;
            relay = null;
            closingProcess = process;
            process = null;
            closingBroker = broker;
            broker = null;
            closingSession = session;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
        Thread cleanup = new Thread(() -> {
            try {
                if (closingRelay != null) closingRelay.close();
                if (closingProcess != null) {
                    closingProcess.destroyForcibly();
                    try { closingProcess.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (closingBroker != null) closingBroker.close();
            } finally {
                synchronized (HftpService.class) {
                    if (current == HftpService.this && logs.owns(closingSession)) {
                        logs.append(closingSession, "INFO", statusState().equals("failed") ? "Owned HFTP process and listener closed after failure" : "HFTP stopped; owned process and listener closed");
                        if (!statusState().equals("failed")) {
                            try { publish(closingSession, new JSONObject(status()).put("state", "stopped").put("reason", "").put("relayReady", false).put("urls", new JSONArray())); }
                            catch (Exception ignored) { publish(closingSession, initial()); }
                        }
                        current = null;
                    }
                }
            }
        }, "ctos-hftp-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
