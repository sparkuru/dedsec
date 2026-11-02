package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** CSHT ownership and strict wire tests; actual parser/matching is proved by the native matrix. */
public final class SshHostTrustMaterialTest {
    static final byte[] BODY = "fixture original known-host bytes\n".getBytes(StandardCharsets.US_ASCII);
    static final String HOST = "Example.invalid";
    static final String FP = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final class Clock implements SshHostTrustMaterial.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    static final class Binding implements SshHostTrustMaterial.NativeBinding {
        byte[] response;
        final List<byte[]> bodies = new ArrayList<>(), endpoints = new ArrayList<>(), outputs = new ArrayList<>();
        Runnable during;
        Throwable failure;
        long deadline;
        @Override public byte[] inspect(byte[] body, byte[] host, int port, boolean prepare, long limit) throws IOException {
            assertEquals(HOST, new String(host, StandardCharsets.US_ASCII)); assertEquals(2200, port);
            bodies.add(body); endpoints.add(host); deadline = limit;
            if (during != null) during.run();
            if (failure instanceof IOException) throw (IOException)failure;
            if (failure instanceof RuntimeException) throw (RuntimeException)failure;
            if (failure instanceof Error) throw (Error)failure;
            byte[] result = response == null ? null : response.clone(); outputs.add(result); return result;
        }
        void clean() {
            for (List<byte[]> arrays : List.of(bodies, endpoints, outputs))
                for (byte[] value : arrays) if (value != null) assertArrayEquals(new byte[value.length], value);
        }
    }
    static byte[] wire(byte[] body, boolean prepare, byte[] replacement, int[][] rows) {
        boolean removable = rows.length != 0;
        for (int[] row : rows) removable &= row[3] == 0;
        int flags = removable ? prepare ? 3 : 1 : 0;
        int length = replacement == null ? 0 : replacement.length;
        ByteBuffer frame = ByteBuffer.allocate(88 + rows.length * 216 + length)
                .putInt(0x43534854).putInt(1).putInt(prepare ? 1 : 0).putInt(flags)
                .putInt(rows.length).putInt(length).put(VaultCodec.digest(body))
                .put(replacement == null ? new byte[32] : VaultCodec.digest(replacement));
        for (int[] row : rows) {
            byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
            byte[] fingerprint = FP.getBytes(StandardCharsets.US_ASCII);
            frame.putInt(row[0]).putInt(row[1]).putInt(row[2]).putInt(row[3])
                    .putInt(algorithm.length).putInt(fingerprint.length).put(algorithm)
                    .put(new byte[128 - algorithm.length]).put(fingerprint).put(new byte[64 - fingerprint.length]);
        }
        if (replacement != null) frame.put(replacement);
        return frame.array();
    }
    private interface Work { void run() throws Exception; }
    private static void rejected(Work work) throws Exception {
        try { work.run(); fail("Expected malformed or expired trust result to be rejected"); }
        catch (IOException expected) { }
    }
    private static SshHostTrustMaterial.Result inspect(Binding binding, boolean prepare) throws IOException {
        return SshHostTrustMaterial.inspect(BODY, HOST, 2200, prepare, 2000, new Clock(), binding);
    }

    @Test public void queryPublishesOnlyStrictPublicMetadataAndWipesNativeLoans() throws Exception {
        byte[] original = BODY.clone(); Binding binding = new Binding();
        binding.response = wire(BODY, false, null, new int[][] {{1, 0, 0, 0}, {8, 0, 1, 0}});
        try (SshHostTrustMaterial.Result result = inspect(binding, false)) {
            assertTrue(result.canForget); assertEquals(2, result.records.size());
            assertEquals("hashed", result.records.get(1).selector);
            assertEquals(FP, result.records.get(0).fingerprint);
            assertEquals(6, result.records.get(0).metadata().size());
            rejected(result::replacement);
            try { result.records.clear(); fail("Mutable records"); } catch (UnsupportedOperationException expected) { }
        }
        assertArrayEquals(original, BODY); assertNotSame(BODY, binding.bodies.get(0)); binding.clean();
    }

    @Test public void prepareOwnsReplacementIncludingEmptyBodyAndClosesIt() throws Exception {
        for (byte[] candidate : List.of(new byte[0], "preserved other bytes".getBytes(StandardCharsets.US_ASCII))) {
            Binding binding = new Binding(); binding.response = wire(BODY, true, candidate, new int[][] {{2, 0, 0, 0}});
            SshHostTrustMaterial.Result result = inspect(binding, true);
            assertArrayEquals(candidate, result.replacement());
            byte[] copy = result.replacement(); Arrays.fill(copy, (byte)12);
            assertArrayEquals(candidate, result.replacement()); binding.clean();
            result.close(); result.close(); rejected(result::replacement);
        }
    }

    @Test public void noMatchesAndEveryBlockingReasonNeverPublishReplacement() throws Exception {
        for (int[][] rows : List.of(new int[0][0], new int[][] {{1, 1, 0, 2}},
                new int[][] {{1, 2, 2, 2}}, new int[][] {{2, 0, 2, 1}}, new int[][] {{3, 0, 1, 3}})) {
            for (boolean prepare : List.of(false, true)) {
                Binding binding = new Binding(); binding.response = wire(BODY, prepare, null, rows);
                try (SshHostTrustMaterial.Result result = inspect(binding, prepare)) {
                    assertFalse(result.canForget); assertEquals(rows.length, result.records.size()); rejected(result::replacement);
                }
                binding.clean();
            }
        }
    }

