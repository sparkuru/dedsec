package im.majo.thanoxbridge.test.appaccessprobe20261123;

import android.app.ActionBar;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Interactive Android package-visibility diagnostic for one dedicated test target. */
public final class AppListAccessActivity extends Activity {
    private static final String TAG = "ThanoxAppListAccessProbe";
    private static final String TARGET =
            "im.majo.thanoxbridge.test.appaccesstarget20261123";
    private static final String QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES";

    private TextView resultView;
    private Button checkButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        applyWindowInsets(content);

        TextView explanation = new TextView(this);
        explanation.setText("点击检查应用列表。UID 匹配有局限，请结合目标 UID 核验结果判断。");
        content.addView(explanation);

        checkButton = new Button(this);
        checkButton.setText("检查应用列表");
        checkButton.setOnClickListener(view -> runCheck());
        content.addView(checkButton);

        resultView = new TextView(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(resultView);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
        content.requestApplyInsets();
    }

    private static void applyWindowInsets(LinearLayout content) {
        content.setPadding(32, 32, 32, 32);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            view.setPadding(32 + left, 32 + top, 32 + right, 32 + bottom);
            return insets;
        });
    }

    private void runCheck() {
        checkButton.setEnabled(false);
        resultView.setText("检查中…");

        new Thread(() -> {
            String result;
            try {
                int expectedTargetUid = getIntent().getIntExtra("targetUid", -1);
                if (expectedTargetUid < 0) {
                    throw new IllegalArgumentException();
                }
                result = performCheck(expectedTargetUid);
            } catch (Throwable error) {
                result = "error=" + error.getClass().getName();
            }
            Log.i(TAG, result);
            final String displayedResult = result;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    resultView.setText(displayedResult);
                    checkButton.setEnabled(true);
                }
            });
        }, "app-list-access-check").start();
    }

    private String performCheck(int expectedTargetUid) {
        PackageManager manager = getPackageManager();
        String appsCount = "unknown";
        String appsTargetUidMatch = "unknown";
        String appsUnknownUidCount = "unknown";
        String packagesCount = "unknown";
        String packagesTargetUidMatch = "unknown";
        String packagesUnknownUidCount = "unknown";

        try {
            List<ApplicationInfo> applications = manager.getInstalledApplications(0);
            int unknown = 0;
            boolean matched = false;
            for (ApplicationInfo application : applications) {
                if (application == null || application.uid < 0) {
                    unknown++;
                } else if (application.uid == expectedTargetUid) {
                    matched = true;
                }
            }
            appsCount = Integer.toString(applications.size());
            appsUnknownUidCount = Integer.toString(unknown);
            appsTargetUidMatch = matchResult(matched, unknown);
        } catch (Throwable error) {
            appsCount = error.getClass().getName();
        }

        try {
            List<PackageInfo> packages = manager.getInstalledPackages(0);
            int unknown = 0;
            boolean matched = false;
            for (PackageInfo info : packages) {
                if (info == null || info.applicationInfo == null || info.applicationInfo.uid < 0) {
                    unknown++;
                } else if (info.applicationInfo.uid == expectedTargetUid) {
                    matched = true;
                }
            }
            packagesCount = Integer.toString(packages.size());
            packagesUnknownUidCount = Integer.toString(unknown);
            packagesTargetUidMatch = matchResult(matched, unknown);
        } catch (Throwable error) {
            packagesCount = error.getClass().getName();
        }

        String targetUid;
        String targetUidMatchesExtra = "unknown";
        try {
            int actualTargetUid = manager.getApplicationInfo(TARGET, 0).uid;
            targetUid = Integer.toString(actualTargetUid);
            targetUidMatchesExtra = Boolean.toString(actualTargetUid == expectedTargetUid);
        } catch (Throwable error) {
            targetUid = error.getClass().getName();
        }

        String queryAllPermission;
        try {
            queryAllPermission = Integer.toString(checkSelfPermission(QUERY_ALL));
        } catch (Throwable error) {
            queryAllPermission = error.getClass().getName();
        }

        return "callerUid=" + Process.myUid()
                + "\nqueryAllPermission=" + queryAllPermission
                + "\nexpectedTargetUid=" + expectedTargetUid
                + "\nappsCount=" + appsCount
                + "\nappsTargetUidMatch=" + appsTargetUidMatch
                + "\nappsUnknownUidCount=" + appsUnknownUidCount
                + "\npackagesCount=" + packagesCount
                + "\npackagesTargetUidMatch=" + packagesTargetUidMatch
                + "\npackagesUnknownUidCount=" + packagesUnknownUidCount
                + "\ntargetUid=" + targetUid
                + "\ntargetUidMatchesExtra=" + targetUidMatchesExtra;
    }

    private static String matchResult(boolean matched, int unknownCount) {
        return matched ? "true" : (unknownCount > 0 ? "unknown" : "false");
    }
}
