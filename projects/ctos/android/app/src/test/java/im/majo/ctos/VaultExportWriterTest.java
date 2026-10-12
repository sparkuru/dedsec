package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.junit.Test;

public final class VaultExportWriterTest {
    private byte[] envelope() throws Exception {
        return VaultEnvelope.encrypt(UUID.randomUUID(), 1, new byte[16], new byte[32], VaultManifest.empty().encode()).encode();
    }
    @Test public void cancellationBeforeTagLeavesUnauthenticatedBytes() throws Exception {
        byte[] encrypted = envelope();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int[] guards = {0};
        try {
            VaultExportWriter.write(received, encrypted, () -> {
                if (++guards[0] == 2) throw new VaultException("VAULT_CANCELLED", "fixture cancel");
            }, () -> { });
            fail();
        } catch (VaultException failure) { assertEquals("VAULT_CANCELLED", failure.code); }
        assertEquals(encrypted.length - 16, received.size());
        try { VaultEnvelope.parse(received.toByteArray()); fail(); }
        catch (VaultException failure) { assertEquals("VAULT_FORMAT", failure.code); }
    }
    @Test public void providerFlushFailureAfterTagIsExplicitlyUncertainAndMayLeaveValidBackup() throws Exception {
        byte[] encrypted = envelope();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        OutputStream provider = new OutputStream() {
            @Override public void write(int value) { received.write(value); }
            @Override public void flush() throws IOException { throw new IOException("Fixture provider stored then failed"); }
        };
        try { VaultExportWriter.write(provider, encrypted, () -> { }, () -> { }); fail(); }
        catch (VaultException failure) { assertEquals("VAULT_EXPORT_UNCERTAIN", failure.code); }
        assertArrayEquals(encrypted, received.toByteArray());
        assertNotNull(VaultEnvelope.parse(received.toByteArray()).decrypt(new byte[32]));
    }
    @Test public void providerCloseFailureAfterTagIsExplicitlyUncertain() throws Exception {
        ByteArrayOutputStream received = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { throw new IOException("Fixture close failed"); }
        };
        try { VaultExportWriter.write(received, envelope(), () -> { }, () -> { }); fail(); }
        catch (VaultException failure) { assertEquals("VAULT_EXPORT_UNCERTAIN", failure.code); }
    }
}
