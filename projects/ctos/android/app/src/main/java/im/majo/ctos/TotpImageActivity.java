package im.majo.ctos;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.widget.TextView;

/** The selected URI and its transient grant stay on this short-lived native Activity. */
public final class TotpImageActivity extends Activity {
    static final class Capture implements AutoCloseable {
        final String id;
        final AppSecrets.EffectGuard live;
        TotpQrImages.Decoded decoded;
        VaultException failure;
        volatile boolean closed;
        final android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
        Capture(String id, AppSecrets.EffectGuard live) { this.id = id; this.live = live; }
        void check() throws VaultException { if (closed) throw new VaultException("VAULT_CANCELLED", "Image read cancelled"); live.check(); }
        @Override public synchronized void close() { closed = true; cancellation.cancel(); if (decoded != null) decoded.close(); decoded = null; }
    }
    private static Capture pending;
    static synchronized void install(Capture capture) { if (pending != null) pending.close(); pending = capture; }
    static synchronized Capture find(String id) { return pending != null && pending.id.equals(id) ? pending : null; }
    static synchronized void release(Capture capture) { if (pending == capture) pending = null; capture.close(); }
    private Capture capture;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean transferred, picker;
    private final Runnable expiry = new Runnable() {
        @Override public void run() {
            try { if (capture == null) { finish(); return; } capture.check(); main.postDelayed(this, 250); }
            catch (VaultException failure) { capture.failure = failure; capture.close(); if (picker) finishActivity(1); finish(); }
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        capture = find(getIntent().getStringExtra("requestId"));
        if (capture == null || saved != null) { finish(); return; }
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this); layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView label = new TextView(this); label.setText(R.string.totp_image_reading); label.setPadding(24, 48, 24, 24); layout.addView(label);
        android.widget.Button cancel = new android.widget.Button(this); cancel.setText(android.R.string.cancel); cancel.setOnClickListener(view -> { capture.close(); finish(); }); layout.addView(cancel); setContentView(layout);
        try {
            capture.check();
            picker = true; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 1);
        } catch (Exception failure) { capture.failure = new VaultException("TOTP_IMAGE", "Image selector unavailable"); finish(); }
        main.post(expiry);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || capture == null) { finish(); return; }
        picker = false;
        if (result != RESULT_OK || data == null || data.getData() == null) { finish(); return; }
        android.net.Uri selected = data.getData();
        Capture own = capture;
        worker.execute(() -> {
            TotpQrImages.Decoded decoded = null;
            try {
                own.check(); decoded = TotpQrImages.decode(getContentResolver(), selected, own::check, own.cancellation);
                synchronized (own) { own.check(); own.decoded = decoded; decoded = null; }
            } catch (VaultException failure) { own.failure = failure; }
            finally { if (decoded != null) decoded.close(); }
            main.post(() -> { if (!isDestroyed()) { transferred = true; setResult(RESULT_OK); finish(); } });
        });
    }
    @Override protected void onDestroy() {
        if (capture != null && !transferred) capture.close();
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
