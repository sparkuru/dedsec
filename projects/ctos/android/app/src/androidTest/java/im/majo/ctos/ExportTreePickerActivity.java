package im.majo.ctos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;
import android.widget.TextView;
import java.util.UUID;
import java.util.regex.Pattern;

/** Test APK only: a bounded, explicit picker lease for one freshly owned UUID leaf. */
public final class ExportTreePickerActivity extends Activity {
    static final String AUTHORITY = "com.android.externalstorage.documents";
    static final String TARGET = "im.majo.ctos";
    static final String EXPECTED_ID = "expectedTreeId", NONCE = "nonce", CALLBACK = "callback";
    static final int WAITING = 0, GRANTED = 1, REJECTED = 2, RELEASE = 3, RELEASED = 4;
    private static final int PICKER = 721;
    private static final int MODES = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final Pattern LEAF = Pattern.compile("primary:Download/ctos/ctos-saf-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expiry = () -> end("timeout");
    private String expectedId, nonce;
    private ResultReceiver callback;
    private Uri granted;
    private boolean replied, ended;

    static boolean validExpectedId(String value) {
        return value != null && LEAF.matcher(value).matches();
    }
    static ResultReceiver frameworkReceiver(ResultReceiver receiver) {
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
    private static boolean validNonce(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException error) { return false; }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getIntent().setExtrasClassLoader(ResultReceiver.class.getClassLoader());
            expectedId = getIntent().getStringExtra(EXPECTED_ID);
            nonce = getIntent().getStringExtra(NONCE);
            callback = getIntent().getParcelableExtra(CALLBACK);
        } catch (RuntimeException invalid) { finish(); return; }
        if (!validExpectedId(expectedId) || !validNonce(nonce) || callback == null) { finish(); return; }
        TextView content = new TextView(this);
        content.setText("ctOS isolated SAF fixture. Select only the new UUID test folder.");
        setContentView(content);
        main.postDelayed(expiry, 120000);
        ResultReceiver release = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int resultCode, Bundle value) {
                try {
                    if (value != null) value.setClassLoader(ResultReceiver.class.getClassLoader());
                    if (resultCode == RELEASE && value != null && nonce.equals(value.getString(NONCE))) end("released");
                } catch (RuntimeException invalid) { end("release_invalid"); }
            }
        };
        Bundle waiting = new Bundle(); waiting.putString(NONCE, nonce);
        waiting.putParcelable("release", frameworkReceiver(release));
        try { callback.send(WAITING, waiting); }
        catch (RuntimeException unavailable) { end("callback_unavailable"); return; }
        if (state == null) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(MODES | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(AUTHORITY, expectedId));
            try { startActivityForResult(picker, PICKER); }
            catch (RuntimeException unavailable) { end("picker_unavailable"); }
        }
    }
    @Override public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICKER || replied || ended) return;
        Uri selected = data == null ? null : data.getData();
        // Validate exact provider/tree identity before ANY query, grant, enumeration, read or write.
        if (result != RESULT_OK || selected == null || !"content".equals(selected.getScheme())
                || !AUTHORITY.equals(selected.getAuthority()) || selected.toString().length() > 4096
                || !DocumentsContract.buildTreeDocumentUri(AUTHORITY, expectedId).equals(selected)
                || (data.getFlags() & MODES) != MODES) {
            end("selection_rejected"); return;
        }
        try {
            // No persistable flag and no takePersistableUriPermission. The package is fixed, never caller supplied.
            granted = selected;
            grantUriPermission(TARGET, selected, MODES | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Bundle value = new Bundle(); value.putString(NONCE, nonce); value.putParcelable("tree", selected);
            value.putInt("modes", MODES);
            replied = true;
            callback.send(GRANTED, value);
        } catch (RuntimeException denied) { end("grant_failed"); }
    }
    private void end(String reason) {
        if (ended) return;
        ended = true; main.removeCallbacks(expiry);
        boolean revoked = revokeOwnGrant();
        try {
            if (callback != null) {
                Bundle value = new Bundle(); value.putString(NONCE, nonce); value.putString("reason", reason);
                value.putBoolean("revoked", revoked);
                callback.send(replied ? RELEASED : REJECTED, value);
            }
        } catch (RuntimeException disconnected) { /* Revocation/finish must outlive the callback. */ }
        finally {
            try { finishActivity(PICKER); } catch (RuntimeException ignored) { /* No outstanding picker. */ }
            finally { finish(); }
        }
    }
    private boolean revokeOwnGrant() {
        if (granted == null) return true;
        try { revokeUriPermission(TARGET, granted, MODES); granted = null; return true; }
        catch (RuntimeException failure) { return false; }
    }
    @Override public void onDestroy() {
        if (!ended) end("activity_destroyed");
        main.removeCallbacks(expiry); revokeOwnGrant(); super.onDestroy();
    }
}
