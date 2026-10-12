package im.majo.ctos;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** One AtomicFile contains the complete portable generation and optional device wrapper. */
final class VaultStore implements AppSecrets.Store {
    private static final int STATE_MAGIC = 0x43545331;
    private final AtomicFile file;
    private static final int MAX_WRAPPER = 1024;

    VaultStore(Context context) {
        this(new File(context.getNoBackupFilesDir(), "app-secrets.bin"));
    }
    VaultStore(File isolatedFile) { file = new AtomicFile(isolatedFile); }

    @Override public synchronized byte[] read() throws IOException { return readState()[0]; }
    @Override public synchronized byte[] quickMaterial() throws IOException { return readState()[1]; }

    private byte[][] readState() throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new byte[][] {null, null};
        }
        try (DataInputStream in = new DataInputStream(file.openRead())) {
            if (in.readInt() != STATE_MAGIC) throw new IOException("Invalid vault state");
            int length = in.readInt();
            if (length < 0 || length > VaultEnvelope.MAX_BYTES) throw new IOException("Invalid vault length");
            byte[] envelope = new byte[length];
            in.readFully(envelope);
            length = in.readInt();
            if (length < 0 || length > MAX_WRAPPER) throw new IOException("Invalid wrapper length");
            byte[] quick = new byte[length];
            in.readFully(quick);
            if (in.read() != -1) throw new IOException("Invalid trailing data");
            return new byte[][] {envelope, length == 0 ? null : quick};
        }
    }

    @Override public synchronized void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard)
            throws IOException, VaultException {
        if (envelope.length > VaultEnvelope.MAX_BYTES || quick != null && quick.length > MAX_WRAPPER) {
            throw new IOException("Oversized vault state");
        }
        FileOutputStream stream = null;
        boolean finishCalled = false;
        byte[][] previous = readState();
        try {
            stream = file.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(STATE_MAGIC);
            out.writeInt(envelope.length);
            out.write(envelope);
            out.writeInt(quick == null ? 0 : quick.length);
            if (quick != null) out.write(quick);
            out.flush();
            stream.getFD().sync();
            guard.beforeCommit();
            // Both ciphertext and device state switch together; no separate enabled preference can revive a wrapper.
            finishCalled = true;
            file.finishWrite(stream);
            byte[][] persisted;
            try { persisted = readState(); }
            catch (IOException failure) {
                throw new VaultException("VAULT_COMMIT_UNCERTAIN", "Unable to confirm the saved vault; reopen with the master password");
            }
            if (!java.util.Arrays.equals(envelope, persisted[0]) || !java.util.Arrays.equals(quick, persisted[1])) {
                if (java.util.Arrays.equals(previous[0], persisted[0]) && java.util.Arrays.equals(previous[1], persisted[1])) {
                    throw new IOException("The previous generation remains authoritative");
                }
                throw new VaultException("VAULT_COMMIT_UNCERTAIN", "Unable to confirm the saved vault; reopen with the master password");
            }
        } catch (IOException | VaultException error) {
            if (stream != null && !finishCalled) file.failWrite(stream);
            throw error;
        }
    }
}
