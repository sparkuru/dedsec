package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

/** Two-phase owner facade tests; native frame validation is covered by the native matrix. */
public final class SshDetachedConfigEvaluatorTest {
    private interface Action { void run() throws Exception; }
    private static class OldBinding implements SshConfigEvaluator.NativeBinding {
        int closes, destroys;
        @Override public long open(long view, byte[] request) { return 91; }
        @Override public int[] run(long handle) { return new int[5]; }
        @Override public byte[] summary(long handle) { return null; }
        @Override public void cancel(long handle) { }
        @Override public void close(long handle) throws IOException { closes++; }
        @Override public void destroy(long handle) throws IOException { destroys++; }
    }
    private static final class Binding extends OldBinding {
        boolean catalog;
        int loads, reserves, cancellations;
        boolean guardAlive = true, failLoad, failDestroy;
        byte[] request;
        @Override public long reserveDetached(boolean mode) {
            reserves++;
            catalog = mode;
            return 91;
        }
        @Override public void loadDetached(long handle, byte[] source, int root, long deadline,
                byte[] request) throws IOException {
            assertEquals(91, handle);
            loads++;
            this.request = request;
            if (request != null) assertEquals(0x43534551, ByteBuffer.wrap(request).getInt());
            if (failLoad) throw new AssertionError("load failure");
        }
        @Override public int[] usage(long handle) { return new int[]{1, 0, 0}; }
        @Override public void cancel(long handle) { cancellations++; }
        @Override public void destroy(long handle) throws IOException {
            super.destroy(handle);
            if (failDestroy) throw new IOException("destroy");
        }
    }
    private static byte[] source(int opens, int read, int lines) {
        byte[] path = "ssh/config".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(36 + path.length).putInt(0x43534449).putInt(1).putInt(1)
                .putInt(opens).putInt(read).putInt(lines).putInt(1).putInt(path.length)
                .put(path).putInt(0).array();
    }
    private static SshConfigEvaluator prepare(Binding binding, boolean catalog) throws IOException {
        return SshConfigEvaluator.prepareDetached(() -> {
            if (!binding.guardAlive) throw new IOException("expired");
        }, binding, catalog);
    }
    private static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected rejection"); } catch (IOException expected) { }
    }
    private static SshConfigEvaluator.Request request() throws IOException {
        return new SshConfigEvaluator.Request("actual", "remote", "", "local", "local.example",
                "", "", 0, 1000);
    }

    @Test public void oldBindingsRefuseDetachedReservationByDefault() throws Exception {
        OldBinding binding = new OldBinding();
        rejected(() -> SshConfigEvaluator.prepareDetached(() -> { }, binding, true));
        assertEquals(0, binding.closes);
        assertEquals(0, binding.destroys);
    }

    @Test public void reservedOwnerMustLoadExactlyOnceAndGettersRequireRun() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = prepare(binding, true)) {
            rejected(evaluator::run);
            rejected(evaluator::catalog);
            rejected(evaluator::usage);
            evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null);
            rejected(() -> evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null));
            evaluator.run();
            assertEquals(1, evaluator.usage().opens);
            rejected(evaluator::run);
            rejected(evaluator::summary);
        }
        assertEquals(1, binding.reserves);
        assertEquals(1, binding.loads);
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void failedLoadStillConsumesAttemptAndKeepsCleanupOwnerAcrossError() throws Exception {
        Binding binding = new Binding();
        binding.failLoad = true;
        try (SshConfigEvaluator evaluator = prepare(binding, true)) {
            try { evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null); fail(); }
            catch (AssertionError expected) { assertEquals("load failure", expected.getMessage()); }
            rejected(evaluator::run);
            rejected(() -> evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null));
        }
        assertEquals(1, binding.loads);
        assertEquals(1, binding.destroys);
    }

    @Test public void ordinaryModeRequiresActualRequestAndWipesItsWireOnError() throws Exception {
        Binding binding = new Binding();
        binding.failLoad = true;
        try (SshConfigEvaluator evaluator = prepare(binding, false)) {
            try { evaluator.loadDetached(source(1, 0, 0), 0, 31_000, request()); fail(); }
            catch (AssertionError expected) { assertEquals("load failure", expected.getMessage()); }
            assertFalse(binding.catalog);
            assertNotNull(binding.request);
            assertArrayEquals(new byte[binding.request.length], binding.request);
        }
        for (boolean catalog : new boolean[]{false, true}) {
            Binding wrong = new Binding();
            try (SshConfigEvaluator evaluator = prepare(wrong, catalog)) {
                rejected(() -> evaluator.loadDetached(source(1, 0, 0), 0, 31_000,
                        catalog ? request() : null));
                assertEquals(0, wrong.loads);
            }
        }
    }

    @Test public void badHeaderAndOriginalRemainderLimitsFailBeforeNativeLoad() throws Exception {
        byte[] valid = source(1, 0, 0);
        int[][] edits = {{0, 0}, {4, 2}, {8, 0}, {8, 129}, {12, -1}, {12, 1025},
                {16, -1}, {16, 67108865}, {20, -1}, {20, 16385}};
        for (int[] edit : edits) {
            Binding binding = new Binding();
            byte[] wire = valid.clone();
            ByteBuffer.wrap(wire).putInt(edit[0], edit[1]);
            try (SshConfigEvaluator evaluator = prepare(binding, true)) {
                rejected(() -> evaluator.loadDetached(wire, 0, 31_000, null));
                assertEquals(0, binding.loads);
            }
        }
        for (byte[] wire : Arrays.asList(new byte[0], new byte[23], new byte[8455705])) {
            Binding binding = new Binding();
            try (SshConfigEvaluator evaluator = prepare(binding, true)) {
                rejected(() -> evaluator.loadDetached(wire, 0, 31_000, null));
                assertEquals(0, binding.loads);
            }
        }
        for (int root : new int[]{-1, 1}) {
            Binding binding = new Binding();
            try (SshConfigEvaluator evaluator = prepare(binding, true)) {
                rejected(() -> evaluator.loadDetached(valid, root, 31_000, null));
                assertEquals(0, binding.loads);
            }
        }
    }

    @Test public void positiveReservationHasNoFailingPostcheckAndCanAlwaysBeClosed() throws Exception {
        boolean[] live = {true};
        OldBinding binding = new OldBinding() {
            @Override public long reserveDetached(boolean catalog) {
                live[0] = false;
                return 91;
            }
        };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetached(() -> {
            if (!live[0]) throw new IOException("expired during reserve");
        }, binding, true)) {
            rejected(() -> evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null));
        }
        assertEquals(1, binding.closes);
        assertEquals(1, binding.destroys);
    }

    @Test public void expiryOrCancellationNeverBlocksRetryableCleanup() throws Exception {
        Binding binding = new Binding();
        SshConfigEvaluator evaluator = prepare(binding, true);
        evaluator.loadDetached(source(1, 0, 0), 0, 31_000, null);
        binding.guardAlive = false;
        rejected(evaluator::run);
        evaluator.cancel();
        binding.failDestroy = true;
        rejected(evaluator::close);
        assertEquals(1, binding.closes);
        binding.failDestroy = false;
        evaluator.close();
        assertEquals(1, binding.closes);
        assertEquals(2, binding.destroys);
    }
}
