package im.majo.ctos;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.NetworkInterface;
import java.util.Collections;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public final class NetworkSnapshot {
    private NetworkSnapshot() {}

    public static JSONObject collect(Context context) throws Exception {
        JSONObject result = new JSONObject();
        result.put("time", System.currentTimeMillis());
        result.put("elapsed", SystemClock.elapsedRealtime());
        result.put("uid", Process.myUid());
        result.put("pid", Process.myPid());
        result.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        result.put("android", Build.VERSION.RELEASE);
        result.put("sdk", Build.VERSION.SDK_INT);
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        JSONArray networks = new JSONArray();
        Network active = manager.getActiveNetwork();
        for (Network network : manager.getAllNetworks()) {
            LinkProperties link = manager.getLinkProperties(network);
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (link == null || caps == null) continue;
            JSONObject item = new JSONObject();
            item.put("id", network.toString());
            item.put("interface", link.getInterfaceName());
            item.put("default", network.equals(active));
            item.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            item.put("transport", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" :
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Cellular" :
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Other");
            item.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            item.put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            item.put("mtu", Build.VERSION.SDK_INT >= 29 ? link.getMtu() : -1);
            item.put("addresses", new JSONArray(link.getLinkAddresses().stream().map(Object::toString).toArray()));
            item.put("dns", new JSONArray(link.getDnsServers().stream().map(a -> a.getHostAddress()).toArray()));
            item.put("routes", new JSONArray(link.getRoutes().stream().map(Object::toString).toArray()));
            item.put("privateDns", link.isPrivateDnsActive());
            item.put("privateDnsName", link.getPrivateDnsServerName());
            networks.put(item);
        }
        result.put("networks", networks);
        JSONArray interfaces = new JSONArray();
        Map<String, long[]> counters = new HashMap<>();
        if (Build.VERSION.SDK_INT < 31) {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/dev"))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 1024) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String[] fields = line.substring(colon + 1).trim().split("\\s+");
                    if (fields.length < 16) continue;
                    long[] values = new long[16];
                    for (int i = 0; i < 16; i++) values[i] = Long.parseLong(fields[i]);
                    counters.put(line.substring(0, colon).trim(), values);
                }
            } catch (Exception error) { result.put("counterError", error.toString()); }
        }
        try {
            for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                JSONObject item = new JSONObject();
                item.put("name", net.getName());
                item.put("up", net.isUp());
                item.put("state", net.isUp() ? "UP" : "DOWN");
                item.put("mtu", net.getMTU());
                item.put("addresses", new JSONArray(net.getInterfaceAddresses().stream().map(Object::toString).toArray()));
                item.put("rx", -1).put("tx", -1).put("rxPackets", -1).put("txPackets", -1);
                if (Build.VERSION.SDK_INT >= 31) {
                    item.put("rx", TrafficStats.getRxBytes(net.getName()));
                    item.put("tx", TrafficStats.getTxBytes(net.getName()));
                    item.put("rxPackets", TrafficStats.getRxPackets(net.getName()));
                    item.put("txPackets", TrafficStats.getTxPackets(net.getName()));
                } else if (counters.containsKey(net.getName())) {
                    long[] values = counters.get(net.getName());
                    item.put("rx", values[0]).put("tx", values[8]);
                    item.put("rxPackets", values[1]).put("txPackets", values[9]);
                    item.put("rxErrors", values[2]).put("txErrors", values[10]);
                    item.put("rxDrops", values[3]).put("txDrops", values[11]);
                }
                interfaces.put(item);
            }
        } catch (Exception error) { result.put("interfaceError", error.toString()); }
        result.put("interfaces", interfaces);
        return result;
    }

    public static JSONObject apiInterfaces(JSONObject snapshot, String source) throws Exception {
        JSONArray interfaces = snapshot.getJSONArray("interfaces");
        for (int i = 0; i < interfaces.length(); i++) {
            JSONObject item = interfaces.getJSONObject(i);
            if (!item.has("rx")) item.put("rx", -1).put("tx", -1);
        }
        StringBuilder routes = new StringBuilder();
        JSONArray networks = snapshot.getJSONArray("networks");
        for (int i = 0; i < networks.length(); i++) {
            JSONObject network = networks.getJSONObject(i);
            routes.append(network.optString("interface")).append("\n");
            JSONArray entries = network.getJSONArray("routes");
            for (int j = 0; j < entries.length(); j++) routes.append(entries.getString(j)).append("\n");
        }
        return new JSONObject().put("interfaces", interfaces).put("elapsed", snapshot.getLong("elapsed"))
                .put("source", source).put("routes", routes.toString());
    }
}
