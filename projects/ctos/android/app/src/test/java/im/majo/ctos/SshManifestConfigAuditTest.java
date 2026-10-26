package im.majo.ctos;

import static org.junit.Assert.*;
import static im.majo.ctos.SshConfigAuditTest.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Actual supplied-manifest reference and all-root orchestration, without native runtime claims. */
public final class SshManifestConfigAuditTest {
    private static final class Clock implements SshManifestConfigInspector.Clock {
        long time = 1000;
        @Override public long now() { return time; }
    }

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String path, String... references) {
        return new VaultManifest.FileObject(type, id, path, id,
                ("Host " + id + "\n").getBytes(StandardCharsets.UTF_8), Arrays.asList(references));
    }

    private static VaultManifest.FileObject config(String id, String... refs) {
        return file(VaultManifest.FileType.CONFIG, id, "ssh/" + id, refs);
    }

    private static VaultManifest manifest(VaultManifest.FileObject... files) throws Exception {
        return new VaultManifest(VaultCollections.list(), Arrays.asList(files));
    }

    private static SshManifestConfigInspector.Report inspect(VaultManifest manifest, Binding binding)
            throws IOException {
        try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            return job.inspectAudit(manifest, 31_000, new SshManifestConfigInspector.Cancellation());
        }
    }

    @Test public void actualCandidateAllAndOrphanRootsKeepOrdinalsAndOneNestedCatalog() throws Exception {
        Binding binding = new Binding();
        binding.hook = stage -> {
            if (!stage.equals("audit")) return;
            binding.response = binding.root == 1
                    ? auditFrame(catalogFrame(2, 2, 1, 0, 3), 2, 9, 3)
                    : auditFrame(catalogFrame(binding.root + 1, 1, 1, 1, 3));
        };
        VaultManifest.FileObject parent = config("candidate", "ssh/child", "ssh/key");
        byte[] original = parent.content.clone();
        try (VaultManifest unrelated = manifest(config("source"));
                VaultManifest supplied = manifest(
                        file(VaultManifest.FileType.PUBLIC_KEY, "key", "ssh/key"),
                        parent, config("child"), config("orphan"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertTrue(report.staticIncludeReferencesVerified);
            assertTrue(report.localEffectsPolicyChecked);
            assertTrue(report.declarationsOnly);
            assertFalse(report.admissionComplete);
            assertEquals(3, report.configFiles);
            assertEquals(Arrays.asList(1, 2, 3), binding.roots);
            assertEquals(2, report.roots.get(0).object);
            assertEquals("candidate", report.roots.get(0).id);
            assertEquals("orphan", report.roots.get(2).id);
            assertSame(report.roots.get(0).catalog, report.roots.get(0).audit.catalog);
            assertEquals(3, report.roots.get(0).audit.edges.get(0).child);
            assertTrue(report.roots.get(2).catalog.includesIncomplete);
            int bytes = 0;
            for (SshManifestConfigInspector.Root root : report.roots) bytes += root.audit.encodedBytes;
            assertEquals(bytes, report.catalogBytes);
            assertEquals(3, report.usage.opens);
            assertArrayEquals(original, parent.content);
            assertEquals(0, binding.catalogs);
            assertEquals(3, binding.audits);
            assertEquals(3, binding.destroys);
            try { report.roots.clear(); fail(); } catch (UnsupportedOperationException expected) { }
            wiped(binding);
        }
    }

    @Test public void actualNestedParentMustDirectlyAuthorizeChildEvenIfRootOrSiblingDoes() throws Exception {
        for (boolean rootHasChild : new boolean[]{false, true}) {
            Binding binding = new Binding();
            binding.response = auditFrame(catalogFrame(1, 3, 1, 0, 1), 1, 4, 2, 2, 17, 3);
            VaultManifest.FileObject root = rootHasChild
                    ? config("root", "ssh/parent", "ssh/child") : config("root", "ssh/parent");
            try (VaultManifest supplied = manifest(root, config("parent"), config("child"),
                    config("sibling", "ssh/child"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                try {
                    job.inspectAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation());
                    fail();
                } catch (SshManifestConfigInspector.ReferenceException expected) {
                    assertEquals(2, expected.parent);
                    assertEquals(17, expected.line);
                    assertEquals(3, expected.child);
                    assertEquals("SSH configuration static Include reference missing", expected.getMessage());
                    assertFalse(expected.getMessage().contains("ssh/"));
                }
                assertEquals(1, binding.audits);
                assertFalse(job.hasPendingCleanup());
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
    }

    @Test public void sameIdentityOrFilenameDoesNotAuthorizeDifferentPath() throws Exception {
        Binding binding = new Binding();
        binding.response = auditFrame(catalogFrame(1, 2, 1, 0, 1), 1, 6, 3);
        VaultManifest.FileObject first = file(VaultManifest.FileType.CONFIG, "a", "one/child");
        VaultManifest.FileObject second = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "b", "two/child", "a", new byte[0], VaultCollections.list());
        try (VaultManifest supplied = manifest(config("root", "one/child"), first, second)) {
            try { inspect(supplied, binding); fail(); }
            catch (SshManifestConfigInspector.ReferenceException expected) {
                assertEquals(3, expected.child);
            }
            wiped(binding);
        }
    }

    @Test public void rejectsForgedNonConfigAndOutOfRangeEdgeOrCatalogSources() throws Exception {
        List<byte[]> frames = new ArrayList<>();
        frames.add(auditFrame(catalogFrame(1, 2, 1, 0, 1), 1, 1, 3));
        frames.add(auditFrame(catalogFrame(1, 2, 1, 0, 1), 3, 1, 2));
        frames.add(auditFrame(catalogFrame(1, 2, 1, 0, 1), 1, 1, 4));
        frames.add(auditFrame(catalogFrame(3, 2, 1, 0, 1)));
        frames.add(auditFrame(catalogFrame(4, 2, 1, 0, 1)));
        frames.add(auditFrame(catalogFrame(1, 3, 1, 0, 1)));
        byte[] site = catalogFrame(3, 2, 0, 1, 1);
        frames.add(auditFrame(site));
        for (byte[] frame : frames) {
            Binding binding = new Binding(); binding.response = frame;
            try (VaultManifest supplied = manifest(config("root", "ssh/child", "ssh/key"),
                    config("child"), file(VaultManifest.FileType.PUBLIC_KEY, "key", "ssh/key"))) {
                rejected(() -> inspect(supplied, binding));
                assertEquals(1, binding.destroys);
                wiped(binding);
            }
        }
    }

    @Test public void legacyInspectDoesNotPerformAuditOrClaimPolicyOrReferences() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest supplied = manifest(config("legacy"));
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
            SshManifestConfigInspector.Report report = job.inspect(supplied, 31_000,
                    new SshManifestConfigInspector.Cancellation());
            assertFalse(report.staticIncludeReferencesVerified);
            assertFalse(report.localEffectsPolicyChecked);
            assertNull(report.roots.get(0).audit);
            assertEquals(1, binding.catalogs);
            assertEquals(0, binding.reserves);
            assertEquals(0, binding.audits);
            rejected(() -> job.inspectAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()));
        }
    }

    @Test public void noConfigAuditIsVacuousWithNoJniAndSameFinalFence() throws Exception {
        Binding binding = new Binding();
        try (VaultManifest supplied = VaultManifest.empty()) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertTrue(report.staticIncludeReferencesVerified);
            assertTrue(report.localEffectsPolicyChecked);
            assertFalse(report.admissionComplete);
            assertEquals(0, report.processedRootCount);
            assertEquals(0, report.catalogBytes);
            assertEquals(0, binding.reserves);
        }
        for (boolean cancel : new boolean[]{false, true}) {
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            int[] checks = {0};
            SshManifestConfigInspector.Clock clock = () -> {
                if (++checks[0] == 2) {
                    if (cancel) cancellation.cancel(); else return 31_000;
                }
                return 1000;
            };
            try (VaultManifest supplied = VaultManifest.empty();
                    SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
                rejected(() -> job.inspectAudit(supplied, 31_000, cancellation));
                assertEquals(0, binding.reserves);
            }
        }
    }

    @Test public void cumulativeNativeUsageUsesOriginalRemainingJobBudgets() throws Exception {
        Binding binding = new Binding(); binding.counters = new int[]{2, 5, 3};
        try (VaultManifest supplied = manifest(config("one"), config("two"), config("three"))) {
            SshManifestConfigInspector.Report report = inspect(supplied, binding);
            assertArrayEquals(new int[]{1024, 67108864, 16384}, binding.allowances.get(0));
            assertArrayEquals(new int[]{1022, 67108859, 16381}, binding.allowances.get(1));
            assertArrayEquals(new int[]{1020, 67108854, 16378}, binding.allowances.get(2));
            assertEquals(6, report.usage.opens);
            assertEquals(15, report.usage.readBytes);
            assertEquals(9, report.usage.lines);
            wiped(binding);
        }
    }

    @Test public void edgesCountInCumulativeRowsAndWholeCscaIsChargedOnce() throws Exception {
        Binding binding = new Binding();
        int[] edges = new int[3072];
        for (int i = 0; i < 1024; i++) {
            edges[i * 3] = 1; edges[i * 3 + 1] = i + 1; edges[i * 3 + 2] = 2;
        }
        binding.response = auditFrame(catalogFrame(1, 2, 1, 0, 1), edges);
        try (VaultManifest supplied = manifest(config("root", "ssh/child"), config("child"),
                config("orphan1"), config("orphan2"), config("orphan3"))) {
            rejected(() -> inspect(supplied, binding));
            assertEquals(4, binding.audits);
            assertEquals(4, binding.destroys);
            wiped(binding);
        }
        Binding byteBudget = new Binding();
        byteBudget.response = auditFrame(catalogFrame(1, 1, 256, 0, 255));
        List<VaultManifest.FileObject> roots = new ArrayList<>();
        for (int i = 0; i < 17; i++) roots.add(config("root" + i));
        try (VaultManifest supplied = new VaultManifest(VaultCollections.list(), roots)) {
            // Sixteen catalogs fit the row cap exactly; the whole-frame byte cap rejects first.
            rejected(() -> inspect(supplied, byteBudget));
            assertEquals(16, byteBudget.audits);
            wiped(byteBudget);
        }
    }

    @Test public void nativePolicyFailureKeepsOriginalNumericDiagnosticAndNoPartialReport() throws Exception {
        Binding binding = new Binding();
        binding.diagnostic = new int[]{1, 4, 1, 7, 22};
        try (VaultManifest supplied = manifest(config("root"))) {
            try { inspect(supplied, binding); fail(); }
            catch (SshConfigEvaluator.EvaluationException expected) {
                assertEquals(4, expected.diagnostic.reason);
                assertEquals(1, expected.diagnostic.object);
                assertEquals(7, expected.diagnostic.line);
            }
            assertEquals(0, binding.audits);
            assertEquals(1, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void cancellationAtEveryStageIncludingReserveAndFinalCleanupDeniesAudit() throws Exception {
        for (String selected : Arrays.asList("reserve", "load", "run", "usage", "audit", "close", "destroy")) {
            Binding binding = new Binding();
            SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
            binding.hook = stage -> { if (stage.equals(selected)) cancellation.cancel(); };
            try (VaultManifest supplied = manifest(config("root"));
                    SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                rejected(() -> job.inspectAudit(supplied, 31_000, cancellation));
                assertEquals(1, binding.destroys);
                assertFalse(job.hasPendingCleanup());
                if (selected.equals("reserve")) assertEquals(0, binding.loads);
                wiped(binding);
            }
        }
    }

    @Test public void pausedAuditGetterAllowsPromptCrossThreadCancellation() throws Exception {
        Binding binding = new Binding();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        binding.hook = stage -> {
            if (!stage.equals("audit")) return;
            entered.countDown();
            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException failure) { throw new IOException("interrupted"); }
        };
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        AtomicReference<Throwable> result = new AtomicReference<>();
        try (VaultManifest supplied = manifest(config("root"))) {
            Thread worker = new Thread(() -> {
                try (SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding)) {
                    job.inspectAudit(supplied, 31_000, cancellation);
                    result.set(new AssertionError("cancelled report"));
                } catch (IOException expected) { }
                catch (Throwable failure) { result.set(failure); }
            });
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread canceller = new Thread(cancellation::cancel);
            canceller.start(); canceller.join(1000);
            boolean prompt = !canceller.isAlive();
            resume.countDown(); worker.join(5000); canceller.join(5000);
            assertTrue(prompt);
            assertFalse(worker.isAlive());
            assertNull(result.get());
            assertEquals(1, binding.destroys);
            wiped(binding);
        }
    }

    @Test public void errorsInAuditAndFailedCleanupRetainExactOwnerForExplicitRetry() throws Exception {
        for (String stage : Arrays.asList("load", "run", "usage", "audit")) {
            Binding binding = new Binding();
            binding.closeFailures = 1;
            binding.hook = selected -> { if (selected.equals(stage)) throw new AssertionError("primary"); };
            try (VaultManifest supplied = manifest(config("root"))) {
                SshManifestConfigInspector job = new SshManifestConfigInspector(new Clock(), binding);
                try { job.inspectAudit(supplied, 31_000, new SshManifestConfigInspector.Cancellation()); fail(); }
                catch (AssertionError expected) {
                    assertEquals("primary", expected.getMessage());
                    assertEquals(1, expected.getSuppressed().length);
                }
                assertTrue(job.hasPendingCleanup());
                assertEquals(0, binding.destroys);
                wiped(binding);
                job.close();
                assertFalse(job.hasPendingCleanup());
                assertEquals(2, binding.closes);
                assertEquals(1, binding.destroys);
            }
        }
    }

    @Test public void missingReferenceWithFailedDestroyRetainsExactOwnerAndWipesCopies() throws Exception {
        Binding binding = new Binding(); binding.destroyFailures = 1;
        binding.response = auditFrame(catalogFrame(1, 2, 1, 0, 1), 1, 23, 2);
        Clock clock = new Clock();
        SshManifestConfigInspector.Cancellation cancellation = new SshManifestConfigInspector.Cancellation();
        try (VaultManifest supplied = manifest(config("parent"), config("child"))) {
            SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding);
            try { job.inspectAudit(supplied, 31_000, cancellation); fail(); }
            catch (SshManifestConfigInspector.ReferenceException expected) {
                assertEquals(23, expected.line);
                assertEquals(1, expected.getSuppressed().length);
            }
            assertTrue(job.hasPendingCleanup());
            wiped(binding);
            clock.time = 99_000; cancellation.cancel();
            job.close();
            assertEquals(1, binding.closes);
            assertEquals(2, binding.destroys);
            assertFalse(job.hasPendingCleanup());
        }
    }

    @Test public void finalGetterAndCleanupDeadlineAreStillPartOfPublication() throws Exception {
        for (String selected : Arrays.asList("audit", "close", "destroy")) {
            Clock clock = new Clock(); Binding binding = new Binding();
            binding.hook = stage -> { if (stage.equals(selected)) clock.time = 31_000; };
            try (VaultManifest supplied = manifest(config("root"))) {
                try (SshManifestConfigInspector job = new SshManifestConfigInspector(clock, binding)) {
                    rejected(() -> job.inspectAudit(supplied, 31_000,
                            new SshManifestConfigInspector.Cancellation()));
                    assertFalse(job.hasPendingCleanup());
                    assertEquals(1, binding.destroys);
                    wiped(binding);
                }
            }
        }
    }
}
