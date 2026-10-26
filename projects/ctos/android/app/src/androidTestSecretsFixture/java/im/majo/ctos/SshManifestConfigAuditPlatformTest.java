package im.majo.ctos;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Test;

/** Explicitly opted-in synthetic public CONFIG audit JNI probe; no account, vault or network. */
public final class SshManifestConfigAuditPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshManifestConfigAuditPlatformTest#syntheticSuppliedConfigAudit";

    private static VaultManifest.FileObject config(String id, String path, String body, String... refs) {
        return new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, id, path, id,
                body.getBytes(StandardCharsets.UTF_8), java.util.Arrays.asList(refs));
    }

    @Test public void syntheticSuppliedConfigAudit() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshManifestConfigAuditPlatformProbe");
        assertTrue("Strict supplied config audit JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped supplied config audit JNI execution required", "true".equals(optIn));
        assertTrue("Only the named config audit JNI method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY,
                "key", "ssh/key", "key", "unused metadata".getBytes(StandardCharsets.UTF_8),
                VaultCollections.list());
        VaultManifest.FileObject root = config("root", "ssh/config",
                "Host supplied\nStrictHostKeyChecking ask\nInclude child\n", ".ssh/child", "ssh/key");
        VaultManifest.FileObject child = config("child", ".ssh/child",
                "Host child\nProxyCommand none\nMatch host exec\nForwardAgent no\n");
        VaultManifest.FileObject orphan = config("orphan", "ssh/orphan",
                "Host orphan\nInclude %h.conf\n");
        byte[] original = root.content.clone();
        SshManifestConfigInspector job = new SshManifestConfigInspector();
        try (VaultManifest supplied = new VaultManifest(VaultCollections.list(),
                VaultCollections.list(key, root, child, orphan))) {
            try {
                SshManifestConfigInspector.Report report = job.inspectAudit(supplied,
                        SystemClock.elapsedRealtime() + 30_000L, new SshManifestConfigInspector.Cancellation());
                assertTrue(report.staticIncludeReferencesVerified);
                assertTrue(report.localEffectsPolicyChecked);
                assertTrue(report.declarationsOnly);
                assertFalse(report.admissionComplete);
                assertEquals(3, report.processedRootCount);
                SshManifestConfigInspector.Root first = report.roots.get(0);
                assertEquals(2, first.object);
                assertSame(first.catalog, first.audit.catalog);
                assertEquals(1, first.audit.edges.size());
                SshConfigEvaluator.Edge edge = first.audit.edges.get(0);
                assertEquals(2, edge.parent);
                assertEquals(3, edge.line);
                assertEquals(3, edge.child);
                assertEquals("supplied", first.catalog.entries.get(0).alias);
                assertEquals("child", first.catalog.entries.get(1).alias);
                assertTrue(report.roots.get(2).catalog.includesIncomplete);
                assertFalse(job.hasPendingCleanup());
                assertArrayEquals(original, root.content);
            } finally { job.close(); }
        } finally { java.util.Arrays.fill(original, (byte)0); }
    }
}
