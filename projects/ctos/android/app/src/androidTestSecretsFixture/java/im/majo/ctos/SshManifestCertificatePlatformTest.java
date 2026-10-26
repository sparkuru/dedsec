package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in public synthetic certificate facts; no store, private key, account or connection. */
public final class SshManifestCertificatePlatformTest {
    // Locally generated Ed25519 HOST certificate: UINT64_MAX serial and an expired 2020 interval.
    private static final String CERTIFICATE = "ssh-ed25519-cert-v01@openssh.com "
            + "AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIMf6zKRXXp0lsuqgWinDwBqVob6gDRAb4i3c5+nxFzRY"
            + "AAAAIHPUVPLpZ6sMlvC/wJnntQRRWiT5eaVg8t+azGGdOesf//////////8AAAACAAAAGmN0b3Mtc3ludGhldGljLWNlcnRpZmljYXRl"
            + "AAAAEwAAAA9maXh0dXJlLmV4YW1wbGUAAAAAXgvhAAAAAABeDTKAAAAAAAAAAAAAAAAAAAAAMwAAAAtzc2gtZWQyNTUxOQAAACBYe866"
            + "jmi41hjLIz/Ve4q3pDUPar5Z62kI5Z7VBP7ieQAAAFMAAAALc3NoLWVkMjU1MTkAAABASOMP4SNvvwOXvy9xsDTxO5cDKIzs"
            + "ADdkGmFyfvPtnAHBYF83VGs759WtMRholuSScCAS9lGT4vveujPmc31ZBQ== synthetic certificate subject";

    private static VaultManifest manifest(boolean malformed) throws Exception {
        String text = "# public synthetic certificate\n" + CERTIFICATE + "\n";
        if (malformed) text += CERTIFICATE + "\n";
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        byte[] password = "unused synthetic password".getBytes(StandardCharsets.US_ASCII);
        try {
            return new VaultManifest(VaultCollections.list(new VaultManifest.Entry(
                    VaultManifest.EntryType.SSH_PASSWORD, "password", "target:opaque-synthetic-target",
                    "", "", 0, 0, password)), VaultCollections.list(new VaultManifest.FileObject(
                    VaultManifest.FileType.CERTIFICATE, "certificate", "ssh/certificate.pub", "certificate",
                    body, VaultCollections.list())));
        } finally { VaultCodec.wipe(body); VaultCodec.wipe(password); }
    }

    @Test public void syntheticAllCertificateFacts() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshCertificatePlatformProbe");
        assertTrue("Strict certificate JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped certificate JNI execution required", "true".equals(optIn));
        assertEquals("im.majo.ctos.SshManifestCertificatePlatformTest#syntheticAllCertificateFacts",
                arguments.getString("class"));
        SshMemoryPlatformTest.guard();
        try (VaultManifest source = manifest(false); VaultManifest candidate = manifest(true)) {
            byte[] original = source.files.get(0).content.clone();
            try {
                long deadline = SystemClock.elapsedRealtime() + 30_000L;
                SshManifestCertificateInspector.Report a = new SshManifestCertificateInspector().inspect(
                        source, deadline, new SshManifestCertificateInspector.Cancellation());
                SshManifestCertificateInspector.Report b = new SshManifestCertificateInspector().inspect(
                        candidate, deadline, new SshManifestCertificateInspector.Cancellation());
                assertTrue(a.allCertificateFilesInspected && a.factsOnly && a.allCertificateFactsAvailable);
                assertFalse(a.admissionComplete || a.trustChecked || a.timeValidityChecked
                        || a.principalApplicabilityChecked || a.revocationChecked || b.allCertificateFactsAvailable);
                SshManifestCertificateInspector.Entry entry = a.entries.get(0);
                assertEquals(SshManifestCertificateInspector.Status.FACTS, entry.status);
                assertEquals(2, entry.sourceLine);
                assertEquals(SshManifestCertificateInspector.CertificateType.HOST, entry.facts.certificateType);
                assertEquals(SshManifestCertificateInspector.KeyKind.ED25519, entry.facts.subjectKind);
                assertEquals(SshManifestCertificateInspector.KeyKind.ED25519, entry.facts.caKind);
                assertEquals(SshManifestCertificateInspector.SignatureAlgorithm.ED25519, entry.facts.signatureAlgorithm);
                assertEquals(-1L, entry.facts.serialBits);
                assertEquals(1577836800L, entry.facts.validAfterBits);
                assertEquals(1577923200L, entry.facts.validBeforeBits);
                assertEquals(1, entry.facts.principalCount);
                assertEquals(0, entry.facts.criticalByteLength); assertEquals(0, entry.facts.extensionByteLength);
                assertEquals(64, entry.facts.certificateDigest.length()); assertEquals(64, entry.facts.subjectDigest.length());
                assertEquals(64, entry.facts.caDigest.length());
                assertEquals(SshManifestCertificateInspector.Status.MALFORMED, b.entries.get(0).status);
                assertEquals(3, b.entries.get(0).sourceLine); assertNull(b.entries.get(0).facts);
                assertArrayEquals(original, source.files.get(0).content);
            } finally { VaultCodec.wipe(original); }
        }
    }
}
