package im.majo.ctos;

import static org.junit.Assert.*;
import static im.majo.ctos.SshConfigAuditTest.*;
import static im.majo.ctos.SshConfigResourceAuditTest.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Independently framed native metadata exercises policy/derivation; this does not execute the parser. */
public final class SshKeyDeletionConfigTest {
    private static VaultManifest.FileObject config(String path, String... refs) {
        return new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshConnectionMaterial.id("config", path),
                path, SshConnectionMaterial.id("config-owner", path), "Host test\n".getBytes(StandardCharsets.US_ASCII),
                List.of(refs));
    }
    private static SshConfigEvaluator.ResourceAudit audit(VaultManifest manifest, int... row) throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 1, 1, 0, 1)), row);
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, binding)) {
            byte[] input = SshConnectionMaterial.detached(manifest);
            try { evaluator.loadDetached(input, 0, 31000, null); }
            finally { VaultCodec.wipe(input); }
            evaluator.run(); return evaluator.resourceAudit(1024 * 1024, 4096);
        }
    }
    private interface Work { void run() throws Exception; }
    private static void dependency(Work work) throws Exception {
        try { work.run(); fail("Expected missing/deferred dependency refusal"); }
        catch (VaultException failure) { assertEquals("SSH_CONFIG_DEPENDENCY", failure.code); }
    }
    @Test public void onlyStoredMissingIdentityIsRepresentableWhileConfigImportRemainsStrict() throws Exception {
        try (VaultManifest manifest = new VaultManifest(List.of(),
                List.of(config(SshConnectionMaterial.ROOT, SshConnectionMaterial.HOSTS), SshConnectionMaterial.emptyHosts()))) {
            SshConfigEvaluator.ResourceAudit missingIdentity = audit(manifest, 1,1,2,1,5,0);
            assertEquals(List.of(SshConnectionMaterial.HOSTS), SshConnectionMaterial.storedReferences(manifest, 1, missingIdentity));
            dependency(() -> SshConnectionMaterial.references(manifest, 1, missingIdentity));
            for (int[] row : new int[][]{{2,1,2,1,5,0}, {1,1,2,1,2,0}, {1,1,2,1,3,0},
                    {2,1,2,1,3,0}, {3,1,2,1,2,0}, {3,1,2,1,3,0}, {4,1,2,1,3,0}, {5,1,2,1,3,0}}) {
                SshConfigEvaluator.ResourceAudit unresolved = audit(manifest, row);
                dependency(() -> SshConnectionMaterial.storedReferences(manifest, 1, unresolved));
            }
            SshConfigEvaluator.ResourceAudit disabled = audit(manifest, 1,1,2,1,4,0);
            assertEquals(List.of(SshConnectionMaterial.HOSTS), SshConnectionMaterial.references(manifest, 1, disabled));
        }
    }

    @Test public void prospectiveAuditDerivesExactActualEdgesWithoutClaimingPersistedAdmission() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> {
            if (!stage.equals("resourceAudit")) return;
            binding.resourceResponse = binding.root == 0
                    ? resourceFrame(auditFrame(catalogFrame(1, 2, 1, 0, 2), 1,1,2), 1,2,2,1,1,3)
                    : resourceFrame(auditFrame(catalogFrame(2, 1, 1, 0, 1)), 1,2,2,1,1,3);
        };
        try (VaultManifest supplied = new VaultManifest(List.of(), List.of(
                config(SshConnectionMaterial.ROOT), config(".ssh/child"),
                new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "key", ".ssh/key", "key", new byte[]{1}, List.of()),
                SshConnectionMaterial.emptyHosts()));
                SshManifestConfigInspector inspector = new SshManifestConfigInspector(() -> 1000, binding)) {
            SshManifestConfigInspector.Report report = inspector.inspectResourceDeclarationsForReferenceRefresh(
                    supplied, 31000, new SshManifestConfigInspector.Cancellation());
            assertFalse(report.admissionComplete); assertTrue(report.declarationsOnly);
            assertFalse(report.staticIncludeReferencesVerified); assertFalse(report.staticResourceReferencesVerified);
            assertTrue(report.localEffectsPolicyChecked); assertEquals(2, report.processedRootCount);
            assertEquals(List.of(SshConnectionMaterial.HOSTS, ".ssh/child"),
                    SshConnectionMaterial.storedReferences(supplied, 1, report.roots.get(0).resourceAudit));
            assertEquals(List.of(SshConnectionMaterial.HOSTS, ".ssh/key"),
                    SshConnectionMaterial.storedReferences(supplied, 2, report.roots.get(1).resourceAudit));
            assertTrue(supplied.files.get(0).references.isEmpty()); assertTrue(supplied.files.get(1).references.isEmpty());
            assertEquals(2, binding.destroys);
        }
    }

    @Test public void strictConfigImportRejectsMissingIdentityInsideRetainedInclude() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 2, 1, 0, 2), 1,1,2), 1,2,2,1,5,0);
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(
                config(SshConnectionMaterial.ROOT, SshConnectionMaterial.HOSTS, ".ssh/child"),
                config(".ssh/child", SshConnectionMaterial.HOSTS), SshConnectionMaterial.emptyHosts()));
                SshConfigEvaluator evaluator = SshConfigEvaluator.prepareDetachedResourceAudit(() -> { }, binding)) {
            byte[] input = SshConnectionMaterial.detached(manifest);
            try { evaluator.loadDetached(input, 0, 31000, null); }
            finally { VaultCodec.wipe(input); }
            evaluator.run();
            SshConfigEvaluator.ResourceAudit audit = evaluator.resourceAudit(1024 * 1024, 4096);
            assertEquals(List.of(SshConnectionMaterial.HOSTS, ".ssh/child"),
                    SshConnectionMaterial.storedReferences(manifest, 1, audit));
            assertEquals(List.of(SshConnectionMaterial.HOSTS), SshConnectionMaterial.storedReferences(manifest, 2, audit));
            dependency(() -> SshConnectionMaterial.references(manifest, 1, audit));
        }
    }

    @Test public void prospectiveModeStillRejectsWrongNativeTypesAndNonConfigIncludeSources() throws Exception {
        for (boolean wrongInclude : List.of(false, true)) {
            ResourceBinding binding = new ResourceBinding();
            binding.resourceResponse = wrongInclude
                    ? resourceFrame(auditFrame(catalogFrame(1, 1, 1, 0, 1), 1,1,2))
                    : resourceFrame(auditFrame(catalogFrame(1, 1, 1, 0, 1)), 3,1,2,1,1,2);
            try (VaultManifest manifest = new VaultManifest(List.of(), List.of(config(SshConnectionMaterial.ROOT),
                    new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "key", ".ssh/key", "key", new byte[]{1}, List.of())));
                    SshManifestConfigInspector inspector = new SshManifestConfigInspector(() -> 1000, binding)) {
                try {
                    inspector.inspectResourceDeclarationsForReferenceRefresh(manifest, 31000,
                            new SshManifestConfigInspector.Cancellation()); fail("Wrong native source/type accepted");
                } catch (IOException expected) { }
                assertFalse(inspector.hasPendingCleanup()); assertEquals(1, binding.destroys);
            }
        }
    }

    @Test public void prospectiveModeRechecksOriginalGuardBeforePublicationAndRetainsCleanup() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(); ResourceBinding binding = new ResourceBinding();
        AtomicInteger refused = new AtomicInteger();
        binding.hook = stage -> { if (stage.equals("resourceAudit")) cancelled.set(true); };
        try (VaultManifest manifest = new VaultManifest(List.of(), List.of(config(SshConnectionMaterial.ROOT)));
                SshManifestConfigInspector inspector = new SshManifestConfigInspector(() -> 1000, binding, () -> {
                    if (cancelled.get()) {
                        refused.incrementAndGet();
                        throw new VaultException("SSH_CANCELLED", "Original request cancelled");
                    }
                })) {
            try {
                inspector.inspectResourceDeclarationsForReferenceRefresh(manifest, 31000,
                        new SshManifestConfigInspector.Cancellation()); fail("Cancelled derivation published");
            } catch (IOException expected) { assertTrue(cancelled.get()); assertTrue(refused.get() > 0); }
            assertFalse(inspector.hasPendingCleanup()); assertEquals(1, binding.destroys);
            for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
        }
    }
}
