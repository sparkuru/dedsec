package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

/** Synthetic Android ARM JNI session-owner gate; no network, account or vault. */
public final class SshNativeSessionPlatformTest {
    private static final String METHOD =
            "im.majo.ctos.SshNativeSessionPlatformTest#syntheticNativeSessionHandle";
    private static final String CONNECTION = "session-platform";
    private static final String TARGET = "target:synthetic@127.0.0.1:22";
    private static final String HOST = "127.0.0.1";
    private static final int STEP_BUDGET = 3;

    private static final class SocketPair implements AutoCloseable {
        final int nativeFd;
        final FileDescriptor peer;
        boolean transferred;

        SocketPair(int nativeFd, FileDescriptor peer) {
            this.nativeFd = nativeFd;
            this.peer = peer;
        }

        static SocketPair open() throws Exception {
            FileDescriptor[] pair = new FileDescriptor[]{new FileDescriptor(), new FileDescriptor()};
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, pair[0], pair[1]);
            ParcelFileDescriptor duplicate = null;
            int raw = -1;
            boolean retained = false;
            try {
                duplicate = ParcelFileDescriptor.dup(pair[0]);
                FileDescriptor nativeDescriptor = duplicate.getFileDescriptor();
                int descriptorFlags = Os.fcntlInt(nativeDescriptor, OsConstants.F_GETFD, 0);
                Os.fcntlInt(nativeDescriptor, OsConstants.F_SETFD,
                        descriptorFlags | OsConstants.FD_CLOEXEC);
                int statusFlags = Os.fcntlInt(nativeDescriptor, OsConstants.F_GETFL, 0);
                Os.fcntlInt(nativeDescriptor, OsConstants.F_SETFL,
                        statusFlags | OsConstants.O_NONBLOCK);
                int peerFlags = Os.fcntlInt(pair[1], OsConstants.F_GETFL, 0);
                Os.fcntlInt(pair[1], OsConstants.F_SETFL,
                        peerFlags | OsConstants.O_NONBLOCK);
                raw = duplicate.detachFd();
                duplicate = null;
                Os.close(pair[0]);
                pair[0] = null;
                FileDescriptor peer = pair[1];
                pair[1] = null;
                retained = true;
                return new SocketPair(raw, peer);
            } finally {
                try {
                    if (duplicate != null) duplicate.close();
                } finally {
                    if (pair[0] != null) closeDescriptor(pair[0]);
                    if (!retained && pair[1] != null) closeDescriptor(pair[1]);
                    if (!retained && raw >= 0) closeRaw(raw);
                }
            }
        }

        @Override public void close() {
            if (!transferred) closeRaw(nativeFd);
            closeDescriptor(peer);
        }

        private static void closeRaw(int fd) {
            try (ParcelFileDescriptor ignored = ParcelFileDescriptor.adoptFd(fd)) {
                // The adopted descriptor is closed by the resource wrapper.
            } catch (IOException | RuntimeException ignored) { }
        }

