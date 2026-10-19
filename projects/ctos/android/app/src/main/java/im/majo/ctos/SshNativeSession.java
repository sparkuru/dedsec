package im.majo.ctos;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * Same-process SSH session-owner seam.
 *
 * <p>The object is deliberately owned by the thread that opened the callback
 * adapter.  Only that thread may enter native open/step/verify/close/destroy;
 * cancellation is the sole cross-thread operation.  A successful native open
 * consumes the bridge-owned socket according to the native contract.  A
 * failed open consumes neither the socket, callback, nor public-view lease.</p>
 *
 * <p>The production JNI binding accepts only bounded key bytes and opaque
 * native tokens.  The native side owns the parsed host key and keeps a
 * revocable public-view borrow until native close; the injected binding below
 * remains the executable Java ownership contract for host JVM tests.</p>
 */
final class SshNativeSession implements AutoCloseable {
    static final int MAX_STEP_BUDGET = 64 * 1024;
    static final int HOST_FINGERPRINT_BYTES = 32;
    static final int MAX_HOST_KEY_BLOB_BYTES = 1024 * 1024;
    private static final String KNOWN_HOSTS_PATH = "ssh/known_hosts";
    private static final ThreadLocal<Boolean> NATIVE_ENTRY = new ThreadLocal<>();

    enum PasswordAuthResult {
        NOT_STARTED(0), PENDING(1), SUCCESS(2), REJECTED(3), PARTIAL(4), CHANGE_REQUIRED(5);

        final int tag;
        PasswordAuthResult(int tag) { this.tag = tag; }

        private static PasswordAuthResult fromNative(int tag) throws IOException {
            for (PasswordAuthResult result : values()) if (result.tag == tag) return result;
            throw new IOException("invalid SSH authentication result");
        }
    }

    enum PrivateKeyAuthResult {
        NOT_STARTED(0), PENDING(1), SUCCESS(2), REJECTED(3), PARTIAL(4);

        final int tag;
        PrivateKeyAuthResult(int tag) { this.tag = tag; }

        private static PrivateKeyAuthResult fromNative(int tag) throws IOException {
            for (PrivateKeyAuthResult result : values()) if (result.tag == tag) return result;
            throw new IOException("invalid SSH authentication result");
        }
    }

    private enum Phase { KEX_ONLY, SERVICE_PENDING, SERVICE_READY, PASSWORD_PENDING,
        PRIVATE_KEY_PENDING, FINISHED }

    /** A public-only view token with deferred cleanup support. */
    interface ViewBinding {
        long token() throws VaultException;
        boolean active();
        void close() throws VaultException;
    }

