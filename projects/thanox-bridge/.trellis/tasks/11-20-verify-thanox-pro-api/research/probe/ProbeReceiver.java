package dev.thanoxbridge.probe.verify20261120;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class ProbeReceiver extends BroadcastReceiver {
    private static final String THANOS = "github.tornaco.android.thanos.core.IThanos";
    private static final String PACKAGE_MANAGER = "github.tornaco.android.thanos.core.pm.IPkgManager";
    private static final String TARGET = "dev.thanoxbridge.target.verify20261120";

    @Override
    public void onReceive(Context context, Intent intent) {
        String result = probe(context);
        Log.i("ThanoxProbe", result);
        setResultCode(0);
        setResultData(result);
    }

    static String probe(Context context) {
        StringBuilder result = new StringBuilder();
        result.append("uid=").append(Process.myUid());
        result.append(";targetSdk=").append(context.getApplicationInfo().targetSdkVersion);
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getDeclaredMethod("getService", String.class);
            IBinder service = (IBinder) getService.invoke(null, "tv_input");
            if (service == null) {
                result.append(";service=null");
            } else {
                result.append(";descriptor=").append(service.getInterfaceDescriptor());
                result.append(";version=").append(readString(service, THANOS, 19));
                result.append(";identity=").append(readString(service, THANOS, 20));
                IBinder pm = readBinder(service, THANOS, 4);
                if (pm == null) {
                    result.append(";pkgManager=null");
                } else {
                    result.append(";pkgDescriptor=").append(pm.getInterfaceDescriptor());
                    result.append(";ownUidPackages=").append(Arrays.toString(readOwnPackages(pm)));
                    appendBoolean(result, "appEnabled", pm, PACKAGE_MANAGER, 10, context, true);
                    appendBoolean(result, "smartFreeze", pm, PACKAGE_MANAGER, 24, context, true);
                    appendComponentState(result, pm, context);
                    appendTargetExperiment(result, pm, context);
                }
                IBinder activity = readBinder(service, THANOS, 3);
                if (activity != null) {
                    String descriptor = "github.tornaco.android.thanos.core.app.IActivityManager";
                    result.append(";activityDescriptor=").append(activity.getInterfaceDescriptor());
                    appendBoolean(result, "appRunning", activity, descriptor, 17, context, true);
                    appendBoolean(result, "startBlocked", activity, descriptor, 25, context, true);
                    appendBoolean(result, "backgroundRestricted", activity, descriptor, 39, context, true);
                }
                IBinder profile = readBinder(service, THANOS, 11);
                if (profile != null) {
                    String descriptor = "github.tornaco.android.thanos.core.profile.IProfileManager";
                    result.append(";profileDescriptor=").append(profile.getInterfaceDescriptor());
                    appendBoolean(result, "profileEnabled", profile, descriptor, 13, context, false);
                }
            }
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            result.append(";error=").append(error.getClass().getName());
            if (error.getMessage() != null) {
                result.append(":").append(error.getMessage().replace('\n', ' '));
            }
        }
        return result.toString();
    }

    private static String readString(IBinder binder, String descriptor, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (!binder.transact(code, data, reply, 0)) {
                return "transaction-not-handled";
            }
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder readBinder(IBinder binder, String descriptor, int code) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (!binder.transact(code, data, reply, 0)) {
                return null;
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String[] readOwnPackages(IBinder binder) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PACKAGE_MANAGER);
            data.writeInt(Process.myUid());
            if (!binder.transact(1, data, reply, 0)) {
                return new String[] {"transaction-not-handled"};
            }
            reply.readException();
            return reply.createStringArray();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void appendBoolean(StringBuilder out, String label, IBinder binder,
            String descriptor, int code, Context context, boolean withPkg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (withPkg) {
                data.writeInt(1);
                data.writeString(context.getPackageName());
                data.writeInt(0);
            }
            if (!binder.transact(code, data, reply, 0)) {
                out.append(';').append(label).append("=transaction-not-handled");
                return;
            }
            reply.readException();
            out.append(';').append(label).append('=').append(reply.readInt() != 0);
        } catch (Throwable error) {
            out.append(';').append(label).append("=ERROR:").append(error.getClass().getSimpleName());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void appendComponentState(StringBuilder out, IBinder binder, Context context) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PACKAGE_MANAGER);
            data.writeInt(0);
            data.writeInt(1);
            new ComponentName(context, ProbeActivity.class).writeToParcel(data, 0);
            if (!binder.transact(8, data, reply, 0)) {
                out.append(";componentState=transaction-not-handled");
                return;
            }
            reply.readException();
            out.append(";componentState=").append(reply.readInt());
        } catch (Throwable error) {
            out.append(";componentState=ERROR:").append(error.getClass().getSimpleName());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void appendTargetExperiment(StringBuilder out, IBinder binder, Context context) {
        ComponentName component = new ComponentName(TARGET, TARGET + ".TargetReceiver");
        final int original;
        try {
            out.append(";targetUid=").append(context.getPackageManager().getPackageUid(TARGET, 0));
            original = readComponentState(binder, component);
            out.append(";targetBefore=").append(original);
            out.append(";androidBefore=").append(
                    context.getPackageManager().getComponentEnabledSetting(component));
        } catch (Throwable error) {
            out.append(";targetSetup=ERROR:").append(error.getClass().getSimpleName());
            return;
        }
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    component, original, PackageManager.DONT_KILL_APP);
            out.append(";androidDirect=allowed");
        } catch (Throwable error) {
            out.append(";androidDirect=").append(error.getClass().getSimpleName());
        }
        int testState = original == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            setComponentState(binder, component, testState);
            out.append(";targetDuring=").append(readComponentState(binder, component));
            out.append(";androidDuring=").append(
                    context.getPackageManager().getComponentEnabledSetting(component));
        } catch (Throwable error) {
            out.append(";targetWrite=ERROR:").append(error.getClass().getSimpleName());
        } finally {
            try {
                setComponentState(binder, component, original);
                out.append(";targetRestored=").append(readComponentState(binder, component));
                out.append(";androidRestored=").append(
                        context.getPackageManager().getComponentEnabledSetting(component));
            } catch (Throwable error) {
                out.append(";targetRestore=ERROR:").append(error.getClass().getSimpleName());
            }
        }
    }

    private static int readComponentState(IBinder binder, ComponentName component) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PACKAGE_MANAGER);
            data.writeInt(0);
            data.writeInt(1);
            component.writeToParcel(data, 0);
            if (!binder.transact(8, data, reply, 0)) {
                throw new IllegalStateException("query transaction not handled");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void setComponentState(IBinder binder, ComponentName component, int state)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PACKAGE_MANAGER);
            data.writeInt(0);
            data.writeInt(1);
            component.writeToParcel(data, 0);
            data.writeInt(state);
            data.writeInt(PackageManager.DONT_KILL_APP);
            if (!binder.transact(7, data, reply, 0)) {
                throw new IllegalStateException("write transaction not handled");
            }
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
