package im.majo.ctos;

import android.content.Context;
import android.os.SystemClock;

/** Process-owned shared vault for Activity UI and future foreground service adapters. */
final class AppSecretsOwner {
    private static AppSecrets instance;
    private AppSecretsOwner() { }

    static synchronized AppSecrets acquire(Context context) {
        if (instance == null) {
            instance = new AppSecrets(new VaultStore(context.getApplicationContext()), SystemClock::elapsedRealtime);
            instance.attachSshValidator(new SshPasswordManifestValidator());
        }
        return instance;
    }
}
