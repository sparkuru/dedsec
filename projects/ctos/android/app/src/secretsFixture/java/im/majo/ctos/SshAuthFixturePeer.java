package im.majo.ctos;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.IOException;

/** Fixed synthetic AF_UNIX server; compiled only with the SSH auth fixture option. */
final class SshAuthFixturePeer implements AutoCloseable {
    static final int SUCCESS = 0;
    static final int REJECTED = 1;
    static final int PARTIAL = 2;
    static final int CHANGE_REQUIRED = 3;
    static final int HOLD = 4;
    static final int MALFORMED_SUCCESS = 5;
    static final int WRONG_PASSPHRASE = 6;
    static final int SHELL = 7;
    static final int PTY_REJECT = 8;
    static final int SHELL_HOLD = 9;
    static final int OPEN_REJECT = 10;
    static final int SHELL_REJECT = 11;
    private static boolean loaded;
    private final Thread owner = Thread.currentThread();
    private long handle;

    private SshAuthFixturePeer(long handle) { this.handle = handle; }

    static synchronized void load(Context target) throws IOException {
        if (!"im.majo.ctos.secretsfixture".equals(target.getPackageName())
                || target.getApplicationInfo().uid != Process.myUid()
                || Process.myUid() < Process.FIRST_APPLICATION_UID
                || !(target.getApplicationContext() instanceof SecretsFixtureApplication)) {
            throw new IOException("Synthetic SSH fixture identity required");
        }
        if (loaded) return;
        File directory = new File(target.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        System.load(new File(directory, "libctos_ssh_files.so").getCanonicalPath());
        System.load(new File(directory, "libctos_ssh_auth_peer.so").getCanonicalPath());
        loaded = true;
    }

    static SshAuthFixturePeer open(int borrowedPeerFd, int mode) throws IOException {
        return open(borrowedPeerFd, mode, 0);
    }

    static SshAuthFixturePeer open(int borrowedPeerFd, int mode, int identityKind) throws IOException {
        return open(borrowedPeerFd, mode, identityKind, false);
    }

    static SshAuthFixturePeer open(int borrowedPeerFd, int mode, int identityKind, boolean encrypted) throws IOException {
        if (!loaded) throw new IOException("Synthetic SSH fixture is not loaded");
        long handle = openNative(borrowedPeerFd, mode, identityKind, encrypted);
        if (handle <= 0) throw new IOException("Synthetic SSH peer unavailable");
        return new SshAuthFixturePeer(handle);
    }

    byte[] hostKeyBlob() throws IOException {
        requireOpen();
        return hostKeyBlobNative(handle);
    }

    /** Generated synthetic identity only; caller wipes private material after envelope construction. */
    byte[][] identityMaterial() throws IOException {
        requireOpen();
        return identityMaterialNative(handle);
    }

    void step() throws IOException {
        requireOpen();
        stepNative(handle);
    }

    int[] summary() throws IOException {
        requireOpen();
        int[] result = summaryNative(handle);
        if (result == null || result.length != 4) throw new IOException("Synthetic SSH summary unavailable");
        return result;
    }

    int[] shellSummary() throws IOException {
        requireOpen();
        int[] result = shellSummaryNative(handle);
        if (result == null || result.length != 8) throw new IOException("Synthetic SSH shell summary unavailable");
        return result;
    }

    private void requireOpen() throws IOException {
        if (Thread.currentThread() != owner || handle <= 0) {
            throw new IOException("Synthetic SSH peer owner required");
        }
    }

    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IOException("Synthetic SSH peer owner required");
        if (handle == 0) return;
        closeNative(handle);
        handle = 0;
    }

    private static native long openNative(int borrowedPeerFd, int mode, int identityKind, boolean encrypted) throws IOException;
    private static native byte[] hostKeyBlobNative(long handle) throws IOException;
    private static native byte[][] identityMaterialNative(long handle) throws IOException;
    private static native void stepNative(long handle) throws IOException;
    private static native int[] summaryNative(long handle) throws IOException;
    private static native int[] shellSummaryNative(long handle) throws IOException;
    private static native void closeNative(long handle) throws IOException;
}
