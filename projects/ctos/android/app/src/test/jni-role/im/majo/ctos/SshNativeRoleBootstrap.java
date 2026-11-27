package im.majo.ctos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Host-only JNI probe for the production native role entry points. */
public final class SshNativeRoleBootstrap {
    private static final class Native {
        static native byte[] open(int program, int hop, int port, long deadline,
                String connectionId, String targetIdentity, String host, String publicViewId,
                byte[] roleNonce, byte[] connectionBinding) throws IOException;
        static native byte[] openChained(int program, int hop, int port, long deadline,
                String connectionId, String targetIdentity, String host, String publicViewId,
                byte[] roleNonce, byte[] connectionBinding, String chainIdentity) throws IOException;
        static native boolean owns(long token, int ownerUid, byte[] wire) throws IOException;
        static native void release(long token, int ownerUid) throws IOException;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("role JNI contract failed");
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte)value);
        return bytes;
    }

    private static String text(ByteBuffer input) {
        int length = Short.toUnsignedInt(input.getShort());
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void fields(byte[] wire, int version, String target, String chain) {
        ByteBuffer input = ByteBuffer.wrap(wire);
        check(input.getInt() == 0x43535242 && Byte.toUnsignedInt(input.get()) == version);
        input.position(104);
        check(text(input).equals("connection_test"));
        check(text(input).equals(target));
        check(text(input).equals("127.0.0.1"));
        check(text(input).equals("view_test"));
        if (version == 2) check(text(input).equals(chain));
        check(!input.hasRemaining());
    }

    private static void expectOpenFailure(long deadline, String chainIdentity) throws Exception {
        try {
            Native.openChained(2, 2, 2222, deadline, "connection_test", "target:jump",
                    "127.0.0.1", "view_test", filled(7), filled(9), chainIdentity);
            throw new AssertionError("invalid chain identity was accepted");
        } catch (IOException expected) {
            // The production JNI entry did not issue an owner token.
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected native library path");
        System.load(args[0]);
        String uptime = Files.readString(Path.of("/proc/uptime"), StandardCharsets.US_ASCII).split(" ")[0];
        long deadline = (long)(Double.parseDouble(uptime) * 1000L) + 30_000L;
        byte[] legacy = Native.open(2, 0, 2221, deadline, "connection_test", "target:legacy",
                "127.0.0.1", "view_test", filled(1), filled(9));
        byte[] nonce = filled(2), binding = filled(9);
        byte[] chained = Native.openChained(1, 1, 2222, deadline, "connection_test", "target:hop",
                "127.0.0.1", "view_test", nonce, binding, "target:whole-chain");
        Arrays.fill(nonce, (byte)0);
        Arrays.fill(binding, (byte)0);
        long legacyToken = ByteBuffer.wrap(legacy).getLong(8);
        long chainedToken = ByteBuffer.wrap(chained).getLong(8);
        int ownerUid = ByteBuffer.wrap(legacy).getInt(20);
        check(ownerUid == ByteBuffer.wrap(chained).getInt(20));
        fields(legacy, 1, "target:legacy", "target:legacy");
        fields(chained, 2, "target:hop", "target:whole-chain");
        check(Native.owns(legacyToken, ownerUid, legacy));
        check(Native.owns(chainedToken, ownerUid, chained));
        check(!Native.owns(chainedToken, ownerUid + 1, chained));
        byte[] forged = chained.clone();
        forged[forged.length - 1] ^= 1;
        check(!Native.owns(chainedToken, ownerUid, forged));
        forged = chained.clone();
        forged[4] = 1;
        check(!Native.owns(chainedToken, ownerUid, forged));
        forged = chained.clone();
        ByteBuffer.wrap(forged).putLong(8, legacyToken);
        check(!Native.owns(legacyToken, ownerUid, forged));
        check(!Native.owns(chainedToken, ownerUid, Arrays.copyOf(chained, chained.length - 1)));
        expectOpenFailure(deadline, "");
        expectOpenFailure(deadline, null);
        Native.release(chainedToken, ownerUid);
        Native.release(legacyToken, ownerUid);
        check(!Native.owns(chainedToken, ownerUid, chained));
        check(!Native.owns(legacyToken, ownerUid, legacy));
        System.out.println("ROLE_JNI v1-v2-canonical-ownership=pass tampering=rejected cleanup=pass");
    }
}
