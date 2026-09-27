package im.majo.ctos;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Logical mounts of trusted APK-bundled packages, not downloadable plugins. */
public final class PortablePackages {
    private final Context context;
    private final Map<String, Package> packages = new LinkedHashMap<>();
    private File shellRc;

    public PortablePackages(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void prepare() throws Exception {
        if (shellRc != null) return;
        packages.clear();
        String[] ids = context.getAssets().list("portable");
        if (ids == null) throw new IOException("No bundled packages");
        long revision = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        for (String id : ids) {
            validateName(id);
            JSONObject manifest;
            try (InputStream input = context.getAssets().open("portable/" + id + "/package.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IOException("Package manifest too large");
                    bytes.write(buffer, 0, count);
                }
                manifest = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            if (manifest.getInt("format") != 1 || !id.equals(manifest.getString("id")))
                throw new IOException("Unsupported package: " + id);
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains(manifest.getString("abi")))
                throw new IOException("Unsupported package ABI: " + id);
            String version = manifest.getString("version");
            validateName(version);
            File root = new File(context.getFilesDir(), "portable/" + id + "/" + version + "-" + revision);
            File marker = new File(root, ".ready");
            if (!marker.exists()) {
                unpack(manifest, root);
                Files.write(marker.toPath(), version.getBytes(StandardCharsets.UTF_8));
            }
            Package mounted = new Package(manifest, root, context.getApplicationInfo().nativeLibraryDir);
            for (String tool : mounted.tools.keySet()) {
                if (packages.values().stream().anyMatch(existing -> existing.tools.containsKey(tool)))
                    throw new IOException("Duplicate tool: " + tool);
            }
            packages.put(id, mounted);
        }
        File rc = new File(context.getFilesDir(), "portable/terminal.rc");
        StringBuilder text = new StringBuilder("# Generated from trusted APK package manifests.\n");
        for (Package mounted : packages.values()) {
            for (Map.Entry<String, String> tool : mounted.tools.entrySet()) {
                // Android install paths contain '='; env would treat the executable as an assignment.
                text.append(tool.getKey()).append("() ( export ");
                for (Map.Entry<String, String> variable : mounted.environment.entrySet())
                    text.append(quote(variable.getKey() + "=" + variable.getValue())).append(' ');
                text.append("; ").append(quote(tool.getValue())).append(" \"$@\"; )\n");
            }
        }
        Files.write(rc.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        shellRc = rc;
    }

    public synchronized Package get(String id) throws Exception {
        prepare();
        Package mounted = packages.get(id);
        if (mounted == null) throw new IOException("Unknown bundled package: " + id);
        return mounted;
    }

    public synchronized JSONArray manifests() throws Exception {
        prepare();
        JSONArray values = new JSONArray();
        for (Package mounted : packages.values()) values.put(new JSONObject()
                .put("id", mounted.manifest.getString("id"))
                .put("version", mounted.manifest.getString("version"))
                .put("abi", mounted.manifest.getString("abi"))
                .put("license", mounted.manifest.getString("license"))
                .put("tools", mounted.manifest.getJSONObject("tools")));
        return values;
    }

    public synchronized String shellRc() throws Exception { prepare(); return shellRc.getAbsolutePath(); }

    private void unpack(JSONObject manifest, File root) throws Exception {
        AssetManager assets = context.getAssets();
        JSONArray archives = manifest.optJSONArray("archives");
        if (archives != null) for (int i = 0; i < archives.length(); i++) {
            JSONObject archive = archives.getJSONObject(i);
            File target = inside(root, archive.getString("target"));
            try (ZipInputStream zip = new ZipInputStream(assets.open(archive.getString("asset")))) {
                ZipEntry entry;
                long expanded = 0;
                int entries = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 10000) throw new IOException("Too many package entries");
                    File output = inside(target, entry.getName());
                    if (entry.isDirectory()) { mkdir(output); continue; }
                    mkdir(output.getParentFile());
                    expanded += copy(zip, output, 64L * 1024 * 1024 - expanded);
                }
            }
        }
        JSONArray directories = manifest.optJSONArray("directories");
        if (directories != null) for (int i = 0; i < directories.length(); i++) {
            JSONObject directory = directories.getJSONObject(i);
            copyAssets(directory.getString("asset"), inside(root, directory.getString("target")), 0);
        }
        JSONArray files = manifest.optJSONArray("files");
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            File output = inside(root, file.getString("target"));
            mkdir(output.getParentFile());
            try (InputStream input = assets.open(file.getString("asset"))) { copy(input, output, 64L * 1024 * 1024); }
        }
    }

    private void copyAssets(String asset, File output, int depth) throws IOException {
        if (depth > 12) throw new IOException("Package directory too deep");
        String[] children = context.getAssets().list(asset);
        if (children != null && children.length > 0) {
            mkdir(output);
            for (String child : children) copyAssets(asset + "/" + child, inside(output, child), depth + 1);
        } else {
            mkdir(output.getParentFile());
            try (InputStream input = context.getAssets().open(asset)) { copy(input, output, 64L * 1024 * 1024); }
        }
    }

    private static long copy(InputStream input, File output, long limit) throws IOException {
        long length = 0;
        try (FileOutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                length += count;
                if (length > limit) throw new IOException("Package exceeds 64 MiB");
                stream.write(buffer, 0, count);
            }
        }
        return length;
    }

    private static File inside(File root, String relative) throws IOException {
        File path = new File(root, relative);
        if (relative.startsWith("/") || !path.getCanonicalPath().startsWith(root.getCanonicalPath() + "/"))
            throw new IOException("Invalid package path: " + relative);
        return path;
    }

    private static void mkdir(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Cannot create package directory");
    }

    private static void validateName(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")) throw new IOException("Invalid package name");
    }

    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    public static final class Package {
        public final JSONObject manifest;
        public final File root;
        public final Map<String, String> tools = new LinkedHashMap<>();
        public final Map<String, String> environment = new LinkedHashMap<>();

        private Package(JSONObject manifest, File root, String nativeDir) throws Exception {
            this.manifest = manifest;
            this.root = root;
            JSONObject entries = manifest.getJSONObject("tools");
            Iterator<String> names = entries.keys();
            while (names.hasNext()) {
                String tool = names.next();
                if (!tool.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) throw new IOException("Invalid tool name");
                String library = entries.getString(tool);
                if (!library.matches("lib[a-zA-Z0-9_.-]+\\.so")) throw new IOException("Invalid tool executable");
                File executable = inside(new File(nativeDir), library);
                if (!executable.canExecute()) throw new IOException("Missing executable: " + library);
                tools.put(tool, executable.getAbsolutePath());
            }
            JSONObject variables = manifest.optJSONObject("environment");
            if (variables != null) {
                Iterator<String> keys = variables.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!key.matches("[A-Z_][A-Z0-9_]*") || Arrays.asList("HOME", "CODEX_HOME").contains(key))
                        throw new IOException("Invalid package environment key");
                    environment.put(key, variables.getString(key).replace("${PACKAGE}", root.getAbsolutePath())
                            .replace("${NATIVE}", nativeDir));
                }
            }
            String libraries = environment.get("LD_LIBRARY_PATH");
            environment.put("LD_LIBRARY_PATH", nativeDir + (libraries == null ? "" : ":" + libraries));
        }
    }
}
