package im.majo.thanoxbridge.test.audioprobe20261120;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Runs one requested stage against the separately signed task-owned target. */
public final class AudioProbeActivity extends Activity {
    private static final String TARGET = "im.majo.thanoxbridge.test.microphone20261120";
    private static final String PERMISSION = "android.permission.RECORD_AUDIO";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String OPS = "github.tornaco.android.thanos.core.ops.IOps";
    private static final int OP = 27;
    private static final List<String> MODES = Arrays.asList(
            "READ", "DENY", "IGNORE", "ASK", "ALLOW_ALWAYS", "ALLOW_FOREGROUND_ONLY");

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String token = intent.getStringExtra("token");
        String mode = intent.getStringExtra("mode");
        int expectedUid = intent.getIntExtra("uid", -1);
        StringBuilder out = new StringBuilder("token=").append(token);
        try {
            if (token == null || !token.matches("[a-z0-9-]{1,32}") ||
                    !MODES.contains(mode) || Build.VERSION.SDK_INT != 36 ||
                    Process.myUid() / 100000 != 0) {
                throw new IllegalArgumentException("invalid stage or environment");
            }
            int targetUid = getPackageManager().getPackageUid(TARGET, 0);
            if (targetUid != expectedUid || targetUid / 100000 != 0) {
                throw new IllegalStateException("target identity changed");
            }
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method method = manager.getDeclaredMethod("getService", String.class);
            IBinder service = (IBinder) method.invoke(null, "tv_input");
            if (service == null || !ROOT.equals(service.getInterfaceDescriptor()) ||
                    !"9.1".equals(readString(service, ROOT, 19, null)) ||
                    !"I am Thanox!!!".equals(readString(service, ROOT, 20, null))) {
                throw new IllegalStateException("incompatible service");
            }
            IBinder ops = readBinder(service, ROOT, 31);
            if (ops == null || !OPS.equals(ops.getInterfaceDescriptor()) ||
                    !"RECORD_AUDIO".equals(readString(ops, OPS, 3, OP)) ||
                    !PERMISSION.equals(readString(ops, OPS, 4, OP))) {
                throw new IllegalStateException("incompatible operation");
            }
            out.append(";callerUid=").append(Process.myUid());
            out.append(";targetUid=").append(targetUid);
            out.append(";op=").append(OP).append(";permission=").append(PERMISSION);
            out.append(";requested=").append(mode);
            out.append(";before=").append(readInfo(ops));
            out.append(";androidBefore=").append(getPackageManager().checkPermission(PERMISSION, TARGET));
            if (!"READ".equals(mode)) setMode(ops, mode);
            out.append(";after=").append(readInfo(ops));
            out.append(";androidAfter=").append(getPackageManager().checkPermission(PERMISSION, TARGET));
        } catch (Throwable error) {
            out.append(";error=").append(error.getClass().getName());
        } finally {
            Log.i("ThanoxAudioProbe", out.toString());
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
        } finally {
            data.recycle();
            reply.recycle();
        }
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
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String readInfo(IBinder ops) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(OP);
            writePkg(data);
            if (!ops.transact(2, data, reply, 0)) throw new IllegalStateException("read transact");
            reply.readException();
            if (reply.readInt() == 0) return "null";
            String result = reply.readString() + "," + (reply.readByte() != 0) + ","
                    + (reply.readByte() != 0) + "," + (reply.readByte() != 0);
            reply.readString();
            return result;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void setMode(IBinder ops, String mode) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(OP);
            writePkg(data);
            data.writeString(mode);
            if (!ops.transact(1, data, reply, 0)) throw new IllegalStateException("write transact");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void writePkg(Parcel data) {
        data.writeInt(1);
        data.writeString(TARGET);
        data.writeInt(0);
    }
}
