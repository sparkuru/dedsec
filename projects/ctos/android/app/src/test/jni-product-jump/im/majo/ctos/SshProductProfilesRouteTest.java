package im.majo.ctos;

import android.os.SystemClock;
import im.majo.ctos.host.HostLinux;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises the production profile-list evaluation with the real managed JNI parser. */
public final class SshProductProfilesRouteTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void target(Map<String, Map<String, Object>> rows, String alias, String host,
            int port, String user, SshConnectionMaterial.Snapshot source) {
        Map<String, Object> row = rows.get(alias);
        require(row != null && !row.containsKey("error") && host.equals(row.get("host"))
                && Integer.valueOf(port).equals(row.get("port")) && user.equals(row.get("username"))
                && SshConnectionMaterial.configBinding(source, alias).equals(row.get("binding")),
                "wrong selectable route row: " + alias);
    }

    private static void error(Map<String, Map<String, Object>> rows, String alias, String expected,
            SshConnectionMaterial.Snapshot source) {
        Map<String, Object> row = rows.get(alias);
        require(row != null && expected.equals(row.get("error")) && "".equals(row.get("host"))
                && "".equals(row.get("username")) && List.of().equals(row.get("keyIds"))
                && SshConnectionMaterial.configBinding(source, alias).equals(row.get("binding")),
                "wrong rejected route row: " + alias);
    }

    public static void main(String[] args) throws Exception {
        String config = "Host direct\n HostName direct.example\n Port 2101\n User directuser\n"
                + "Host disabled\n HostName disabled.example\n Port 2102\n User disableduser\n ProxyJump none\n"
                + "Host jump\n HostName jump.example\n Port 2103\n User jumpuser\n"
                + "Host second\n HostName second.example\n Port 2104\n User seconduser\n"
                + "Host single\n HostName single.example\n Port 2105\n User singleuser\n ProxyJump jump\n"
                + "Host multi\n HostName multi.example\n Port 2106\n User multiuser\n ProxyJump jump,second\n"
                + "Host broken\n HostName broken.example\n Port 2107\n User brokenuser\n ProxyJump badjump\n"
                + "Host badjump\n HostName bad.example\n User baduser\n ProxyCommand /bin/false\n";
        byte[] bytes = config.getBytes(StandardCharsets.US_ASCII);
        HostLinux.resetSocketEvidence();
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(new VaultManifest.FileObject(
                VaultManifest.FileType.CONFIG, SshConnectionMaterial.id("config", SshConnectionMaterial.ROOT),
                SshConnectionMaterial.ROOT, SshConnectionMaterial.id("config-owner", SshConnectionMaterial.ROOT),
                bytes, List.of())));
                SshConnectionMaterial.Snapshot source = new SshConnectionMaterial.Snapshot(manifest, 3, 7, "fixture")) {
            List<Map<String, Object>> evaluated = SshProfilesBridge.profileHosts(source, List.of(),
                    SystemClock.elapsedRealtime() + 30_000, () -> { });
            Map<String, Map<String, Object>> rows = new HashMap<>();
            for (Map<String, Object> row : evaluated)
                require(rows.put((String)row.get("alias"), row) == null, "duplicate catalog row");
            target(rows, "direct", "direct.example", 2101, "directuser", source);
            target(rows, "disabled", "disabled.example", 2102, "disableduser", source);
            target(rows, "single", "single.example", 2105, "singleuser", source);
            error(rows, "multi", "SSH_JUMP_CHAIN_UNSUPPORTED", source);
            error(rows, "broken", "SSH_CONFIG_UNSUPPORTED", source);
            require(HostLinux.socketCount() == 0, "listing a profile opened a network socket");
        } finally { VaultCodec.wipe(bytes); }
        System.out.println("PRODUCT_PROFILES direct=pass none=pass single=pass multi=unsupported bad-hop=rejected sockets=0");
    }
}