        private static void closeDescriptor(FileDescriptor descriptor) {
            if (descriptor == null) return;
            try { Os.close(descriptor); }
            catch (ErrnoException | RuntimeException ignored) { }
        }
    }

    private static SshPublicFileLease view(long deadline, byte[] hostKeyBlob) throws Exception {
        VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 73,
                new byte[16], new byte[32], new byte[0]);
        try {
            VaultOperation operation = new VaultOperation(VaultOperation.Consumer.SSH,
                    VaultOperation.Purpose.SSH_FILES, "ssh/config", "", CONNECTION,
                    envelope, 7, deadline);
            VaultManifest.FileObject config = new VaultManifest.FileObject(
                    VaultManifest.FileType.CONFIG, "root", "ssh/config", "config",
                    "Host synthetic\n".getBytes(StandardCharsets.US_ASCII),
                    VaultCollections.list("ssh/known_hosts"));
            String knownHostsLine = HOST + " ssh-ed25519 "
                    + Base64.getEncoder().encodeToString(hostKeyBlob) + "\n";
            VaultManifest.FileObject knownHosts = new VaultManifest.FileObject(
                    VaultManifest.FileType.KNOWN_HOSTS, "trust", "ssh/known_hosts", "trust",
                    knownHostsLine.getBytes(StandardCharsets.US_ASCII),
                    VaultCollections.list());
            return SshPublicFileLease.create(new SshFileSnapshot(operation,
                    VaultCollections.list(config, knownHosts)));
        } finally {
            VaultCodec.wipe(envelope.salt);
            VaultCodec.wipe(envelope.nonce);
            VaultCodec.wipe(envelope.header);
            VaultCodec.wipe(envelope.ciphertext);
        }
    }

    private static byte[] hostKeyBlob() throws IOException {
        byte[] publicKey = new byte[]{
                (byte)0xd7, (byte)0x5a, (byte)0x98, 0x01, (byte)0x82, (byte)0xb1,
                0x0a, (byte)0xb7, (byte)0xd5, 0x4b, (byte)0xfe, (byte)0xd3,
                (byte)0xc9, 0x64, 0x07, 0x3a, 0x0e, (byte)0xe1, 0x72, (byte)0xf3,
                (byte)0xda, (byte)0xa6, 0x23, 0x25, (byte)0xaf, 0x02, 0x1a,
                0x68, (byte)0xf7, 0x07, 0x51, 0x1a};
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(51);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(11);
            output.write("ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
            output.writeInt(publicKey.length);
            output.write(publicKey);
            output.flush();
            return bytes.toByteArray();
        } finally {
            Arrays.fill(publicKey, (byte)0);
        }
    }

    private static SshNativeRoleBootstrap.Issued issueRole(
            SshRoleRegistry registry, SshRoleContext.PublicView view,
            SshRoleContext.Clock clock, long deadline) throws Exception {
        byte[] nonce = filled(32, (byte)3);
        byte[] binding = filled(32, (byte)4);
        byte[] wire = null;
        try {
            SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(
                    SshRoleContext.Program.CLIENT, Process.myPid(), 0, CONNECTION,
                    TARGET, HOST, 22, "session-view", nonce, binding, deadline);
            wire = SshNativeRoleBootstrap.open(bootstrap);
            return SshNativeRoleBootstrap.issue(wire, registry, view, clock,
                    SshNativeRoleBootstrap.productionOwnership());
        } finally {
            VaultCodec.wipe(nonce);
            VaultCodec.wipe(binding);
            VaultCodec.wipe(wire);
        }
    }

    private static byte[] filled(int length, byte value) {
        byte[] result = new byte[length];
        Arrays.fill(result, value);
        return result;
    }

    private static boolean retryable(ErrnoException failure) {
        return failure.errno == OsConstants.EAGAIN
                || failure.errno == OsConstants.EINTR;
    }

    private static int writePeer(FileDescriptor peer, byte[] bytes, int offset, int count)
            throws Exception {
        try {
            return Os.write(peer, bytes, offset, count);
        } catch (ErrnoException failure) {
            if (retryable(failure)) return 0;
            throw failure;
        }
    }

    private static int readPeer(FileDescriptor peer, byte[] bytes, int offset)
            throws Exception {
        assertTrue("Bounded peer output buffer", offset < bytes.length);
        try {
            int read = Os.read(peer, bytes, offset, bytes.length - offset);
            assertTrue("Client must keep its socket open", read > 0);
            return read;
        } catch (ErrnoException failure) {
            if (retryable(failure)) return 0;
            throw failure;
        }
    }

    private static void assertTransportStep(SshNativeSession.StepResult step) {
        assertTrue(step.bytesRead >= 0 && step.bytesRead <= STEP_BUDGET);
        assertTrue(step.bytesWritten >= 0 && step.bytesWritten <= STEP_BUDGET);
        assertEquals(step.bytesRead, step.inputAppended);
        assertEquals(0, step.packetType);
        assertFalse(step.complete);
        if (step.readWouldBlock) assertEquals(0, step.bytesRead);
        if (step.writeWouldBlock) {
            assertEquals(0, step.bytesWritten);
            assertFalse(step.partialWrite);
        }
        if (step.partialWrite) assertTrue(step.bytesWritten > 0);
    }

    private static void assertFragmentedTransport(SshNativeSession session, SocketPair sockets,
            SshNativeSession.StepResult initial) throws Exception {
        byte[] banner = "SSH-2.0-synthetic-server\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] peerOutput = new byte[16 * 1024];
        int sent = 0;
        int received = 0;
        int appended = 0;
        int written = initial.bytesWritten;
        int peerRead = 0;
        int inputTurns = 0;
        int bannerEnd = 0;
        int packetLength = 0;
        boolean partialWrite = initial.partialWrite;
        boolean completePacket = false;
        boolean drained = false;
        try {
            assertTransportStep(initial);
            for (int turn = 0; turn < 2000; turn++) {
                if (sent < banner.length) {
                    sent += writePeer(sockets.peer, banner, sent,
                            Math.min(STEP_BUDGET, banner.length - sent));
                }
                SshNativeSession.StepResult step = session.step();
                assertTransportStep(step);
                received += step.bytesRead;
                appended += step.inputAppended;
                assertTrue("Client cannot read unsent banner bytes", received <= sent);
                if (step.bytesRead > 0) inputTurns++;
                written += step.bytesWritten;
                assertTrue(written <= peerOutput.length);
                if (step.partialWrite) {
                    partialWrite = true;
                }
                peerRead += readPeer(sockets.peer, peerOutput, peerRead);
                assertTrue("Peer bytes must match reported writes", peerRead <= written);
                if (bannerEnd == 0) {
                    for (int index = 0; index + 1 < peerRead; index++) {
                        if (peerOutput[index] == '\r' && peerOutput[index + 1] == '\n') {
                            bannerEnd = index + 2;
                            break;
                        }
                    }
                }
                if (bannerEnd > 0 && peerRead >= bannerEnd + 4) {
                    packetLength = ((peerOutput[bannerEnd] & 0xff) << 24)
                            | ((peerOutput[bannerEnd + 1] & 0xff) << 16)
                            | ((peerOutput[bannerEnd + 2] & 0xff) << 8)
                            | (peerOutput[bannerEnd + 3] & 0xff);
                    assertTrue("Bounded initial KEX packet", packetLength >= 16
                            && packetLength <= peerOutput.length - bannerEnd - 4);
                    completePacket = peerRead >= bannerEnd + 4 + packetLength;
                }
                if (completePacket && peerRead == written && !step.partialWrite
                        && !step.writeWouldBlock) {
                    drained = true;
                    break;
                }
            }
            assertTrue("Transport must drain within the turn bound", drained);
            assertEquals(banner.length, sent);
            assertEquals(sent, received);
            assertEquals(received, appended);
            assertTrue("Banner must span multiple native reads", inputTurns > 1);
            assertTrue("Small write budget must leave queued output", partialWrite);
            assertTrue("Peer must receive the complete initial KEX packet", completePacket);
            assertEquals(written, peerRead);
            assertEquals(bannerEnd + 4 + packetLength, peerRead);
            assertTrue(new String(peerOutput, 0, bannerEnd, StandardCharsets.US_ASCII)
                    .startsWith("SSH-2.0-"));
            int padding = peerOutput[bannerEnd + 4] & 0xff;
            assertTrue(padding >= 4 && packetLength > padding + 1);
            assertEquals("Initial packet uses 8-byte alignment", 0, (packetLength + 4) % 8);
            assertEquals(20, peerOutput[bannerEnd + 5] & 0xff);
        } finally {
            VaultCodec.wipe(banner);
            VaultCodec.wipe(peerOutput);
        }
    }

    private static void assertTerminalStep(SshNativeSession session) throws Exception {
        try {
            session.step();
            fail("Peer EOF must reject further session progress");
        } catch (VaultException failure) {
            assertEquals("SSH_SESSION_STEP", failure.code);
        }
    }

    @Test public void syntheticNativeSessionHandle() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String optIn = arguments.getString("ctosSshNativeSessionPlatformProbe");
        assertTrue("Strict JNI session opt-in", optIn == null || "true".equals(optIn));
        Assume.assumeTrue("Scoped JNI session execution required", "true".equals(optIn));
        assertTrue("Only the named JNI session method", METHOD.equals(arguments.getString("class")));
        SshMemoryPlatformTest.guard();

        runSyntheticSession(false);
        runSyntheticSession(true);
    }

    private static void runSyntheticSession(boolean peerEof) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        SshRoleContext.Clock clock = SystemClock::elapsedRealtime;
        SshPublicFileLease view = null;
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = null;
        SshNativeCallbackAdapter callback = null;
        SshNativeSession session = null;
        SocketPair sockets = null;
        byte[] key = null;
        byte[] fingerprint = null;
        try {
            key = hostKeyBlob();
            view = view(deadline, key);
            issued = issueRole(registry, view, clock, deadline);
            callback = SshNativeCallbackAdapter.bind(issued, registry,
                    new SshHostKeyAuthority(), clock);
            sockets = SocketPair.open();
            fingerprint = VaultCodec.digest(key);
            session = SshNativeSession.open(callback, view, sockets.nativeFd, key,
                    fingerprint, "session-candidate", STEP_BUDGET);
            sockets.transferred = true;
            SshNativeSession.StepResult emptyPeerStep = session.step();
            assertTransportStep(emptyPeerStep);
            assertEquals(0, emptyPeerStep.bytesRead);
            assertEquals(0, emptyPeerStep.inputAppended);
            assertTrue(emptyPeerStep.readWouldBlock);
            if (peerEof) {
                Os.shutdown(sockets.peer, OsConstants.SHUT_WR);
                assertTerminalStep(session);
                assertTerminalStep(session);
                assertFalse("Terminal step must still require owner cleanup", session.closed());
                assertTrue("EOF must not revoke the managed view", view.active());
            } else {
                assertFragmentedTransport(session, sockets, emptyPeerStep);
                session.verifyHostKey(key);
                assertTrue("Native open must not revoke the view", view.active());
                view.revoke();
                assertFalse("Explicit revoke must be visible to Java", view.active());
            }
            session.close();
            assertTrue("Native session tombstone must close", session.closed());
            assertFalse("Session cleanup must release its managed view", view.active());
            session.close();
        } finally {
            try {
                if (session != null && !session.closed()) session.close();
            } finally {
                try {
                    if (issued != null && issued.role.active()) {
                        if (callback != null) callback.close();
                        else SshNativeRoleBootstrap.release(issued, registry,
                                SshNativeRoleBootstrap.productionRelease());
                    }
                } finally {
                    try {
                        if (view != null) view.close();
                    } finally {
                        try {
                            if (sockets != null) sockets.close();
                        } finally {
                            try {
                                registry.close();
                            } finally {
                                VaultCodec.wipe(key);
                                VaultCodec.wipe(fingerprint);
                                SshMemoryPlatformTest.guard();
                            }
                        }
                    }
                }
            }
        }
    }
}
