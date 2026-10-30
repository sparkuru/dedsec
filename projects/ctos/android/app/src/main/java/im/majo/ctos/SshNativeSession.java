package im.majo.ctos;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * Same-process SSH session-owner seam.
 *
 * <p>The object is deliberately owned by the thread that opened the callback
 * adapter.  Only that thread may enter native open/step/verify/close/destroy;
 * cancellation is the sole cross-thread operation.  A successful native open
 * consumes the bridge-owned socket according to the native contract. A failed
 * native open leaves the descriptor caller-owned. The production ownership
 * wrapper retains native cleanup if a Java guard fails after transfer.</p>
 *
 * <p>The production JNI binding accepts only bounded key bytes and opaque
 * native tokens.  The native side owns the parsed host key and keeps a
 * revocable public-view borrow until native close; the injected binding below
 * remains the executable Java ownership contract for host JVM tests.</p>
 */
final class SshNativeSession implements AutoCloseable {
    static final int MAX_STEP_BUDGET = 64 * 1024;
    static final int MAX_SHELL_IO_BYTES = 32768;
    static final int MAX_SHELL_BUFFER_BYTES = 65536;
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

    enum ShellPhase {
        DISABLED(0), OPENING(1), PTY_PENDING(2), SHELL_PENDING(3), RUNNING(4), CLOSED(5), REJECTED(6);

        final int tag;
        ShellPhase(int tag) { this.tag = tag; }

        private static ShellPhase fromNative(int tag) throws IOException {
            for (ShellPhase phase : values()) if (phase.tag == tag) return phase;
            throw new IOException("invalid SSH shell phase");
        }

        boolean terminal() { return this == CLOSED || this == REJECTED; }
    }

    /** Immutable, validated snapshot; an unknown exit status is never reported as success. */
    static final class ShellStatus {
        final ShellPhase phase;
        final boolean remoteEof;
        final boolean localEof;
        final boolean exitStatusKnown;
        final long exitStatus;
        final boolean exitSignal;
        final int bufferedBytes;

