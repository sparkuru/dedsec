package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in synthetic lease/parser JNI gate. No sockets, account or persistent vault. */
public final class SshConfigEvaluatorPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshConfigEvaluatorPlatformTest#syntheticManagedConfigEvaluation";
    private static final String CATALOG_METHOD =
            "im.majo.ctos.SshConfigEvaluatorPlatformTest#syntheticManagedConfigCatalog";

    private static SshPublicFileLease view(String included) throws Exception {
        return view("Include parts/*.conf\n", included);
    }

    private static SshPublicFileLease view(String rootText, String included) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 73,
                new byte[16], new byte[32], new byte[0]);
        try {
            VaultOperation operation = new VaultOperation(VaultOperation.Consumer.SSH,
                    VaultOperation.Purpose.SSH_FILES, "ssh/config", "", "config-jni-probe",
                    envelope, 7, deadline);
            VaultManifest.FileObject root = new VaultManifest.FileObject(
                    VaultManifest.FileType.CONFIG, "root", "ssh/config", "config",
                    rootText.getBytes(StandardCharsets.UTF_8),
                    VaultCollections.list("ssh/parts/10-target.conf"));
            VaultManifest.FileObject child = new VaultManifest.FileObject(
                    VaultManifest.FileType.CONFIG, "child", "ssh/parts/10-target.conf", "child",
                    included.getBytes(StandardCharsets.UTF_8), VaultCollections.list());
            return SshPublicFileLease.create(new SshFileSnapshot(operation,
                    VaultCollections.list(root, child)));
        } finally {
            VaultCodec.wipe(envelope.salt);
            VaultCodec.wipe(envelope.nonce);
            VaultCodec.wipe(envelope.header);
            VaultCodec.wipe(envelope.ciphertext);
        }
    }

    @Test public void syntheticManagedConfigCatalog() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshConfigCatalogPlatformProbe");
        assertTrue("Strict catalog JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped catalog JNI execution required", "true".equals(optIn));
        assertTrue("Only the named catalog JNI method",
                CATALOG_METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        String root = "Host root-before !skip *\nInclude parts/*.conf\nInclude %h.conf\n"
                + "Match exec \"ignored command\"\nHost root-after\n";
        String child = "Host child child-two root-before\nPort invalid\n";
        try (SshPublicFileLease lease = view(root, child)) {
            long originalToken = lease.nativeToken();
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(lease)) {
                evaluator.run();
                SshConfigEvaluator.Catalog catalog = evaluator.catalog();
                assertTrue(catalog.declarationsOnly && catalog.omittedSelectors
                        && catalog.hasMatch && catalog.includesIncomplete);
                assertEquals(2, catalog.scannedFiles);
                assertEquals(4, catalog.entries.size());
                assertEquals("root-before", catalog.entries.get(0).alias);
                assertEquals("child", catalog.entries.get(1).alias);
                assertEquals("child-two", catalog.entries.get(2).alias);
                assertEquals("root-after", catalog.entries.get(3).alias);
                assertEquals(1, catalog.entries.get(0).object);
                assertEquals(1, catalog.entries.get(0).line);
                assertEquals(2, catalog.entries.get(1).object);
                assertEquals(1, catalog.entries.get(1).line);
                assertEquals(1, catalog.entries.get(3).object);
                assertEquals(5, catalog.entries.get(3).line);
                assertEquals(1, catalog.deferredSites.size());
                assertEquals(1, catalog.deferredSites.get(0).object);
                assertEquals(3, catalog.deferredSites.get(0).line);
                try { evaluator.summary(); fail("catalog is not effective configuration"); }
                catch (IOException expected) { }
            }
            assertTrue(lease.active());
            assertEquals(originalToken, lease.nativeToken());
            assertTrue(lease.summary().length > 0);
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.openCatalog(lease)) {
                evaluator.run();
                lease.revoke();
                try { evaluator.catalog(); fail("revoked catalog must fail"); }
                catch (IOException expected) {
                    assertFalse(expected.getMessage().contains("root-before"));
                }
            }
            assertFalse(lease.active());
        }
    }

    private static SshConfigEvaluator.Request request() throws IOException {
        return new SshConfigEvaluator.Request("config-probe", "", "", "virtual",
                "local.example", "", "", 0, 1000);
    }

    @Test public void syntheticManagedConfigEvaluation() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshConfigEvaluatorPlatformProbe");
        assertTrue("Strict config JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped config JNI execution required", "true".equals(optIn));
        assertTrue("Only the named config JNI method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        String included = "Host config-probe\n HostName 主机.example\n User 用户\ud83d\ude80\n Port 2345\n";
        try (SshPublicFileLease lease = view(included)) {
            long originalToken = lease.nativeToken();
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(lease, request())) {
                evaluator.run();
                SshConfigEvaluator.Summary summary = evaluator.summary();
                assertEquals("config-probe", summary.alias);
                assertEquals("主机.example", summary.hostname);
                assertEquals("用户\ud83d\ude80", summary.user);
                assertEquals(2345, summary.port);
                assertFalse(summary.userRequired);
                assertTrue(summary.hasRawFields);
            }
            // Closing an evaluator releases only its borrow; the caller lease remains useful.
            assertTrue(lease.active());
            assertEquals(originalToken, lease.nativeToken());
            assertTrue(lease.summary().length > 0);
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(lease, request())) {
                evaluator.run();
                lease.revoke();
                try { evaluator.summary(); fail("revoked summary must fail"); }
                catch (IOException expected) {
                    assertFalse(expected.getMessage().contains("主机"));
                }
            }
            assertFalse(lease.active());
        }

        try (SshPublicFileLease lease = view("Host config-probe\n ProxyCommand forbidden\n")) {
            try (SshConfigEvaluator evaluator = SshConfigEvaluator.open(lease, request())) {
                try { evaluator.run(); fail("forbidden command must fail"); }
                catch (SshConfigEvaluator.EvaluationException expected) {
                    assertEquals(1, expected.diagnostic.status);
                    assertEquals(4, expected.diagnostic.reason);
                    assertEquals(2, expected.diagnostic.object);
                    assertEquals(2, expected.diagnostic.line);
                    assertTrue(expected.diagnostic.errorNumber > 0);
                    assertFalse(expected.getMessage().contains("forbidden"));
                }
            }
            assertTrue(lease.active());
            assertTrue(lease.summary().length > 0);
        }
    }
}
