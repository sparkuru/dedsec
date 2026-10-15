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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * JVM regression seam for the event-loop session owner. No JNI, socket, vault,
 * device, OpenSSH, or external endpoint is used here.
 */
public final class SshNativeSessionTest {
    private static final byte[] HOST_KEY = bytes("ssh-ed25519-public-blob");
    private static final byte[] FINGERPRINT = filled(32, 7);

    private static final class Clock implements SshRoleContext.Clock {
        long value = 1_000;
        @Override public long now() { return value; }
    }

    private static final class CallbackNative implements SshNativeCallbackAdapter.NativeBinding {
        int openCalls;
        int cancelCalls;
        int closeCalls;

        @Override public long open(SshNativeCallbackAdapter target, long role, int ownerUid,
                long deadline) {
            assertNotNull(target);
            assertTrue(role > 0 && ownerUid >= 0 && deadline > 0);
            openCalls++;
            return 901;
        }

        @Override public void cancel(long callback) {
            assertEquals(901, callback);
            cancelCalls++;
        }

        @Override public void close(long callback) {
            assertEquals(901, callback);
            closeCalls++;
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

    private static final class ViewLease implements SshNativeSession.ViewBinding,
            SshRoleContext.PublicView {
        final List<String> order;
        boolean active = true;
        boolean failClose;
        int closeCalls;

        ViewLease(List<String> order) { this.order = order; }

        @Override public long token() throws VaultException {
            if (!active) throw new VaultException("VAULT_CHANGED", "view revoked");
            return 902;
        }

        @Override public boolean active() { return active; }

        @Override public void close() throws VaultException {
            closeCalls++;
            order.add("view");
            if (failClose) throw new VaultException("SSH_FILE_VIEW", "view cleanup failed");
            active = false;
        }
    }

    private static final class SessionNative implements SshNativeSession.NativeBinding {
        final List<String> order = new ArrayList<>();
        int openCalls;
        int socketFd = -1;
        byte[] openHostKey;
        byte[] openFingerprint;
        byte[] verifyInput;
        boolean failOpen;
        boolean failClose;
        boolean failDestroy;
        boolean lastStepCancelled;
        boolean malformedStepResult;
        boolean completeStepResult;

        @Override public long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob, byte[] expectedFingerprint,
                String knownHostsPath, String host, String candidateId) throws IOException {
            assertTrue(role > 0 && ownerUid >= 0 && callback > 0 && view > 0);
            assertTrue(deadline > 0 && stepBudget > 0);
            assertEquals("ssh/known_hosts", knownHostsPath);
            assertEquals("example.invalid", host);
            assertEquals("candidate_1", candidateId);
            this.socketFd = socketFd;
            this.openHostKey = hostKeyBlob;
            this.openFingerprint = expectedFingerprint;
            openCalls++;
            order.add("open");
            if (failOpen) throw new IOException("fixture open failure");
            return 903;
        }

        @Override public SshNativeSession.StepResult step(long handle, boolean cancelled)
                throws IOException {
            assertEquals(903, handle);
            lastStepCancelled = cancelled;
            order.add("step");
            if (cancelled) throw new IOException("fixture cancellation");
            if (malformedStepResult) {
                return new SshNativeSession.StepResult(SshNativeSession.MAX_STEP_BUDGET,
                        0, SshNativeSession.MAX_STEP_BUDGET, 0, false, false, false, false);
            }
            return new SshNativeSession.StepResult(4, 5, 4, 0, true, false, false,
                    completeStepResult);
        }

        @Override public void verifyHostKey(long handle, byte[] hostKeyBlob) {
            assertEquals(903, handle);
            verifyInput = hostKeyBlob;
            order.add("verify");
        }

        @Override public void close(long handle) throws IOException {
            assertEquals(903, handle);
            order.add("close");
            if (failClose) throw new IOException("fixture close failure");
        }

        @Override public void destroy(long handle) throws IOException {
            assertEquals(903, handle);
            order.add("destroy");
            if (failDestroy) throw new IOException("fixture destroy failure");
        }
    }

