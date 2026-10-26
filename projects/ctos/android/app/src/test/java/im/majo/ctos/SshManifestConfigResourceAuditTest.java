package im.majo.ctos;

import static org.junit.Assert.*;
import static im.majo.ctos.SshConfigAuditTest.*;
import static im.majo.ctos.SshConfigResourceAuditTest.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Actual caller-manifest type/direct-reference validation and shared all-root ownership. */
public final class SshManifestConfigResourceAuditTest {
    private static final class Clock implements SshManifestConfigInspector.Clock {
        long time = 1000;
        @Override public long now() { return time; }
    }

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String path, String... refs) {
        return new VaultManifest.FileObject(type, id, path, id,
                (type == VaultManifest.FileType.CONFIG ? "Host " + id + "\n" : "private-or-public-body-" + id)
                        .getBytes(StandardCharsets.UTF_8), Arrays.asList(refs));
    }

    private static VaultManifest.FileObject config(String id, String... refs) {
        return file(VaultManifest.FileType.CONFIG, id, "ssh/" + id, refs);
    }

    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }

    private static SshManifestConfigInspector.Report inspect(VaultManifest supplied, ResourceBinding binding)
            throws IOException {
        try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            return job.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation());
        }
    }

    @Test public void candidateAllOrphanRootsKeepTypedBindingsAndOnlyConfigBodiesInOneSource() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> {
            if (!stage.equals("resourceAudit")) return;
            if (binding.root == 4) binding.resourceResponse = resourceFrame(
                    auditFrame(catalogFrame(5, 2, 1, 0, 3), 5,2,6),
                    1,5,3,1,1,1, 1,5,4,1,1,2, 1,5,5,1,1,3,
                    2,5,6,1,1,3, 3,5,7,1,1,4, 4,5,8,1,1,4, 5,5,9,1,1,2);
            else if (binding.root == 5) binding.resourceResponse = resourceFrame(
                    auditFrame(catalogFrame(6, 1, 1, 0, 3)), 1,6,4,1,1,1);
            else binding.resourceResponse = resourceFrame(
                    auditFrame(catalogFrame(7, 1, 1, 0, 3)), 2,7,2,1,3,0);
        };
        VaultManifest.FileObject key = file(VaultManifest.FileType.PRIVATE_KEY, "key", "keys/key");
        byte[] original = key.content.clone();
        try (VaultManifest unrelated = manifest(config("unrelated"));
                VaultManifest supplied = manifest(key,
                        file(VaultManifest.FileType.PUBLIC_KEY, "public", "keys/public"),
                        file(VaultManifest.FileType.CERTIFICATE, "cert", "keys/cert"),
                        file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "trust/hosts"),
                        config("candidate", "ssh/child", "keys/key", "keys/public", "keys/cert", "trust/hosts"),
                        config("child", "keys/key"), config("orphan"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertEquals(Arrays.asList(4, 5, 6), binding.roots);
            assertEquals(3, report.configFiles);
            assertEquals(3, report.processedRootCount);
            assertTrue(report.staticIncludeReferencesVerified);
            assertTrue(report.localEffectsPolicyChecked);
            assertTrue(report.staticResourceReferencesVerified);
            assertTrue(report.resourcesIncomplete);
            assertTrue(report.declarationsOnly);
            assertFalse(report.admissionComplete);
            SshManifestConfigInspector.Root first = report.roots.get(0);
            assertEquals(5, first.object);
            assertEquals("candidate", first.id);
            assertSame(first.audit, first.resourceAudit.audit);
            assertSame(first.catalog, first.audit.catalog);
            assertEquals(7, first.resourceAudit.resources.size());
            assertEquals(1, first.resourceAudit.resources.get(0).target);
            assertFalse(first.resourceAudit.hasDeferred);
            assertFalse(report.roots.get(2).catalog.includesIncomplete);
            assertTrue(report.roots.get(2).resourceAudit.hasDeferred);
            int bytes = 0;
            for (SshManifestConfigInspector.Root root : report.roots) bytes += root.resourceAudit.encodedBytes;
            assertEquals(bytes, report.catalogBytes);
            assertEquals(0, binding.reserves);
            assertEquals(0, binding.audits);
            assertEquals(0, binding.catalogs);
            assertEquals(3, binding.resourceReserves);
            assertEquals(3, binding.resourceGets);
            assertEquals(3, binding.destroys);
            assertArrayEquals(original, key.content);
            for (byte[] input : binding.inputs) {
                assertArrayEquals(new byte[input.length], input);
            }
            for (byte[] output : binding.outputs) assertArrayEquals(new byte[output.length], output);
            try { report.roots.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        }
    }

    @Test public void loadLoanHasEveryMetadataOrdinalAndNoNonConfigBody() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> {
            if (!stage.equals("load")) return;
            ByteBuffer wire = ByteBuffer.wrap(binding.inputs.get(binding.inputs.size() - 1));
            assertEquals(0x43534449, wire.getInt()); assertEquals(1, wire.getInt());
            assertEquals(3, wire.getInt()); wire.position(24);
            for (int type : new int[]{2, 1, 5}) {
                assertEquals(type, wire.getInt()); int pathBytes = wire.getInt();
                wire.position(wire.position() + pathBytes); int bodyBytes = wire.getInt();
                if (type == 1) assertTrue(bodyBytes > 0); else assertEquals(0, bodyBytes);
                wire.position(wire.position() + bodyBytes);
            }
            assertFalse(wire.hasRemaining());
        };
        try (VaultManifest supplied = manifest(file(VaultManifest.FileType.PRIVATE_KEY, "key", "key"),
                config("root"), file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "hosts"))) {
            inspect(supplied, binding);
            wiped(binding);
        }
    }

    @Test public void staticResourceMustBeInItsActualParentsDirectReferences() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 2, 1, 0, 1), 1,1,2),
                1,2,8,1,1,3);
        try (VaultManifest supplied = manifest(config("root", "ssh/parent", "keys/key"),
                config("parent"), file(VaultManifest.FileType.PUBLIC_KEY, "key", "keys/key"),
                config("sibling", "keys/key"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            try { job.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
            catch (SshManifestConfigInspector.ResourceReferenceException expected) {
                assertEquals(1, expected.kind); assertEquals(2, expected.parent);
                assertEquals(8, expected.line); assertEquals(1, expected.argument); assertEquals(3, expected.target);
                assertEquals("SSH configuration static resource reference missing", expected.getMessage());
                assertFalse(expected.getMessage().contains("keys/"));
            }
            assertFalse(job.hasPendingCleanup());
            assertEquals(1, binding.resourceGets);
            assertEquals(1, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void sameIdentityAndSuffixSiblingDoNotAuthorizeAnotherExactTarget() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 1, 1, 0, 1)), 1,1,2,1,1,3);
        VaultManifest.FileObject sibling = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                "sibling", "keys/key.pub", "key", new byte[]{1}, VaultCollections.list());
        try (VaultManifest supplied = manifest(config("root", "keys/key"),
                file(VaultManifest.FileType.PRIVATE_KEY, "key", "keys/key"), sibling)) {
            try { inspect(supplied, binding); fail(); }
            catch (SshManifestConfigInspector.ResourceReferenceException expected) { assertEquals(3, expected.target); }
            wiped(binding);
        }
    }

    @Test public void rejectsWrongTypeAndForgedParentTargetOrNestedCatalogSources() throws Exception {
        for (int[] row : new int[][]{ {2,1,1,1,1,3}, {3,1,1,1,1,3}, {5,1,1,1,1,4},
                {1,1,1,1,1,4}, {1,1,1,1,1,2}, {1,3,1,1,3,0}, {1,5,1,1,3,0}, {1,1,1,1,1,5} }) {
            ResourceBinding binding = new ResourceBinding();
            binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 2, 1, 0, 1)), row);
            try (VaultManifest supplied = manifest(config("root", "ssh/child", "keys/key", "trust/hosts"),
                    config("child"), file(VaultManifest.FileType.PUBLIC_KEY, "key", "keys/key"),
                    file(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "trust/hosts"))) {
                rejected(() -> inspect(supplied, binding));
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
        for (byte[] nested : Arrays.asList(auditFrame(catalogFrame(3, 2, 1, 0, 1)),
                auditFrame(catalogFrame(1, 3, 1, 0, 1)), auditFrame(catalogFrame(1, 2, 1, 0, 1), 1,1,3))) {
            ResourceBinding binding = new ResourceBinding(); binding.resourceResponse = resourceFrame(nested);
            try (VaultManifest supplied = manifest(config("root", "ssh/child", "keys/key"),
                    config("child"), file(VaultManifest.FileType.PUBLIC_KEY, "key", "keys/key"))) {
                rejected(() -> inspect(supplied, binding));
                wiped(binding);
            }
        }
    }

    @Test public void unresolvedRowsRetainNoTargetAndDoNotClaimMaterialOrAdmission() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 1, 1, 0, 1)),
                1,1,1,1,5,0, 2,1,2,1,3,0, 3,1,3,1,2,0, 4,1,4,1,4,0, 5,1,5,256,3,0);
        try (VaultManifest supplied = manifest(config("root"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertTrue(report.staticResourceReferencesVerified);
            assertTrue(report.resourcesIncomplete);
            assertFalse(report.admissionComplete);
            assertFalse(report.roots.get(0).catalog.includesIncomplete);
            for (SshConfigEvaluator.Resource resource : report.roots.get(0).resourceAudit.resources)
                assertEquals(0, resource.target);
        }
        binding = new ResourceBinding();
        binding.resourceResponse = resourceFrame(auditFrame(catalogFrame(1, 1, 0, 0, 1)),
                1,1,1,1,4,0, 3,1,2,1,4,0, 4,1,3,1,4,0, 5,1,4,1,4,0);
        try (VaultManifest supplied = manifest(config("root"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertFalse(report.resourcesIncomplete);
            assertFalse(report.admissionComplete);
        }
    }

    @Test public void oldInspectAndAuditDoNotAcquireResourcesOrUpgradeFlags() throws Exception {
        for (boolean audit : new boolean[]{false, true}) {
            ResourceBinding binding = new ResourceBinding(); binding.resourceResponse = new byte[]{0};
            try (VaultManifest supplied = manifest(config("legacy"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                SshManifestConfigInspector.Report report = audit
                        ? job.inspectAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation())
                        : job.inspect(supplied, 31_000, new SshManifestConfigInspector.Cancellation());
                assertFalse(report.staticResourceReferencesVerified);
                assertFalse(report.resourcesIncomplete);
                assertNull(report.roots.get(0).resourceAudit);
                assertEquals(0, binding.resourceReserves);
                assertEquals(0, binding.resourceGets);
                rejected(() -> job.inspectResourceAudit(supplied, 31_000,
                        new SshManifestConfigInspector.Cancellation()));
            }
        }
    }

    @Test public void zeroConfigResourceAuditHasNoJniAndFinalLifetimeFenceStillRuns() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        try (VaultManifest empty = VaultManifest.empty()) {
            SshManifestConfigInspector.Report report = inspect(empty, binding);
            assertTrue(report.staticResourceReferencesVerified);
            assertTrue(report.staticIncludeReferencesVerified);
            assertFalse(report.resourcesIncomplete);
            assertFalse(report.admissionComplete);
            assertEquals(0, report.processedRootCount);
            assertEquals(0, binding.resourceReserves);
            for (boolean cancel : new boolean[]{false, true}) {
                int[] checks = {0}; SshManifestConfigInspector.Cancellation cancellation =
                        new SshManifestConfigInspector.Cancellation();
                SshManifestConfigInspector.Clock clock = () -> {
                    if (++checks[0] == 2) { if (cancel) cancellation.cancel(); else return 31_000; }
                    return 1000;
                };
                try (SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
                    rejected(() -> job.inspectResourceAudit(empty, 31_000, cancellation));
                }
            }
        }
    }

    @Test public void wholeEnvelopeRowsCountOnceAndExactAggregateLimitHasNoTruncation() throws Exception {
        for (int count : new int[]{4, 5}) {
            ResourceBinding binding = new ResourceBinding();
            binding.hook = stage -> {
                if (stage.equals("resourceAudit")) binding.resourceResponse = resourceFrame(
                        auditFrame(catalogFrame(binding.root + 1, 1, 1, 0, 1)),
                        repeatedResources(binding.root + 1, binding.root < 4 ? 1023 : 0));
            };
            VaultManifest.FileObject[] roots = new VaultManifest.FileObject[count];
            for (int i = 0; i < count; i++) roots[i] = config("c" + i);
            try (VaultManifest supplied = manifest(roots)) {
                if (count == 4) {
                    SshManifestConfigInspector.Report report = inspect(supplied, binding);
                    assertEquals(4, report.processedRootCount);
                    int bytes = 0;
                    for (SshManifestConfigInspector.Root root : report.roots) bytes += root.resourceAudit.encodedBytes;
                    assertEquals(bytes, report.catalogBytes);
                } else rejected(() -> inspect(supplied, binding));
                assertEquals(count, binding.resourceGets);
                assertEquals(count, binding.destroys);
                wiped(binding);
            }
        }
    }

    @Test public void wholeEnvelopeByteCapFailsBeforeRowsWouldExceed4096() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> {
            if (stage.equals("resourceAudit")) binding.resourceResponse = resourceFrame(
                    auditFrame(catalogFrame(binding.root + 1, 1, 256, 0, 255)));
        };
        VaultManifest.FileObject[] roots = new VaultManifest.FileObject[17];
        for (int i = 0; i < roots.length; i++) roots[i] = config("c" + i);
        try (VaultManifest supplied = manifest(roots)) {
            rejected(() -> inspect(supplied, binding));
            assertEquals(16, binding.resourceGets);
            assertEquals(16, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void nativeWorkRemainingAllowancesCarryAcrossResourceRootsWithoutRenewal() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        binding.hook = stage -> {
            if (stage.equals("usage")) binding.counters = binding.root == 0
                    ? new int[]{1023, 67108863, 16383} : new int[]{1, 1, 1};
        };
        try (VaultManifest supplied = manifest(config("a"), config("b"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertEquals(1024, report.usage.opens);
            assertEquals(67108864, report.usage.readBytes);
            assertEquals(16384, report.usage.lines);
            assertArrayEquals(new int[]{1, 1, 1}, binding.allowances.get(1));
        }
    }

    @Test public void cancelInReserveGapClosesTheIssuedResourceOwnerWithoutLoad() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        binding.hook = stage -> { if (stage.equals("resourceReserve")) cancellation.cancel(); };
        try (VaultManifest supplied = manifest(config("a"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            rejected(() -> job.inspectResourceAudit(supplied, 31_000, cancellation));
            assertEquals(1, binding.resourceReserves); assertEquals(0, binding.loads);
            assertEquals(1, binding.cancels); assertEquals(1, binding.destroys);
            assertFalse(job.hasPendingCleanup());
        }
    }

    @Test public void crossThreadCancelReturnsWhileResourceGetterIsPausedAndNoReportPublishes() throws Exception {
        ResourceBinding binding = new ResourceBinding();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        binding.hook = stage -> {
            if (!stage.equals("resourceAudit")) return;
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("latch timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        };
        try (VaultManifest supplied = manifest(config("a"))) {
            Thread worker = new Thread(() -> {
                try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                    job.inspectResourceAudit(supplied, 31_000, cancellation);
                    result.set(new AssertionError("published cancelled report"));
                } catch (Throwable failure) { result.set(failure); }
            });
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                cancellation.cancel(); assertEquals(1, binding.cancels);
            } finally { release.countDown(); worker.join(5000); }
            assertFalse(worker.isAlive());
            assertTrue(result.get() instanceof IOException);
            assertEquals(1, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void getterErrorWipesInputAndRetainsFailedCleanupUntilExplicitRetry() throws Exception {
        ResourceBinding binding = new ResourceBinding(); binding.closeFailures = 1;
        AssertionError error = new AssertionError("getter failed");
        binding.hook = stage -> { if (stage.equals("resourceAudit")) throw error; };
        try (VaultManifest supplied = manifest(config("a"))) {
            SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding);
            try { job.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
            catch (AssertionError expected) { assertSame(error, expected); assertEquals(1, expected.getSuppressed().length); }
            assertTrue(job.hasPendingCleanup());
            wiped(binding);
            binding.hook = stage -> { }; job.close();
            assertFalse(job.hasPendingCleanup()); assertEquals(2, binding.closes); assertEquals(1, binding.destroys);
        }
    }

    @Test public void lastCleanupExpiryAndSameThreadReentryCannotBypassPublication() throws Exception {
        ResourceBinding binding = new ResourceBinding(); Clock clock = new Clock();
        binding.hook = stage -> { if (stage.equals("destroy")) clock.time = 31_000; };
        try (VaultManifest supplied = manifest(config("a"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
            rejected(() -> job.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()));
            assertEquals(1, binding.destroys); assertFalse(job.hasPendingCleanup()); wiped(binding);
        }
        ResourceBinding reentrant = new ResourceBinding();
        try (VaultManifest supplied = manifest(config("a"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), reentrant);
                SshManifestConfigInspector other = new SshManifestConfigInspector(new Clock(), reentrant)) {
            reentrant.hook = stage -> {
                if (!stage.equals("resourceAudit")) return;
                try { job.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
                catch (IOException expected) { }
                try { other.inspectResourceAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
                catch (IOException expected) { }
            };
            assertEquals(1, job.inspectResourceAudit(supplied, 31_000,
                    new SshManifestConfigInspector.Cancellation()).processedRootCount);
            assertEquals(1, reentrant.resourceReserves);
        }
    }
}
