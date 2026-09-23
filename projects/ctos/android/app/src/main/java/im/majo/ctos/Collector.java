package im.majo.ctos;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Collector {
    private Collector() {}
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
                "cmd package list packages -U; exit \"$ctos_exit\"" : "ss -tunape", root, 8);
        String[] sections = result.getString("output").split("@PACKAGES@", 2);
        Map<Integer, String> packages = new HashMap<>();
        for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) {
            String label = context.getPackageManager().getApplicationLabel(app).toString();
            packages.merge(app.uid, label + " (" + app.packageName + ")", (a, b) -> a + ", " + b);
        }
        if (sections.length > 1) {
            Pattern packageLine = Pattern.compile("package:(\\S+)\\s+uid:(\\d+)");
            for (String line : sections[1].split("\n")) {
                Matcher match = packageLine.matcher(line);
                if (!match.find()) continue;
                int uidValue = Integer.parseInt(match.group(2));
                String packageName = match.group(1);
                String previous = packages.get(uidValue);
                if (previous == null) packages.put(uidValue, packageName);
                else if (!previous.contains(packageName)) packages.put(uidValue, previous + ", " + packageName);
            }
        }
        StringBuilder output = new StringBuilder();
        Pattern uid = Pattern.compile("uid:(\\d+)");
        for (String line : sections[0].split("\n")) {
            output.append(line);
            Matcher match = uid.matcher(line);
            if (match.find()) {
                String owner = packages.get(Integer.parseInt(match.group(1)));
                if (owner != null) output.append("\n  ↳ ").append(owner);
            }
            output.append('\n');
        }
        result.put("output", output.toString());
        result.put("partial", !root || output.toString().contains("Permission denied"));
        return result;
    }
}
