package im.majo.ctos;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Test;

/** Exact opt-in resource-metadata JNI probe; synthetic bodies are not key/trust validation. */
public final class SshManifestConfigResourceAuditPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshManifestConfigResourceAuditPlatformTest#syntheticSuppliedResourceAudit";

    private static VaultManifest.FileObject file(VaultManifest.FileType type, String id,
            String path, String body, String... refs) {
        return new VaultManifest.FileObject(type, id, path, id,
                body.getBytes(StandardCharsets.UTF_8), java.util.Arrays.asList(refs));
    }

    @Test public void syntheticSuppliedResourceAudit() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshManifestConfigResourceAuditPlatformProbe");
        assertTrue("Strict supplied resource audit JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped resource audit JNI execution required", "true".equals(optIn));
        assertTrue("Only the named resource audit JNI method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        VaultManifest.FileObject key = file(VaultManifest.FileType.PRIVATE_KEY,
                "key", "keys/key", "not copied or parsed");
        VaultManifest.FileObject publicKey = file(VaultManifest.FileType.PUBLIC_KEY,
                "public", "keys/pub", "not copied or parsed");
        VaultManifest.FileObject certificate = file(VaultManifest.FileType.CERTIFICATE,
                "cert", "keys/cert", "not copied or parsed");
        VaultManifest.FileObject hosts = file(VaultManifest.FileType.KNOWN_HOSTS,
                "hosts", "trust/$literal", "not copied or parsed");
        VaultManifest.FileObject root = file(VaultManifest.FileType.CONFIG, "root", "ssh/config",
                "Host supplied\nStrictHostKeyChecking ask\nInclude child\n"
                + "IdentityFile ~/keys/key\nCertificateFile ~/keys/cert\n"
                + "UserKnownHostsFile ~/trust/$literal\nGlobalKnownHostsFile ~/trust/$literal\n"
                + "RevokedHostKeys ~/keys/pub\nIdentityFile none\n",
                ".ssh/child", "keys/key", "keys/pub", "keys/cert", "trust/$literal");
        VaultManifest.FileObject child = file(VaultManifest.FileType.CONFIG, "child", ".ssh/child",
                "Host child\nIdentityFile ~/keys/pub\nGlobalKnownHostsFile ~/trust/$literal\n",
                "keys/pub", "trust/$literal");
        VaultManifest.FileObject orphan = file(VaultManifest.FileType.CONFIG, "orphan", "ssh/orphan",
                "Host orphan\nIdentityFile missing-base\nCertificateFile none\n"
                + "RevokedHostKeys none\nUserKnownHostsFile ~/trust/${UNKNOWN}\nIdentityFile ~/keys/missing\n");
        byte[] original = root.content.clone();
        SshManifestConfigInspector job = new SshManifestConfigInspector();
        try (VaultManifest supplied = new VaultManifest(VaultCollections.list(),
                VaultCollections.list(key, publicKey, certificate, hosts, root, child, orphan))) {
            try {
                SshManifestConfigInspector.Report report = job.inspectResourceAudit(supplied,
                        SystemClock.elapsedRealtime() + 30_000L, new SshManifestConfigInspector.Cancellation());
                assertTrue(report.staticResourceReferencesVerified);
                assertTrue(report.staticIncludeReferencesVerified);
                assertTrue(report.localEffectsPolicyChecked);
                assertTrue(report.resourcesIncomplete);
                assertFalse(report.admissionComplete);
                assertTrue(report.declarationsOnly);
                assertEquals(3, report.processedRootCount);
                SshManifestConfigInspector.Root first = report.roots.get(0);
                assertEquals(5, first.object);
                assertSame(first.audit, first.resourceAudit.audit);
                assertSame(first.catalog, first.audit.catalog);
                assertEquals(8, first.resourceAudit.resources.size());
                assertEquals(6, first.resourceAudit.resources.get(0).parent);
                assertEquals(2, first.resourceAudit.resources.get(0).target);
                assertEquals(5, first.audit.edges.get(0).parent);
                assertEquals(3, first.audit.edges.get(0).line);
                assertEquals(6, first.audit.edges.get(0).child);
                assertFalse(first.resourceAudit.hasDeferred);
                SshConfigEvaluator.ResourceAudit unresolved = report.roots.get(2).resourceAudit;
                assertTrue(unresolved.hasDeferred);
                assertEquals(5, unresolved.resources.size());
                assertEquals(SshConfigEvaluator.Resource.DEFERRED_CWD, unresolved.resources.get(0).state);
                assertEquals(SshConfigEvaluator.Resource.DEFERRED_CWD, unresolved.resources.get(1).state);
                assertEquals(SshConfigEvaluator.Resource.DISABLED, unresolved.resources.get(2).state);
                assertEquals(SshConfigEvaluator.Resource.DEFERRED_CONTEXT, unresolved.resources.get(3).state);
                assertEquals(SshConfigEvaluator.Resource.DEFERRED_KEY_RESOLUTION, unresolved.resources.get(4).state);
                assertArrayEquals(original, root.content);
                assertFalse(job.hasPendingCleanup());
            } finally { job.close(); }
        } finally { java.util.Arrays.fill(original, (byte)0); }
    }
}
