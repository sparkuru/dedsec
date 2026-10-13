package im.majo.ctos;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.google.zxing.ResultPoint;
import com.google.zxing.BarcodeFormat;
import java.util.List;

/** Own, non-exported scanner. Only an opaque request ID crosses the Activity boundary. */
public final class TotpCameraActivity extends Activity {
    static final class Capture implements AutoCloseable {
        final String id;
        final AppSecrets.EffectGuard live;
        final java.util.function.Consumer<Boolean> visibility;
        TotpInput input;
        VaultException failure;
        boolean done;
        Capture(String id, AppSecrets.EffectGuard live, java.util.function.Consumer<Boolean> visibility) { this.id = id; this.live = live; this.visibility = visibility; }
        synchronized boolean active() {
            if (done) return false;
            try { live.check(); return true; }
            catch (VaultException expired) { failure = expired; done = true; return false; }
        }
        synchronized void accept(String text) {
            if (!active()) return;
            try { input = TotpInput.uri(text); }
            catch (VaultException unsupported) { failure = unsupported; }
            done = true;
        }
        @Override public synchronized void close() { done = true; if (input != null) input.close(); input = null; }
    }
    private static Capture pending;
    static synchronized void install(Capture capture) { if (pending != null) pending.close(); pending = capture; }
    static synchronized Capture find(String id) { return pending != null && pending.id.equals(id) ? pending : null; }
    static synchronized void release(Capture capture) { if (pending == capture) pending = null; capture.close(); }
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private Capture capture;
    private BarcodeView barcode;
    private final java.util.concurrent.ExecutorService parser = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean capturing;
    private final Runnable expiry = new Runnable() {
        @Override public void run() { if (capture == null || !capture.active()) finish(); else handler.postDelayed(this, 250); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        capture = find(getIntent().getStringExtra("requestId"));
        if (capture == null || !capture.active()) { finish(); return; }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this); label.setText(R.string.totp_scan_title); label.setTextSize(20); label.setPadding(24, 24, 24, 24);
        layout.addView(label);
        barcode = new BarcodeView(this);
        barcode.setDecoderFactory(hints -> {
            com.google.zxing.MultiFormatReader reader = new com.google.zxing.MultiFormatReader();
            java.util.Map<com.google.zxing.DecodeHintType, Object> options = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
            options.putAll(hints); options.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            reader.setHints(options);
            return new com.journeyapps.barcodescanner.Decoder(reader) {
                @Override public com.google.zxing.Result decode(com.google.zxing.LuminanceSource source) {
                    if (source.getWidth() > 2048 || source.getHeight() > 2048 || (long) source.getWidth() * source.getHeight() > 4000000) {
                        synchronized (capture) { capture.failure = new VaultException("TOTP_LIMIT", "Camera frame too large"); capture.done = true; }
                        return null;
                    }
                    return super.decode(source);
                }
            };
        });
        // Limit the decoder's cropped region even when the camera chooses a larger preview.
        barcode.setFramingRectSize(new com.journeyapps.barcodescanner.Size(720, 720));
        barcode.addStateListener(new com.journeyapps.barcodescanner.CameraPreview.StateListener() {
            @Override public void previewSized() { }
            @Override public void previewStarted() { }
            @Override public void previewStopped() { }
            @Override public void cameraClosed() { }
            @Override public void cameraError(Exception error) {
                if (capture != null) { capture.failure = new VaultException("TOTP_CAMERA", "Camera unavailable; use image or manual entry"); }
                finish();
            }
        });
        layout.addView(barcode, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this); cancel.setText(android.R.string.cancel); cancel.setOnClickListener(view -> finish()); layout.addView(cancel);
        setContentView(layout);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        else startScanner();
        handler.post(expiry);
    }
    private void startScanner() {
        if (capture == null || !capture.active() || barcode == null) { finish(); return; }
        barcode.decodeSingle(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult result) {
                if (capture == null || capturing) return;
                capturing = true;
                Capture own = capture;
                parser.execute(() -> {
                    own.accept(result.getText());
                    handler.post(() -> { if (!isDestroyed()) { setResult(RESULT_OK); finish(); } });
                });
            }
            @Override public void possibleResultPoints(List<ResultPoint> points) { }
        });
        barcode.resume();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startScanner();
        else { if (capture != null) capture.failure = new VaultException("TOTP_CAMERA_PERMISSION", "Camera permission denied; use image or manual entry"); finish(); }
    }
    @Override protected void onStart() { super.onStart(); if (capture != null && capture.active()) capture.visibility.accept(true); else finish(); }
    @Override protected void onStop() { if (capture != null) capture.visibility.accept(false); super.onStop(); }
    @Override protected void onResume() { super.onResume(); if (barcode != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScanner(); }
    @Override protected void onPause() { if (barcode != null) barcode.pause(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); parser.shutdownNow(); if (barcode != null) barcode.pause(); super.onDestroy(); }
}
