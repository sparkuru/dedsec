package im.majo.ctos;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Collector {
    private Collector() {}
    private static final int UID_PER_USER = 100000;
    private static volatile RootSession rootSession;

    public static synchronized JSONObject authorizeRoot() throws Exception {
        closeRoot();
        RootSession session = new RootSession();
        try {
            JSONObject result = session.command("id", 30);
            if (result.getInt("exit") == 0 && result.getString("output").contains("uid=0(")) rootSession = session;
            else session.close();
            return result;
        } catch (Exception error) { session.close(); throw error; }
    }

    public static void closeRoot() {
        RootSession session = rootSession;
        rootSession = null;
        if (session != null) session.close();
    }

    public static JSONObject command(String command, boolean root, int timeoutSeconds) throws Exception {
        if (root) {
            RootSession session = rootSession;
            if (session == null) throw new IllegalStateException("Root has not been authorized");
            return session.command(command, timeoutSeconds);
        }
        java.lang.Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        StringBuilder text = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStreamReader stream = new InputStreamReader(process.getInputStream())) {
                char[] buffer = new char[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (text) {
                        if (text.length() < 1024 * 1024) text.append(buffer, 0, Math.min(count, 1024 * 1024 - text.length()));
                    }
                }
            } catch (Exception ignored) { }
        }, "ctos-command-output");
        reader.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        reader.join(1000);
        JSONObject result = new JSONObject();
        synchronized (text) { result.put("output", text.toString()); }
        result.put("exit", finished ? process.exitValue() : -1);
        result.put("timeout", !finished);
        return result;
    }

    public static JSONObject interfaces(boolean root) throws Exception {
        JSONObject result = command("cat /proc/net/dev; printf '\\n@ADDR@\\n'; ip -o addr; " +
                "printf '\\n@LINK@\\n'; ip -o link; printf '\\n@ROUTE@\\n'; ip route show table all", root, 5);
        result.put("elapsed", SystemClock.elapsedRealtime());
        String[] sections = result.getString("output").split("@(?:ADDR|LINK|ROUTE)@");
        JSONArray items = new JSONArray();
        Map<String, JSONObject> byName = new HashMap<>();
        for (String line : sections[0].split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String[] values = line.substring(colon + 1).trim().split("\\s+");
            if (values.length < 16) continue;
            try {
                JSONObject item = new JSONObject().put("name", name).put("rx", Long.parseLong(values[0]))
                        .put("tx", Long.parseLong(values[8])).put("rxPackets", Long.parseLong(values[1]))
                        .put("txPackets", Long.parseLong(values[9])).put("rxErrors", Long.parseLong(values[2]))
                        .put("txErrors", Long.parseLong(values[10])).put("rxDrops", Long.parseLong(values[3]))
                        .put("txDrops", Long.parseLong(values[11])).put("addresses", new JSONArray());
                byName.put(name, item);
                items.put(item);
            } catch (NumberFormatException ignored) { }
        }
        if (sections.length > 1) {
            Pattern address = Pattern.compile("^\\d+:\\s+(\\S+)\\s+inet6?\\s+(\\S+)");
            for (String line : sections[1].split("\n")) {
                Matcher match = address.matcher(line.trim());
                if (!match.find()) continue;
                JSONObject item = byName.get(match.group(1).split("@")[0]);
                if (item != null) item.getJSONArray("addresses").put(match.group(2));
            }
        }
        if (sections.length > 2) {
            Pattern link = Pattern.compile("^\\d+:\\s+([^:]+):.*?mtu\\s+(\\d+).*?state\\s+(\\S+)");
            for (String line : sections[2].split("\n")) {
                Matcher match = link.matcher(line.trim());
                if (!match.find()) continue;
                JSONObject item = byName.get(match.group(1).split("@")[0]);
                if (item != null) item.put("mtu", Integer.parseInt(match.group(2))).put("state", match.group(3));
            }
        }
        result.put("interfaces", items);
        result.put("routes", sections.length > 3 ? sections[3].trim() : "");
        result.put("source", root ? "root / procfs + ip" : "app / procfs + ip");
        return result;
    }

    public static JSONObject connections(Context context, boolean root) throws Exception {
        JSONObject result = command(root ? "ss -tunape; ctos_exit=$?; printf '\\n@PACKAGES@\\n'; " +
                "cmd package list packages -U; printf '\\n@USERS@\\n'; " +
                "dumpsys user | grep 'UserInfo{'; printf '\\n@ALIASES@\\n'; " +
                "content query --uri content://com.android.launcher.OplusFavoritesProvider/favorites " +
                "--projection title:intent:profileId 2>/dev/null; exit \"$ctos_exit\"" : "ss -tunape", root, 12);
        String[] sections = result.getString("output").split("@(?:PACKAGES|USERS|ALIASES)@");
        Pattern uid = Pattern.compile("uid:(\\d+)");
        Set<Integer> observedUids = new HashSet<>();
        for (String line : sections[0].split("\n")) {
            Matcher match = uid.matcher(line);
            if (match.find()) observedUids.add(Integer.parseInt(match.group(1)));
        }
        Map<Integer, Integer> userBySerial = userIdsBySerial(sections.length > 2 ? sections[2] : "");
        Map<String, String> aliases = launcherAliases(sections.length > 3 ? sections[3] : "", userBySerial);
        Map<Integer, String> packages = new HashMap<>();
        Map<Integer, JSONArray> appRows = new HashMap<>();
        Set<String> knownPackages = new HashSet<>();
        Map<String, ApplicationInfo> visibleApps = new HashMap<>();
        PackageManager packageManager = context.getPackageManager();
        for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) {
            visibleApps.put(app.packageName, app);
            if (!observedUids.contains(app.uid)) continue;
            String label = packageManager.getApplicationLabel(app).toString();
            int userId = app.uid / UID_PER_USER;
            String alias = aliases.getOrDefault(userId + ":" + app.packageName, "");
            packages.merge(app.uid, (alias.isEmpty() ? label : alias) + " (" + app.packageName + ")",
                    (a, b) -> a + ", " + b);
            knownPackages.add(app.uid + ":" + app.packageName);
            JSONObject row = new JSONObject().put("label", label).put("packageName", app.packageName)
                    .put("alias", alias).put("userId", userId)
                    .put("applicationName", app.name == null ? "" : app.name)
                    .put("processName", app.processName == null ? "" : app.processName);
            String icon = appIcon(packageManager, app);
            if (icon != null) row.put("icon", icon);
            appRows.computeIfAbsent(app.uid, ignored -> new JSONArray()).put(row);
        }
        if (sections.length > 1) {
            Pattern packageLine = Pattern.compile("package:(\\S+)\\s+uid:([\\d,]+)");
            for (String line : sections[1].split("\n")) {
                Matcher match = packageLine.matcher(line);
                if (!match.find()) continue;
                String packageName = match.group(1);
                ApplicationInfo baseApp = visibleApps.get(packageName);
                for (String uidText : match.group(2).split(",")) {
                    int uidValue = Integer.parseInt(uidText);
                    if (!observedUids.contains(uidValue)) continue;
                    if (!knownPackages.add(uidValue + ":" + packageName)) continue;
                    int userId = uidValue / UID_PER_USER;
                    String label = baseApp == null ? packageName : packageManager.getApplicationLabel(baseApp).toString();
                    String alias = aliases.getOrDefault(userId + ":" + packageName, "");
                    String previous = packages.get(uidValue);
                    String owner = (alias.isEmpty() ? label : alias) + " (" + packageName + ")";
                    packages.put(uidValue, previous == null ? owner : previous + ", " + owner);
                    JSONObject row = new JSONObject().put("label", label).put("packageName", packageName)
                            .put("alias", alias).put("userId", userId)
                            .put("applicationName", baseApp == null || baseApp.name == null ? "" : baseApp.name)
                            .put("processName", baseApp == null || baseApp.processName == null ? "" : baseApp.processName);
                    if (baseApp != null) {
                        String icon = appIcon(packageManager, baseApp);
                        if (icon != null) row.put("icon", icon);
                    }
                    appRows.computeIfAbsent(uidValue, ignored -> new JSONArray()).put(row);
                }
            }
        }
        StringBuilder output = new StringBuilder();
        for (String line : sections[0].split("\n")) {
            output.append(line);
            Matcher match = uid.matcher(line);
            if (match.find()) {
                String owner = packages.get(Integer.parseInt(match.group(1)));
                if (owner != null) output.append("\n  ↳ ").append(owner);
            }
            output.append('\n');
        }
        JSONObject apps = new JSONObject();
        for (Map.Entry<Integer, JSONArray> entry : appRows.entrySet())
            apps.put(String.valueOf(entry.getKey()), entry.getValue());
        result.put("output", output.toString());
        result.put("apps", apps);
        result.put("partial", !root || output.toString().contains("Permission denied"));
        return result;
    }

    private static String appIcon(PackageManager packageManager, ApplicationInfo app) {
        try {
            Drawable drawable = packageManager.getApplicationIcon(app).mutate();
            Bitmap bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, 72, 72);
            drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            bitmap.recycle();
            return encoded ? Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP) : null;
        } catch (RuntimeException ignored) { return null; }
    }

    static Map<Integer, Integer> userIdsBySerial(String output) {
        Map<Integer, Integer> result = new HashMap<>();
        Pattern userLine = Pattern.compile("UserInfo\\{(\\d+):[^}]*\\} serialNo=(\\d+)");
        for (String line : output.split("\n")) {
            Matcher match = userLine.matcher(line);
            if (match.find()) result.put(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(1)));
        }
        return result;
    }

    static Map<String, String> launcherAliases(String output, Map<Integer, Integer> userBySerial) {
        Map<String, String> result = new HashMap<>();
        Pattern component = Pattern.compile("component=([^/;]+)");
        for (String line : output.split("\n")) {
            int titleStart = line.indexOf("title=");
            int intentStart = line.indexOf(", intent=", titleStart);
            int profileStart = line.lastIndexOf(", profileId=");
            if (titleStart < 0 || intentStart < 0 || profileStart < intentStart) continue;
            Matcher match = component.matcher(line.substring(intentStart, profileStart));
            if (!match.find()) continue;
            try {
                int serial = Integer.parseInt(line.substring(profileStart + 12).trim());
                Integer userId = userBySerial.get(serial);
                if (userId != null && userId != 0)
                    result.put(userId + ":" + match.group(1), line.substring(titleStart + 6, intentStart));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }
}
