package dev.thanoxbridge.probe.perm20261120;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** A single, explicitly requested operation on the task-owned disposable target. */
public final class StageProbeActivity extends Activity {
    private static final String TARGET = "dev.thanoxbridge.target.perm20261120";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String OPS = "github.tornaco.android.thanos.core.ops.IOps";
    private static final List<String> MODES = Arrays.asList(
            "READ", "DENY", "IGNORE", "ASK", "ALLOW_ALWAYS", "ALLOW_FOREGROUND_ONLY");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String token = intent.getStringExtra("token");
        String mode = intent.getStringExtra("mode");
        int code = intent.getIntExtra("op", -1);
        StringBuilder out = new StringBuilder("token=").append(token);
        try {
            if (token == null || !token.matches("[a-z0-9-]{1,32}")
                    || !MODES.contains(mode) || (code != 26 && code != 3)) {
                throw new IllegalArgumentException("invalid stage");
            }
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method method = manager.getDeclaredMethod("getService", String.class);
            IBinder service = (IBinder) method.invoke(null, "tv_input");
            if (service == null || !ROOT.equals(service.getInterfaceDescriptor())) {
                throw new IllegalStateException("service descriptor");
            }
            String version = readString(service, ROOT, 19, null);
            if (!"9.1".equals(version)) throw new IllegalStateException("version=" + version);
            IBinder ops = readBinder(service, ROOT, 31);
            if (ops == null || !OPS.equals(ops.getInterfaceDescriptor())) {
                throw new IllegalStateException("ops descriptor");
            }
            String permission = readString(ops, OPS, 4, code);
            String expected = code == 26 ? "android.permission.CAMERA" : "android.permission.VIBRATE";
            if (!expected.equals(permission)) throw new IllegalStateException("unexpected permission");
            out.append(";callerUid=").append(Process.myUid());
            out.append(";targetUid=").append(getPackageManager().getPackageUid(TARGET, 0));
            out.append(";op=").append(code).append(";permission=").append(permission);
            out.append(";requested=").append(mode);
            out.append(";before=").append(readInfo(ops, code));
            out.append(";androidBefore=").append(getPackageManager().checkPermission(permission, TARGET));
            if (!"READ".equals(mode)) setMode(ops, code, mode);
            out.append(";after=").append(readInfo(ops, code));
            out.append(";androidAfter=").append(getPackageManager().checkPermission(permission, TARGET));
        } catch (Throwable error) {
            out.append(";error=").append(error.getClass().getName()).append(':')
                    .append(String.valueOf(error.getMessage()).replace('\n', ' '));
        } finally {
            Log.i("ThanoxPermStage", out.toString());
            finish();
        }
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

    private static String readString(IBinder binder, String descriptor, int code, Integer argument)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (argument != null) data.writeInt(argument);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("string transact");
            reply.readException();
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static String readInfo(IBinder ops, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(code);
            writePkg(data);
            if (!ops.transact(2, data, reply, 0)) throw new IllegalStateException("read transact");
            reply.readException();
            if (reply.readInt() == 0) return "null";
            String result = reply.readString() + "," + (reply.readByte() != 0) + ","
                    + (reply.readByte() != 0) + "," + (reply.readByte() != 0);
            reply.readString();
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void setMode(IBinder ops, int code, String mode) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(code);
            writePkg(data);
            data.writeString(mode);
            if (!ops.transact(1, data, reply, 0)) throw new IllegalStateException("write transact");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void writePkg(Parcel data) {
        data.writeInt(1);
        data.writeString(TARGET);
        data.writeInt(0);
    }
}
