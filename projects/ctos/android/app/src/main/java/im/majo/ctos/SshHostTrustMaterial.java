package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Owned supplied-body trust inspection; this does not approve any offered host key. */
final class SshHostTrustMaterial {
    static final int MAX_BYTES = 1024 * 1024, MAX_ROWS = 128;
    private static final int HEADER_BYTES = 88, ROW_BYTES = 216;
    interface Clock { long now(); }
    interface NativeBinding {
        byte[] inspect(byte[] body, byte[] host, int port, boolean prepare, long deadline) throws IOException;
    }
    private static final class NativeLoader {
        static { System.loadLibrary("ctos_ssh_files"); }
        static void load() { }
    }
    private static native byte[] nativeInspect(byte[] body, byte[] host, int port,
            boolean prepare, long deadline) throws IOException;

    static final class Record {
        final int line;
        final String algorithm, fingerprint, marker, selector, reason;
        private Record(ByteBuffer frame, int previousLine) throws IOException {
            line = frame.getInt();
            int markerCode = frame.getInt(), selectorCode = frame.getInt(), reasonCode = frame.getInt();
            int nameLength = frame.getInt(), fingerprintLength = frame.getInt();
            if (line <= previousLine || line > 16384 || markerCode < 0 || markerCode > 2
                    || selectorCode < 0 || selectorCode > 2 || reasonCode < 0 || reasonCode > 3
                    || nameLength < 1 || nameLength > 128 || fingerprintLength != 50) throw invalid();
            marker = new String[] {"none", "revoked", "ca"}[markerCode];
            selector = new String[] {"literal", "hashed", "shared"}[selectorCode];
            reason = new String[] {"none", "shared", "policy", "unsupported"}[reasonCode];
            if (markerCode != 0 ? reasonCode != 2 : reasonCode == 2
                    || (selectorCode == 2 ? reasonCode != 1 : reasonCode == 1)) throw invalid();
            algorithm = paddedAscii(frame, nameLength, 128);
            fingerprint = paddedAscii(frame, fingerprintLength, 64);
            if (!algorithm.matches("[A-Za-z0-9@._+-]{1,128}")
                    || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) throw invalid();
            byte[] decoded = null;
            try {
                decoded = Base64.getDecoder().decode(fingerprint.substring(7));
                if (decoded.length != 32 || !Base64.getEncoder().withoutPadding().encodeToString(decoded)
                        .equals(fingerprint.substring(7))) throw invalid();
            } catch (IllegalArgumentException failure) { throw invalid(); }
            finally { VaultCodec.wipe(decoded); }
        }
        Map<String, Object> metadata() {
            return VaultCollections.map("line", line, "algorithm", algorithm, "fingerprint", fingerprint,
                    "marker", marker, "selector", selector, "reason", reason);
        }
    }

