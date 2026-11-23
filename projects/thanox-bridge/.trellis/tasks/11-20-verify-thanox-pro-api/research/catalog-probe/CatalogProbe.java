package im.majo.thanoxbridge.test.catalogprobe20261120;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

import java.util.List;

/** Read-only probe; its result contains counts and the dedicated target, never other package names. */
public final class CatalogProbe extends BroadcastReceiver {
    private static final String TARGET = "im.majo.thanoxbridge.test.identity20261120";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String PKG = "github.tornaco.android.thanos.core.pm.IPkgManager";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            setResultData(observe(context));
        } catch (Throwable error) {
            setResultData("error=" + error.getClass().getSimpleName());
        }
    }

    static String observe(Context context) throws Exception {
        PackageManager android = context.getPackageManager();
        List<ApplicationInfo> apps = android.getInstalledApplications(0);
        boolean listed = false;
        for (ApplicationInfo app : apps) {
            if (TARGET.equals(app.packageName)) listed = true;
        }
        int directUid;
        try {
            directUid = android.getApplicationInfo(TARGET, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            directUid = -1;
        }

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder root = (IBinder) serviceManager.getDeclaredMethod("getService", String.class)
                .invoke(null, "tv_input");
        if (root == null || !ROOT.equals(root.getInterfaceDescriptor()) ||
                !"9.1".equals(readString(root, 19)) ||
                !"I am Thanox!!!".equals(readString(root, 20))) {
            throw new IllegalStateException("unexpected service");
        }
        IBinder pm = readBinder(root, 4);
        if (pm == null || !PKG.equals(pm.getInterfaceDescriptor())) {
            throw new IllegalStateException("unexpected package manager");
        }
        String thanox = observeThanox(pm);
        return "callerUid=" + Process.myUid() + ";androidCount=" + apps.size()
                + ";androidListedTarget=" + listed + ";androidDirectTargetUid=" + directUid
                + ";" + thanox;
    }

    private static String observeThanox(IBinder pm) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PKG);
            data.writeInt(255); // AppInfo.FLAGS_ALL in the examined source revision.
            if (!pm.transact(3, data, reply, 0)) throw new IllegalStateException("unsupported");
            reply.readException();
            int count = reply.readInt();
            if (count < 0 || count > 10000) throw new IllegalStateException("invalid count");
            boolean found = false;
            int targetUid = -1;
            for (int i = 0; i < count; i++) {
                if (reply.readInt() == 0) continue;
                String packageName = reply.readString();
                reply.readString(); // icon URL
                reply.readString(); // label
                reply.readInt(); // version code
                reply.readString(); // version name
                reply.readString(); // APK path
                reply.readString(); // data directory
                reply.readInt(); // flags
                int uid = reply.readInt();
                int userId = reply.readInt();
                reply.readLong(); // first installation time
                reply.readLong(); // last update time
                reply.readInt(); // debuggable
                reply.readInt(); // minimum SDK
                reply.readInt(); // target SDK
                reply.readInt(); // enabled state
                reply.readInt(); // idle
                reply.readInt(); // dummy
                if (TARGET.equals(packageName) && userId == 0) {
                    found = true;
                    targetUid = uid;
                }
            }
            return "thanoxCount=" + count + ";thanoxListedTarget=" + found
                    + ";thanoxTargetUid=" + targetUid;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String readString(IBinder binder, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ROOT);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("unsupported");
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static IBinder readBinder(IBinder binder, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ROOT);
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("unsupported");
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
