package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class SshAuthenticationSelectionTest {
    private final SshRoleContext.Clock clock = () -> 1_000;

    private SshRoleContext role(int seed) throws VaultException {
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) seed);
        return SshRoleContext.issue(new SshRoleContext.Bootstrap(SshRoleContext.Program.CLIENT,
                123, 0, "connection", "opaque-target", "example.invalid", 22, "view",
                nonce, nonce, 31_000), () -> true, clock);
    }

    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("invalid selection must be rejected"); }
        catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
    }

    @Test public void canonicalPasswordRecordMatchesGoldenBytesAndDigest() throws Exception {
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(role(1),
                " Aé😀 ", "selected_2", clock);
        byte[] wire = selection.encode(0x0102030405060708L, clock);
        assertArrayEquals(hex("4353415300010000010203040506070800000000"
                + "01".repeat(64) + "00000001000000092041c3a9f09f988020"
                + "0000000a73656c65637465645f32000000000000000000000000"), wire);
        assertArrayEquals(hex("e4e28f197ddc3ba0c6718719277a5fd24cfcdec25207039bdcdf41a5b262a6f3"),
                VaultCodec.digest(wire));
        wire[0] = 0;
        assertEquals(0x43, selection.encode(0x0102030405060708L, clock)[0]);
        rejected(() -> selection.encode(0, clock));
        rejected(() -> selection.encode(-1, clock));
    }

    @Test public void canonicalPrivateRecordPreservesAllFiveFieldLengthsAndBytes() throws Exception {
        byte[] blob = new byte[] { 0, (byte) 0xff, 4 };
        SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role(2), "用户😀",
                "keys/私钥", "身份", blob, clock);
        java.nio.ByteBuffer wire = java.nio.ByteBuffer.wrap(selection.encode(Long.MAX_VALUE, clock));
        assertEquals(SshAuthenticationSelection.WIRE_MAGIC, wire.getInt());
        assertEquals(1, wire.getShort());
        assertEquals(0, wire.getShort());
        assertEquals(Long.MAX_VALUE, wire.getLong());
        assertEquals(0, wire.getInt());
        byte[] nonce = new byte[64];
        wire.get(nonce);
        byte[] expectedNonce = new byte[64];
        Arrays.fill(expectedNonce, (byte) 2);
        assertArrayEquals(expectedNonce, nonce);
        assertEquals(4, wire.getInt());
        for (byte[] expected : new byte[][] { "用户😀".getBytes(StandardCharsets.UTF_8), new byte[0],
                "keys/私钥".getBytes(StandardCharsets.UTF_8), "身份".getBytes(StandardCharsets.UTF_8), blob }) {
            assertEquals(expected.length, wire.getInt());
            byte[] actual = new byte[expected.length];
            wire.get(actual);
            assertArrayEquals(expected, actual);
        }
        assertEquals(0, wire.remaining());
    }

    @Test public void encryptedPrivateRecordFreezesExactPassphraseEntryAsVersionTwo() throws Exception {
        SshRoleContext role = role(3);
        SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role, "user",
                "keys/key", "identity", new byte[]{1, 2, 3}, "passphrase_2", clock);
        java.nio.ByteBuffer wire = java.nio.ByteBuffer.wrap(selection.encode(9, clock));
        assertEquals(SshAuthenticationSelection.WIRE_MAGIC, wire.getInt());
        assertEquals(2, wire.getShort());
        assertEquals(0, wire.getShort());
        wire.position(84);
        assertEquals(4, wire.getInt());
        byte[] user = new byte[wire.getInt()]; wire.get(user);
        byte[] entry = new byte[wire.getInt()]; wire.get(entry);
        assertArrayEquals("user".getBytes(StandardCharsets.UTF_8), user);
        assertArrayEquals("passphrase_2".getBytes(StandardCharsets.UTF_8), entry);
        assertEquals("passphrase_2", selection.entryId);
        for (String invalid : new String[]{"bad.id", "a".repeat(129), "x\0y"})
            rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "keys/key", "identity",
                    new byte[]{1}, invalid, clock));
        rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "keys/key", "identity",
                new byte[]{1}, null, clock));
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    @Test public void usernameUsesStrictUtf8ByteBoundsAndPreservesInput() throws Exception {
        SshRoleContext role = role(1);
        for (String accepted : new String[] { "a".repeat(255), "é".repeat(127) + "a",
                " 用户e\u0301😀 " }) {
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(role, accepted,
                    "password", clock);
            assertEquals(accepted, selection.username);
            assertArrayEquals(accepted.getBytes(StandardCharsets.UTF_8), selection.usernameBytes());
            byte[] changed = selection.usernameBytes();
            changed[0] ^= 1;
            assertArrayEquals(accepted.getBytes(StandardCharsets.UTF_8), selection.usernameBytes());
        }
        for (String invalid : new String[] { "", "a".repeat(256), "é".repeat(128),
                "a\0b", "a\rb", "a\nb", "a\u0085b", "a\u007fb", "\ud800", "\udc00" }) {
            rejected(() -> SshAuthenticationSelection.password(role, invalid, "password", clock));
        }
        rejected(() -> SshAuthenticationSelection.password(role, null, "password", clock));
    }

    @Test public void typedShapesRejectInvalidIdsPathsAndKeyMetadata() throws Exception {
        SshRoleContext role = role(1);
        rejected(() -> SshAuthenticationSelection.password(role, "user", "bad.id", clock));
        rejected(() -> SshAuthenticationSelection.password(role, "user", "a".repeat(129), clock));
        for (String path : new String[] { "", "/key", "../key", "a//b", "a\\b", "a:b",
                "a%b", "~key", "k*", "k?", "k[", "a/./b", "a\u0085b", "\ud800",
                "é".repeat(257), "a/b/c/d/e/f/g/h/i" }) {
            rejected(() -> SshAuthenticationSelection.privateKey(role, "user", path, "identity",
                    new byte[] { 1 }, clock));
        }
        rejected(() -> SshAuthenticationSelection.privateKey(role, "user", null, "id", new byte[] {1}, clock));
        for (String identity : new String[] { "", "a\n", "\udc00", "é".repeat(257) }) {
            rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "keys/key", identity,
                    new byte[] { 1 }, clock));
        }
        rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "key", "id", null, clock));
        rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "key", "id", new byte[0], clock));
        rejected(() -> SshAuthenticationSelection.privateKey(role, "user", "key", "id",
                new byte[VaultManifest.MAX_FILE_BYTES + 1], clock));
    }

    @Test public void publicDeclarationIsBoundedAndDefensivelyCopied() throws Exception {
        byte[] input = new byte[VaultManifest.MAX_FILE_BYTES];
        input[0] = 7;
        SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(role(1), "user",
                "keys/key", "identity", input, clock);
        input[0] = 9;
        byte[] output = selection.publicKeyBlob();
        assertEquals(7, output[0]);
        output[0] = 10;
        assertEquals(7, selection.publicKeyBlob()[0]);
        assertEquals(SshCredentialBridge.Kind.PRIVATE_KEY, selection.kind);
        assertEquals("", selection.entryId);
    }

    @Test public void exactRoleObjectRejectsSameLegacyBindingWithDifferentNonces() throws Exception {
        SshRoleContext selectedRole = role(1);
        SshRoleContext sameFields = role(2);
        assertEquals(selectedRole.binding(), sameFields.binding());
        SshAuthenticationSelection selection = SshAuthenticationSelection.password(selectedRole,
                "user", "password", clock);
        selection.requireMatches(selectedRole, SshCredentialBridge.Kind.PASSWORD, clock);
        rejected(() -> selection.requireMatches(sameFields, SshCredentialBridge.Kind.PASSWORD, clock));
        rejected(() -> selection.requireMatches(selectedRole, SshCredentialBridge.Kind.PRIVATE_KEY, clock));
        selectedRole.close();
        try { selection.requireMatches(selectedRole, selection.kind, clock); fail("closed role"); }
        catch (VaultException failure) { assertEquals("SSH_ROLE_EXPIRED", failure.code); }
    }
}
