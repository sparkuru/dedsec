package im.majo.ctos;

import java.io.IOException;
import java.io.OutputStream;

/** SAF has no general transaction. Withhold GCM's tag until the final authorized effect boundary. */
final class VaultExportWriter {
    private VaultExportWriter() { }
    static void write(OutputStream output, byte[] envelope, AppSecrets.EffectGuard guard,
            AppSecrets.EffectGuard finalizing) throws VaultException {
        boolean tagStarted = false;
        try (OutputStream stream = output) {
            int body = envelope.length - 16;
            for (int offset = 0; offset < body; offset += 8192) {
                guard.check();
                stream.write(envelope, offset, Math.min(8192, body - offset));
            }
            guard.check();
            finalizing.check();
            tagStarted = true;
            stream.write(envelope, body, 16);
            stream.flush();
        } catch (IOException | RuntimeException error) {
            if (tagStarted) throw uncertain();
            throw new VaultException("VAULT_IO", "Backup write did not complete; the vault is unchanged");
        }
    }
    static VaultException uncertain() {
        return new VaultException("VAULT_EXPORT_UNCERTAIN",
                "The file provider could not confirm completion; an encrypted backup may exist. The vault is unchanged");
    }
}
