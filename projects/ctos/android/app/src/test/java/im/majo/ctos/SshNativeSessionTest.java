package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * JVM regression seam for the event-loop session owner. The fresh-child tests use
 * an encrypted in-memory vault; the native session and transport remain test doubles.
 */
public final class SshNativeSessionTest {
    private static final byte[] HOST_KEY = bytes("ssh-ed25519-public-blob");
    private static final byte[] FINGERPRINT = filled(32, 7);

    private static final class Clock implements SshRoleContext.Clock, VaultSession.Clock {
        volatile long value = 1_000;
        Thread blockedThread;
        int callsBeforeBlock;
        CountDownLatch entered, release;
        void blockOnCall(int count) {
            blockedThread = Thread.currentThread(); callsBeforeBlock = count;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
        }
        @Override public long now() {
            if (Thread.currentThread() == blockedThread && callsBeforeBlock > 0 && --callsBeforeBlock == 0) {
                entered.countDown(); awaitTestLatch(release);
            }
            return value;
        }
    }

    private static final class CallbackNative implements SshNativeCallbackAdapter.NativeBinding {
        int openCalls;
        int cancelCalls;
        int closeCalls;
        boolean failCancel;
        boolean fatalCancel;
        Runnable cancelHook;

        @Override public long open(SshNativeCallbackAdapter target, long role, int ownerUid,
                long deadline) {
            assertNotNull(target);
            assertTrue(role > 0 && ownerUid >= 0 && deadline > 0);
            openCalls++;
            return 901;
        }

        @Override public void cancel(long callback) throws IOException {
            assertEquals(901, callback);
            cancelCalls++;
            if (cancelHook != null) cancelHook.run();
            if (fatalCancel) throw new AssertionError("fixture fatal callback cancellation");
            if (failCancel) throw new IOException("fixture callback cancellation failure");
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
            SshRoleContext.PublicView, VaultSession.Revocation {
        final List<String> order;
        volatile boolean active = true;
        boolean failClose;
        boolean fatalClose;
        boolean failToken;
        int closeCalls;
        Runnable closeHook;
        Thread pauseSelectionThread;
        CountDownLatch selectionEntered, selectionRelease;

        ViewLease(List<String> order) { this.order = order; }

        @Override public long token() throws VaultException {
            if (failToken) throw new VaultException("SSH_FILE_VIEW", "fixture borrow unavailable");
            if (!active) throw new VaultException("VAULT_CHANGED", "view revoked");
            return 902;
        }

        void pauseFinalSelectionViewRead() {
            pauseSelectionThread = Thread.currentThread();
            selectionEntered = new CountDownLatch(1); selectionRelease = new CountDownLatch(1);
        }

        @Override public boolean active() {
            boolean result = active;
            if (Thread.currentThread() == pauseSelectionThread) {
                boolean selection = false, binding = false, roleCheck = false;
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    selection |= frame.getClassName().equals(SshNativeSession.class.getName())
                            && frame.getMethodName().equals("requireSelectionReadLive");
                    binding |= frame.getClassName().equals(SshSharedPublicView.Binding.class.getName())
                            && frame.getMethodName().equals("active");
                    roleCheck |= frame.getClassName().equals(SshRoleContext.class.getName())
                            && frame.getMethodName().equals("requireBound");
                }
                if (selection && binding && !roleCheck) {
                    pauseSelectionThread = null;
                    selectionEntered.countDown(); awaitTestLatch(selectionRelease);
                }
            }
            return result;
        }

        @Override public void revoke() { active = false; }
        @Override public boolean revocationConfirmed() { return !active; }

        @Override public void close() throws VaultException {
            closeCalls++;
            order.add("view");
            if (closeHook != null) closeHook.run();
            if (fatalClose) throw new AssertionError("unconfirmed view close");
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
        IOException stepFailure;
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
            if (stepFailure != null) throw stepFailure;
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

    private static class AuthNative extends SelectedSessionNative {
        boolean serviceReady;
        int result;
        int serviceBegins;
        int serviceReads;
        int authBegins;
        int authReads;
        int keyBegins;
        int keyReads;
        int aliveCalls, aliveInterval, aliveCountMax, noPtyCalls;
        int discoveryBegins;
        int[] discovery = new int[] {0, 0};
        String failOperation;
        int failureKind;
        Runnable protocolHook;

        void invoke(String operation) throws IOException {
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

        @Override public void beginAuthDiscovery(long handle) throws IOException {
            assertEquals(903, handle);
            discoveryBegins++;
            invoke("discoveryBegin");
            discovery = new int[] {1, 0};
        }

        @Override public int[] authDiscoveryResult(long handle) throws IOException {
            assertEquals(903, handle);
            invoke("discoveryRead");
            return discovery == null ? null : discovery.clone();
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

        @Override public void configureServerAlive(long handle, int interval, int countMax) throws IOException {
            assertEquals(903, handle);
            aliveCalls++; aliveInterval = interval; aliveCountMax = countMax;
            invoke("serverAlive");
        }

        @Override public void configureNoPty(long handle) throws IOException {
            assertEquals(903, handle);
            noPtyCalls++;
            invoke("noPty");
        }
    }

    private static class ShellNative extends AuthNative {
        int[] status = new int[7];
        int shellCalls;
        int writes;
        int reads;
        int accepted;
        int resizeAccepted;
        int eofAccepted;
        byte[] writeReference;
        byte[] readReference;
        byte[] output = bytes("output\r\n");
        Runnable shellHook;
        String currentShellOperation;

        private void shellInvoke(String operation) throws IOException {
            shellCalls++;
            currentShellOperation = operation;
            if (shellHook != null) shellHook.run();
            invoke(operation);
        }
        @Override public void beginShell(long handle, int columns, int rows) throws IOException {
            shellInvoke("shellBegin");
            status[0] = 1;
        }
        @Override public int[] shellStatus(long handle) throws IOException {
            shellInvoke("shellStatus");
            return status == null ? null : status.clone();
        }
        @Override public int writeShell(long handle, byte[] bytes) throws IOException {
            writes++;
            writeReference = bytes;
            shellInvoke("shellWrite");
            return accepted;
        }
        @Override public byte[] readShell(long handle, int maximum) throws IOException {
            reads++;
            readReference = output == null ? null : output.clone();
            shellInvoke("shellRead");
            return readReference;
        }
        @Override public int resizeShell(long handle, int columns, int rows) throws IOException {
            shellInvoke("shellResize");
            return resizeAccepted;
        }
        @Override public int sendShellEof(long handle) throws IOException {
            shellInvoke("shellEof");
            if (eofAccepted == 1) status[2] = 1;
            return eofAccepted;
        }
    }

    private static class KeyboardNative extends ShellNative {
        byte[] challengeWire, challengeBorrow, responseBorrow, responseCopy;
        int keyboardBegins, keyboardResponds;
        @Override public void beginKeyboardInteractiveAuth(long handle) throws IOException {
            assertEquals(903, handle); keyboardBegins++; invoke("keyboardBegin"); result=1;
        }
        @Override public int keyboardInteractiveAuthResult(long handle) throws IOException {
            assertEquals(903, handle); invoke("keyboardResult"); return result;
        }
        @Override public byte[] keyboardInteractiveChallenge(long handle) throws IOException {
            assertEquals(903, handle); invoke("keyboardChallenge");
            challengeBorrow=challengeWire.clone(); return challengeBorrow;
        }
        @Override public void respondKeyboardInteractive(long handle, byte[] response) throws IOException {
            assertEquals(903, handle); keyboardResponds++; responseBorrow=response; responseCopy=response.clone();
            invoke("keyboardResponse"); result=1;
        }
    }

    private static class MfaNative extends KeyboardNative {
        int continuationCalls, discoveredKeyboardCalls, discoveredPasswordCalls, receiptMutation, passwordOriginal;
        int automaticContinuationOriginal, automaticContinuationStage, continuationMethods = 2;
        int passwordStage = 2, privateContinuationCalls, privateContinuationStage, promptPolls, promptMethod;
        byte[] borrowedPlan, borrowedStage, borrowedPassword, borrowedPollPlan;
        @Override public byte[] continueKeyboardInteractiveAuth(long handle, byte[] plan) throws IOException {
            continuationCalls++; borrowedPlan = plan; invoke("continuation");
            boolean automatic = automaticContinuationOriginal != 0;
            java.nio.ByteBuffer stage = java.nio.ByteBuffer.allocate(92).putInt(0x43534150)
                    .putShort((short)(automatic ? 2 : 1)).putShort((short)0)
                    .putInt(automatic ? automaticContinuationStage : 2)
                    .putInt(automatic ? automaticContinuationOriginal : keyBegins > 0 ? 4 : 1)
                    .putInt(3).put(digest).putLong(1).put(filled(32,7));
            borrowedStage = stage.array();
            if (receiptMutation >= 0 && receiptMutation < 92) borrowedStage[receiptMutation] ^= 1;
            if (receiptMutation == 92) borrowedStage = java.util.Arrays.copyOf(borrowedStage, 93);
            result = 1; return borrowedStage;
        }
        @Override public byte[] beginDiscoveredKeyboard(long handle, byte[] plan) throws IOException {
            discoveredKeyboardCalls++; borrowedPlan = plan; invoke("discoveredKeyboard");
            borrowedStage = java.nio.ByteBuffer.allocate(92).putInt(0x43534150)
                    .putShort((short)2).putShort((short)0).putInt(2).putInt(4).putInt(3)
                    .put(digest).putLong(1).put(filled(32,7)).array();
            if (receiptMutation >= 0 && receiptMutation < 92) borrowedStage[receiptMutation] ^= 1;
            if (receiptMutation == 92) borrowedStage = java.util.Arrays.copyOf(borrowedStage, 93);
            result = 1; return borrowedStage;
        }
        @Override public byte[] beginDiscoveredPassword(long handle, byte[] plan, byte[] password) throws IOException {
            discoveredPasswordCalls++; borrowedPlan = plan; borrowedPassword = password; invoke("discoveredPassword");
            borrowedStage = java.nio.ByteBuffer.allocate(92).putInt(0x43534150)
                    .putShort((short)2).putShort((short)0).putInt(passwordStage).putInt(passwordOriginal).putInt(1)
                    .put(digest).putLong(1).put(filled(32,7)).array();
            if (receiptMutation >= 0 && receiptMutation < 92) borrowedStage[receiptMutation] ^= 1;
            if (receiptMutation == 92) borrowedStage = java.util.Arrays.copyOf(borrowedStage, 93);
            result = 1; return borrowedStage;
        }
        @Override public int authContinuationMethods(long handle) throws IOException {
            invoke("continuationMethods"); return continuationMethods;
        }
        @Override public void pollAuthPrompt(long handle, byte[] plan, int method) throws IOException {
            promptPolls++; promptMethod = method; borrowedPollPlan = plan; invoke("promptPoll");
        }
        @Override public byte[] continuePrivateKeyAuth(long handle, byte[] plan) throws IOException {
            privateContinuationCalls++; borrowedPlan = plan; invoke("privateContinuation");
            borrowedStage = java.nio.ByteBuffer.allocate(92).putInt(0x43534150)
                    .putShort((short)2).putShort((short)0).putInt(privateContinuationStage).putInt(4).putInt(4)
                    .put(digest).putLong(1).put(filled(32,7)).array();
            if (receiptMutation >= 0 && receiptMutation < 92) borrowedStage[receiptMutation] ^= 1;
            if (receiptMutation == 92) borrowedStage = java.util.Arrays.copyOf(borrowedStage, 93);
            result = 1; return borrowedStage;
        }
    }

    private static void mfaReady(Setup setup, MfaNative binding, boolean key, boolean continuation) throws Exception {
        mfaReady(setup,binding,key,new SshPasswordConnector.ContinuationPolicy(
                continuation ? SshPasswordConnector.ContinuationMode.STRICT : SshPasswordConnector.ContinuationMode.NONE,false));
    }

    private static void mfaReady(Setup setup, MfaNative binding, boolean key,
            SshPasswordConnector.ContinuationPolicy continuation) throws Exception {
        SshAuthenticationSelection selection = key ? SshAuthenticationSelection.privateKey(setup.issued.role,
                "user", "keys/key", "identity", bytes("public"), setup.clock)
                : SshAuthenticationSelection.password(setup.issued.role, "user", "password", setup.clock);
        selection = continuation.select(selection,setup.clock);
        setup.callback.selectAuthentication(selection);
        setup.session = open(setup);
        assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                filled(32,2), filled(32,3)));
        assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
        ready(setup, binding);
        if (key) setup.session.beginPrivateKeyAuth(); else setup.session.beginPasswordAuth();
    }

    @Test public void mfaTransitionRetainsPlanAndKexAndIsSingleUseBeforeFinalShell() throws Exception {
        for (boolean key : new boolean[]{false,true}) {
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            Setup setup = new Setup(true, binding);
            try {
                mfaReady(setup, binding, key, true);
                binding.result = 4; setup.session.step();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80,24));
                assertCode("SSH_SESSION_PHASE", () -> setup.session.configureNoPty());
                setup.session.continueKeyboardInteractiveAuth();
                assertArrayEquals(new byte[32], binding.borrowedPlan);
                assertArrayEquals(new byte[92], binding.borrowedStage);
                Object stage = setup.session.keyboardStage(); setup.session.requireKeyboardStageLive(stage);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.requireKeyboardStageLive(new Object()));
                assertCode("SSH_SESSION_PHASE", setup.session::continueKeyboardInteractiveAuth);
                assertCode("SSH_SESSION_PHASE", setup.session::beginPasswordAuth);
                assertCode("SSH_SESSION_PHASE", setup.session::beginPrivateKeyAuth);
                binding.challengeWire = SshKeyboardChallengeTest.frame(1,"server","notice","Code"); binding.result = 5;
                setup.session.step(); SshKeyboardChallenge challenge = setup.session.keyboardInteractiveChallenge();
                setup.session.respondKeyboardInteractive(challenge, challenge.response(List.of("")));
                binding.result = 2; setup.session.step();
                setup.session.configureServerAlive(15, 3);
                assertEquals(1, binding.aliveCalls);
                setup.session.configureNoPty(); assertEquals(1, binding.noPtyCalls);
                setup.session.beginShell(80,24);
                assertEquals(1,binding.continuationCalls); assertEquals(key ? 1 : 0,binding.keyBegins);
                assertEquals(key ? 0 : 1,binding.authBegins); assertEquals(0,binding.keyboardBegins);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void firstFactorPartialRequiresExplicitContinuation() throws Exception {
        for (boolean continuation : new boolean[] {false, true}) {
            assertFalse(SshPasswordConnector.firstFactorNeedsContinuation(true, false, continuation));
            assertCode("SSH_AUTHENTICATION", () ->
                    SshPasswordConnector.firstFactorNeedsContinuation(false, false, continuation));
        }
        assertTrue(SshPasswordConnector.firstFactorNeedsContinuation(false, true, true));
        assertCode("SSH_AUTH_PARTIAL", () ->
                SshPasswordConnector.firstFactorNeedsContinuation(false, true, false));
    }

    @Test public void automaticPartialWithoutAuthorizedContinuationDoesNotGuessTheNextMethod() throws Exception {
        for (boolean key : new boolean[]{false,true}) {
            SshPasswordConnector.ContinuationPolicy policy = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnector.ContinuationMode.AUTOMATIC,true);
            policy.acceptConfiguredCapability(false);
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            Setup setup = new Setup(true,binding);
            try {
                mfaReady(setup,binding,key,policy); binding.result = 4; setup.session.step();
                // No server method list is retained in this selection; PARTIAL could also require another key.
                assertCode("SSH_AUTH_PARTIAL", () ->
                        SshPasswordConnector.firstFactorNeedsContinuation(false,true,policy.enabled()));
                assertEquals(0,binding.continuationCalls);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80,24));
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void automaticBridgeRequestUsesTheProductionSelectionAndSessionStages() throws Exception {
        for (boolean key : new boolean[]{false,true}) for (boolean configured : new boolean[]{false,true})
                for (boolean allowed : new boolean[]{false,true}) {
            if (!configured && !allowed) continue;
            String method = configured ? key ? "ssh.configConnect" : "ssh.configPasswordConnect"
                    : key ? "ssh.keyConnect" : "ssh.passwordConnect";
            java.util.Map<String,Object> arguments = new java.util.HashMap<>(VaultCollections.map(
                    "requestId","request","username","user","columns",80,"rows",24,"autoKeyboardInteractive",true));
            if (configured) { arguments.put("alias","host"); arguments.put("binding","a".repeat(64)); }
            else { arguments.put("host","host.invalid"); arguments.put("port",22); }
            if (key) arguments.put("keyId","key");
            else {
                arguments.put("password","synthetic");
                if (configured) { arguments.put("useSavedPassword",false); arguments.put("savePassword",false); }
            }
            SshPasswordConnector.ContinuationPolicy policy = new SshPasswordConnector.ContinuationPolicy(
                    SshPasswordConnectBridge.authenticationContinuationMode(new io.flutter.plugin.common.MethodCall(method,arguments)),
                    configured);
            if (configured) policy.acceptConfiguredCapability(allowed);
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            Setup setup = new Setup(true,binding);
            try {
                mfaReady(setup,binding,key,policy);
                assertEquals(allowed ? 3 : 1,java.nio.ByteBuffer.wrap(binding.wireCopy).getShort(4));
                if (allowed) {
                    binding.result = 4; setup.session.step();
                    assertTrue(SshPasswordConnector.firstFactorNeedsContinuation(false,true,
                            policy.enabled()));
                    setup.session.continueKeyboardInteractiveAuth();
                    binding.result = 2; setup.session.step();
                } else {
                    binding.result = 2; setup.session.step();
                    assertFalse(SshPasswordConnector.firstFactorNeedsContinuation(true,false,
                            policy.enabled()));
                }
                setup.session.beginShell(80,24);
                assertEquals(allowed ? 1 : 0,binding.continuationCalls);
                assertEquals(key ? 1 : 0,binding.keyBegins);
                assertEquals(key ? 0 : 1,binding.authBegins);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void mfaReceiptsRejectEveryBindingFieldAndWipeNativeOwnedBytes() throws Exception {
        for (int mutation : new int[]{0,4,6,8,12,16,20,51,52,59,60,91,92}) {
            MfaNative binding = new MfaNative(); binding.receiptMutation = mutation;
            Setup setup = new Setup(true, binding);
            try {
                mfaReady(setup,binding,false,true); binding.result = 4; setup.session.step();
                assertCode("SSH_MFA_AUTH",setup.session::continueKeyboardInteractiveAuth);
                assertCode("SSH_MFA_AUTH",setup.session::continueKeyboardInteractiveAuth);
                assertArrayEquals(new byte[32],binding.borrowedPlan);
                assertArrayEquals(new byte[binding.borrowedStage.length],binding.borrowedStage);
                assertEquals(1,binding.continuationCalls);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void mfaNeedsExplicitPlanPartialAndOriginalLiveOwner() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            Setup setup = new Setup(true,binding);
            try {
                mfaReady(setup,binding,false,mode != 0); binding.result = mode == 1 ? 3 : mode == 2 ? 2 : 4;
                setup.session.step();
                if (mode == 3) setup.session.cancel();
                if (mode == 4) setup.clock.value = setup.issued.role.deadline;
                if (mode == 5) {
                    AtomicReference<Throwable> observed = new AtomicReference<>();
                    Thread thread = new Thread(() -> { try { setup.session.continueKeyboardInteractiveAuth(); }
                        catch (Throwable failure) { observed.set(failure); } });
                    thread.start(); thread.join();
                    assertEquals("SSH_SESSION_THREAD",((VaultException)observed.get()).code);
                    setup.session.continueKeyboardInteractiveAuth(); assertEquals(1,binding.continuationCalls);
                } else {
                    String code = mode == 0 ? "SSH_AUTH_SELECTION" : mode == 3 ? "SSH_SESSION_CANCELLED"
                        : mode == 4 ? "SSH_SESSION_EXPIRED" : "SSH_SESSION_PHASE";
                    assertCode(code,setup.session::continueKeyboardInteractiveAuth); assertEquals(0,binding.continuationCalls);
                }
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void keyboardRoundsStayOwnerBoundAndWipeEveryNativeBorrowBeforeSuccessfulShell() throws Exception {
        KeyboardNative binding=new KeyboardNative(); Setup setup=new Setup(true,binding);
        try {
            setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock));
            setup.session=open(setup);
            assertCode("SSH_SESSION_PHASE",()->setup.session.beginKeyboardInteractiveAuth());
            ready(setup,binding); setup.session.beginKeyboardInteractiveAuth();
            for(int round=1;round<=16;round++){
                binding.challengeWire=SshKeyboardChallengeTest.frame(round,"server","notice","?"); binding.result=5;
                setup.session.step();
                assertEquals(SshNativeSession.KeyboardInteractiveAuthResult.CHALLENGE,setup.session.keyboardInteractiveAuthResult());
                SshKeyboardChallenge challenge=setup.session.keyboardInteractiveChallenge();
                assertSame(challenge,setup.session.keyboardInteractiveChallenge());
                assertArrayEquals(new byte[binding.challengeBorrow.length],binding.challengeBorrow);
                assertCode("SSH_SESSION_PHASE",()->setup.session.beginShell(80,24));
                byte[] response=challenge.response(java.util.List.of(""));
                setup.session.respondKeyboardInteractive(challenge,response);
                assertArrayEquals(response,binding.responseCopy);
                assertArrayEquals(new byte[response.length],binding.responseBorrow);
                assertCode("SSH_SESSION_PHASE",()->setup.session.respondKeyboardInteractive(challenge,response));
                assertEquals(SshNativeSession.KeyboardInteractiveAuthResult.PENDING,setup.session.keyboardInteractiveAuthResult());
                assertCode("SSH_SESSION_PHASE",()->setup.session.passwordAuthResult());
                assertCode("SSH_SESSION_PHASE",()->setup.session.privateKeyAuthResult());
                VaultCodec.wipe(response);
            }
            binding.result=2;setup.session.step();setup.session.configureServerAlive(15,3);
            assertEquals(1,binding.aliveCalls);setup.session.configureNoPty();
            assertEquals(1,binding.noPtyCalls);setup.session.beginShell(80,24);
            assertEquals(1,binding.keyboardBegins);assertEquals(16,binding.keyboardResponds);
            assertCode("SSH_SESSION_PHASE",()->setup.session.keyboardInteractiveAuthResult());
        }finally{if(setup.session!=null)setup.session.close();setup.closeRegistry();}
    }

    @Test public void keyboardMalformedWireReplayPartialAndPrematureSuccessCannotPromote() throws Exception {
        for(int scenario=0;scenario<5;scenario++){
            KeyboardNative binding=new KeyboardNative();Setup setup=new Setup(true,binding);
            try{
                setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock));
                setup.session=open(setup);ready(setup,binding);setup.session.beginKeyboardInteractiveAuth();
                if(scenario<3){
                    binding.result=5;binding.challengeWire=SshKeyboardChallengeTest.frame(scenario==0?2:1,"","","?");
                    if(scenario==1)binding.challengeWire[4]=1;
                    setup.session.step();
                    if(scenario<2)assertCode("SSH_KEYBOARD_CHALLENGE",()->setup.session.keyboardInteractiveChallenge());
                    else{
                        setup.session.keyboardInteractiveChallenge();binding.result=2;
                        assertCode("SSH_KEYBOARD_AUTH",()->setup.session.step());
                    }
                }else{
                    binding.result=scenario==3?4:3;setup.session.step();
                    assertEquals(scenario==3?SshNativeSession.KeyboardInteractiveAuthResult.PARTIAL:
                            SshNativeSession.KeyboardInteractiveAuthResult.REJECTED,setup.session.keyboardInteractiveAuthResult());
                }
                assertCode(scenario<2?"SSH_KEYBOARD_CHALLENGE":scenario==2?"SSH_KEYBOARD_AUTH":"SSH_SESSION_PHASE",
                        ()->setup.session.beginShell(80,24));
                assertEquals(0,binding.keyboardResponds);
            }finally{setup.session.close();setup.closeRegistry();}
        }
    }

    @Test public void keyboardResponseFailureStillWipesBytesAndCancellationOutranksNativeFailure() throws Exception {
        for(boolean cancelled:new boolean[]{false,true}){
            KeyboardNative binding=new KeyboardNative();Setup setup=new Setup(true,binding);
            try{
                setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock));
                setup.session=open(setup);ready(setup,binding);setup.session.beginKeyboardInteractiveAuth();
                binding.challengeWire=SshKeyboardChallengeTest.frame(1,"","","?");binding.result=5;setup.session.step();
                SshKeyboardChallenge challenge=setup.session.keyboardInteractiveChallenge();
                byte[] response=challenge.response(java.util.List.of("private"));
                binding.failOperation="keyboardResponse";
                if(cancelled)binding.protocolHook=()->{try{setup.session.cancel();}catch(VaultException failure){throw new AssertionError(failure);}};
                assertCode(cancelled?"SSH_SESSION_CANCELLED":"SSH_KEYBOARD_CHALLENGE",
                        ()->setup.session.respondKeyboardInteractive(challenge,response));
                assertArrayEquals(new byte[response.length],binding.responseBorrow);
                assertArrayEquals(response,binding.responseCopy);VaultCodec.wipe(response);
            }finally{setup.session.close();setup.closeRegistry();}
        }
    }

    @Test public void keyboardOwnerViewAndExpiryGuardsRunBeforeOrAfterEveryNativeRead() throws Exception {
        KeyboardNative binding=new KeyboardNative();Setup setup=new Setup(true,binding);
        try{
            setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock));
            setup.session=open(setup);ready(setup,binding);
            AtomicReference<Throwable> wrong=new AtomicReference<>();
            Thread other=new Thread(()->{try{setup.session.beginKeyboardInteractiveAuth();}
                catch(Throwable failure){wrong.set(failure);}});other.start();other.join();
            assertTrue(wrong.get() instanceof VaultException);assertEquals("SSH_SESSION_THREAD",((VaultException)wrong.get()).code);
            assertEquals(0,binding.keyboardBegins);setup.session.beginKeyboardInteractiveAuth();
            binding.challengeWire=SshKeyboardChallengeTest.frame(1,"","","?");binding.result=5;setup.session.step();
            binding.protocolHook=()->setup.clock.value=setup.issued.role.deadline;
            assertCode("SSH_SESSION_EXPIRED",()->setup.session.keyboardInteractiveChallenge());
            assertArrayEquals(new byte[binding.challengeBorrow.length],binding.challengeBorrow);
            assertEquals(0,binding.keyboardResponds);
        }finally{setup.session.close();setup.closeRegistry();}
        KeyboardNative expired=new KeyboardNative();Setup invalid=new Setup(true,expired);
        try{
            invalid.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(invalid.issued.role,"user",invalid.clock));
            invalid.session=open(invalid);ready(invalid,expired);invalid.session.beginKeyboardInteractiveAuth();
            invalid.view.active=false;
            assertCode("SSH_FILE_VIEW",()->invalid.session.keyboardInteractiveAuthResult());
        }finally{invalid.session.close();invalid.closeRegistry();}
    }