    @Test public void malformedHeadersFlagsLengthsBaseAndReplacementDigestsReject() throws Exception {
        byte[] good = wire(BODY, true, new byte[] {1}, new int[][] {{1, 0, 0, 0}});
        List<byte[]> bad = new ArrayList<>();
        for (int[] patch : new int[][] {{0, 0}, {4, 2}, {8, 0}, {12, 2}, {12, 4}, {16, -1}, {16, 129}, {20, -1}, {20, 1048577}}) {
            byte[] value = good.clone(); ByteBuffer.wrap(value).putInt(patch[0], patch[1]); bad.add(value);
        }
        for (int offset : new int[] {24, 56, 304}) { byte[] value = good.clone(); value[offset] ^= 1; bad.add(value); }
        bad.add(Arrays.copyOf(good, good.length + 1)); bad.add(Arrays.copyOf(good, 87));
        bad.add(new byte[88 + 128 * 216 + 1048577]);
        for (byte[] value : bad) {
            Binding binding = new Binding(); binding.response = value;
            rejected(() -> inspect(binding, true)); binding.clean();
        }
        Binding missing = new Binding(); rejected(() -> inspect(missing, true)); missing.clean();
    }

    @Test public void rowOrderingEnumsPolicyRelationshipsPaddingAndTextAreStrict() throws Exception {
        byte[] good = wire(BODY, false, null, new int[][] {{1, 0, 0, 0}, {2, 0, 1, 0}});
        List<byte[]> bad = new ArrayList<>();
        for (int[] patch : new int[][] {{88, 0}, {88, 16385}, {92, 3}, {96, -1}, {100, 4},
                {104, 0}, {104, 129}, {108, 49}, {304, 1}, {92, 1}, {96, 2}}) {
            byte[] value = good.clone(); ByteBuffer.wrap(value).putInt(patch[0], patch[1]); bad.add(value);
        }
        for (int offset : new int[] {112, 123, 240, 290}) {
            byte[] value = good.clone(); value[offset] = 32; bad.add(value);
        }
        // Decoder also rejects noncanonical base64 padding bits in a SHA256 fingerprint.
        byte[] base64 = good.clone(); base64[240 + 49] = 'B'; bad.add(base64);
        for (byte[] value : bad) {
            Binding binding = new Binding(); binding.response = value;
            rejected(() -> inspect(binding, false)); binding.clean();
        }
    }

    @Test public void queryCannotSmuggleCandidateOrInconsistentCanForgetFlag() throws Exception {
        List<byte[]> bad = new ArrayList<>();
        byte[] query = wire(BODY, false, new byte[] {1}, new int[][] {{1, 0, 0, 0}}); bad.add(query);
        byte[] empty = wire(BODY, false, null, new int[0][0]); ByteBuffer.wrap(empty).putInt(12, 1); bad.add(empty);
        byte[] blocked = wire(BODY, false, null, new int[][] {{1, 1, 0, 2}}); ByteBuffer.wrap(blocked).putInt(12, 1); bad.add(blocked);
        for (byte[] value : bad) {
            Binding binding = new Binding(); binding.response = value;
            rejected(() -> inspect(binding, false)); binding.clean();
        }
    }

    @Test public void originalBudgetIsClampedForNativeWithoutRenewalAndLateOutputIsWiped() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding();
        binding.response = wire(BODY, false, null, new int[0][0]);
        try (SshHostTrustMaterial.Result ignored = SshHostTrustMaterial.inspect(BODY, HOST, 2200, false, 121000, clock, binding)) {
            assertEquals(61000, binding.deadline);
        }
        binding.clean();
        Binding late = new Binding(); late.response = binding.response; late.during = () -> clock.now = 2000;
        rejected(() -> SshHostTrustMaterial.inspect(BODY, HOST, 2200, false, 2000, clock, late)); late.clean();
        rejected(() -> SshHostTrustMaterial.inspect(BODY, HOST, 2200, false, 2000, clock, binding));
        clock.now = 1000;
        rejected(() -> SshHostTrustMaterial.inspect(BODY, HOST, 2200, false, 121001, clock, binding));
    }

    @Test public void invalidCallerInputsFailBeforeNativeAndAllThrowablePathsCleanLoans() throws Exception {
        for (String host : new String[] {null, "", "bad host", "host\n", "a".repeat(254)}) {
            Binding binding = new Binding();
            rejected(() -> SshHostTrustMaterial.inspect(BODY, host, 2200, false, 2000, new Clock(), binding));
            assertTrue(binding.bodies.isEmpty());
        }
        for (Throwable failure : new Throwable[] {new IOException("fixed"), new IllegalStateException("fixed"), new AssertionError("fixed")}) {
            Binding binding = new Binding(); binding.failure = failure;
            try { inspect(binding, false); fail("Expected original failure"); }
            catch (Throwable actual) { assertSame(failure, actual); }
            binding.clean();
        }
        Binding mutated = new Binding(); mutated.response = wire(BODY, false, null, new int[0][0]);
        mutated.during = () -> mutated.bodies.get(0)[0] ^= 1;
        rejected(() -> inspect(mutated, false)); mutated.clean();
        assertEquals('f', BODY[0]);
    }
}
