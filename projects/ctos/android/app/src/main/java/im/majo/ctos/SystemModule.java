package im.majo.ctos;

import android.content.BroadcastReceiver;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SystemModule implements IXposedHookLoadPackage {
    public static final String QUERY = "im.majo.ctos.QUERY";
    public static final String RESULT = "im.majo.ctos.RESULT";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"android".equals(load.packageName) || !"android".equals(load.processName)) return;
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.am.ActivityManagerService", load.classLoader),
                "systemReady", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object thread = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
                            start((Context) XposedHelpers.callMethod(thread, "getSystemContext"));
                        } catch (Throwable error) { XposedBridge.log("ctos: " + error); }
                    }
                });
        XposedBridge.log("ctos: system_server entry loaded");
    }

    private static void start(Context context) {
        if (!STARTED.compareAndSet(false, true)) return;
        HandlerThread worker = new HandlerThread("ctos-network");
        worker.start();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            private long lastQuery;
            @Override public void onReceive(Context ignored, Intent request) {
                String nonce = request.getStringExtra("nonce");
                if (nonce == null || nonce.length() > 80) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastQuery < 500) return;
                lastQuery = now;
                try {
                    JSONObject snapshot = NetworkSnapshot.collect(context);
                    snapshot.put("source", "Vector / system_server");
                    Intent response = new Intent(RESULT).setPackage("im.majo.ctos")
                            .putExtra("nonce", nonce).putExtra("snapshot", snapshot.toString());
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.sendBroadcast(response, "im.majo.ctos.permission.QUERY",
                                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                    } else context.sendBroadcast(response, "im.majo.ctos.permission.QUERY");
                } catch (Throwable error) { XposedBridge.log("ctos query: " + error); }
            }
        };
        Handler handler = new Handler(worker.getLooper());
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(QUERY), "im.majo.ctos.permission.QUERY",
                handler, ContextCompat.RECEIVER_EXPORTED);
        XposedBridge.log("ctos: authenticated network bridge ready");
    }
}
