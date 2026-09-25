package im.majo.ctos;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileReader;

public final class DeviceSnapshot {
    private DeviceSnapshot() {}

    public static JSONObject collect(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("system", section("Android API", () -> new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("kernel", System.getProperty("os.version", ""))
                .put("architectures", new JSONArray(Build.SUPPORTED_ABIS))
                .put("uptimeMs", SystemClock.elapsedRealtime())));
        result.put("memory", section("ActivityManager.MemoryInfo", () -> {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new UnsupportedOperationException("ActivityManager unavailable");
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return new JSONObject().put("totalBytes", info.totalMem)
                    .put("availableBytes", info.availMem).put("low", info.lowMemory);
        }));
        result.put("cpu", section("/proc/loadavg", () -> {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
                String line = reader.readLine();
                if (line == null) throw new IllegalStateException("Empty load average");
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 3) throw new IllegalStateException("Invalid load average");
                return new JSONObject().put("load1", fields[0]).put("load5", fields[1])
                        .put("load15", fields[2]).put("cores", Runtime.getRuntime().availableProcessors());
            }
        }));
        result.put("battery", section("ACTION_BATTERY_CHANGED", () -> {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) throw new UnsupportedOperationException("Battery data unavailable");
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            Object percent = level >= 0 && scale > 0
                    ? Integer.valueOf(Math.round(level * 100f / scale)) : JSONObject.NULL;
            Object temperatureC = temperature != Integer.MIN_VALUE
                    ? Double.valueOf(temperature / 10.0) : JSONObject.NULL;
            return new JSONObject()
                    .put("percent", percent)
                    .put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                    .put("temperatureC", temperatureC);
        }));
        result.put("storage", section("StatFs /data", () -> {
            StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return new JSONObject().put("totalBytes", stats.getTotalBytes())
                    .put("availableBytes", stats.getAvailableBytes());
        }));
        return result;
    }

    private interface Reader { JSONObject read() throws Exception; }

    private static JSONObject section(String source, Reader reader) throws Exception {
        JSONObject result = new JSONObject().put("source", source)
                .put("capturedAt", System.currentTimeMillis());
        try {
            result.put("state", "available").put("data", reader.read());
        } catch (SecurityException error) {
            result.put("state", "permission_denied").put("reason", error.toString());
        } catch (UnsupportedOperationException error) {
            result.put("state", "unsupported").put("reason", error.toString());
        } catch (Exception error) {
            String reason = error.toString();
            String state = reason.contains("Permission denied") || reason.contains("EACCES")
                    ? "permission_denied" : "failed";
            result.put("state", state).put("reason", reason);
        }
        return result;
    }
}
