package im.majo.thanoxbridge.test.board20261123.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Runs one explicit operation against one task-owned development-board fixture. */
public final class BoardProbeActivity extends Activity {
    private static final String TARGET = "im.majo.thanoxbridge.test.board20261123.target";
    private static final String CALLER = "im.majo.thanoxbridge.test.board20261123.probe";
    private static final String ROOT = "github.tornaco.android.thanos.core.IThanos";
    private static final String PKG_MANAGER = "github.tornaco.android.thanos.core.pm.IPkgManager";
    private static final String OPS = "github.tornaco.android.thanos.core.ops.IOps";
    private static final String TAG = "ThanoxBoardProbe";
    private static final int USER_ID_RANGE = 100000;
    private static final Set<String> PERMISSIONS = new HashSet<>(Arrays.asList(
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.VIBRATE"));
    private static final Set<String> STATES = new HashSet<>(Arrays.asList(
            "DEFAULT", "NOT_SET", "DENY", "IGNORE", "ASK", "ALLOW_ALWAYS",
            "ALLOW_FOREGROUND_ONLY", "UNKNOWN"));

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String token = "invalid";
        String requested = "invalid";
        String before = "unavailable";
        String after = "unavailable";
        String androidBefore = "unavailable";
        String androidAfter = "unavailable";
        String phase = "INPUT";
        int callerUid = Process.myUid();
        int targetUid = -1;
        int op = -1;
        boolean writeAttempted = false;
        try {
            Intent intent = getIntent();
            String suppliedToken = intent.getStringExtra("token");
            String suppliedMode = intent.getStringExtra("mode");
            String suppliedSigner = intent.getStringExtra("cert");
            int suppliedUid = intent.getIntExtra("uid", -1);
            int suppliedOp = intent.getIntExtra("op", -1);
            boolean validToken = suppliedToken != null && suppliedToken.matches("[A-Za-z0-9-]{1,32}");
            boolean validMode = modeAllowed(suppliedOp, suppliedMode);
            if (validToken) token = suppliedToken;
            if (suppliedUid >= 10000 && suppliedUid < USER_ID_RANGE) targetUid = suppliedUid;
            if (validMode) {
                requested = suppliedMode;
                op = suppliedOp;
            }
            if (!validToken || !validMode ||
                    suppliedSigner == null || !suppliedSigner.matches("[A-Fa-f0-9]{64}") ||
                    suppliedUid < 10000 || suppliedUid >= USER_ID_RANGE ||
                    callerUid < 10000 || callerUid >= USER_ID_RANGE || suppliedUid == callerUid ||
                    Build.VERSION.SDK_INT != 30 || !CALLER.equals(getPackageName())) {
                throw new IllegalArgumentException("invalid input or environment");
            }
            String expectedName = operationName(suppliedOp);
            String permission = operationPermission(suppliedOp);
            String signer = suppliedSigner.toLowerCase(Locale.ROOT);

            phase = "IDENTITY";
            requireSeparateSigner(signer);
            TargetIdentity identity = checkedTarget(targetUid, signer, null);

            phase = "SERVICE";
            IBinder root = rootService();
            IBinder manager = childBinder(root, 4, PKG_MANAGER);
            IBinder ops = childBinder(root, 31, OPS);
            if (!expectedName.equals(readString(ops, OPS, 3, op)) ||
                    !permission.equals(readString(ops, OPS, 4, op))) {
                throw new IllegalStateException("operation mapping mismatch");
            }

            phase = "BEFORE_READ";
            verifyTargetAndUid(targetUid, signer, identity, manager);
            PermInfo initial = readInfo(ops, op);
            if (initial == null) throw new IllegalStateException("missing operation information");
            verifyTargetAndUid(targetUid, signer, identity, manager);
            before = initial.toLog();
            androidBefore = permissionState(permission);

            if (!"READ".equals(requested)) {
                phase = "PRE_WRITE";
                validateCapabilities(op, requested, initial);
                verifyTargetAndUid(targetUid, signer, identity, manager);
                PermInfo current = readInfo(ops, op);
                if (current == null || !initial.sameAs(current)) {
                    throw new IllegalStateException("operation changed before write");
                }
                validateCapabilities(op, requested, current);
                verifyTargetAndUid(targetUid, signer, identity, manager);
                phase = "WRITE";
                writeAttempted = true;
                setMode(ops, op, requested);
                phase = "AFTER_WRITE";
                verifyTargetAndUid(targetUid, signer, identity, manager);
            }

            phase = "AFTER_READ";
            PermInfo observed = readInfo(ops, op);
            if (observed == null) throw new IllegalStateException("missing post-operation information");
            verifyTargetAndUid(targetUid, signer, identity, manager);
            after = observed.toLog();
            androidAfter = permissionState(permission);
        } catch (Throwable error) {
            after = (writeAttempted ? "ERROR_AFTER_WRITE_ATTEMPT_" : "ERROR_BEFORE_WRITE_") + phase;
        } finally {
            Log.i(TAG, "token=" + token + ";callerUid=" + callerUid + ";targetUid=" + targetUid
                    + ";op=" + op + ";requested=" + requested + ";before=" + before
                    + ";androidBefore=" + androidBefore + ";after=" + after
                    + ";androidAfter=" + androidAfter);
            finish();
        }
    }

    private static String operationName(int op) {
        switch (op) {
            case 3: return "VIBRATE";
            case 26: return "CAMERA";
            case 27: return "RECORD_AUDIO";
            default: throw new IllegalArgumentException("invalid operation");
        }
    }

    private static String operationPermission(int op) {
        switch (op) {
            case 3: return "android.permission.VIBRATE";
            case 26: return "android.permission.CAMERA";
            case 27: return "android.permission.RECORD_AUDIO";
            default: throw new IllegalArgumentException("invalid operation");
        }
    }

    private static boolean modeAllowed(int op, String mode) {
        if ("READ".equals(mode)) return op == 3 || op == 26 || op == 27;
        if (op == 3) return "ALLOW_ALWAYS".equals(mode) || "IGNORE".equals(mode);
        return (op == 26 || op == 27) && ("ALLOW_ALWAYS".equals(mode) ||
                "ALLOW_FOREGROUND_ONLY".equals(mode) || "ASK".equals(mode) ||
                "IGNORE".equals(mode) || "DENY".equals(mode));
    }

    private static void validateCapabilities(int op, String mode, PermInfo info) {
        if ("UNKNOWN".equals(info.state) || (op == 3 && info.runtime) ||
                (op != 3 && !info.runtime) ||
                ("ALLOW_FOREGROUND_ONLY".equals(mode) && !info.background) ||
                ("ASK".equals(mode) && !info.oneTime)) {
            throw new IllegalStateException("unsupported operation capability");
        }
    }

    private void requireSeparateSigner(String expectedTargetSigner) throws Exception {
        PackageInfo caller = getPackageManager().getPackageInfo(CALLER, PackageManager.GET_SIGNING_CERTIFICATES);
        if (expectedTargetSigner.equals(singleSignerDigest(caller))) {
            throw new IllegalStateException("probe and target must use different signers");
        }
    }

    private TargetIdentity checkedTarget(int expectedUid, String signer, TargetIdentity previous) throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(
                TARGET, PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNING_CERTIFICATES);
        ApplicationInfo app = info.applicationInfo;
        if (app == null || !TARGET.equals(info.packageName) || !TARGET.equals(app.packageName) ||
                app.uid != expectedUid || app.uid / USER_ID_RANGE != 0 || app.targetSdkVersion != 35 ||
                (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP |
                        ApplicationInfo.FLAG_HAS_CODE)) != 0 || info.sharedUserId != null ||
                info.requestedPermissions == null ||
                !PERMISSIONS.equals(new HashSet<>(Arrays.asList(info.requestedPermissions))) ||
                !signer.equals(singleSignerDigest(info)) || info.firstInstallTime <= 0 ||
                info.lastUpdateTime <= 0 || app.sourceDir == null || app.sourceDir.isEmpty()) {
            throw new IllegalStateException("target identity mismatch");
        }
        TargetIdentity current = new TargetIdentity(
                info.firstInstallTime, info.lastUpdateTime, info.getLongVersionCode(), app.sourceDir);
        if (previous != null && !previous.sameAs(current)) {
            throw new IllegalStateException("target installation changed");
        }
        return current;
    }

    private static String singleSignerDigest(PackageInfo info) throws Exception {
        Signature[] signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) throw new IllegalStateException("invalid signing identity");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private void verifyTargetAndUid(int uid, String signer, TargetIdentity identity, IBinder manager)
            throws Exception {
        checkedTarget(uid, signer, identity);
        if (thanoxUid(manager) != uid) throw new IllegalStateException("Thanox UID mismatch");
    }

    private String permissionState(String permission) {
        int state = getPackageManager().checkPermission(permission, TARGET);
        if (state != PackageManager.PERMISSION_GRANTED && state != PackageManager.PERMISSION_DENIED) {
            throw new IllegalStateException("invalid Android permission result");
        }
        return Integer.toString(state);
    }

    private static IBinder rootService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder root = (IBinder) serviceManager.getDeclaredMethod("getService", String.class)
                .invoke(null, "tv_input");
        if (root == null || !ROOT.equals(root.getInterfaceDescriptor()) ||
                !"9.6".equals(readString(root, ROOT, 19, null)) ||
                !"I am Thanox!!!".equals(readString(root, ROOT, 20, null))) {
            throw new IllegalStateException("incompatible Thanox service");
        }
        return root;
    }

    private static IBinder childBinder(IBinder root, int code, String expectedDescriptor) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ROOT);
            if (!root.transact(code, data, reply, 0)) throw new IllegalStateException("unsupported binder");
            reply.readException();
            IBinder child = reply.readStrongBinder();
            requireEnd(reply);
            if (child == null || !expectedDescriptor.equals(child.getInterfaceDescriptor())) {
                throw new IllegalStateException("incompatible interface");
            }
            return child;
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
            if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException("unsupported string");
            reply.readException();
            String value = requiredString(reply);
            requireEnd(reply);
            return value;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int thanoxUid(IBinder manager) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PKG_MANAGER);
            writeTarget(data);
            if (!manager.transact(2, data, reply, 0)) throw new IllegalStateException("unsupported UID");
            reply.readException();
            if (reply.dataAvail() != Integer.BYTES) throw new IllegalStateException("malformed UID");
            int uid = reply.readInt();
            requireEnd(reply);
            return uid;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static PermInfo readInfo(IBinder ops, int op) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(op);
            writeTarget(data);
            if (!ops.transact(2, data, reply, 0)) throw new IllegalStateException("unsupported info");
            reply.readException();
            int present = requiredInt(reply);
            if (present != 0 && present != 1) throw new IllegalStateException("invalid presence marker");
            PermInfo info = null;
            if (present == 1) {
                String state = requiredString(reply);
                if (!STATES.contains(state)) throw new IllegalStateException("invalid operation state");
                boolean background = requiredBoolean(reply);
                boolean runtime = requiredBoolean(reply);
                boolean oneTime = requiredBoolean(reply);
                requiredString(reply);
                info = new PermInfo(state, background, runtime, oneTime);
            }
            requireEnd(reply);
            return info;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void setMode(IBinder ops, int op, String mode) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPS);
            data.writeInt(op);
            writeTarget(data);
            data.writeString(mode);
            if (!ops.transact(1, data, reply, 0)) throw new IllegalStateException("unsupported write");
            reply.readException();
            requireEnd(reply);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void writeTarget(Parcel data) {
        data.writeInt(1);
        data.writeString(TARGET);
        data.writeInt(0);
    }

    private static int requiredInt(Parcel parcel) {
        if (parcel.dataAvail() < Integer.BYTES) throw new IllegalStateException("truncated integer");
        return parcel.readInt();
    }

    private static boolean requiredBoolean(Parcel parcel) {
        int value = requiredInt(parcel);
        if (value != 0 && value != 1) throw new IllegalStateException("invalid boolean");
        return value == 1;
    }

    private static String requiredString(Parcel parcel) {
        if (parcel.dataAvail() < Integer.BYTES) throw new IllegalStateException("truncated string");
        String value = parcel.readString();
        if (value == null) throw new IllegalStateException("null string");
        return value;
    }

    private static void requireEnd(Parcel parcel) {
        if (parcel.dataAvail() != 0) throw new IllegalStateException("unexpected trailing data");
    }

    private static final class PermInfo {
        final String state;
        final boolean background;
        final boolean runtime;
        final boolean oneTime;

        PermInfo(String state, boolean background, boolean runtime, boolean oneTime) {
            this.state = state;
            this.background = background;
            this.runtime = runtime;
            this.oneTime = oneTime;
        }

        boolean sameAs(PermInfo other) {
            return state.equals(other.state) && background == other.background &&
                    runtime == other.runtime && oneTime == other.oneTime;
        }

        String toLog() {
            return state + "," + background + "," + runtime + "," + oneTime;
        }
    }

    private static final class TargetIdentity {
        final long firstInstallTime;
        final long lastUpdateTime;
        final long versionCode;
        final String sourceDir;

        TargetIdentity(long firstInstallTime, long lastUpdateTime, long versionCode, String sourceDir) {
            this.firstInstallTime = firstInstallTime;
            this.lastUpdateTime = lastUpdateTime;
            this.versionCode = versionCode;
            this.sourceDir = sourceDir;
        }

        boolean sameAs(TargetIdentity other) {
            return firstInstallTime == other.firstInstallTime && lastUpdateTime == other.lastUpdateTime &&
                    versionCode == other.versionCode && sourceDir.equals(other.sourceDir);
        }
    }
}
