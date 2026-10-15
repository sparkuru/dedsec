package im.majo.ctos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Owned native bootstrap view. No descriptor, pointer or content channel API. */
final class SshPublicFileLease implements AutoCloseable, VaultSession.Revocation,
        SshRoleContext.PublicView {
    private static final int MAGIC = 0x43535056;
    private static final int MAX_WIRE = VaultEnvelope.MAX_PLAINTEXT;
    private long token;
    private volatile boolean active = true;
    private boolean cleanupFailed;

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native long create(byte[] wire) throws IOException;
        static native long[] summary(long token) throws IOException;
        static native boolean destroy(long token);
    }

    private SshPublicFileLease(long token) { this.token = token; }

    static SshPublicFileLease create(SshFileSnapshot snapshot) throws VaultException {
        byte[] wire = encode(snapshot);
        try {
            SshPublicFileLease lease = new SshPublicFileLease(0);
            long token = Native.create(wire);
            if (token <= 0) throw unavailable();
            lease.token = token;
            return lease;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { VaultCodec.wipe(wire); }
    }

    private static int tag(VaultManifest.FileType type) {
        switch (type) {
            case CONFIG: return 1;
            case PRIVATE_KEY: return 2;
            case PUBLIC_KEY: return 3;
            case CERTIFICATE: return 4;
            case KNOWN_HOSTS: return 5;
            default: throw new AssertionError("Unsupported SSH file type");
        }
    }

    private static int textSize(String value, int maximum) throws VaultException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > maximum || value.isEmpty() || value.indexOf('\0') >= 0)
                throw VaultException.invalid();
            return 4 + bytes.length;
        } finally { VaultCodec.wipe(bytes); }
    }

    /** Native independently rechecks this format; private content is never serialized. */
    static byte[] encode(SshFileSnapshot snapshot) throws VaultException {
        if (snapshot.metadata.isEmpty() || snapshot.metadata.size() > VaultManifest.MAX_FILES)
            throw VaultException.invalid();
        Map<String, VaultManifest.FileObject> publicFiles = new HashMap<>();
        for (VaultManifest.FileObject file : snapshot.publicFiles) {
            if (file.type == VaultManifest.FileType.PRIVATE_KEY || publicFiles.put(file.path, file) != null)
                throw VaultException.invalid();
        }
        byte[] binding = snapshot.binding();
        try {
            if (binding.length != 32) throw VaultException.invalid();
            long size = 8 + 16 + 24 + 32 + 4;
            size += textSize(snapshot.requestId, 128) + textSize(snapshot.connectionId, 512)
                    + textSize(snapshot.rootPath, 512);
            long contentTotal = 0;
            for (SshFileSnapshot.Metadata file : snapshot.metadata) {
                VaultManifest.path(file.path);
                if (file.contentBytes < 0 || file.contentBytes > VaultManifest.MAX_FILE_BYTES
                        || file.references.size() > VaultManifest.MAX_FILES) throw VaultException.invalid();
                contentTotal += file.contentBytes;
                size += 1 + 4 + 4 + 4 + textSize(file.id, 128) + textSize(file.path, 512)
                        + textSize(file.identity, 512);
                for (String ref : file.references) size += textSize(ref, 512);
                if (file.type != VaultManifest.FileType.PRIVATE_KEY) {
                    VaultManifest.FileObject body = publicFiles.remove(file.path);
                    if (body == null || body.type != file.type || !body.id.equals(file.id)
                            || !body.identity.equals(file.identity) || body.content.length != file.contentBytes
                            || !body.references.equals(file.references)) throw VaultException.invalid();
                    size += body.content.length;
                }
                if (size > MAX_WIRE || contentTotal > VaultManifest.MAX_TOTAL_FILE_BYTES)
                    throw VaultException.invalid();
            }
            if (!publicFiles.isEmpty()) throw VaultException.invalid();
            // The checked exact size avoids abandoned backing arrays on growth.
            try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer((int)size)) {
                DataOutputStream out = new DataOutputStream(buffer);
                out.writeInt(MAGIC); out.writeInt(1);
                out.writeLong(snapshot.vaultId.getMostSignificantBits());
                out.writeLong(snapshot.vaultId.getLeastSignificantBits());
                out.writeLong(snapshot.generation); out.writeLong(snapshot.epoch); out.writeLong(snapshot.deadline);
                VaultCodec.writeText(out, snapshot.requestId);
                VaultCodec.writeText(out, snapshot.connectionId);
                VaultCodec.writeText(out, snapshot.rootPath);
                out.write(binding); out.writeInt(snapshot.metadata.size());
                Map<String, VaultManifest.FileObject> bodies = new HashMap<>();
                for (VaultManifest.FileObject file : snapshot.publicFiles) bodies.put(file.path, file);
                for (SshFileSnapshot.Metadata file : snapshot.metadata) {
                    out.writeByte(tag(file.type));
                    VaultCodec.writeText(out, file.id); VaultCodec.writeText(out, file.path);
                    VaultCodec.writeText(out, file.identity); out.writeInt(file.contentBytes);
                    out.writeInt(file.references.size());
                    for (String ref : file.references) VaultCodec.writeText(out, ref);
                    if (file.type == VaultManifest.FileType.PRIVATE_KEY) out.writeInt(0);
                    else VaultCodec.writeBytes(out, bodies.get(file.path).content);
                }
                out.flush();
                if (buffer.size() != size) throw VaultException.invalid();
                return buffer.toByteArray();
            } catch (IOException failure) { throw unavailable(); }
        } finally { VaultCodec.wipe(binding); }
    }

    synchronized long[] summary() throws VaultException {
        if (!active) throw new VaultException("VAULT_CHANGED", "The SSH file view was revoked");
        try { return Native.summary(token); }
        catch (IOException | LinkageError failure) { throw unavailable(); }
    }

    /**
     * Internal session-owner handle. It is only a token for the native lease;
     * callers never receive the mapped view, descriptor, or file body.
     */
    synchronized long nativeToken() throws VaultException {
        if (!active || token <= 0) throw new VaultException("VAULT_CHANGED",
                "The SSH file view was revoked");
        return token;
    }

    @Override public boolean active() { return active; }

    /** No AppSecrets/session calls: also safe under the session's lock. */
    @Override public synchronized void revoke() {
        if (active) active = false;
        destroyNative();
    }

    @Override public void close() throws VaultException {
        revoke();
        synchronized (this) { if (cleanupFailed) throw unavailable(); }
    }

    /** Revoke may only acknowledge a deferred native borrow release. Keep the
     * token when cleanup is unconfirmed so the owner can retry it. */
    private synchronized void destroyNative() {
        if (token <= 0) return;
        try {
            if (Native.destroy(token)) {
                token = 0;
                cleanupFailed = false;
            } else {
                cleanupFailed = true;
            }
        } catch (LinkageError failure) {
            cleanupFailed = true;
        }
    }

    private static VaultException unavailable() {
        return new VaultException("SSH_FILE_VIEW", "The protected SSH file view is unavailable");
    }
}
