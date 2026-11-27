package im.majo.ctos.host;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Checks only the host compatibility layer, without claiming product coverage. */
public final class HostLinuxSmoke {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require(Process.myPid() == ProcessHandle.current().pid() && Process.myUid() >= 0, "process identity");
        long started = SystemClock.elapsedRealtime();
        require(started > 0 && SystemClock.elapsedRealtime() >= started, "boot clock");
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (ServerSocket listener = new ServerSocket(0, 8, loopback)) {
            FileDescriptor fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM | 0x80000 | 0x800,
                    OsConstants.IPPROTO_TCP);
            try {
                try { Os.connect(fd, loopback, listener.getLocalPort()); }
                catch (ErrnoException expected) {
                    require(expected.errno == OsConstants.EINPROGRESS, "unexpected connect error");
                }
                StructPollfd item = new StructPollfd();
                item.fd = fd;
                item.events = OsConstants.POLLOUT;
                require(Os.poll(new StructPollfd[] {item}, 1000) == 1
                        && (item.revents & OsConstants.POLLOUT) != 0, "connect polling");
                require(new InetSocketAddress(loopback, listener.getLocalPort()).equals(Os.getpeername(fd)),
                        "peer identity");
                try (Socket accepted = listener.accept(); ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(fd)) {
                    require(accepted.isConnected() && duplicate.getFd() >= 0, "descriptor duplication");
                    int detached = duplicate.detachFd();
                    try (ParcelFileDescriptor adopted = ParcelFileDescriptor.adoptFd(detached)) {
                        require(adopted.getFd() == detached, "descriptor adoption");
                    }
                }
            } finally { Os.close(fd); }
        }
        FileDescriptor denied = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM | 0x80000 | 0x800,
                OsConstants.IPPROTO_TCP);
        try {
            try {
                Os.connect(denied, InetAddress.getByAddress(new byte[] {1, 1, 1, 1}), 22);
                throw new AssertionError("non-loopback socket was accepted");
            } catch (ErrnoException expected) {
                require(expected.errno == 13, "wrong non-loopback rejection");
            }
        } finally { Os.close(denied); }
        System.out.println("HOST_COMPAT smoke=pass scope=loopback-only product=not-tested");
    }
}
