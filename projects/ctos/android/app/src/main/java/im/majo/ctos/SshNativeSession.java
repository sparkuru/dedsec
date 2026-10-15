package im.majo.ctos;

import java.io.IOException;

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
            return Native.open(role, ownerUid, callback, view, socketFd, deadline, stepBudget,
                    hostKeyBlob, expectedFingerprint, knownHostsPath, host, candidateId);
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
                String candidateId) throws IOException;
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
    private boolean nativeClosed;
    private boolean callbackClosed;
    private boolean roleReleased;
    private boolean viewReleased;
    private boolean destroyed;
    private boolean complete;
    private volatile boolean cancellationRequested;
    private String terminalFailure;

    private SshNativeSession(SshNativeCallbackAdapter callback, ViewBinding view,
            NativeBinding nativeBinding,
            SshNativeCallbackAdapter.NativeSessionClaim callbackClaim, long nativeHandle,
            int stepBudget) {
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
        if (callback == null || view == null || !callback.ownsPublicView(view)) throw invalid();
        return open(callback, new ProductionViewBinding(view), socketFd, hostKeyBlob,
                expectedFingerprint,
                candidateId, stepBudget, new ProductionNativeBinding());
    }

    /**
     * Package-private test seam. It preserves the same production state
     * machine while keeping host JVM tests independent of Android/JNI.
     */
    static SshNativeSession open(SshNativeCallbackAdapter callback, ViewBinding view,
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
        SshNativeCallbackAdapter.NativeSessionClaim callbackClaim =
                callback.beginNativeSessionOpen();
        final long viewToken;
        long callbackToken = callback.nativeCallbackToken();
        try {
            viewToken = view.token();
        } catch (VaultException failure) {
            callback.finishNativeSessionOpen(callbackClaim, false);
            throw failure;
        }
        if (viewToken <= 0 || callbackToken <= 0) {
            callback.finishNativeSessionOpen(callbackClaim, false);
            throw unavailable();
        }
        byte[] key = hostKeyBlob.clone();
        byte[] fingerprint = expectedFingerprint.clone();
        final long handle;
        try {
            handle = nativeBinding.open(callback.roleToken(), callback.ownerUid(), callbackToken,
                    viewToken, socketFd, callback.deadline(), stepBudget, key, fingerprint,
                    KNOWN_HOSTS_PATH, callback.host(), candidateId);
        } catch (IOException | LinkageError | RuntimeException failure) {
            callback.finishNativeSessionOpen(callbackClaim, false);
            throw unavailable();
        } finally {
            VaultCodec.wipe(key);
            VaultCodec.wipe(fingerprint);
        }
        if (handle <= 0) {
            callback.finishNativeSessionOpen(callbackClaim, false);
            throw unavailable();
        }
        callback.finishNativeSessionOpen(callbackClaim, true);
        return new SshNativeSession(callback, view, nativeBinding, callbackClaim, handle,
                stepBudget);
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
        synchronized (stateLock) {
            requireOpenLocked();
            if (terminalFailure != null) throw failure(terminalFailure);
        }
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
            return result;
        } catch (VaultException failure) {
            throw failure;
        } catch (IOException | LinkageError | RuntimeException failure) {
            synchronized (stateLock) {
                terminalFailure = cancellationRequested
                        ? "SSH_SESSION_CANCELLED" : "SSH_SESSION_STEP";
            }
            throw failure(terminalFailure);
        }
    }

    void verifyHostKey(byte[] hostKeyBlob) throws VaultException {
        requireOwnerThread();
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