    private static final class Setup {
        final Clock clock = new Clock();
        final SshRoleRegistry registry = new SshRoleRegistry(clock);
        final CallbackNative callbackNative = new CallbackNative();
        final RoleRelease roleRelease = new RoleRelease();
        final SshNativeRoleBootstrap.Issued issued;
        final SshNativeCallbackAdapter callback;
        final SessionNative sessionNative = new SessionNative();
        final ViewLease view = new ViewLease(sessionNative.order);
        SshNativeSession session;

        Setup() throws Exception {
            issued = SshNativeRoleBootstrap.issue(record(31, 42, 1001, 0), registry,
                    view, clock, ignored -> true);
            callback = SshNativeCallbackAdapter.bind(issued, registry,
                    new SshHostKeyAuthority(), clock, callbackNative, roleRelease);
        }

        void closeRegistry() { registry.close(); }
    }

    @Test public void ownerThreadDrivesNativeLifecycleAndPreservesCloseOrder() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.session.verifyHostKey(HOST_KEY);
            assertTrue(allZero(setup.sessionNative.verifyInput));
            SshNativeSession.StepResult result = setup.session.step();
            assertEquals(4, result.bytesRead);
            setup.session.close();
            assertEquals(Arrays.asList("open", "verify", "step", "close", "view", "destroy"),
                    setup.sessionNative.order);
            assertEquals(1, setup.roleRelease.calls);
            assertEquals(0, setup.callbackNative.closeCalls);
            assertTrue(setup.session.closed());
            setup.session.close();
            assertEquals(1, setup.roleRelease.calls);
            assertFalse(setup.issued.role.active());
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void nonOwnerCannotEnterStepVerifyOrClose() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            AtomicReference<String> stepCode = new AtomicReference<>();
            AtomicReference<String> verifyCode = new AtomicReference<>();
            AtomicReference<String> closeCode = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { setup.session.step(); fail("step must stay on owner thread"); }
                catch (VaultException failure) { stepCode.set(failure.code); }
                try { setup.session.verifyHostKey(HOST_KEY); fail("verify must stay on owner thread"); }
                catch (VaultException failure) { verifyCode.set(failure.code); }
                try { setup.session.close(); fail("close must stay on owner thread"); }
                catch (VaultException failure) { closeCode.set(failure.code); }
            });
            worker.start();
            worker.join();
            assertEquals("SSH_SESSION_THREAD", stepCode.get());
            assertEquals("SSH_SESSION_THREAD", verifyCode.get());
            assertEquals("SSH_SESSION_THREAD", closeCode.get());
            assertEquals(Arrays.asList("open"), setup.sessionNative.order);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void cancelMayCrossThreadsButNativeStepStillRunsOnOwner() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            Thread worker = new Thread(() -> {
                try { setup.session.cancel(); }
                catch (VaultException failure) { throw new AssertionError(failure); }
            });
            worker.start();
            worker.join();
            assertEquals(1, setup.callbackNative.cancelCalls);
            try {
                setup.session.step();
                fail("cancelled session must fail closed");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_CANCELLED", failure.code);
            }
            assertTrue(setup.sessionNative.lastStepCancelled);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void failedOpenLeavesSocketCallbackRoleAndViewWithCaller() throws Exception {
        Setup setup = new Setup();
        try {
            setup.sessionNative.failOpen = true;
            try {
                open(setup);
                fail("open must fail");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_NATIVE", failure.code);
            }
            assertEquals(41, setup.sessionNative.socketFd);
            assertTrue(setup.view.active);
            assertTrue(setup.issued.role.active());
            assertEquals(0, setup.callbackNative.closeCalls);
            assertEquals(1, setup.registry.size());
            setup.sessionNative.failOpen = false;
            setup.session = open(setup);
            setup.session.close();
            assertEquals(0, setup.callbackNative.closeCalls);
            assertEquals(1, setup.roleRelease.calls);
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void callbackBindingAllowsOnlyOneNativeSessionAtATime() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            try {
                open(setup);
                fail("one callback binding must not own two sessions");
            } catch (VaultException failure) {
                assertEquals("SSH_CALLBACK_SESSION", failure.code);
            }
            assertEquals(1, setup.sessionNative.openCalls);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void roleBindingRequiresTheExactPublicViewInstance() throws Exception {
        Setup setup = new Setup();
        ViewLease other = new ViewLease(setup.sessionNative.order);
        try {
            assertTrue(setup.callback.ownsPublicView(setup.view));
            assertFalse(setup.callback.ownsPublicView(other));
            assertTrue(other.active);
            assertEquals(1, setup.registry.size());
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void directCallbackCloseCannotBypassAnActiveSessionClaim() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            try {
                setup.callback.close();
                fail("the session owner must close the callback binding");
            } catch (VaultException failure) {
                assertEquals("SSH_CALLBACK_SESSION", failure.code);
            }
            assertEquals(0, setup.callbackNative.closeCalls);
            assertEquals(Arrays.asList("open"), setup.sessionNative.order);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void malformedStepResultTerminalizesTheSession() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.sessionNative.malformedStepResult = true;
            try {
                setup.session.step();
                fail("an over-budget step result must fail closed");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_STEP", failure.code);
            }
            try {
                setup.session.step();
                fail("a terminal step failure must not be retried");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_STEP", failure.code);
            }
            assertEquals(Arrays.asList("open", "step"), setup.sessionNative.order);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void completeStepRejectsFurtherIoButAllowsClose() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.sessionNative.completeStepResult = true;
            assertTrue(setup.session.step().complete);
            try {
                setup.session.step();
                fail("a completed session must not accept another step");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_COMPLETE", failure.code);
            }
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void closeFailureAndViewFailureRemainRetryableWithoutReordering() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.sessionNative.failClose = true;
            try { setup.session.close(); fail("close must remain visible"); }
            catch (VaultException failure) { assertEquals("SSH_SESSION_CLEANUP", failure.code); }
            assertEquals(Arrays.asList("open", "close"), setup.sessionNative.order);
            assertEquals(0, setup.roleRelease.calls);
            setup.sessionNative.failClose = false;
            setup.view.failClose = true;
            try { setup.session.close(); fail("view cleanup must remain visible"); }
            catch (VaultException failure) { assertEquals("SSH_FILE_VIEW", failure.code); }
            assertEquals(Arrays.asList("open", "close", "close", "view"),
                    setup.sessionNative.order);
            assertEquals(1, setup.roleRelease.calls);
            setup.view.failClose = false;
            setup.session.close();
            assertEquals(Arrays.asList("open", "close", "close", "view", "view", "destroy"),
                    setup.sessionNative.order);
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void deadlineStopsStepBeforeNativeAndStillAllowsOwnerClose() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.clock.value = setup.issued.role.deadline;
            try {
                setup.session.step();
                fail("expired session must fail closed");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_EXPIRED", failure.code);
            }
            assertEquals(Arrays.asList("open"), setup.sessionNative.order);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    @Test public void deadlineStopsHostVerificationBeforeNative() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.clock.value = setup.issued.role.deadline;
            try {
                setup.session.verifyHostKey(HOST_KEY);
                fail("expired session must reject host verification");
            } catch (VaultException failure) {
                assertEquals("SSH_SESSION_EXPIRED", failure.code);
            }
            assertEquals(Arrays.asList("open"), setup.sessionNative.order);
            setup.session.close();
        } finally {
            setup.closeRegistry();
        }
    }

    private static SshNativeSession open(Setup setup) throws Exception {
        return SshNativeSession.open(setup.callback, setup.view, 41, HOST_KEY, FINGERPRINT,
                "candidate_1", 1024, setup.sessionNative);
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
        text(output, "target:user@example.invalid:22");
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

    private static byte[] filled(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte)value);
        return result;
    }

    private static boolean allZero(byte[] value) {
        if (value == null) return false;
        for (byte item : value) if (item != 0) return false;
        return true;
    }
}