    @Test public void keyboardNativeErrorsAreTerminalAndCannotRepeatAnAnswer() throws Exception {
        for(String operation:new String[]{"keyboardResult","keyboardChallenge","keyboardResponse"}){
            KeyboardNative binding=new KeyboardNative();Setup setup=new Setup(true,binding);
            try{
                setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock));
                setup.session=open(setup);ready(setup,binding);setup.session.beginKeyboardInteractiveAuth();
                binding.challengeWire=SshKeyboardChallengeTest.frame(1,"","","?");binding.result=5;setup.session.step();
                SshKeyboardChallenge challenge=operation.equals("keyboardChallenge")?null:setup.session.keyboardInteractiveChallenge();
                byte[] response=challenge==null?null:challenge.response(java.util.List.of("private"));
                binding.failOperation=operation;binding.failureKind=3;
                try{
                    if(operation.equals("keyboardResult"))setup.session.keyboardInteractiveAuthResult();
                    else if(operation.equals("keyboardChallenge"))setup.session.keyboardInteractiveChallenge();
                    else setup.session.respondKeyboardInteractive(challenge,response);
                    fail("Native Error must propagate after cleanup");
                }catch(AssertionError expected){assertEquals("synthetic native Error",expected.getMessage());}
                String code=operation.equals("keyboardResult")?"SSH_KEYBOARD_AUTH":"SSH_KEYBOARD_CHALLENGE";
                assertCode(code,()->setup.session.keyboardInteractiveAuthResult());
                if(binding.responseBorrow!=null){
                    assertArrayEquals(new byte[binding.responseBorrow.length],binding.responseBorrow);
                    assertCode(code,()->setup.session.respondKeyboardInteractive(challenge,response));
                    assertEquals(1,binding.keyboardResponds);
                }
                VaultCodec.wipe(response);
            }finally{setup.session.close();setup.closeRegistry();}
        }
    }

    private static class ManagedShellNative extends ShellNative {
        int managedOpenCalls;
        String expectedKnownHostsPath = "ssh/known_hosts";
        String openedKnownHostsPath;

        @Override public long openManaged(long role, int ownerUid, long callback, long view,
                int socketFd, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException {
            assertEquals(31, role);
            assertEquals(42, ownerUid);
            assertEquals(901, callback);
            assertEquals(902, view);
            assertEquals(31_000, deadline);
            assertEquals(1024, stepBudget);
            assertEquals(expectedKnownHostsPath, knownHostsPath);
            openedKnownHostsPath = knownHostsPath;
            assertEquals("example.invalid", host);
            assertEquals("candidate_1", candidateId);
            this.socketFd = socketFd;
            wireReference = selectionWire;
            wireCopy = selectionWire == null ? null : selectionWire.clone();
            digest = selectionWire == null ? null : VaultCodec.digest(selectionWire);
            managedOpenCalls++;
            order.add("managedOpen");
            if (openHook != null) openHook.run();
            if (failOpen) throw new IOException("fixture managed open failure");
            return 903;
        }
    }

    private static final class JumpNative extends ManagedShellNative {
        int[] jump = {1, 0, 0, 0};
        int[] middleJump = {1, 0, 0, 0};
        int[] childShell = {0, 0, 0, 0, 0, 0, 0};
        int[] targetShell = {0, 0, 0, 0, 0, 0, 0};
        int childState;
        int nextOpenState = 4;
        int stateFailures;
        int abortFailures;
        int childCloseFailures;
        int childDestroyFailures;
        int childResult;
        int reserves;
        int aborts;
        int childCloses;
        int childDestroys;
        int jumpBegins;
        int jumpRetains;
        int relayPumps;
        int jumpStatusReads;
        int childReceiptReads;
        int childShellBegins;
        int childShellStatusReads;
        boolean failReserve;
        boolean failChildOpen;
        long failRetainHandle;
        Runnable childOpenHook;
        Runnable childStepHook;
        byte[] childDigest;
        byte[] targetDigest;
        byte[] freshInput, freshCopy;

        @Override public long reserveJumpChild(long parentHandle) throws IOException {
            assertTrue(parentHandle == 903 || parentHandle == 904);
            reserves++;
            if (failReserve) throw new IOException("fixture reservation failure");
            childState = 0;
            return parentHandle == 903 ? 904 : 905;
        }

        @Override public boolean openJumpChild(long handle, long role, int uid, long callback,
                long view, long deadline, int budget, String knownHosts, String host,
                String candidate, byte[] selection) throws IOException {
            assertTrue(handle == 904 || handle == 905);
            assertEquals(handle == 904 ? 32 : 33, role);
            assertEquals(42, uid);
            assertEquals(901, callback);
            assertEquals(902, view);
            assertEquals(31_000, deadline);
            assertEquals(1024, budget);
            assertEquals("ssh/known_hosts", knownHosts);
            assertEquals("example.invalid", host);
            assertEquals("candidate_1", candidate);
            if (handle == 904) childDigest = selection == null ? null : VaultCodec.digest(selection);
            else targetDigest = selection == null ? null : VaultCodec.digest(selection);
            childState = nextOpenState;
            order.add("childOpen");
            if (childOpenHook != null) childOpenHook.run();
            if (failChildOpen) throw new IOException("fixture child open failure");
            return childState == 4;
        }

        @Override public boolean openJumpChildFresh(long handle, long role, int uid, long callback,
                long view, long deadline, int budget, String knownHosts, String host,
                String candidate, byte[] selection, byte[] fresh) throws IOException {
            freshInput = fresh;
            freshCopy = fresh == null ? null : fresh.clone();
            return openJumpChild(handle, role, uid, callback, view, deadline, budget,
                    knownHosts, host, candidate, selection);
        }

        @Override public int jumpChildState(long handle) throws IOException {
            assertTrue(handle == 904 || handle == 905);
            if (stateFailures-- > 0) throw new IOException("fixture state failure");
            return childState;
        }

        @Override public void abortJumpChild(long handle) throws IOException {
            assertTrue(handle == 904 || handle == 905);
            aborts++;
            if (abortFailures-- > 0) throw new IOException("fixture abort failure");
            assertTrue(childState < 2);
            order.add("childAbort");
        }

        @Override public void beginJump(long handle, long nextRole) {
            assertTrue(handle == 903 || handle == 904);
            assertEquals(handle == 903 ? 32 : 33, nextRole);
            jumpBegins++;
            order.add("jumpBegin");
        }

        @Override public int[] jumpStatus(long handle) {
            assertTrue(handle == 903 || handle == 904);
            jumpStatusReads++;
            int[] status = handle == 903 ? jump : middleJump;
            return status == null ? null : status.clone();
        }

        @Override public void pumpJumpRelay(long handle) {
            assertTrue(handle == 904 || handle == 905);
            relayPumps++;
            order.add("pump:" + handle);
        }

        @Override public void retainJumpTransport(long handle) throws IOException {
            assertTrue(handle == 903 || handle == 904);
            order.add("retain:" + handle);
            if (failRetainHandle == handle) throw new IOException("fixture retention failure");
            jumpRetains++;
            order.add("jumpRetain");
        }

        @Override public SshNativeSession.StepResult step(long handle, boolean cancelled) throws IOException {
            order.add("step:" + handle);
            if (handle == 903) return super.step(handle, cancelled);
            assertTrue(handle == 904 || handle == 905);
            if (cancelled) throw new IOException("fixture cancellation");
            order.add("childStep");
            if (childStepHook != null) childStepHook.run();
            return new SshNativeSession.StepResult(4, 5, 4, 0, true, false, false, true);
        }

        @Override public void beginUserauthService(long handle) throws IOException {
            if (handle == 903) { super.beginUserauthService(handle); return; }
            assertTrue(handle == 904 || handle == 905);
            order.add("childService");
        }

        @Override public boolean userauthServiceReady(long handle) throws IOException {
            if (handle == 903) return super.userauthServiceReady(handle);
            assertTrue(handle == 904 || handle == 905);
            return true;
        }

        @Override public void beginPasswordAuth(long handle) throws IOException {
            if (handle == 903) { super.beginPasswordAuth(handle); return; }
            assertTrue(handle == 904 || handle == 905);
            childResult = 1;
        }

        @Override public int passwordAuthResult(long handle) throws IOException {
            if (handle == 903) return super.passwordAuthResult(handle);
            assertTrue(handle == 904 || handle == 905);
            return childResult;
        }

        @Override public byte[] authenticationSelectionReceipt(long handle) throws IOException {
            if (handle == 903) return super.authenticationSelectionReceipt(handle);
            assertTrue(handle == 904 || handle == 905);
            childReceiptReads++;
            return (handle == 904 ? childDigest : targetDigest).clone();
        }

        @Override public void beginShell(long handle, int columns, int rows) throws IOException {
            if (handle == 903) { super.beginShell(handle, columns, rows); return; }
            assertTrue(handle == 904 || handle == 905);
            childShellBegins++;
            (handle == 904 ? childShell : targetShell)[0] = 1;
        }

        @Override public int[] shellStatus(long handle) throws IOException {
            if (handle == 903) return super.shellStatus(handle);
            assertTrue(handle == 904 || handle == 905);
            childShellStatusReads++;
            return (handle == 904 ? childShell : targetShell).clone();
        }

        int observedCalls() {
            return order.size() + shellCalls + jumpStatusReads + childReceiptReads
                    + childShellBegins + childShellStatusReads;
        }

        @Override public void close(long handle) throws IOException {
            if (handle == 903) { super.close(handle); return; }
            assertTrue(handle == 904 || handle == 905);
            childCloses++;
            order.add("close:" + handle);
            if (childCloseFailures-- > 0) throw new IOException("fixture child close failure");
            order.add("childClose");
        }

        @Override public void destroy(long handle) throws IOException {
            if (handle == 903) { super.destroy(handle); return; }
            assertTrue(handle == 904 || handle == 905);
            childDestroys++;
            order.add("destroy:" + handle);
            if (childDestroyFailures-- > 0) throw new IOException("fixture relay destroy failure");
            order.add("childDestroy");
        }
    }

    private static final class Setup {
        final Clock clock = new Clock();
        final SshRoleRegistry registry;
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
            this(selectedBinding, nativeBinding, null);
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding, ViewLease sharedView) throws Exception {
            this(selectedBinding, nativeBinding, sharedView, 31, 0, false);
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding, ViewLease sharedView,
                long token, int hop, boolean jump) throws Exception {
            this(selectedBinding, nativeBinding, sharedView, token, hop, jump, null);
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding, ViewLease sharedView,
                long token, int hop, boolean jump, FreshCallbackFactory factory) throws Exception {
            this(selectedBinding, nativeBinding, sharedView, token, hop, jump, factory, false);
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding, ViewLease sharedView,
                long token, int hop, boolean jump, FreshCallbackFactory factory, boolean chained) throws Exception {
            this(selectedBinding, nativeBinding, sharedView, token, hop, jump, factory, chained, null);
        }

        Setup(boolean selectedBinding, SessionNative nativeBinding, ViewLease sharedView,
                long token, int hop, boolean jump, FreshCallbackFactory factory, boolean chained,
                SshRoleRegistry sharedRegistry) throws Exception {
            sessionNative = nativeBinding;
            view = sharedView == null ? new ViewLease(sessionNative.order) : sharedView;
            registry = sharedRegistry == null ? new SshRoleRegistry(clock) : sharedRegistry;
            issued = SshNativeRoleBootstrap.issue(chained ? chainedRecord(token, 42, 1001, hop, jump)
                    : record(token, 42, 1001, hop, jump), registry,
                    view, clock, ignored -> true);
            SshHostKeyAuthority authority = new SshHostKeyAuthority();
            SshManagedProvider provider = selectedBinding ? metadataOnlyProvider(authority, clock) : null;
            callback = factory == null ? SshNativeCallbackAdapter.bind(issued, registry,
                    authority, clock, callbackNative, roleRelease, provider) : factory.create(this, authority);
        }

        void closeRegistry() { registry.close(); }
    }

    private interface FreshCallbackFactory {
        SshNativeCallbackAdapter create(Setup setup, SshHostKeyAuthority authority) throws Exception;
    }

    /** Java protocol state uses a fake native peer; no real native proof is claimed. */
    private static final class AdmissionNative extends MfaNative {
        @Override public long openManaged(long role, int ownerUid, long callback, long view,
                int socketFd, long deadline, int stepBudget, String path, String host,
                String candidate, byte[] selectionWire) throws IOException {
            assertEquals(31, role); assertEquals(42, ownerUid); assertEquals(901, callback);
            assertEquals(902, view); assertEquals(31_000, deadline); assertEquals(1024, stepBudget);
            wireReference = selectionWire;
            digest = selectionWire == null ? null : VaultCodec.digest(selectionWire);
            return 903;
        }
    }

    private static final class AdmissionFixture implements AutoCloseable {
        final AdmissionNative binding = new AdmissionNative();
        final Setup parent;
        final SshNativeRoleBootstrap.Issued next;
        final SshSharedPublicView shared;

        AdmissionFixture(int mode) throws Exception { this(mode, 0); }

        AdmissionFixture(int mode, int parentHop) throws Exception {
            binding.receiptMutation = -1;
            parent = new Setup(true, binding, null, 31, parentHop, true, null, true);
            next = SshNativeRoleBootstrap.issue(chainedRecord(32, 42, 1001, parentHop + 1, false),
                    parent.registry, parent.view, parent.clock, ignored -> true);
            shared = new SshSharedPublicView(parent.view, parent.view, parent.view::revoke);
            SshAuthenticationSelection selected = mode == 1
                    ? SshAuthenticationSelection.privateKey(parent.issued.role, "user", "keys/key",
                        "identity", bytes("public"), parent.clock)
                    : mode == 2 || mode == 3
                    ? SshAuthenticationSelection.keyboardInteractive(parent.issued.role, "user", parent.clock)
                    : SshAuthenticationSelection.password(parent.issued.role, "user", "password", parent.clock);
            if (mode == 3) selected = selected.withAutomaticDiscovery(0, parent.clock);
            if (mode == 4) selected = selected.withKeyboardContinuation(parent.clock);
            parent.callback.selectAuthentication(selected);
            parent.session = SshNativeSession.openManaged(parent.callback, shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts", "candidate_1", 1024, binding);
            assertTrue(parent.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                    "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32, 7)),
                    filled(32, 2), filled(32, 3)));
            assertTrue(parent.callback.onNativeKexComplete(1, filled(32, 7)));
            ready(parent, binding);
        }

        void success(int mode) throws Exception {
            if (mode == 3) {
                parent.session.beginAuthDiscovery(); binding.discovery = new int[] {3, 0}; parent.session.step();
            } else if (mode == 2) {
                parent.session.beginKeyboardInteractiveAuth(); binding.result = 2; parent.session.step();
            } else {
                if (mode == 1) parent.session.beginPrivateKeyAuth(); else parent.session.beginPasswordAuth();
                binding.result = mode == 4 ? 4 : 2; parent.session.step();
                if (mode == 4) {
                    parent.session.continueKeyboardInteractiveAuth(); binding.result = 2; parent.session.step();
                }
            }
        }

        SshNativeSession.JumpAdmission issue() throws VaultException {
            return parent.session.issueJumpAdmission(parent.issued, next, shared, parent.view);
        }

        @Override public void close() throws Exception {
            if (parent.session != null) parent.session.close();
            parent.callback.close();
            shared.seal(); shared.close(); parent.closeRegistry();
        }
    }

    /** Real encrypted in-memory vault; native session and public view stay test doubles. */
    private static final class FreshVault {
        static final byte[] HOSTS = bytes("example.invalid ssh-ed25519 fixture\n");
        final AppSecrets vault;
        final SshVaultAdapter adapter;
        final SshManagedKnownHostsProvider.ChildTrustSnapshot source;
        final SshNativeCallbackAdapter callback;

        FreshVault(Setup setup, SshHostKeyAuthority authority) throws Exception {
            char[] password = "child trust test passphrase".toCharArray();
            byte[] salt = filled(16, 7), key = VaultEnvelope.derive(password, salt), plain = null;
            final byte[] envelope;
            try (VaultManifest manifest = new VaultManifest(List.of(), List.of(
                    new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, "config", "ssh/config",
                            "config identity", bytes("Host example.invalid\n"), List.of("ssh/known_hosts")),
                    new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, "hosts", "ssh/known_hosts",
                            "hosts identity", HOSTS, List.of())))) {
                plain = manifest.encode();
                envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode();
            } finally { VaultCodec.wipe(key); VaultCodec.wipe(plain); }
            AppSecrets.Store store = new AppSecrets.Store() {
                @Override public byte[] read() { return envelope.clone(); }
                @Override public byte[] quickMaterial() { return null; }
                @Override public void commit(byte[] value, byte[] quick, AppSecrets.EffectGuard guard) {
                    throw new AssertionError("unexpected trust write");
                }
            };
            vault = new AppSecrets(store, setup.clock);
            vault.attachSshValidator(manifest -> { });
            vault.session.foreground(true);
            vault.unlock(password, () -> { });
            vault.session.attachRevocation(vault.session.epoch(), setup.view);
            adapter = new SshVaultAdapter(vault, (connection, target) -> { }, proposal -> { });
            source = SshManagedKnownHostsProvider.snapshotForChild(adapter, setup.issued.role, setup.clock,
                    setup.view, "ssh/config", "ssh/known_hosts", "hosts", "hosts identity");
            SshManagedKnownHostsProvider provider =
                    SshManagedKnownHostsProvider.bindForNativeHandoff(adapter, source.receipt());
            SshKnownHostsCandidateHandoff handoff = provider.nativeCandidateHandoff();
            SshManagedProvider managed = new SshManagedProvider(new SshCredentialBridge(vault, authority, setup.clock),
                    setup.clock, provider, provider, null);
            callback = SshNativeCallbackAdapter.bind(setup.issued, setup.registry, authority, setup.clock,
                    setup.callbackNative, setup.roleRelease, managed, handoff);
            Arrays.fill(password, '\0');
        }
    }

    private static final class JumpFixture implements AutoCloseable {
        final JumpNative binding = new JumpNative();
        final ViewLease view = new ViewLease(new ArrayList<>());
        final Setup parent;
        final Setup target;
        final SshSharedPublicView shared;
        SshNativeSession.JumpChildOwner owner;

        JumpFixture() throws Exception { this(false); }

        JumpFixture(boolean childJump) throws Exception {
            this(childJump, null);
        }

        JumpFixture(boolean childJump, FreshCallbackFactory factory) throws Exception {
            this(childJump, factory, false);
        }

        JumpFixture(boolean childJump, FreshCallbackFactory factory, boolean chained) throws Exception {
            parent = new Setup(true, binding, view, 31, 0, true, null, chained);
            target = new Setup(true, binding, view, 32, 1, childJump, factory, chained,
                    chained ? parent.registry : null);
            shared = new SshSharedPublicView(view, view, () -> view.active = false);
            select(parent, false);
            select(target, false);
            parent.session = SshNativeSession.openManaged(parent.callback, shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts",
                    "candidate_1", 1024, binding);
            ready(parent, binding);
            parent.session.beginPasswordAuth();
            binding.result = 2;
            parent.session.step();
        }

        void prepare() throws Exception {
            owner = SshNativeSession.prepareJumpChild(parent.session, target.callback, shared,
                    "ssh/known_hosts", "candidate_1", 1024, binding);
        }

        void begin() throws Exception {
            prepare();
            parent.session.beginJump(owner);
            binding.jump[0] = 2;
        }

        void authenticateChild() throws Exception {
            authenticateChild(null);
        }

        void authenticateChild(AppSecrets.EffectGuard confirmation) throws Exception {
            authenticateChildBeforeShell(confirmation);
            target.session.beginShell(80, 24);
        }

        void authenticateChildBeforeShell(AppSecrets.EffectGuard confirmation) throws Exception {
            target.session = owner.open();
            target.session.step();
            target.session.beginUserauthService();
            assertTrue(target.session.userauthServiceReady());
            target.session.beginPasswordAuth();
            binding.childResult = 2;
            target.session.step();
            if (confirmation != null) target.session.confirmShellWith(confirmation);
        }

        @Override public void close() throws Exception {
            binding.stateFailures = 0;
            binding.abortFailures = 0;
            binding.childCloseFailures = 0;
            binding.childDestroyFailures = 0;
            if (owner != null) owner.close();
            if (parent.session != null) parent.session.close();
            target.callback.close();
            parent.callback.close();
            shared.seal();
            shared.close();
            target.closeRegistry();
            parent.closeRegistry();
        }
    }

    private static final class TerminalProbe implements SshTerminalSession.Scheduler, SshTerminalSession.Sink {
        final ArrayDeque<Runnable> turns = new ArrayDeque<>();
        final List<String> states = new ArrayList<>();
        final List<String> order;
        int outputs;

        TerminalProbe(List<String> order) { this.order = order; }
        @Override public void later(Runnable turn, long delayMillis) { turns.add(turn); }
        @Override public void data(byte[] bytes, Runnable consumed) { outputs++; consumed.run(); }
        @Override public void state(String state, Long status, String reason) {
            states.add(state);
            order.add("state:" + state);
        }
        void next() { assertFalse(turns.isEmpty()); turns.remove().run(); }
    }

    @Test public void legacyJumpFixtureCannotUseAManagedLease() throws Exception {
        // This lease is an identity/ownership fixture, not a real JNI allocation.
        java.lang.reflect.Constructor<SshPublicFileLease> constructor =
                SshPublicFileLease.class.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        SshPublicFileLease lease = constructor.newInstance(902L);
        SshSharedPublicView shared = new SshSharedPublicView(lease);
        Clock clock = new Clock();
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        ManagedShellNative binding = new ManagedShellNative();
        SshNativeRoleBootstrap.Issued root = SshNativeRoleBootstrap.issue(
                chainedRecord(31, 42, 1001, 0, true), registry, lease, clock, ignored -> true);
        SshNativeRoleBootstrap.Issued child = SshNativeRoleBootstrap.issue(
                chainedRecord(32, 42, 1001, 1, false), registry, lease, clock, ignored -> true);
        SshNativeCallbackAdapter parentCallback = SshNativeCallbackAdapter.bind(root, registry,
                new SshHostKeyAuthority(), clock, new CallbackNative(), new RoleRelease());
        SshNativeCallbackAdapter childCallback = SshNativeCallbackAdapter.bind(child, registry,
                new SshHostKeyAuthority(), clock, new CallbackNative(), new RoleRelease());
        SshNativeSession parent = null;
        try {
            parent = SshNativeSession.openManaged(parentCallback, shared,
                    new SshSocketOwnership(41, ignored -> fail("native owner adopted socket")),
                    "ssh/known_hosts", "candidate_1", 1024, binding);
            SshNativeSession exactParent = parent;
            assertCode("SSH_JUMP_EFFECT_GUARD", () -> SshNativeSession.prepareJumpChild(
                    exactParent, childCallback, shared, "ssh/known_hosts", "candidate_1", 1024, binding));
            assertEquals(1, binding.managedOpenCalls);
            assertTrue(lease.active());
            childCallback.requireNativeSessionLive();
            // No failed child owner may prevent the original session from closing.
            parent.close();
            parent = null;
        } finally {
            if (parent != null) parent.close();
            childCallback.close();
            parentCallback.close();
            shared.seal();
            java.lang.reflect.Field token = SshPublicFileLease.class.getDeclaredField("token");
            token.setAccessible(true);
            token.setLong(lease, 0L); // The fixture token never represented a native lease.
            shared.close();
            registry.close();
        }
    }

    @Test public void legacyJumpFixtureRejectsProductionNativeBinding() throws Exception {
        Class<?> type = Class.forName("im.majo.ctos.SshNativeSession$ProductionNativeBinding");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        SshNativeSession.NativeBinding production = (SshNativeSession.NativeBinding) constructor.newInstance();
        try (JumpFixture fixture = new JumpFixture()) {
            assertCode("SSH_JUMP_EFFECT_GUARD", () -> SshNativeSession.prepareJumpChild(
                    fixture.parent.session, fixture.target.callback, fixture.shared,
                    "ssh/known_hosts", "candidate_1", 1024, production));
            // The explicit fake-native fixture path still works after the refusal.
            fixture.begin();
            fixture.target.session = fixture.owner.open();
        }
    }

    private static List<String> chainTurns(List<String> order) {
        List<String> result = new ArrayList<>();
        for (String item : order)
            if (item.startsWith("step:") || item.startsWith("pump:")) result.add(item);
        return result;
    }

    @Test public void terminalNativePortDirectSessionKeepsOriginalConfirmation() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(true, binding);
        try {
            select(setup, false);
            setup.session = openManaged(setup);
            ready(setup, binding);
            setup.session.beginPasswordAuth();
            binding.result = 2;
            setup.session.step();
            AtomicInteger confirmations = new AtomicInteger();
            setup.session.confirmShellWith(confirmations::incrementAndGet);
            TerminalProbe probe = new TerminalProbe(binding.order);
            SshTerminalSession terminal = new SshTerminalSession(setup.session,
                    () -> binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            binding.status[0] = 4;
            probe.next();
            assertEquals(1, confirmations.get());
            assertEquals(Arrays.asList("running"), probe.states);
            assertTrue(terminal.publicationLive());
            terminal.stop();
            assertTrue(terminal.closed());
        } finally {
            if (setup.session != null) setup.session.close();
            setup.callback.close();
            setup.closeRegistry();
        }
    }

    @Test public void terminalNativePortDirectClosedWithUnconfirmedOutputFailsWithoutPublishing() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(true, binding);
        try {
            select(setup, false);
            setup.session = openManaged(setup);
            ready(setup, binding);
            setup.session.beginPasswordAuth();
            binding.result = 2;
            setup.session.step();
            AtomicInteger confirmations = new AtomicInteger();
            setup.session.confirmShellWith(confirmations::incrementAndGet);
            TerminalProbe probe = new TerminalProbe(binding.order);
            SshTerminalSession terminal = new SshTerminalSession(setup.session,
                    () -> binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            binding.status[0] = 5;
            binding.status[6] = 2;
            probe.next();
            assertTrue(terminal.closed());
            assertEquals(Arrays.asList("failed"), probe.states);
            assertEquals(0, probe.outputs);
            assertEquals(0, confirmations.get());
            assertEquals(0, binding.reads);
        } finally {
            if (setup.session != null) setup.session.close();
            setup.callback.close();
            setup.closeRegistry();
        }
    }

    @Test public void terminalNativePortJumpPostStepClosedWithUnconfirmedOutputFails() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            AtomicInteger confirmations = new AtomicInteger();
            fixture.authenticateChildBeforeShell(confirmations::incrementAndGet);
            TerminalProbe probe = new TerminalProbe(fixture.binding.order);
            SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                    () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            fixture.binding.childStepHook = () -> {
                fixture.binding.childShell[0] = 5;
                fixture.binding.childShell[6] = 2;
            };
            probe.next();
            assertTrue(terminal.closed());
            assertEquals(Arrays.asList("failed"), probe.states);
            assertEquals(0, probe.outputs);
            assertEquals(0, confirmations.get());
            assertEquals(0, fixture.binding.jumpRetains);
            assertEquals(0, fixture.binding.reads);
        }
    }

    @Test public void terminalNativePortPumpsExactJumpAndConfirmsBeforeRunning() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            AtomicInteger confirmations = new AtomicInteger();
            fixture.authenticateChildBeforeShell(() -> {
                fixture.binding.order.add("confirmation");
                confirmations.incrementAndGet();
            });
            TerminalProbe probe = new TerminalProbe(fixture.binding.order);
            SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                    () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            fixture.binding.order.clear();
            probe.next();
            assertEquals(Arrays.asList("step:903", "pump:904", "step:904", "pump:904"),
                    chainTurns(fixture.binding.order));
            assertTrue(probe.states.isEmpty());
            assertEquals(0, confirmations.get());
            fixture.binding.childShell[0] = 4;
            probe.next();
            assertEquals(Arrays.asList("running"), probe.states);
            assertEquals(1, confirmations.get());
            assertTrue(fixture.binding.order.indexOf("retain:903") < fixture.binding.order.indexOf("confirmation"));
            assertTrue(fixture.binding.order.indexOf("confirmation") < fixture.binding.order.indexOf("state:running"));
            assertTrue(terminal.publicationLive());
            terminal.stop();
            assertTrue(terminal.closed());
            assertTrue(fixture.binding.order.indexOf("destroy:904") < fixture.binding.order.indexOf("close"));
            assertTrue(fixture.binding.order.indexOf("close") < fixture.binding.order.indexOf("resources"));
        }
    }

    @Test public void terminalNativePortNeverPublishesOnRetentionFailureOrParentCancellation() throws Exception {
        for (boolean cancelParent : new boolean[] {false, true}) {
            try (JumpFixture fixture = new JumpFixture()) {
                fixture.begin();
                AtomicInteger confirmations = new AtomicInteger();
                fixture.authenticateChildBeforeShell(confirmations::incrementAndGet);
                TerminalProbe probe = new TerminalProbe(fixture.binding.order);
                SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                        () -> { }, probe, probe, () -> { }, new SshSessionOwner.Cancellation());
                terminal.start(80, 24);
                fixture.binding.childShell[0] = 4;
                fixture.binding.childShell[6] = 2;
                if (cancelParent) fixture.parent.session.cancel();
                else fixture.binding.failRetainHandle = 903;
                probe.next();
                assertFalse(probe.states.contains("running"));
                assertEquals(0, probe.outputs);
                assertEquals(0, confirmations.get());
                assertTrue(terminal.closed());
            }
        }
    }

    @Test public void terminalNativePortDrainsChildWhenRetainedJumpIsClosed() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.authenticateChildBeforeShell(null);
            TerminalProbe probe = new TerminalProbe(fixture.binding.order);
            SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                    () -> { }, probe, probe, () -> { }, new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            fixture.binding.childShell[0] = 4;
            probe.next();
            assertEquals(Arrays.asList("running"), probe.states);
            assertEquals(1, fixture.binding.jumpRetains);
            fixture.binding.jump[0] = 3;
            fixture.binding.jump[1] = 1;
            fixture.binding.jump[3] = 8;
            fixture.binding.childShell[6] = fixture.binding.output.length;
            fixture.binding.order.clear();
            probe.next();
            assertEquals(Arrays.asList("pump:904", "step:904", "pump:904"),
                    chainTurns(fixture.binding.order));
            assertEquals(1, probe.outputs);
            terminal.stop();
            assertTrue(terminal.closed());
        }
    }

    @Test public void terminalNativePortCancelsAllAndRetriesReverseCleanup() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.authenticateChildBeforeShell(null);
            TerminalProbe probe = new TerminalProbe(fixture.binding.order);
            SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                    () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            fixture.target.callbackNative.failCancel = true;
            Thread cancellation = new Thread(terminal::requestCancel);
            cancellation.start(); cancellation.join();
            assertEquals(1, fixture.target.callbackNative.cancelCalls);
            assertEquals(1, fixture.parent.callbackNative.cancelCalls);
            fixture.target.callbackNative.failCancel = false;
            fixture.binding.childCloseFailures = 1;
            fixture.binding.order.clear();
            terminal.stop();
            assertFalse(terminal.closed());
            assertFalse(fixture.binding.order.contains("close"));
            assertFalse(fixture.binding.order.contains("resources"));
            while (!terminal.closed()) probe.next();
            assertTrue(fixture.binding.order.indexOf("destroy:904") < fixture.binding.order.indexOf("close"));
            assertTrue(fixture.binding.order.indexOf("close") < fixture.binding.order.indexOf("resources"));
        }
    }

    @Test public void terminalNativePortRetainsOwnerWhenCallbackFailureSuppressesAnError() throws Exception {
        // This fixture has fake native resources; the intentionally unknown parent must remain retained.
        JumpFixture fixture = new JumpFixture();
        fixture.begin();
        fixture.authenticateChildBeforeShell(null);
        TerminalProbe probe = new TerminalProbe(fixture.binding.order);
        SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                new SshSessionOwner.Cancellation());
        terminal.start(80, 24);
        fixture.target.callbackNative.failCancel = true;
        fixture.parent.callbackNative.fatalCancel = true;
        terminal.requestCancel();
        assertEquals(1, fixture.target.callbackNative.cancelCalls);
        assertEquals(1, fixture.parent.callbackNative.cancelCalls);
        fixture.binding.order.clear();
        terminal.stop();
        while (!probe.turns.isEmpty()) probe.next();
        assertFalse(terminal.closed());
        assertFalse(fixture.binding.order.contains("close"));
        assertFalse(fixture.binding.order.contains("resources"));
        assertEquals(1, fixture.target.callbackNative.cancelCalls);
        assertEquals(1, fixture.parent.callbackNative.cancelCalls);
        fixture.owner.close();
        assertTrue(fixture.target.session.closed());
        assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
        assertFalse(fixture.binding.order.contains("close"));
    }

    @Test public void terminalNativePortRetriesParentAfterChildRelayDestroyed() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.authenticateChildBeforeShell(null);
            TerminalProbe probe = new TerminalProbe(fixture.binding.order);
            SshTerminalSession terminal = new SshTerminalSession(fixture.target.session,
                    () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                    new SshSessionOwner.Cancellation());
            terminal.start(80, 24);
            fixture.binding.order.clear();
            fixture.binding.failClose = true;
            terminal.stop();
            assertFalse(terminal.closed());
            assertTrue(fixture.target.session.closed());
            assertFalse(fixture.parent.session.closed());
            assertEquals(1, fixture.binding.childCloses);
            assertEquals(1, fixture.binding.childDestroys);
            assertTrue(fixture.binding.order.indexOf("destroy:904") < fixture.binding.order.indexOf("close"));
            assertFalse(fixture.binding.order.contains("resources"));
            fixture.binding.failClose = false;
            while (!terminal.closed()) probe.next();
            assertEquals(1, fixture.binding.childCloses);
            assertEquals(1, fixture.binding.childDestroys);
            assertTrue(fixture.parent.session.closed());
            assertTrue(fixture.binding.order.lastIndexOf("close") < fixture.binding.order.indexOf("resources"));
        }
    }

    @Test public void terminalNativePortPumpsTwoJumpsAndRetainsInsideOut() throws Exception {
        try (JumpFixture fixture = new JumpFixture(true)) {
            fixture.begin();
            fixture.target.session = fixture.owner.open();
            fixture.target.session.step();
            fixture.target.session.beginUserauthService();
            assertTrue(fixture.target.session.userauthServiceReady());
            fixture.target.session.beginPasswordAuth();
            fixture.binding.childResult = 2;
            fixture.target.session.step();
            Setup destination = new Setup(true, fixture.binding, fixture.view, 33, 2, false);
            SshNativeSession.JumpChildOwner grandchild = null;
            try {
                select(destination, false);
                grandchild = SshNativeSession.prepareJumpChild(fixture.target.session,
                        destination.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                        1024, fixture.binding);
                fixture.target.session.beginJump(grandchild);
                fixture.binding.middleJump[0] = 2;
                destination.session = grandchild.open();
                destination.session.step();
                destination.session.beginUserauthService();
                assertTrue(destination.session.userauthServiceReady());
                destination.session.beginPasswordAuth();
                fixture.binding.childResult = 2;
                destination.session.step();
                destination.session.confirmShellWith(() -> fixture.binding.order.add("confirmation"));
                TerminalProbe probe = new TerminalProbe(fixture.binding.order);
                SshTerminalSession terminal = new SshTerminalSession(destination.session,
                        () -> fixture.binding.order.add("resources"), probe, probe, () -> { },
                        new SshSessionOwner.Cancellation());
                terminal.start(80, 24);
                fixture.binding.order.clear();
                probe.next();
                assertEquals(Arrays.asList("step:903", "pump:904", "step:904", "pump:904",
                        "pump:905", "step:905", "pump:905"), chainTurns(fixture.binding.order));
                assertTrue(probe.states.isEmpty());
                fixture.binding.targetShell[0] = 4;
                probe.next();
                assertEquals(Arrays.asList("running"), probe.states);
                assertTrue(fixture.binding.order.indexOf("retain:904") < fixture.binding.order.indexOf("retain:903"));
                assertTrue(fixture.binding.order.indexOf("retain:903") < fixture.binding.order.indexOf("confirmation"));
                assertTrue(fixture.binding.order.indexOf("confirmation") < fixture.binding.order.indexOf("state:running"));
                terminal.stop();
                assertTrue(terminal.closed());
                assertTrue(fixture.binding.order.indexOf("destroy:905") < fixture.binding.order.indexOf("close:904"));
                assertTrue(fixture.binding.order.indexOf("destroy:904") < fixture.binding.order.indexOf("close"));
            } finally {
                if (grandchild != null) grandchild.close();
                destination.callback.close();
                destination.closeRegistry();
            }
        }
    }

    @Test public void jumpOwnerPinsParentAndPromotesOnlyRunningExactChild() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.prepare();
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.parent.session.beginJump(fixture.owner);
            fixture.binding.jump[0] = 2;
            fixture.parent.session.step();
            assertEquals(SshNativeSession.JumpPhase.OPEN, fixture.parent.session.jumpStatus().phase);
            AtomicInteger confirmations = new AtomicInteger();
            fixture.authenticateChild(confirmations::incrementAndGet);
            fixture.target.session.pumpJumpRelay();
            assertEquals(1, fixture.binding.relayPumps);
            assertCode("SSH_SESSION_PHASE", fixture.parent.session::retainJumpTransport);
            assertEquals(0, fixture.binding.jumpRetains);
            assertEquals(0, confirmations.get());
            fixture.parent.session.step();
            fixture.binding.childShell[0] = 4;
            fixture.target.session.step();
            assertEquals(0, confirmations.get());
            assertFalse(fixture.target.session.shellOutputPublicationLive());
            fixture.parent.session.retainJumpTransport();
            assertEquals(1, fixture.binding.jumpRetains);
            assertEquals(0, confirmations.get());
            assertEquals(SshNativeSession.ShellPhase.RUNNING, fixture.target.session.shellStatus().phase);
            assertEquals(1, confirmations.get());
            assertTrue(fixture.view.active);
            assertCode("SSH_SESSION_PHASE", fixture.parent.session::authenticationSelectionReceipt);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.target.session.close();
            fixture.owner.close();
            fixture.parent.session.close();
            assertTrue(fixture.binding.order.indexOf("childClose")
                    < fixture.binding.order.indexOf("childDestroy"));
            assertTrue(fixture.binding.order.indexOf("childDestroy")
                    < fixture.binding.order.lastIndexOf("close"));
        }
    }

    @Test public void failedChildCloseFencesNativeEntriesUntilSameOwnerRetries() throws Exception {
        for (boolean runningShell : new boolean[] {false, true}) {
            for (boolean closeThroughOwner : new boolean[] {false, true}) {
                try (JumpFixture fixture = new JumpFixture()) {
                    fixture.begin();
                    if (runningShell) {
                        fixture.authenticateChild();
                        fixture.parent.session.step();
                        fixture.binding.childShell[0] = SshNativeSession.ShellPhase.RUNNING.tag;
                        fixture.target.session.step();
                        fixture.parent.session.retainJumpTransport();
                        assertEquals(SshNativeSession.ShellPhase.RUNNING,
                                fixture.target.session.shellStatus().phase);
                        assertTrue(fixture.target.session.shellOutputPublicationLive());
                        fixture.binding.accepted = 1;
                        assertEquals(1, fixture.target.session.writeShell(bytes("x")));
                        assertArrayEquals(fixture.binding.output, fixture.target.session.readShell(32));
                        assertFalse(fixture.target.session.resizeShell(80, 24));
                    } else {
                        fixture.target.session = fixture.owner.open();
                        assertTrue(fixture.target.session.step().complete);
                        byte[] receipt = fixture.target.session.authenticationSelectionReceipt();
                        assertEquals(32, receipt.length);
                        VaultCodec.wipe(receipt);
                    }
                    fixture.target.session.pumpJumpRelay();
                    assertEquals(SshNativeSession.JumpPhase.OPEN, fixture.parent.session.jumpStatus().phase);

                    fixture.binding.childCloseFailures = 1;
                    if (closeThroughOwner) assertCode("SSH_SESSION_CLEANUP", fixture.owner::close);
                    else assertCode("SSH_SESSION_CLEANUP", fixture.target.session::close);
                    assertFalse(fixture.target.session.closed());
                    assertEquals(1, fixture.binding.childCloses);
                    assertEquals(0, fixture.binding.childDestroys);
                    int before = fixture.binding.observedCalls();

                    assertCode("SSH_SESSION_CLOSED", fixture.target.session::pumpJumpRelay);
                    assertCode("SSH_SESSION_CLOSED", fixture.parent.session::step);
                    assertCode("SSH_SESSION_CLOSED", fixture.parent.session::jumpStatus);
                    if (runningShell) {
                        assertCode("SSH_SESSION_CLOSED", fixture.target.session::step);
                        assertCode("SSH_SESSION_CLOSED", fixture.target.session::shellStatus);
                        assertCode("SSH_SESSION_CLOSED", () -> fixture.target.session.writeShell(bytes("x")));
                        assertCode("SSH_SESSION_CLOSED", () -> fixture.target.session.readShell(32));
                        assertCode("SSH_SESSION_CLOSED", () -> fixture.target.session.resizeShell(80, 24));
                        assertCode("SSH_SESSION_CLOSED", fixture.target.session::sendShellEof);
                        assertFalse(fixture.target.session.shellOutputPublicationLive());
                    } else {
                        assertCode("SSH_SESSION_CLOSED", fixture.target.session::beginUserauthService);
                        assertCode("SSH_SESSION_CLOSED", fixture.target.session::authenticationSelectionReceipt);
                    }
                    assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
                    assertEquals("closing must reject every effect before entering native", before,
                            fixture.binding.observedCalls());

                    fixture.owner.close();
                    assertTrue(fixture.target.session.closed());
                    assertEquals(2, fixture.binding.childCloses);
                    assertEquals(1, fixture.binding.childDestroys);
                    fixture.parent.session.close();
                    assertTrue(fixture.parent.session.closed());
                    assertTrue(fixture.binding.order.indexOf("destroy:904")
                            < fixture.binding.order.lastIndexOf("close"));
                }
            }
        }
    }

    @Test public void freshJumpOwnerTransfersExactVaultSourceAndUsesItsHostEvidence() throws Exception {
        AtomicReference<FreshVault> current = new AtomicReference<>();
        try (JumpFixture fixture = new JumpFixture(false, (setup, authority) -> {
            FreshVault vault = new FreshVault(setup, authority);
            current.set(vault);
            return vault.callback;
        })) {
            FreshVault vault = current.get();
            assertNotNull(vault);
            assertCode("SSH_HOST_KEY_REVIEW", () -> SshNativeSession.prepareJumpChildFresh(
                    fixture.parent.session, fixture.target.callback, fixture.shared,
                    "ssh/other", "candidate_1", 1024, vault.source, fixture.binding));
            try (SshManagedKnownHostsProvider.ChildTrustSnapshot other =
                    SshManagedKnownHostsProvider.snapshotForChild(vault.adapter, fixture.target.issued.role,
                            fixture.target.clock, fixture.view, "ssh/config", "ssh/known_hosts",
                            "hosts", "hosts identity")) {
                assertCode("SSH_HOST_KEY_REVIEW", () -> SshNativeSession.prepareJumpChildFresh(
                        fixture.parent.session, fixture.target.callback, fixture.shared,
                        "ssh/known_hosts", "candidate_1", 1024, other, fixture.binding));
            }
            fixture.owner = SshNativeSession.prepareJumpChildFresh(fixture.parent.session,
                    fixture.target.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                    1024, vault.source, fixture.binding);
            fixture.parent.session.beginJump(fixture.owner);
            fixture.binding.jump[0] = 2;
            fixture.target.session = fixture.owner.open();
            assertNotNull(fixture.binding.freshCopy);
            assertTrue(fixture.binding.freshCopy.length > FreshVault.HOSTS.length);
            assertTrue(allZero(fixture.binding.freshInput));
            assertCode("VAULT_EXPIRED", vault.source::takeWire);
            byte[] digest = VaultCodec.digest(FreshVault.HOSTS);
            byte[] result = fixture.target.callback.onNativeHostEvidence(32, 1, 22, "connection-31",
                    "target:user@example.invalid:22", "example.invalid", 1, "ssh-ed25519",
                    "SHA256:" + "A".repeat(43), digest, digest);
            assertNotNull(result);
            assertNotNull(fixture.target.callback.hostProof());
            fixture.target.session.step();
            byte[] secret = bytes("synthetic password");
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginUserauthService);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginAuthDiscovery);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginPasswordAuth);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginPrivateKeyAuth);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::continuePrivateKeyAuth);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginKeyboardInteractiveAuth);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::continueKeyboardInteractiveAuth);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::beginDiscoveredKeyboard);
            assertCode("SSH_JUMP_EFFECT_GUARD", () -> fixture.target.session.beginDiscoveredPassword(null, secret));
            assertCode("SSH_JUMP_EFFECT_GUARD", () -> fixture.target.session.respondKeyboardInteractive(null, secret));
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::passwordPromptStage);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::privateKeyPromptStage);
            assertCode("SSH_JUMP_EFFECT_GUARD", fixture.target.session::keyboardStage);
            assertArrayEquals(bytes("synthetic password"), secret);
            assertEquals(0, fixture.binding.childResult);
            VaultCodec.wipe(secret);
            VaultCodec.wipe(digest);
            VaultCodec.wipe(result);
        }
    }

    @Test public void failedFreshJumpOpenWipesWireAndRetainsAbortOwner() throws Exception {
        AtomicReference<FreshVault> current = new AtomicReference<>();
        try (JumpFixture fixture = new JumpFixture(false, (setup, authority) -> {
            FreshVault vault = new FreshVault(setup, authority);
            current.set(vault);
            return vault.callback;
        })) {
            fixture.owner = SshNativeSession.prepareJumpChildFresh(fixture.parent.session,
                    fixture.target.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                    1024, current.get().source, fixture.binding);
            fixture.parent.session.beginJump(fixture.owner);
            fixture.binding.jump[0] = 2;
            fixture.binding.nextOpenState = 1;
            fixture.binding.failChildOpen = true;
            assertCode("SSH_SESSION_NATIVE", fixture.owner::open);
            assertTrue(allZero(fixture.binding.freshInput));
            assertTrue(fixture.view.active());
            fixture.owner.close();
            assertEquals(1, fixture.binding.aborts);
            assertCode("VAULT_EXPIRED", current.get().source::requireCurrent);
        }
    }

    @Test public void freshEffectFencePreservesCancellationRevocationAndExpiry() throws Exception {
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            AtomicReference<FreshVault> current = new AtomicReference<>();
            try (JumpFixture fixture = new JumpFixture(false, (setup, authority) -> {
                FreshVault vault = new FreshVault(setup, authority);
                current.set(vault);
                return vault.callback;
            })) {
                fixture.owner = SshNativeSession.prepareJumpChildFresh(fixture.parent.session,
                        fixture.target.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                        1024, current.get().source, fixture.binding);
                fixture.parent.session.beginJump(fixture.owner);
                fixture.binding.jump[0] = 2;
                fixture.target.session = fixture.owner.open();
                if (lifecycle == 0) fixture.target.session.cancel();
                else if (lifecycle == 1) fixture.view.revoke();
                else fixture.target.clock.value = 31_000;
                String expected = lifecycle == 0 ? "SSH_SESSION_CANCELLED"
                        : lifecycle == 1 ? "SSH_FILE_VIEW" : "SSH_SESSION_EXPIRED";
                assertCode(expected, fixture.target.session::beginUserauthService);
                assertCode(expected, fixture.target.session::beginPasswordAuth);
                assertCode(expected, fixture.target.session::beginPrivateKeyAuth);
                assertCode(expected, fixture.target.session::beginKeyboardInteractiveAuth);
                assertCode(expected, () -> fixture.target.session.beginDiscoveredPassword(null, bytes("unused")));
                assertCode(expected, () -> fixture.target.session.respondKeyboardInteractive(null, bytes("unused")));
                assertEquals(0, fixture.binding.childResult);
            }
        }
    }

    @Test public void freshEffectFenceRetainsCancellationAfterInitialLiveCheck() throws Exception {
        AtomicReference<FreshVault> current = new AtomicReference<>();
        try (JumpFixture fixture = new JumpFixture(false, (setup, authority) -> {
            FreshVault vault = new FreshVault(setup, authority);
            current.set(vault);
            return vault.callback;
        })) {
            fixture.owner = SshNativeSession.prepareJumpChildFresh(fixture.parent.session,
                    fixture.target.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                    1024, current.get().source, fixture.binding);
            fixture.parent.session.beginJump(fixture.owner);
            fixture.binding.jump[0] = 2;
            fixture.target.session = fixture.owner.open();
            fixture.target.clock.blockOnCall(3); // selection live's two reads, then the effect fence
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread canceller = new Thread(() -> {
                try { awaitTestLatch(fixture.target.clock.entered); fixture.target.session.cancel(); }
                catch (Throwable failure) { problem.set(failure); }
                finally { fixture.target.clock.release.countDown(); }
            });
            canceller.start();
            assertCode("SSH_SESSION_CANCELLED", fixture.target.session::beginUserauthService);
            canceller.join(5000);
            assertFalse(canceller.isAlive());
            if (problem.get() != null) throw new AssertionError(problem.get());
            assertEquals(0, fixture.binding.childResult);
        }
    }

    @Test public void jumpAdmissionRequiresSuccessAndIsOneShotForEveryAuthMethod() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            try (AdmissionFixture fixture = new AdmissionFixture(mode)) {
                assertCode("SSH_SESSION_PHASE", fixture::issue);
                fixture.success(mode);
                SshNativeSession.JumpAdmission admission = fixture.issue();
                assertCode("SSH_SESSION_PHASE", fixture::issue);
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(null, fixture.next, fixture.parent.view));
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(fixture.parent.issued, null, fixture.parent.view));
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(fixture.parent.issued, fixture.next, null));
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(fixture.next, fixture.next, fixture.parent.view));
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(fixture.parent.issued,
                        fixture.parent.issued, fixture.parent.view));
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(fixture.parent.issued,
                        fixture.next, new ViewLease(new ArrayList<>())));
                AtomicReference<String> otherThread = new AtomicReference<>();
                Thread thread = new Thread(() -> {
                    try { admission.consume(fixture.parent.issued, fixture.next, fixture.parent.view); }
                    catch (VaultException error) { otherThread.set(error.code); }
                });
                thread.start(); thread.join();
                assertEquals("SSH_SESSION_THREAD", otherThread.get());
                admission.consume(fixture.parent.issued, fixture.next, fixture.parent.view);
                assertCode("SSH_SESSION_PHASE", () -> admission.consume(
                        fixture.parent.issued, fixture.next, fixture.parent.view));
                assertCode("SSH_SESSION_PHASE", fixture::issue);
            }
        }
    }

    @Test public void jumpAdmissionRejectsPartialAndDeadParent() throws Exception {
        try (AdmissionFixture fixture = new AdmissionFixture(4)) {
            fixture.parent.session.beginPasswordAuth(); fixture.binding.result = 4; fixture.parent.session.step();
            assertCode("SSH_SESSION_PHASE", fixture::issue);
        }
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            try (AdmissionFixture fixture = new AdmissionFixture(0)) {
                fixture.success(0);
                SshNativeSession.JumpAdmission admission = fixture.issue();
                if (lifecycle == 0) fixture.parent.session.cancel();
                else if (lifecycle == 1) fixture.parent.view.revoke();
                else fixture.parent.clock.value = 31_000;
                try {
                    admission.consume(fixture.parent.issued, fixture.next, fixture.parent.view);
                    fail("dead parent must not authorize a hop");
                } catch (VaultException expected) {
                    assertNotNull(expected.code);
                }
            }
        }
    }

    @Test public void jumpAdmissionRetainsCancellationAfterItsLastLiveCheck() throws Exception {
        try (AdmissionFixture fixture = new AdmissionFixture(0)) {
            fixture.success(0);
            SshNativeSession.JumpAdmission admission = fixture.issue();
            fixture.parent.view.pauseFinalSelectionViewRead();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread canceller = new Thread(() -> {
                try { awaitTestLatch(fixture.parent.view.selectionEntered); fixture.parent.session.cancel(); }
                catch (Throwable failure) { problem.set(failure); }
                finally { fixture.parent.view.selectionRelease.countDown(); }
            });
            canceller.start();
            assertCode("SSH_SESSION_CANCELLED", () -> admission.consume(
                    fixture.parent.issued, fixture.next, fixture.parent.view));
            canceller.join(5000);
            assertFalse(canceller.isAlive());
            if (problem.get() != null) throw new AssertionError(problem.get());
        }
    }

    @Test public void jumpAdmissionChecksActualChildOwnersAndEveryLiveAncestor() throws Exception {
        for (boolean cancelBeforeIssue : new boolean[] {true, false}) {
            try (JumpFixture fixture = new JumpFixture(true, null, true)) {
                fixture.begin();
                fixture.authenticateChildBeforeShell(null);
                SshNativeRoleBootstrap.Issued next = SshNativeRoleBootstrap.issue(
                        chainedRecord(33, 42, 1001, 2, false), fixture.parent.registry,
                        fixture.view, fixture.parent.clock, ignored -> true);
                if (cancelBeforeIssue) fixture.parent.session.cancel();
                if (cancelBeforeIssue) {
                    assertCode("SSH_SESSION_CANCELLED", () -> fixture.target.session.issueJumpAdmission(
                            fixture.target.issued, next, fixture.shared, fixture.view));
                } else {
                    SshNativeSession.JumpAdmission admission = fixture.target.session.issueJumpAdmission(
                            fixture.target.issued, next, fixture.shared, fixture.view);
                    fixture.parent.session.cancel();
                    assertCode("SSH_SESSION_CANCELLED", () -> admission.consume(
                            fixture.target.issued, next, fixture.view));
                }
                assertTrue(fixture.view.active());
            }
        }
    }

    @Test public void jumpAdmissionRejectsNonzeroHopOpenedAsRoot() throws Exception {
        try (AdmissionFixture fixture = new AdmissionFixture(0, 1)) {
            fixture.success(0);
            assertCode("SSH_SESSION_PHASE", fixture::issue);
        }
    }

    @Test public void jumpChildNeverConfirmsAfterAncestorCancellationBeforeRetention() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            AtomicInteger confirmations = new AtomicInteger();
            fixture.authenticateChild(confirmations::incrementAndGet);
            fixture.binding.childShell[0] = 4;
            fixture.target.session.step();
            assertEquals(0, confirmations.get());
            fixture.parent.session.cancel();
            fixture.target.session.shellStatus();
            assertEquals(0, confirmations.get());
            assertFalse(fixture.target.session.shellOutputPublicationLive());
        }
    }

    @Test public void jumpChildConfirmationWaitsForEveryAncestor() throws Exception {
        try (JumpFixture fixture = new JumpFixture(true)) {
            fixture.begin();
            fixture.target.session = fixture.owner.open();
            fixture.target.session.step();
            fixture.target.session.beginUserauthService();
            assertTrue(fixture.target.session.userauthServiceReady());
            fixture.target.session.beginPasswordAuth();
            fixture.binding.childResult = 2;
            fixture.target.session.step();

            Setup destination = new Setup(true, fixture.binding, fixture.view, 33, 2, false);
            SshNativeSession.JumpChildOwner grandchild = null;
            try {
                select(destination, false);
                grandchild = SshNativeSession.prepareJumpChild(fixture.target.session,
                        destination.callback, fixture.shared, "ssh/known_hosts", "candidate_1",
                        1024, fixture.binding);
                fixture.target.session.beginJump(grandchild);
                fixture.binding.middleJump[0] = 2;
                destination.session = grandchild.open();
                destination.session.step();
                destination.session.beginUserauthService();
                assertTrue(destination.session.userauthServiceReady());
                destination.session.beginPasswordAuth();
                fixture.binding.childResult = 2;
                destination.session.step();
                AtomicInteger confirmations = new AtomicInteger();
                destination.session.confirmShellWith(confirmations::incrementAndGet);
                destination.session.beginShell(80, 24);
                fixture.binding.targetShell[0] = 4;
                destination.session.step();
                assertEquals(0, confirmations.get());
                fixture.target.session.retainJumpTransport();
                destination.session.step();
                assertEquals(0, confirmations.get());
                assertFalse(destination.session.shellOutputPublicationLive());
                fixture.parent.session.retainJumpTransport();
                destination.session.step();
                assertEquals(1, confirmations.get());
                assertTrue(destination.session.shellOutputPublicationLive());
            } finally {
                if (grandchild != null) grandchild.close();
                destination.callback.close();
                destination.closeRegistry();
            }
        }
    }

    @Test public void jumpOwnerRetriesReservationAndUnadoptedAbort() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.prepare();
            fixture.parent.session.beginJump(fixture.owner);
            assertCode("SSH_SESSION_PHASE", fixture.owner::open);
            assertEquals(0, fixture.binding.reserves);
            fixture.binding.jump[0] = 2;
            fixture.target.session = fixture.owner.open();
            assertEquals(1, fixture.binding.reserves);
            fixture.owner.close();
        }
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.binding.failReserve = true;
            assertCode("SSH_SESSION_NATIVE", fixture.owner::open);
            assertEquals(0, fixture.binding.childCloses);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.owner.close();
            assertTrue(fixture.view.active);
        }
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.binding.nextOpenState = 1;
            fixture.binding.failChildOpen = true;
            assertCode("SSH_SESSION_NATIVE", fixture.owner::open);
            fixture.binding.stateFailures = 1;
            assertCode("SSH_SESSION_CLEANUP", fixture.owner::close);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.binding.abortFailures = 1;
            assertCode("SSH_SESSION_CLEANUP", fixture.owner::close);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.owner.close();
            assertEquals(2, fixture.binding.aborts);
            assertEquals(0, fixture.binding.childCloses);
            assertTrue(fixture.view.active);
        }
    }

    @Test public void jumpOwnerRetriesCleanupWhenOpenThrowsAfterNativeTransfer() throws Exception {
        for (int state : new int[] {2, 3, 4}) {
            try (JumpFixture fixture = new JumpFixture()) {
                fixture.begin();
                fixture.binding.nextOpenState = state;
                fixture.binding.failChildOpen = true;
                assertCode("SSH_SESSION_NATIVE", fixture.owner::open);
                fixture.binding.childCloseFailures = 1;
                assertCode("SSH_SESSION_CLEANUP", fixture.owner::close);
                assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
                fixture.binding.childDestroyFailures = 1;
                assertCode("SSH_SESSION_CLEANUP", fixture.owner::close);
                assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
                fixture.owner.close();
                fixture.owner.close();
                assertEquals(2, fixture.binding.childCloses);
                assertEquals(2, fixture.binding.childDestroys);
                assertTrue(fixture.view.active);
            }
        }
    }

    @Test public void jumpOwnerRetainsChildWhenStateQueryOrPostOpenGuardFails() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.binding.stateFailures = 1;
            assertCode("SSH_SESSION_NATIVE", fixture.owner::open);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.owner.close();
            assertEquals(1, fixture.binding.childCloses);
            assertEquals(1, fixture.binding.childDestroys);
        }
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.binding.childOpenHook = fixture.shared::cancel;
            assertCode("SSH_FILE_VIEW", fixture.owner::open);
            assertCode("SSH_SESSION_CLEANUP", fixture.parent.session::close);
            fixture.owner.close();
            assertEquals(1, fixture.binding.childCloses);
            assertFalse(fixture.view.active);
        }
    }

    @Test public void jumpOwnerRejectsWrongViewAndMalformedOrRegressingStatus() throws Exception {
        try (JumpFixture fixture = new JumpFixture()) {
            Setup other = new Setup(false, new ManagedShellNative());
            try {
                assertCode("SSH_SESSION_ARGUMENTS", () -> SshNativeSession.prepareJumpChild(
                        fixture.parent.session, other.callback, fixture.shared, "ssh/known_hosts",
                        "candidate_1", 1024, fixture.binding));
            } finally { other.callback.close(); other.closeRegistry(); }
            fixture.begin();
            fixture.binding.jump = new int[] {2, 2, 0, 0};
            assertCode("SSH_SESSION_JUMP_STATUS", fixture.parent.session::jumpStatus);
        }
        try (JumpFixture fixture = new JumpFixture()) {
            fixture.begin();
            fixture.parent.session.jumpStatus();
            fixture.binding.jump[0] = 1;
            assertCode("SSH_SESSION_JUMP_STATUS", fixture.parent.session::jumpStatus);
        }
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

    @Test public void keyboardSelectionCannotUseGenericOrSelectedSavedCredentialCallback() throws Exception {
        Setup setup=new Setup(true);
        try{
            SshAuthenticationSelection selection=SshAuthenticationSelection.keyboardInteractive(setup.issued.role,"user",setup.clock);
            setup.callback.selectAuthentication(selection);
            assertEquals(null,setup.callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_KEYBOARD_INTERACTIVE,selection));
            assertEquals("SSH_AUTH_SELECTION",setup.callback.lastFailureCode());
            assertEquals(null,setup.callback.onNativeCredentialBytes(SshNativeCallbackAdapter.CREDENTIAL_PASSWORD));
            assertEquals("SSH_AUTH_SELECTION",setup.callback.lastFailureCode());
        }finally{setup.callback.close();setup.closeRegistry();}
    }

    @Test public void managedDefaultFailsClosedWithoutCallingPinnedOrConsumingClaim() throws Exception {
        Setup setup = new Setup();
        try {
            assertCode("SSH_SESSION_NATIVE", () -> openManaged(setup));
            assertEquals(0, setup.sessionNative.openCalls);
            assertTrue(setup.sessionNative.order.isEmpty());
            assertTrue(setup.view.active);
            assertTrue(setup.issued.role.active());
            assertEquals(0, setup.roleRelease.calls);
            assertEquals(0, setup.callbackNative.closeCalls);
            setup.session = open(setup);
            setup.session.close();
        } finally { setup.closeRegistry(); }
    }

    @Test public void descriptorPreparationFailureAndReentryPreserveGlobalNativeGuard() throws Exception {
        for (int descriptor : new int[] {-1, 41}) {
            try { SshNativeSession.prepareTcpDescriptor(descriptor); fail("JNI is unavailable on the host"); }
            catch (IOException expected) {
                assertEquals("SSH TCP descriptor preparation failed", expected.getMessage());
            }
        }
        Setup setup = new Setup();
        try {
            setup.sessionNative.openHook = () -> {
                try { SshNativeSession.prepareTcpDescriptor(41); fail("reentry must fail"); }
                catch (IOException expected) {
                    assertEquals("SSH TCP descriptor preparation failed", expected.getMessage());
                }
                try { assertCode("SSH_SESSION_REENTRANT", () -> open(setup)); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            setup.session = open(setup);
            setup.session.close();
            assertTrue(setup.session.closed());
        } finally { setup.closeRegistry(); }
    }

    @Test public void managedOpenValidatesOwnerAndArgumentsBeforeBinding() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        try {
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { openManaged(setup); }
                catch (Throwable failure) { observed.set(failure); }
            });
            worker.start(); worker.join();
            assertTrue(observed.get() instanceof VaultException);
            assertEquals("SSH_SESSION_THREAD", ((VaultException) observed.get()).code);
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                    setup.view, -1, "candidate_1", 1024, binding));
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                    setup.view, 41, "bad candidate", 1024, binding));
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                    setup.view, 41, "candidate_1", 0, binding));
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                    (SshPublicFileLease) null, 41, "candidate_1", 1024));
            assertEquals(0, binding.managedOpenCalls);
            // Null/empty key material never implicitly selects managed discovery on the old ABI.
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.open(setup.callback,
                    setup.view, 41, null, null, "candidate_1", 1024, binding));
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.open(setup.callback,
                    setup.view, 41, HOST_KEY, new byte[32], "candidate_1", 1024, binding));
            assertEquals(0, binding.openCalls);
            setup.session = openManaged(setup);
            setup.session.close();
        } finally { setup.closeRegistry(); }
    }

    @Test public void managedFailedOpenWipesMetadataAndPreservesCallerResourcesForRetry() throws Exception {
        for (boolean error : new boolean[] {false, true}) {
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(true, binding);
            try {
                SshAuthenticationSelection selected = select(setup, false);
                binding.failOpen = !error;
                if (error) binding.openHook = () -> { throw new AssertionError("managed open Error"); };
                if (error) {
                    try { openManaged(setup); fail("Error must escape"); }
                    catch (AssertionError expected) { assertEquals("managed open Error", expected.getMessage()); }
                } else assertCode("SSH_SESSION_NATIVE", () -> openManaged(setup));
                assertArrayEquals(selected.encode(setup.issued.token, setup.clock), binding.wireCopy);
                assertTrue(allZero(binding.wireReference));
                assertEquals(41, binding.socketFd);
                assertEquals(0, binding.openCalls);
                assertTrue(setup.view.active);
                assertTrue(setup.issued.role.active());
                assertEquals(0, setup.view.closeCalls);
                assertEquals(0, setup.callbackNative.closeCalls);
                assertEquals(0, setup.roleRelease.calls);
                assertEquals(Arrays.asList("managedOpen"), binding.order);
                binding.failOpen = false; binding.openHook = null;
                setup.session = openManaged(setup);
                assertCode("SSH_CALLBACK_SESSION", () -> openManaged(setup));
                assertCode("SSH_CALLBACK_SESSION", () -> open(setup));
                assertEquals(2, binding.managedOpenCalls);
                assertArrayEquals(binding.digest, setup.session.authenticationSelectionReceipt());
                setup.session.close();
            } finally {
                binding.openHook = null;
                if (setup.session != null) setup.session.close();
                setup.closeRegistry();
            }
        }
    }

    @Test public void managedManualVerifyIsNonterminalAndNeverEntersBinding() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        try {
            setup.session = openManaged(setup);
            for (byte[] offered : new byte[][] {null, new byte[0], HOST_KEY}) {
                assertCode("SSH_SESSION_PHASE", () -> setup.session.verifyHostKey(offered));
            }
            assertEquals(null, binding.verifyInput);
            assertEquals(null, binding.openHostKey);
            assertEquals(null, binding.openFingerprint);
            assertEquals(0, binding.openCalls);
            assertEquals(Arrays.asList("managedOpen"), binding.order);
            setup.session.step();
            setup.session.close();
        } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void managedViewTokenFailureReleasesOpeningClaimBeforeAnyNativeCall() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        try {
            setup.view.failToken = true;
            assertCode("SSH_FILE_VIEW", () -> openManaged(setup));
            assertEquals(0, binding.managedOpenCalls);
            assertTrue(binding.order.isEmpty());
            assertTrue(setup.view.active);
            assertTrue(setup.issued.role.active());
            setup.view.failToken = false;
            setup.session = openManaged(setup);
            setup.session.close();
        } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void managedOpenAndManualVerifyRetainThreadWideReentryGuard() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        Setup other = new Setup();
        try {
            other.session = open(other);
            binding.openHook = () -> {
                try {
                    assertCode("SSH_SESSION_REENTRANT", () -> openManaged(setup));
                    for (CheckedAction action : entries(other))
                        assertCode("SSH_SESSION_REENTRANT", action);
                } catch (Exception failure) { throw new AssertionError(failure); }
            };
            setup.session = openManaged(setup);
            binding.openHook = null;
            binding.stepHook = () -> {
                try { assertCode("SSH_SESSION_REENTRANT", () -> setup.session.verifyHostKey(HOST_KEY)); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            setup.session.step();
            assertEquals(1, binding.managedOpenCalls);
            assertEquals(null, binding.verifyInput);
            binding.stepHook = null;
            other.session.step();
        } finally {
            binding.openHook = binding.stepHook = null;
            if (setup.session != null) setup.session.close();
            if (other.session != null) other.session.close();
            setup.closeRegistry(); other.closeRegistry();
        }
    }

    @Test public void managedSelectedAuthenticationStillRequiresSuccessAndPreservesShellCleanup()
            throws Exception {
        for (boolean key : new boolean[] {false, true}) {
            for (int result : new int[] {2, 3, 4}) {
                ManagedShellNative binding = new ManagedShellNative();
                Setup setup = new Setup(true, binding);
                try {
                    select(setup, key);
                    setup.session = openManaged(setup);
                    assertTrue(allZero(binding.wireReference));
                    assertArrayEquals(binding.digest, setup.session.authenticationSelectionReceipt());
                    ready(setup, binding);
                    if (key) setup.session.beginPrivateKeyAuth();
                    else setup.session.beginPasswordAuth();
                    binding.result = result;
                    setup.session.step();
                    if (result != 2) {
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80, 24));
                        assertEquals(0, binding.shellCalls);
                        continue;
                    }
                    setup.session.beginShell(80, 24);
                    binding.status[0] = 4;
                    assertEquals(SshNativeSession.ShellPhase.RUNNING, setup.session.shellStatus().phase);
                    byte[] command = bytes("echo managed\r");
                    binding.accepted = command.length;
                    assertEquals(command.length, setup.session.writeShell(command));
                    assertTrue(allZero(binding.writeReference));
                    assertArrayEquals(binding.output, setup.session.readShell(32));
                    assertTrue(allZero(binding.readReference));
                    binding.resizeAccepted = 1;
                    assertTrue(setup.session.resizeShell(100, 40));
                    binding.status = new int[] {5, 1, 1, 1, 0, 0, 0};
                    assertEquals(0, setup.session.shellStatus().exitStatus);
                    binding.failClose = true;
                    assertCode("SSH_SESSION_CLEANUP", () -> setup.session.close());
                    assertFalse(setup.view.active);
                    assertEquals(0, setup.roleRelease.calls);
                    binding.failClose = false;
                    setup.session.close();
                    assertTrue(setup.session.closed());
                    assertFalse(setup.view.active);
                    assertEquals(1, setup.roleRelease.calls);
                    assertEquals(0, binding.openCalls);
                } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void managedShellCancelBlocksPublicationWhileRetiredViewAndExpiryRetainTransport()
            throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(true, binding);
            try {
                select(setup, false);
                setup.session = openManaged(setup);
                ready(setup, binding);
                setup.session.beginPasswordAuth();
                binding.result = 2;
                setup.session.step();
                setup.session.beginShell(80, 24);
                binding.status[0] = 4;
                setup.session.shellStatus();
                assertTrue(setup.session.shellOutputPublicationLive());
                if (mode == 0) {
                    AtomicReference<Throwable> observed = new AtomicReference<>();
                    Thread cancel = new Thread(() -> {
                        try { setup.session.cancel(); }
                        catch (Throwable failure) { observed.set(failure); }
                    });
                    cancel.start(); cancel.join();
                    assertEquals(null, observed.get());
                    assertEquals(1, setup.callbackNative.cancelCalls);
                } else if (mode == 1) setup.view.active = false;
                else setup.clock.value = 31_000;
                assertEquals(mode != 0, setup.session.shellOutputPublicationLive());
                int calls = binding.shellCalls;
                if (mode != 0) {
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.authenticationSelectionReceipt());
                    assertArrayEquals(binding.output, setup.session.readShell(32));
                    assertTrue(binding.shellCalls > calls);
                } else {
                    assertCode("SSH_SESSION_CANCELLED",
                            () -> setup.session.readShell(32));
                    assertEquals(calls, binding.shellCalls);
                }
                setup.session.close();
                assertTrue(setup.session.closed());
                assertEquals(1, setup.roleRelease.calls);
                assertEquals(1, setup.view.closeCalls);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void sharedViewFixtureRequiresOneObjectAndCannotEnterProductionBinding() throws Exception {
        ManagedShellNative nativeBinding = new ManagedShellNative();
        Setup setup = new Setup(false, nativeBinding);
        ViewLease other = new ViewLease(new ArrayList<>());
        SshSocketOwnership socket = new SshSocketOwnership(41, ignored -> { });
        try {
            try {
                new SshSharedPublicView(setup.view, other, () -> { });
                fail("role and token views must be the same fixture object");
            } catch (IllegalArgumentException expected) { }
            SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                    () -> setup.view.active = false);
            assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                    shared, socket, "ssh/known_hosts", "candidate_1", 1024));
            assertFalse(socket.adopted());
            assertTrue(setup.view.active);
            assertEquals(0, setup.view.closeCalls);
            setup.session = SshNativeSession.openManaged(setup.callback, shared, socket,
                    "ssh/known_hosts", "candidate_1", 1024, nativeBinding);
            shared.seal();
            setup.session.close();
            shared.close();
        } finally {
            if (setup.session != null) setup.session.close();
            else socket.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewAdoptionRequiresExactCallbackAndCanRetry() throws Exception {
        Setup setup = new Setup();
        Setup other = new Setup();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                () -> setup.view.active = false);
        try {
            SshSharedPublicView.Binding failed = shared.issue(setup.callback);
            assertCode("SSH_SESSION_ARGUMENTS", () -> failed.nativeAdopted(other.callback));
            failed.abortUnadopted();
            SshSharedPublicView.Binding binding = shared.issue(setup.callback);
            binding.nativeAdopted(setup.callback);
            binding.nativeAdopted(setup.callback);
            binding.close();
            assertCode("SSH_SESSION_ARGUMENTS", () -> binding.nativeAdopted(setup.callback));
            shared.seal();
            shared.close();
        } finally {
            setup.callback.close(); other.callback.close();
            setup.closeRegistry(); other.closeRegistry();
        }
    }

    @Test public void sharedViewRetiresOnlyTheCompletedSessionAndClosesAfterBoth() throws Exception {
        ManagedShellNative targetNative = new ManagedShellNative();
        ManagedShellNative parentNative = new ManagedShellNative();
        ViewLease view = new ViewLease(new ArrayList<>());
        Setup target = new Setup(true, targetNative, view);
        Setup parent = new Setup(true, parentNative, view);
        SshSharedPublicView shared = new SshSharedPublicView(view, view, () -> view.active = false);
        SshSocketOwnership targetSocket = new SshSocketOwnership(41, ignored -> { });
        SshSocketOwnership parentSocket = new SshSocketOwnership(41, ignored -> { });
        try {
            select(target, false);
            target.session = SshNativeSession.openManaged(target.callback, shared, targetSocket,
                    "ssh/known_hosts", "candidate_1", 1024, targetNative);
            parent.session = SshNativeSession.openManaged(parent.callback, shared, parentSocket,
                    "ssh/known_hosts", "candidate_1", 1024, parentNative);
            assertCode("SSH_SESSION_ARGUMENTS", () -> shared.issue(parent.callback));
            shared.seal();
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            ready(target, targetNative);
            target.session.beginPasswordAuth(); targetNative.result = 2; target.session.step();
            target.session.beginShell(80, 24);
            assertTrue(view.active);
            assertEquals(0, view.closeCalls);
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            parent.session.close();
            assertTrue(view.active);
            shared.close();
            assertFalse(view.active);
            assertEquals(1, view.closeCalls);
            target.session.close();
            shared.close();
            assertEquals(1, view.closeCalls);
        } finally {
            if (target.session != null) target.session.close();
            if (parent.session != null) parent.session.close();
            target.closeRegistry(); parent.closeRegistry();
        }
    }

    @Test public void sharedViewRejectsOtherViewAndLateBindingsAndRetriesClose() throws Exception {
        ManagedShellNative nativeBinding = new ManagedShellNative();
        Setup setup = new Setup(false, nativeBinding);
        Setup other = new Setup(false, new ManagedShellNative());
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                () -> setup.view.active = false);
        try {
            assertCode("SSH_SESSION_ARGUMENTS", () -> shared.issue(other.callback));
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            setup.session = SshNativeSession.openManaged(setup.callback, shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts",
                    "candidate_1", 1024, nativeBinding);
            shared.seal();
            assertCode("SSH_FILE_VIEW", () -> shared.issue(setup.callback));
            setup.session.close();
            setup.view.failClose = true;
            assertCode("SSH_FILE_VIEW", shared::close);
            setup.view.failClose = false;
            shared.close();
            assertEquals(2, setup.view.closeCalls);
        } finally {
            if (setup.session != null) setup.session.close();
            setup.closeRegistry(); other.callback.close(); other.closeRegistry();
        }
    }

    @Test public void sharedViewRevokesOnCancelBeforeSessionsRetire() throws Exception {
        ManagedShellNative nativeBinding = new ManagedShellNative();
        Setup setup = new Setup(false, nativeBinding);
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                () -> setup.view.active = false);
        try {
            setup.session = SshNativeSession.openManaged(setup.callback, shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts",
                    "candidate_1", 1024, nativeBinding);
            Thread cancel = new Thread(shared::cancel);
            cancel.start(); cancel.join();
            assertFalse(setup.view.active);
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            setup.session.close();
            shared.close();
        } finally {
            if (setup.session != null) setup.session.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewRetainsLeaseAfterUnconfirmedRevoke() throws Exception {
        Setup setup = new Setup();
        AtomicInteger revokes = new AtomicInteger();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view, () -> {
            revokes.incrementAndGet();
            throw new IllegalStateException("unconfirmed view revoke");
        });
        try {
            SshSharedPublicView.Binding binding = shared.issue(setup.callback);
            assertTrue(binding.active());
            try { shared.cancel(); fail(); }
            catch (IllegalStateException expected) { assertEquals("unconfirmed view revoke", expected.getMessage()); }
            assertFalse(binding.active());
            binding.abortUnadopted();
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            try { shared.cancel(); fail(); }
            catch (IllegalStateException expected) { assertNotNull(expected.getCause()); }
            assertEquals(1, revokes.get());
            assertEquals(0, setup.view.closeCalls);
        } finally {
            setup.callback.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewCloseWaitsForInFlightRevoke() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view, () -> {
            entered.countDown();
            awaitTestLatch(resume);
            setup.view.active = false;
        });
        Thread cancel = new Thread(() -> {
            try { shared.cancel(); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            cancel.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            assertEquals(0, setup.view.closeCalls);
        } finally { resume.countDown(); }
        cancel.join(5000);
        assertFalse(cancel.isAlive());
        assertNull(failure.get());
        shared.close();
        assertEquals(1, setup.view.closeCalls);
        setup.callback.close();
        setup.closeRegistry();
    }

    @Test public void sharedViewCancelDoesNotRaceAnAlreadyAdmittedClose() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), cancelDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger revokes = new AtomicInteger();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view, revokes::incrementAndGet);
        shared.seal();
        setup.view.closeHook = () -> { entered.countDown(); awaitTestLatch(cancelDone); };
        Thread cancel = new Thread(() -> {
            try {
                awaitTestLatch(entered);
                try { shared.cancel(); failure.set(new AssertionError("in-flight close was ignored")); }
                catch (IllegalStateException expected) { /* Close was admitted first. */ }
            } catch (Throwable error) { failure.set(error); }
            finally { cancelDone.countDown(); }
        });
        cancel.start();
        shared.close();
        cancel.join(5000);
        assertFalse(cancel.isAlive());
        assertNull(failure.get());
        assertEquals(1, setup.view.closeCalls);
        assertEquals(0, revokes.get());
        shared.cancel(); // An already closed lease needs no second invalidation.
        setup.callback.close();
        setup.closeRegistry();
    }

    @Test public void sharedViewRetriesCancellationAfterAdmittedCloseFails() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), cancelDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger revokes = new AtomicInteger();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view, () -> {
            revokes.incrementAndGet();
            setup.view.active = false;
        });
        shared.seal();
        setup.view.failClose = true;
        setup.view.closeHook = () -> { entered.countDown(); awaitTestLatch(cancelDone); };
        Thread cancel = new Thread(() -> {
            try {
                awaitTestLatch(entered);
                try { shared.cancel(); failure.set(new AssertionError("in-flight close was ignored")); }
                catch (IllegalStateException expected) { /* No invalidation was attempted. */ }
            } catch (Throwable error) { failure.set(error); }
            finally { cancelDone.countDown(); }
        });
        try {
            cancel.start();
            assertCode("SSH_FILE_VIEW", shared::close);
            cancel.join(5000);
            assertFalse(cancel.isAlive());
            assertNull(failure.get());
            setup.view.closeHook = null;
            setup.view.failClose = false;
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            shared.cancel();
            shared.close();
            assertEquals(1, revokes.get());
            assertEquals(2, setup.view.closeCalls);
        } finally {
            setup.callback.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewDoesNotRetryUncheckedCloseFailure() throws Exception {
        Setup setup = new Setup();
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view, () -> { });
        try {
            shared.seal();
            setup.view.fatalClose = true;
            try { shared.close(); fail(); }
            catch (AssertionError expected) { assertEquals("unconfirmed view close", expected.getMessage()); }
            setup.view.fatalClose = false;
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            assertEquals(1, setup.view.closeCalls);
        } finally {
            setup.callback.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewRetainsPostOpenFailureUntilNativeOwnerCloses() throws Exception {
        ManagedShellNative nativeBinding = new ManagedShellNative();
        Setup setup = new Setup(false, nativeBinding);
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                () -> setup.view.active = false);
        SshSocketOwnership socket = new SshSocketOwnership(41, ignored -> fail("adopted socket"));
        try {
            nativeBinding.openHook = () -> setup.view.active = false;
            nativeBinding.failClose = true;
            assertCode("SSH_FILE_VIEW", () -> SshNativeSession.openManaged(setup.callback, shared,
                    socket, "ssh/known_hosts", "candidate_1", 1024, nativeBinding));
            assertTrue(socket.adopted());
            shared.seal();
            assertCode("SSH_SESSION_CLEANUP", shared::close);
            assertEquals(0, setup.view.closeCalls);
            nativeBinding.failClose = false;
            socket.close();
            shared.close();
            assertEquals(1, setup.view.closeCalls);
        } finally {
            nativeBinding.failClose = false;
            if (socket.adopted()) socket.close();
            setup.closeRegistry();
        }
    }

    @Test public void sharedViewFailedUnadoptedOpenCanRetryWithoutRetiringOtherOwners() throws Exception {
        ManagedShellNative nativeBinding = new ManagedShellNative();
        Setup setup = new Setup(false, nativeBinding);
        SshSharedPublicView shared = new SshSharedPublicView(setup.view, setup.view,
                () -> setup.view.active = false);
        SshSocketOwnership failedSocket = new SshSocketOwnership(41, ignored -> { });
        try {
            nativeBinding.failOpen = true;
            assertCode("SSH_SESSION_NATIVE", () -> SshNativeSession.openManaged(setup.callback, shared,
                    failedSocket, "ssh/known_hosts", "candidate_1", 1024, nativeBinding));
            assertFalse(failedSocket.adopted());
            assertTrue(setup.view.active);
            failedSocket.close();
            nativeBinding.failOpen = false;
            setup.session = SshNativeSession.openManaged(setup.callback, shared,
                    new SshSocketOwnership(41, ignored -> { }), "ssh/known_hosts",
                    "candidate_1", 1024, nativeBinding);
            shared.seal();
            setup.session.close();
            shared.close();
            assertEquals(1, setup.view.closeCalls);
        } finally {
            if (setup.session != null) setup.session.close();
            failedSocket.close(); setup.closeRegistry();
        }
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

    @Test public void sessionCloseWaitsForInFlightCallbackCancellation() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            setup.session = open(setup);
            setup.callbackNative.cancelHook = () -> { entered.countDown(); awaitTestLatch(resume); };
            Thread cancel = new Thread(() -> {
                try { setup.session.cancel(); }
                catch (Throwable error) { failure.set(error); }
            });
            cancel.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(setup.session.cancellationRequested());
                assertCode("SSH_SESSION_CLEANUP", setup.session::close);
                assertFalse(setup.sessionNative.order.contains("close"));
            } finally { resume.countDown(); }
            cancel.join(5000);
            assertFalse(cancel.isAlive());
            assertNull(failure.get());
            setup.session.close();
            assertTrue(setup.session.closed());
        } finally { setup.closeRegistry(); }
    }

    @Test public void callbackNativeCancellationFailureAllowsOnlyCleanup() throws Exception {
        Setup setup = new Setup();
        try {
            setup.session = open(setup);
            setup.callbackNative.failCancel = true;
            assertCode("SSH_CALLBACK_NATIVE", setup.session::cancel);
            assertTrue(setup.session.cancellationRequested());
            assertCode("SSH_SESSION_CANCELLED", setup.session::step);
            assertFalse(setup.sessionNative.order.contains("step"));
            setup.session.close();
            assertTrue(setup.session.closed());
        } finally { setup.closeRegistry(); }
    }

    @Test public void sessionCancelDoesNotRaceAnAdmittedClose() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), cancelDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            setup.session = open(setup);
            setup.sessionNative.closeHook = () -> { entered.countDown(); awaitTestLatch(cancelDone); };
            Thread cancel = new Thread(() -> {
                try {
                    awaitTestLatch(entered);
                    try { setup.session.cancel(); failure.set(new AssertionError("in-flight close was ignored")); }
                    catch (VaultException expected) {
                        if (!"SSH_SESSION_CLEANUP".equals(expected.code)) failure.set(expected);
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { cancelDone.countDown(); }
            });
            cancel.start();
            setup.session.close();
            cancel.join(5000);
            assertFalse(cancel.isAlive());
            assertNull(failure.get());
            assertTrue(setup.session.cancellationRequested());
            assertTrue(setup.session.closed());
            assertEquals(0, setup.callbackNative.cancelCalls);
        } finally { setup.closeRegistry(); }
    }

    @Test public void failedAdmittedSessionCloseRequiresPendingCancelBeforeRetry() throws Exception {
        Setup setup = new Setup();
        CountDownLatch entered = new CountDownLatch(1), cancelDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            setup.session = open(setup);
            setup.sessionNative.failClose = true;
            setup.sessionNative.closeHook = () -> { entered.countDown(); awaitTestLatch(cancelDone); };
            Thread cancel = new Thread(() -> {
                try {
                    awaitTestLatch(entered);
                    try { setup.session.cancel(); failure.set(new AssertionError("in-flight close was ignored")); }
                    catch (VaultException expected) {
                        if (!"SSH_SESSION_CLEANUP".equals(expected.code)) failure.set(expected);
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { cancelDone.countDown(); }
            });
            cancel.start();
            assertCode("SSH_SESSION_CLEANUP", setup.session::close);
            cancel.join(5000);
            assertFalse(cancel.isAlive());
            assertNull(failure.get());
            setup.sessionNative.failClose = false;
            setup.sessionNative.closeHook = null;
            assertCode("SSH_SESSION_CLEANUP", setup.session::close);
            setup.session.cancel();
            setup.session.close();
            assertTrue(setup.session.closed());
            assertEquals(1, setup.callbackNative.cancelCalls);
        } finally { setup.closeRegistry(); }
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

    @Test public void actualHostRejectionDiagnosticIsFixedAndTerminalWithoutReplayingNative() throws Exception {
        for (String code : new String[] {"SSH_HOST_KEY_CHANGED", "SSH_HOST_KEY_REVOKED", "SSH_HOST_KEY_CERTIFICATE", "SSH_HOST_TRUST_BLOCKED"}) {
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(false, binding);
            try {
                setup.session = openManaged(setup);
                binding.stepFailure = new SshNativeSession.HostKeyRejectedException(code);
                assertCode(code, () -> setup.session.step());
                binding.stepFailure = null;
                assertCode(code, () -> setup.session.step());
                assertEquals(Arrays.asList("managedOpen", "step"), binding.order);
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void arbitraryNativeIOExceptionTextNeverBecomesHostTrustEvidence() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        try {
            setup.session = openManaged(setup);
            binding.stepFailure = new IOException("SSH_HOST_KEY_CHANGED");
            assertCode("SSH_SESSION_STEP", () -> setup.session.step());
            assertCode("SSH_SESSION_STEP", () -> setup.session.step());
        } finally { setup.session.close(); setup.closeRegistry(); }
        try {
            new SshNativeSession.HostKeyRejectedException("remote payload");
            fail("Unknown native rejection tag must fail closed");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void currentLifecycleInvalidationOutranksActualHostRejectionDiagnostic() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            final int selected = mode;
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(false, binding);
            try {
                setup.session = openManaged(setup);
                binding.stepFailure = new SshNativeSession.HostKeyRejectedException("SSH_HOST_KEY_CHANGED");
                binding.stepHook = () -> {
                    try {
                        if (selected == 0) setup.session.cancel();
                        if (selected == 1) setup.callback.cancel();
                        if (selected == 2) setup.clock.value = setup.issued.role.deadline;
                        if (selected == 3) setup.view.active = false;
                    } catch (VaultException failure) { throw new AssertionError(failure); }
                };
                String code = selected < 2 ? "SSH_SESSION_CANCELLED"
                        : selected == 2 ? "SSH_SESSION_EXPIRED" : "SSH_FILE_VIEW";
                assertCode(code, () -> setup.session.step());
                binding.stepHook = null;
                assertCode(code, () -> setup.session.step());
            } finally { binding.stepHook = null; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void hostProofCallbackFailureIsTerminalWhenNativeReportsCancellation() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        try {
            setup.session = openManaged(setup);
            binding.stepFailure = new IOException("synthetic native callback rejection");
            binding.stepHook = () -> {
                assertEquals(null, setup.callback.onNativeHostEvidence(setup.issued.token,
                        setup.issued.role.hop + 1, setup.issued.role.port,
                        setup.issued.role.connectionId, setup.issued.role.targetIdentity,
                        setup.issued.role.host, SshNativeCallbackAdapter.HOST_KNOWN,
                        "ssh-ed25519", "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2)));
                assertEquals("SSH_HOST_PROOF_INVALID", setup.callback.lastFailureCode());
            };
            assertCode("SSH_SESSION_CANCELLED", () -> setup.session.step());
            binding.stepHook = null;
            assertCode("SSH_SESSION_CANCELLED", () -> setup.session.step());
            assertEquals(Arrays.asList("managedOpen", "step"), binding.order);
            assertEquals(null, setup.callback.hostProof());
            assertEquals(null, setup.callback.kexProof());
        } finally { binding.stepHook = null; setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void keyExchangeReportsOnlyTheCurrentLiveCallbackProofFailure() throws Exception {
        for (int mode = 0; mode < 18; mode++) {
            final int selected = mode;
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(false, binding);
            AtomicInteger checks = new AtomicInteger();
            AtomicInteger writesChecked = new AtomicInteger();
            try {
                setup.session = openManaged(setup);
                binding.stepFailure = new IOException("synthetic native callback rejection");
                binding.stepHook = () -> {
                    assertEquals(null, setup.callback.onNativeHostEvidence(setup.issued.token,
                            setup.issued.role.hop + 1, setup.issued.role.port,
                            setup.issued.role.connectionId, setup.issued.role.targetIdentity,
                            setup.issued.role.host, SshNativeCallbackAdapter.HOST_KNOWN,
                            "ssh-ed25519", "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2)));
                    try {
                        if (selected == 3) setup.view.active = false;
                        if (selected == 4 || selected == 13 || selected == 14 || selected == 15)
                            setup.clock.value = 2_000;
                        if (selected == 5 || selected == 13 || selected == 17) setup.session.cancel();
                        if (selected == 6 || selected == 14) setup.callback.cancel();
                    } catch (VaultException failure) { throw new AssertionError(failure); }
                };
                AppSecrets.EffectGuard guard = () -> {
                    int count = checks.incrementAndGet();
                    if (selected == 15 || selected == 16) setup.callback.cancel();
                    if (selected == 1 || selected == 9 && count == 2)
                        throw new VaultException("SSH_CANCELLED", "synthetic product cancellation");
                    if (selected == 2 || selected == 16 || selected == 17)
                        throw new VaultException("VAULT_COMMIT_UNCERTAIN", "synthetic uncertain write");
                    if (!setup.view.active) throw new VaultException("VAULT_CHANGED", "synthetic lock");
                    SshPasswordConnector.requireTransportDeadline(setup.clock.now(), 2_000);
                    if (selected == 7 && count == 1 || selected == 8 && count == 2)
                        setup.callback.cancel();
                };
                String expected = selected == 0 ? SshHostKeyAuthority.PROOF_INVALID
                        : selected == 1 || selected == 9 ? "SSH_CANCELLED"
                        : selected == 2 || selected == 16 || selected == 17 ? "VAULT_COMMIT_UNCERTAIN"
                        : selected == 3 ? "VAULT_CHANGED"
                        : selected == 4 ? "SSH_TIMED_OUT" : "SSH_SESSION_CANCELLED";
                assertCode(expected, () -> SshPasswordConnector.keyExchangeStep(setup.session, setup.callback,
                        guard, () -> selected == 10 || selected >= 11 && selected <= 12
                                && writesChecked.incrementAndGet() >= selected - 9));
                binding.stepHook = null;
                assertCode("SSH_SESSION_CANCELLED", () -> setup.session.step());
                assertEquals(Arrays.asList("managedOpen", "step"), binding.order);
                assertEquals(null, setup.callback.hostProof());
                assertEquals(null, setup.callback.kexProof());
            } finally { binding.stepHook = null; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void keyExchangeDoesNotInferProofFailureFromNativeExceptionText() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(false, binding);
        AtomicInteger checks = new AtomicInteger();
        try {
            setup.session = openManaged(setup);
            binding.stepFailure = new IOException(SshHostKeyAuthority.PROOF_INVALID);
            assertCode("SSH_SESSION_STEP", () -> SshPasswordConnector.keyExchangeStep(
                    setup.session, setup.callback, checks::incrementAndGet, () -> false));
            assertEquals(0, checks.get());
            assertEquals(Arrays.asList("managedOpen", "step"), binding.order);
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void firstFactorDiagnosesTheRealSelectedCredentialCallbackWithoutReadingSecrets()
            throws Exception {
        for (boolean privateKey : new boolean[] {false, true}) {
            for (int mode = 0; mode < 12; mode++) {
                final int selected = mode;
                ManagedShellNative binding = new ManagedShellNative();
                Setup setup = new Setup(true, binding);
                AtomicInteger guardChecks = new AtomicInteger();
                AtomicInteger writeChecks = new AtomicInteger();
                try {
                    select(setup, privateKey);
                    setup.session = openManaged(setup);
                    byte[] hostReceipt = setup.callback.onNativeHostEvidence(setup.issued.token,
                            setup.issued.role.hop, setup.issued.role.port,
                            setup.issued.role.connectionId, setup.issued.role.targetIdentity,
                            setup.issued.role.host, SshNativeCallbackAdapter.HOST_KNOWN,
                            "ssh-ed25519", "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2));
                    assertNotNull(hostReceipt);
                    VaultCodec.wipe(hostReceipt);
                    assertTrue(setup.callback.onNativeKexComplete(1, filled(32, 7)));
                    ready(setup, binding);
                    installForeignHostProof(setup);
                    setup.clock.value = 2_000;
                    assertTrue("The shorter connection timeout has already elapsed", setup.clock.now() > 1_500);
                    binding.failOperation = privateKey ? "keyBegin" : "authBegin";
                    binding.protocolHook = () -> {
                        assertEquals(null, setup.callback.onNativeSelectedCredentialBytes(
                                privateKey ? SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY
                                        : SshNativeCallbackAdapter.CREDENTIAL_PASSWORD,
                                binding.digest, 1, filled(32, 7)));
                        assertEquals(SshHostKeyAuthority.PROOF_INVALID, setup.callback.lastFailureCode());
                        try {
                            if (selected == 2 || selected == 7) setup.callback.cancel();
                            if (selected == 3) setup.session.cancel();
                            if (selected == 4) setup.clock.value = setup.issued.role.deadline;
                            if (selected == 5) setup.view.active = false;
                        } catch (VaultException failure) { throw new AssertionError(failure); }
                    };
                    AppSecrets.EffectGuard guard = () -> {
                        int count = guardChecks.incrementAndGet();
                        if (selected == 10 && count == 2) setup.callback.cancel();
                        if (selected == 1 || selected == 11 && count == 2)
                            throw new VaultException("SSH_CANCELLED", "synthetic authentication cancellation");
                        if (selected == 8)
                            throw new VaultException("VAULT_COMMIT_UNCERTAIN", "synthetic uncertain write");
                        if (!setup.view.active) throw new VaultException("VAULT_CHANGED", "synthetic lock");
                        long authenticationDeadline = selected == 7 ? 2_000 : setup.issued.role.deadline;
                        if (setup.clock.now() >= authenticationDeadline)
                            throw new VaultException("SSH_TIMED_OUT", "synthetic authentication deadline");
                    };
                    String expected = selected == 0 ? SshHostKeyAuthority.PROOF_INVALID
                            : selected == 1 || selected == 11 ? "SSH_CANCELLED"
                            : selected == 4 ? "SSH_TIMED_OUT"
                            : selected == 5 ? "VAULT_CHANGED"
                            : selected == 8 ? "VAULT_COMMIT_UNCERTAIN" : "SSH_SESSION_CANCELLED";
                    assertCode(expected, () -> SshPasswordConnector.beginFirstFactor(
                            setup.session, setup.callback, privateKey, guard,
                            () -> selected == 6 || selected == 9 && writeChecks.incrementAndGet() >= 2));
                    binding.protocolHook = null;
                    assertCode("SSH_SESSION_CANCELLED", () -> {
                        if (privateKey) setup.session.beginPrivateKeyAuth();
                        else setup.session.beginPasswordAuth();
                    });
                    assertEquals(null, setup.callback.onNativeSelectedCredentialBytes(
                            privateKey ? SshNativeCallbackAdapter.CREDENTIAL_PRIVATE_KEY
                                    : SshNativeCallbackAdapter.CREDENTIAL_PASSWORD,
                            binding.digest, 1, filled(32, 7)));
                    assertCode("SSH_SESSION_CANCELLED", () -> setup.session.beginShell(80, 24));
                    assertEquals(privateKey ? 0 : 1, binding.authBegins);
                    assertEquals(privateKey ? 1 : 0, binding.keyBegins);
                    assertEquals(0, binding.shellCalls);
                    assertEquals(null, setup.callback.hostProof());
                    assertEquals(null, setup.callback.kexProof());
                } finally {
                    binding.protocolHook = null;
                    if (setup.session != null) setup.session.close();
                    setup.closeRegistry();
                }
            }
        }
    }

    private static void installForeignHostProof(Setup setup) throws Exception {
        SshRoleRegistry foreignRegistry = new SshRoleRegistry(setup.clock);
        ViewLease foreignView = new ViewLease(new ArrayList<>());
        try {
            SshNativeRoleBootstrap.Issued foreign = SshNativeRoleBootstrap.issue(
                    record(32, 42, 1002, 1), foreignRegistry, foreignView, setup.clock, ignored -> true);
            SshHostKeyAuthority.Evidence evidence = SshHostKeyAuthority.Evidence.fromNative(
                    foreign.role, SshHostKeyAuthority.Classification.KNOWN, "ssh-ed25519",
                    "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2));
            SshHostKeyAuthority.Proof proof = new SshHostKeyAuthority().requireVerified(foreign.role, evidence, null);
            java.lang.reflect.Field field = SshNativeCallbackAdapter.class.getDeclaredField("hostProof");
            field.setAccessible(true);
            field.set(setup.callback, proof);
        } finally { foreignRegistry.close(); }
    }

    @Test public void firstFactorWrapperPreservesNormalStartAndUnrelatedNativeFailures() throws Exception {
        for (boolean privateKey : new boolean[] {false, true}) {
            for (boolean nativeFailure : new boolean[] {false, true}) {
                ManagedShellNative binding = new ManagedShellNative();
                Setup setup = new Setup(true, binding);
                AtomicInteger diagnostics = new AtomicInteger();
                try {
                    select(setup, privateKey);
                    setup.session = openManaged(setup);
                    ready(setup, binding);
                    if (nativeFailure) binding.failOperation = privateKey ? "keyBegin" : "authBegin";
                    CheckedAction start = () -> SshPasswordConnector.beginFirstFactor(
                            setup.session, setup.callback, privateKey, diagnostics::incrementAndGet, () -> false);
                    if (nativeFailure) {
                        assertCode("SSH_SESSION_AUTH", start);
                        assertCode("SSH_SESSION_AUTH", start);
                    } else {
                        start.run();
                        if (privateKey)
                            assertEquals(SshNativeSession.PrivateKeyAuthResult.PENDING, setup.session.privateKeyAuthResult());
                        else
                            assertEquals(SshNativeSession.PasswordAuthResult.PENDING, setup.session.passwordAuthResult());
                        assertCode("SSH_SESSION_PHASE", start);
                    }
                    assertEquals(privateKey ? 0 : 1, binding.authBegins);
                    assertEquals(privateKey ? 1 : 0, binding.keyBegins);
                    assertEquals(0, diagnostics.get());
                } finally {
                    if (setup.session != null) setup.session.close();
                    setup.closeRegistry();
                }
            }
        }
    }

    @Test public void proofDiagnosticNeverUsesAnotherSessionsCallback() throws Exception {
        for (boolean authentication : new boolean[] {false, true}) {
            ManagedShellNative binding = new ManagedShellNative();
            Setup current = new Setup(authentication, binding);
            Setup foreign = new Setup();
            AtomicInteger guardChecks = new AtomicInteger();
            try {
                if (authentication) select(current, false);
                current.session = openManaged(current);
                if (authentication) ready(current, binding);
                assertEquals(null, foreign.callback.onNativeHostEvidence(foreign.issued.token,
                        foreign.issued.role.hop + 1, foreign.issued.role.port,
                        foreign.issued.role.connectionId, foreign.issued.role.targetIdentity,
                        foreign.issued.role.host, SshNativeCallbackAdapter.HOST_KNOWN,
                        "ssh-ed25519", "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2)));
                assertEquals(SshHostKeyAuthority.PROOF_INVALID, foreign.callback.lastFailureCode());
                if (authentication) {
                    binding.failOperation = "authBegin";
                    binding.protocolHook = () -> {
                        assertEquals(null, current.callback.onNativeSelectedCredentialBytes(
                                SshNativeCallbackAdapter.CREDENTIAL_PASSWORD, new byte[32], 1, filled(32, 7)));
                        assertEquals("SSH_AUTH_SELECTION", current.callback.lastFailureCode());
                    };
                    assertCode("SSH_SESSION_CANCELLED", () -> SshPasswordConnector.beginFirstFactor(
                            current.session, foreign.callback, false, guardChecks::incrementAndGet, () -> false));
                    assertEquals(1, binding.authBegins);
                    assertEquals(0, binding.keyBegins);
                } else {
                    binding.stepFailure = new IOException("synthetic native callback rejection");
                    binding.stepHook = () -> {
                        assertEquals(null, current.callback.onNativeHostEvidence(0,
                                current.issued.role.hop, current.issued.role.port,
                                current.issued.role.connectionId, current.issued.role.targetIdentity,
                                current.issued.role.host, SshNativeCallbackAdapter.HOST_KNOWN,
                                "ssh-ed25519", "SHA256:" + "A".repeat(43), filled(32, 1), filled(32, 2)));
                        assertEquals("SSH_HOST_KEY_INVALID", current.callback.lastFailureCode());
                    };
                    assertCode("SSH_SESSION_CANCELLED", () -> SshPasswordConnector.keyExchangeStep(
                            current.session, foreign.callback, guardChecks::incrementAndGet, () -> false));
                    assertEquals(Arrays.asList("managedOpen", "step"), binding.order);
                }
                assertEquals(0, guardChecks.get());
                assertEquals(0, binding.shellCalls);
            } finally {
                binding.protocolHook = null;
                binding.stepHook = null;
                if (current.session != null) current.session.close();
                else current.callback.close();
                foreign.callback.close();
                current.closeRegistry();
                foreign.closeRegistry();
            }
        }
    }

    @Test public void aFreshSessionDoesNotInheritAnotherAttemptHostRejection() throws Exception {
        ManagedShellNative firstBinding = new ManagedShellNative();
        Setup first = new Setup(false, firstBinding);
        try {
            first.session = openManaged(first);
            firstBinding.stepFailure = new SshNativeSession.HostKeyRejectedException("SSH_HOST_KEY_CHANGED");
            assertCode("SSH_HOST_KEY_CHANGED", () -> first.session.step());
        } finally { first.session.close(); first.closeRegistry(); }
        ManagedShellNative secondBinding = new ManagedShellNative();
        Setup second = new Setup(false, secondBinding);
        try {
            second.session = openManaged(second);
            assertNotNull(second.session.step());
        } finally { second.session.close(); second.closeRegistry(); }
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

    private static void authenticated(Setup setup, AuthNative binding, boolean key, int result)
            throws Exception {
        select(setup, key);
        setup.session = open(setup);
        ready(setup, binding);
        if (key) setup.session.beginPrivateKeyAuth();
        else setup.session.beginPasswordAuth();
        binding.result = result;
        setup.session.step();
    }

    private static void shellRunning(Setup setup, ShellNative binding) throws Exception {
        authenticated(setup, binding, false, 2);
        setup.session.beginShell(80, 24);
        binding.status[0] = 4;
        assertEquals(SshNativeSession.ShellPhase.RUNNING, setup.session.shellStatus().phase);
    }

    @Test public void automaticSelectionRequiresDiscoveryAndItsOwnMethodPolicy() throws Exception {
        for (boolean key : new boolean[] {false, true}) for (boolean allowed : new boolean[] {false, true}) {
            KeyboardNative binding = new KeyboardNative(); Setup setup = new Setup(true, binding);
            try {
                SshAuthenticationSelection original = key
                        ? SshAuthenticationSelection.privateKey(setup.issued.role, "user", "keys/key", "identity", bytes("public"), setup.clock)
                        : SshAuthenticationSelection.keyboardInteractive(setup.issued.role, "user", setup.clock);
                int method = key ? 1 : 2;
                setup.callback.selectAuthentication(original.withAutomaticDiscovery(allowed ? method : 4, setup.clock));
                setup.session = open(setup); ready(setup, binding);
                if (key) assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPrivateKeyAuth());
                else assertCode("SSH_SESSION_PHASE", () -> setup.session.beginKeyboardInteractiveAuth());
                setup.session.beginAuthDiscovery(); binding.discovery = new int[] {2, method}; setup.session.step();
                if (allowed) {
                    if (key) setup.session.beginPrivateKeyAuth(); else setup.session.beginKeyboardInteractiveAuth();
                    assertEquals(key ? 1 : 0, binding.keyBegins); assertEquals(key ? 0 : 1, binding.keyboardBegins);
                } else {
                    if (key) assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPrivateKeyAuth());
                    else assertCode("SSH_SESSION_PHASE", () -> setup.session.beginKeyboardInteractiveAuth());
                    assertEquals(0, binding.keyBegins); assertEquals(0, binding.keyboardBegins);
                }
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
        KeyboardNative binding = new KeyboardNative(); Setup setup = new Setup(true, binding);
        try {
            setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role, "user", setup.clock)
                    .withAutomaticDiscovery(0, setup.clock));
            setup.session = open(setup); ready(setup, binding);
            setup.session.beginAuthDiscovery(); binding.discovery = new int[] {3, 0}; setup.session.step();
            setup.session.beginShell(80, 24);
            assertEquals(0, binding.keyboardBegins); assertEquals(0, binding.authBegins); assertEquals(0, binding.keyBegins);
        } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void discoveredKeyboardWithASelectedKeyBindsTheOriginalPlanAndKex() throws Exception {
        for (int mutation : new int[] {-1, 0, 5, 11, 15, 19, 20, 52, 60, 91, 92}) {
            MfaNative binding = new MfaNative(); binding.receiptMutation = mutation;
            Setup setup = new Setup(true, binding);
            try {
                SshAuthenticationSelection selection = SshAuthenticationSelection.privateKey(setup.issued.role,
                        "user", "keys/key", "identity", bytes("public"), setup.clock).withAutomaticDiscovery(3, setup.clock);
                setup.callback.selectAuthentication(selection); setup.session = open(setup);
                assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                        filled(32,2), filled(32,3)));
                assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
                ready(setup, binding); setup.session.beginAuthDiscovery();
                binding.discovery = new int[] {2, 2}; setup.session.step();
                if (mutation >= 0) {
                    assertCode("SSH_KEYBOARD_AUTH", () -> setup.session.beginDiscoveredKeyboard());
                    assertCode("SSH_KEYBOARD_AUTH", () -> setup.session.beginShell(80, 24));
                } else {
                    setup.session.beginDiscoveredKeyboard();
                    Object stage = setup.session.keyboardStage(); setup.session.requireKeyboardStageLive(stage);
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.beginDiscoveredKeyboard());
                    binding.challengeWire = SshKeyboardChallengeTest.frame(1, "server", "notice", "Code");
                    binding.result = 5; setup.session.step();
                    SshKeyboardChallenge challenge = setup.session.keyboardInteractiveChallenge();
                    setup.session.respondKeyboardInteractive(challenge, challenge.response(List.of("")));
                    binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
                }
                assertEquals(1, binding.discoveredKeyboardCalls);
                assertEquals(0, binding.keyBegins); assertEquals(0, binding.authBegins);
                assertArrayEquals(new byte[32], binding.borrowedPlan);
                assertArrayEquals(new byte[binding.borrowedStage.length], binding.borrowedStage);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void discoveredPasswordRequiresTheServerAndBindsTheOriginalPlanAndKex() throws Exception {
        for (boolean key : new boolean[] {false, true}) for (int mutation : new int[] {-1, 0, 5, 11, 15, 19, 20, 52, 60, 91, 92}) {
            MfaNative binding = new MfaNative(); binding.receiptMutation = mutation; binding.passwordOriginal = key ? 4 : 3;
            Setup setup = new Setup(true, binding);
            byte[] input = bytes("synthetic-password");
            try {
                SshAuthenticationSelection selection = key ? SshAuthenticationSelection.privateKey(setup.issued.role,
                        "user", "keys/key", "identity", bytes("public"), setup.clock)
                        : SshAuthenticationSelection.keyboardInteractive(setup.issued.role, "user", setup.clock);
                setup.callback.selectAuthentication(selection.withAutomaticDiscovery(key ? 7 : 6, setup.clock));
                setup.session = open(setup);
                assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                        filled(32,2), filled(32,3)));
                assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
                ready(setup, binding);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordPromptStage());
                setup.session.beginAuthDiscovery(); binding.discovery = new int[] {2, 4}; setup.session.step();
                Object stage = setup.session.passwordPromptStage();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordPromptStage());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginDiscoveredPassword(new Object(), input));
                setup.session.pollPasswordPrompt(stage);
                setup.session.pollPasswordPrompt(stage);
                assertEquals(2, binding.promptPolls); assertEquals(4, binding.promptMethod);
                assertArrayEquals(new byte[32], binding.borrowedPollPlan);
                if (mutation >= 0) {
                    assertCode("SSH_SESSION_AUTH", () -> setup.session.beginDiscoveredPassword(stage, input));
                    assertCode("SSH_SESSION_AUTH", () -> setup.session.beginShell(80, 24));
                } else {
                    setup.session.beginDiscoveredPassword(stage, input);
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.beginDiscoveredPassword(stage, input));
                    binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
                }
                assertEquals(1, binding.discoveredPasswordCalls);
                assertEquals(0, binding.keyBegins); assertEquals(0, binding.authBegins); assertEquals(0, binding.keyboardBegins);
                assertArrayEquals(new byte[32], binding.borrowedPlan);
                assertArrayEquals(new byte[binding.borrowedStage.length], binding.borrowedStage);
                assertArrayEquals(new byte[input.length], binding.borrowedPassword);
                assertArrayEquals(bytes("synthetic-password"), input);
            } finally { VaultCodec.wipe(input); if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void automaticAlternativesAndPartialContinueOnlyUnderTheOriginalPlanAndKex() throws Exception {
        for (int outcome : new int[] {3, 4}) for (int first : new int[] {0, 1, 2})
                for (int mutation : new int[] {-1, 0, 11, 15, 19, 20, 52, 60, 91, 92}) {
            boolean key = first != 0, keyFirst = first == 2;
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            binding.passwordOriginal = key ? 4 : 3;
            binding.automaticContinuationOriginal = key ? 4 : 3;
            binding.automaticContinuationStage = keyFirst ? 2 : 3;
            Setup setup = new Setup(true, binding); byte[] input = bytes("synthetic-password");
            try {
                SshAuthenticationSelection selection = key ? SshAuthenticationSelection.privateKey(setup.issued.role,
                        "user", "keys/key", "identity", bytes("public"), setup.clock)
                        : SshAuthenticationSelection.keyboardInteractive(setup.issued.role, "user", setup.clock);
                setup.callback.selectAuthentication(selection.withAutomaticDiscovery(key ? 7 : 6, setup.clock));
                setup.session = open(setup);
                assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                        filled(32,2), filled(32,3)));
                assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
                ready(setup, binding); setup.session.beginAuthDiscovery();
                binding.discovery = new int[] {2, keyFirst ? 1 : 4}; setup.session.step();
                if (keyFirst) setup.session.beginPrivateKeyAuth();
                else setup.session.beginDiscoveredPassword(setup.session.passwordPromptStage(), input);
                binding.result = outcome; setup.session.step();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80, 24));
                assertEquals(2, setup.session.authContinuationMethods());
                binding.receiptMutation = mutation;
                if (mutation >= 0) {
                    assertCode("SSH_MFA_AUTH", () -> setup.session.continueKeyboardInteractiveAuth());
                    assertCode("SSH_MFA_AUTH", () -> setup.session.beginShell(80, 24));
                } else {
                    setup.session.continueKeyboardInteractiveAuth();
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.continueKeyboardInteractiveAuth());
                    binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
                }
                assertEquals(1, binding.continuationCalls);
                assertArrayEquals(new byte[32], binding.borrowedPlan);
                assertArrayEquals(new byte[binding.borrowedStage.length], binding.borrowedStage);
            } finally { VaultCodec.wipe(input); if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void keyboardAlternativesAndPartialCanContinueWithActualPasswordOrSelectedKey() throws Exception {
        for (int outcome : new int[] {3, 4}) for (int mode = 0; mode < 3; mode++)
                for (int mutation : new int[] {-1, 11, 15, 19, 20, 60, 92}) {
            boolean key = mode != 0, keyNext = mode == 2;
            MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
            binding.passwordOriginal = key ? 4 : 3; binding.passwordStage = key ? 3 : 2;
            binding.privateContinuationStage = 3; binding.continuationMethods = keyNext ? 1 : 4;
            Setup setup = new Setup(true, binding); byte[] input = bytes("synthetic-password");
            try {
                SshAuthenticationSelection selection = key ? SshAuthenticationSelection.privateKey(setup.issued.role,
                        "user", "keys/key", "identity", bytes("public"), setup.clock)
                        : SshAuthenticationSelection.keyboardInteractive(setup.issued.role, "user", setup.clock);
                setup.callback.selectAuthentication(selection.withAutomaticDiscovery(key ? 7 : 6, setup.clock));
                setup.session = open(setup);
                assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                        filled(32,2), filled(32,3)));
                assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
                ready(setup, binding); setup.session.beginAuthDiscovery();
                binding.discovery = new int[] {2, 2}; setup.session.step();
                if (key) setup.session.beginDiscoveredKeyboard(); else setup.session.beginKeyboardInteractiveAuth();
                binding.result = outcome; setup.session.step();
                assertEquals(keyNext ? 1 : 4, setup.session.authContinuationMethods());
                if (keyNext) {
                    Object keyStage = setup.session.privateKeyPromptStage();
                    assertEquals(1, setup.session.privateKeyPromptMethods(keyStage));
                    setup.session.pollPrivateKeyPrompt(keyStage);
                    assertEquals(1, binding.promptMethod);
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.continuePrivateKeyAuth());
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.finishPrivateKeyPrompt(new Object()));
                    setup.session.finishPrivateKeyPrompt(keyStage);
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.finishPrivateKeyPrompt(keyStage));
                }
                Object stage = keyNext ? null : setup.session.passwordPromptStage();
                if (stage != null) {
                    assertEquals(4, setup.session.passwordPromptMethods(stage));
                    setup.session.pollPasswordPrompt(stage); assertEquals(4, binding.promptMethod);
                }
                binding.receiptMutation = mutation;
                if (mutation >= 0) {
                    if (keyNext) assertCode("SSH_MFA_AUTH", () -> setup.session.continuePrivateKeyAuth());
                    else assertCode("SSH_SESSION_AUTH", () -> setup.session.beginDiscoveredPassword(stage, input));
                } else {
                    if (keyNext) setup.session.continuePrivateKeyAuth();
                    else setup.session.beginDiscoveredPassword(stage, input);
                    binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
                }
                assertEquals(keyNext ? 1 : 0, binding.privateContinuationCalls);
                assertEquals(keyNext ? 0 : 1, binding.discoveredPasswordCalls);
                assertArrayEquals(new byte[binding.borrowedStage.length], binding.borrowedStage);
            } finally { VaultCodec.wipe(input); if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void threeActualMethodsCanReachTheFourthBoundStage() throws Exception {
        MfaNative binding = new MfaNative(); binding.receiptMutation = -1;
        binding.passwordOriginal = 4; binding.passwordStage = 3; binding.privateContinuationStage = 4;
        Setup setup = new Setup(true, binding); byte[] input = bytes("synthetic-password");
        try {
            setup.callback.selectAuthentication(SshAuthenticationSelection.privateKey(setup.issued.role,
                    "user", "keys/key", "identity", bytes("public"), setup.clock).withAutomaticDiscovery(7, setup.clock));
            setup.session = open(setup);
            assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                    "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                    filled(32,2), filled(32,3)));
            assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
            ready(setup, binding); setup.session.beginAuthDiscovery();
            binding.discovery = new int[] {2, 2}; setup.session.step(); setup.session.beginDiscoveredKeyboard();
            binding.result = 4; setup.session.step(); binding.continuationMethods = 4;
            setup.session.beginDiscoveredPassword(setup.session.passwordPromptStage(), input);
            binding.result = 4; setup.session.step(); binding.continuationMethods = 1;
            setup.session.continuePrivateKeyAuth();
            binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
            assertEquals(1, binding.discoveredKeyboardCalls);
            assertEquals(1, binding.discoveredPasswordCalls);
            assertEquals(1, binding.privateContinuationCalls);
        } finally { VaultCodec.wipe(input); if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void delayedPasswordAnswerPollsTheOwnedPromptInsteadOfSteppingAnIdleSession() throws Exception {
        MfaNative binding = new MfaNative(); binding.receiptMutation = -1; binding.passwordOriginal = 3;
        Setup setup = new Setup(true, binding);
        java.util.concurrent.CountDownLatch polled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> responder = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<SshPasswordMailbox> mailboxRef = new java.util.concurrent.atomic.AtomicReference<>();
        SshPasswordMailbox mailbox = null;
        try {
            SshAuthenticationSelection selection = SshAuthenticationSelection.keyboardInteractive(setup.issued.role,
                    "user", setup.clock).withAutomaticDiscovery(6, setup.clock);
            setup.callback.selectAuthentication(selection);
            setup.session = open(setup);
            assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                    "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                    filled(32,2), filled(32,3)));
            assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
            ready(setup, binding); setup.session.beginAuthDiscovery();
            binding.discovery = new int[] {2, 4}; setup.session.step();
            Object stage = setup.session.passwordPromptStage();
            long stepsBeforePrompt = binding.order.stream().filter("step"::equals).count();
            mailbox = new SshPasswordMailbox(setup.issued.role, selection, setup.clock,
                    () -> setup.session.requirePasswordPromptStageLive(stage), ticket -> {
                Thread thread = new Thread(() -> {
                    try {
                        if (!polled.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("prompt not polled");
                        assertTrue(mailboxRef.get().respond(ticket.id, ticket.binding,
                                new byte[] {65}, SshPasswordMailbox.Choice.ONCE));
                    } catch (Throwable error) { failure.set(error); }
                });
                responder.set(thread); thread.start();
            }, SshPasswordMailbox.Purpose.PASSWORD);
            mailboxRef.set(mailbox);
            try (SshPasswordMailbox.Response response = mailbox.await(() -> {
                setup.session.pollPasswordPrompt(stage);
                polled.countDown();
            }, false)) {
                SshPasswordConnector.submitDiscoveredPassword(setup.session, stage, response.bytes, () -> {});
            }
            responder.get().join(); assertNull(failure.get());
            assertTrue(binding.promptPolls >= 2); assertEquals(4, binding.promptMethod);
            assertEquals(stepsBeforePrompt, binding.order.stream().filter("step"::equals).count());
            assertEquals(1, binding.discoveredPasswordCalls); assertArrayEquals(new byte[32], binding.borrowedPollPlan);
        } finally {
            if (mailbox != null) mailbox.close();
            if (setup.session != null) setup.session.close(); setup.closeRegistry();
        }
    }

    @Test public void promptTransportFailureCannotLaterSendASecret() throws Exception {
        MfaNative binding = new MfaNative(); binding.receiptMutation = -1; binding.passwordOriginal = 3;
        Setup setup = new Setup(true, binding); byte[] input = bytes("synthetic-password");
        try {
            setup.callback.selectAuthentication(SshAuthenticationSelection.keyboardInteractive(setup.issued.role,
                    "user", setup.clock).withAutomaticDiscovery(6, setup.clock));
            setup.session = open(setup);
            assertTrue(setup.callback.onNativeHostKey(SshNativeCallbackAdapter.HOST_KNOWN, "ssh-ed25519",
                    "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(filled(32,7)),
                    filled(32,2), filled(32,3)));
            assertTrue(setup.callback.onNativeKexComplete(1, filled(32,7)));
            ready(setup, binding); setup.session.beginAuthDiscovery();
            binding.discovery = new int[] {2, 4}; setup.session.step();
            Object stage = setup.session.passwordPromptStage(); binding.failOperation = "promptPoll";
            assertCode("SSH_SESSION_STEP", () -> SshPasswordConnector.submitDiscoveredPassword(setup.session, stage, input, () -> {}));
            assertCode("SSH_SESSION_STEP", () -> setup.session.beginDiscoveredPassword(stage, input));
            assertEquals(0, binding.discoveredPasswordCalls);
            assertArrayEquals(new byte[32], binding.borrowedPollPlan);
        } finally { VaultCodec.wipe(input); if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void discoveryUsesTheOriginalSessionAndDistinguishesNoneSuccess() throws Exception {
        for (boolean key : new boolean[] {false, true}) for (int outcome : new int[] {2, 3, 4}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                select(setup, key); setup.session = open(setup); ready(setup, binding);
                assertCode("SSH_SESSION_PHASE", () -> setup.session.authDiscoveryResult());
                AtomicReference<Throwable> wrongOwner = new AtomicReference<>();
                Thread other = new Thread(() -> {
                    try { setup.session.beginAuthDiscovery(); }
                    catch (Throwable failure) { wrongOwner.set(failure); }
                });
                other.start(); other.join();
                assertTrue(wrongOwner.get() instanceof VaultException);
                assertEquals("SSH_SESSION_THREAD", ((VaultException)wrongOwner.get()).code);
                setup.session.beginAuthDiscovery();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginAuthDiscovery());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80, 24));
                assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
                assertEquals(SshNativeSession.AuthDiscoveryResult.PENDING, setup.session.authDiscoveryResult().result);
                binding.discovery = new int[] {outcome, outcome == 3 ? 0 : key ? 1 : 4};
                setup.session.step();
                SshNativeSession.AuthDiscoveryStatus status = setup.session.authDiscoveryResult();
                assertEquals(outcome == 2 ? SshNativeSession.AuthDiscoveryResult.METHODS
                        : outcome == 3 ? SshNativeSession.AuthDiscoveryResult.NONE_SUCCESS
                        : SshNativeSession.AuthDiscoveryResult.PARTIAL, status.result);
                assertEquals(outcome == 3 ? 0 : key ? 1 : 4, status.methods);
                if (outcome == 3) {
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.passwordAuthResult());
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPrivateKeyAuth());
                    setup.session.beginShell(80, 24);
                    assertEquals(0, binding.authBegins); assertEquals(0, binding.keyBegins);
                } else {
                    if (key) setup.session.beginPrivateKeyAuth(); else setup.session.beginPasswordAuth();
                    binding.result = 2; setup.session.step(); setup.session.beginShell(80, 24);
                    assertEquals(key ? 1 : 0, binding.keyBegins);
                    assertEquals(key ? 0 : 1, binding.authBegins);
                }
                assertEquals(1, binding.discoveryBegins);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void discoveryRefusesUnadvertisedMethodsAndMalformedOrChangedResults() throws Exception {
        for (int[] invalid : new int[][] {{0, 0}, {1, 1}, {2, 16}, {3, 1}, {5, 0}}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                select(setup, false); setup.session = open(setup); ready(setup, binding);
                setup.session.beginAuthDiscovery(); binding.discovery = invalid;
                assertCode("SSH_SESSION_AUTH_DISCOVERY", () -> setup.session.step());
                assertCode("SSH_SESSION_AUTH_DISCOVERY", () -> setup.session.beginShell(80, 24));
                assertEquals(0, binding.authBegins);
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
        ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
        try {
            select(setup, false); setup.session = open(setup); ready(setup, binding);
            setup.session.beginAuthDiscovery(); binding.discovery = new int[] {2, 2}; setup.session.step();
            assertCode("SSH_SESSION_PHASE", () -> setup.session.beginPasswordAuth());
            assertEquals(0, binding.authBegins);
            binding.discovery = new int[] {2, 4};
            assertCode("SSH_SESSION_AUTH_DISCOVERY", () -> setup.session.authDiscoveryResult());
        } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void discoveryCancellationCannotPromoteASession() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                select(setup, false); setup.session = open(setup); ready(setup, binding);
                if (cancel) binding.protocolHook = () -> {
                    try { setup.session.cancel(); } catch (VaultException failure) { throw new AssertionError(failure); }
                };
                else binding.failOperation = "discoveryBegin";
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_AUTH_DISCOVERY",
                        () -> setup.session.beginAuthDiscovery());
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_AUTH_DISCOVERY",
                        () -> setup.session.beginShell(80, 24));
                assertEquals(0, binding.shellCalls);
                binding.failOperation = null; binding.protocolHook = null;
            } finally { if (setup.session != null) setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void noPtyRequiresSuccessfulAuthenticationAndOriginalOwner() throws Exception {
        for (boolean key : new boolean[] {false, true}) for (int result : new int[] {1, 2, 3, 4}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                authenticated(setup, binding, key, result);
                if (result != 2) {
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.configureNoPty());
                    assertEquals(0, binding.noPtyCalls);
                } else {
                    AtomicReference<Throwable> wrongOwner = new AtomicReference<>();
                    Thread other = new Thread(() -> {
                        try { setup.session.configureNoPty(); }
                        catch (Throwable failure) { wrongOwner.set(failure); }
                    });
                    other.start(); other.join();
                    assertTrue(wrongOwner.get() instanceof VaultException);
                    assertEquals("SSH_SESSION_THREAD", ((VaultException) wrongOwner.get()).code);
                    assertEquals(0, binding.noPtyCalls);
                    setup.session.configureNoPty();
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.configureNoPty());
                    setup.session.beginShell(80, 24);
                    assertCode("SSH_SESSION_PHASE", () -> setup.session.configureNoPty());
                    assertEquals(1, binding.noPtyCalls);
                    binding.status[0] = 3;
                    assertEquals(SshNativeSession.ShellPhase.SHELL_PENDING, setup.session.shellStatus().phase);
                    binding.status[0] = 4;
                    assertEquals(SshNativeSession.ShellPhase.RUNNING, setup.session.shellStatus().phase);
                }
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void noPtyNativeFailureAndCancellationCannotPromoteTheSession() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                authenticated(setup, binding, false, 2);
                if (cancel) binding.protocolHook = () -> {
                    try { setup.session.cancel(); } catch (VaultException failure) { throw new AssertionError(failure); }
                };
                else binding.failOperation = "noPty";
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_SHELL", () -> setup.session.configureNoPty());
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_SHELL", () -> setup.session.beginShell(80, 24));
                assertEquals(1, binding.noPtyCalls); assertEquals(0, binding.shellCalls);
                binding.failOperation = null; binding.protocolHook = null;
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void serverAlivePolicyRequiresSuccessfulAuthenticationAndOriginalOwner() throws Exception {
        for (boolean key : new boolean[] {false, true}) {
            for (int result : new int[] {1, 2, 3, 4}) {
                ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
                try {
                    authenticated(setup, binding, key, result);
                    if (result != 2) {
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.configureServerAlive(10, 2));
                        assertEquals(0, binding.aliveCalls);
                    } else {
                        assertCode("SSH_SESSION_REQUIRED", () -> setup.session.configureServerAlive(-1, 2));
                        assertEquals(0, binding.aliveCalls);
                        AtomicReference<Throwable> wrongOwner = new AtomicReference<>();
                        Thread other = new Thread(() -> {
                            try { setup.session.configureServerAlive(10, 2); }
                            catch (Throwable failure) { wrongOwner.set(failure); }
                        });
                        other.start(); other.join();
                        assertTrue(wrongOwner.get() instanceof VaultException);
                        assertEquals("SSH_SESSION_THREAD", ((VaultException) wrongOwner.get()).code);
                        assertEquals(0, binding.aliveCalls);
                        setup.session.configureServerAlive(10, 2);
                        assertEquals(1, binding.aliveCalls); assertEquals(10, binding.aliveInterval);
                        assertEquals(2, binding.aliveCountMax);
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.configureServerAlive(10, 2));
                        setup.session.beginShell(80, 24);
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.configureServerAlive(10, 2));
                        assertEquals(1, binding.aliveCalls);
                    }
                } finally { setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void serverAliveNativeFailureAndCancellationCannotPromoteTheSession() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            ShellNative binding = new ShellNative(); Setup setup = new Setup(true, binding);
            try {
                authenticated(setup, binding, false, 2);
                if (cancel) binding.protocolHook = () -> {
                    try { setup.session.cancel(); } catch (VaultException failure) { throw new AssertionError(failure); }
                };
                else binding.failOperation = "serverAlive";
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_KEEPALIVE",
                        () -> setup.session.configureServerAlive(10, 2));
                assertEquals(1, binding.aliveCalls);
                assertCode(cancel ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_KEEPALIVE",
                        () -> setup.session.beginShell(80, 24));
                assertEquals(0, binding.shellCalls);
                binding.failOperation = null; binding.protocolHook = null;
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellRequiresSavedSelectedSuccessForEitherAuthentication() throws Exception {
        for (boolean key : new boolean[] {false, true}) {
            for (int result = 1; result <= (key ? 4 : 5); result++) {
                ShellNative binding = new ShellNative();
                Setup setup = new Setup(true, binding);
                try {
                    authenticated(setup, binding, key, result);
                    if (result != 2) {
                        int calls = binding.shellCalls;
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80, 24));
                        assertEquals(calls, binding.shellCalls);
                    } else {
                        assertCode("SSH_SESSION_REQUIRED", () -> setup.session.beginShell(0, 24));
                        assertEquals(SshNativeSession.ShellPhase.DISABLED, setup.session.shellStatus().phase);
                        setup.session.beginShell(80, 24);
                        int calls = binding.shellCalls;
                        assertCode("SSH_SESSION_PHASE", () -> setup.session.beginShell(80, 24));
                        assertEquals(calls, binding.shellCalls);
                        binding.status[0] = 4;
                        setup.session.step();
                        assertEquals(SshNativeSession.ShellPhase.RUNNING, setup.session.shellStatus().phase);
                        assertEquals(1, binding.authBegins + binding.keyBegins);
                    }
                } finally { setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void shellDefaultsFailClosedForEveryNewEntry() throws Exception {
        SessionNative defaults = new SessionNative();
        for (int operation = 0; operation < 6; operation++) {
            try {
                if (operation == 0) defaults.beginShell(903, 80, 24);
                if (operation == 1) defaults.shellStatus(903);
                if (operation == 2) defaults.writeShell(903, bytes("x"));
                if (operation == 3) defaults.readShell(903, 10);
                if (operation == 4) defaults.resizeShell(903, 80, 24);
                if (operation == 5) defaults.sendShellEof(903);
                fail("missing shell default");
            } catch (IOException expected) { assertEquals("SSH shell is unavailable", expected.getMessage()); }
        }
    }

    @Test public void shellStatusTracksUnsignedExitAndDrainsAfterCloseWithoutAnotherStep() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        try {
            shellRunning(setup, binding);
            binding.status = new int[] {5, 1, 0, 1, -1, 0, 8};
            int steps = binding.order.size();
            assertCode("SSH_SESSION_PHASE", () -> setup.session.step());
            assertEquals(steps, binding.order.size());
            SshNativeSession.ShellStatus status = setup.session.shellStatus();
            assertEquals(SshNativeSession.ShellPhase.CLOSED, status.phase);
            assertEquals(4294967295L, status.exitStatus);
            assertTrue(status.exitStatusKnown);
            assertTrue(status.remoteEof);
            assertFalse(status.localEof);
            assertFalse(status.exitSignal);
            assertEquals(8, status.bufferedBytes);
            byte[] output = setup.session.readShell(32);
            assertArrayEquals(binding.output, output);
            assertTrue(allZero(binding.readReference));
            binding.status[6] = 0;
            assertEquals(8, status.bufferedBytes);
            setup.session.close();
            assertTrue(setup.session.closed());
            assertEquals(Arrays.asList("view", "close", "destroy"),
                    binding.order.subList(binding.order.size() - 3, binding.order.size()));
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void shellStatusRejectsMalformedFlagsCapsPhasesAndRegression() throws Exception {
        int[][] malformed = {null, new int[6], {7,0,0,0,0,0,0}, {4,2,0,0,0,0,0},
            {4,0,2,0,0,0,0}, {4,0,0,2,0,0,0}, {4,0,0,0,0,2,0},
            {4,0,0,0,0,0,-1}, {4,0,0,0,0,0,65537}, {4,0,0,0,1,0,0},
            {4,0,0,1,0,1,0}, {0,0,0,0,0,0,0}, {3,0,0,0,0,0,0}};
        for (int[] status : malformed) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                shellRunning(setup, binding);
                binding.status = status;
                assertCode("SSH_SESSION_SHELL_STATUS", () -> setup.session.shellStatus());
                assertCode("SSH_SESSION_SHELL_STATUS", () -> setup.session.readShell(20));
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
        for (int field : new int[] {1,2,3,5}) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                shellRunning(setup, binding);
                binding.status[field] = 1;
                setup.session.shellStatus();
                binding.status[field] = 0;
                assertCode("SSH_SESSION_SHELL_STATUS", () -> setup.session.shellStatus());
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellPartialWriteAndBackpressurePreserveCallerDataAndEofDirection() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        try {
            shellRunning(setup, binding);
            byte[] input = bytes("command\n");
            byte[] original = input.clone();
            assertEquals(0, setup.session.writeShell(input));
            assertTrue(allZero(binding.writeReference));
            binding.accepted = 3;
            assertEquals(3, setup.session.writeShell(input));
            assertTrue(allZero(binding.writeReference));
            assertArrayEquals(original, input);
            assertFalse(setup.session.resizeShell(120, 40));
            binding.resizeAccepted = 1;
            assertTrue(setup.session.resizeShell(120, 40));
            binding.status[1] = 1; // Remote EOF permits writes until local EOF or CLOSE.
            assertEquals(3, setup.session.writeShell(input));
            assertFalse(setup.session.sendShellEof());
            binding.eofAccepted = 1;
            assertTrue(setup.session.sendShellEof());
            int writes = binding.writes;
            assertCode("SSH_SESSION_PHASE", () -> setup.session.writeShell(input));
            assertCode("SSH_SESSION_PHASE", () -> setup.session.sendShellEof());
            assertEquals(writes, binding.writes);
            assertTrue(setup.session.resizeShell(100, 30));
            assertArrayEquals(binding.output, setup.session.readShell(32));
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void queuedShellOutputPublicationIsReadonlyCrossThreadAndDropsStaleData() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                shellRunning(setup, binding);
                assertArrayEquals(binding.output, setup.session.readShell(32));
                AtomicReference<Boolean> live = new AtomicReference<>();
                Thread initial = new Thread(() -> live.set(setup.session.shellOutputPublicationLive()));
                initial.start(); initial.join();
                assertTrue(live.get());
                if (mode == 0) setup.session.cancel();
                if (mode == 1) setup.callback.cancel();
                if (mode == 2) setup.clock.value = setup.issued.role.deadline;
                if (mode == 3) setup.view.active = false;
                if (mode == 4) {
                    binding.output = null;
                    assertCode("SSH_SESSION_SHELL_READ", () -> setup.session.readShell(32));
                }
                if (mode == 5) setup.session.close();
                int calls = binding.shellCalls;
                Thread publish = new Thread(() -> live.set(setup.session.shellOutputPublicationLive()));
                publish.start(); publish.join();
                assertEquals(mode == 2 || mode == 3, live.get());
                assertEquals(calls, binding.shellCalls);
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void connectorHandoffIsAdmittedAgainstItsOwnersStop() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        AppSecrets vault = new AppSecrets(new AppSecrets.Store() {
            @Override public byte[] read() { throw new AssertionError("unexpected vault read"); }
            @Override public byte[] quickMaterial() { throw new AssertionError("unexpected vault read"); }
            @Override public void commit(byte[] envelope, byte[] quick, AppSecrets.EffectGuard guard) {
                throw new AssertionError("unexpected vault write");
            }
        }, setup.clock);
        vault.attachSshValidator(manifest -> { });
        vault.session.foreground(true);
        vault.session.unlock(new byte[32]);
        SshSessionOwner owner = null;
        try {
            shellRunning(setup, binding);
            SshPasswordConnector connector = new SshPasswordConnector(vault,
                    new SshPasswordProfile("example.invalid", 22, "user"), "", "", "",
                    31_000, () -> { }, null, setup.clock);
            java.lang.reflect.Field session = SshPasswordConnector.class.getDeclaredField("session");
            session.setAccessible(true);
            session.set(connector, setup.session);
            List<Runnable> posted = new ArrayList<>();
            owner = new SshSessionOwner(connector, "ssh", 80, 24, new SshTerminalState(), posted::add,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String state) { }
                        @Override public void released() { }
                    });

            assertTrue(connector.handoffLive());
            owner.requestStop();
            assertTrue(connector.trustCancellation().stopRequested());
            // Do not run the owner's queued cleanup: the live session isolates the shared STOP guard.
            assertTrue(setup.session.shellOutputPublicationLive());
            assertFalse(connector.handoffLive());
        } finally {
            if (owner != null) {
                java.lang.reflect.Field executor = SshSessionOwner.class.getDeclaredField("executor");
                executor.setAccessible(true);
                ((java.util.concurrent.ScheduledExecutorService) executor.get(owner)).shutdownNow();
            }
            if (setup.session != null) setup.session.close();
            setup.closeRegistry();
            vault.session.lock();
        }
    }

    @Test public void acceptedShellEofCannotRegressEvenBeforeAnotherStatusRead() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        try {
            shellRunning(setup, binding);
            binding.eofAccepted = 1;
            assertTrue(setup.session.sendShellEof());
            binding.status[2] = 0;
            assertCode("SSH_SESSION_SHELL_STATUS", () -> setup.session.writeShell(bytes("x")));
            assertEquals(0, binding.writes);
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void shellEscapingErrorPreservesCancellationAndAlwaysAllowsCleanup() throws Exception {
        for (int operation : new int[] {0, 2, 3, 4, 5}) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                if (operation == 0) authenticated(setup, binding, false, 2);
                else shellRunning(setup, binding);
                binding.failOperation = new String[] {"shellBegin", "shellStatus", "shellWrite",
                    "shellRead", "shellResize", "shellEof"}[operation];
                binding.failureKind = 3;
                binding.protocolHook = () -> {
                    if (binding.failOperation.equals(binding.currentShellOperation)) {
                        try { setup.session.cancel(); }
                        catch (VaultException failure) { throw new AssertionError(failure); }
                    }
                };
                try { shellOperation(setup.session, operation).run(); fail("Error must propagate"); }
                catch (AssertionError expected) { assertEquals("synthetic native Error", expected.getMessage()); }
                assertCode("SSH_SESSION_CANCELLED", () -> setup.session.shellStatus());
            } finally { binding.protocolHook = null; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellInvalidLocalInputIsNonterminalAndNeverReachesIo() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        try {
            shellRunning(setup, binding);
            for (byte[] input : new byte[][] {null, new byte[0], new byte[32769]})
                assertCode("SSH_SESSION_REQUIRED", () -> setup.session.writeShell(input));
            for (int maximum : new int[] {0, -1, 32769})
                assertCode("SSH_SESSION_REQUIRED", () -> setup.session.readShell(maximum));
            assertCode("SSH_SESSION_REQUIRED", () -> setup.session.resizeShell(4097, 24));
            assertEquals(0, binding.writes + binding.reads);
            assertArrayEquals(binding.output, setup.session.readShell(32768));
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    private static CheckedAction shellOperation(SshNativeSession session, int operation) {
        if (operation == 0) return () -> session.beginShell(80, 24);
        if (operation == 1) return () -> session.shellStatus();
        if (operation == 2) return () -> session.writeShell(bytes("command\n"));
        if (operation == 3) return () -> session.readShell(32);
        if (operation == 4) return () -> session.resizeShell(100, 30);
        return () -> session.sendShellEof();
    }

    @Test public void shellFailuresAndErrorsTerminalizeAndAlwaysReleaseThreadGuard() throws Exception {
        String[] names = {"shellBegin", "shellStatus", "shellWrite", "shellRead", "shellResize", "shellEof"};
        String[] codes = {"SSH_SESSION_SHELL", "SSH_SESSION_SHELL_STATUS", "SSH_SESSION_SHELL_WRITE",
            "SSH_SESSION_SHELL_READ", "SSH_SESSION_SHELL_RESIZE", "SSH_SESSION_SHELL_EOF"};
        for (int operation = 0; operation < 6; operation++) {
            for (int kind = 0; kind < 4; kind++) {
                ShellNative binding = new ShellNative();
                Setup setup = new Setup(true, binding);
                try {
                    if (operation == 0) authenticated(setup, binding, false, 2);
                    else shellRunning(setup, binding);
                    binding.failOperation = names[operation];
                    binding.failureKind = kind;
                    CheckedAction action = shellOperation(setup.session, operation);
                    if (kind == 3) {
                        try { action.run(); fail("Error must propagate"); }
                        catch (AssertionError expected) { assertEquals("synthetic native Error", expected.getMessage()); }
                    } else assertCode(codes[operation], action);
                    assertCode(codes[operation], () -> setup.session.shellStatus());
                    if (binding.writeReference != null) assertTrue(allZero(binding.writeReference));
                    setup.session.close();
                    assertTrue(setup.session.closed());
                } finally { setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void shellPrePostLifecycleAndNativeFailurePreservePreciseErrorsWithoutPublishing() throws Exception {
        for (int operation = 0; operation < 6; operation++) {
            for (int mode = 0; mode < 12; mode++) {
                ShellNative binding = new ShellNative();
                Setup setup = new Setup(true, binding);
                try {
                    if (operation == 0) authenticated(setup, binding, false, 2);
                    else shellRunning(setup, binding);
                    final int lifecycle = mode % 4;
                    Runnable change = () -> {
                        try {
                            if (lifecycle == 0) setup.session.cancel();
                            if (lifecycle == 1) setup.callback.cancel();
                            if (lifecycle == 2) setup.clock.value = setup.issued.role.deadline;
                            if (lifecycle == 3) setup.view.active = false;
                        } catch (VaultException failure) { throw new AssertionError(failure); }
                    };
                    int calls = binding.shellCalls;
                    if (mode < 4) change.run();
                    else {
                        final String operationName = new String[] {"shellBegin", "shellStatus",
                            "shellWrite", "shellRead", "shellResize", "shellEof"}[operation];
                        binding.shellHook = () -> {
                            if (operationName.equals(binding.currentShellOperation)) change.run();
                        };
                    }
                    if (mode >= 8) binding.failOperation = new String[] {"shellBegin", "shellStatus",
                        "shellWrite", "shellRead", "shellResize", "shellEof"}[operation];
                    if (lifecycle >= 2 && operation != 0
                            || lifecycle == 2 && mode >= 4 && mode < 8) {
                        if (mode >= 8) {
                            String[] errors = {"SSH_SESSION_SHELL", "SSH_SESSION_SHELL_STATUS",
                                "SSH_SESSION_SHELL_WRITE", "SSH_SESSION_SHELL_READ",
                                "SSH_SESSION_SHELL_RESIZE", "SSH_SESSION_SHELL_EOF"};
                            assertCode(errors[operation], shellOperation(setup.session, operation));
                        } else shellOperation(setup.session, operation).run();
                        continue;
                    }
                    String code = lifecycle < 2 ? "SSH_SESSION_CANCELLED"
                            : lifecycle == 2 ? "SSH_SESSION_EXPIRED" : "SSH_FILE_VIEW";
                    assertCode(code, shellOperation(setup.session, operation));
                    if (mode < 4) assertEquals(calls, binding.shellCalls);
                    if (binding.writeReference != null) assertTrue(allZero(binding.writeReference));
                    if (mode < 8 && binding.readReference != null) assertTrue(allZero(binding.readReference));
                } finally { binding.shellHook = null; setup.session.close(); setup.closeRegistry(); }
            }
        }
    }

    @Test public void establishedShellOutlivesAuthDeadlineWithoutRenewingSelection() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        try {
            shellRunning(setup, binding);
            setup.clock.value = setup.issued.role.deadline;
            assertCode("SSH_SESSION_PHASE", () -> setup.session.authenticationSelectionReceipt());
            assertCode("SSH_FILE_VIEW", () -> setup.issued.role.requireActive(setup.clock));
            setup.session.step();
            setup.session.shellStatus();
            setup.session.writeShell(bytes("x"));
            assertArrayEquals(binding.output, setup.session.readShell(32));
            setup.session.resizeShell(100, 30);
            setup.session.sendShellEof();
            assertTrue(setup.session.shellOutputPublicationLive());
            setup.callback.cancel();
            assertFalse(setup.session.shellOutputPublicationLive());
            assertCode("SSH_SESSION_CANCELLED", () -> setup.session.shellStatus());
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void promotionRetiresAuthenticationBeforeOutputAndRejectsLaterSecretsWithoutCancelling() throws Exception {
        for (boolean key : new boolean[] {false, true}) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            int[] retired = {0};
            try {
                authenticated(setup, binding, key, 2);
                setup.session.retireAuthenticationWith(() -> {
                    assertFalse(setup.view.active());
                    assertFalse(setup.session.shellOutputPublicationLive());
                    retired[0]++;
                });
                setup.session.beginShell(80, 24);
                assertEquals(1, retired[0]);
                assertEquals(1, setup.view.closeCalls);
                binding.status[0] = 4;
                assertTrue(setup.session.shellOutputPublicationLive());
                assertCode("SSH_SESSION_PHASE", () -> setup.session.authenticationSelectionReceipt());
                assertCode("SSH_CALLBACK_TRANSPORT_ONLY", setup.callback::requireAuthenticationSelectionLive);
                assertEquals(null, setup.callback.onNativeCredentialBytes(1));
                assertFalse(setup.callback.onNativeCredential(1));
                assertFalse(setup.issued.role.reclaimable(setup.clock));
                assertArrayEquals(binding.output, setup.session.readShell(32));
                assertTrue(setup.session.shellOutputPublicationLive());
                setup.callback.cancel();
                assertFalse(setup.session.shellOutputPublicationLive());
                assertCode("SSH_SESSION_CANCELLED", () -> setup.session.shellStatus());
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellConfirmationRunsOnceBeforePublicationAndOnlyOnRunningShell() throws Exception {
        for (SshNativeSession.ShellPhase phase : new SshNativeSession.ShellPhase[] {
                SshNativeSession.ShellPhase.RUNNING, SshNativeSession.ShellPhase.CLOSED,
                SshNativeSession.ShellPhase.REJECTED }) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            int[] confirmations = {0};
            try {
                authenticated(setup, binding, false, 2);
                setup.session.confirmShellWith(() -> {
                    setup.session.requireShellConfirmationLive();
                    assertFalse(setup.view.active());
                    assertFalse(setup.session.shellOutputPublicationLive());
                    confirmations[0]++;
                });
                setup.session.beginShell(80, 24);
                assertEquals(0, confirmations[0]);
                assertFalse(setup.session.shellOutputPublicationLive());
                binding.status[0] = phase.tag;
                setup.session.shellStatus();
                setup.session.shellStatus();
                boolean running = phase == SshNativeSession.ShellPhase.RUNNING;
                assertEquals(running ? 1 : 0, confirmations[0]);
                assertEquals(running, setup.session.shellOutputPublicationLive());
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void failedShellConfirmationNeverPublishesOrRetriesSave() throws Exception {
        ShellNative binding = new ShellNative();
        Setup setup = new Setup(true, binding);
        int[] confirmations = {0};
        try {
            authenticated(setup, binding, false, 2);
            setup.session.confirmShellWith(() -> {
                confirmations[0]++;
                throw new VaultException("VAULT_COMMIT_UNCERTAIN", "synthetic save outcome");
            });
            setup.session.beginShell(80, 24);
            binding.status[0] = 4;
            assertCode("VAULT_COMMIT_UNCERTAIN", setup.session::shellStatus);
            assertCode("VAULT_COMMIT_UNCERTAIN", setup.session::shellStatus);
            assertEquals(1, confirmations[0]);
            assertFalse(setup.session.shellOutputPublicationLive());
        } finally { setup.session.close(); setup.closeRegistry(); }
    }

    @Test public void stopDuringShellConfirmationKeepsItsFailureAcrossCleanupRetry() throws Exception {
        for (boolean screenLock : new boolean[] {false, true}) {
            CountDownLatch atConfirmation = new CountDownLatch(1), allowConfirmation = new CountDownLatch(1);
            CountDownLatch retryEntered = new CountDownLatch(1), allowClose = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            SshTerminalState events = new SshTerminalState();
            SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
                private Setup setup;
                private int closeCalls;

                @Override public boolean tracksCredentialSave() { return true; }

                @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                    ShellNative binding = new ShellNative() {
                        @Override public void beginShell(long handle, int columns, int rows) throws IOException {
                            super.beginShell(handle, columns, rows);
                            status[0] = SshNativeSession.ShellPhase.RUNNING.tag;
                        }
                    };
                    setup = new Setup(true, binding);
                    authenticated(setup, binding, false, 2);
                    setup.session.confirmShellWith(() -> {
                        atConfirmation.countDown();
                        awaitTestLatch(allowConfirmation);
                    });
                    return setup.session;
                }

                @Override public void close() throws Exception {
                    if (++closeCalls == 1) throw new IOException("synthetic cleanup failure");
                    retryEntered.countDown();
                    awaitTestLatch(allowClose);
                    setup.closeRegistry();
                }
            };
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24, events, Runnable::run,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String state) { }
                        @Override public void released() { released.countDown(); }
                    });
            owner.begin();
            try {
                assertTrue(atConfirmation.await(5, TimeUnit.SECONDS));
                if (screenLock) assertTrue(owner.stopForScreenLock(owner.id));
                else owner.stop(owner.generation);
                assertFalse(owner.connected.isDone());
                allowConfirmation.countDown();
                assertTrue(retryEntered.await(5, TimeUnit.SECONDS));
                assertEquals("SSH_TERMINAL_CLEANUP_PENDING", events.snapshot().get("reason"));
                assertFalse(owner.connected.isDone());
            } finally {
                allowConfirmation.countDown();
                allowClose.countDown();
            }
            try {
                owner.connected.get(5, TimeUnit.SECONDS);
                fail("A stopped shell must not publish the connection");
            } catch (ExecutionException failure) {
                assertEquals(screenLock ? "SSH_SCREEN_LOCKED" : "SSH_SESSION_CANCELLED",
                        ((VaultException)failure.getCause()).code);
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void deferredFirstResultSurvivesFactoryReturningSessionAndCleanupRetry() throws Exception {
        for (boolean serviceFirst : new boolean[] {false, true}) {
            CountDownLatch ready = new CountDownLatch(1), allowReturn = new CountDownLatch(1);
            CountDownLatch retryEntered = new CountDownLatch(1), allowClose = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            AtomicInteger nativeCloses = new AtomicInteger(), factoryCloses = new AtomicInteger();
            SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
                private Setup setup;
                @Override public boolean mayWriteTrust() { return true; }
                @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                    ShellNative binding = new ShellNative() {
                        @Override public void close(long handle) throws IOException {
                            if (nativeCloses.incrementAndGet() == 1) throw new IOException("injected native close failure");
                            retryEntered.countDown();
                            awaitTestLatch(allowClose);
                            super.close(handle);
                        }
                    };
                    setup = new Setup(true, binding);
                    authenticated(setup, binding, false, 2);
                    ready.countDown();
                    awaitTestLatch(allowReturn);
                    return setup.session;
                }
                @Override public void close() {
                    factoryCloses.incrementAndGet();
                    setup.closeRegistry();
                }
            };
            SshSessionOwner owner = new SshSessionOwner(factory, "ssh", 80, 24,
                    new SshTerminalState(), Runnable::run, new SshSessionOwner.Lifecycle() {
                        @Override public void state(String value) { }
                        @Override public void released() { released.countDown(); }
                    });
            owner.begin();
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                if (serviceFirst) {
                    owner.rejectStart();
                    assertTrue(owner.stopForScreenLock(owner.id));
                } else {
                    assertTrue(owner.stopForScreenLock(owner.id));
                    owner.rejectStart();
                }
                assertFalse(owner.connected.isDone());
                allowReturn.countDown();
                try { owner.connected.get(5, TimeUnit.SECONDS); fail("cancelled owner must not connect"); }
                catch (ExecutionException failure) {
                    assertEquals(serviceFirst ? "SSH_SERVICE_START" : "SSH_SCREEN_LOCKED",
                            ((VaultException)failure.getCause()).code);
                }
                assertTrue(retryEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, factoryCloses.get());
                assertEquals(1, released.getCount());
            } finally {
                allowReturn.countDown();
                allowClose.countDown();
            }
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(2, nativeCloses.get());
            assertEquals(1, factoryCloses.get());
        }
    }

    private static void awaitTestLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    /** Actual owner and terminal state; only the native binding and UI dispatcher are fixtures. */
    private static final class OutputOwnerFixture {
        final SshTerminalState events = new SshTerminalState();
        final SshSessionOwner.Cancellation cancellation = new SshSessionOwner.Cancellation();
        final BlockingQueue<Runnable> posted = new LinkedBlockingQueue<>();
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicReference<Setup> setup = new AtomicReference<>();
        final AtomicReference<ShellNative> binding = new AtomicReference<>();
        final SshSessionOwner owner;

        OutputOwnerFixture(SshTerminalState.Listener listener) {
            SshSessionOwner.Factory factory = new SshSessionOwner.Factory() {
                @Override public SshSessionOwner.Cancellation trustCancellation() { return cancellation; }
                @Override public SshNativeSession create(java.util.function.BooleanSupplier cancelled) throws Exception {
                    ShellNative nativeBinding = new ShellNative() {
                        @Override public void beginShell(long handle, int columns, int rows) throws IOException {
                            super.beginShell(handle, columns, rows);
                            status[0] = SshNativeSession.ShellPhase.RUNNING.tag;
                            status[6] = output.length;
                        }
                        @Override public byte[] readShell(long handle, int maximum) throws IOException {
                            byte[] result = super.readShell(handle, maximum);
                            status[6] = 0;
                            return result;
                        }
                    };
                    Setup created = new Setup(true, nativeBinding);
                    setup.set(created);
                    binding.set(nativeBinding);
                    authenticated(created, nativeBinding, false, 2);
                    return created.session;
                }
                @Override public void close() {
                    Setup created = setup.get();
                    if (created != null) created.closeRegistry();
                }
            };
            owner = new SshSessionOwner(factory, "ssh", 80, 24, events, posted::add,
                    new SshSessionOwner.Lifecycle() {
                        @Override public void state(String value) { }
                        @Override public void released() { released.countDown(); }
                    });
            events.subscribe(listener);
        }

        Runnable startAndTakeOutput() throws Exception {
            owner.begin();
            assertEquals("ssh", owner.connected.get(5, TimeUnit.SECONDS));
            Runnable running = posted.poll(5, TimeUnit.SECONDS);
            assertNotNull("the running event must be queued", running);
            running.run();
            assertEquals("running", events.snapshot().get("state"));
            Runnable output = posted.poll(5, TimeUnit.SECONDS);
            assertNotNull("the actual owner must queue output", output);
            return output;
        }

        byte[] inFlight() throws Exception {
            java.lang.reflect.Field terminal = SshSessionOwner.class.getDeclaredField("terminal");
            terminal.setAccessible(true);
            SshTerminalSession session = (SshTerminalSession)terminal.get(owner);
            assertNotNull(session);
            java.lang.reflect.Field output = SshTerminalSession.class.getDeclaredField("inFlight");
            output.setAccessible(true);
            byte[] bytes = (byte[])output.get(session);
            assertNotNull(bytes);
            return bytes;
        }

        void stopAndDrain() throws Exception {
            owner.stop(owner.generation);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (released.getCount() != 0 && System.nanoTime() < until) {
                Runnable action = posted.poll(100, TimeUnit.MILLISECONDS);
                if (action != null) action.run();
            }
            assertEquals("the original owner must finish cleanup", 0, released.getCount());
        }
    }

    private static void awaitStateMonitorBlock(Thread thread, String method) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < until) {
            if (thread.getState() == Thread.State.BLOCKED) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(SshTerminalState.class.getName())
                            && frame.getMethodName().equals(method)) return;
                }
            }
            Thread.sleep(1);
        }
        fail("thread must wait at SshTerminalState." + method + " on the state monitor");
    }

    @Test public void ownerDropsCheckedOutputIfSharedCancellationWinsBeforeStateAdmission() throws Exception {
        for (boolean stopOwner : new boolean[] {false, true}) {
            AtomicInteger deliveries = new AtomicInteger();
            OutputOwnerFixture fixture = new OutputOwnerFixture(event -> {
                if ("data".equals(event.get("type"))) deliveries.incrementAndGet();
            });
            AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
            Thread delivery = null;
            try {
                Runnable output = fixture.startAndTakeOutput();
                byte[] inFlight = fixture.inFlight();
                assertArrayEquals(bytes("output\r\n"), inFlight);
                assertTrue(allZero(fixture.binding.get().readReference));
                delivery = new Thread(() -> {
                    try { output.run(); } catch (Throwable failure) { deliveryFailure.set(failure); }
                }, "ssh-output-ui");
                synchronized (fixture.events) {
                    delivery.start();
                    awaitStateMonitorBlock(delivery, "data");
                    if (stopOwner) fixture.owner.stop(fixture.owner.generation);
                    else fixture.cancellation.cancel(); // connector cancellation precedes its owner callback
                    assertTrue(fixture.cancellation.getAsBoolean());
                    assertEquals(0, fixture.events.bufferedBytes());
                }
                delivery.join(5_000);
                assertFalse(delivery.isAlive());
                assertNull(deliveryFailure.get());
                assertEquals(0, deliveries.get());
                assertArrayEquals(new byte[0], (byte[])fixture.events.snapshot().get("bytes"));
                fixture.stopAndDrain();
                assertArrayEquals(new byte[inFlight.length], inFlight);
            } finally {
                if (delivery != null) delivery.join(5_000);
                fixture.stopAndDrain();
            }
        }
    }

    @Test public void admittedOutputCanFinishAfterStopButNewSnapshotHidesIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), allowDelivery = new CountDownLatch(1);
        AtomicReference<byte[]> delivered = new AtomicReference<>();
        OutputOwnerFixture fixture = new OutputOwnerFixture(event -> {
            if ("data".equals(event.get("type"))) {
                entered.countDown();
                awaitTestLatch(allowDelivery);
                delivered.set(((byte[])event.get("bytes")).clone());
            }
        });
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        AtomicReference<Map<String, Object>> snapshot = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread delivery = null, stopper = null, snapshotThread = null;
        try {
            Runnable output = fixture.startAndTakeOutput();
            byte[] inFlight = fixture.inFlight();
            delivery = new Thread(() -> {
                try { output.run(); } catch (Throwable failure) { deliveryFailure.set(failure); }
            }, "ssh-admitted-output");
            delivery.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            stopper = new Thread(() -> {
                try { fixture.owner.stop(fixture.owner.generation); } finally { stopped.countDown(); }
            }, "ssh-output-stop");
            stopper.start();
            assertTrue("stop must not wait for the listener", stopped.await(5, TimeUnit.SECONDS));
            assertNull("the admitted listener is still in progress", delivered.get());
            snapshotThread = new Thread(() -> snapshot.set(fixture.events.snapshot()), "ssh-output-snapshot");
            snapshotThread.start();
            awaitStateMonitorBlock(snapshotThread, "snapshot");
            allowDelivery.countDown();
            delivery.join(5_000);
            snapshotThread.join(5_000);
            assertFalse(delivery.isAlive());
            assertFalse(snapshotThread.isAlive());
            assertNull(deliveryFailure.get());
            assertArrayEquals(bytes("output\r\n"), delivered.get());
            assertArrayEquals(new byte[0], (byte[])snapshot.get().get("bytes"));
            fixture.stopAndDrain();
            assertArrayEquals(new byte[inFlight.length], inFlight);
        } finally {
            allowDelivery.countDown();
            if (delivery != null) delivery.join(5_000);
            if (stopper != null) stopper.join(5_000);
            if (snapshotThread != null) snapshotThread.join(5_000);
            fixture.stopAndDrain();
        }
    }

    @Test public void listenerCanSynchronouslyStopOwnerDuringAdmittedOutput() throws Exception {
        AtomicReference<OutputOwnerFixture> fixtureRef = new AtomicReference<>();
        AtomicReference<byte[]> delivered = new AtomicReference<>();
        AtomicReference<Map<String, Object>> beforeStop = new AtomicReference<>();
        AtomicReference<Map<String, Object>> afterStop = new AtomicReference<>();
        OutputOwnerFixture fixture = new OutputOwnerFixture(event -> {
            if ("data".equals(event.get("type"))) {
                OutputOwnerFixture current = fixtureRef.get();
                delivered.set(((byte[])event.get("bytes")).clone());
                beforeStop.set(current.events.snapshot());
                current.owner.stop(current.owner.generation);
                afterStop.set(current.events.snapshot());
            }
        });
        fixtureRef.set(fixture);
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        Thread delivery = null;
        try {
            Runnable output = fixture.startAndTakeOutput();
            byte[] inFlight = fixture.inFlight();
            delivery = new Thread(() -> {
                try { output.run(); } catch (Throwable failure) { deliveryFailure.set(failure); }
            }, "ssh-reentrant-output-stop");
            delivery.setDaemon(true);
            delivery.start();
            delivery.join(5_000);
            assertFalse("listener must return after synchronously stopping its owner", delivery.isAlive());
            assertNull(deliveryFailure.get());
            assertArrayEquals(bytes("output\r\n"), delivered.get());
            assertArrayEquals(bytes("output\r\n"), (byte[])beforeStop.get().get("bytes"));
            assertEquals("running", afterStop.get().get("state"));
            assertArrayEquals(new byte[0], (byte[])afterStop.get().get("bytes"));
            fixture.stopAndDrain();
            assertEquals(0, fixture.events.bufferedBytes());
            assertArrayEquals(new byte[inFlight.length], inFlight);
        } finally {
            if (delivery == null || !delivery.isAlive()) fixture.stopAndDrain();
        }
    }

    @Test public void ownerCancellationHidesPriorReplayFromNewSubscriberBeforeClosedEvent() throws Exception {
        for (boolean screenLock : new boolean[] {false, true}) {
            AtomicReference<byte[]> original = new AtomicReference<>();
            OutputOwnerFixture fixture = new OutputOwnerFixture(event -> {
                if ("data".equals(event.get("type"))) original.set(((byte[])event.get("bytes")).clone());
            });
            try {
                Runnable output = fixture.startAndTakeOutput();
                byte[] inFlight = fixture.inFlight();
                output.run();
                assertArrayEquals(bytes("output\r\n"), original.get());
                assertArrayEquals(bytes("output\r\n"), (byte[])fixture.events.snapshot().get("bytes"));
                if (screenLock) assertTrue(fixture.owner.stopForScreenLock(fixture.owner.id));
                else fixture.owner.stop(fixture.owner.generation);
                assertEquals("the owner's failed/closed event has not been dispatched", "running",
                        fixture.events.snapshot().get("state"));
                AtomicReference<Map<String, Object>> replay = new AtomicReference<>();
                fixture.events.subscribe(replay::set);
                assertEquals("snapshot", replay.get().get("type"));
                assertEquals("running", replay.get().get("state"));
                assertEquals(fixture.owner.generation, replay.get().get("generation"));
                assertArrayEquals(new byte[0], (byte[])replay.get().get("bytes"));
                assertTrue("cleanup has not yet cleared the retained buffer", fixture.events.bufferedBytes() > 0);
                fixture.stopAndDrain();
                assertEquals(0, fixture.events.bufferedBytes());
                assertArrayEquals(new byte[inFlight.length], inFlight);
            } finally { fixture.stopAndDrain(); }
        }
    }

    @Test public void failedAuthenticationRetirementNeverPublishesAndCleanupRetriesOnOriginalOwner() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int scenario = mode;
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                authenticated(setup, binding, false, 2);
                if (mode == 0) setup.view.failClose = true;
                else setup.session.retireAuthenticationWith(() -> {
                    if (scenario == 1) setup.session.cancel();
                    else throw new VaultException("SSH_FILE_VIEW", "retirement failed");
                });
                assertCode(mode == 1 ? "SSH_SESSION_CANCELLED" : "SSH_FILE_VIEW",
                        () -> setup.session.beginShell(80, 24));
                assertFalse(setup.session.shellOutputPublicationLive());
                int calls = binding.shellCalls;
                int nativeCalls = binding.order.size();
                String denied = mode == 1 ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_PHASE";
                assertCode(denied, () -> setup.session.step());
                assertCode(denied, () -> setup.session.shellStatus());
                assertCode(denied, () -> setup.session.writeShell(bytes("must not reach native")));
                assertEquals(calls, binding.shellCalls);
                assertEquals(nativeCalls, binding.order.size());
                setup.view.failClose = false;
                setup.session.close();
                assertTrue(setup.session.closed());
                assertEquals(1, setup.roleRelease.calls);
            } finally { setup.view.failClose = false; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void socketOwnershipDistinguishesNativeTransferAndRetainsCleanupRetry() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            ManagedShellNative binding = new ManagedShellNative();
            Setup setup = new Setup(true, binding);
            int[] callerCloses = {0};
            SshSocketOwnership socket = new SshSocketOwnership(41, descriptor -> {
                assertEquals(41, descriptor);
                callerCloses[0]++;
                throw new IOException("uncertain caller close");
            });
            try {
                select(setup, false);
                if (mode == 0) binding.failOpen = true;
                else {
                    binding.openHook = () -> setup.view.active = false;
                    binding.failClose = mode == 2;
                }
                final int scenario = mode;
                assertCode(scenario == 0 ? "SSH_SESSION_NATIVE" : "SSH_FILE_VIEW", () ->
                    SshNativeSession.openManaged(setup.callback, setup.view, socket,
                        "candidate_1", 1024, binding));
                assertEquals(mode != 0, socket.adopted());
                if (mode == 0) {
                    try { socket.close(); fail("close error must propagate"); }
                    catch (IOException expected) { assertEquals("uncertain caller close", expected.getMessage()); }
                    socket.close();
                    assertEquals(1, callerCloses[0]);
                    assertFalse(binding.order.contains("close"));
                } else {
                    if (mode == 2) {
                        assertCode("SSH_SESSION_CLEANUP", () -> socket.close());
                        binding.failClose = false;
                    }
                    socket.close();
                    socket.close();
                    assertEquals(0, callerCloses[0]);
                    assertTrue(binding.order.contains("destroy"));
                }
            } finally {
                binding.failClose = false;
                try { socket.close(); } catch (IOException ignored) { }
                setup.closeRegistry();
            }
        }
    }

    @Test public void managedSocketUsesSelectedTrustPathAndPreservesLegacyDefault() throws Exception {
        for (boolean privateKey : new boolean[] {false, true}) {
            ManagedShellNative binding = new ManagedShellNative();
            binding.expectedKnownHostsPath = privateKey ? ".ssh/known_hosts" : "ssh/known_hosts";
            Setup setup = new Setup(true, binding);
            int[] callerCloses = {0};
            SshSocketOwnership socket = new SshSocketOwnership(41, descriptor -> callerCloses[0]++);
            try {
                select(setup, privateKey);
                setup.session = privateKey
                        ? SshNativeSession.openManaged(setup.callback, setup.view, socket,
                                ".ssh/known_hosts", "candidate_1", 1024, binding)
                        : SshNativeSession.openManaged(setup.callback, setup.view, socket,
                                "candidate_1", 1024, binding);
                assertEquals(binding.expectedKnownHostsPath, binding.openedKnownHostsPath);
                assertEquals(1, binding.managedOpenCalls);
                assertEquals(0, binding.openCalls);
                assertTrue(socket.adopted());
                assertArrayEquals(binding.digest, setup.session.authenticationSelectionReceipt());
                setup.session.close(); socket.close();
                assertEquals(0, callerCloses[0]);
            } finally {
                if (setup.session != null) setup.session.close();
                socket.close(); setup.closeRegistry();
            }
        }
    }

    @Test public void invalidSelectedTrustPathDoesNotClaimSessionOrTransferSocket() throws Exception {
        ManagedShellNative binding = new ManagedShellNative();
        Setup setup = new Setup(true, binding);
        int[] callerCloses = {0};
        SshSocketOwnership socket = new SshSocketOwnership(41, descriptor -> callerCloses[0]++);
        try {
            select(setup, true);
            for (String path : new String[] {null, "", "/.ssh/known_hosts", "../known_hosts", "bad\0path"}) {
                assertCode("SSH_SESSION_REQUIRED", () -> SshNativeSession.openManaged(setup.callback,
                        setup.view, socket, path, "candidate_1", 1024, binding));
                assertEquals(0, binding.managedOpenCalls);
                assertFalse(socket.adopted());
                assertTrue(setup.view.active());
            }
            binding.expectedKnownHostsPath = ".ssh/known_hosts";
            setup.session = SshNativeSession.openManaged(setup.callback, setup.view, socket,
                    ".ssh/known_hosts", "candidate_1", 1024, binding);
            assertEquals(1, binding.managedOpenCalls);
            assertTrue(socket.adopted());
        } finally {
            if (setup.session != null) setup.session.close();
            socket.close(); setup.closeRegistry();
        }
        assertEquals(0, callerCloses[0]);
    }

    @Test public void shellInvalidNativeIoResultsTerminalizeWithoutPublishing() throws Exception {
        for (int mode = 0; mode < 7; mode++) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                shellRunning(setup, binding);
                if (mode < 2) {
                    binding.accepted = mode == 0 ? -1 : 2;
                    assertCode("SSH_SESSION_SHELL_WRITE", () -> setup.session.writeShell(bytes("x")));
                    assertTrue(allZero(binding.writeReference));
                } else if (mode < 4) {
                    binding.output = mode == 2 ? null : new byte[33];
                    assertCode("SSH_SESSION_SHELL_READ", () -> setup.session.readShell(32));
                    if (binding.readReference != null) assertTrue(allZero(binding.readReference));
                } else if (mode < 6) {
                    binding.resizeAccepted = mode == 4 ? -1 : 2;
                    assertCode("SSH_SESSION_SHELL_RESIZE", () -> setup.session.resizeShell(80, 24));
                } else {
                    binding.eofAccepted = 2;
                    assertCode("SSH_SESSION_SHELL_EOF", () -> setup.session.sendShellEof());
                }
            } finally { setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellStepStopsAtDrainedTerminalAndRetainsCleanupRetry() throws Exception {
        for (int terminal : new int[] {5, 6}) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                authenticated(setup, binding, false, 2);
                setup.session.beginShell(80, 24);
                binding.status[0] = 2;
                setup.session.step();
                binding.stepHook = () -> binding.status[0] = terminal;
                setup.session.step();
                binding.stepHook = null;
                int calls = binding.order.size();
                assertCode("SSH_SESSION_PHASE", () -> setup.session.step());
                assertEquals(calls, binding.order.size());
                assertEquals(terminal, setup.session.shellStatus().phase.tag);
                binding.failClose = true;
                assertCode("SSH_SESSION_CLEANUP", () -> setup.session.close());
                assertFalse(setup.view.active);
                binding.failClose = false;
                binding.failDestroy = true;
                assertCode("SSH_SESSION_CLEANUP", () -> setup.session.close());
                assertFalse(setup.view.active);
                assertFalse(setup.session.closed());
                binding.failDestroy = false;
                setup.session.close();
                assertTrue(setup.session.closed());
            } finally { binding.stepHook = null; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellReadPostGuardWipesTemporaryWhileRetiredViewDoesNotRevokeTransport() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            try {
                shellRunning(setup, binding);
                final int lifecycle = mode;
                binding.protocolHook = () -> {
                    if (binding.reads != 0) {
                        try {
                            if (lifecycle == 0) setup.session.cancel();
                            else setup.view.active = false;
                        } catch (VaultException failure) { throw new AssertionError(failure); }
                    }
                };
                if (mode == 0) assertCode("SSH_SESSION_CANCELLED", () -> setup.session.readShell(32));
                else assertArrayEquals(binding.output, setup.session.readShell(32));
                assertTrue(allZero(binding.readReference));
            } finally { binding.protocolHook = null; setup.session.close(); setup.closeRegistry(); }
        }
    }

    @Test public void shellEntriesEnforceOwnerAndThreadWideReentryAcrossSessions() throws Exception {
        for (int outer = 0; outer < 6; outer++) {
            ShellNative binding = new ShellNative();
            Setup setup = new Setup(true, binding);
            Setup other = new Setup(true, new ShellNative());
            try {
                if (outer == 0) authenticated(setup, binding, false, 2);
                else shellRunning(setup, binding);
                other.session = open(other);
                CheckedAction action = shellOperation(setup.session, outer);
                int calls = binding.shellCalls;
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread thread = new Thread(() -> {
                    try { action.run(); } catch (Throwable failure) { error.set(failure); }
                });
                thread.start(); thread.join();
                assertTrue(error.get() instanceof VaultException);
                assertEquals("SSH_SESSION_THREAD", ((VaultException)error.get()).code);
                assertEquals(calls, binding.shellCalls);
                binding.shellHook = () -> {
                    for (Setup target : new Setup[] {setup, other}) {
                        for (int operation = 0; operation < 6; operation++) {
                            try { assertCode("SSH_SESSION_REENTRANT", shellOperation(target.session, operation)); }
                            catch (Exception failure) { throw new AssertionError(failure); }
                        }
                        for (CheckedAction entry : entries(target)) {
                            try { assertCode("SSH_SESSION_REENTRANT", entry); }
                            catch (Exception failure) { throw new AssertionError(failure); }
                        }
                    }
                };
                action.run();
                binding.shellHook = null;
                other.session.step();
            } finally {
                binding.shellHook = null;
                setup.session.close(); other.session.close(); setup.closeRegistry(); other.closeRegistry();
            }
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
            () -> setup.session.beginShell(80, 24), () -> setup.session.shellStatus(),
            () -> setup.session.writeShell(bytes("x")), () -> setup.session.readShell(32),
            () -> setup.session.resizeShell(80, 24), () -> setup.session.sendShellEof(),
            () -> open(setup),
            () -> SshNativeSession.open(setup.callback, (SshPublicFileLease) null, 41,
                    HOST_KEY, FINGERPRINT, "candidate_1", 1024),
            () -> openManaged(setup),
            () -> SshNativeSession.openManaged(setup.callback, (SshPublicFileLease) null, 41,
                    "candidate_1", 1024)
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

    private static SshNativeSession openManaged(Setup setup) throws Exception {
        return SshNativeSession.openManaged(setup.callback, setup.view, 41,
                "candidate_1", 1024, setup.sessionNative);
    }

    private static byte[] record(long token, int uid, int pid, int hop) throws IOException {
        return record(token, uid, pid, hop, false);
    }

    private static byte[] record(long token, int uid, int pid, int hop, boolean jump) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x43535242);
        output.writeByte(1);
        output.writeByte(jump ? 2 : 1);
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

    private static byte[] chainedRecord(long token, int uid, int pid, int hop, boolean jump) throws IOException {
        byte[] legacy = record(token, uid, pid, hop, jump);
        legacy[4] = 2;
        Arrays.fill(legacy, 40, 72, (byte)(hop + 4));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(legacy);
        text(output, "chain:admission");
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
