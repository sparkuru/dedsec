package im.majo.ctos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Host-only test linking the real Java role decoder to production JNI ownership. */
public final class SshRoleRealJniTest {
    private static final class View implements SshRoleContext.PublicView {
        @Override public boolean active() { return true; }
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("real role JNI contract failed");
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte)value);
        return bytes;
    }

    private static void rejected(byte[] wire, SshRoleContext.Clock clock) throws Exception {
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        try {
            try {
                SshNativeRoleBootstrap.issue(wire, registry, new View(), clock,
                        SshNativeRoleBootstrap.productionOwnership());
                throw new AssertionError("unowned native role was issued");
            } catch (VaultException expected) {
                check("SSH_ROLE_REQUIRED".equals(expected.code));
                check(registry.size() == 0);
            }
        } finally { registry.close(); }
    }

    public static void main(String[] args) throws Exception {
        String uptime = Files.readString(Path.of("/proc/uptime")).split(" ")[0];
        long now = (long)(Double.parseDouble(uptime) * 1000L);
        int pid = Math.toIntExact(ProcessHandle.current().pid());
        SshRoleContext.Clock clock = () -> now;
        SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(
                SshRoleContext.Program.JUMP, pid, 0, "connection_test", "target:hop-account",
                "127.0.0.1", 2221, "view_test", filled(3), filled(4), now + 30_000L,
                "target:whole-chain");
        byte[] wire = SshNativeRoleBootstrap.open(bootstrap);
        byte[] forged = wire.clone();
        forged[forged.length - 1] ^= 1;
        rejected(forged, clock);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = null;
        try {
            issued = SshNativeRoleBootstrap.issue(wire, registry, new View(), clock,
                    SshNativeRoleBootstrap.productionOwnership());
            check(issued.role.identityVersion == SshRoleContext.IDENTITY_V2);
            check("target:hop-account".equals(issued.role.targetIdentity));
            check("target:whole-chain".equals(issued.role.chainIdentity));
            SshNativeRoleBootstrap.release(issued, registry,
                    SshNativeRoleBootstrap.productionRelease());
            issued = null;
            rejected(wire, clock);
        } finally {
            if (issued != null) SshNativeRoleBootstrap.release(issued, registry,
                    SshNativeRoleBootstrap.productionRelease());
            registry.close();
            VaultCodec.wipe(wire);
            VaultCodec.wipe(forged);
        }
        System.out.println("ROLE_REAL_JNI java-native-v2=pass forged-and-stale=rejected cleanup=pass");
    }
}
