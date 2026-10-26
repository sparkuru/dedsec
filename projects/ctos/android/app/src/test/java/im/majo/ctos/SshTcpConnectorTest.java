package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Transport boundary failures exercise original deadline, selected peer, FD ownership and retry. */
public final class SshTcpConnectorTest {
    private static final class Clock implements SshTcpConnector.Clock {
        long now = 1000;
        @Override public long now() { return now; }
    }
    private static final class Socket implements SshTcpConnector.Socket {
        int polls, closes, detaches, descriptorCloses;
        boolean ready = true, wrongPeer, closeFails, rawClosed;
        Runnable onPoll, afterDetach;
        InetAddress chosen;
        int port;
        @Override public void begin(InetAddress address, int port) { chosen = address; this.port = port; }
        @Override public boolean poll(int millis) {
            assertTrue(millis > 0 && millis <= 50); polls++;
            if (onPoll != null) onPoll.run(); return ready;
        }
        @Override public void requirePeer(InetAddress address, int port) throws IOException {
            assertEquals(chosen, address); assertEquals(this.port, port);
            if (wrongPeer) throw new IOException("Synthetic wrong actual peer");
        }
        @Override public SshSocketOwnership detach() {
            detaches++;
            SshSocketOwnership result = new SshSocketOwnership(37, fd -> { assertEquals(37, fd); descriptorCloses++; });
            if (afterDetach != null) afterDetach.run(); return result;
        }
        @Override public void close() throws IOException {
            if (rawClosed) return;
            rawClosed = true; closes++;
            if (closeFails) throw new IOException("Synthetic consumed original close failure");
        }
    }
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (VaultException failure) { assertEquals(expected, failure.code); }
    }
    private static SshPasswordProfile profile() throws Exception { return new SshPasswordProfile("fixture.invalid", 2222, "user"); }
    private static InetAddress address() throws Exception { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); }
    private static SshTcpConnector connector(Clock clock, Socket socket) throws Exception {
        InetAddress address = address();
        return new SshTcpConnector(clock, (host, deadline, guard) -> new InetAddress[]{address}, peer -> socket);
    }

    @Test public void verifiedPeerTransfersOneDescriptorAndOriginalClosesWithoutClosingTransfer() throws Exception {
        Clock clock = new Clock(); Socket socket = new Socket();
        try (SshTcpConnector connector = connector(clock, socket)) {
            SshSocketOwnership owned = connector.connect(profile(), 60000, () -> { });
            assertEquals(37, owned.descriptor()); assertEquals(1, socket.closes);
            assertEquals(1, socket.detaches); assertEquals(0, socket.descriptorCloses);
        }
        assertEquals(1, socket.descriptorCloses);
    }
    @Test public void writableButWrongActualPeerNeverTransfersDescriptor() throws Exception {
        Clock clock = new Clock(); Socket socket = new Socket(); socket.wrongPeer = true;
        try (SshTcpConnector connector = connector(clock, socket)) {
            code("SSH_CONNECT", () -> connector.connect(profile(), 60000, () -> { }));
            assertEquals(0, socket.detaches); assertEquals(1, socket.closes);
        }
    }
    @Test public void originalDeadlineAndCancelAreCheckedEveryPollSlice() throws Exception {
        Clock clock = new Clock(); Socket socket = new Socket(); socket.ready = false;
        socket.onPoll = () -> clock.now += 50;
        try (SshTcpConnector connector = connector(clock, socket)) {
            code("SSH_TIMED_OUT", () -> connector.connect(profile(), 1100, () -> { }));
            assertEquals(2, socket.polls); assertEquals(1, socket.closes); assertEquals(0, socket.detaches);
        }
        AtomicBoolean cancelled = new AtomicBoolean(); socket = new Socket(); socket.ready = false;
        socket.onPoll = () -> cancelled.set(true);
        try (SshTcpConnector connector = connector(clock, socket)) {
            code("SSH_CANCELLED", () -> connector.connect(profile(), 60000, () -> {
                if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic cancellation");
            }));
            assertEquals(1, socket.closes); assertEquals(0, socket.detaches);
        }
    }
    @Test public void lateResolverCannotOpenSocketAndLateDetachIsClosed() throws Exception {
        Clock clock = new Clock(); AtomicBoolean cancelled = new AtomicBoolean(); AtomicInteger opens = new AtomicInteger();
        SshTcpConnector connector = new SshTcpConnector(clock, (host, deadline, guard) -> {
            cancelled.set(true); return new InetAddress[]{address()};
        }, peer -> { opens.incrementAndGet(); return new Socket(); });
        code("SSH_CANCELLED", () -> connector.connect(profile(), 60000, () -> {
            if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic cancellation");
        }));
        assertEquals(0, opens.get()); connector.close();
        cancelled.set(false); Socket socket = new Socket(); socket.afterDetach = () -> cancelled.set(true);
        try (SshTcpConnector late = connector(clock, socket)) {
            code("SSH_CANCELLED", () -> late.connect(profile(), 60000, () -> {
                if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "Synthetic cancellation");
            }));
            assertEquals(1, socket.descriptorCloses);
        }
    }
    @Test public void originalCloseErrorStillClosesDetachedCopyWithoutRetryingConsumedOriginal() throws Exception {
        Clock clock = new Clock(); Socket socket = new Socket(); socket.closeFails = true;
        InetAddress address = address(); AtomicInteger opens = new AtomicInteger();
        SshTcpConnector connector = new SshTcpConnector(clock,
                (host, deadline, guard) -> new InetAddress[]{address, address}, peer -> {
                    opens.incrementAndGet(); return socket;
                });
        code("SSH_CONNECT", () -> connector.connect(profile(), 60000, () -> { }));
        assertEquals(1, socket.closes); assertEquals(1, socket.descriptorCloses);
        connector.close(); connector.close();
        assertEquals(1, socket.closes); assertEquals(1, socket.descriptorCloses); assertEquals(1, opens.get());
    }
    @Test public void actualOsSocketCloseConsumesOwnershipBeforeCallbackErrorAndNumericReuse() throws Exception {
        FileDescriptor original = new FileDescriptor();
        AtomicInteger kernelCloses = new AtomicInteger();
        AtomicBoolean numberReused = new AtomicBoolean();
        SshTcpConnector.OsSocket socket = new SshTcpConnector.OsSocket(original, fd -> {
            assertSame(original, fd);
            assertFalse("A reused numeric descriptor must never reach another raw close", numberReused.get());
            kernelCloses.incrementAndGet();
            numberReused.set(true);
            throw new IOException("Synthetic EINTR after kernel consumed the original descriptor");
        });
        try { socket.close(); fail(); }
        catch (IOException expected) { }
        socket.close(); socket.close();
        assertTrue(numberReused.get()); assertEquals(1, kernelCloses.get());
    }
    @Test public void actualOsSocketRequestsAtomicNonblockingAndCloseOnExecFlagsBeforeConnect() throws Exception {
        FileDescriptor original = new FileDescriptor(); AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        SshTcpConnector.OsSocket socket = new SshTcpConnector.OsSocket(address(), (domain, type, protocol) -> {
            // These are Linux socket ABI bits, available before their API 29 Java constants.
            assertEquals(0x80800, type & 0x80800); opens.incrementAndGet(); return original;
        }, fd -> { assertSame(original, fd); closes.incrementAndGet(); });
        assertEquals(1, opens.get()); assertEquals(0, closes.get());
        socket.close(); socket.close(); assertEquals(1, closes.get());
    }
    @Test public void failedAtomicSocketCreationNeverCreatesCallerOwnership() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        try {
            new SshTcpConnector.OsSocket(address(), (domain, type, protocol) -> {
                throw new IOException("Synthetic socket acquisition failure");
            }, fd -> closes.incrementAndGet());
            fail("A failed acquisition must not create a socket owner");
        } catch (IOException expected) { }
        assertEquals(0, closes.get());
    }
    @Test public void actualOsSocketCloseAlsoConsumesOwnershipBeforeUncheckedFailure() throws Exception {
        AtomicInteger kernelCloses = new AtomicInteger();
        SshTcpConnector.OsSocket socket = new SshTcpConnector.OsSocket(new FileDescriptor(), fd -> {
            kernelCloses.incrementAndGet(); throw new AssertionError("Synthetic close callback failure");
        });
        try { socket.close(); fail(); }
        catch (AssertionError expected) { }
        socket.close(); assertEquals(1, kernelCloses.get());
    }
}
