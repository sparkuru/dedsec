package im.majo.ctos.host;

import android.system.ErrnoException;
import java.io.FileDescriptor;

/** Host-only Linux calls; this class is absent from Android production builds. */
public final class HostLinux {
    static { System.loadLibrary("ctos_host_compat"); }
    private HostLinux() { }

    public static native int pid();
    public static native int uid();
    public static native long elapsedRealtime();
    public static native FileDescriptor socket(int domain, int type, int protocol) throws ErrnoException;
    public static native void connect(FileDescriptor fd, byte[] address, int port) throws ErrnoException;
    public static native int poll(FileDescriptor fd, int events, int timeout) throws ErrnoException;
    public static native byte[] peer(FileDescriptor fd) throws ErrnoException;
    public static native int dup(FileDescriptor fd) throws ErrnoException;
    public static native void closeDescriptor(FileDescriptor fd) throws ErrnoException;
    public static native void closeNumber(int fd) throws ErrnoException;
    public static native void resetSocketEvidence();
    public static native int socketCount();
    public static native int transferredSocket();
    public static native boolean descriptorClosed(int fd);
}
