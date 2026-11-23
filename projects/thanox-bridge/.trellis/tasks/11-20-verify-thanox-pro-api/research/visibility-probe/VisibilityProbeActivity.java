package im.majo.thanoxbridge.test.visibilityprobe20261120;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.util.List;

/** Compares visibility of the dedicated test target without logging other packages. */
public final class VisibilityProbeActivity extends Activity {
    private static final String TARGET = "im.majo.thanoxbridge.test.visibletarget20261120";
    private static final String QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES";
    private static final String TAG = "ThanoxVisibilityProbe";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String token = getIntent().getStringExtra("token");
            if (token == null || !token.matches("[A-Za-z0-9]{1,32}")) {
                throw new IllegalArgumentException();
            }

            PackageManager manager = getPackageManager();
            List<ApplicationInfo> apps = manager.getInstalledApplications(0);
            List<PackageInfo> packages = manager.getInstalledPackages(0);
            boolean appListed = false;
            for (ApplicationInfo app : apps) {
                if (TARGET.equals(app.packageName)) appListed = true;
            }
            boolean packageListed = false;
            for (PackageInfo info : packages) {
                if (TARGET.equals(info.packageName)) packageListed = true;
            }

            int targetUid = -1;
            try {
                targetUid = manager.getApplicationInfo(TARGET, 0).uid;
            } catch (PackageManager.NameNotFoundException ignored) {
                // A filtered target and an absent target both produce this result.
            }
            long version = manager.getPackageInfo(getPackageName(), 0).getLongVersionCode();
            int queryAllPermission = checkSelfPermission(QUERY_ALL);
            Log.i(TAG, "token=" + token + ";callerUid=" + Process.myUid()
                    + ";probeVersion=" + version + ";appsCount=" + apps.size()
                    + ";appsListedTarget=" + appListed + ";packagesCount=" + packages.size()
                    + ";packagesListedTarget=" + packageListed + ";targetUid=" + targetUid
                    + ";queryAllPermission=" + queryAllPermission);
        } catch (Throwable error) {
            Log.i(TAG, "error=" + error.getClass().getSimpleName());
        } finally {
            finish();
        }
    }
}
