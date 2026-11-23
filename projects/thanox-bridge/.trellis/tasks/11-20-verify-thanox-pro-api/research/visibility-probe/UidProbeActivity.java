package im.majo.thanoxbridge.test.visibilityprobe20261120;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

/** Reads UID results for two fixed test packages and never enumerates other apps. */
public final class UidProbeActivity extends Activity {
    private static final String TARGET = "im.majo.thanoxbridge.test.visibletarget20261120";
    private static final String ABSENT = "im.majo.thanoxbridge.test.visibilityabsent20261120";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String PKG = "github.tornaco.android.thanos.core.pm.IPkgManager";
    private static final String TAG = "ThanoxUidProbe";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String token = getIntent().getStringExtra("token");
            if (token == null || !token.matches("[A-Za-z0-9]{1,32}")) {
                throw new IllegalArgumentException();
            }
            int callerUid = Process.myUid();
            if (Build.VERSION.SDK_INT != 36 || callerUid / 100000 != 0) {
                throw new IllegalStateException();
            }

            String androidTarget = androidUid(TARGET);
            String androidAbsent = androidUid(ABSENT);
            String thanoxTarget;
            String thanoxAbsent;
            try {
                IBinder pm = connect();
                thanoxTarget = thanoxUid(pm, TARGET);
                thanoxAbsent = thanoxUid(pm, ABSENT);
            } catch (Throwable error) {
                thanoxTarget = exceptionResult(error);
                thanoxAbsent = exceptionResult(error);
            }
            Log.i(TAG, "token=" + token + ";callerUid=" + callerUid
                    + ";androidTarget=" + androidTarget + ";androidAbsent=" + androidAbsent
                    + ";thanoxTarget=" + thanoxTarget + ";thanoxAbsent=" + thanoxAbsent);
        } catch (Throwable error) {
            Log.i(TAG, "error=" + error.getClass().getSimpleName());
        } finally {
            finish();
        }
    }

    private String androidUid(String packageName) {
        try {
            return Integer.toString(getPackageManager().getPackageUid(packageName, 0));
        } catch (Throwable error) {
            return exceptionResult(error);
        }
    }

    private static String thanoxUid(IBinder pm, String packageName) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PKG);
            data.writeInt(1);
            data.writeString(packageName);
            data.writeInt(0);
            if (!pm.transact(2, data, reply, 0)) throw new IllegalStateException();
            reply.readException();
            if (reply.dataAvail() != Integer.BYTES) throw new IllegalStateException();
            int uid = reply.readInt();
            if (reply.dataAvail() != 0) throw new IllegalStateException();
            return Integer.toString(uid);
        } catch (Throwable error) {
            return exceptionResult(error);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder connect() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder root = (IBinder) manager.getDeclaredMethod("getService", String.class)
                .invoke(null, "tv_input");
        if (root == null || !ROOT.equals(root.getInterfaceDescriptor()) ||
                !"9.1".equals(readString(root, 19)) ||
                !"I am Thanox!!!".equals(readString(root, 20))) {
            throw new IllegalStateException();
        }
        IBinder pm = readBinder(root, 4);
        if (pm == null || !PKG.equals(pm.getInterfaceDescriptor())) {
            throw new IllegalStateException();
        }
        return pm;
    }

    private static String readString(IBinder binder, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ROOT);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException();
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder readBinder(IBinder binder, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ROOT);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException();
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String exceptionResult(Throwable error) {
        return "error:" + error.getClass().getSimpleName();
    }
}
