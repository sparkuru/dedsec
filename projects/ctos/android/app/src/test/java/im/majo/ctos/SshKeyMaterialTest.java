package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The native parser/proof has its own real C matrix; these assert the Java wire/loan boundary. */
public final class SshKeyMaterialTest {
    private static final class Clock implements SshKeyMaterial.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Binding implements SshKeyMaterial.NativeBinding, SshKeyMaterial.PassphraseNativeBinding {
        byte[] response;
        final List<byte[]> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        final List<byte[]> passphrases = new ArrayList<>();
        Runnable during;
        Throwable failure;
        @Override public byte[] inspect(byte[] input, byte[] passphrase, long deadline) throws IOException {
            passphrases.add(passphrase);
            return inspect(input, deadline);
        }
        @Override public byte[] inspect(byte[] input, long deadline) throws IOException {
            assertEquals(2000, deadline); inputs.add(input);
            if (during != null) during.run();
            if (failure instanceof IOException) throw (IOException)failure;
            if (failure instanceof RuntimeException) throw (RuntimeException)failure;
            if (failure instanceof Error) throw (Error)failure;
            byte[] wire = response == null ? null : response.clone();
            outputs.add(wire); return wire;
        }
        void clean() {
            for (byte[] input : inputs) assertArrayEquals(new byte[input.length], input);
            for (byte[] output : outputs) if (output != null) assertArrayEquals(new byte[output.length], output);
            for (byte[] phrase : passphrases) assertArrayEquals(new byte[phrase.length], phrase);
        }
    }
    private static byte[] blob(String algorithm) {
        byte[] name = algorithm.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + name.length + 8).putInt(name.length).put(name).putLong(1234).array();
    }
    private static byte[] wire(int status, int kind, int flags, byte[] blob) {
        ByteBuffer frame = ByteBuffer.allocate(56 + blob.length).putInt(0x43534b45).putInt(1)
                .putInt(status).putInt(kind).putInt(flags).putInt(blob.length);
        frame.put(blob.length == 0 ? new byte[32] : VaultCodec.digest(blob)).put(blob);
        return frame.array();
    }
    private interface Work { void run() throws Exception; }
    private static void rejected(Work work) throws Exception {
        try { work.run(); fail("Expected rejected public export metadata"); }
        catch (IOException expected) { }
    }
    @Test public void actualNativeLoanIsExclusiveAndResultIsPublicImmutableMetadata() throws Exception {
        byte[] caller = {1, 2, 3}, publicBlob = blob("ssh-ed25519");
        Binding binding = new Binding(); binding.response = wire(0, 1, 1, publicBlob);
        SshKeyMaterial.Result result = SshKeyMaterial.inspect(caller, 2000, new Clock(), binding);
        assertArrayEquals(new byte[] {1, 2, 3}, caller);
        assertNotSame(caller, binding.inputs.get(0));
        assertEquals(SshKeyMaterial.Status.PARSED, result.status); assertEquals("ssh-ed25519", result.algorithm);
        assertFalse(result.encrypted);
        assertTrue(result.fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
        assertArrayEquals(publicBlob, result.publicBlob());
        byte[] clone = result.publicBlob(); Arrays.fill(clone, (byte)0);
        assertArrayEquals(publicBlob, result.publicBlob());
        assertTrue(new String(result.publicLine(), StandardCharsets.US_ASCII).startsWith("ssh-ed25519 "));
        binding.clean();
    }
    @Test public void completeOutcomeMatrixRejectsBlobOrProofOnFailure() throws Exception {
        for (int status = 1; status <= 4; status++) {
            Binding binding = new Binding(); binding.response = wire(status, 0, status >= 3 ? 2 : 0, new byte[0]);
            SshKeyMaterial.Result result = SshKeyMaterial.inspect(new byte[] {1}, 2000, new Clock(), binding);
            assertEquals(status, result.status.ordinal()); assertEquals("", result.algorithm);
            assertEquals(status >= 3, result.encrypted);
            assertEquals("", result.fingerprint); assertEquals(0, result.publicBlob().length);
            assertEquals(0, result.publicLine().length); binding.clean();
            binding.response = wire(status, 1, 1, blob("ssh-ed25519"));
            rejected(() -> SshKeyMaterial.inspect(new byte[] {1}, 2000, new Clock(), binding)); binding.clean();
        }
    }
    @Test public void malformedHeadersAlgorithmDigestBoundsAndTrailingDataRejectAndWipe() throws Exception {
        byte[] good = wire(0, 1, 1, blob("ssh-ed25519"));
        List<byte[]> bad = new ArrayList<>();
        for (int[] change : new int[][] {{0, 0}, {4, 2}, {8, 5}, {12, 0}, {12, 6}, {16, 0}, {16, 2}, {16, 4}, {20, 0}}) {
            byte[] value = good.clone(); ByteBuffer.wrap(value).putInt(change[0], change[1]); bad.add(value);
        }
        byte[] digest = good.clone(); digest[24] ^= 1; bad.add(digest);
        bad.add(wire(0, 5, 1, blob("ssh-ed25519")));
        bad.add(Arrays.copyOf(good, good.length + 1)); bad.add(Arrays.copyOf(good, 55));
        bad.add(new byte[SshKeyMaterial.MAX_BYTES + 57]);
        for (byte[] value : bad) {
            Binding binding = new Binding(); binding.response = value;
            rejected(() -> SshKeyMaterial.inspect(new byte[] {1}, 2000, new Clock(), binding)); binding.clean();
        }
        Binding missing = new Binding();
        rejected(() -> SshKeyMaterial.inspect(new byte[] {1}, 2000, new Clock(), missing)); missing.clean();
    }
    @Test public void originalDeadlineAndThrowableCleanupNeverTouchCallerArrays() throws Exception {
        Clock clock = new Clock(); Binding binding = new Binding(); binding.response = wire(0, 1, 1, blob("ssh-ed25519"));
        byte[] caller = {1, 2, 3};
        binding.during = () -> clock.now = 2000;
        rejected(() -> SshKeyMaterial.inspect(caller, 2000, clock, binding)); binding.clean();
        assertArrayEquals(new byte[] {1, 2, 3}, caller);
        for (Throwable failure : new Throwable[] {new IOException("fixed"), new IllegalStateException("fixed"), new AssertionError("fixed")}) {
            Clock current = new Clock(); Binding throwing = new Binding(); throwing.failure = failure;
            try { SshKeyMaterial.inspect(caller, 2000, current, throwing); fail("Expected original exception"); }
            catch (Throwable actual) { assertSame(failure, actual); }
            throwing.clean(); assertArrayEquals(new byte[] {1, 2, 3}, caller);
        }
        rejected(() -> SshKeyMaterial.inspect(new byte[0], 2000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(new byte[SshKeyMaterial.MAX_BYTES + 1], 2000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(caller, 1000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(caller, 61001, new Clock(), binding));
    }
    @Test public void encryptedPublicProofUsesAnOwnedPassphraseAndRetainsOnlyPublicMetadata() throws Exception {
        byte[] caller = {1, 2, 3}, phrase = "密钥口令".getBytes(StandardCharsets.UTF_8);
        byte[] beforePhrase = phrase.clone(), publicBlob = blob("ecdsa-sha2-nistp256");
        Binding binding = new Binding(); binding.response = wire(0, 2, 3, publicBlob);
        SshKeyMaterial.Result result = SshKeyMaterial.inspect(caller, phrase, 2000, new Clock(), binding);
        assertEquals(SshKeyMaterial.Status.PARSED, result.status); assertTrue(result.encrypted);
        assertEquals("ecdsa-sha2-nistp256", result.algorithm); assertArrayEquals(publicBlob, result.publicBlob());
        assertNotSame(caller, binding.inputs.get(0)); assertNotSame(phrase, binding.passphrases.get(0));
        assertArrayEquals(new byte[] {1, 2, 3}, caller); assertArrayEquals(beforePhrase, phrase);
        binding.clean();
        Binding absent = new Binding(); absent.response = wire(3, 0, 2, new byte[0]);
        assertEquals(SshKeyMaterial.Status.NEEDS_PASSPHRASE,
                SshKeyMaterial.inspect(caller, null, 2000, new Clock(), absent).status);
        assertEquals(0, absent.passphrases.get(0).length); absent.clean();
    }
    @Test public void encryptedFailuresCannotClaimProofOrPublishAnyKey() throws Exception {
        for (int status = 1; status <= 4; status++) {
            Binding binding = new Binding(); binding.response = wire(status, 0, 2, new byte[0]);
            SshKeyMaterial.Result result = SshKeyMaterial.inspect(new byte[] {1}, new byte[] {2}, 2000, new Clock(), binding);
            assertEquals(status, result.status.ordinal()); assertTrue(result.encrypted);
            assertEquals("", result.algorithm); assertEquals("", result.fingerprint);
            assertEquals(0, result.publicBlob().length); assertEquals(0, result.publicLine().length); binding.clean();
            binding.response = wire(status, 0, 3, new byte[0]);
            rejected(() -> SshKeyMaterial.inspect(new byte[] {1}, new byte[] {2}, 2000, new Clock(), binding));
            binding.clean();
        }
        for (int status : new int[] {3, 4}) {
            Binding binding = new Binding(); binding.response = wire(status, 0, 0, new byte[0]);
            rejected(() -> SshKeyMaterial.inspect(new byte[] {1}, new byte[] {2}, 2000, new Clock(), binding));
            binding.clean();
        }
    }
    @Test public void passphraseBoundsAndAllThrowablePathsWipeBothOwnedInputs() throws Exception {
        byte[] caller = {1, 2, 3}, phrase = {4, 5, 6};
        for (Throwable failure : new Throwable[] {new IOException("fixed"), new IllegalStateException("fixed"), new AssertionError("fixed")}) {
            Binding binding = new Binding(); binding.failure = failure;
            try { SshKeyMaterial.inspect(caller, phrase, 2000, new Clock(), binding); fail("Expected original exception"); }
            catch (Throwable actual) { assertSame(failure, actual); }
            binding.clean(); assertArrayEquals(new byte[] {1, 2, 3}, caller); assertArrayEquals(new byte[] {4, 5, 6}, phrase);
        }
        Clock clock = new Clock(); Binding expired = new Binding(); expired.response = wire(0, 1, 3, blob("ssh-ed25519"));
        expired.during = () -> clock.now = 2000;
        rejected(() -> SshKeyMaterial.inspect(caller, phrase, 2000, clock, expired)); expired.clean();
        Binding binding = new Binding(); binding.response = wire(0, 1, 3, blob("ssh-ed25519"));
        byte[] maximum = new byte[SshKeyMaterial.MAX_PASSPHRASE_BYTES]; Arrays.fill(maximum, (byte)'p');
        assertTrue(SshKeyMaterial.inspect(caller, maximum, 2000, new Clock(), binding).encrypted);
        binding.clean(); assertEquals('p', maximum[0]);
        int calls = binding.inputs.size();
        rejected(() -> SshKeyMaterial.inspect(caller, new byte[8193], 2000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(caller, new byte[] {1, 0, 2}, 2000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(caller, phrase, 1000, new Clock(), binding));
        rejected(() -> SshKeyMaterial.inspect(caller, phrase, 61001, new Clock(), binding));
        assertEquals(calls, binding.inputs.size());
    }
}
