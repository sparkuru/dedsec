package im.majo.ctos;

import static org.junit.Assert.*;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.Test;

/** Opt-in isolated UID, supplied public fixtures only; compilation is not an Android JNI pass. */
public final class SshHostTrustMaterialDeviceTest {
    private static final String BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea";
    private static final String KEY = "ssh-ed25519 " + BLOB;

    @Test(timeout = 45000) public void actualJniMatchesEndpointAndPreservesOtherBytes() throws Exception {
        String retained = "# retained\r\n[fixture.invalid]:2201 " + KEY + " other";
        byte[] source = bytes("# retained\r\n[FIXTURE.INVALID]:2200 " + KEY
                + " old\r\n[fixture.invalid]:2201 " + KEY + " other");
        byte[] original = source.clone();
        long deadline = SystemClock.elapsedRealtime() + 30000;
        String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(BLOB)));
        try (SshHostTrustMaterial.Result query = SshHostTrustMaterial.inspect(
                    source, "Fixture.Invalid", 2200, false, deadline);
                SshHostTrustMaterial.Result prepared = SshHostTrustMaterial.inspect(
                    source, "Fixture.Invalid", 2200, true, deadline)) {
            assertTrue(query.canForget);
            assertEquals(1, query.records.size());
            SshHostTrustMaterial.Record record = query.records.get(0);
            assertEquals(2, record.line);
            assertEquals("ssh-ed25519", record.algorithm);
            assertEquals(fingerprint, record.fingerprint);
            assertEquals("none", record.marker);
            assertEquals("literal", record.selector);
            assertEquals("none", record.reason);
            assertTrue(prepared.canForget);
            byte[] replacement = prepared.replacement();
            try {
                assertArrayEquals(bytes(retained), replacement);
                replacement[0] = 0;
                byte[] independent = prepared.replacement();
                try { assertArrayEquals(bytes(retained), independent); }
                finally { VaultCodec.wipe(independent); }
            } finally { VaultCodec.wipe(replacement); }
            try { query.replacement(); fail("A query must not own a replacement"); }
            catch (IOException expected) { }
        } finally {
            assertArrayEquals(original, source);
            VaultCodec.wipe(source); VaultCodec.wipe(original);
        }
    }

    @Test(timeout = 45000) public void actualJniKeepsPolicyAndSharedRowsOutOfForgetCandidates() throws Exception {
        byte[] source = bytes("fixture.invalid " + KEY + "\n@revoked fixture.invalid " + KEY
                + "\nfixture.invalid,other.invalid " + KEY + "\n");
        try (SshHostTrustMaterial.Result result = SshHostTrustMaterial.inspect(
                source, "fixture.invalid", 22, true, SystemClock.elapsedRealtime() + 30000)) {
            assertFalse(result.canForget);
            assertEquals(3, result.records.size());
            assertEquals("none", result.records.get(0).reason);
            assertEquals("revoked", result.records.get(1).marker);
            assertEquals("policy", result.records.get(1).reason);
            assertEquals("shared", result.records.get(2).selector);
            assertEquals("shared", result.records.get(2).reason);
            try { result.replacement(); fail("Blocked records must not produce a partial candidate"); }
            catch (IOException expected) { }
        } finally { VaultCodec.wipe(source); }
    }

    @Test(timeout = 45000) public void actualJniDistinguishesEmptyReplacementFromNoRecords() throws Exception {
        byte[] source = bytes("fixture.invalid " + KEY);
        try (SshHostTrustMaterial.Result result = SshHostTrustMaterial.inspect(
                    source, "fixture.invalid", 22, true, SystemClock.elapsedRealtime() + 30000);
                SshHostTrustMaterial.Result empty = SshHostTrustMaterial.inspect(
                    new byte[0], "fixture.invalid", 22, true, SystemClock.elapsedRealtime() + 30000)) {
            assertTrue(result.canForget);
            assertEquals(0, result.replacement().length);
            assertFalse(empty.canForget);
            assertTrue(empty.records.isEmpty());
            try { empty.replacement(); fail("No records must not produce a replacement"); }
            catch (IOException expected) { }
        } finally { VaultCodec.wipe(source); }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
