package im.majo.ctos;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONObject;

/** App-owned preferences; URI grants are obtained only through the system picker. */
final class HftpConfig {
    static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    static final String DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents";
    static final String DEFAULT_DIRECTORY = "App 私有共享目录";
    final String host, treeUri, directoryName;
    final int port, maxUploadMiB;
    final boolean rootRelay;

    HftpConfig(String host, int port, int maxUploadMiB, String treeUri, String directoryName) {
        this(host, port, maxUploadMiB, treeUri, directoryName, false);
    }

    HftpConfig(String host, int port, int maxUploadMiB, String treeUri, String directoryName, boolean rootRelay) {
        if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host)) || port < 1024 || port > 65535)
            throw new IllegalArgumentException("Expected a local bind address and port 1024-65535");
        if (maxUploadMiB < 1 || maxUploadMiB > 1024)
            throw new IllegalArgumentException("Upload limit must be 1-1024 MiB");
        if (rootRelay && !"0.0.0.0".equals(host)) throw new IllegalArgumentException("Root Wi-Fi relay requires LAN mode");
        this.host = host;
        this.port = port;
        this.maxUploadMiB = maxUploadMiB;
        this.rootRelay = rootRelay;
        this.treeUri = treeUri == null ? "" : treeUri;
        this.directoryName = this.treeUri.isEmpty() ? DEFAULT_DIRECTORY : directoryName;
        if (!this.treeUri.isEmpty()) validateTree(Uri.parse(this.treeUri));
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("hftp", Context.MODE_PRIVATE);
    }

    static HftpConfig load(Context context) {
        SharedPreferences values = preferences(context);
        return new HftpConfig(values.getString("host", "0.0.0.0"), values.getInt("port", 7888),
                values.getInt("maxUploadMiB", 32), values.getString("treeUri", ""),
                values.getString("directoryName", DEFAULT_DIRECTORY), values.getBoolean("rootRelay", false));
    }

    HftpConfig directory(String uri, String name) {
        return new HftpConfig(host, port, maxUploadMiB, uri, name, rootRelay);
    }

    void save(Context context) {
        preferences(context).edit().putString("host", host).putInt("port", port)
                .putInt("maxUploadMiB", maxUploadMiB).putString("treeUri", treeUri)
                .putString("directoryName", directoryName).putBoolean("rootRelay", rootRelay).apply();
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("host", host).put("port", Integer.toString(port))
                .put("maxUploadMiB", Integer.toString(maxUploadMiB))
                .put("treeUri", treeUri).put("directoryName", directoryName).put("rootRelay", rootRelay);
    }

    static void validateTree(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !(EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())
                || DOWNLOADS_AUTHORITY.equals(uri.getAuthority())) || !DocumentsContract.isTreeUri(uri))
            throw new IllegalArgumentException("Choose a directory in local device storage");
    }

    static void requirePermission(Context context, Uri uri) {
        validateTree(uri);
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission() && permission.isWritePermission()) return;
        }
        throw new SecurityException("Directory permission expired; select the directory again");
    }
}
