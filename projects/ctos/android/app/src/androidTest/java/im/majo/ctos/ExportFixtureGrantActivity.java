package im.majo.ctos;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Starts this test APK's fixed provider under its owner UID; accepts no caller parameters. */
public final class ExportFixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try (Cursor ignored = getContentResolver().query(
                Uri.parse("content://im.majo.ctos.export.fixture/document/root"),
                new String[] {"document_id"}, null, null, null)) {
            // Provider initialization grants only its fixed private tree to the fixed target package.
        } finally {
            finish();
        }
    }
}
