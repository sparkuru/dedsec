package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Native real-Options policy is tested separately; these assert typed projection and owner fences. */
public final class SshDirectConfigPlanTest {
    private interface Work { void run() throws Exception; }
    private static final class Binding implements SshConfigEvaluator.NativeBinding {
        int reserved, loaded, cancelled, closed, destroyed;
        boolean live = true;
        byte[] response;
        final List<byte[]> outputs = new ArrayList<>();
        Runnable during;
        @Override public long open(long view, byte[] request) { return 31; }
        @Override public long reserveDirect() { reserved++; return 31; }
        @Override public long reserveDetached(boolean catalog) { return 32; }
        @Override public void loadDetached(long handle, byte[] source, int root, long deadline, byte[] request) {
            loaded++; assertNotNull(request); assertEquals(0x43534551, ByteBuffer.wrap(request).getInt());
        }
        @Override public int[] run(long handle) { return new int[5]; }
        @Override public byte[] summary(long handle) { return null; }
        @Override public byte[] directPlan(long handle) {
            byte[] result = response.clone(); outputs.add(result);
            if (during != null) during.run(); return result;
        }
        @Override public void cancel(long handle) { cancelled++; }
        @Override public void close(long handle) { closed++; }
        @Override public void destroy(long handle) { destroyed++; }
        void clean() { for (byte[] output : outputs) assertArrayEquals(new byte[output.length], output); }
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private static byte[] frame(String user, String path) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        for (int value : new int[] {0x43534350, 1, 2222, 1, 1, 0, 3000, 0, 3, user.isEmpty() ? 1 : 0, 1, 1}) out.writeInt(value);
        text(out, "actual-alias"); text(out, "actual.example"); text(out, user);
        text(out, path); text(out, ".ssh/known_hosts"); return bytes.toByteArray();
    }
    private static byte[] source() {
        byte[] path = ".ssh/config".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(36 + path.length).putInt(0x43534449).putInt(1).putInt(1)
                .putInt(10).putInt(1024).putInt(100).putInt(1).putInt(path.length).put(path).putInt(0).array();
    }
    private static SshConfigEvaluator.Request request() throws IOException {
        return new SshConfigEvaluator.Request("actual-alias", "", "", "actual-local", "local.example", "", "", 0, 1000);
    }
    private static SshConfigEvaluator prepare(Binding binding) throws IOException {
        return SshConfigEvaluator.prepareDirectDetached(() -> {
            if (!binding.live) throw new IOException("original guard expired");
        }, binding);
    }
    private static void rejected(Work work) throws Exception {
        try { work.run(); fail("Expected direct plan refusal"); } catch (IOException expected) { }
    }
    @Test public void directProjectionRetainsActualTupleAndManagedPathsWithoutNativePointer() throws Exception {
        Binding binding = new Binding(); binding.response = frame("remote", ".ssh/id_ed25519");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            rejected(evaluator::directPlan);
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            SshConfigEvaluator.DirectPlan plan = evaluator.directPlan();
            assertEquals("actual-alias", plan.alias); assertEquals("actual.example", plan.hostname);
            assertEquals("remote", plan.user); assertEquals(2222, plan.port);
            assertEquals(List.of(".ssh/id_ed25519"), plan.identityFiles);
            assertEquals(List.of(".ssh/known_hosts"), plan.userKnownHostsFiles);
            assertTrue(plan.identitiesOnly); assertTrue(plan.pubkeyAuthentication); assertFalse(plan.passwordAuthentication);
            assertFalse(plan.userRequired); assertEquals(3000, plan.connectTimeoutMillis);
            assertEquals(0, plan.requestTty); assertEquals(3, plan.strictHostKeyChecking);
            try { plan.identityFiles.add("other"); fail(); } catch (UnsupportedOperationException expected) { }
            assertEquals(1, binding.reserved); assertEquals(1, binding.loaded); binding.clean();
        }
        assertEquals(1, binding.closed); assertEquals(1, binding.destroyed);
    }
    @Test public void malformedCountsFlagsPathsAndTrailingDataRejectWithWireCleanup() throws Exception {
        byte[] good = frame("remote", ".ssh/id");
        List<byte[]> invalid = new ArrayList<>();
        int[][] changes = {{0, 0}, {4, 2}, {8, 0}, {8, 65536}, {12, 2}, {16, 2}, {20, -1},
                {24, 0}, {24, 3001}, {24, 61000}, {28, 1}, {32, 0}, {36, 1}, {40, -1}, {40, 257}, {44, 0}, {44, 33}};
        for (int[] change : changes) {
            byte[] value = good.clone(); ByteBuffer.wrap(value).putInt(change[0], change[1]); invalid.add(value);
        }
        invalid.add(frame("remote", "../escape")); invalid.add(frame("remote", "/home/owner/key"));
        invalid.add(frame("remote", "a//b")); invalid.add(Arrays.copyOf(good, good.length + 1));
        invalid.add(Arrays.copyOf(good, 59)); invalid.add(new byte[SshConfigEvaluator.MAX_DIRECT_PLAN_BYTES + 1]);
        for (byte[] value : invalid) {
            Binding binding = new Binding(); binding.response = value;
            try (SshConfigEvaluator evaluator = prepare(binding)) {
                evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
                rejected(evaluator::directPlan); binding.clean();
            }
        }
    }
    @Test public void noImplicitLocalUsernameAndOriginalOwnerGuardRemainRequired() throws Exception {
        Binding binding = new Binding(); binding.response = frame("", ".ssh/id");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run();
            assertTrue(evaluator.directPlan().userRequired); assertEquals("", evaluator.directPlan().user);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread other = new Thread(() -> { try { evaluator.directPlan(); } catch (Throwable failure) { error.set(failure); } });
            other.start(); other.join(); assertTrue(error.get() instanceof IOException);
            binding.during = () -> binding.live = false;
            rejected(evaluator::directPlan); binding.clean();
        }
        Binding ordinary = new Binding(); ordinary.response = frame("remote", ".ssh/id");
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetached(() -> { }, ordinary, false)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run(); rejected(evaluator::directPlan);
            assertEquals(0, ordinary.outputs.size());
        }
    }
    @Test public void gettersAfterCancelAndMissingActualRequestCannotAcquireAuthority() throws Exception {
        Binding binding = new Binding(); binding.response = frame("remote", ".ssh/id");
        try (SshConfigEvaluator evaluator = prepare(binding)) {
            rejected(() -> evaluator.loadDetached(source(), 0, 31000, null));
            assertEquals(0, binding.loaded); rejected(evaluator::directPlan);
        }
        Binding cancelled = new Binding(); cancelled.response = binding.response;
        try (SshConfigEvaluator evaluator = prepare(cancelled)) {
            evaluator.loadDetached(source(), 0, 31000, request()); evaluator.run(); evaluator.cancel();
            rejected(evaluator::directPlan); assertEquals(0, cancelled.outputs.size());
        }
    }
}
