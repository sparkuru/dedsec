package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Host/JVM contract checks for the synchronous JNI callback boundary. These
 * tests use an injected native lifecycle double; they do not load JNI, start
 * OpenSSH, open a socket, or access the vault.
 */
public final class SshNativeCallbackAdapterTest {
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final String TARGET = "target:user@example.invalid:22";

    private static final class Clock implements SshRoleContext.Clock {
        long value = 1_000;
        @Override public long now() { return value; }
    }

    private static final class View implements SshRoleContext.PublicView {
        boolean active = true;
        @Override public boolean active() { return active; }
    }

    private static final class NativeDouble implements SshNativeCallbackAdapter.NativeBinding {
        long next = 700;
        int openCalls;
        int cancelCalls;
        int closeCalls;
        boolean failClose;

        @Override public long open(SshNativeCallbackAdapter target, long role, int ownerUid,
                long deadline) {
            assertNotNull(target);
            assertTrue(role > 0 && ownerUid >= 0 && deadline > 0);
            openCalls++;
            return next++;
        }

        @Override public void cancel(long callback) throws IOException {
            assertTrue(callback > 0);
            cancelCalls++;
        }

        @Override public void close(long callback) throws IOException {
            assertTrue(callback > 0);
            closeCalls++;
            if (failClose) throw new IOException("fixture native cleanup failure");
        }
    }

    private static final class RoleRelease implements SshNativeRoleBootstrap.NativeRelease {
        int calls;
        boolean fail;

        @Override public void release(long token, int ownerUid) throws IOException {
            assertTrue(token > 0 && ownerUid >= 0);
            calls++;
            if (fail) throw new IOException("fixture role cleanup failure");
        }
    }

    private static final class Setup {
        final Clock clock;
        final SshRoleRegistry registry;
        final SshNativeRoleBootstrap.Issued issued;
        final NativeDouble nativeDouble;
        final RoleRelease roleRelease;
        final SshNativeCallbackAdapter adapter;

        Setup(SshHostKeyAuthority authority) throws Exception {
            clock = new Clock();
            registry = new SshRoleRegistry(clock);
            issued = SshNativeRoleBootstrap.issue(record(31, 42, 1001, 0), registry,
                    new View(), clock, ignored -> true);
            nativeDouble = new NativeDouble();
            roleRelease = new RoleRelease();
            adapter = SshNativeCallbackAdapter.bind(issued, registry, authority, clock,
                    nativeDouble, roleRelease);
        }

        void close() throws Exception {
            try { adapter.close(); }
            finally { registry.close(); }
        }
    }

