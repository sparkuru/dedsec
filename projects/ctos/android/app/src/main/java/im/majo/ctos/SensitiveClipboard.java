package im.majo.ctos;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;

/** User-requested current TOTP copying with a sensitive-content hint. */
final class SensitiveClipboard {
    private final ClipboardManager clipboard;

    SensitiveClipboard(Context context) { clipboard = context.getSystemService(ClipboardManager.class); }

    void copyCode(String code) throws VaultException {
        if (clipboard == null) throw new VaultException("VAULT_UNAVAILABLE", "Clipboard is unavailable");
        ClipData clip = ClipData.newPlainText("ctOS code", code);
        PersistableBundle extras = new PersistableBundle();
        // Literal is supported on old releases and recognized as EXTRA_IS_SENSITIVE on API33+.
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        try { clipboard.setPrimaryClip(clip); }
        catch (SecurityException error) { throw new VaultException("VAULT_UNAVAILABLE", "Clipboard copy is unavailable"); }
    }
}
