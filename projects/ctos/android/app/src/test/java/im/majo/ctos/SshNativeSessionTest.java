package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
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

    private static class SessionNative implements SshNativeSession.NativeBinding {
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
        boolean failStep;
        Runnable openHook;
        Runnable stepHook;
        Runnable verifyHook;
        Runnable closeHook;
        Runnable destroyHook;

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
            if (openHook != null) openHook.run();
            if (failOpen) throw new IOException("fixture open failure");
            return 903;
        }

        @Override public SshNativeSession.StepResult step(long handle, boolean cancelled)
                throws IOException {
            assertEquals(903, handle);
            lastStepCancelled = cancelled;
            order.add("step");
            if (stepHook != null) stepHook.run();
            if (failStep) throw new IOException("synthetic step failure");
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
            if (verifyHook != null) verifyHook.run();
        }

        @Override public void close(long handle) throws IOException {
            assertEquals(903, handle);
            order.add("close");
            if (closeHook != null) closeHook.run();
            if (failClose) throw new IOException("fixture close failure");
        }

        @Override public void destroy(long handle) throws IOException {
            assertEquals(903, handle);
            order.add("destroy");
            if (destroyHook != null) destroyHook.run();
            if (failDestroy) throw new IOException("fixture destroy failure");
        }
    }

    private static class SelectedSessionNative extends SessionNative {
        byte[] wireReference;
        byte[] wireCopy;
        byte[] digest;
        byte[] returnedReceipt;
        int receiptCalls;
        int receiptMode;
        Runnable receiptHook;

        @Override public long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob, byte[] expectedFingerprint,
                String knownHostsPath, String host, String candidateId, byte[] selectionWire)
                throws IOException {
            wireReference = selectionWire;
            wireCopy = selectionWire == null ? null : selectionWire.clone();
            digest = selectionWire == null ? null : VaultCodec.digest(selectionWire);
            return super.open(role, ownerUid, callback, view, socketFd, deadline, stepBudget,
                    hostKeyBlob, expectedFingerprint, knownHostsPath, host, candidateId);
        }

        @Override public byte[] authenticationSelectionReceipt(long handle) throws IOException {
            assertEquals(903, handle);
            receiptCalls++;
            if (receiptHook != null) receiptHook.run();
            if (receiptMode == 4) throw new IOException("fixture receipt failure");
            returnedReceipt = receiptMode == 1 ? null : receiptMode == 2 ? new byte[31] : digest.clone();
            if (receiptMode == 3) returnedReceipt[0] ^= 1;
            return returnedReceipt;
        }
    }

    private static final class AuthNative extends SelectedSessionNative {
        boolean serviceReady;
        int result;
        int serviceBegins;
        int serviceReads;
        int authBegins;
        int authReads;
        int keyBegins;
        int keyReads;
        String failOperation;
        int failureKind;
        Runnable protocolHook;

        private void invoke(String operation) throws IOException {
            if (protocolHook != null) protocolHook.run();
            if (!operation.equals(failOperation)) return;
            if (failureKind == 1) throw new IllegalStateException("synthetic native failure");
            if (failureKind == 2) throw new UnsatisfiedLinkError("synthetic unavailable symbol");
            if (failureKind == 3) throw new AssertionError("synthetic native Error");
            throw new IOException("synthetic native failure");
        }

        @Override public void beginUserauthService(long handle) throws IOException {
            assertEquals(903, handle);
            serviceBegins++;
            invoke("serviceBegin");
        }

        @Override public boolean userauthServiceReady(long handle) throws IOException {
            assertEquals(903, handle);
            serviceReads++;
            invoke("serviceRead");
            return serviceReady;
        }

        @Override public void beginPasswordAuth(long handle) throws IOException {
            assertEquals(903, handle);
            authBegins++;
            invoke("authBegin");
            result = 1;
        }

        @Override public int passwordAuthResult(long handle) throws IOException {
            assertEquals(903, handle);
            authReads++;
            invoke("authRead");
            return result;
        }
        @Override public void beginPrivateKeyAuth(long handle) throws IOException {
            assertEquals(903, handle);
            keyBegins++;
            invoke("keyBegin");
            result = 1;
        }

        @Override public int privateKeyAuthResult(long handle) throws IOException {
            assertEquals(903, handle);
            keyReads++;
            invoke("keyRead");
            return result;
        }
    }

    private static final class Setup {
        final Clock clock = new Clock();
        final SshRoleRegistry registry = new SshRoleRegistry(clock);
        final CallbackNative callbackNative = new CallbackNative();
        final RoleRelease roleRelease = new RoleRelease();
        final SshNativeRoleBootstrap.Issued issued;
        final SshNativeCallbackAdapter callback;
        final SessionNative sessionNative;
        final ViewLease view;
        SshNativeSession session;

        Setup() throws Exception { this(false); }

        Setup(boolean selectedBinding) throws Exception {
            this(selectedBinding, selectedBinding ? new SelectedSessionNative() : new SessionNative());
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding) throws Exception {
            sessionNative = nativeBinding;
            view = new ViewLease(sessionNative.order);
            issued = SshNativeRoleBootstrap.issue(record(31, 42, 1001, 0), registry,
                    view, clock, ignored -> true);
            SshHostKeyAuthority authority = new SshHostKeyAuthority();
            SshManagedProvider provider = selectedBinding ? metadataOnlyProvider(authority, clock) : null;
            callback = SshNativeCallbackAdapter.bind(issued, registry,
                    authority, clock, callbackNative, roleRelease, provider);
        }

        void closeRegistry() { registry.close(); }
    }

    private static SshManagedProvider metadataOnlyProvider(SshHostKeyAuthority authority, Clock clock) {
        AppSecrets vault = new AppSecrets(new AppSecrets.Store() {
            @Override public byte[] read() { throw new AssertionError("metadata cannot read vault"); }
            @Override public byte[] quickMaterial() { throw new AssertionError("metadata cannot read vault"); }
            @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                throw new AssertionError("metadata cannot write vault");
            }
        }, clock::now);
        return new SshManagedProvider(new SshCredentialBridge(vault, authority, clock), clock,
                null, (bridge, role, hostKey, kind) -> { throw new AssertionError("metadata cannot request secrets"); });
    }

    private static SshAuthenticationSelection select(Setup setup, boolean privateKey) throws Exception {
        SshAuthenticationSelection selection = privateKey
                ? SshAuthenticationSelection.privateKey(setup.issued.role, " 用户😀 ", "keys/key",
                        "expected-key", bytes("public declaration"), setup.clock)
                : SshAuthenticationSelection.password(setup.issued.role, " 用户😀 ", "password", setup.clock);
        setup.callback.selectAuthentication(selection);
        return selection;
    }

    @Test public void selectedOpenTransportsCanonicalMetadataAndExplicitReceiptSurvivesKexCompletion()
            throws Exception {
        for (boolean key : new boolean[] { false, true }) {
            Setup setup = new Setup(true);
            try {
                SshAuthenticationSelection selection = select(setup, key);
                byte[] expectedWire = selection.encode(setup.issued.token, setup.clock);
                setup.session = open(setup);
                SelectedSessionNative nativeBinding = (SelectedSessionNative) setup.sessionNative;
                assertArrayEquals(expectedWire, nativeBinding.wireCopy);
                assertTrue(allZero(nativeBinding.wireReference));
                assertEquals(0, nativeBinding.receiptCalls);
                byte[] result = setup.session.authenticationSelectionReceipt();
                assertArrayEquals(VaultCodec.digest(expectedWire), result);
                assertTrue(allZero(nativeBinding.returnedReceipt));
                result[0] ^= 1;
                assertArrayEquals(VaultCodec.digest(expectedWire), setup.session.authenticationSelectionReceipt());
                setup.sessionNative.completeStepResult = true;
                assertTrue(setup.session.step().complete);
                assertArrayEquals(VaultCodec.digest(expectedWire), setup.session.authenticationSelectionReceipt());
                setup.sessionNative.failClose = true;
                try { setup.session.close(); fail("close retry"); }
                catch (VaultException failure) { assertEquals("SSH_SESSION_CLEANUP", failure.code); }
                assertArrayEquals(VaultCodec.digest(expectedWire), setup.session.authenticationSelectionReceipt());
                setup.sessionNative.failClose = false;
                setup.session.close();
                try { setup.session.authenticationSelectionReceipt(); fail("closed receipt"); }
                catch (VaultException failure) { assertEquals("SSH_SESSION_CLOSED", failure.code); }
            } finally {
                if (setup.session != null && !setup.session.closed()) setup.session.close();
                setup.closeRegistry();
            }
        }
    }

    @Test public void legacyOpenIsCompatibleAndMissingSelectionReadIsNonterminal() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            try { setup.session.authenticationSelectionReceipt(); fail("unselected read"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            assertEquals(4, setup.session.step().bytesRead);
            setup.session.close();
        } finally { setup.closeRegistry(); }
    }

    @Test public void selectedOpenCannotSilentlyDowngradeOldBindingAndFailedOpenWipesWire() throws Exception {
        Setup setup = new Setup(true);
        try {
            SshAuthenticationSelection selection = select(setup, false);
            SessionNative oldBinding = new SessionNative();
            try {
                SshNativeSession.open(setup.callback, setup.view, 41, HOST_KEY, FINGERPRINT,
                        "candidate_1", 1024, oldBinding);
                fail("old binding cannot drop selected wire");
            } catch (VaultException failure) { assertEquals("SSH_SESSION_NATIVE", failure.code); }
            assertEquals(0, oldBinding.openCalls);
            assertTrue(setup.view.active);
            assertTrue(setup.issued.role.active());
            try { setup.callback.selectAuthentication(selection); fail("failed open cannot reselect"); }
            catch (VaultException failure) { assertEquals("SSH_AUTH_SELECTION", failure.code); }
            setup.sessionNative.failOpen = true;
            try { open(setup); fail("selected IOException"); }
            catch (VaultException failure) { assertEquals("SSH_SESSION_NATIVE", failure.code); }
            assertTrue(allZero(((SelectedSessionNative) setup.sessionNative).wireReference));
            assertTrue(allZero(setup.sessionNative.openHostKey));
            assertTrue(allZero(setup.sessionNative.openFingerprint));
            assertEquals(0, setup.callbackNative.closeCalls);
            setup.sessionNative.failOpen = false;
            setup.session = open(setup);
            setup.session.close();
        } finally { setup.closeRegistry(); }
    }

    @Test public void malformedOrNativeFailedReceiptTerminalizesWithoutLosingCleanup() throws Exception {
        for (int mode = 1; mode <= 4; mode++) {
            Setup setup = new Setup(true);
            try {
                select(setup, false);
                setup.session = open(setup);
                SelectedSessionNative nativeBinding = (SelectedSessionNative) setup.sessionNative;
                nativeBinding.receiptMode = mode;
                try { setup.session.authenticationSelectionReceipt(); fail("bad receipt"); }
                catch (VaultException failure) { assertEquals("SSH_SESSION_SELECTION", failure.code); }
                if (nativeBinding.returnedReceipt != null) assertTrue(allZero(nativeBinding.returnedReceipt));
                try { setup.session.step(); fail("receipt failure must be terminal"); }
                catch (VaultException failure) { assertEquals("SSH_SESSION_SELECTION", failure.code); }
                assertEquals(Arrays.asList("open"), nativeBinding.order);
                setup.session.close();
            } finally { setup.closeRegistry(); }
        }
    }

    @Test public void receiptReadRequiresOwnerAndRechecksCancelExpiryAndViewAfterNativeReturn() throws Exception {
        for (int mode = 0; mode < 8; mode++) {
            Setup setup = new Setup(true);
            try {
                select(setup, false);
                setup.session = open(setup);
                SelectedSessionNative nativeBinding = (SelectedSessionNative) setup.sessionNative;
                if (mode == 0) {
                    AtomicReference<String> error = new AtomicReference<>();
                    Thread thread = new Thread(() -> {
                        try { setup.session.authenticationSelectionReceipt(); }
                        catch (VaultException failure) { error.set(failure.code); }
                    });
                    thread.start();
                    thread.join();
                    assertEquals("SSH_SESSION_THREAD", error.get());
                    assertEquals(0, nativeBinding.receiptCalls);
                    assertNotNull(setup.session.authenticationSelectionReceipt());
                } else {
                    if (mode == 1) setup.session.cancel();
                    if (mode == 2) setup.clock.value = setup.issued.role.deadline;
                    if (mode == 6) setup.callback.cancel();
                    if (mode == 3) nativeBinding.receiptHook = () -> setup.clock.value = setup.issued.role.deadline;
                    if (mode == 4) nativeBinding.receiptHook = () -> setup.view.active = false;
                    if (mode == 5 || mode == 7) {
                        final int cancelMode = mode;
                        nativeBinding.receiptHook = () -> {
                            try {
                                if (cancelMode == 7) setup.callback.cancel();
                                else setup.session.cancel();
                            } catch (VaultException failure) { throw new AssertionError(failure); }
                        };
                    }
                    try { setup.session.authenticationSelectionReceipt(); fail("late lifecycle failure"); }
                    catch (VaultException failure) {
                        assertEquals(mode == 1 || mode == 5 || mode == 6 || mode == 7 ? "SSH_SESSION_CANCELLED"
                                : mode == 4 ? "SSH_FILE_VIEW" : "SSH_SESSION_EXPIRED", failure.code);
                    }
                    assertEquals(mode <= 2 || mode == 6 ? 0 : 1, nativeBinding.receiptCalls);
                    if (nativeBinding.returnedReceipt != null) assertTrue(allZero(nativeBinding.returnedReceipt));
                }
                setup.session.close();
            } finally { setup.closeRegistry(); }
        }
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

    @Test public void explicitPasswordPhasesRetainKexAndAllFourFinalResults() throws Exception {
        for (int tag = 2; tag <= 5; tag++) {
            AuthNative nativeBinding = new AuthNative();
            Setup setup = new Setup(true, nativeBinding);
            try {
                select(setup, false);
                setup.session = open(setup);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginUserauthService());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.userauthServiceReady());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordAuthResult());
                assertEquals(0, nativeBinding.serviceBegins + nativeBinding.serviceReads
                        + nativeBinding.authBegins + nativeBinding.authReads);
                nativeBinding.completeStepResult = true;
                assertTrue(setup.session.step().complete);
                assertEquals(0, nativeBinding.serviceReads + nativeBinding.authReads);
                assertCode("SSH_SESSION_COMPLETE", () -> setup.session.verifyHostKey(HOST_KEY));
                assertCode("SSH_SESSION_COMPLETE", () -> setup.session.step());
                setup.session.beginUserauthService();
                assertFalse(setup.session.userauthServiceReady());
                assertTrue(setup.session.step().complete);
                assertEquals(2, nativeBinding.serviceReads);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginUserauthService());
                nativeBinding.serviceReady = true;
                assertTrue(setup.session.step().complete);
                int steps = nativeBinding.order.size();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.step());
                assertEquals(steps, nativeBinding.order.size());
                assertEquals(SshNativeSession.PasswordAuthResult.NOT_STARTED,
                        setup.session.passwordAuthResult());
                setup.session.beginPasswordAuth();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
                assertEquals(SshNativeSession.PasswordAuthResult.PENDING,
                        setup.session.passwordAuthResult());
                nativeBinding.result = tag;
                assertTrue(setup.session.step().complete);
                assertEquals(tag, setup.session.passwordAuthResult().tag);
                assertEquals(tag, setup.session.passwordAuthResult().tag);
                assertTrue(setup.session.userauthServiceReady());
                assertNotNull(setup.session.authenticationSelectionReceipt());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.step());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginUserauthService());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
                assertEquals(1, nativeBinding.serviceBegins);
                assertEquals(1, nativeBinding.authBegins);
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void noSelectionIsNonterminalAndOldBindingDefaultsFailClosed() throws Exception {
        AuthNative nativeBinding = new AuthNative();
        Setup setup = new Setup(false, nativeBinding);
        try {
            setup.session = open(setup);
            ready(setup, nativeBinding);
            assertCode("SSH_AUTH_SELECTION", () -> setup.session.beginPasswordAuth());
            assertEquals(0, nativeBinding.authBegins);
            assertTrue(setup.session.userauthServiceReady());
        } finally { setup.session.close(); setup.closeRegistry(); }
        Setup legacy = new Setup();
        try {
            legacy.session = open(legacy);
            legacy.sessionNative.completeStepResult = true;
            legacy.session.step();
            assertCode("SSH_SESSION_SERVICE", () -> legacy.session.beginUserauthService());
            assertCode("SSH_SESSION_SERVICE", () -> legacy.session.step());
        } finally { legacy.session.close(); legacy.closeRegistry(); }
        // The other three defaults must throw rather than return ready/success.
        SessionNative defaults = new SessionNative();
        try { defaults.userauthServiceReady(903); fail("missing ready default"); }
        catch (IOException expected) { /* fail closed */ }
        try { defaults.beginPasswordAuth(903); fail("missing begin default"); }
        catch (IOException expected) { /* fail closed */ }
        try { defaults.passwordAuthResult(903); fail("missing result default"); }
        catch (IOException expected) { /* fail closed */ }
    }

    @Test public void unknownEarlyAndRegressingResultsAreTerminal() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            AuthNative binding = new AuthNative();
            Setup setup = new Setup(true, binding);
            try {
                select(setup, false);
                setup.session = open(setup);
                ready(setup, binding);
                if (mode == 0) {
                    binding.serviceReady = false;
                    assertCode("SSH_SESSION_SERVICE", () -> setup.session.userauthServiceReady());
                    assertCode("SSH_SESSION_SERVICE", () -> setup.session.beginPasswordAuth());
                } else {
                    if (mode == 1) binding.result = 2;
                    else {
                        setup.session.beginPasswordAuth();
                        if (mode == 2) binding.result = -1;
                        if (mode == 3) binding.result = 6;
                        if (mode == 4) binding.result = 0;
                        if (mode == 5) {
                            binding.result = 2;
                            assertEquals(SshNativeSession.PasswordAuthResult.SUCCESS,
                                    setup.session.passwordAuthResult());
                            binding.result = 3;
                        }
                    }
                    assertCode("SSH_SESSION_AUTH_RESULT", () -> setup.session.passwordAuthResult());
                    assertCode("SSH_SESSION_AUTH_RESULT", () -> setup.session.step());
                }
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void newNativeFailuresAreTypedTerminalAndGuardAlwaysClears() throws Exception {
        for (int operation = 0; operation < 6; operation++) {
            for (int kind = 0; kind < 4; kind++) {
                AuthNative binding = new AuthNative();
                Setup setup = new Setup(true, binding);
                try {
                    select(setup, operation >= 4);
                    setup.session = open(setup);
                    setup.sessionNative.completeStepResult = true;
                    setup.session.step();
                    if (operation > 0) setup.session.beginUserauthService();
                    if (operation > 1) {
                        binding.serviceReady = true;
                        setup.session.userauthServiceReady();
                    }
                    if (operation == 3) setup.session.beginPasswordAuth();
                    if (operation == 5) setup.session.beginPrivateKeyAuth();
                    binding.failOperation = new String[] {
                        "serviceBegin", "serviceRead", "authBegin", "authRead", "keyBegin", "keyRead" }[operation];
                    binding.failureKind = kind;
                    CheckedAction action = protocolOperation(setup.session, operation);
                    String code = operation < 2 ? "SSH_SESSION_SERVICE"
                            : (operation == 2 || operation == 4) ? "SSH_SESSION_AUTH" : "SSH_SESSION_AUTH_RESULT";
                    if (kind == 3) {
                        try { action.run(); fail("Error must propagate"); }
                        catch (AssertionError expected) {
                            assertEquals("synthetic native Error", expected.getMessage());
                        }
                        if (operation == 0 || operation == 2 || operation == 4) {
                            assertCode(code, () -> setup.session.authenticationSelectionReceipt());
                        }
                    } else {
                        assertCode(code, action);
                        assertCode(code, () -> setup.session.authenticationSelectionReceipt());
                    }
                    binding.failOperation = null;
                    setup.session.close();
                    assertTrue(setup.session.closed());
                } finally {
                    if (!setup.session.closed()) setup.session.close();
                    setup.closeRegistry();
                }
            }
        }
    }

    @Test public void newOperationsEnforceOwnerAndPreAndPostLifecycle() throws Exception {
        for (int operation = 0; operation < 6; operation++) {
            for (int mode = 0; mode < 13; mode++) {
                AuthNative binding = new AuthNative();
                Setup setup = new Setup(true, binding);
                try {
                    select(setup, operation >= 4);
                    setup.session = open(setup);
                    setup.sessionNative.completeStepResult = true;
                    setup.session.step();
                    if (operation > 0) setup.session.beginUserauthService();
                    if (operation > 1) {
                        binding.serviceReady = true;
                        setup.session.userauthServiceReady();
                    }
                    if (operation == 3) setup.session.beginPasswordAuth();
                    if (operation == 5) setup.session.beginPrivateKeyAuth();
                    CheckedAction action = protocolOperation(setup.session, operation);
                    int before = protocolCalls(binding);
                    if (mode == 0) {
                        AtomicReference<Throwable> failure = new AtomicReference<>();
                        Thread other = new Thread(() -> {
                            try { action.run(); }
                            catch (Throwable caught) { failure.set(caught); }
                        });
                        other.start(); other.join();
                        assertTrue(failure.get() instanceof VaultException);
                        assertEquals("SSH_SESSION_THREAD", ((VaultException) failure.get()).code);
                        assertEquals(before, protocolCalls(binding));
                        continue;
                    }
                    final int lifecycle = (mode - 1) % 4;
                    Runnable change = () -> {
                        try {
                            if (lifecycle == 0) setup.session.cancel();
                            if (lifecycle == 1) setup.callback.cancel();
                            if (lifecycle == 2) setup.clock.value = setup.issued.role.deadline;
                            if (lifecycle == 3) setup.view.active = false;
                        } catch (VaultException failure) { throw new AssertionError(failure); }
                    };
                    if (mode <= 4) change.run();
                    else binding.protocolHook = change;
                    if (mode >= 9) {
                        binding.failOperation = new String[] {
                            "serviceBegin", "serviceRead", "authBegin", "authRead", "keyBegin", "keyRead" }[operation];
                    }
                    String code = lifecycle < 2 ? "SSH_SESSION_CANCELLED"
                            : lifecycle == 2 ? "SSH_SESSION_EXPIRED" : "SSH_FILE_VIEW";
                    assertCode(code, action);
                    assertEquals(before + (mode <= 4 ? 0 : 1), protocolCalls(binding));
                } finally { setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void explicitStepNativeFailurePreservesDirectCancelExpiryAndViewPriority()
            throws Exception {
        for (boolean passwordPending : new boolean[] { false, true }) {
            for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
                AuthNative binding = new AuthNative();
                Setup setup = new Setup(true, binding);
                try {
                    select(setup, false);
                    setup.session = open(setup);
                    binding.completeStepResult = true;
                    setup.session.step();
                    setup.session.beginUserauthService();
                    if (passwordPending) {
                        binding.serviceReady = true;
                        setup.session.userauthServiceReady();
                        setup.session.beginPasswordAuth();
                    }
                    final int change = lifecycle;
                    binding.stepHook = () -> {
                        try {
                            if (change == 0) setup.callback.cancel();
                            if (change == 1) setup.clock.value = setup.issued.role.deadline;
                            if (change == 2) setup.view.active = false;
                        } catch (VaultException failure) { throw new AssertionError(failure); }
                    };
                    binding.failStep = true;
                    String code = lifecycle == 0 ? "SSH_SESSION_CANCELLED"
                            : lifecycle == 1 ? "SSH_SESSION_EXPIRED" : "SSH_FILE_VIEW";
                    assertCode(code, () -> setup.session.step());
                    assertCode(code, () -> setup.session.authenticationSelectionReceipt());
                } finally {
                    binding.stepHook = null;
                    setup.session.close(); setup.closeRegistry();
                }
            }
        }
    }

    @Test public void automaticMilestoneFailureRetainsTypedCodeAndReleasesEntryGuard() throws Exception {
        for (boolean passwordPending : new boolean[] { false, true }) {
            AuthNative binding = new AuthNative();
            Setup setup = new Setup(true, binding);
            try {
                select(setup, false);
                setup.session = open(setup);
                binding.completeStepResult = true;
                setup.session.step();
                setup.session.beginUserauthService();
                if (passwordPending) {
                    binding.serviceReady = true;
                    setup.session.userauthServiceReady();
                    setup.session.beginPasswordAuth();
                    binding.result = 99;
                } else binding.failOperation = "serviceRead";
                String code = passwordPending ? "SSH_SESSION_AUTH_RESULT" : "SSH_SESSION_SERVICE";
                assertCode(code, () -> setup.session.step());
                assertCode(code, () -> setup.session.step());
                assertCode(code, () -> setup.session.passwordAuthResult());
                binding.failOperation = null;
                setup.session.close();
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void everyEntryRejectsSameAndOtherSessionReentryBeforeBindingOrState() throws Exception {
        for (int outer = 0; outer < 11; outer++) {
            AuthNative binding = new AuthNative();
            Setup setup = new Setup(true, binding);
            Setup other = new Setup(true, new AuthNative());
            try {
                select(setup, false); select(other, false);
                setup.session = open(setup); other.session = open(other);
                if ((outer >= 3 && outer <= 6) || outer >= 9) {
                    setup.sessionNative.completeStepResult = true;
                    setup.session.step();
                    if (outer > 3) setup.session.beginUserauthService();
                    if (outer > 4) {
                        binding.serviceReady = true;
                        setup.session.userauthServiceReady();
                    }
                    if (outer == 6) setup.session.beginPasswordAuth();
                    if (outer == 10) setup.session.beginPrivateKeyAuth();
                }
                Runnable hook = () -> {
                    for (Setup target : new Setup[] { setup, other }) {
                        int before = target.sessionNative.order.size()
                                + protocolCalls((AuthNative) target.sessionNative)
                                + ((AuthNative) target.sessionNative).receiptCalls;
                        for (CheckedAction action : entries(target)) {
                            try { assertCode("SSH_SESSION_REENTRANT", action); }
                            catch (Exception failure) { throw new AssertionError(failure); }
                        }
                        assertEquals(before, target.sessionNative.order.size()
                                + protocolCalls((AuthNative) target.sessionNative)
                                + ((AuthNative) target.sessionNative).receiptCalls);
                        assertFalse(target.session.closed());
                    }
                };
                if (outer == 0) { binding.stepHook = hook; setup.session.step(); }
                if (outer == 1) { binding.verifyHook = hook; setup.session.verifyHostKey(HOST_KEY); }
                if (outer == 2) { binding.receiptHook = hook; setup.session.authenticationSelectionReceipt(); }
                if (outer >= 3 && outer <= 6) {
                    binding.protocolHook = hook;
                    protocolOperation(setup.session, outer - 3).run();
                }
                if (outer >= 9) {
                    binding.protocolHook = hook;
                    if (outer == 9) setup.session.beginPrivateKeyAuth();
                    else setup.session.privateKeyAuthResult();
                }
                if (outer == 7) { binding.closeHook = hook; setup.session.close(); }
                if (outer == 8) { binding.destroyHook = hook; setup.session.close(); }
                binding.stepHook = binding.verifyHook = binding.receiptHook = null;
                binding.closeHook = binding.destroyHook = binding.protocolHook = null;
                // The handled inner refusals must leave the other session fully usable.
                other.session.step();
            } finally {
                binding.stepHook = binding.verifyHook = binding.receiptHook = null;
                binding.closeHook = binding.destroyHook = binding.protocolHook = null;
                setup.session.close(); other.session.close();
                setup.closeRegistry(); other.closeRegistry();
            }
        }
    }

    @Test public void openAndCleanupErrorReleaseGuardAndCancellationRemainsAllowed() throws Exception {
        Setup setup = new Setup(true, new AuthNative());
        Setup other = new Setup(true, new AuthNative());
        try {
            select(setup, false); select(other, false);
            other.session = open(other);
            setup.sessionNative.openHook = () -> {
                for (CheckedAction entry : entries(other)) {
                    try { assertCode("SSH_SESSION_REENTRANT", entry); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                throw new AssertionError("open Error");
            };
            try { open(setup); fail("open Error"); }
            catch (AssertionError expected) { assertEquals("open Error", expected.getMessage()); }
            setup.sessionNative.openHook = null;
            setup.session = open(setup);
            setup.sessionNative.closeHook = () -> { throw new AssertionError("close Error"); };
            try { setup.session.close(); fail("close Error"); }
            catch (AssertionError expected) { assertEquals("close Error", expected.getMessage()); }
            setup.sessionNative.closeHook = null;
            setup.session.close();
            other.sessionNative.stepHook = () -> {
                try { other.session.cancel(); }
                catch (VaultException failure) { throw new AssertionError(failure); }
            };
            assertCode("SSH_SESSION_CANCELLED", () -> other.session.step());
            other.sessionNative.stepHook = null;
        } finally {
            if (setup.session != null) setup.session.close();
            if (other.session != null) other.session.close();
            setup.closeRegistry(); other.closeRegistry();
        }
    }

    @Test public void privateKeyResultsAreStableAndMethodsCannotCrossOver() throws Exception {
        for (int tag = 2; tag <= 4; tag++) {
            AuthNative binding = new AuthNative();
            Setup setup = new Setup(true, binding);
            try {
                select(setup, true);
                setup.session = open(setup);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPrivateKeyAuth());
                ready(setup, binding);
                assertEquals(SshNativeSession.PrivateKeyAuthResult.NOT_STARTED, setup.session.privateKeyAuthResult());
                setup.session.beginPrivateKeyAuth();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordAuthResult());
                assertEquals(SshNativeSession.PrivateKeyAuthResult.PENDING, setup.session.privateKeyAuthResult());
                binding.result = tag;
                assertTrue(setup.session.step().complete);
                assertEquals(tag, setup.session.privateKeyAuthResult().tag);
                assertEquals(tag, setup.session.privateKeyAuthResult().tag);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.step());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPrivateKeyAuth());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordAuthResult());
                assertEquals(1, binding.keyBegins);
                assertEquals(0, binding.authBegins);
                binding.result = 1;
                assertCode("SSH_SESSION_AUTH_RESULT", () -> setup.session.privateKeyAuthResult());
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
        AuthNative binding = new AuthNative();
        Setup password = new Setup(true, binding);
        try {
            select(password, false); password.session = open(password); ready(password, binding);
            password.session.beginPasswordAuth();
            assertCode("SSH_SESSION_PHASE", () -> password.session.privateKeyAuthResult());
            binding.result = 2;
            password.session.passwordAuthResult();
            assertCode("SSH_SESSION_PHASE", () -> password.session.privateKeyAuthResult());
        } finally { password.session.close(); password.closeRegistry(); }
    }

    @Test public void privateKeyDefaultsBadTagsAndNativeErrorsFailClosed() throws Exception {
        SessionNative defaults = new SessionNative();
        try { defaults.beginPrivateKeyAuth(903); fail("missing key begin"); }
        catch (IOException expected) { /* fail closed */ }
        try { defaults.privateKeyAuthResult(903); fail("missing key result"); }
        catch (IOException expected) { /* fail closed */ }
        for (int mode = 0; mode < 10; mode++) {
            AuthNative binding = new AuthNative();
            Setup setup = new Setup(true, binding);
            try {
                select(setup, true); setup.session = open(setup); ready(setup, binding);
                if (mode < 4) {
                    binding.failOperation = "keyBegin"; binding.failureKind = mode;
                    if (mode == 3) {
                        try { setup.session.beginPrivateKeyAuth(); fail("key Error"); }
                        catch (AssertionError expected) { assertEquals("synthetic native Error", expected.getMessage()); }
                    } else assertCode("SSH_SESSION_AUTH", () -> setup.session.beginPrivateKeyAuth());
                } else {
                    setup.session.beginPrivateKeyAuth();
                    if (mode < 8) {
                        binding.failOperation = "keyRead"; binding.failureKind = mode - 4;
                        if (mode == 7) {
                            try { setup.session.privateKeyAuthResult(); fail("key Error"); }
                            catch (AssertionError expected) { assertEquals("synthetic native Error", expected.getMessage()); }
                        } else assertCode("SSH_SESSION_AUTH_RESULT", () -> setup.session.privateKeyAuthResult());
                    } else {
                        binding.result = mode == 8 ? 0 : 5;
                        assertCode("SSH_SESSION_AUTH_RESULT", () -> setup.session.privateKeyAuthResult());
                    }
                }
                binding.failOperation = null;
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    private interface CheckedAction { void run() throws Exception; }

    private static void assertCode(String code, CheckedAction action) throws Exception {
        try { action.run(); fail("expected " + code); }
        catch (VaultException failure) { assertEquals(code, failure.code); }
    }

    private static CheckedAction protocolOperation(SshNativeSession session, int operation) {
        if (operation == 0) return () -> session.beginUserauthService();
        if (operation == 1) return () -> session.userauthServiceReady();
        if (operation == 2) return () -> session.beginPasswordAuth();
        if (operation == 3) return () -> session.passwordAuthResult();
        if (operation == 4) return () -> session.beginPrivateKeyAuth();
        return () -> session.privateKeyAuthResult();
    }

    private static CheckedAction[] entries(Setup setup) {
        return new CheckedAction[] {
            () -> setup.session.step(), () -> setup.session.verifyHostKey(HOST_KEY),
            () -> setup.session.authenticationSelectionReceipt(), () -> setup.session.close(),
            () -> setup.session.beginUserauthService(), () -> setup.session.userauthServiceReady(),
            () -> setup.session.beginPasswordAuth(), () -> setup.session.passwordAuthResult(),
            () -> setup.session.beginPrivateKeyAuth(), () -> setup.session.privateKeyAuthResult(),
            () -> open(setup),
            () -> SshNativeSession.open(setup.callback, (SshPublicFileLease) null, 41,
                    HOST_KEY, FINGERPRINT, "candidate_1", 1024)
        };
    }

    private static int protocolCalls(AuthNative binding) {
        return binding.serviceBegins + binding.serviceReads + binding.authBegins + binding.authReads
                + binding.keyBegins + binding.keyReads;
    }

    private static void ready(Setup setup, AuthNative binding) throws Exception {
        binding.completeStepResult = true;
        setup.session.step();
        setup.session.beginUserauthService();
        binding.serviceReady = true;
        assertTrue(setup.session.userauthServiceReady());
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
