package im.majo.dedsec.example;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import im.majo.dedsec.example.targets.HookTargets;

public final class WorkerService extends Service {
    public static final String LOG_TAG = "DedsecTargetWorker";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HookTargets targets = new HookTargets("worker");
        String message = "process=" + Application.getProcessName() + " result=" + targets.greet("service");
        Log.i(LOG_TAG, message);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
