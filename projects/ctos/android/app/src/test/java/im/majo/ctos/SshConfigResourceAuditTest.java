package im.majo.ctos;

import static org.junit.Assert.*;
import static im.majo.ctos.SshConfigAuditTest.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;

/** Independently framed resource metadata; no native/parser execution is claimed by these vectors. */
public final class SshConfigResourceAuditTest {
    static class ResourceBinding extends Binding {
        int resourceReserves, resourceGets;
        byte[] resourceResponse;
        @Override public long reserveResourceAudit() throws IOException {
            resourceReserves++;
            hook.at("resourceReserve");
            return resourceReserves;
        }
        @Override public byte[] resourceAudit(long token) throws IOException {
            resourceGets++;
            hook.at("resourceAudit");
            byte[] wire = resourceResponse == null
                    ? resourceFrame(auditFrame(catalogFrame(root + 1, 1, 1, 0, 1)))
                    : resourceResponse.clone();
            outputs.add(wire);
            return wire;
        }
    }

    static byte[] resourceFrame(byte[] audit, int... resources) {
        if (resources.length % 6 != 0) throw new AssertionError("resource tuples required");
        ByteBuffer buffer = ByteBuffer.allocate(20 + audit.length + resources.length * 4);
        buffer.putInt(0x43534352).putInt(1).putInt(1).putInt(audit.length)
                .putInt(resources.length / 6).put(audit);
        for (int value : resources) buffer.putInt(value);
        return buffer.array();
    }

    static int[] repeatedResources(int parent, int count) {
        int[] rows = new int[count * 6];
        for (int i = 0; i < count; i++) {
            rows[i * 6] = 5;
            rows[i * 6 + 1] = parent;
            rows[i * 6 + 2] = i + 1;
            rows[i * 6 + 3] = 1;
            rows[i * 6 + 4] = 3;
        }
        return rows;
    }

    private static byte[] source() {
        return ByteBuffer.allocate(37).putInt(0x43534449).putInt(1).putInt(1)
                .putInt(1024).putInt(67108864).putInt(16384)
                .putInt(1).putInt(1).put((byte)'c').putInt(0).array();
    }