    static final class Result implements AutoCloseable {
        final List<Record> records;
        final boolean canForget;
        private byte[] replacement;
        private boolean closed;
        private Result(byte[] wire, byte[] expectedBase, boolean prepare) throws IOException {
            if (wire == null || wire.length < HEADER_BYTES
                    || wire.length > HEADER_BYTES + MAX_ROWS * ROW_BYTES + MAX_BYTES) throw invalid();
            ByteBuffer frame = ByteBuffer.wrap(wire);
            if (frame.getInt() != 0x43534854 || frame.getInt() != 1
                    || frame.getInt() != (prepare ? 1 : 0)) throw invalid();
            int flags = frame.getInt(), count = frame.getInt(), length = frame.getInt();
            if (flags < 0 || flags > 3 || count < 0 || count > MAX_ROWS || length < 0 || length > MAX_BYTES
                    || wire.length != HEADER_BYTES + count * ROW_BYTES + length) throw invalid();
            byte[] base = new byte[32], digest = new byte[32], actual = null;
            frame.get(base); frame.get(digest);
            try {
                if (!MessageDigest.isEqual(base, expectedBase)) throw invalid();
                List<Record> rows = new ArrayList<>();
                int previousLine = 0;
                boolean allOrdinary = count != 0;
                for (int index = 0; index < count; index++) {
                    Record row = new Record(frame, previousLine);
                    rows.add(row); previousLine = row.line;
                    allOrdinary &= row.reason.equals("none");
                }
                canForget = allOrdinary;
                int expectedFlags = canForget ? prepare ? 3 : 1 : 0;
                if (flags != expectedFlags) throw invalid();
                if ((flags & 2) == 0) {
                    if (length != 0 || !zero(digest)) throw invalid();
                } else {
                    byte[] candidate = new byte[length];
                    try {
                        frame.get(candidate); actual = VaultCodec.digest(candidate);
                        if (!MessageDigest.isEqual(digest, actual)) throw invalid();
                        replacement = candidate; candidate = null;
                    } finally { VaultCodec.wipe(candidate); }
                }
                if (frame.hasRemaining()) throw invalid();
                records = VaultCollections.copyList(rows);
            } catch (IOException | RuntimeException | Error failure) {
                VaultCodec.wipe(replacement); replacement = null; throw failure;
            } finally { VaultCodec.wipe(base); VaultCodec.wipe(digest); VaultCodec.wipe(actual); }
        }
        synchronized byte[] replacement() throws IOException {
            if (closed || replacement == null) throw invalid();
            return replacement.clone();
        }
        List<Map<String, Object>> metadata() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Record row : records) result.add(row.metadata());
            return VaultCollections.copyList(result);
        }
        @Override public synchronized void close() {
            closed = true; VaultCodec.wipe(replacement); replacement = null;
        }
    }

    static Result inspect(byte[] body, String host, int port, boolean prepare, long deadline) throws IOException {
        return inspect(body, host, port, prepare, deadline, SystemClock::elapsedRealtime,
                (input, endpoint, selectedPort, mode, limit) -> {
                    NativeLoader.load(); return nativeInspect(input, endpoint, selectedPort, mode, limit);
                });
    }

    static Result inspect(byte[] body, String host, int port, boolean prepare, long deadline,
            Clock clock, NativeBinding binding) throws IOException {
        if (body == null || body.length > MAX_BYTES || clock == null || binding == null) throw invalid();
        try { SshPasswordProfile.checkTarget(host, port); }
        catch (VaultException failure) { throw invalid(); }
        long now = check(deadline, clock);
        if (deadline - now > 120_000) throw invalid();
        byte[] owned = null, endpoint = null, wire = null, digest = null, after = null;
        Result result = null;
        try {
            owned = body.clone(); endpoint = host.getBytes(StandardCharsets.US_ASCII);
            digest = VaultCodec.digest(owned);
            now = check(deadline, clock);
            wire = binding.inspect(owned, endpoint, port, prepare, Math.min(deadline, now + 60_000));
            check(deadline, clock);
            after = VaultCodec.digest(owned);
            if (!MessageDigest.isEqual(digest, after)) throw invalid();
            result = new Result(wire, digest, prepare);
            check(deadline, clock);
            Result published = result; result = null; return published;
        } finally {
            if (result != null) result.close();
            VaultCodec.wipe(owned); VaultCodec.wipe(endpoint); VaultCodec.wipe(wire);
            VaultCodec.wipe(digest); VaultCodec.wipe(after);
        }
    }

    private static long check(long deadline, Clock clock) throws IOException {
        long now = clock.now();
        if (now < 0 || deadline <= now) throw invalid();
        return now;
    }
    private static String paddedAscii(ByteBuffer frame, int length, int width) throws IOException {
        byte[] value = new byte[length];
        try {
            frame.get(value);
            for (byte current : value) if (current < 33 || current > 126) throw invalid();
            for (int index = length; index < width; index++) if (frame.get() != 0) throw invalid();
            return new String(value, StandardCharsets.US_ASCII);
        } finally { VaultCodec.wipe(value); }
    }
    private static boolean zero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }
    private static IOException invalid() { return new IOException("SSH host trust could not be inspected"); }
}
