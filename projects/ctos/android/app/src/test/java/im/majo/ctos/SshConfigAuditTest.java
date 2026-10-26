package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Independently assembled CSCA vectors; native parser behavior has its own real matrix. */
public final class SshConfigAuditTest {
    interface Action { void run() throws Exception; }
    interface Hook { void at(String stage) throws IOException; }
    static class Binding implements SshConfigEvaluator.NativeBinding {
        int reserves, catalogs, audits, loads, closes, destroys, cancels, root;
        int closeFailures, destroyFailures;
        byte[] response;
        int[] diagnostic = new int[5], counters = {1, 1, 1};
        Hook hook = stage -> { };
        final List<byte[]> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        final List<int[]> allowances = new ArrayList<>();
        final List<Integer> roots = new ArrayList<>();
        @Override public long open(long view, byte[] request) { throw new AssertionError("No live owner"); }
        @Override public long reserveAudit() throws IOException {
            reserves++;
            hook.at("reserve");
            return reserves;
        }
        @Override public long reserveDetached(boolean catalog) throws IOException {
            assertTrue(catalog);
            catalogs++;
            hook.at("reserve");
            return catalogs;
        }
        @Override public void loadDetached(long token, byte[] source, int root, long deadline,
                byte[] request) throws IOException {
            assertNull(request);
            assertEquals(31_000, deadline);
            this.root = root;
            roots.add(root);
            loads++;
            inputs.add(source);
            ByteBuffer header = ByteBuffer.wrap(source);
            allowances.add(new int[]{header.getInt(12), header.getInt(16), header.getInt(20)});
            hook.at("load");
        }
        @Override public int[] run(long token) throws IOException { hook.at("run"); return diagnostic.clone(); }
        @Override public byte[] summary(long token) { throw new AssertionError("No effective result"); }
        @Override public byte[] catalog(long token) throws IOException {
            hook.at("catalog");
            byte[] wire = catalogFrame(root + 1, 1, 1, 0, 1);
            outputs.add(wire);
            return wire;
        }
        @Override public byte[] audit(long token) throws IOException {
            audits++;
            hook.at("audit");
            byte[] wire = response == null
                    ? auditFrame(catalogFrame(root + 1, 1, 1, 0, 1)) : response.clone();
            outputs.add(wire);
            return wire;
        }
        @Override public int[] usage(long token) throws IOException { hook.at("usage"); return counters.clone(); }
        @Override public void cancel(long token) throws IOException { cancels++; hook.at("cancel"); }
        @Override public void close(long token) throws IOException {
            closes++;
            hook.at("close");
            if (closeFailures-- > 0) throw new IOException("close details");
        }
        @Override public void destroy(long token) throws IOException {
            destroys++;
            hook.at("destroy");
            if (destroyFailures-- > 0) throw new IOException("destroy details");
        }
    }

    static byte[] catalogFrame(int object, int scanned, int aliases, int deferred, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(24 + aliases * (12 + length) + deferred * 8);
        buffer.putInt(0x43534344).putInt(1).putInt(deferred == 0 ? 1 : 9)
                .putInt(scanned).putInt(aliases).putInt(deferred);
        for (int i = 0; i < aliases; i++) {
            byte[] alias = new byte[length];
            Arrays.fill(alias, (byte)'a');
            byte[] number = Integer.toString(i).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(number, 0, alias, 0, Math.min(number.length, length));
            buffer.putInt(object).putInt(i + 1).putInt(length).put(alias);
        }
        for (int i = 0; i < deferred; i++) buffer.putInt(object).putInt(i + 1);
        return buffer.array();
    }

    static byte[] auditFrame(byte[] catalog, int... edges) {
        if (edges.length % 3 != 0) throw new AssertionError("triples required");
        ByteBuffer buffer = ByteBuffer.allocate(20 + catalog.length + 4 * edges.length);
        buffer.putInt(0x43534341).putInt(1).putInt(1).putInt(catalog.length)
                .putInt(edges.length / 3).put(catalog);
        for (int value : edges) buffer.putInt(value);
        return buffer.array();
    }

    static void rejected(Action action) throws Exception {
        try { action.run(); fail("expected rejection"); } catch (IOException expected) { }
    }

    static void wiped(Binding binding) {
        for (byte[] wire : binding.inputs) assertArrayEquals(new byte[wire.length], wire);
        for (byte[] wire : binding.outputs) assertArrayEquals(new byte[wire.length], wire);
    }

