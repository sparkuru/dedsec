package im.majo.ctos;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Test;

/** Separately opted-in supplied configuration JNI probe; no vault, account or socket. */
public final class SshManifestConfigInspectorPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshManifestConfigInspectorPlatformTest#syntheticSuppliedConfigInspection";

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id, String body) {
        return new VaultManifest.FileObject(type, id, "ssh/" + id, id,
                body.getBytes(StandardCharsets.UTF_8), VaultCollections.list());
    }

    @Test public void syntheticSuppliedConfigInspection() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshManifestConfigInspectorPlatformProbe");
        assertTrue("Strict supplied config JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped supplied config JNI execution required", "true".equals(optIn));
        assertTrue("Only the named supplied config JNI method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        VaultManifest.FileObject key = file(VaultManifest.FileType.PUBLIC_KEY, "key", "unused metadata only");
        VaultManifest.FileObject root = file(VaultManifest.FileType.CONFIG, "config", "Host supplied\nInclude child\n");
        VaultManifest.FileObject child = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "child", ".ssh/child", "child", "Host child\n".getBytes(StandardCharsets.UTF_8),
                VaultCollections.list());
        VaultManifest.FileObject orphan = file(VaultManifest.FileType.CONFIG, "orphan", "Host orphan\nInclude %h.conf\n");
        byte[] original = root.content.clone();
        // The retained job remains available to retry close even when inspect throws.
        SshManifestConfigInspector job = new SshManifestConfigInspector();
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(),
                VaultCollections.list(key, root, child, orphan))) {
            try {
                SshManifestConfigInspector.Report report = job.inspect(manifest,
                        SystemClock.elapsedRealtime() + 30_000L, new SshManifestConfigInspector.Cancellation());
                assertTrue(report.declarationsOnly);
                assertFalse(report.admissionComplete);
                assertEquals(3, report.processedRootCount);
                assertEquals(2, report.roots.get(0).object);
                assertEquals("supplied", report.roots.get(0).catalog.entries.get(0).alias);
                assertEquals("child", report.roots.get(0).catalog.entries.get(1).alias);
                assertEquals(3, report.roots.get(0).catalog.entries.get(1).object);
                assertEquals(4, report.roots.get(2).object);
                assertTrue(report.roots.get(2).catalog.includesIncomplete);
                assertTrue(report.usage.opens >= 3);
                assertFalse(job.hasPendingCleanup());
                assertArrayEquals(original, root.content);
            } finally { job.close(); }
        } finally { java.util.Arrays.fill(original, (byte)0); }
    }
}
