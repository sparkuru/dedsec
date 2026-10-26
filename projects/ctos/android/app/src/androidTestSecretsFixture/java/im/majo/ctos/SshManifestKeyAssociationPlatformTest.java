package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in synthetic RFC8032 identity; no store, account, network or auth peer. */
public final class SshManifestKeyAssociationPlatformTest {
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static void string(DataOutputStream out, byte[] value) throws Exception {
        out.writeInt(value.length); out.write(value);
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static VaultManifest manifest(boolean inconsistent) throws Exception {
        // Same public fields in both containers; only the secret seed changes.
        byte[] seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] secret = new byte[64];
        if (inconsistent) seed[0] ^= 1;
        System.arraycopy(seed, 0, secret, 0, 32); System.arraycopy(publicKey, 0, secret, 32, 32);
        ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
        DataOutputStream pub = new DataOutputStream(publicBytes);
        string(pub, ascii("ssh-ed25519")); string(pub, publicKey);
        ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
        DataOutputStream key = new DataOutputStream(privateBytes);
        key.writeInt(0x12345678); key.writeInt(0x12345678);
        string(key, ascii("ssh-ed25519")); string(key, publicKey); string(key, secret); string(key, new byte[0]);
        int padding = 8 - privateBytes.size() % 8;
        for (int i = 1; i <= padding; i++) key.writeByte(i);
        ByteArrayOutputStream containerBytes = new ByteArrayOutputStream();
        DataOutputStream container = new DataOutputStream(containerBytes);
        container.write(ascii("openssh-key-v1\0"));
        string(container, ascii("none")); string(container, ascii("none")); string(container, new byte[0]);
        container.writeInt(1); string(container, publicBytes.toByteArray()); string(container, privateBytes.toByteArray());
        byte[] body = ascii("-----BEGIN OPENSSH PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(70, new byte[]{10}).encodeToString(containerBytes.toByteArray())
                + "\n-----END OPENSSH PRIVATE KEY-----\n");
        byte[] peer = ascii("ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBytes.toByteArray()) + "\n");
        try {
            return new VaultManifest(VaultCollections.list(), VaultCollections.list(
                    new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY, "private", "ssh/key", "private", body, VaultCollections.list()),
                    new VaultManifest.FileObject(VaultManifest.FileType.PUBLIC_KEY, "public", "ssh/key.pub", "different label", peer, VaultCollections.list())));
        } finally { VaultCodec.wipe(seed); VaultCodec.wipe(secret); VaultCodec.wipe(body); }
    }
    @Test public void syntheticExplicitPairProof() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshKeyAssociationPlatformProbe");
        assertTrue("Strict pair JNI opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped pair JNI execution required", "true".equals(optIn));
        assertEquals("im.majo.ctos.SshManifestKeyAssociationPlatformTest#syntheticExplicitPairProof", arguments.getString("class"));
        SshMemoryPlatformTest.guard();
        try (VaultManifest source = manifest(false); VaultManifest candidate = manifest(true)) {
            byte[] original = source.files.get(0).content.clone();
            try {
                long deadline = SystemClock.elapsedRealtime() + 30_000L;
                SshManifestKeyAssociationInspector.Association pair = new SshManifestKeyAssociationInspector.Association("private", "public", null);
                SshManifestKeyAssociationInspector.Report a = new SshManifestKeyAssociationInspector().inspect(source, VaultCollections.list(pair), deadline, new SshManifestKeyAssociationInspector.Cancellation());
                SshManifestKeyAssociationInspector.Report b = new SshManifestKeyAssociationInspector().inspect(candidate, VaultCollections.list(pair), deadline, new SshManifestKeyAssociationInspector.Cancellation());
                assertTrue(a.explicitPairsOnly && a.allRequestedPairsMatch);
                assertFalse(a.admissionComplete || a.trustChecked || a.credentialTargetsChecked || b.allRequestedPairsMatch);
                assertEquals(SshManifestKeyAssociationInspector.Status.MATCH, a.entries.get(0).status);
                assertEquals(SshManifestKeyAssociationInspector.Status.MISMATCH, b.entries.get(0).status);
                assertEquals(SshManifestKeyAssociationInspector.Stage.SIGNER, b.entries.get(0).stage);
                assertArrayEquals(original, source.files.get(0).content);
            } finally { VaultCodec.wipe(original); }
        }
    }
}