    private static byte[] source() {
        return ByteBuffer.allocate(37).putInt(0x43534449).putInt(1).putInt(1)
                .putInt(1024).putInt(67108864).putInt(16384)
                .putInt(1).putInt(1).put((byte)'c').putInt(0).array();
    }

    private static SshConfigEvaluator ready(Binding binding) throws IOException {
        SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(() -> { }, binding);
        evaluator.loadDetached(source(), 0, 31_000, null);
        evaluator.run();
        return evaluator;
    }

    private static SshConfigEvaluator.Audit decode(byte[] wire, int bytes, int rows) throws Exception {
        Binding binding = new Binding();
        binding.response = wire;
        try (SshConfigEvaluator evaluator = ready(binding)) { return evaluator.audit(bytes, rows); }
        finally {
            for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
        }
    }

    @Test public void nestedCatalogAndOrderedImmutableEdgesPreserveExactMetadata() throws Exception {
        byte[] catalog = catalogFrame(4, 3, 1, 1, 3);
        ByteBuffer.wrap(catalog).putInt(8, 15);
        byte[] wire = auditFrame(catalog, 4, 19, 2, 2, 7, 3);
        SshConfigEvaluator.Audit result = decode(wire, wire.length, 4);
        assertTrue(result.localEffectsPolicyChecked);
        assertEquals(wire.length, result.encodedBytes);
        assertEquals(catalog.length, result.catalog.encodedBytes);
        assertTrue(result.catalog.hasMatch);
        assertTrue(result.catalog.omittedSelectors);
        assertTrue(result.catalog.includesIncomplete);
        assertEquals(4, result.catalog.entries.get(0).object);
        assertEquals(19, result.edges.get(0).line);
        assertEquals(2, result.edges.get(1).parent);
        assertEquals(3, result.edges.get(1).child);
        try { result.edges.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { result.catalog.entries.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void rejectsEveryOuterHeaderAndExactLengthViolation() throws Exception {
        byte[] valid = auditFrame(catalogFrame(1, 1, 0, 0, 1));
        for (int[] edit : new int[][]{{0, 0}, {4, 2}, {8, 0}, {8, 3}, {12, -1},
                {12, 23}, {12, 70425}, {16, -1}, {16, 1025}, {16, Integer.MAX_VALUE}}) {
            byte[] wire = valid.clone();
            ByteBuffer.wrap(wire).putInt(edit[0], edit[1]);
            rejected(() -> decode(wire, 82732, 4096));
        }
        for (byte[] wire : Arrays.asList(new byte[0], new byte[43], new byte[82733],
                Arrays.copyOf(valid, valid.length - 1), Arrays.copyOf(valid, valid.length + 1)))
            rejected(() -> decode(wire, 82732, 4096));
    }

    @Test public void rejectsNestedHeaderLengthsUtf8DuplicateNamesAndTrailingBytes() throws Exception {
        byte[] valid = catalogFrame(1, 1, 1, 0, 2);
        for (int[] edit : new int[][]{{0, 0}, {4, 2}, {8, 0}, {8, 16}, {12, 0}, {12, 129},
                {16, -1}, {16, 257}, {20, -1}, {20, 257}, {24, 0}, {28, 0}, {32, 256}}) {
            byte[] nested = valid.clone();
            ByteBuffer.wrap(nested).putInt(edit[0], edit[1]);
            rejected(() -> decode(auditFrame(nested), 82732, 4096));
        }
        byte[] invalidUtf8 = valid.clone(); invalidUtf8[36] = (byte)0xff;
        rejected(() -> decode(auditFrame(invalidUtf8), 82732, 4096));
        byte[] controls = valid.clone(); controls[36] = 0;
        rejected(() -> decode(auditFrame(controls), 82732, 4096));
        byte[] duplicate = catalogFrame(1, 1, 2, 0, 2);
        System.arraycopy(duplicate, 36, duplicate, 50, 2);
        rejected(() -> decode(auditFrame(duplicate), 82732, 4096));
        rejected(() -> decode(auditFrame(Arrays.copyOf(valid, valid.length + 1)), 82732, 4096));
        rejected(() -> decode(auditFrame(Arrays.copyOf(valid, valid.length - 1)), 82732, 4096));
    }

    @Test public void rejectsBadEdgeOrdinalsLinesSelfAndDuplicateTriples() throws Exception {
        byte[] catalog = catalogFrame(1, 1, 0, 0, 1);
        for (int[] edge : new int[][]{{0, 1, 2}, {129, 1, 2}, {1, 1, 0}, {1, 1, 129},
                {1, 0, 2}, {1, 16385, 2}, {1, 1, 1}})
            rejected(() -> decode(auditFrame(catalog, edge), 82732, 4096));
        rejected(() -> decode(auditFrame(catalog, 1, 1, 2, 1, 1, 2), 82732, 4096));
        assertEquals(2, decode(auditFrame(catalog, 1, 1, 2, 1, 2, 2), 82732, 4096).edges.size());
    }

    @Test public void exactByteAndRowQuotaIncludesEdgesBeforeNestedDecode() throws Exception {
        byte[] wire = auditFrame(catalogFrame(1, 2, 2, 1, 2), 1, 1, 2);
        assertEquals(1, decode(wire, wire.length, 4).edges.size());
        rejected(() -> decode(wire, wire.length - 1, 4));
        rejected(() -> decode(wire, wire.length, 3));
        rejected(() -> decode(wire, wire.length, 0));
        rejected(() -> decode(wire, wire.length, -1));
        rejected(() -> decode(wire, -1, 4096));
    }

    @Test public void exactProtocolMaximumAndEdgeCapacityRemainBounded() throws Exception {
        int[] edges = new int[3072];
        for (int i = 0; i < 1024; i++) {
            edges[i * 3] = 1; edges[i * 3 + 1] = i + 1; edges[i * 3 + 2] = 128;
        }
        byte[] wire = auditFrame(catalogFrame(1, 128, 256, 256, 255), edges);
        assertEquals(82732, wire.length);
        SshConfigEvaluator.Audit result = decode(wire, 82732, 1536);
        assertEquals(1024, result.edges.size());
        rejected(() -> decode(wire, 82732, 1535));
    }

    @Test public void auditIsExplicitAndOldBindingsFailClosed() throws Exception {
        SshConfigEvaluator.NativeBinding old = new SshConfigEvaluator.NativeBinding() {
            @Override public long open(long view, byte[] request) { return 1; }
            @Override public int[] run(long handle) { return new int[5]; }
            @Override public byte[] summary(long handle) { return null; }
            @Override public void cancel(long handle) { }
            @Override public void close(long handle) { }
            @Override public void destroy(long handle) { }
        };
        rejected(() -> SshConfigEvaluator.prepareDetachedAudit(() -> { }, old));
        rejected(() -> old.audit(1));
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = ready(binding)) {
            rejected(evaluator::catalog);
            rejected(evaluator::summary);
            assertEquals(0, binding.audits);
            evaluator.audit(82732, 4096);
        }
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetached(() -> { }, binding, true)) {
            evaluator.loadDetached(source(), 0, 31_000, null);
            evaluator.run();
            rejected(() -> evaluator.audit(82732, 4096));
            evaluator.catalog();
        }
        assertEquals(1, binding.reserves);
        assertEquals(1, binding.catalogs);
        assertEquals(1, binding.audits);
    }

    @Test public void auditRunAndLoadStatesCannotBeBypassed() throws Exception {
        Binding binding = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(() -> { }, binding)) {
            rejected(evaluator::run);
            rejected(() -> evaluator.audit(82732, 4096));
            evaluator.loadDetached(source(), 0, 31_000, null);
            rejected(() -> evaluator.audit(82732, 4096));
            rejected(() -> evaluator.loadDetached(source(), 0, 31_000, null));
            evaluator.run();
            rejected(evaluator::run);
            evaluator.audit(82732, 4096);
        }
        Binding wrong = new Binding();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(() -> { }, wrong)) {
            SshConfigEvaluator.Request request = new SshConfigEvaluator.Request("actual", "u", "", "l",
                    "localhost", "", "", 0, 1);
            rejected(() -> evaluator.loadDetached(source(), 0, 31_000, request));
            assertEquals(0, wrong.loads);
        }
    }

    @Test public void positiveAuditTokenSurvivesReserveExpiryAndGetterPostFenceWipes() throws Exception {
        boolean[] live = {true};
        Binding binding = new Binding();
        binding.hook = stage -> { if (stage.equals("reserve")) live[0] = false; };
        SshConfigEvaluator.DetachedGuard guard = () -> { if (!live[0]) throw new IOException("expired"); };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(guard, binding)) {
            rejected(() -> evaluator.loadDetached(source(), 0, 31_000, null));
        }
        assertEquals(1, binding.destroys);
        live[0] = true;
        binding.hook = stage -> { if (stage.equals("audit")) live[0] = false; };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(guard, binding)) {
            evaluator.loadDetached(source(), 0, 31_000, null);
            evaluator.run();
            rejected(() -> evaluator.audit(82732, 4096));
        }
        for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
        assertEquals(2, binding.destroys);
    }
}
