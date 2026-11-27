package android.os;

import im.majo.ctos.host.HostLinux;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;

/** Host fixture substitute retaining the same one-shot descriptor transfer. */
public final class ParcelFileDescriptor implements Closeable {
    private int fd;

    private ParcelFileDescriptor(int fd) {
        if (fd < 0) throw new IllegalArgumentException("invalid descriptor");
        this.fd = fd;
    }

    public static ParcelFileDescriptor dup(FileDescriptor descriptor) throws IOException {
        try { return new ParcelFileDescriptor(HostLinux.dup(descriptor)); }
        catch (android.system.ErrnoException failure) { throw new IOException("dup failed", failure); }
    }

    public static ParcelFileDescriptor adoptFd(int fd) { return new ParcelFileDescriptor(fd); }

    public int getFd() {
        if (fd < 0) throw new IllegalStateException("descriptor unavailable");
        return fd;
    }

    public int detachFd() {
        int result = getFd();
        fd = -1;
        return result;
    }

    @Override public void close() throws IOException {
        int owned = fd;
        fd = -1;
        if (owned < 0) return;
        try { HostLinux.closeNumber(owned); }
        catch (android.system.ErrnoException failure) { throw new IOException("close failed", failure); }
    }
}
