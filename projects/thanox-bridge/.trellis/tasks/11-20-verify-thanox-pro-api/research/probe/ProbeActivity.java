package dev.thanoxbridge.probe.verify20261120;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class ProbeActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("ThanoxProbe", ProbeReceiver.probe(this));
        finish();
    }
}
