package im.majo.ctos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Detached metadata seam using the production material, evaluator, JNI and parser. */
public final class SshConnectionMaterialRouteRealJniTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static long deadline() throws Exception {
        String uptime = Files.readString(Path.of("/proc/uptime")).split(" ", 2)[0];
        return (long)(Double.parseDouble(uptime) * 1000) + 30000;
    }

    public static void main(String[] args) throws Exception {
        String config = "Host target\n HostName target.example\n User carol\n ProxyJump alice@one:2201\n"
                + "Host one\n HostName jump.example\n IdentityFile /source/first\n"
                + "Host *\n UserKnownHostsFile ~/.ssh/known_hosts\n";
        VaultManifest.FileObject root = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "root", ".ssh/config", "source", config.getBytes(StandardCharsets.UTF_8), List.of());
        VaultManifest.FileObject hosts = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "hosts", ".ssh/known_hosts", "hosts", new byte[0], List.of());
        SshConfigEvaluator.ProfileRoute route;
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(root, hosts))) {
            route = SshConnectionMaterial.profileRoute(manifest, ".ssh/config", "target", "", deadline(), () -> { });
            check(route.entries.size() == 2 && route.entries.get(0).hostname.equals("jump.example"));
            check(route.entries.get(0).user.equals("alice") && route.entries.get(0).port == 2201);
            check(route.entries.get(0).identityHints.equals(List.of("/source/first")));
            check(route.target().hostname.equals("target.example") && route.target().user.equals("carol"));
            check(route.target().userKnownHostsFiles.equals(List.of(".ssh/known_hosts")));
            try {
                SshConnectionMaterial.profilePlan(manifest, ".ssh/config", "target", "", deadline(), () -> { });
                throw new AssertionError("profile direct gate");
            } catch (java.io.IOException expected) { }
            try {
                SshConnectionMaterial.profileRoute(manifest, ".ssh/ctos-manual", "target", "", deadline(), () -> { });
                throw new AssertionError("manual source gate");
            } catch (VaultException expected) { }
            int[] checks = {0};
            try {
                SshConnectionMaterial.profileRoute(manifest, ".ssh/config", "target", "", deadline(), () -> {
                    if (++checks[0] == 3) throw VaultException.invalid();
                });
                throw new AssertionError("guard revocation");
            } catch (java.io.IOException | VaultException expected) { }
            check(SshConnectionMaterial.profileRoute(manifest, ".ssh/config", "target", "", deadline(), () -> { })
                    .entries.size() == 2);
        }
        check(route.target().hostname.equals("target.example"));
        System.out.println("CONNECTION_MATERIAL_ROUTE_REAL_JNI manifest=pass snapshot=pass gates=pass revoke-cleanup=pass");
    }
}
