package im.majo.ctos;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Activity-owned permission request; persistent process belongs to the Service. */
public final class HftpBridge {
    private static final int PERMISSION = 90;
    private final Activity activity;
    private MethodChannel.Result pending;
    private String host;
    private int port;

    public HftpBridge(Activity activity) { this.activity = activity; }

    public boolean handle(MethodCall call, MethodChannel.Result result) {
        if (call.method.equals("hftpStatus")) { result.success(HftpService.status()); return true; }
        if (call.method.equals("hftpStop")) {
            activity.stopService(new Intent(activity, HftpService.class));
            result.success(null);
            return true;
        }
        if (!call.method.equals("hftpStart")) return false;
        try {
            if (pending != null || HftpService.active()) throw new IllegalStateException("Service is already running or preparing");
            host = call.argument("host");
            String portValue = call.argument("port");
            port = Integer.parseInt(portValue == null ? "8080" : portValue);
            if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host)) || port < 1024 || port > 65535)
                throw new IllegalArgumentException("Expected a local bind address and port 1024-65535");
            pending = result;
            HftpService.createChannel(activity);
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION);
            else start();
        } catch (Exception error) { pending = null; result.error("HFTP", error.getMessage(), null); }
        return true;
    }

    public void onRequestPermissionsResult(int request, int[] results) {
        if (request != PERMISSION || pending == null) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            pending.error("HFTP", "HFTP needs notification permission to remain visibly active", null);
            pending = null;
            return;
        }
        start();
    }

    private void start() {
        MethodChannel.Result result = pending;
        pending = null;
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(HftpService.CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE)
                throw new IllegalStateException("Enable HFTP notifications in Android settings before starting");
            activity.startForegroundService(new Intent(activity, HftpService.class).putExtra("host", host).putExtra("port", port));
            result.success("{\"state\":\"starting\",\"reason\":\"\"}");
        } catch (Exception error) { result.error("HFTP", error.getMessage(), null); }
    }

    public void close() { pending = null; }
}