    private static SshConfigEvaluator ready(ResourceBinding binding) throws IOException {
        SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, binding);
        evaluator.loadDetached(source(), 0, 31_000, null);
        evaluator.run();
        return evaluator;
    }

    private static SshConfigEvaluator.ResourceAudit decode(byte[] wire, int bytes, int rows) throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = wire;
        try (SshConfigEvaluator evaluator = ready(binding)) { return evaluator.resourceAudit(bytes, rows); }
        finally {
            for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
        }
    }

    @Test public void nestedAuditAndResourcesAreImmutableSingleMetadataObjects() throws Exception {
        byte[] nested = auditFrame(catalogFrame(3, 2, 1, 1, 2), 3, 7, 4);
        byte[] wire = resourceFrame(nested,
                1, 3, 12, 1, 1, 2,
                2, 3, 13, 1, 3, 0,
                3, 3, 14, 1, 4, 0,
                5, 4, 2, 256, 2, 0);
        SshConfigEvaluator.ResourceAudit result = decode(wire, wire.length, 7);
        assertEquals(wire.length, result.encodedBytes);
        assertEquals(nested.length, result.audit.encodedBytes);
        assertEquals(1, result.audit.edges.size());
        assertEquals(3, result.audit.catalog.entries.get(0).object);
        assertTrue(result.audit.catalog.includesIncomplete);
        assertTrue(result.hasDeferred);
        SshConfigEvaluator.Resource first = result.resources.get(0);
        assertEquals(1, first.kind);
        assertEquals(3, first.parent);
        assertEquals(12, first.line);
        assertEquals(1, first.argument);
        assertEquals(2, first.target);
        try { result.resources.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { result.audit.edges.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void rejectsOuterVersionsCountsBoundsAndExactLengthViolations() throws Exception {
        byte[] valid = resourceFrame(auditFrame(catalogFrame(1, 1, 0, 0, 1)));
        for (int[] edit : new int[][]{{0, 0}, {4, 2}, {8, 0}, {8, 3}, {12, -1},
                {12, 43}, {12, 82733}, {16, -1}, {16, 1025}, {16, Integer.MAX_VALUE}}) {
            byte[] wire = valid.clone(); ByteBuffer.wrap(wire).putInt(edit[0], edit[1]);
            rejected(() -> decode(wire, 107328, 4096));
        }
        for (byte[] wire : Arrays.asList(new byte[0], new byte[63], new byte[107329],
                Arrays.copyOf(valid, valid.length - 1), Arrays.copyOf(valid, valid.length + 1)))
            rejected(() -> decode(wire, 107328, 4096));
    }

    @Test public void nestedAuditAndCatalogRemainStrictInsideResourceEnvelope() throws Exception {
        byte[] valid = auditFrame(catalogFrame(1, 1, 1, 0, 2), 1, 1, 2);
        for (int[] edit : new int[][]{{0, 0}, {4, 2}, {8, 0}, {12, 23}, {16, -1},
                {20, 0}, {28, 16}, {32, 0}, {36, 257}}) {
            byte[] nested = valid.clone(); ByteBuffer.wrap(nested).putInt(edit[0], edit[1]);
            rejected(() -> decode(resourceFrame(nested), 107328, 4096));
        }
        byte[] badUtf8 = valid.clone(); badUtf8[56] = (byte)0xff;
        rejected(() -> decode(resourceFrame(badUtf8), 107328, 4096));
        rejected(() -> decode(resourceFrame(Arrays.copyOf(valid, valid.length + 1)), 107328, 4096));
        rejected(() -> decode(resourceFrame(Arrays.copyOf(valid, valid.length - 1)), 107328, 4096));
    }

    @Test public void rejectsInvalidResourceLocatorAndClosedStateCombinations() throws Exception {
        byte[] audit = auditFrame(catalogFrame(1, 1, 0, 0, 1));
        for (int[] row : new int[][]{
                {0,1,1,1,1,2}, {6,1,1,1,1,2}, {1,0,1,1,1,2}, {1,129,1,1,1,2},
                {1,1,0,1,1,2}, {1,1,16385,1,1,2}, {5,1,1,0,3,0}, {5,1,1,257,3,0},
                {1,1,1,2,1,2}, {2,1,1,2,3,0}, {3,1,1,33,3,0}, {4,1,1,33,3,0},
                {1,1,1,1,0,0}, {1,1,1,1,6,0}, {4,1,1,1,2,0}, {2,1,1,1,4,0},
                {3,1,1,2,4,0}, {3,1,1,1,5,0}, {5,1,1,1,5,0}, {1,1,1,1,1,0},
                {1,1,1,1,1,129}, {1,1,1,1,1,1}, {1,1,1,1,3,2}, {1,1,1,1,4,2}})
            rejected(() -> decode(resourceFrame(audit, row), 107328, 4096));
    }

    @Test public void duplicateLocatorRejectsConflictingStateOrTargetAndArgument256DoesNotAliasLine() throws Exception {
        byte[] audit = auditFrame(catalogFrame(1, 1, 0, 0, 1));
        rejected(() -> decode(resourceFrame(audit, 1,1,1,1,1,2, 1,1,1,1,1,2), 107328, 4096));
        rejected(() -> decode(resourceFrame(audit, 1,1,1,1,1,2, 1,1,1,1,3,0), 107328, 4096));
        rejected(() -> decode(resourceFrame(audit, 1,1,1,1,1,2, 1,1,1,1,1,3), 107328, 4096));
        SshConfigEvaluator.ResourceAudit result = decode(resourceFrame(audit,
                5,1,2,256,3,0, 5,1,3,256,3,0, 5,1,2,255,3,0), 107328, 3);
        assertEquals(3, result.resources.size());
        assertEquals(256, result.resources.get(0).argument);
        assertEquals(3, result.resources.get(1).line);
    }

    @Test public void deferredFlagSeparatesContextCwdAndKeyResolutionFromDisabledAndStatic() throws Exception {
        byte[] audit = auditFrame(catalogFrame(1, 1, 0, 0, 1));
        assertFalse(decode(resourceFrame(audit, 1,1,1,1,1,2, 3,1,2,1,4,0), 107328, 2).hasDeferred);
        for (int state : new int[]{2, 3, 5})
            assertTrue(decode(resourceFrame(audit, 1,1,1,1,state,0), 107328, 1).hasDeferred);
        assertTrue(decode(resourceFrame(audit, 2,1,1,1,5,0), 107328, 1).hasDeferred);
        assertFalse(decode(resourceFrame(audit), 64, 0).hasDeferred);
    }

    @Test public void allThreeRowAndByteBudgetsApplyBeforeNestedRetention() throws Exception {
        byte[] wire = resourceFrame(auditFrame(catalogFrame(1, 2, 2, 1, 2), 1,1,2),
                1,1,4,1,1,3, 2,1,5,1,5,0);
        assertEquals(2, decode(wire, wire.length, 6).resources.size());
        rejected(() -> decode(wire, wire.length, 5));
        rejected(() -> decode(wire, wire.length - 1, 6));
        rejected(() -> decode(wire, -1, 6));
        rejected(() -> decode(wire, wire.length, -1));
        rejected(() -> decode(wire, wire.length, 1));
    }

    @Test public void exactMaximumResourceCapacityAndEnvelopeAreAccepted() throws Exception {
        int[] edges = new int[3072];
        for (int i = 0; i < 1024; i++) {
            edges[i * 3] = 1; edges[i * 3 + 1] = i + 1; edges[i * 3 + 2] = 128;
        }
        byte[] wire = resourceFrame(auditFrame(catalogFrame(1, 128, 256, 256, 255), edges),
                repeatedResources(1, 1024));
        assertEquals(107328, wire.length);
        assertEquals(1024, decode(wire, 107328, 2560).resources.size());
        rejected(() -> decode(wire, 107328, 2559));
    }

    @Test public void resourceModeIsExplicitAndEveryOldModeGetterRemainsIsolated() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        try (SshConfigEvaluator evaluator = ready(binding)) {
            rejected(evaluator::catalog);
            rejected(evaluator::summary);
            rejected(() -> evaluator.audit(82732, 4096));
            assertEquals(0, binding.audits);
            evaluator.resourceAudit(107328, 4096);
        }
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedAudit(() -> { }, binding)) {
            evaluator.loadDetached(source(), 0, 31_000, null); evaluator.run();
            rejected(() -> evaluator.resourceAudit(107328, 4096));
            evaluator.audit(82732, 4096);
        }
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetached(() -> { }, binding, true)) {
            evaluator.loadDetached(source(), 0, 31_000, null); evaluator.run();
            rejected(() -> evaluator.resourceAudit(107328, 4096));
            evaluator.catalog();
        }
        assertEquals(1, binding.resourceReserves);
        assertEquals(1, binding.resourceGets);
        assertEquals(1, binding.reserves);
        assertEquals(1, binding.audits);
    }

    @Test public void originalBindingDefaultsFailClosedWithoutReservingAnOldMode() throws Exception {
        Binding old = new Binding();
        rejected(() -> SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, old));
        rejected(() -> old.resourceAudit(1));
        assertEquals(0, old.reserves);
        assertEquals(0, old.catalogs);
    }

    @Test public void resourceRunLoadModeAndCallerInputOwnershipRemainStrict() throws Exception {
        ResourceBinding binding = new ResourceBinding(); byte[] input = source(), original = input.clone();
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, binding)) {
            rejected(evaluator::run);
            rejected(() -> evaluator.resourceAudit(107328, 4096));
            evaluator.loadDetached(input, 0, 31_000, null);
            rejected(() -> evaluator.resourceAudit(107328, 4096));
            rejected(() -> evaluator.loadDetached(input, 0, 31_000, null));
            evaluator.run();
            rejected(evaluator::run);
            evaluator.resourceAudit(107328, 4096);
        }
        assertArrayEquals(original, input);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, binding)) {
            SshConfigEvaluator.Request request = new SshConfigEvaluator.Request("actual", "u", "", "l",
                    "localhost", "", "", 0, 1);
            rejected(() -> evaluator.loadDetached(source(), 0, 31_000, request));
        }
        assertEquals(1, binding.loads);
    }

    @Test public void issuedResourceOwnerSurvivesReserveFailureFenceAndGetterInvalidationWipes() throws Exception {
        boolean[] live = {true}; ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> { if (stage.equals("resourceReserve")) live[0] = false; };
        SshConfigEvaluator.DetachedGuard guard = () -> { if (!live[0]) throw new IOException("expired"); };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(guard, binding)) {
            rejected(() -> evaluator.loadDetached(source(), 0, 31_000, null));
        }
        assertEquals(1, binding.destroys);
        live[0] = true;
        binding.hook = stage -> { if (stage.equals("resourceAudit")) live[0] = false; };
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(guard, binding)) {
            evaluator.loadDetached(source(), 0, 31_000, null); evaluator.run();
            rejected(() -> evaluator.resourceAudit(107328, 4096));
        }
        for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
        assertEquals(2, binding.destroys);
    }
}