        private ShellStatus(int[] values) throws IOException {
            if (values == null || values.length != 7) throw new IOException("invalid SSH shell status");
            phase = ShellPhase.fromNative(values[0]);
            for (int index : new int[] {1, 2, 3, 5}) {
                if (values[index] != 0 && values[index] != 1)
                    throw new IOException("invalid SSH shell flag");
            }
            if (values[6] < 0 || values[6] > MAX_SHELL_BUFFER_BYTES
                    || (values[3] == 0 && values[4] != 0)
                    || (values[3] != 0 && values[5] != 0))
                throw new IOException("invalid SSH shell status");
            if (phase == ShellPhase.DISABLED) {
                for (int index = 1; index < 7; index++)
                    if (values[index] != 0) throw new IOException("invalid disabled SSH shell");
            }
            remoteEof = values[1] != 0;
            localEof = values[2] != 0;
            exitStatusKnown = values[3] != 0;
            exitStatus = Integer.toUnsignedLong(values[4]);
            exitSignal = values[5] != 0;
            bufferedBytes = values[6];
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
        default long openManaged(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException {
            throw new IOException("SSH managed host-key discovery is unavailable");
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
        default void beginShell(long handle, int columns, int rows) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        default int[] shellStatus(long handle) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        default int writeShell(long handle, byte[] bytes) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        default byte[] readShell(long handle, int maximum) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        default int resizeShell(long handle, int columns, int rows) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        default int sendShellEof(long handle) throws IOException {
            throw new IOException("SSH shell is unavailable");
        }
        StepResult step(long handle, boolean cancelled) throws IOException;
        void verifyHostKey(long handle, byte[] hostKeyBlob) throws IOException;
        void close(long handle) throws IOException;
        void destroy(long handle) throws IOException;
    }

    /** Bounded transport result; application data uses the separate shell API. */
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
        @Override public long openManaged(long role, int ownerUid, long callback, long view,
                int socketFd, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException {
            return Native.openManaged(role, ownerUid, callback, view, socketFd, deadline,
                    stepBudget, knownHostsPath, host, candidateId, selectionWire);
        }

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

        @Override public void beginShell(long handle, int columns, int rows) throws IOException {
            Native.beginShell(handle, columns, rows);
        }
        @Override public int[] shellStatus(long handle) throws IOException { return Native.shellStatus(handle); }
        @Override public int writeShell(long handle, byte[] bytes) throws IOException {
            return Native.writeShell(handle, bytes);
        }
        @Override public byte[] readShell(long handle, int maximum) throws IOException {
            return Native.readShell(handle, maximum);
        }
        @Override public int resizeShell(long handle, int columns, int rows) throws IOException {
            return Native.resizeShell(handle, columns, rows);
        }
        @Override public int sendShellEof(long handle) throws IOException { return Native.sendShellEof(handle); }

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

        static native void prepareTcpDescriptor(int descriptor) throws IOException;

        static native long openManaged(long role, int ownerUid, long callback, long view,
                int socketFd, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException;
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
        static native void beginShell(long handle, int columns, int rows) throws IOException;
        static native int[] shellStatus(long handle) throws IOException;
        static native int writeShell(long handle, byte[] bytes) throws IOException;
        static native byte[] readShell(long handle, int maximum) throws IOException;
        static native int resizeShell(long handle, int columns, int rows) throws IOException;
        static native int sendShellEof(long handle) throws IOException;
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
    private long nativeHandle;
    private final int stepBudget;
    private final byte[] expectedSelectionReceipt;
    private final boolean managedHostKey;
    private boolean nativeClosed;
    private boolean callbackClosed;
    private boolean roleReleased;
    private boolean viewReleased;
    private boolean destroyed;
    private boolean complete;
    private Phase phase = Phase.KEX_ONLY;
    private ShellPhase shellPhase = ShellPhase.DISABLED;
    private ShellStatus lastShellStatus;
    private boolean shellEofAccepted;
    private volatile boolean established;
    private volatile boolean authenticationRetired;
    private AppSecrets.EffectGuard authenticationRetirement;
    private PasswordAuthResult authenticationResult = PasswordAuthResult.NOT_STARTED;
    private PrivateKeyAuthResult privateKeyResult = PrivateKeyAuthResult.NOT_STARTED;
    private boolean privateKeyAuthentication;
    private volatile boolean cancellationRequested;
    private volatile String terminalFailure;

    /** Call while the duplicating PFD still owns the descriptor, before detach. */
    static void prepareTcpDescriptor(int descriptor) throws IOException {
        if (descriptor < 0) throw new IOException("SSH TCP descriptor preparation failed");
        try { enterNative(); }
        catch (VaultException failure) { throw new IOException("SSH TCP descriptor preparation failed"); }
        try {
            Native.prepareTcpDescriptor(descriptor);
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw new IOException("SSH TCP descriptor preparation failed");
        } finally { NATIVE_ENTRY.remove(); }
    }

    private SshNativeSession(SshNativeCallbackAdapter callback, ViewBinding view,
            NativeBinding nativeBinding,
            SshNativeCallbackAdapter.NativeSessionClaim callbackClaim, long nativeHandle,
            int stepBudget, byte[] expectedSelectionReceipt, boolean managedHostKey) {
        this.managedHostKey = managedHostKey;
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
                    expectedFingerprint, candidateId, stepBudget, new ProductionNativeBinding(), false, null, KNOWN_HOSTS_PATH);
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
                    candidateId, stepBudget, nativeBinding, false, null, KNOWN_HOSTS_PATH);
        } finally { NATIVE_ENTRY.remove(); }
    }

    /** Discovers only the actual key offered by the initial transport KEX. */
    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, SshPublicFileLease view,
            int socketFd, String candidateId, int stepBudget) throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            if (view == null || !callback.ownsPublicView(view)) throw invalid();
            return openInternal(callback, new ProductionViewBinding(view), socketFd, null, null,
                    candidateId, stepBudget, new ProductionNativeBinding(), true, null, KNOWN_HOSTS_PATH);
        } finally { NATIVE_ENTRY.remove(); }
    }

    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, ViewBinding view,
            int socketFd, String candidateId, int stepBudget, NativeBinding nativeBinding)
            throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            return openInternal(callback, view, socketFd, null, null, candidateId, stepBudget,
                    nativeBinding, true, null, KNOWN_HOSTS_PATH);
        } finally { NATIVE_ENTRY.remove(); }
    }

    /** Production handoff retains cleanup ownership even when a post-open guard fails. */
    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, SshPublicFileLease view,
            SshSocketOwnership socket, String candidateId, int stepBudget) throws VaultException {
        return openManaged(callback, view, socket, KNOWN_HOSTS_PATH, candidateId, stepBudget);
    }

    /** The caller supplies the exact trust object selected from this immutable public view. */
    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, SshPublicFileLease view,
            SshSocketOwnership socket, String knownHostsPath, String candidateId, int stepBudget) throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            if (view == null || socket == null || !callback.ownsPublicView(view)) throw invalid();
            return openInternal(callback, new ProductionViewBinding(view), socket.descriptor(), null,
                    null, candidateId, stepBudget, new ProductionNativeBinding(), true, socket, knownHostsPath);
        } finally { NATIVE_ENTRY.remove(); }
    }

    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, ViewBinding view,
            SshSocketOwnership socket, String candidateId, int stepBudget, NativeBinding binding)
            throws VaultException {
        return openManaged(callback, view, socket, KNOWN_HOSTS_PATH, candidateId, stepBudget, binding);
    }

    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, ViewBinding view,
            SshSocketOwnership socket, String knownHostsPath, String candidateId, int stepBudget, NativeBinding binding)
            throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            if (socket == null) throw invalid();
            return openInternal(callback, view, socket.descriptor(), null, null, candidateId,
                    stepBudget, binding, true, socket, knownHostsPath);
        } finally { NATIVE_ENTRY.remove(); }
    }

    private static SshNativeSession openInternal(SshNativeCallbackAdapter callback, ViewBinding view,
            int socketFd, byte[] hostKeyBlob, byte[] expectedFingerprint,
            String candidateId, int stepBudget, NativeBinding nativeBinding, boolean managedHostKey,
            SshSocketOwnership socket, String knownHostsPath)
            throws VaultException {
        if (callback == null || view == null || nativeBinding == null
                || Thread.currentThread() != callback.eventLoopThread()
                || socketFd < 0 || !view.active()
                || stepBudget <= 0
                || stepBudget > MAX_STEP_BUDGET
                || (!managedHostKey && (!validHostKey(hostKeyBlob)
                        || !validFingerprint(expectedFingerprint)))
                || !identifier(candidateId) || knownHostsPath == null) {
            throw invalid();
        }
        try { VaultManifest.path(knownHostsPath); }
        catch (VaultException failure) { throw invalid(); }
        callback.requireNativeSessionLive();
        byte[] wire = null;
        byte[] suppliedWire = null;
        byte[] expectedReceipt = null;
        byte[] key = null;
        byte[] fingerprint = null;
        SshNativeCallbackAdapter.NativeSessionClaim claim = null;
        boolean opened = false;
        boolean returned = false;
        SshNativeSession prepared = null;
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
            if (!managedHostKey) {
                key = hostKeyBlob.clone();
                fingerprint = expectedFingerprint.clone();
            }
            claim = callback.beginNativeSessionOpen();
            long viewToken = view.token();
            long callbackToken = callback.nativeCallbackToken();
            if (viewToken <= 0 || callbackToken <= 0) throw unavailable();
            prepared = new SshNativeSession(callback, view, nativeBinding, claim, 0,
                    stepBudget, expectedReceipt, managedHostKey);
            if (socket != null) socket.prepareNativeOwner(prepared);
            final long handle;
            try {
                handle = managedHostKey ? nativeBinding.openManaged(callback.roleToken(),
                        callback.ownerUid(), callbackToken, viewToken, socketFd, callback.deadline(),
                        stepBudget, knownHostsPath, callback.host(), candidateId, suppliedWire)
                        : nativeBinding.open(callback.roleToken(), callback.ownerUid(), callbackToken,
                        viewToken, socketFd, callback.deadline(), stepBudget, key, fingerprint,
                        knownHostsPath, callback.host(), candidateId, suppliedWire);
            } catch (IOException | LinkageError | RuntimeException failure) { throw unavailable(); }
            if (handle <= 0) throw unavailable();
            prepared.nativeHandle = handle;
            if (socket != null) socket.nativeAdopted();
            callback.finishNativeSessionOpen(claim, true);
            opened = true;
            if (socket != null) {
                callback.requireAuthenticationSelectionLive();
                if (!view.active()) throw failure("SSH_FILE_VIEW");
            }
            returned = true;
            return prepared;
        } catch (Throwable failure) {
            if (prepared != null && prepared.nativeHandle > 0) {
                callback.finishNativeSessionOpen(claim, true);
                opened = true;
                prepared.terminalFailure = "SSH_SESSION_OPEN";
                try { prepared.closeInternal(); }
                catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        } finally {
            if (!opened && claim != null) callback.finishNativeSessionOpen(claim, false);
            VaultCodec.wipe(wire);
            VaultCodec.wipe(suppliedWire);
            VaultCodec.wipe(key);
            VaultCodec.wipe(fingerprint);
            if (!returned) VaultCodec.wipe(expectedReceipt);
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
            if (phase == Phase.SERVICE_READY
                    || (phase == Phase.FINISHED && shellPhase == ShellPhase.DISABLED)) throw phaseFailure();
            if (complete && phase == Phase.KEX_ONLY) throw failure("SSH_SESSION_COMPLETE");
        }
        boolean explicit = phase != Phase.KEX_ONLY;
        if (explicit) {
            requireTransportLive();
            if (shellPhase != ShellPhase.DISABLED && queryShellStatus().phase.terminal())
                throw phaseFailure();
        }
        if (cancellationRequested) {
            terminalFailure = "SSH_SESSION_CANCELLED";
        } else if (!explicit) {
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
                requireTransportLive();
                if (shellPhase != ShellPhase.DISABLED) queryShellStatus();
                else if (phase == Phase.SERVICE_PENDING) queryServiceReady();
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
            if (explicit) recordProtocolError("SSH_SESSION_STEP");
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

    void beginShell(int columns, int rows) throws VaultException {
        shellEntry("SSH_SESSION_SHELL", () -> {
            if (phase != Phase.FINISHED || shellPhase != ShellPhase.DISABLED
                    || expectedSelectionReceipt == null
                    || (privateKeyAuthentication ? privateKeyResult != PrivateKeyAuthResult.SUCCESS
                            : authenticationResult != PasswordAuthResult.SUCCESS)) throw phaseFailure();
            requireDimensions(columns, rows);
            nativeBinding.beginShell(nativeHandle, columns, rows);
            try { callback.retainEstablishedSession(callbackClaim); }
            catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
            established = true;
            view.close();
            viewReleased = true;
            VaultCodec.wipe(expectedSelectionReceipt);
            AppSecrets.EffectGuard retirement = authenticationRetirement;
            if (retirement != null) retirement.check();
            authenticationRetirement = null;
            authenticationRetired = true;
            requireTransportLive();
            shellPhase = ShellPhase.OPENING;
            return null;
        });
    }

    /** Called on the original owner before shell promotion; no UI or native entry may reenter. */
    void retireAuthenticationWith(AppSecrets.EffectGuard retirement) throws VaultException {
        requireOwnerThread();
        if (retirement == null || established || authenticationRetirement != null)
            throw phaseFailure();
        authenticationRetirement = retirement;
    }

    ShellStatus shellStatus() throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_STATUS", this::queryShellStatus);
    }

    int writeShell(byte[] bytes) throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_WRITE", () -> {
            requireRunningShell();
            if (shellEofAccepted || lastShellStatus.localEof) throw phaseFailure();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_SHELL_IO_BYTES) throw invalid();
            byte[] copy = bytes.clone();
            try {
                int accepted = nativeBinding.writeShell(nativeHandle, copy);
                requireTransportLive();
                if (accepted < 0 || accepted > copy.length) throw new IOException("invalid SSH shell write");
                return accepted;
            } finally { VaultCodec.wipe(copy); }
        });
    }

    byte[] readShell(int maximum) throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_READ", () -> {
            if (shellPhase == ShellPhase.DISABLED) throw phaseFailure();
            if (maximum <= 0 || maximum > MAX_SHELL_IO_BYTES) throw invalid();
            byte[] received = null;
            byte[] result = null;
            boolean published = false;
            try {
                received = nativeBinding.readShell(nativeHandle, maximum);
                requireTransportLive();
                if (received == null || received.length > maximum) throw new IOException("invalid SSH shell read");
                result = received.clone();
                requireTransportLive();
                published = true;
                return result;
            } finally {
                VaultCodec.wipe(received);
                if (!published) VaultCodec.wipe(result);
            }
        });
    }

    boolean resizeShell(int columns, int rows) throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_RESIZE", () -> {
            requireRunningShell();
            requireDimensions(columns, rows);
            return shellAccepted(nativeBinding.resizeShell(nativeHandle, columns, rows));
        });
    }

    boolean sendShellEof() throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_EOF", () -> {
            requireRunningShell();
            if (shellEofAccepted || lastShellStatus.localEof) throw phaseFailure();
            boolean accepted = shellAccepted(nativeBinding.sendShellEof(nativeHandle));
            if (accepted) shellEofAccepted = true;
            return accepted;
        });
    }

    private boolean shellAccepted(int accepted) throws IOException, VaultException {
        requireTransportLive();
        if (accepted != 0 && accepted != 1) throw new IOException("invalid SSH shell acceptance");
        return accepted == 1;
    }

    private void requireRunningShell() throws VaultException {
        if (shellPhase == ShellPhase.DISABLED) throw phaseFailure();
        if (queryShellStatus().phase != ShellPhase.RUNNING) throw phaseFailure();
    }

    private static void requireDimensions(int columns, int rows) throws VaultException {
        if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096) throw invalid();
    }

    private ShellStatus queryShellStatus() throws VaultException {
        try {
            ShellStatus result = new ShellStatus(nativeBinding.shellStatus(nativeHandle));
            requireTransportLive();
            if ((shellPhase == ShellPhase.DISABLED && result.phase != ShellPhase.DISABLED)
                    || (shellPhase != ShellPhase.DISABLED && result.phase == ShellPhase.DISABLED)
                    || (shellPhase.terminal() && result.phase != shellPhase)
                    || (!result.phase.terminal() && result.phase.tag < shellPhase.tag)
                    || (shellEofAccepted && !result.localEof)
                    || (lastShellStatus != null && (lastShellStatus.remoteEof && !result.remoteEof
                            || lastShellStatus.localEof && !result.localEof
                            || lastShellStatus.exitStatusKnown && (!result.exitStatusKnown
                                    || lastShellStatus.exitStatus != result.exitStatus)
                            || lastShellStatus.exitSignal && !result.exitSignal)))
                throw new IOException("SSH shell status regressed");
            shellPhase = result.phase;
            lastShellStatus = result;
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_SHELL_STATUS");
        } catch (Error nativeFailure) {
            recordProtocolError("SSH_SESSION_SHELL_STATUS");
            throw nativeFailure;
        }
    }

    private interface ShellOperation<T> { T run() throws IOException, VaultException; }

    private <T> T shellEntry(String code, ShellOperation<T> operation) throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            requireTransportLive();
            try { return operation.run(); }
            catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure(code);
            } catch (Error nativeFailure) {
                recordProtocolError(code);
                throw nativeFailure;
            }
        } finally { NATIVE_ENTRY.remove(); }
    }

    private void recordProtocolError(String code) {
        if (terminalFailure != null) return;
        try { requireTransportLive(); }
        catch (VaultException lifecycle) { return; }
        terminalFailure = code;
    }

    private void requireProtocolState() throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
            if (terminalFailure != null) throw failure(terminalFailure);
        }
    }

    private void requireProtocolLive() throws VaultException {
        if (established) throw phaseFailure();
        try { requireSelectionReadLive(); }
        catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
    }

    private void requireTransportLive() throws VaultException {
        if (!established) { requireProtocolLive(); return; }
        try {
            synchronized (stateLock) {
                if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
                if (terminalFailure != null) throw failure(terminalFailure);
                if (cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
                if (!authenticationRetired) throw phaseFailure();
            }
            callback.requireEstablishedSessionBound(callbackClaim);
        } catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
    }

    private VaultException protocolNativeFailure(String code) throws VaultException {
        // A callback may cancel, expire or revoke the view while native reports failure.
        requireTransportLive();
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
        if (established) throw phaseFailure();
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
        synchronized (stateLock) {
            if (!established || !"SSH_SESSION_EXPIRED".equals(code)) terminalFailure = code;
        }
        return code.equals(lifecycle.code) ? lifecycle : failure(code);
    }

    void verifyHostKey(byte[] hostKeyBlob) throws VaultException {
        requireOwnerThread();
        enterNative();
        try { verifyHostKeyInternal(hostKeyBlob); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private void verifyHostKeyInternal(byte[] hostKeyBlob) throws VaultException {
        if (managedHostKey) throw phaseFailure();
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
        authenticationRetirement = null;
        synchronized (stateLock) { destroyed = true; }
    }

    /** Drops previously read output at its eventual UI publication; grants no native entry. */
    boolean shellOutputPublicationLive() {
        if (!outputPublicationStateLive()) return false;
        try {
            if (!established) return false;
            callback.requireEstablishedSessionBound(callbackClaim);
        }
        catch (VaultException lifecycle) { return false; }
        return authenticationRetired && outputPublicationStateLive();
    }

    private boolean outputPublicationStateLive() {
        synchronized (stateLock) {
            return !nativeClosed && !destroyed && !cancellationRequested && terminalFailure == null;
        }
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
