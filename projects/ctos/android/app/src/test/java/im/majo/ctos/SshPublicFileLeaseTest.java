package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Test;

public final class SshPublicFileLeaseTest {
    private static final byte[] PRIVATE = "synthetic-private-body-must-not-cross".getBytes(StandardCharsets.UTF_8);

    private SshFileSnapshot snapshot() throws Exception {
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 73, new byte[16], new byte[32], new byte[0]);
        VaultOperation operation = new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                "ssh/config", "", "connection-1", envelope, 7, 9999999);
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "root", "ssh/config", "root-config", "Host public".getBytes(StandardCharsets.UTF_8),
                VaultCollections.list("ssh/key", "ssh/known_hosts"));
        VaultManifest.FileObject key = new VaultManifest.FileObject(VaultManifest.FileType.PRIVATE_KEY,
                "key", "ssh/key", "key-identity", PRIVATE, VaultCollections.list());
        VaultManifest.FileObject trust = new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                "trust", "ssh/known_hosts", "trust-identity", new byte[]{1, 2, 3}, VaultCollections.list());
        return new SshFileSnapshot(operation, VaultCollections.list(config, key, trust));
    }

    @Test public void nativeWireCarriesBindingsAndPrivateMetadataWithNoPrivateBody() throws Exception {
        SshFileSnapshot snapshot = snapshot(); byte[] wire = SshPublicFileLease.encode(snapshot);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire))) {
            assertEquals(0x43535056, in.readInt()); assertEquals(1, in.readInt());
            assertEquals(snapshot.vaultId.getMostSignificantBits(), in.readLong());
            assertEquals(snapshot.vaultId.getLeastSignificantBits(), in.readLong());
            assertEquals(73, in.readLong()); assertEquals(7, in.readLong()); assertEquals(9999999, in.readLong());
            assertEquals(snapshot.requestId, VaultCodec.readText(in, 128));
            assertEquals("connection-1", VaultCodec.readText(in, 512));
            assertEquals("ssh/config", VaultCodec.readText(in, 512));
            byte[] binding = new byte[32]; in.readFully(binding); assertArrayEquals(snapshot.binding(), binding);
            assertEquals(3, in.readInt());
            int[] types = {1, 2, 5};
            for (int i = 0; i < types.length; i++) {
                assertEquals(types[i], in.readUnsignedByte());
                SshFileSnapshot.Metadata file = snapshot.metadata.get(i);
                assertEquals(file.id, VaultCodec.readText(in, 128));
                assertEquals(file.path, VaultCodec.readText(in, 512));
                assertEquals(file.identity, VaultCodec.readText(in, 512));
                assertEquals(file.contentBytes, in.readInt()); assertEquals(file.references.size(), in.readInt());
                for (String ref : file.references) assertEquals(ref, VaultCodec.readText(in, 512));
                byte[] body = VaultCodec.readBytes(in, VaultManifest.MAX_FILE_BYTES);
                assertEquals(i == 1 ? 0 : file.contentBytes, body.length);
                VaultCodec.wipe(body);
            }
            assertEquals(0, in.available());
            assertFalse(new String(wire, StandardCharsets.ISO_8859_1).contains(new String(PRIVATE, StandardCharsets.US_ASCII)));
        } finally { VaultCodec.wipe(wire); }
    }

    @Test public void encodingUsesBorrowedPublicBodiesAndFailsOnSizeOverflow() throws Exception {
        SshFileSnapshot snapshot = snapshot();
        for (VaultManifest.FileObject file : snapshot.publicFiles) VaultCodec.wipe(file.content);
        byte[] wire = SshPublicFileLease.encode(snapshot);
        assertFalse(new String(wire, StandardCharsets.US_ASCII).contains("Host public")); VaultCodec.wipe(wire);
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], new byte[32], new byte[0]);
        VaultOperation operation = new VaultOperation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                "ssh/config", "", "connection-1", envelope, 1, 99);
        VaultManifest.FileObject tooLarge = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                "root", "ssh/config", "root-config", new byte[VaultManifest.MAX_FILE_BYTES + 1], VaultCollections.list());
        try {
            SshPublicFileLease.encode(new SshFileSnapshot(operation, Arrays.asList(tooLarge))); fail();
        } catch (VaultException failure) { assertEquals("VAULT_CONTENT", failure.code); }
        finally { VaultCodec.wipe(tooLarge.content); }
    }
}
