package im.majo.ctos;

import android.app.Application;

/** Only compiled into the explicitly selected, independently installed fixture flavor. */
public final class SecretsFixtureApplication extends Application {
    static final String PACKAGE = "im.majo.ctos.secretsfixture";
    @Override public void onCreate() {
        super.onCreate();
        if (!PACKAGE.equals(getPackageName())) throw new IllegalStateException("Unexpected fixture package");
        if (!getSharedPreferences("root_access", MODE_PRIVATE).edit().putBoolean("auto_start", false).commit()) {
            throw new IllegalStateException("Fixture Root startup could not be disabled");
        }
    }
}
