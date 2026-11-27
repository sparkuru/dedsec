package android.system;

import im.majo.ctos.host.HostLinux;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Host fixture substitute, deliberately restricted to IPv4 loopback. */
public final class Os {
    private Os() { }

    public static FileDescriptor socket(int domain, int type, int protocol) throws ErrnoException {
        return HostLinux.socket(domain, type, protocol);
    }

    public static void connect(FileDescriptor fd, InetAddress address, int port) throws ErrnoException {
        HostLinux.connect(fd, address.getAddress(), port);
    }

    public static int poll(StructPollfd[] descriptors, int timeout) throws ErrnoException {
        if (descriptors == null || descriptors.length != 1 || descriptors[0] == null)
            throw new IllegalArgumentException("one poll descriptor required");
        StructPollfd descriptor = descriptors[0];
        descriptor.revents = (short)HostLinux.poll(descriptor.fd, descriptor.events, timeout);
        return descriptor.revents == 0 ? 0 : 1;
    }

    public static SocketAddress getpeername(FileDescriptor fd) throws ErrnoException {
        byte[] peer = HostLinux.peer(fd);
        try {
            InetAddress address = InetAddress.getByAddress(Arrays.copyOf(peer, 4));
            int port = (Byte.toUnsignedInt(peer[4]) << 8) | Byte.toUnsignedInt(peer[5]);
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }

    public static void close(FileDescriptor fd) throws ErrnoException { HostLinux.closeDescriptor(fd); }
}
