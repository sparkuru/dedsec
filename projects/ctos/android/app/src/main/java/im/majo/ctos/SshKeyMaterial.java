package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** Public-only result derived and self-verified from an actual supplied private key. */
final class SshKeyMaterial {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int HEADER_BYTES = 56;
    enum Status { PARSED, MALFORMED, UNSUPPORTED, NEEDS_PASSPHRASE }
    interface Clock { long now(); }
    interface NativeBinding { byte[] inspect(byte[] input, long deadline) throws IOException; }
    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native byte[] inspect(byte[] input, long deadline) throws IOException;
    }
    static final class Result {
        final Status status;
        final int kind;
        final String algorithm, fingerprint;
        private final byte[] blob;
        private Result(byte[] wire) throws IOException {
            if (wire == null || wire.length < HEADER_BYTES || wire.length > HEADER_BYTES + MAX_BYTES) throw invalid();
            ByteBuffer frame = ByteBuffer.wrap(wire);
            if (frame.getInt() != 0x43534b45 || frame.getInt() != 1) throw invalid();
            int statusCode = frame.getInt();
            kind = frame.getInt();
            int flags = frame.getInt(), length = frame.getInt();
            byte[] digest = new byte[32], actual = null;
            frame.get(digest);
            try {
                if (statusCode < 0 || statusCode >= Status.values().length || length != frame.remaining()) throw invalid();
                status = Status.values()[statusCode];
                if (status != Status.PARSED) {
                    if (kind != 0 || length != 0 || (flags != 0 && flags != 2)
                            || status == Status.NEEDS_PASSPHRASE && flags != 2 || !zero(digest)) throw invalid();
                    algorithm = ""; fingerprint = ""; blob = new byte[0];
                    return;
                }
                if (kind < 1 || kind > 5 || flags != 1 || length < 8) throw invalid();
                algorithm = new String[] {"ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384",
                        "ecdsa-sha2-nistp521", "ssh-rsa"}[kind - 1];
                byte[] name = algorithm.getBytes(StandardCharsets.US_ASCII);
                if (frame.getInt() != name.length || frame.remaining() < name.length) throw invalid();
                for (byte value : name) if (frame.get() != value) throw invalid();
                actual = VaultCodec.digest(Arrays.copyOfRange(wire, HEADER_BYTES, wire.length));
                if (!MessageDigest.isEqual(digest, actual)) throw invalid();
                blob = Arrays.copyOfRange(wire, HEADER_BYTES, wire.length);
                fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            } finally { VaultCodec.wipe(digest); VaultCodec.wipe(actual); }
        }
        byte[] publicBlob() { return blob.clone(); }
        byte[] publicLine() {
            return status == Status.PARSED ? (algorithm + " " + Base64.getEncoder().encodeToString(blob) + "\n")
                    .getBytes(StandardCharsets.US_ASCII) : new byte[0];
        }
    }
    static Result inspect(byte[] input, long deadline) throws IOException {
        return inspect(input, deadline, SystemClock::elapsedRealtime, Native::inspect);
    }
    static Result inspect(byte[] input, long deadline, Clock clock, NativeBinding binding) throws IOException {
        if (input == null || input.length == 0 || input.length > MAX_BYTES || clock == null || binding == null) throw invalid();
        check(deadline, clock);
        byte[] owned = input.clone(), wire = null;
        try {
            wire = binding.inspect(owned, deadline);
            check(deadline, clock);
            Result result = new Result(wire);
            check(deadline, clock);
            return result;
        } finally { VaultCodec.wipe(owned); VaultCodec.wipe(wire); }
    }
    private static void check(long deadline, Clock clock) throws IOException {
        long now = clock.now();
        if (deadline <= now || deadline - now > 60_000) throw invalid();
    }
    private static boolean zero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }
    private static IOException invalid() { return new IOException("SSH private key could not be inspected"); }
}