    /** JNI-independent contract used by JVM tests and the production bridge. */
    interface NativeBinding {
        long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob,
                byte[] expectedFingerprint, String knownHostsPath, String host,
                String candidateId) throws IOException;
        default long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob, byte[] expectedFingerprint,
                String knownHostsPath, String host, String candidateId, byte[] selectionWire)
                throws IOException {
            if (selectionWire != null) throw new IOException("SSH selection transport is unavailable");
            return open(role, ownerUid, callback, view, socketFd, deadline, stepBudget,
                    hostKeyBlob, expectedFingerprint, knownHostsPath, host, candidateId);
        }
        default byte[] authenticationSelectionReceipt(long handle) throws IOException {
            throw new IOException("SSH selection receipt is unavailable");
        }
        default void beginUserauthService(long handle) throws IOException {
            throw new IOException("SSH userauth service is unavailable");
        }
        default boolean userauthServiceReady(long handle) throws IOException {
            throw new IOException("SSH userauth service is unavailable");
        }
        default void beginPasswordAuth(long handle) throws IOException {
            throw new IOException("SSH password authentication is unavailable");
        }
        default int passwordAuthResult(long handle) throws IOException {
            throw new IOException("SSH password authentication is unavailable");
        }
        default void beginPrivateKeyAuth(long handle) throws IOException {
            throw new IOException("SSH private-key authentication is unavailable");
        }
        default int privateKeyAuthResult(long handle) throws IOException {
            throw new IOException("SSH private-key authentication is unavailable");
        }
        StepResult step(long handle, boolean cancelled) throws IOException;
        void verifyHostKey(long handle, byte[] hostKeyBlob) throws IOException;
        void close(long handle) throws IOException;
        void destroy(long handle) throws IOException;
    }

    /** Bounded result; no application byte pipe crosses this API. */
    static final class StepResult {
        final int bytesRead;
        final int bytesWritten;
        final int inputAppended;
        final int packetType;
        final boolean readWouldBlock;
        final boolean writeWouldBlock;
        final boolean partialWrite;
        final boolean complete;

        StepResult(int bytesRead, int bytesWritten, int inputAppended, int packetType,
                boolean readWouldBlock, boolean writeWouldBlock, boolean partialWrite,
                boolean complete) {
            if (bytesRead < 0 || bytesRead > MAX_STEP_BUDGET
                    || bytesWritten < 0 || bytesWritten > MAX_STEP_BUDGET
                    || inputAppended < 0 || inputAppended > MAX_STEP_BUDGET
                    || inputAppended > bytesRead || packetType < 0 || packetType > 0xff) {
                throw new IllegalArgumentException("invalid SSH step result");
            }
            this.bytesRead = bytesRead;
            this.bytesWritten = bytesWritten;
            this.inputAppended = inputAppended;
            this.packetType = packetType;
            this.readWouldBlock = readWouldBlock;
            this.writeWouldBlock = writeWouldBlock;
            this.partialWrite = partialWrite;
            this.complete = complete;
        }

        private static StepResult fromNative(int[] values) throws IOException {
            if (values == null || values.length != 8) throw new IOException("invalid SSH step result");
            try {
                for (int index = 4; index < 8; index++) {
                    if (values[index] != 0 && values[index] != 1) {
                        throw new IOException("invalid SSH step flag");
                    }
                }
                return new StepResult(values[0], values[1], values[2], values[3],
                        values[4] != 0, values[5] != 0, values[6] != 0, values[7] != 0);
            } catch (RuntimeException failure) {
                throw new IOException("invalid SSH step result");
            }
        }

        private boolean validFor(int budget) {
            return budget > 0 && budget <= MAX_STEP_BUDGET
                    && bytesRead <= budget && bytesWritten <= budget
                    && inputAppended <= budget && inputAppended <= bytesRead;
        }
    }

    private static final class ProductionNativeBinding implements NativeBinding {
        @Override public long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob, byte[] expectedFingerprint,
                String knownHostsPath, String host, String candidateId) throws IOException {
            return open(role, ownerUid, callback, view, socketFd, deadline, stepBudget,
                    hostKeyBlob, expectedFingerprint, knownHostsPath, host, candidateId, null);
        }

        @Override public long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob, byte[] expectedFingerprint,
                String knownHostsPath, String host, String candidateId, byte[] selectionWire)
                throws IOException {
            return Native.open(role, ownerUid, callback, view, socketFd, deadline, stepBudget,
                    hostKeyBlob, expectedFingerprint, knownHostsPath, host, candidateId, selectionWire);
        }

        @Override public byte[] authenticationSelectionReceipt(long handle) throws IOException {
            return Native.authenticationSelectionReceipt(handle);
        }

        @Override public void beginUserauthService(long handle) throws IOException {
            Native.beginUserauthService(handle);
        }

        @Override public boolean userauthServiceReady(long handle) throws IOException {
            return Native.userauthServiceReady(handle);
        }

        @Override public void beginPasswordAuth(long handle) throws IOException {
            Native.beginPasswordAuth(handle);
        }

        @Override public int passwordAuthResult(long handle) throws IOException {
            return Native.passwordAuthResult(handle);
        }
        @Override public void beginPrivateKeyAuth(long handle) throws IOException {
            Native.beginPrivateKeyAuth(handle);
        }
        @Override public int privateKeyAuthResult(long handle) throws IOException {
            return Native.privateKeyAuthResult(handle);
        }

        @Override public StepResult step(long handle, boolean cancelled) throws IOException {
            return StepResult.fromNative(Native.step(handle, cancelled));
        }

        @Override public void verifyHostKey(long handle, byte[] hostKeyBlob) throws IOException {
            Native.verifyHostKey(handle, hostKeyBlob);
        }

        @Override public void close(long handle) throws IOException { Native.close(handle); }

        @Override public void destroy(long handle) throws IOException { Native.destroy(handle); }
    }

    /** Bridges the production lease without making the test binding a role view. */
    private static final class ProductionViewBinding implements ViewBinding {
        private final SshPublicFileLease lease;

        ProductionViewBinding(SshPublicFileLease lease) { this.lease = lease; }

        @Override public long token() throws VaultException { return lease.nativeToken(); }

        @Override public boolean active() { return lease.active(); }

        @Override public void close() throws VaultException { lease.close(); }
    }

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }

        static native long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob,
                byte[] expectedFingerprint, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException;
        static native byte[] authenticationSelectionReceipt(long handle) throws IOException;
        static native void beginUserauthService(long handle) throws IOException;
        static native boolean userauthServiceReady(long handle) throws IOException;
        static native void beginPasswordAuth(long handle) throws IOException;
        static native int passwordAuthResult(long handle) throws IOException;
        static native void beginPrivateKeyAuth(long handle) throws IOException;
        static native int privateKeyAuthResult(long handle) throws IOException;
        static native int[] step(long handle, boolean cancelled) throws IOException;
        static native void verifyHostKey(long handle, byte[] hostKeyBlob) throws IOException;
        static native void close(long handle) throws IOException;
        static native void destroy(long handle) throws IOException;
    }

    private final Object stateLock = new Object();
    private final SshNativeCallbackAdapter callback;
    private final ViewBinding view;
    private final NativeBinding nativeBinding;
    private final Thread ownerThread;
    private final SshNativeCallbackAdapter.NativeSessionClaim callbackClaim;
    private final long nativeHandle;
    private final int stepBudget;
    private final byte[] expectedSelectionReceipt;
    private boolean nativeClosed;
    private boolean callbackClosed;
    private boolean roleReleased;
    private boolean viewReleased;
    private boolean destroyed;
    private boolean complete;
    private Phase phase = Phase.KEX_ONLY;
    private PasswordAuthResult authenticationResult = PasswordAuthResult.NOT_STARTED;
    private PrivateKeyAuthResult privateKeyResult = PrivateKeyAuthResult.NOT_STARTED;
    private boolean privateKeyAuthentication;
    private volatile boolean cancellationRequested;
    private String terminalFailure;

    private SshNativeSession(SshNativeCallbackAdapter callback, ViewBinding view,
            NativeBinding nativeBinding,
            SshNativeCallbackAdapter.NativeSessionClaim callbackClaim, long nativeHandle,
            int stepBudget, byte[] expectedSelectionReceipt) {
        this.expectedSelectionReceipt = expectedSelectionReceipt;
        this.callback = callback;
        this.view = view;
        this.nativeBinding = nativeBinding;
        this.ownerThread = Thread.currentThread();
        this.callbackClaim = callbackClaim;
        this.nativeHandle = nativeHandle;
        this.stepBudget = stepBudget;
    }

    /** Production bridge entry point; the raw FD is owned by the bridge caller. */
    static SshNativeSession open(SshNativeCallbackAdapter callback, SshPublicFileLease view,
            int socketFd, byte[] hostKeyBlob, byte[] expectedFingerprint,
            String candidateId, int stepBudget) throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            if (view == null || !callback.ownsPublicView(view)) throw invalid();
            return openInternal(callback, new ProductionViewBinding(view), socketFd, hostKeyBlob,
                    expectedFingerprint, candidateId, stepBudget, new ProductionNativeBinding());
        } finally { NATIVE_ENTRY.remove(); }
    }

    /**
     * Package-private test seam. It preserves the same production state
     * machine while keeping host JVM tests independent of Android/JNI.
     */
    static SshNativeSession open(SshNativeCallbackAdapter callback, ViewBinding view,
            int socketFd, byte[] hostKeyBlob, byte[] expectedFingerprint,
            String candidateId, int stepBudget, NativeBinding nativeBinding) throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            return openInternal(callback, view, socketFd, hostKeyBlob, expectedFingerprint,
                    candidateId, stepBudget, nativeBinding);
        } finally { NATIVE_ENTRY.remove(); }
    }

    private static SshNativeSession openInternal(SshNativeCallbackAdapter callback, ViewBinding view,
            int socketFd, byte[] hostKeyBlob, byte[] expectedFingerprint,
            String candidateId, int stepBudget, NativeBinding nativeBinding) throws VaultException {
        if (callback == null || view == null || nativeBinding == null
                || Thread.currentThread() != callback.eventLoopThread()
                || socketFd < 0 || !view.active()
                || stepBudget <= 0
                || stepBudget > MAX_STEP_BUDGET || !validHostKey(hostKeyBlob)
                || !validFingerprint(expectedFingerprint) || !identifier(candidateId)) {
            throw invalid();
        }
        callback.requireNativeSessionLive();
        byte[] wire = null;
        byte[] suppliedWire = null;
        byte[] expectedReceipt = null;
        byte[] key = null;
        byte[] fingerprint = null;
        SshNativeCallbackAdapter.NativeSessionClaim claim = null;
        boolean opened = false;
        try {
            // All metadata work precedes the claim and native ownership transfer.
            wire = callback.authenticationSelectionWire();
            if (wire != null) {
                if (wire.length == 0 || wire.length > SshAuthenticationSelection.MAX_WIRE_BYTES) {
                    throw SshAuthenticationSelection.invalid();
                }
                suppliedWire = wire.clone();
                expectedReceipt = VaultCodec.digest(wire);
            }
            key = hostKeyBlob.clone();
            fingerprint = expectedFingerprint.clone();
            claim = callback.beginNativeSessionOpen();
            long viewToken = view.token();
            long callbackToken = callback.nativeCallbackToken();
            if (viewToken <= 0 || callbackToken <= 0) throw unavailable();
            final long handle;
            try {
                handle = nativeBinding.open(callback.roleToken(), callback.ownerUid(), callbackToken,
                        viewToken, socketFd, callback.deadline(), stepBudget, key, fingerprint,
                        KNOWN_HOSTS_PATH, callback.host(), candidateId, suppliedWire);
            } catch (IOException | LinkageError | RuntimeException failure) { throw unavailable(); }
            if (handle <= 0) throw unavailable();
            callback.finishNativeSessionOpen(claim, true);
            opened = true;
            return new SshNativeSession(callback, view, nativeBinding, claim, handle,
                    stepBudget, expectedReceipt);
        } finally {
            if (!opened && claim != null) callback.finishNativeSessionOpen(claim, false);
            VaultCodec.wipe(wire);
            VaultCodec.wipe(suppliedWire);
            VaultCodec.wipe(key);
            VaultCodec.wipe(fingerprint);
            if (!opened) VaultCodec.wipe(expectedReceipt);
        }
    }

    /** Request cancellation from any thread; all native I/O remains owner-thread only. */
    void cancel() throws VaultException {
        synchronized (stateLock) {
            if (destroyed || nativeClosed) return;
            cancellationRequested = true;
        }
        callback.cancel();
    }

    StepResult step() throws VaultException {
        requireOwnerThread();
        enterNative();
        try { return stepInternal(); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private StepResult stepInternal() throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
            if (terminalFailure != null) throw failure(terminalFailure);
            if (phase == Phase.SERVICE_READY || phase == Phase.FINISHED) throw phaseFailure();
            if (complete && phase == Phase.KEX_ONLY) throw failure("SSH_SESSION_COMPLETE");
        }
        boolean explicit = phase != Phase.KEX_ONLY;
        if (explicit) requireProtocolLive();
        if (cancellationRequested) {
            terminalFailure = "SSH_SESSION_CANCELLED";
        } else {
            try {
                callback.requireNativeSessionLive();
            } catch (VaultException callbackFailure) {
                String code = sessionFailureCode(callbackFailure.code);
                synchronized (stateLock) { terminalFailure = code; }
                if (code.equals(callbackFailure.code)) throw callbackFailure;
                throw failure(code);
            }
        }
        try {
            StepResult result = nativeBinding.step(nativeHandle, cancellationRequested);
            if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
            if (result == null) throw new IOException("missing SSH step result");
            if (!result.validFor(stepBudget)) throw new IOException("invalid SSH step result");
            if (result.complete) synchronized (stateLock) { complete = true; }
            if (explicit) {
                requireProtocolLive();
                if (phase == Phase.SERVICE_PENDING) queryServiceReady();
                else if (phase == Phase.PASSWORD_PENDING) queryPasswordResult();
                else if (phase == Phase.PRIVATE_KEY_PENDING) queryPrivateKeyResult();
            }
            return result;
        } catch (VaultException failure) {
            throw failure;
        } catch (IOException | LinkageError | RuntimeException failure) {
            if (explicit) throw protocolNativeFailure("SSH_SESSION_STEP");
            synchronized (stateLock) {
                terminalFailure = cancellationRequested
                        ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_STEP";
            }
            throw failure(terminalFailure);
        } catch (Error failure) {
            if (explicit) terminalFailure = "SSH_SESSION_STEP";
            throw failure;
        }
    }

    void beginUserauthService() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (!complete || phase != Phase.KEX_ONLY) throw phaseFailure();
            requireProtocolLive();
            try {
                nativeBinding.beginUserauthService(nativeHandle);
                requireProtocolLive();
                phase = Phase.SERVICE_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_SERVICE");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_SESSION_SERVICE";
                throw nativeFailure;
            }
        } finally { NATIVE_ENTRY.remove(); }
    }

    boolean userauthServiceReady() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (phase == Phase.KEX_ONLY) throw phaseFailure();
            requireProtocolLive();
            return queryServiceReady();
        } finally { NATIVE_ENTRY.remove(); }
    }

    void beginPasswordAuth() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (phase != Phase.SERVICE_READY) throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            try {
                nativeBinding.beginPasswordAuth(nativeHandle);
                requireProtocolLive();
                phase = Phase.PASSWORD_PENDING;
                authenticationResult = PasswordAuthResult.PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_AUTH");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_SESSION_AUTH";
                throw nativeFailure;
            }
        } finally { NATIVE_ENTRY.remove(); }
    }

    PasswordAuthResult passwordAuthResult() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (privateKeyAuthentication) throw phaseFailure();
            if (phase != Phase.SERVICE_READY && phase != Phase.PASSWORD_PENDING
                    && phase != Phase.FINISHED) throw phaseFailure();
            requireProtocolLive();
            return queryPasswordResult();
        } finally { NATIVE_ENTRY.remove(); }
    }

    /** These raw milestone queries share the caller's entry guard, including step(). */
    private boolean queryServiceReady() throws VaultException {
        try {
            boolean ready = nativeBinding.userauthServiceReady(nativeHandle);
            requireProtocolLive();
            if (!ready && phase != Phase.SERVICE_PENDING) {
                throw new IOException("SSH service readiness regressed");
            }
            if (ready && phase == Phase.SERVICE_PENDING) phase = Phase.SERVICE_READY;
            return ready;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_SERVICE");
        }
    }

    private PasswordAuthResult queryPasswordResult() throws VaultException {
        try {
            PasswordAuthResult result = PasswordAuthResult.fromNative(
                    nativeBinding.passwordAuthResult(nativeHandle));
            requireProtocolLive();
            if (phase == Phase.SERVICE_READY) {
                if (result != PasswordAuthResult.NOT_STARTED) {
                    throw new IOException("unexpected SSH authentication result");
                }
            } else if (phase == Phase.PASSWORD_PENDING) {
                if (result == PasswordAuthResult.NOT_STARTED) {
                    throw new IOException("SSH authentication result regressed");
                }
                authenticationResult = result;
                if (result != PasswordAuthResult.PENDING) phase = Phase.FINISHED;
            } else if (phase != Phase.FINISHED || result != authenticationResult) {
                throw new IOException("SSH authentication result regressed");
            }
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_AUTH_RESULT");
        }
    }

    void beginPrivateKeyAuth() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (phase != Phase.SERVICE_READY) throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            try {
                nativeBinding.beginPrivateKeyAuth(nativeHandle);
                requireProtocolLive();
                phase = Phase.PRIVATE_KEY_PENDING;
                privateKeyAuthentication = true;
                privateKeyResult = PrivateKeyAuthResult.PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_AUTH");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_SESSION_AUTH";
                throw nativeFailure;
            }
        } finally { NATIVE_ENTRY.remove(); }
    }

    PrivateKeyAuthResult privateKeyAuthResult() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (phase != Phase.SERVICE_READY && phase != Phase.PRIVATE_KEY_PENDING
                    && phase != Phase.FINISHED) throw phaseFailure();
            if (phase == Phase.FINISHED && !privateKeyAuthentication) throw phaseFailure();
            requireProtocolLive();
            return queryPrivateKeyResult();
        } finally { NATIVE_ENTRY.remove(); }
    }

    private PrivateKeyAuthResult queryPrivateKeyResult() throws VaultException {
        try {
            PrivateKeyAuthResult result = PrivateKeyAuthResult.fromNative(
                    nativeBinding.privateKeyAuthResult(nativeHandle));
            requireProtocolLive();
            if (phase == Phase.SERVICE_READY) {
                if (result != PrivateKeyAuthResult.NOT_STARTED)
                    throw new IOException("unexpected SSH authentication result");
            } else if (phase == Phase.PRIVATE_KEY_PENDING) {
                if (result == PrivateKeyAuthResult.NOT_STARTED)
                    throw new IOException("SSH authentication result regressed");
                privateKeyResult = result;
                if (result != PrivateKeyAuthResult.PENDING) phase = Phase.FINISHED;
            } else if (phase != Phase.FINISHED || result != privateKeyResult) {
                throw new IOException("SSH authentication result regressed");
            }
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_AUTH_RESULT");
        }
    }

    private void requireProtocolState() throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
            if (terminalFailure != null) throw failure(terminalFailure);
        }
    }

    private void requireProtocolLive() throws VaultException {
        try { requireSelectionReadLive(); }
        catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
    }

    private VaultException protocolNativeFailure(String code) throws VaultException {
        // A callback may cancel, expire or revoke the view while native reports failure.
        requireProtocolLive();
        synchronized (stateLock) { terminalFailure = code; }
        return failure(code);
    }

    private static VaultException phaseFailure() { return failure("SSH_SESSION_PHASE"); }

    /** Read-only metadata fingerprint; neither KEX completion nor this receipt authorizes a secret. */
    byte[] authenticationSelectionReceipt() throws VaultException {
        requireOwnerThread();
        enterNative();
        try { return authenticationSelectionReceiptInternal(); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private byte[] authenticationSelectionReceiptInternal() throws VaultException {
        try { requireSelectionReadLive(); }
        catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
        if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
        byte[] received = null;
        try {
            received = nativeBinding.authenticationSelectionReceipt(nativeHandle);
            requireSelectionReadLive();
            if (received == null || received.length != 32
                    || !MessageDigest.isEqual(received, expectedSelectionReceipt)) {
                throw new IOException("SSH selection receipt does not match");
            }
            return received.clone();
        } catch (VaultException lifecycle) {
            throw selectionLifecycleFailure(lifecycle);
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            // Prefer a cancellation/expiry observed during the native call.
            try { requireSelectionReadLive(); }
            catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
            synchronized (stateLock) { terminalFailure = "SSH_SESSION_SELECTION"; }
            throw failure("SSH_SESSION_SELECTION");
        } finally { VaultCodec.wipe(received); }
    }

    private void requireSelectionReadLive() throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
            if (terminalFailure != null) throw failure(terminalFailure);
            if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
        }
        callback.requireAuthenticationSelectionLive();
        if (!view.active()) throw failure("SSH_FILE_VIEW");
    }

    private VaultException selectionLifecycleFailure(VaultException lifecycle) {
        String code = cancellationRequested || "SSH_CALLBACK_CANCELLED".equals(lifecycle.code)
                ? "SSH_SESSION_CANCELLED" : sessionFailureCode(lifecycle.code);
        synchronized (stateLock) { terminalFailure = code; }
        return code.equals(lifecycle.code) ? lifecycle : failure(code);
    }

    void verifyHostKey(byte[] hostKeyBlob) throws VaultException {
        requireOwnerThread();
        enterNative();
        try { verifyHostKeyInternal(hostKeyBlob); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private void verifyHostKeyInternal(byte[] hostKeyBlob) throws VaultException {
        if (!validHostKey(hostKeyBlob)) throw invalid();
        synchronized (stateLock) {
            requireOpenLocked();
            if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
        }
        byte[] copy = hostKeyBlob.clone();
        try {
            callback.requireNativeSessionLive();
            if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
            nativeBinding.verifyHostKey(nativeHandle, copy);
            if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
            callback.requireNativeSessionLive();
        } catch (VaultException callbackFailure) {
            String code = cancellationRequested ? "SSH_SESSION_CANCELLED"
                    : sessionFailureCode(callbackFailure.code);
            synchronized (stateLock) { terminalFailure = code; }
            if (code.equals(callbackFailure.code)) throw callbackFailure;
            throw failure(code);
        } catch (IOException | LinkageError | RuntimeException failure) {
            String code = cancellationRequested ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_VERIFY";
            synchronized (stateLock) { terminalFailure = code; }
            throw failure(code);
        } finally {
            VaultCodec.wipe(copy);
        }
    }

    /**
     * Close/retry order: native close (callback/KEX/socket), Java role
     * release, managed view release, then native tombstone destroy. A failed
     * stage remains pending and later owner-thread calls retry that stage.
     */
    @Override public void close() throws VaultException {
        requireOwnerThread();
        enterNative();
        try { closeInternal(); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private void closeInternal() throws VaultException {
        synchronized (stateLock) {
            if (destroyed) return;
        }
        if (!nativeClosed) {
            try {
                nativeBinding.close(nativeHandle);
            } catch (IOException | LinkageError | RuntimeException failure) {
                throw new VaultException("SSH_SESSION_CLEANUP",
                        "The native SSH session close is unconfirmed");
            }
            synchronized (stateLock) { nativeClosed = true; }
            VaultCodec.wipe(expectedSelectionReceipt);
        }
        if (!callbackClosed) {
            callback.nativeClientClosed(callbackClaim);
            synchronized (stateLock) { callbackClosed = true; }
        }
        if (!roleReleased) {
            callback.releaseRoleAfterNativeClient();
            synchronized (stateLock) { roleReleased = true; }
        }
        if (!viewReleased) {
            view.close();
            synchronized (stateLock) { viewReleased = true; }
        }
        try {
            nativeBinding.destroy(nativeHandle);
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw new VaultException("SSH_SESSION_CLEANUP",
                    "The native SSH session tombstone is unconfirmed");
        }
        synchronized (stateLock) { destroyed = true; }
    }

    boolean closed() { synchronized (stateLock) { return destroyed; } }
    boolean cancellationRequested() { return cancellationRequested; }

    private void requireOwnerThread() throws VaultException {
        if (Thread.currentThread() != ownerThread) throw new VaultException("SSH_SESSION_THREAD",
                "SSH session I/O must run on its event-loop thread");
    }

    private static void requireOpenOwner(SshNativeCallbackAdapter callback) throws VaultException {
        if (callback == null) throw invalid();
        if (Thread.currentThread() != callback.eventLoopThread()) {
            throw failure("SSH_SESSION_THREAD");
        }
    }

    private static void enterNative() throws VaultException {
        if (Boolean.TRUE.equals(NATIVE_ENTRY.get())) throw failure("SSH_SESSION_REENTRANT");
        NATIVE_ENTRY.set(Boolean.TRUE);
    }

    private void requireOpenLocked() throws VaultException {
        if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
        if (complete) throw failure("SSH_SESSION_COMPLETE");
    }

    private static boolean validHostKey(byte[] value) {
        return value != null && value.length > 0 && value.length <= MAX_HOST_KEY_BLOB_BYTES;
    }

    private static boolean validFingerprint(byte[] value) {
        if (value == null || value.length != HOST_FINGERPRINT_BYTES) return false;
        byte combined = 0;
        for (byte item : value) combined |= item;
        return combined != 0;
    }

    private static boolean identifier(String value) {
        return value != null && value.length() > 0 && value.length() <= 128
                && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static VaultException invalid() {
        return new VaultException("SSH_SESSION_REQUIRED", "The SSH session binding is invalid");
    }

    private static VaultException unavailable() {
        return new VaultException("SSH_SESSION_NATIVE",
                "The native SSH session owner is unavailable");
    }

    private static VaultException failure(String code) {
        return new VaultException(code, "The SSH session is no longer usable");
    }

    private static String sessionFailureCode(String callbackCode) {
        return "SSH_ROLE_EXPIRED".equals(callbackCode) ? "SSH_SESSION_EXPIRED" : callbackCode;
    }
}
