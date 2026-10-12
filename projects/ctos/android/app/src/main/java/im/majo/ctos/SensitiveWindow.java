package im.majo.ctos;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Main-thread owner tokens prevent stale/nested routes from clearing another sensitive route. */
final class SensitiveWindow {
    private final Activity activity;
    private final Set<String> owners = new HashSet<>();
    private boolean inheritedSecure;
    private boolean background;

    SensitiveWindow(Activity activity) { this.activity = activity; }

    String acquire() {
        if (owners.isEmpty() && !background) {
            inheritedSecure = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        }
        String token = UUID.randomUUID().toString();
        owners.add(token);
        apply();
        return token;
    }

    void release(String token) { owners.remove(token); apply(); }

    void background(boolean value) {
        if (value && owners.isEmpty() && !background) {
            inheritedSecure = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        }
        background = value;
        apply();
    }

    private void apply() {
        if (background || !owners.isEmpty() || inheritedSecure) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!background && owners.isEmpty() && !inheritedSecure);
    }
}
