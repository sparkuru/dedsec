package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Assume;
import org.junit.Test;

/** Independent opt-in supplied-material JNI probe; no vault, socket or account. */
public final class SshManifestInspectorPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshManifestInspectorPlatformTest#syntheticSuppliedManifestInspection";

    private static String publicKey(int seed) {
        byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer blob = ByteBuffer.allocate(4 + algorithm.length + 4 + 32);
        blob.putInt(algorithm.length).put(algorithm).putInt(32);
        for (int i = 0; i < 32; i++) blob.put((byte)(seed + i));
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.array());
    }

    private static VaultManifest manifest(String key, boolean malformed) throws Exception {
        VaultManifest.FileObject publicKey = new VaultManifest.FileObject(
                VaultManifest.FileType.PUBLIC_KEY, "public", "ssh/key.pub", "public",
                (key + " synthetic comment\n").getBytes(StandardCharsets.US_ASCII),
                VaultCollections.list());
        String hosts = "[synthetic.example]:2222 " + key + "\n";
        if (malformed) hosts += "unrelated.example ssh-ed25519 invalid\n";
        VaultManifest.FileObject knownHosts = new VaultManifest.FileObject(
                VaultManifest.FileType.KNOWN_HOSTS, "hosts", "ssh/known_hosts", "hosts",
                hosts.getBytes(StandardCharsets.US_ASCII), VaultCollections.list());
        VaultManifest.FileObject config = new VaultManifest.FileObject(
                VaultManifest.FileType.CONFIG, "config", "ssh/config", "config",
                "Host synthetic\n Port invalid\n".getBytes(StandardCharsets.US_ASCII),
                VaultCollections.list());
        return new VaultManifest(VaultCollections.list(), VaultCollections.list(publicKey, knownHosts, config));
    }

    @Test public void syntheticSuppliedManifestInspection() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshManifestInspectorPlatformProbe");
        assertTrue("Strict material JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped material JNI execution required", "true".equals(optIn));
        assertTrue("Only the named material JNI method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        SshManifestInspector inspector = new SshManifestInspector();
        try (VaultManifest source = manifest(publicKey(1), false);
                VaultManifest candidate = manifest(publicKey(2), true)) {
            byte[] sourceBody = source.files.get(0).content.clone();
            byte[] candidateBody = candidate.files.get(0).content.clone();
            try {
                SshManifestInspector.Report a = inspector.inspect(source, deadline,
                        new SshManifestInspector.Cancellation());
                SshManifestInspector.Report b = inspector.inspect(candidate, deadline,
                        new SshManifestInspector.Cancellation());
                assertTrue(a.contentOnly && b.contentOnly);
                assertFalse(a.admissionComplete || b.admissionComplete);
                assertEquals(3, a.entries.size());
                assertEquals(3, b.entries.size());
                assertEquals(SshManifestInspector.Status.PARSED, a.entries.get(0).status);
                assertEquals(SshManifestInspector.Status.PARSED, b.entries.get(0).status);
                assertEquals(SshManifestInspector.KeyKind.ED25519, a.entries.get(0).keyKind);
                assertFalse(a.entries.get(0).publicBlobDigest.equals(b.entries.get(0).publicBlobDigest));
                assertEquals(SshManifestInspector.Status.PARSED, a.entries.get(1).status);
                assertEquals(1, a.entries.get(1).records);
                assertEquals(SshManifestInspector.Status.MALFORMED, b.entries.get(1).status);
                assertEquals(2, b.entries.get(1).line);
                assertEquals(SshManifestInspector.Status.CONFIG_DEFERRED, a.entries.get(2).status);
                assertEquals(SshManifestInspector.Status.CONFIG_DEFERRED, b.entries.get(2).status);
                assertArrayEquals(sourceBody, source.files.get(0).content);
                assertArrayEquals(candidateBody, candidate.files.get(0).content);
            } finally {
                VaultCodec.wipe(sourceBody);
                VaultCodec.wipe(candidateBody);
            }
        }
    }
}
