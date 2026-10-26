package im.majo.ctos;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded DNS and nonblocking TCP acquisition; native sessions stay on their original owner. */
final class SshTcpConnector implements AutoCloseable {
    interface Clock { long now(); }
    interface Resolver { InetAddress[] resolve(String host, long deadline, AppSecrets.EffectGuard guard) throws Exception; }
    interface Socket extends AutoCloseable {
        void begin(InetAddress address, int port) throws IOException;
        boolean poll(int millis) throws IOException;
        void requirePeer(InetAddress address, int port) throws IOException;
        SshSocketOwnership detach() throws IOException;
        @Override void close() throws IOException;
    }
    interface Sockets { Socket open(InetAddress address) throws IOException; }
    interface SocketOpener { FileDescriptor open(int domain, int type, int protocol) throws IOException; }
    interface RawCloser { void close(FileDescriptor descriptor) throws IOException; }
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "ctos-ssh-dns"); thread.setDaemon(true); return thread;
            });
    static { DNS.allowCoreThreadTimeOut(true); }
    private final Clock clock;
    private final Resolver resolver;
    private final Sockets sockets;
    private Socket pending;
    private SshSocketOwnership ownership;

    SshTcpConnector() { this(SystemClock::elapsedRealtime, SshTcpConnector::resolve, OsSocket::new); }
    SshTcpConnector(Clock clock, Resolver resolver, Sockets sockets) {
        this.clock = clock; this.resolver = resolver; this.sockets = sockets;
    }

    SshSocketOwnership connect(SshPasswordProfile profile, long deadline, AppSecrets.EffectGuard guard)
            throws Exception {
        if (pending != null || ownership != null) throw new VaultException("BUSY", "SSH transport cleanup is pending");
        check(deadline, guard);
        InetAddress[] addresses;
        try { addresses = resolver.resolve(profile.host, deadline, guard); }
        catch (VaultException failure) { throw failure; }
        catch (Exception failure) { throw new VaultException("SSH_DNS", "The SSH host could not be resolved"); }
        check(deadline, guard);
        if (addresses == null || addresses.length == 0 || addresses.length > 16)
            throw new VaultException("SSH_DNS", "The SSH host could not be resolved");
        IOException last = null;
        for (InetAddress address : addresses) {
            check(deadline, guard);
            if (address == null) throw new VaultException("SSH_DNS", "The SSH host could not be resolved");
            try {
                pending = sockets.open(address);
                pending.begin(address, profile.port);
                while (true) {
                    check(deadline, guard);
                    if (!pending.poll((int)Math.min(50, deadline - clock.now()))) continue;
                    pending.requirePeer(address, profile.port);
                    check(deadline, guard);
                    ownership = pending.detach();
                    pending.close(); pending = null;
                    check(deadline, guard);
                    return ownership;
                }
            } catch (IOException failure) {
                last = failure;
                boolean detached = ownership != null;
                close();
                if (detached) throw new VaultException("SSH_CONNECT", "The SSH endpoint could not be reached");
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        if (last != null) throw new VaultException("SSH_CONNECT", "The SSH endpoint could not be reached");
        throw new VaultException("SSH_CONNECT", "The SSH endpoint could not be reached");
    }

    private void check(long deadline, AppSecrets.EffectGuard guard) throws VaultException {
        guard.check();
        if (clock.now() >= deadline) throw new VaultException("SSH_TIMED_OUT", "The SSH connection timed out");
    }

    @Override public void close() throws Exception {
        if (pending != null) { pending.close(); pending = null; }
        if (ownership != null) { ownership.close(); ownership = null; }
    }

    private static InetAddress[] resolve(String host, long deadline, AppSecrets.EffectGuard guard)
            throws Exception {
        Future<InetAddress[]> result;
        try { result = DNS.submit(() -> InetAddress.getAllByName(host)); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            throw new VaultException("SSH_DNS_BUSY", "A previous DNS lookup is still finishing; try again");
        }
        try {
            while (true) {
                guard.check();
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new VaultException("SSH_TIMED_OUT", "The SSH connection timed out");
                try { return result.get(Math.min(50, remaining), TimeUnit.MILLISECONDS); }
                catch (TimeoutException pending) { /* Check original authority before waiting again. */ }
            }
        } finally { if (!result.isDone()) result.cancel(true); }
    }

    static final class OsSocket implements Socket {
        // Linux UAPI values already supported by Android 28's public Os.socket;
        // the corresponding Java OsConstants fields were exposed only in API 29.
        // https://android.googlesource.com/platform/bionic/+/android-9.0.0_r61/libc/include/sys/socket.h
        // https://raw.githubusercontent.com/aosp-mirror/platform_bionic/android-9.0.0_r61/libc/kernel/uapi/asm-generic/fcntl.h
        private static final int SOCKET_CLOEXEC = 0x80000, SOCKET_NONBLOCK = 0x800;
        private FileDescriptor descriptor;
        private final RawCloser closer;
        private boolean immediate;
        OsSocket(InetAddress address) throws IOException {
            this(address, OsSocket::openRaw, OsSocket::closeRaw);
        }
        OsSocket(InetAddress address, SocketOpener opener, RawCloser closer) throws IOException {
            this.closer = java.util.Objects.requireNonNull(closer);
            descriptor = java.util.Objects.requireNonNull(opener).open(
                    address instanceof Inet6Address ? OsConstants.AF_INET6 : OsConstants.AF_INET,
                    OsConstants.SOCK_STREAM | SOCKET_CLOEXEC | SOCKET_NONBLOCK, OsConstants.IPPROTO_TCP);
            if (descriptor == null) throw new IOException("SSH socket unavailable");
        }
        private static FileDescriptor openRaw(int domain, int type, int protocol) throws IOException {
            try { return Os.socket(domain, type, protocol); }
            catch (ErrnoException failure) { throw new IOException("SSH socket unavailable", failure); }
        }
        private static void closeRaw(FileDescriptor fd) throws IOException {
            try { Os.close(fd); }
            catch (ErrnoException failure) { throw new IOException("SSH socket close failed", failure); }
        }
        OsSocket(FileDescriptor descriptor, RawCloser closer) {
            this.descriptor = java.util.Objects.requireNonNull(descriptor);
            this.closer = java.util.Objects.requireNonNull(closer);
        }
        @Override public void begin(InetAddress address, int port) throws IOException {
            try { Os.connect(descriptor, address, port); immediate = true; }
            catch (ErrnoException failure) {
                if (failure.errno != OsConstants.EINPROGRESS && failure.errno != OsConstants.EALREADY
                        && failure.errno != OsConstants.EINTR) throw new IOException("SSH connect failed", failure);
            }
        }
        @Override public boolean poll(int millis) throws IOException {
            if (immediate) return true;
            StructPollfd item = new StructPollfd(); item.fd = descriptor; item.events = (short)OsConstants.POLLOUT;
            try {
                int count = Os.poll(new StructPollfd[] {item}, Math.max(1, millis));
                if ((item.revents & OsConstants.POLLNVAL) != 0) throw new IOException("SSH socket unavailable");
                return count > 0;
            } catch (ErrnoException failure) {
                if (failure.errno == OsConstants.EINTR) return false;
                throw new IOException("SSH connect polling failed", failure);
            }
        }
        @Override public void requirePeer(InetAddress address, int port) throws IOException {
            try {
                SocketAddress raw = Os.getpeername(descriptor);
                if (!(raw instanceof InetSocketAddress)) throw new IOException("SSH peer mismatch");
                InetSocketAddress peer = (InetSocketAddress)raw;
                if (peer.getPort() != port || !address.equals(peer.getAddress())) throw new IOException("SSH peer mismatch");
                if (address instanceof Inet6Address && ((Inet6Address)address).getScopeId() != 0
                        && (!(peer.getAddress() instanceof Inet6Address)
                        || ((Inet6Address)address).getScopeId() != ((Inet6Address)peer.getAddress()).getScopeId()))
                    throw new IOException("SSH peer scope mismatch");
            } catch (ErrnoException failure) { throw new IOException("SSH connection failed", failure); }
        }
        @Override public SshSocketOwnership detach() throws IOException {
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor)) {
                // API 28 dup shares O_NONBLOCK but does not preserve FD_CLOEXEC. Our JNI
                // validates the connected socket and sets it while this PFD still owns it.
                SshNativeSession.prepareTcpDescriptor(duplicate.getFd());
                SshSocketOwnership result = new SshSocketOwnership(duplicate.getFd(), fd -> ParcelFileDescriptor.adoptFd(fd).close());
                int detached = duplicate.detachFd();
                if (detached != result.descriptor()) {
                    ParcelFileDescriptor.adoptFd(detached).close(); throw new IOException("SSH descriptor unavailable");
                }
                return result;
            }
        }
        @Override public synchronized void close() throws IOException {
            FileDescriptor owned = descriptor;
            descriptor = null;
            if (owned == null) return;
            // Even an EINTR/error may follow kernel consumption. Never retry a reused number.
            closer.close(owned);
        }
    }
}
