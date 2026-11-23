package im.majo.thanoxbridge.test.catalogprobe20261120;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class CatalogActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Log.i("ThanoxCatalogProbe", CatalogProbe.observe(this));
        } catch (Throwable error) {
            Log.i("ThanoxCatalogProbe", "error=" + error.getClass().getSimpleName());
        }
        finish();
    }
}