    @Test public void authenticationSelectionRequiresManagedProviderAndOwner() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            SshAuthenticationSelection selection = SshAuthenticationSelection.password(setup.issued.role,
                    "explicit-user", "password", setup.clock);
            AtomicReference<String> code = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { setup.adapter.selectAuthentication(selection); }
                catch (VaultException failure) { code.set(failure.code); }
            });
            thread.start();
            thread.join();
            assertEquals("SSH_CALLBACK_THREAD", code.get());
            try { setup.adapter.selectAuthentication(selection); fail("missing provider"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            // Failed setup does not poison the existing legacy host/KEX path.
            assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertTrue(setup.adapter.onNativeKexComplete(1, filled(32, 7)));
        } finally { setup.close(); }
    }

    @Test public void boundThreadCanContinueFromKnownHostProofToKexProof() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            assertEquals(Thread.currentThread(), setup.adapter.eventLoopThread());
            assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertNotNull(setup.adapter.hostProof());
            assertTrue(setup.adapter.onNativeKexComplete(1, filled(32, 7)));
            assertNotNull(setup.adapter.kexProof());
        } finally {
            setup.close();
        }
        assertEquals(1, setup.nativeDouble.openCalls);
        assertEquals(1, setup.nativeDouble.closeCalls);
        assertEquals(1, setup.roleRelease.calls);
    }

    @Test public void crossThreadCallbackIsRejectedWithoutTouchingJavaAuthority() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            AtomicBoolean result = new AtomicBoolean(true);
            AtomicReference<String> code = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                result.set(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                        "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
                code.set(setup.adapter.lastFailureCode());
            });
            thread.start();
            thread.join();
            assertFalse(result.get());
            assertEquals("SSH_CALLBACK_THREAD", code.get());
            assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
        } finally {
            setup.close();
        }
    }

    @Test public void crossThreadCloseIsRejectedAndEventLoopCanRetry() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            AtomicReference<String> code = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    setup.adapter.close();
                    fail("cross-thread close must be rejected");
                } catch (VaultException failure) {
                    code.set(failure.code);
                }
            });
            thread.start();
            thread.join();
            assertEquals("SSH_CALLBACK_THREAD", code.get());
            assertEquals(0, setup.nativeDouble.closeCalls);
            assertTrue(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
        } finally {
            setup.close();
        }
    }

    @Test public void reentrantCallbackIsRejectedAndPoisonsTheBinding() throws Exception {
        SshNativeCallbackAdapter[] holder = new SshNativeCallbackAdapter[1];
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            assertFalse(holder[0].onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
        });
        Setup setup = new Setup(authority);
        holder[0] = setup.adapter;
        byte[] base = digest("known");
        byte[] content = bytes("example.invalid ssh-ed25519 AAAA\n");
        try (SshTrustProposal proposal = new SshTrustProposal(setup.issued.role, "known_hosts", "trust", TARGET,
                "connection-31", "example.invalid", 22, "ssh-ed25519", FINGERPRINT, base, content)) {
            assertFalse(setup.adapter.onNativeHostKeyForTest(SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(content), proposal));
            assertEquals("SSH_CALLBACK_REENTRANT", setup.adapter.lastFailureCode());
        } finally {
            setup.close();
        }
    }

    @Test public void deadlineCancelCloseAndLateCallbacksFailClosed() throws Exception {
        Setup cancelled = new Setup(new SshHostKeyAuthority());
        try {
            cancelled.adapter.cancel();
            assertEquals(1, cancelled.nativeDouble.cancelCalls);
            assertFalse(cancelled.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertEquals("SSH_CALLBACK_CANCELLED", cancelled.adapter.lastFailureCode());
        } finally {
            cancelled.close();
        }

        Setup expired = new Setup(new SshHostKeyAuthority());
        try {
            expired.clock.value = expired.issued.role.deadline;
            assertFalse(expired.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertEquals("SSH_ROLE_EXPIRED", expired.adapter.lastFailureCode());
        } finally {
            expired.close();
        }

        Setup closed = new Setup(new SshHostKeyAuthority());
        closed.adapter.close();
        assertFalse(closed.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
        assertEquals("SSH_CALLBACK_CLOSED", closed.adapter.lastFailureCode());
        closed.registry.close();
    }

    @Test public void everyRejectedHostClassificationPoisonsOnlyItsBinding() throws Exception {
        int[] classifications = {
                SshNativeCallbackAdapter.HOST_NEW,
                SshNativeCallbackAdapter.HOST_CHANGED,
                SshNativeCallbackAdapter.HOST_REVOKED,
                SshNativeCallbackAdapter.HOST_CERTIFICATE,
                SshNativeCallbackAdapter.HOST_INVALID
        };
        String[] codes = {
                "SSH_HOST_KEY_REVIEW",
                "SSH_HOST_KEY_CHANGED",
                "SSH_HOST_KEY_REVOKED",
                "SSH_HOST_KEY_CERTIFICATE",
                "SSH_HOST_KEY_INVALID"
        };
        for (int index = 0; index < classifications.length; index++) {
            Setup setup = new Setup(new SshHostKeyAuthority());
            try {
                assertFalse(setup.adapter.onNativeHostKey(classifications[index],
                        "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
                assertEquals(codes[index], setup.adapter.lastFailureCode());
                assertEquals(null, setup.adapter.hostProof());
                assertFalse(setup.adapter.onNativeKexComplete(1, filled(32, 3)));
                assertEquals(codes[index], setup.adapter.lastFailureCode());
            } finally {
                setup.close();
            }
        }
    }

    @Test public void javaExceptionsMapToStableFailureWithoutLeakingMessage() throws Exception {
        SshHostKeyAuthority authority = new SshHostKeyAuthority(review -> {
            throw new IllegalStateException("secret exception text");
        });
        Setup setup = new Setup(authority);
        byte[] base = digest("known");
        byte[] content = bytes("example.invalid ssh-ed25519 AAAA\n");
        try (SshTrustProposal proposal = new SshTrustProposal(setup.issued.role, "known_hosts", "trust", TARGET,
                "connection-31", "example.invalid", 22, "ssh-ed25519", FINGERPRINT, base, content)) {
            assertFalse(setup.adapter.onNativeHostKeyForTest(SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, base, digest(content), proposal));
            assertEquals("SSH_CALLBACK_JAVA_EXCEPTION", setup.adapter.lastFailureCode());
            assertFalse(setup.adapter.lastFailureCode().contains("secret"));
        } finally {
            setup.close();
        }
    }

    @Test public void newHostWithoutManagedProviderFailsClosed() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            assertFalse(setup.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_NEW,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertEquals("SSH_HOST_KEY_REVIEW", setup.adapter.lastFailureCode());
            assertEquals(null, setup.adapter.hostProof());
        } finally {
            setup.close();
        }
    }

    @Test public void credentialCallbackCannotReachReceiverBeforeOrAfterKex() throws Exception {
        Setup before = new Setup(new SshHostKeyAuthority());
        AtomicBoolean receiver = new AtomicBoolean();
        try {
            assertEquals(null, before.adapter.onNativeCredentialBytes(
                    SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
            assertEquals("SSH_KEX_REQUIRED", before.adapter.lastFailureCode());
            before.close();
            before = null;

            before = new Setup(new SshHostKeyAuthority());
            assertFalse(before.adapter.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
            assertEquals("SSH_KEX_REQUIRED", before.adapter.lastFailureCode());
            assertFalse(receiver.get());
        } finally {
            if (before != null) before.close();
        }

        Setup after = new Setup(new SshHostKeyAuthority());
        try {
            assertTrue(after.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertTrue(after.adapter.onNativeKexComplete(1, filled(32, 9)));
            assertFalse(after.adapter.onNativeCredential(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
            assertEquals("SSH_CREDENTIAL_PROVIDER", after.adapter.lastFailureCode());
            assertFalse(receiver.get());
        } finally {
            after.close();
        }

        Setup bytesAfter = new Setup(new SshHostKeyAuthority());
        try {
            assertTrue(bytesAfter.adapter.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN,
                    "ssh-ed25519", FINGERPRINT, digest("known"), digest("replacement")));
            assertTrue(bytesAfter.adapter.onNativeKexComplete(1, filled(32, 9)));
            assertEquals(null, bytesAfter.adapter.onNativeCredentialBytes(
                    SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
            assertEquals("SSH_CREDENTIAL_PROVIDER", bytesAfter.adapter.lastFailureCode());
        } finally {
            bytesAfter.close();
        }
    }

    @Test public void nativeCleanupFailureDoesNotDeregisterRoleAndCanRetry() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            setup.nativeDouble.failClose = true;
            try {
                setup.adapter.close();
                fail("native callback cleanup failure must remain visible");
            } catch (VaultException failure) {
                assertEquals("SSH_CALLBACK_CLEANUP", failure.code);
            }
            assertEquals(1, setup.registry.size());
            assertTrue(setup.issued.role.active());
            assertEquals(0, setup.roleRelease.calls);

            setup.nativeDouble.failClose = false;
            setup.adapter.close();
            assertEquals(0, setup.registry.size());
            assertFalse(setup.issued.role.active());
            assertEquals(1, setup.roleRelease.calls);
        } finally {
            setup.registry.close();
        }
    }

    @Test public void selectedCallbackWithoutSelectionFailsAndNativeCleanupCanRetry() throws Exception {
        Setup setup = new Setup(new SshHostKeyAuthority());
        try {
            assertEquals(null, setup.adapter.onNativeSelectedCredentialBytes(1, filled(32, 3),
                    1, filled(32, 7)));
            assertEquals("SSH_AUTH_SELECTION", setup.adapter.lastFailureCode());
            setup.nativeDouble.failClose = true;
            try { setup.adapter.close(); fail("cleanup must remain retryable"); }
            catch (VaultException failure) { assertEquals("SSH_CALLBACK_CLEANUP", failure.code); }
            assertEquals(1, setup.registry.size());
            setup.nativeDouble.failClose = false;
            setup.adapter.close();
            assertEquals(2, setup.nativeDouble.closeCalls);
            assertEquals(1, setup.roleRelease.calls);
            assertEquals(0, setup.registry.size());
        } finally { setup.registry.close(); }
    }

    private static byte[] record(long token, int uid, int pid, int hop) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x43535242);
        output.writeByte(1);
        output.writeByte(1);
        output.writeShort(0);
        output.writeLong(token);
        output.writeInt(pid);
        output.writeInt(uid);
        output.writeInt(hop);
        output.writeInt(22);
        output.writeLong(31_000);
        output.write(filled(32, 4));
        output.write(filled(32, 5));
        text(output, "connection-31");
        text(output, TARGET);
        text(output, "example.invalid");
        text(output, "view-31");
        output.flush();
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static byte[] digest(String value) { return VaultCodec.digest(bytes(value)); }

    private static byte[] digest(byte[] value) { return VaultCodec.digest(value); }

    private static byte[] filled(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte)value);
        return result;
    }
}
