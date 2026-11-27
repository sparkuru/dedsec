package im.majo.ctos;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

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

    /** JNI-only negative issuer metadata, not a proof or an arbitrary exception message. */
    static final class HostKeyRejectedException extends IOException {
        final String code;
        HostKeyRejectedException(String code) {
            super("The actual SSH host key was rejected");
            if (!"SSH_HOST_KEY_CHANGED".equals(code) && !"SSH_HOST_KEY_REVOKED".equals(code)
                    && !"SSH_HOST_KEY_CERTIFICATE".equals(code) && !"SSH_HOST_TRUST_BLOCKED".equals(code))
                throw new IllegalArgumentException("Invalid SSH host rejection code");
            this.code = code;
        }
    }

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

    enum KeyboardInteractiveAuthResult {
        NOT_STARTED(0), PENDING(1), SUCCESS(2), REJECTED(3), PARTIAL(4), CHALLENGE(5);
        final int tag;
        KeyboardInteractiveAuthResult(int tag) { this.tag = tag; }
        private static KeyboardInteractiveAuthResult fromNative(int tag) throws IOException {
            for (KeyboardInteractiveAuthResult result : values()) if (result.tag == tag) return result;
            throw new IOException("invalid SSH keyboard-interactive authentication result");
        }
    }

    enum AuthDiscoveryResult {
        NOT_STARTED(0), PENDING(1), METHODS(2), NONE_SUCCESS(3), PARTIAL(4);
        final int tag;
        AuthDiscoveryResult(int tag) { this.tag = tag; }
        private static AuthDiscoveryResult fromNative(int tag) throws IOException {
            for (AuthDiscoveryResult result : values()) if (result.tag == tag) return result;
            throw new IOException("invalid SSH discovery result");
        }
    }

    static final class AuthDiscoveryStatus {
        static final int PUBLIC_KEY = 1, KEYBOARD_INTERACTIVE = 2, PASSWORD = 4, OTHER = 8;
        final AuthDiscoveryResult result;
        final int methods;
        private AuthDiscoveryStatus(int[] values) throws IOException {
            if (values == null || values.length != 2 || values[1] < 0 || values[1] > 15)
                throw new IOException("invalid SSH discovery status");
            result = AuthDiscoveryResult.fromNative(values[0]);
            methods = values[1];
            if ((result == AuthDiscoveryResult.NOT_STARTED || result == AuthDiscoveryResult.PENDING
                    || result == AuthDiscoveryResult.NONE_SUCCESS) && methods != 0)
                throw new IOException("invalid SSH discovery methods");
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

    enum JumpPhase {
        OPENING(1), OPEN(2), CLOSED(3), REJECTED(4);

        final int tag;
        JumpPhase(int tag) { this.tag = tag; }

        private static JumpPhase fromNative(int tag) throws IOException {
            for (JumpPhase phase : values()) if (phase.tag == tag) return phase;
            throw new IOException("invalid SSH jump phase");
        }

        boolean terminal() { return this == CLOSED || this == REJECTED; }
    }

    /** Validated channel metadata; it does not authorize the next host. */
    static final class JumpStatus {
        final JumpPhase phase;
        final boolean remoteEof;
        final boolean localEof;
        final int bufferedBytes;

        private JumpStatus(int[] values) throws IOException {
            if (values == null || values.length != 4
                    || (values[1] != 0 && values[1] != 1)
                    || (values[2] != 0 && values[2] != 1)
                    || values[3] < 0 || values[3] > MAX_SHELL_BUFFER_BYTES)
                throw new IOException("invalid SSH jump status");
            phase = JumpPhase.fromNative(values[0]);
            remoteEof = values[1] != 0;
            localEof = values[2] != 0;
            bufferedBytes = values[3];
        }
    }

    private enum Phase { KEX_ONLY, SERVICE_PENDING, SERVICE_READY, DISCOVERY_PENDING, PASSWORD_PENDING,
        PRIVATE_KEY_PENDING, KEYBOARD_PENDING, FINISHED }

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
        default long reserveJumpChild(long parentHandle) throws IOException {
            throw new IOException("SSH jump child is unavailable");
        }
        default boolean openJumpChild(long childHandle, long role, int ownerUid, long callback,
                long view, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException {
            throw new IOException("SSH jump child is unavailable");
        }
        default boolean openJumpChildFresh(long childHandle, long role, int ownerUid, long callback,
                long view, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire, byte[] freshWire) throws IOException {
            throw new IOException("SSH fresh jump trust source is unavailable");
        }
        default int jumpChildState(long childHandle) throws IOException {
            throw new IOException("SSH jump child state is unavailable");
        }
        default void abortJumpChild(long childHandle) throws IOException {
            throw new IOException("SSH jump child cleanup is unavailable");
        }
        default void beginJump(long parentHandle, long nextRole) throws IOException {
            throw new IOException("SSH jump channel is unavailable");
        }
        default void beginJumpGuarded(long parentHandle, long nextRole, long deadline, long generation)
                throws IOException {
            throw new IOException("SSH guarded jump channel is unavailable");
        }
        default int[] jumpStatus(long parentHandle) throws IOException {
            throw new IOException("SSH jump channel is unavailable");
        }
        default void pumpJumpRelay(long childHandle) throws IOException {
            throw new IOException("SSH jump relay is unavailable");
        }
        default void retainJumpTransport(long parentHandle) throws IOException {
            throw new IOException("SSH jump transport is unavailable");
        }
        default byte[] authenticationSelectionReceipt(long handle) throws IOException {
            throw new IOException("SSH selection receipt is unavailable");
        }
        default void beginUserauthService(long handle) throws IOException {
            throw new IOException("SSH userauth service is unavailable");
        }
        default void beginUserauthServiceGuarded(long handle, long generation) throws IOException {
            throw new IOException("SSH guarded userauth service is unavailable");
        }
        default boolean userauthServiceReady(long handle) throws IOException {
            throw new IOException("SSH userauth service is unavailable");
        }
        default void beginPasswordAuth(long handle) throws IOException {
            throw new IOException("SSH password authentication is unavailable");
        }
        default void beginPasswordAuthGuarded(long handle, long generation) throws IOException {
            throw new IOException("SSH guarded password authentication is unavailable");
        }
        default byte[] beginDiscoveredPassword(long handle, byte[] planReceipt, byte[] password) throws IOException {
            throw new IOException("SSH discovered password authentication is unavailable");
        }
        default void beginAuthDiscovery(long handle) throws IOException {
            throw new IOException("SSH method discovery is unavailable");
        }
        default void beginAuthDiscoveryGuarded(long handle, long generation) throws IOException {
            throw new IOException("SSH guarded method discovery is unavailable");
        }
        default int[] authDiscoveryResult(long handle) throws IOException {
            throw new IOException("SSH method discovery is unavailable");
        }
        default int authContinuationMethods(long handle) throws IOException {
            throw new IOException("SSH continuation methods are unavailable");
        }
        default void pollAuthPrompt(long handle, byte[] planReceipt, int method) throws IOException {
            throw new IOException("SSH prompt transport polling is unavailable");
        }
        default void pollSelectedPasswordPrompt(long handle, byte[] planReceipt) throws IOException {
            throw new IOException("SSH selected password polling is unavailable");
        }
        default int passwordAuthResult(long handle) throws IOException {
            throw new IOException("SSH password authentication is unavailable");
        }
        default void beginPrivateKeyAuth(long handle) throws IOException {
            throw new IOException("SSH private-key authentication is unavailable");
        }
        default byte[] continuePrivateKeyAuth(long handle, byte[] planReceipt) throws IOException {
            throw new IOException("SSH private-key continuation is unavailable");
        }
        default int privateKeyAuthResult(long handle) throws IOException {
            throw new IOException("SSH private-key authentication is unavailable");
        }
        default void beginKeyboardInteractiveAuth(long handle) throws IOException {
            throw new IOException("SSH keyboard-interactive authentication is unavailable");
        }
        default byte[] continueKeyboardInteractiveAuth(long handle, byte[] planReceipt) throws IOException {
            throw new IOException("SSH authentication continuation is unavailable");
        }
        default byte[] beginDiscoveredKeyboard(long handle, byte[] planReceipt) throws IOException {
            throw new IOException("SSH discovered authentication is unavailable");
        }
        default int keyboardInteractiveAuthResult(long handle) throws IOException {
            throw new IOException("SSH keyboard-interactive authentication is unavailable");
        }
        default byte[] keyboardInteractiveChallenge(long handle) throws IOException {
            throw new IOException("SSH keyboard-interactive authentication is unavailable");
        }
        default void respondKeyboardInteractive(long handle, byte[] response) throws IOException {
            throw new IOException("SSH keyboard-interactive authentication is unavailable");
        }
        default void configureServerAlive(long handle, int interval, int countMax) throws IOException {
            throw new IOException("SSH server-alive policy is unavailable");
        }
        default void configureNoPty(long handle) throws IOException {
            throw new IOException("SSH no-PTY policy is unavailable");
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

        @Override public long reserveJumpChild(long parentHandle) throws IOException {
            return Native.reserveJumpChild(parentHandle);
        }
        @Override public boolean openJumpChild(long childHandle, long role, int ownerUid,
                long callback, long view, long deadline, int stepBudget, String knownHostsPath,
                String host, String candidateId, byte[] selectionWire) throws IOException {
            return Native.openJumpChild(childHandle, role, ownerUid, callback, view, deadline,
                    stepBudget, knownHostsPath, host, candidateId, selectionWire);
        }
        @Override public boolean openJumpChildFresh(long childHandle, long role, int ownerUid,
                long callback, long view, long deadline, int stepBudget, String knownHostsPath,
                String host, String candidateId, byte[] selectionWire, byte[] freshWire) throws IOException {
            return Native.openJumpChildFresh(childHandle, role, ownerUid, callback, view, deadline,
                    stepBudget, knownHostsPath, host, candidateId, selectionWire, freshWire);
        }
        @Override public int jumpChildState(long childHandle) throws IOException {
            return Native.jumpChildState(childHandle);
        }
        @Override public void abortJumpChild(long childHandle) throws IOException {
            Native.abortJumpChild(childHandle);
        }
        @Override public void beginJump(long parentHandle, long nextRole) throws IOException {
            Native.beginJump(parentHandle, nextRole);
        }
        @Override public void beginJumpGuarded(long parentHandle, long nextRole, long deadline, long generation)
                throws IOException {
            Native.beginJumpGuarded(parentHandle, nextRole, deadline, generation);
        }
        @Override public int[] jumpStatus(long parentHandle) throws IOException {
            return Native.jumpStatus(parentHandle);
        }
        @Override public void pumpJumpRelay(long childHandle) throws IOException {
            Native.pumpJumpRelay(childHandle);
        }
        @Override public void retainJumpTransport(long parentHandle) throws IOException {
            Native.retainJumpTransport(parentHandle);
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
        @Override public void beginUserauthServiceGuarded(long handle, long generation) throws IOException {
            Native.beginUserauthServiceGuarded(handle, generation);
        }

        @Override public boolean userauthServiceReady(long handle) throws IOException {
            return Native.userauthServiceReady(handle);
        }

        @Override public void beginPasswordAuth(long handle) throws IOException {
            Native.beginPasswordAuth(handle);
        }
        @Override public void beginPasswordAuthGuarded(long handle, long generation) throws IOException {
            Native.beginPasswordAuthGuarded(handle, generation);
        }
        @Override public byte[] beginDiscoveredPassword(long handle, byte[] planReceipt, byte[] password) throws IOException {
            return Native.beginDiscoveredPassword(handle, planReceipt, password);
        }
        @Override public void beginAuthDiscovery(long handle) throws IOException {
            Native.beginAuthDiscovery(handle);
        }
        @Override public void beginAuthDiscoveryGuarded(long handle, long generation) throws IOException {
            Native.beginAuthDiscoveryGuarded(handle, generation);
        }
        @Override public int[] authDiscoveryResult(long handle) throws IOException {
            return Native.authDiscoveryResult(handle);
        }
        @Override public int authContinuationMethods(long handle) throws IOException {
            return Native.authContinuationMethods(handle);
        }
        @Override public void pollAuthPrompt(long handle, byte[] planReceipt, int method) throws IOException {
            Native.pollAuthPrompt(handle, planReceipt, method);
        }
        @Override public void pollSelectedPasswordPrompt(long handle, byte[] planReceipt) throws IOException {
            Native.pollSelectedPasswordPrompt(handle, planReceipt);
        }

        @Override public int passwordAuthResult(long handle) throws IOException {
            return Native.passwordAuthResult(handle);
        }
        @Override public void beginPrivateKeyAuth(long handle) throws IOException {
            Native.beginPrivateKeyAuth(handle);
        }
        @Override public byte[] continuePrivateKeyAuth(long handle, byte[] planReceipt) throws IOException {
            return Native.continuePrivateKeyAuth(handle, planReceipt);
        }
        @Override public int privateKeyAuthResult(long handle) throws IOException {
            return Native.privateKeyAuthResult(handle);
        }
        @Override public void beginKeyboardInteractiveAuth(long handle) throws IOException {
            Native.beginKeyboardInteractiveAuth(handle);
        }
        @Override public int keyboardInteractiveAuthResult(long handle) throws IOException {
            return Native.keyboardInteractiveAuthResult(handle);
        }
        @Override public byte[] continueKeyboardInteractiveAuth(long handle, byte[] planReceipt) throws IOException {
            return Native.continueKeyboardInteractiveAuth(handle, planReceipt);
        }
        @Override public byte[] beginDiscoveredKeyboard(long handle, byte[] planReceipt) throws IOException {
            return Native.beginDiscoveredKeyboard(handle, planReceipt);
        }
        @Override public byte[] keyboardInteractiveChallenge(long handle) throws IOException {
            return Native.keyboardInteractiveChallenge(handle);
        }
        @Override public void respondKeyboardInteractive(long handle, byte[] response) throws IOException {
            Native.respondKeyboardInteractive(handle, response);
        }

        @Override public void configureServerAlive(long handle, int interval, int countMax) throws IOException {
            Native.configureServerAlive(handle, interval, countMax);
        }
        @Override public void configureNoPty(long handle) throws IOException {
            Native.configureNoPty(handle);
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
        static native long reserveJumpChild(long parentHandle) throws IOException;
        static native boolean openJumpChild(long childHandle, long role, int ownerUid, long callback,
                long view, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException;
        static native boolean openJumpChildFresh(long childHandle, long role, int ownerUid, long callback,
                long view, long deadline, int stepBudget, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire, byte[] freshWire) throws IOException;
        static native int jumpChildState(long childHandle) throws IOException;
        static native void abortJumpChild(long childHandle) throws IOException;
        static native void beginJump(long parentHandle, long nextRole) throws IOException;
        static native void beginJumpGuarded(long parentHandle, long nextRole, long deadline, long generation)
                throws IOException;
        static native int[] jumpStatus(long parentHandle) throws IOException;
        static native void pumpJumpRelay(long childHandle) throws IOException;
        static native void retainJumpTransport(long parentHandle) throws IOException;
        static native long open(long role, int ownerUid, long callback, long view, int socketFd,
                long deadline, int stepBudget, byte[] hostKeyBlob,
                byte[] expectedFingerprint, String knownHostsPath, String host,
                String candidateId, byte[] selectionWire) throws IOException;
        static native byte[] authenticationSelectionReceipt(long handle) throws IOException;
        static native void beginUserauthService(long handle) throws IOException;
        static native void beginUserauthServiceGuarded(long handle, long generation) throws IOException;
        static native boolean userauthServiceReady(long handle) throws IOException;
        static native void beginPasswordAuth(long handle) throws IOException;
        static native void beginPasswordAuthGuarded(long handle, long generation) throws IOException;
        static native byte[] beginDiscoveredPassword(long handle, byte[] planReceipt, byte[] password) throws IOException;
        static native void beginAuthDiscovery(long handle) throws IOException;
        static native void beginAuthDiscoveryGuarded(long handle, long generation) throws IOException;
        static native int[] authDiscoveryResult(long handle) throws IOException;
        static native int authContinuationMethods(long handle) throws IOException;
        static native void pollAuthPrompt(long handle, byte[] planReceipt, int method) throws IOException;
        static native void pollSelectedPasswordPrompt(long handle, byte[] planReceipt) throws IOException;
        static native int passwordAuthResult(long handle) throws IOException;
        static native void beginPrivateKeyAuth(long handle) throws IOException;
        static native byte[] continuePrivateKeyAuth(long handle, byte[] planReceipt) throws IOException;
        static native int privateKeyAuthResult(long handle) throws IOException;
        static native void beginKeyboardInteractiveAuth(long handle) throws IOException;
        static native byte[] continueKeyboardInteractiveAuth(long handle, byte[] planReceipt) throws IOException;
        static native byte[] beginDiscoveredKeyboard(long handle, byte[] planReceipt) throws IOException;
        static native int keyboardInteractiveAuthResult(long handle) throws IOException;
        static native byte[] keyboardInteractiveChallenge(long handle) throws IOException;
        static native void respondKeyboardInteractive(long handle, byte[] response) throws IOException;
        static native void configureServerAlive(long handle, int interval, int countMax) throws IOException;
        static native void configureNoPty(long handle) throws IOException;
        static native void beginShell(long handle, int columns, int rows) throws IOException;
        static native int[] shellStatus(long handle) throws IOException;
        static native int writeShell(long handle, byte[] bytes) throws IOException;
        static native byte[] readShell(long handle, int maximum) throws IOException;
        static native int resizeShell(long handle, int columns, int rows) throws IOException;
        static native int sendShellEof(long handle) throws IOException;
        static native int[] step(long handle, boolean cancelled) throws IOException;
        /* Test library only. The release JNI library deliberately has no such entry. */
        static native boolean provisionalNewFixtureReady(long handle, long callback) throws IOException;
        static native int provisionalNewFixturePoll(long handle, long callback) throws IOException;
        static native boolean provisionalNewFixtureExtSeen(long handle, long callback) throws IOException;
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
    private SshSharedPublicView sharedView;
    private volatile JumpChildOwner jumpChildOwner;
    private JumpAdmission jumpAdmission;
    private JumpChildOwner ownedByJump;
    private boolean nativeClosed;
    private boolean callbackClosed;
    private boolean roleReleased;
    private boolean viewReleased;
    private boolean destroyed;
    private boolean closeInProgress;
    private boolean cancellationInProgress;
    private boolean cancellationNeedsCompletion;
    private boolean cancellationExplicitlyUnstarted;
    private boolean cancellationComplete;
    private boolean callbackCancelCleanupOnly;
    private Throwable cancellationUnconfirmed;
    private boolean complete;
    private volatile Phase phase = Phase.KEX_ONLY;
    private ShellPhase shellPhase = ShellPhase.DISABLED;
    private JumpPhase jumpPhase;
    private JumpStatus lastJumpStatus;
    private boolean serverAliveConfigured;
    private boolean noPtyConfigured;
    private ShellStatus lastShellStatus;
    private boolean shellEofAccepted;
    private volatile boolean established;
    private volatile boolean authenticationRetired;
    private AppSecrets.EffectGuard authenticationRetirement;
    private AppSecrets.EffectGuard shellConfirmation;
    private volatile boolean shellConfirmationPending;
    private PasswordAuthResult authenticationResult = PasswordAuthResult.NOT_STARTED;
    private AuthDiscoveryResult discoveryResult = AuthDiscoveryResult.NOT_STARTED;
    private int discoveryMethods;
    private int authenticationStage = 1;
    private PrivateKeyAuthResult privateKeyResult = PrivateKeyAuthResult.NOT_STARTED;
    private boolean privateKeyAuthentication;
    private boolean keyboardInteractiveAuthentication;
    private KeyboardInteractiveAuthResult keyboardResult = KeyboardInteractiveAuthResult.NOT_STARTED;
    private SshKeyboardChallenge keyboardChallenge;
    private int keyboardRound;
    private volatile Object keyboardStage;
    private volatile Object passwordPromptStage;
    private boolean passwordPromptIssued;
    private volatile Object selectedPasswordPromptStage;
    private boolean selectedPasswordPromptIssued;
    private volatile Object privateKeyPromptStage;
    private boolean privateKeyPromptIssued;
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

    /** Shared chain use retains the exact lease until every native owner retires its own borrow. */
    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, SshSharedPublicView owner,
            SshSocketOwnership socket, String knownHostsPath, String candidateId, int stepBudget)
            throws VaultException {
        requireOpenOwner(callback);
        if (owner == null || !owner.hasManagedLease()) throw invalid();
        return openManaged(callback, owner, socket, knownHostsPath, candidateId, stepBudget,
                new ProductionNativeBinding());
    }

    /** JVM seam for the same shared-view ownership and native handoff order. */
    static SshNativeSession openManaged(SshNativeCallbackAdapter callback, SshSharedPublicView owner,
            SshSocketOwnership socket, String knownHostsPath, String candidateId, int stepBudget,
            NativeBinding binding) throws VaultException {
        requireOpenOwner(callback);
        enterNative();
        try {
            if (owner == null || socket == null || binding == null) throw invalid();
            SshSharedPublicView.Binding view = owner.issue(callback);
            try {
                SshNativeSession result = openInternal(callback, view, socket.descriptor(), null,
                        null, candidateId, stepBudget, binding, true, socket, knownHostsPath);
                result.sharedView = owner;
                return result;
            } catch (Throwable failure) {
                try { view.abortUnadopted(); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
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

    /** Legacy fixture seam; real leases and the production JNI binding require a fresh source. */
    static JumpChildOwner prepareJumpChild(SshNativeSession parent, SshNativeCallbackAdapter callback,
            SshSharedPublicView shared, String knownHostsPath, String candidateId, int stepBudget,
            NativeBinding nativeBinding) throws VaultException {
        if ((shared != null && shared.hasManagedLease()) || nativeBinding instanceof ProductionNativeBinding)
            throw failure("SSH_JUMP_EFFECT_GUARD");
        return prepareJumpChild(parent, callback, shared, knownHostsPath, candidateId, stepBudget,
                null, null, nativeBinding);
    }

    /** The exact same-read vault receipt also owns the one-shot native source. */
    static JumpChildOwner prepareJumpChildFresh(SshNativeSession parent, SshNativeCallbackAdapter callback,
            SshSharedPublicView shared, String knownHostsPath, String candidateId, int stepBudget,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshPasswordConnector.TrustCommitState trustState) throws VaultException {
        if (shared == null || !shared.hasManagedLease() || source == null || trustState == null) throw invalid();
        if (parent != null && parent.ownedByJump != null) throw failure("SSH_JUMP_CHAIN_UNSUPPORTED");
        return prepareJumpChild(parent, callback, shared, knownHostsPath, candidateId, stepBudget,
                source, trustState, parent == null ? null : parent.nativeBinding);
    }

    /** Existing source-only tests cannot use a managed lease or the production JNI binding. */
    static JumpChildOwner prepareJumpChildFresh(SshNativeSession parent, SshNativeCallbackAdapter callback,
            SshSharedPublicView shared, String knownHostsPath, String candidateId, int stepBudget,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source, NativeBinding nativeBinding)
            throws VaultException {
        if (source == null) throw invalid();
        if ((shared != null && shared.hasManagedLease()) || nativeBinding instanceof ProductionNativeBinding)
            throw failure("SSH_JUMP_EFFECT_GUARD");
        return prepareJumpChild(parent, callback, shared, knownHostsPath, candidateId, stepBudget,
                source, null, nativeBinding);
    }

    /** JVM seam for the same strict admission/provider checks with fake transport owners. */
    static JumpChildOwner prepareJumpChildFresh(SshNativeSession parent, SshNativeCallbackAdapter callback,
            SshSharedPublicView shared, String knownHostsPath, String candidateId, int stepBudget,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshPasswordConnector.TrustCommitState trustState, NativeBinding nativeBinding)
            throws VaultException {
        if (source == null || trustState == null) throw invalid();
        if ((shared != null && shared.hasManagedLease()) || nativeBinding instanceof ProductionNativeBinding)
            throw failure("SSH_JUMP_EFFECT_GUARD");
        return prepareJumpChild(parent, callback, shared, knownHostsPath, candidateId, stepBudget,
                source, trustState, nativeBinding);
    }

    private static JumpChildOwner prepareJumpChild(SshNativeSession parent, SshNativeCallbackAdapter callback,
            SshSharedPublicView shared, String knownHostsPath, String candidateId, int stepBudget,
            SshManagedKnownHostsProvider.ChildTrustSnapshot source,
            SshPasswordConnector.TrustCommitState trustState, NativeBinding nativeBinding)
            throws VaultException {
        requireOpenOwner(callback);
        if (parent == null || shared == null || parent.sharedView != shared
                || parent.nativeBinding != nativeBinding || parent.jumpChildOwner != null
                || callback == parent.callback || callback.deadline() != parent.callback.deadline()
                || stepBudget <= 0 || stepBudget > MAX_STEP_BUDGET || !identifier(candidateId)
                || knownHostsPath == null) throw invalid();
        parent.requireOwnerThread();
        parent.requireProtocolState();
        if (parent.jumpAdmission != null && (!parent.jumpAdmission.consumed
                || !callback.ownsIssued(parent.jumpAdmission.next))) throw invalid();
        try { VaultManifest.path(knownHostsPath); }
        catch (VaultException failure) { throw invalid(); }
        if (trustState != null) {
            if (parent.jumpAdmission == null) throw phaseFailure();
            trustState.requireChildAssemblySlot(parent.jumpAdmission);
            parent.requireJumpAdmissionLive(parent.jumpAdmission);
            trustState.requireChildAssembly(parent.jumpAdmission, parent.callback, callback, source, knownHostsPath);
        }
        if (source != null) callback.bindChildTrustSnapshot(source, knownHostsPath);
        else callback.requireNoChildTrustSnapshot();
        JumpChildOwner result = new JumpChildOwner(parent, callback, shared, knownHostsPath,
                candidateId, stepBudget, nativeBinding, source, trustState);
        try {
            if (trustState != null && shared.hasManagedLease() && nativeBinding instanceof ProductionNativeBinding) {
                SshNativeCallbackAdapter.enableJumpEffectFence();
                result.effectFenceRegistered = true;
                callback.bindChildEffectOwner(result);
            }
            result.view = shared.issue(callback);
            if (trustState != null) {
                parent.requireJumpAdmissionLive(parent.jumpAdmission);
                trustState.claimChildAssembly(parent.jumpAdmission, parent.callback, callback, source, knownHostsPath);
            }
            parent.jumpChildOwner = result;
            return result;
        } catch (Throwable failure) {
            callback.unbindChildEffectOwner(result);
            result.releaseEffectFence();
            if (result.view != null) {
                try { result.view.abortUnadopted(); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /**
     * A caller retains this object before native reservation. A failed open can
     * leave a cleanup-only native child; the same owner must retry close.
     */
    static final class JumpChildOwner implements AutoCloseable {
        private final SshNativeSession parent;
        private final SshNativeCallbackAdapter callback;
        private final SshSharedPublicView shared;
        private final String knownHostsPath;
        private final String candidateId;
        private final int stepBudget;
        private final NativeBinding nativeBinding;
        private final SshManagedKnownHostsProvider.ChildTrustSnapshot trustSource;
        private final SshPasswordConnector.TrustCommitState trustState;
        private final Thread owner = Thread.currentThread();
        private SshSharedPublicView.Binding view;
        private SshNativeCallbackAdapter.NativeSessionClaim callbackClaim;
        private SshNativeSession child;
        private boolean attempted;
        private boolean callbackAdopted;
        private boolean viewAdopted;
        private volatile boolean closing;
        private volatile boolean closed;
        private boolean effectFenceRegistered;
        private long token;
        private long guardedHopDeadline;

        private JumpChildOwner(SshNativeSession parent, SshNativeCallbackAdapter callback,
                SshSharedPublicView shared, String knownHostsPath, String candidateId,
                int stepBudget, NativeBinding nativeBinding,
                SshManagedKnownHostsProvider.ChildTrustSnapshot trustSource,
                SshPasswordConnector.TrustCommitState trustState) {
            this.parent = parent;
            this.callback = callback;
            this.shared = shared;
            this.knownHostsPath = knownHostsPath;
            this.candidateId = candidateId;
            this.stepBudget = stepBudget;
            this.nativeBinding = nativeBinding;
            this.trustSource = trustSource;
            this.trustState = trustState;
        }

        boolean ownsCallback(SshNativeCallbackAdapter actual) { return callback == actual; }
        private boolean unavailable() { return closing || closed; }

        void requireProtectedMetadata() throws VaultException {
            if (!effectFenceRegistered || trustState == null || trustSource == null || child == null
                    || child.ownedByJump != this || child.callback != callback
                    || unavailable() || parent.jumpChildOwner != this || !callbackAdopted || !viewAdopted
                    || !shared.hasManagedLease())
                throw failure("SSH_JUMP_EFFECT_GUARD");
            if (trustState.publicationUncertain()) throw failure("VAULT_COMMIT_UNCERTAIN");
            if (!trustState.cancellation.liveForJumpEffect()) throw failure("SSH_SESSION_CANCELLED");
            trustSource.requireCurrent();
            SshNativeSession current = child;
            for (int index = callback.roleHop(); index >= 0; index--) {
                if (current == null || current.sharedView != shared || current.callback.roleHop() != index)
                    throw phaseFailure();
                current.requireTransportLive();
                JumpChildOwner link = current.ownedByJump;
                if (index == 0) {
                    if (link != null) throw phaseFailure();
                } else {
                    if (link == null || link.unavailable() || !link.callbackAdopted || !link.viewAdopted
                            || link.child != current || link.callback != current.callback
                            || link.shared != shared || link.parent.jumpChildOwner != link)
                        throw phaseFailure();
                    current = link.parent;
                }
            }
            callback.requireSelectedSavedChild(this);
            if (!trustState.cancellation.liveForJumpEffect()) throw failure("SSH_SESSION_CANCELLED");
        }

        void requireProtectedEffect() throws VaultException {
            requireOwner();
            requireProtectedMetadata();
        }

        long captureProtectedEffect() throws VaultException {
            long generation = SshNativeCallbackAdapter.captureJumpEffectGeneration();
            requireProtectedEffect();
            return generation;
        }

        long captureProtectedJumpOpen(long hopDeadline) throws VaultException {
            requireCurrent();
            if (!effectFenceRegistered || trustState == null || trustSource == null || !shared.hasManagedLease()
                    || hopDeadline <= 0 || hopDeadline > callback.deadline() || attempted || view == null)
                throw failure("SSH_JUMP_EFFECT_GUARD");
            long generation = SshNativeCallbackAdapter.captureJumpEffectGeneration();
            if (trustState.publicationUncertain()) throw failure("VAULT_COMMIT_UNCERTAIN");
            if (!trustState.cancellation.liveForJumpEffect()) throw failure("SSH_SESSION_CANCELLED");
            trustSource.requireCurrent();
            callback.requireAuthenticationSelectionLive();
            if (!view.active()) throw failure("SSH_FILE_VIEW");
            SshNativeSession current = parent;
            for (int index = parent.callback.roleHop(); index >= 0; index--) {
                if (current == null || current.sharedView != shared || current.callback.roleHop() != index)
                    throw phaseFailure();
                current.requireTransportLive();
                JumpChildOwner link = current.ownedByJump;
                if (index == 0) {
                    if (link != null) throw phaseFailure();
                } else {
                    if (link == null || link.unavailable() || !link.callbackAdopted || !link.viewAdopted
                            || link.child != current || link.parent.jumpChildOwner != link || link.shared != shared)
                        throw phaseFailure();
                    current = link.parent;
                }
            }
            if (!trustState.cancellation.liveForJumpEffect()) throw failure("SSH_SESSION_CANCELLED");
            return generation;
        }

        private void releaseEffectFence() {
            if (!effectFenceRegistered) return;
            SshNativeCallbackAdapter.disableJumpEffectFence();
            effectFenceRegistered = false;
        }

        private void requireOwner() throws VaultException {
            if (Thread.currentThread() != owner) throw failure("SSH_SESSION_THREAD");
        }

        private void requireCurrent() throws VaultException {
            requireOwner();
            if (unavailable() || parent.jumpChildOwner != this || parent.sharedView != shared)
                throw phaseFailure();
        }

        /** Only state 4 with all post-open checks returns a usable child. */
        SshNativeSession open() throws VaultException {
            requireCurrent();
            enterNative();
            byte[] wire = null, supplied = null, receipt = null, freshWire = null;
            try {
                if (attempted || parent.jumpPhase == null) throw phaseFailure();
                parent.requireProtocolState();
                parent.requireTransportLive();
                JumpStatus status = parent.queryJumpStatus();
                if (status.phase != JumpPhase.OPEN || status.remoteEof || status.localEof)
                    throw phaseFailure();
                callback.requireAuthenticationSelectionLive();
                if (!view.active()) throw failure("SSH_FILE_VIEW");
                wire = callback.authenticationSelectionWire();
                if (wire != null) {
                    if (wire.length == 0 || wire.length > SshAuthenticationSelection.MAX_WIRE_BYTES)
                        throw SshAuthenticationSelection.invalid();
                    supplied = wire.clone();
                    receipt = VaultCodec.digest(wire);
                }
                if (trustSource != null) {
                    callback.requireChildTrustSnapshot(trustSource, knownHostsPath);
                    freshWire = trustSource.takeWire();
                } else callback.requireNoChildTrustSnapshot();
                attempted = true;
                callbackClaim = callback.beginNativeSessionOpen();
                child = new SshNativeSession(callback, view, nativeBinding, callbackClaim, 0,
                        stepBudget, receipt, true);
                receipt = null;
                child.sharedView = shared;
                child.ownedByJump = this;
                try {
                    token = nativeBinding.reserveJumpChild(parent.nativeHandle);
                    if (token <= 0) throw new IOException("SSH jump reservation is unavailable");
                    child.nativeHandle = token;
                    boolean opened = trustSource == null
                            ? nativeBinding.openJumpChild(token, callback.roleToken(), callback.ownerUid(),
                                callback.nativeCallbackToken(), view.token(), callback.deadline(), stepBudget,
                                knownHostsPath, callback.host(), candidateId, supplied)
                            : nativeBinding.openJumpChildFresh(token, callback.roleToken(), callback.ownerUid(),
                                callback.nativeCallbackToken(), view.token(), callback.deadline(), stepBudget,
                                knownHostsPath, callback.host(), candidateId, supplied, freshWire);
                    int state = childState();
                    if (state >= 2) adopt();
                    if (!opened || state != 4) throw new IOException("SSH jump child did not open");
                    parent.requireTransportLive();
                    callback.requireAuthenticationSelectionLive();
                    if (trustSource != null) trustSource.requireCurrent();
                    if (!view.active()) throw failure("SSH_FILE_VIEW");
                    if (trustSource != null) callback.activateChildTrustSnapshot(trustSource, knownHostsPath);
                    return child;
                } catch (IOException | LinkageError | RuntimeException failure) {
                    throw SshNativeSession.unavailable();
                }
            } finally {
                VaultCodec.wipe(wire);
                VaultCodec.wipe(supplied);
                VaultCodec.wipe(receipt);
                VaultCodec.wipe(freshWire);
                NATIVE_ENTRY.remove();
            }
        }

        private int childState() throws IOException {
            int state = nativeBinding.jumpChildState(token);
            if (state < 0 || state > 4) throw new IOException("invalid SSH jump child state");
            return state;
        }

        private void adopt() throws VaultException {
            if (!callbackAdopted) {
                callback.finishNativeSessionOpen(callbackClaim, true);
                callbackAdopted = true;
            }
            if (!viewAdopted) {
                view.nativeAdopted(callback);
                viewAdopted = true;
            }
        }

        private void childDestroyed(SshNativeSession candidate) throws VaultException {
            if (candidate == child) finish();
        }

        private void finish() throws VaultException {
            if (closed) return;
            closing = true;
            token = 0;
            try {
                if (trustSource != null) trustSource.close();
                callback.unbindChildEffectOwner(this);
                releaseEffectFence();
            } catch (RuntimeException | LinkageError failure) {
                VaultException unconfirmed = cleanupFailure();
                unconfirmed.initCause(failure);
                throw unconfirmed;
            }
            if (parent.jumpChildOwner == this) parent.jumpChildOwner = null;
            closed = true;
        }

        @Override public void close() throws VaultException {
            requireOwner();
            if (closed) return;
            enterNative();
            try {
                closing = true;
                if (child != null && child.closed()) { finish(); return; }
                if (token > 0 && !callbackAdopted) {
                    final int state;
                    try { state = childState(); }
                    catch (IOException | LinkageError | RuntimeException failure) { throw cleanupFailure(); }
                    if (state >= 2) adopt();
                    else {
                        try { nativeBinding.abortJumpChild(token); }
                        catch (IOException | LinkageError | RuntimeException failure) { throw cleanupFailure(); }
                        token = 0;
                    }
                }
                if (callbackAdopted) {
                    if (!viewAdopted) adopt();
                    child.closeInternal();
                } else {
                    if (callbackClaim != null) callback.finishNativeSessionOpen(callbackClaim, false);
                    view.abortUnadopted();
                    if (child != null) VaultCodec.wipe(child.expectedSelectionReceipt);
                    if (trustSource != null) try {
                        callback.closeUnadoptedChildTrust(trustSource);
                    } catch (RuntimeException | LinkageError failure) {
                        VaultException unconfirmed = cleanupFailure();
                        unconfirmed.initCause(failure);
                        throw unconfirmed;
                    }
                    finish();
                }
            } finally { NATIVE_ENTRY.remove(); }
        }

        private static VaultException cleanupFailure() {
            return new VaultException("SSH_SESSION_CLEANUP", "The SSH jump child cleanup is unconfirmed");
        }
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
        if (view instanceof SshSharedPublicView.Binding)
            ((SshSharedPublicView.Binding) view).requireForOpen(callback);
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
            if (view instanceof SshSharedPublicView.Binding)
                ((SshSharedPublicView.Binding) view).nativeAdopted(callback);
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

    /** Exact owner of a checked native refusal before invalidation began. */
    private static final class CancellationNotStarted extends UnsatisfiedLinkError {
        private final SshNativeSession session;

        private CancellationNotStarted(SshNativeSession session) {
            super("SSH session invalidation did not begin");
            this.session = session;
        }
    }

    private static final int RETRY_COMPLETE = 0, RETRY_WAIT = 1, RETRY_CLEANUP_ONLY = 2;

    /** Request cancellation from any thread; all native I/O remains owner-thread only. */
    void cancel() throws VaultException {
        synchronized (stateLock) {
            if (destroyed || nativeClosed) return;
            cancellationRequested = true;
            if (cancellationUnconfirmed != null)
                throw new IllegalStateException("SSH session cancellation is unconfirmed", cancellationUnconfirmed);
            if (cancellationComplete) return;
            if (callbackCancelCleanupOnly)
                throw new VaultException("SSH_CALLBACK_NATIVE", "The SSH callback cancellation failed");
            cancellationNeedsCompletion = true;
            if (closeInProgress || cancellationInProgress)
                throw new VaultException("SSH_SESSION_CLEANUP", "SSH session cleanup is in progress");
            cancellationExplicitlyUnstarted = false;
            cancellationInProgress = true;
        }
        cancelReserved();
    }

    private void cancelReserved() throws VaultException {
        boolean explicitlyUnstarted = false;
        try {
            int begun = SshNativeCallbackAdapter.beginJumpCleanupInvalidation();
            if (begun == -1) {
                explicitlyUnstarted = true;
                synchronized (stateLock) { cancellationExplicitlyUnstarted = true; }
                throw new CancellationNotStarted(this);
            }
            if (begun == 1) SshNativeCallbackAdapter.endJumpEffectInvalidation(true);
            else if (begun != 0) throw new IllegalStateException("SSH session invalidation is unconfirmed");
            try { callback.cancel(); }
            catch (VaultException failure) {
                synchronized (stateLock) {
                    if ("SSH_CALLBACK_NATIVE".equals(failure.code) && failure.getCause() == null
                            && failure.getSuppressed().length == 0) {
                        cancellationNeedsCompletion = false;
                        callbackCancelCleanupOnly = true;
                    } else cancellationUnconfirmed = failure;
                }
                throw failure;
            }
            synchronized (stateLock) {
                cancellationNeedsCompletion = false;
                cancellationComplete = true;
            }
        } catch (RuntimeException | Error failure) {
            if (!explicitlyUnstarted) synchronized (stateLock) { cancellationUnconfirmed = failure; }
            throw failure;
        } finally {
            synchronized (stateLock) { cancellationInProgress = false; }
        }
    }

    /** Retry only a checked pre-begin refusal; another caller may have resolved it. */
    private int retryExplicitlyUnstarted() throws VaultException {
        synchronized (stateLock) {
            if (destroyed || nativeClosed || cancellationComplete) return RETRY_COMPLETE;
            if (cancellationUnconfirmed != null)
                throw new IllegalStateException("SSH session cancellation is unconfirmed", cancellationUnconfirmed);
            if (callbackCancelCleanupOnly) return RETRY_CLEANUP_ONLY;
            if (closeInProgress || cancellationInProgress) return RETRY_WAIT;
            if (!cancellationExplicitlyUnstarted)
                throw new IllegalStateException("SSH session cancellation cannot be retried");
            cancellationExplicitlyUnstarted = false;
            cancellationInProgress = true;
        }
        cancelReserved();
        return RETRY_COMPLETE;
    }

    private boolean cleanupOnlyCancellation(Throwable failure) {
        synchronized (stateLock) {
            return callbackCancelCleanupOnly && cancellationUnconfirmed == null
                    && failure instanceof VaultException && "SSH_CALLBACK_NATIVE".equals(((VaultException)failure).code)
                    && failure.getCause() == null && failure.getSuppressed().length == 0;
        }
    }

    /** Fixed session ownership for the terminal; no new authentication authority. */
    static final class TerminalChain {
        static final int CANCEL_CONFIRMED = 0, CANCEL_RETRYABLE = 1, CANCEL_CLEANUP_ONLY = 2,
                CANCEL_WAIT = 3;
        private final List<SshNativeSession> sessions;
        private List<SshNativeSession> retryableCancellation = Collections.emptyList();
        private boolean cancellationCleanupOnly;
        private Throwable cancellationFailure;

        private TerminalChain(List<SshNativeSession> sessions) {
            this.sessions = Collections.unmodifiableList(sessions);
        }

        private SshNativeSession target() { return sessions.get(sessions.size() - 1); }

        ShellStatus status() throws VaultException {
            SshNativeSession target = target();
            ShellStatus result = target.shellStatus();
            if (sessions.size() == 1 || result.phase != ShellPhase.RUNNING) return result;
            for (int index = sessions.size() - 2; index >= 0; index--) {
                SshNativeSession ancestor = sessions.get(index);
                if (!ancestor.established) ancestor.retainJumpTransport();
                else if (!ancestor.authenticationRetired) throw phaseFailure();
            }
            // The first query may observe RUNNING before its confirmation can run.
            result = target.shellStatus();
            if (result.phase == ShellPhase.RUNNING
                    && (!target.jumpAncestorsRetained() || target.shellConfirmationPending
                            || !target.shellOutputPublicationLive()))
                throw phaseFailure();
            return result;
        }

        void step() throws VaultException {
            int last = sessions.size() - 1;
            for (int index = 0; index <= last; index++) {
                SshNativeSession current = sessions.get(index);
                if (index > 0) current.pumpJumpRelay();
                if (index < last) {
                    JumpStatus status = current.jumpStatus();
                    if (status.phase == JumpPhase.REJECTED) throw failure("SSH_SESSION_JUMP_STATUS");
                    // Closed jump channels may still have accepted bytes to drain.
                    if (status.phase != JumpPhase.CLOSED) current.step();
                } else current.step();
                if (index > 0) current.pumpJumpRelay();
            }
        }

        /** Called from any thread; all callbacks are attempted even on failure. */
        void cancel() throws VaultException {
            Throwable first = null;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                try { sessions.get(index).cancel(); }
                catch (VaultException | RuntimeException | Error failure) {
                    if (first == null) first = failure;
                    else if (first != failure) first.addSuppressed(failure);
                }
            }
            if (first instanceof VaultException) throw (VaultException) first;
            if (first instanceof RuntimeException) throw (RuntimeException) first;
            if (first != null) throw (Error) first;
        }

        /** Preserve each checked refusal and never turn another failure into a retry grant. */
        int cancelForTerminal() throws VaultException {
            List<SshNativeSession> retryable = new ArrayList<>();
            Throwable first = null;
            boolean cleanupOnly = false, unknown = false;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                SshNativeSession session = sessions.get(index);
                try { session.cancel(); }
                catch (VaultException | RuntimeException | Error failure) {
                    if (unstartedCancellation(session, failure)) retryable.add(session);
                    else if (session.cleanupOnlyCancellation(failure)) cleanupOnly = true;
                    else unknown = true;
                    if (first == null) first = failure;
                    else if (first != failure) first.addSuppressed(failure);
                }
            }
            if (unknown) { throwCancellationFailure(first); }
            cancellationCleanupOnly = cleanupOnly;
            cancellationFailure = first;
            retryableCancellation = retryable;
            if (!retryable.isEmpty()) return CANCEL_RETRYABLE;
            cancellationFailure = null;
            return cleanupOnly ? CANCEL_CLEANUP_ONLY : CANCEL_CONFIRMED;
        }

        /** Called only by the chain's owner, for the exact sessions that refused to begin. */
        int retryCancellationForTerminal() throws VaultException {
            target().requireCleanupOwnerThread();
            if (retryableCancellation.isEmpty())
                throw new IllegalStateException("SSH session cancellation is not retryable");
            List<SshNativeSession> retryable = new ArrayList<>();
            Throwable failure = null;
            boolean unknown = false, refused = false;
            for (SshNativeSession session : retryableCancellation) {
                try {
                    int status = session.retryExplicitlyUnstarted();
                    if (status == RETRY_WAIT) retryable.add(session);
                    else if (status == RETRY_CLEANUP_ONLY) cancellationCleanupOnly = true;
                } catch (VaultException | RuntimeException | Error error) {
                    if (unstartedCancellation(session, error)) { retryable.add(session); refused = true; }
                    else if (session.cleanupOnlyCancellation(error)) cancellationCleanupOnly = true;
                    else unknown = true;
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
            if (unknown) {
                if (cancellationFailure != null && cancellationFailure != failure) {
                    cancellationFailure.addSuppressed(failure);
                    failure = cancellationFailure;
                }
                throwCancellationFailure(failure);
            }
            retryableCancellation = retryable;
            if (!retryable.isEmpty()) return refused ? CANCEL_RETRYABLE : CANCEL_WAIT;
            cancellationFailure = null;
            return cancellationCleanupOnly ? CANCEL_CLEANUP_ONLY : CANCEL_CONFIRMED;
        }

        private static boolean unstartedCancellation(SshNativeSession session, Throwable failure) {
            return failure instanceof CancellationNotStarted
                    && ((CancellationNotStarted)failure).session == session
                    && failure.getCause() == null && failure.getSuppressed().length == 0;
        }

        private static void throwCancellationFailure(Throwable failure) throws VaultException {
            if (failure instanceof VaultException) throw (VaultException)failure;
            if (failure instanceof RuntimeException) throw (RuntimeException)failure;
            throw (Error)failure;
        }

        /** Stop on the first unconfirmed child/relay close; retry the same owners. */
        void close() throws VaultException {
            for (int index = sessions.size() - 1; index >= 0; index--)
                sessions.get(index).close();
        }
    }

    /** Snapshot only the exact Java/JNI owner chain on its original thread. */
    static TerminalChain terminalChain(SshNativeSession target) throws VaultException {
        if (target == null) throw invalid();
        List<SshNativeSession> reversed = new ArrayList<>();
        IdentityHashMap<SshNativeSession, Boolean> seen = new IdentityHashMap<>();
        SshNativeSession current = target;
        if (current.jumpChildOwner != null) throw phaseFailure();
        while (current != null) {
            current.requireOwnerThread();
            current.requireProtocolState();
            if (seen.put(current, Boolean.TRUE) != null || reversed.size() >= SshRoleContext.MAX_HOPS)
                throw phaseFailure();
            reversed.add(current);
            JumpChildOwner link = current.ownedByJump;
            if (link == null) break;
            SshNativeSession parent = link.parent;
            if (link.unavailable() || !link.callbackAdopted || !link.viewAdopted || link.child != current
                    || link.callback != current.callback || link.shared != current.sharedView
                    || parent == null || parent.jumpChildOwner != link || parent.sharedView != link.shared
                    || parent.ownerThread != current.ownerThread) throw phaseFailure();
            current = parent;
        }
        Collections.reverse(reversed);
        return new TerminalChain(reversed);
    }

    StepResult step() throws VaultException {
        requireOwnerThread();
        enterNative();
        try { return stepInternal(); }
        finally { NATIVE_ENTRY.remove(); }
    }

    /** Fixture-only observation bound to one real child, never a trust or credential grant. */
    static final class SignedNewFixtureReady {
        private final SshNativeSession session;
        private final JumpChildOwner link;
        private final SshNativeCallbackAdapter callback;
        private final long handle, callbackToken, deadline;

        private SignedNewFixtureReady(SshNativeSession session, JumpChildOwner link,
                SshNativeCallbackAdapter callback, long handle, long callbackToken, long deadline) {
            this.session = session;
            this.link = link;
            this.callback = callback;
            this.handle = handle;
            this.callbackToken = callbackToken;
            this.deadline = deadline;
        }

        long deadline() { return deadline; }

        boolean belongsTo(SshNativeSession candidate) { return session == candidate; }

        boolean matches(JumpChildOwner owner, SshManagedKnownHostsProvider.ChildTrustSnapshot source,
                String candidateId) {
            return link == owner && link.trustSource == source && link.candidateId.equals(candidateId);
        }

        void requireLive(SshNativeCallbackAdapter exact) throws VaultException {
            if (exact == null || callback != exact || session.callback != exact)
                throw failure("SSH_HOST_KEY_REVIEW");
            session.requireSignedNewFixtureState(link, handle, callbackToken, deadline);
        }
    }

    /** Called after step returns; missing fixture JNI or an undrained KEX cannot issue a review. */
    SshNativeCallbackAdapter.SignedNewFixtureReview signedNewFixtureReview() throws VaultException {
        requireOwnerThread();
        JumpChildOwner link = ownedByJump;
        long handle;
        synchronized (stateLock) { handle = nativeHandle; }
        long callbackToken = callback.nativeCallbackToken();
        long hopDeadline = link == null ? 0 : link.guardedHopDeadline;
        requireSignedNewFixtureState(link, handle, callbackToken, hopDeadline);
        callback.requireSignedNewFixturePending();
        boolean ready;
        enterNative();
        try {
            ready = Native.provisionalNewFixtureReady(handle, callbackToken);
        } catch (IOException | LinkageError | RuntimeException unavailable) {
            throw failure("SSH_HOST_KEY_REVIEW");
        } finally {
            NATIVE_ENTRY.remove();
        }
        if (!ready) return null;
        SignedNewFixtureReady observation = new SignedNewFixtureReady(this, link, callback,
                handle, callbackToken, hopDeadline);
        observation.requireLive(callback);
        return callback.claimSignedNewFixtureReview(observation);
    }

    /** One bounded fixture turn; bit 0 is idle and bit 1 records a validated extension. */
    int pollSignedNewFixture(SshNativeCallbackAdapter.SignedNewFixtureReview review) throws VaultException {
        requireOwnerThread();
        if (review == null) throw failure("SSH_HOST_KEY_REVIEW");
        review.requireLiveFor(this);
        JumpChildOwner link = ownedByJump;
        if (link == null || link.parent == null) throw failure("SSH_HOST_KEY_REVIEW");
        pumpJumpRelay();
        link.parent.step();
        pumpJumpRelay();
        review.requireLiveFor(this);
        long handle;
        synchronized (stateLock) { handle = nativeHandle; }
        long callbackToken = callback.nativeCallbackToken();
        int observed;
        enterNative();
        try {
            observed = Native.provisionalNewFixturePoll(handle, callbackToken);
        } catch (IOException | LinkageError | RuntimeException unavailable) {
            throw failure("SSH_HOST_KEY_REVIEW");
        } finally {
            NATIVE_ENTRY.remove();
        }
        if ((observed & ~3) != 0) throw failure("SSH_HOST_KEY_REVIEW");
        review.requireLiveFor(this);
        return observed;
    }

    /* No Java state, callback, view or vault monitor is held across the JNI query above. */
    private void requireSignedNewFixtureState(JumpChildOwner link, long handle, long callbackToken,
            long hopDeadline) throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed || cancellationRequested || terminalFailure != null
                    || complete || phase != Phase.KEX_ONLY || nativeHandle != handle || handle <= 0)
                throw failure("SSH_SESSION_CLOSED");
        }
        if (!(nativeBinding instanceof ProductionNativeBinding) || link == null || ownedByJump != link
                || link.child != this || link.callback != callback || link.unavailable()
                || link.parent == null || link.parent.jumpChildOwner != link || link.shared != sharedView
                || link.nativeBinding != nativeBinding || link.callbackClaim != callbackClaim
                || link.view != view || link.token != handle
                || !link.callbackAdopted || !link.viewAdopted || !link.effectFenceRegistered
                || link.trustSource == null || link.trustState == null || !link.shared.hasManagedLease()
                || callbackToken <= 0 || callback.nativeCallbackToken() != callbackToken
                || hopDeadline <= 0 || link.guardedHopDeadline != hopDeadline
                || !link.trustState.cancellation.liveForJumpEffect() || link.trustState.publicationUncertain())
            throw failure("SSH_HOST_KEY_REVIEW");
        link.parent.requireTransportLive();
        if (!view.active()) throw failure("SSH_FILE_VIEW");
        link.trustSource.requireCurrent();
        if (!link.trustState.cancellation.liveForJumpEffect() || link.unavailable())
            throw failure("SSH_HOST_KEY_REVIEW");
    }

    private StepResult stepInternal() throws VaultException {
        synchronized (stateLock) {
            if (nativeClosed || destroyed) throw failure("SSH_SESSION_CLOSED");
            if (cancellationRequested && (cancellationInProgress
                    || cancellationNeedsCompletion || cancellationUnconfirmed != null || callbackCancelCleanupOnly))
                throw failure("SSH_SESSION_CANCELLED");
            if (terminalFailure != null) throw failure(terminalFailure);
            if (phase == Phase.SERVICE_READY
                    || (phase == Phase.FINISHED && shellPhase == ShellPhase.DISABLED
                        && jumpPhase == null)) throw phaseFailure();
            if (complete && phase == Phase.KEX_ONLY) throw failure("SSH_SESSION_COMPLETE");
        }
        boolean explicit = phase != Phase.KEX_ONLY;
        if (explicit) {
            requireTransportLive();
            if (shellPhase != ShellPhase.DISABLED && queryShellStatus().phase.terminal())
                throw phaseFailure();
            if (jumpPhase != null && queryJumpStatus().phase.terminal()) throw phaseFailure();
        }
        if (cancellationRequested) {
            terminalFailure = "SSH_SESSION_CANCELLED";
        } else if (!explicit) {
            try {
                callback.requireNativeSessionLive();
                callback.requireLocalEffectAdmission();
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
                else if (jumpPhase != null) queryJumpStatus();
                else if (phase == Phase.SERVICE_PENDING) queryServiceReady();
                else if (phase == Phase.DISCOVERY_PENDING) queryAuthDiscovery();
                else if (phase == Phase.PASSWORD_PENDING) queryPasswordResult();
                else if (phase == Phase.PRIVATE_KEY_PENDING) queryPrivateKeyResult();
                else if (phase == Phase.KEYBOARD_PENDING) queryKeyboardInteractiveResult();
            }
            return result;
        } catch (VaultException failure) {
            throw failure;
        } catch (IOException | LinkageError | RuntimeException failure) {
            String code = !explicit && failure instanceof HostKeyRejectedException
                    ? ((HostKeyRejectedException)failure).code : "SSH_SESSION_STEP";
            // Current cancellation, expiry and view revocation still outrank diagnostics.
            throw protocolNativeFailure(code);
        } catch (Error failure) {
            if (explicit) recordProtocolError("SSH_SESSION_STEP");
            throw failure;
        }
    }

    void beginUserauthService() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            long generation = captureFreshSelectedEffect();
            requireProtocolState();
            if (!complete || phase != Phase.KEX_ONLY) throw phaseFailure();
            requireProtocolLive();
            try {
                if (generation == 0) nativeBinding.beginUserauthService(nativeHandle);
                else nativeBinding.beginUserauthServiceGuarded(nativeHandle, generation);
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

    void beginAuthDiscovery() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            long generation = captureFreshSelectedEffect();
            requireProtocolState();
            if (phase != Phase.SERVICE_READY || discoveryResult != AuthDiscoveryResult.NOT_STARTED)
                throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            try {
                if (generation == 0) nativeBinding.beginAuthDiscovery(nativeHandle);
                else nativeBinding.beginAuthDiscoveryGuarded(nativeHandle, generation);
                requireProtocolLive();
                discoveryResult = AuthDiscoveryResult.PENDING;
                phase = Phase.DISCOVERY_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_AUTH_DISCOVERY");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_SESSION_AUTH_DISCOVERY";
                throw nativeFailure;
            }
        } finally { NATIVE_ENTRY.remove(); }
    }

    AuthDiscoveryStatus authDiscoveryResult() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (discoveryResult == AuthDiscoveryResult.NOT_STARTED
                    || (phase != Phase.DISCOVERY_PENDING && phase != Phase.SERVICE_READY
                            && !(phase == Phase.FINISHED && discoveryResult == AuthDiscoveryResult.NONE_SUCCESS)))
                throw phaseFailure();
            requireProtocolLive();
            return queryAuthDiscovery();
        } finally { NATIVE_ENTRY.remove(); }
    }

    private AuthDiscoveryStatus queryAuthDiscovery() throws VaultException {
        try {
            AuthDiscoveryStatus result = new AuthDiscoveryStatus(nativeBinding.authDiscoveryResult(nativeHandle));
            requireProtocolLive();
            if (phase == Phase.DISCOVERY_PENDING) {
                if (result.result == AuthDiscoveryResult.NOT_STARTED)
                    throw new IOException("SSH discovery state regressed");
                discoveryResult = result.result;
                discoveryMethods = result.methods;
                if (result.result == AuthDiscoveryResult.NONE_SUCCESS) phase = Phase.FINISHED;
                else if (result.result != AuthDiscoveryResult.PENDING) phase = Phase.SERVICE_READY;
            } else if (result.result != discoveryResult || result.methods != discoveryMethods) {
                throw new IOException("SSH discovery result changed");
            }
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_AUTH_DISCOVERY");
        } catch (Error nativeFailure) {
            recordProtocolError("SSH_SESSION_AUTH_DISCOVERY");
            throw nativeFailure;
        }
    }

    private boolean discoveryAllows(int method) {
        return discoveryResult == AuthDiscoveryResult.NOT_STARTED
                || (discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)
                && (discoveryMethods & method) != 0;
    }

    int authContinuationMethods() throws VaultException {
        requireOwnerThread(); enterNative();
        try {
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.FINISHED || !authenticationCanContinue()) throw phaseFailure();
            callback.requireAutomaticSelection();
            try {
                int methods = nativeBinding.authContinuationMethods(nativeHandle);
                requireProtocolLive();
                if (methods < 0 || methods > 15) throw new IOException("invalid SSH continuation methods");
                return methods;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_MFA_AUTH");
            } catch (Error nativeFailure) { terminalFailure = "SSH_MFA_AUTH"; throw nativeFailure; }
        } finally { NATIVE_ENTRY.remove(); }
    }

    private boolean authenticationPartial() {
        return keyboardInteractiveAuthentication ? keyboardResult == KeyboardInteractiveAuthResult.PARTIAL
                : privateKeyAuthentication ? privateKeyResult == PrivateKeyAuthResult.PARTIAL
                : authenticationResult == PasswordAuthResult.PARTIAL;
    }

    private boolean authenticationRejected() {
        return keyboardInteractiveAuthentication ? keyboardResult == KeyboardInteractiveAuthResult.REJECTED
                : privateKeyAuthentication ? privateKeyResult == PrivateKeyAuthResult.REJECTED
                : authenticationResult == PasswordAuthResult.REJECTED;
    }

    private boolean authenticationCanContinue() {
        return authenticationPartial() || authenticationRejected();
    }

    void beginPasswordAuth() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireFreshEffectGuard();
            requireProtocolState();
            if (phase != Phase.SERVICE_READY || !discoveryAllows(AuthDiscoveryStatus.PASSWORD)) throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            if (!callback.authenticationSelectionAllows(AuthDiscoveryStatus.PASSWORD,
                    discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)) throw phaseFailure();
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

    /** One prompt for the exact saved password after a server method discovery. */
    Object selectedPasswordPromptStage() throws VaultException {
        requireOwnerThread();
        requireSelectedPasswordMetadata();
        if (selectedPasswordPromptIssued || expectedSelectionReceipt == null || phase != Phase.SERVICE_READY
                || (discoveryResult != AuthDiscoveryResult.METHODS && discoveryResult != AuthDiscoveryResult.PARTIAL)
                || (discoveryMethods & AuthDiscoveryStatus.PASSWORD) == 0) throw phaseFailure();
        callback.requirePlainSavedPasswordSelection();
        selectedPasswordPromptIssued = true;
        selectedPasswordPromptStage = new Object();
        return selectedPasswordPromptStage;
    }

    /** Metadata only; the UI response thread does not enter native code. */
    void requireSelectedPasswordPromptStageLive(Object stage) throws VaultException {
        requireSelectedPasswordMetadata();
        if (stage == null || selectedPasswordPromptStage != stage || phase != Phase.SERVICE_READY
                || (discoveryResult != AuthDiscoveryResult.METHODS && discoveryResult != AuthDiscoveryResult.PARTIAL)
                || (discoveryMethods & AuthDiscoveryStatus.PASSWORD) == 0) throw phaseFailure();
        callback.requirePlainSavedPasswordSelection();
    }

    void pollSelectedPasswordPrompt(Object stage) throws VaultException {
        requireOwnerThread(); enterNative();
        byte[] receipt = null;
        try {
            requireSelectedPasswordPromptStageLive(stage);
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            receipt = expectedSelectionReceipt.clone();
            try {
                nativeBinding.pollSelectedPasswordPrompt(nativeHandle, receipt);
                requireSelectedPasswordPromptStageLive(stage);
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_STEP");
            } catch (Error nativeFailure) { terminalFailure = "SSH_SESSION_STEP"; throw nativeFailure; }
        } finally { VaultCodec.wipe(receipt); NATIVE_ENTRY.remove(); }
    }

    /** The stage can only dispatch the already selected native saved-password callback once. */
    void beginSelectedSavedPassword(Object stage) throws VaultException {
        requireOwnerThread(); enterNative();
        try {
            requireSelectedPasswordPromptStageLive(stage);
            long generation = captureFreshSelectedEffect();
            requireSelectedPasswordPromptStageLive(stage);
            selectedPasswordPromptStage = null;
            try {
                if (generation == 0) nativeBinding.beginPasswordAuth(nativeHandle);
                else nativeBinding.beginPasswordAuthGuarded(nativeHandle, generation);
                requireProtocolLive();
                phase = Phase.PASSWORD_PENDING;
                authenticationResult = PasswordAuthResult.PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_AUTH");
            } catch (Error nativeFailure) { terminalFailure = "SSH_SESSION_AUTH"; throw nativeFailure; }
        } finally { NATIVE_ENTRY.remove(); }
    }

    private void requireSelectedPasswordMetadata() throws VaultException {
        requireProtocolState();
        requireProtocolLive();
        if (callback.hasChildTrustSource()) {
            if (ownedByJump == null) throw failure("SSH_JUMP_EFFECT_GUARD");
            ownedByJump.requireProtectedMetadata();
        }
    }

    Object passwordPromptStage() throws VaultException {
        requireOwnerThread();
        requireFreshEffectGuard();
        requireProtocolState(); requireProtocolLive();
        boolean initial = phase == Phase.SERVICE_READY
                && (discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)
                && (discoveryMethods & AuthDiscoveryStatus.PASSWORD) != 0;
        boolean continuation = phase == Phase.FINISHED && authenticationCanContinue() && authenticationStage < 4;
        if (passwordPromptIssued || expectedSelectionReceipt == null || (!initial && !continuation)) throw phaseFailure();
        callback.requireDiscoveredPasswordSelection();
        if (continuation && (authContinuationMethods() & AuthDiscoveryStatus.PASSWORD) == 0) throw phaseFailure();
        if (callback.kexProof() == null || callback.hostProof() == null) throw SshAuthenticationSelection.invalid();
        passwordPromptIssued = true;
        passwordPromptStage = new Object();
        return passwordPromptStage;
    }

    /** Metadata-only; safe on the event publisher thread. */
    void requirePasswordPromptStageLive(Object stage) throws VaultException {
        requireFreshEffectGuard();
        requireProtocolState(); requireProtocolLive();
        if (stage == null || passwordPromptStage != stage
                || !(phase == Phase.SERVICE_READY || phase == Phase.FINISHED && authenticationCanContinue())) throw phaseFailure();
    }

    int passwordPromptMethods(Object stage) throws VaultException {
        requireOwnerThread(); requirePasswordPromptStageLive(stage);
        return phase == Phase.FINISHED ? authContinuationMethods() : authDiscoveryResult().methods;
    }

    void pollPasswordPrompt(Object stage) throws VaultException {
        pollAuthPrompt(stage, false);
    }

    Object privateKeyPromptStage() throws VaultException {
        requireOwnerThread(); requireFreshEffectGuard(); requireProtocolState(); requireProtocolLive();
        boolean initial = phase == Phase.SERVICE_READY
                && (discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)
                && (discoveryMethods & AuthDiscoveryStatus.PUBLIC_KEY) != 0;
        boolean continuation = phase == Phase.FINISHED && authenticationCanContinue() && authenticationStage < 4;
        if (privateKeyPromptIssued || expectedSelectionReceipt == null || (!initial && !continuation)) throw phaseFailure();
        callback.requireAutomaticKeySelection();
        if (continuation && (authContinuationMethods() & AuthDiscoveryStatus.PUBLIC_KEY) == 0) throw phaseFailure();
        if (callback.kexProof() == null || callback.hostProof() == null) throw SshAuthenticationSelection.invalid();
        privateKeyPromptIssued = true; privateKeyPromptStage = new Object();
        return privateKeyPromptStage;
    }

    void requirePrivateKeyPromptStageLive(Object stage) throws VaultException {
        requireFreshEffectGuard();
        requireProtocolState(); requireProtocolLive();
        if (stage == null || privateKeyPromptStage != stage
                || !(phase == Phase.SERVICE_READY || phase == Phase.FINISHED && authenticationCanContinue())) throw phaseFailure();
    }

    int privateKeyPromptMethods(Object stage) throws VaultException {
        requireOwnerThread(); requirePrivateKeyPromptStageLive(stage);
        return phase == Phase.FINISHED ? authContinuationMethods() : authDiscoveryResult().methods;
    }

    void pollPrivateKeyPrompt(Object stage) throws VaultException {
        pollAuthPrompt(stage, true);
    }

    private void pollAuthPrompt(Object stage, boolean key) throws VaultException {
        requireOwnerThread(); enterNative();
        byte[] expected = null;
        try {
            if (key) requirePrivateKeyPromptStageLive(stage); else requirePasswordPromptStageLive(stage);
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            expected = expectedSelectionReceipt.clone();
            try {
                nativeBinding.pollAuthPrompt(nativeHandle, expected,
                        key ? AuthDiscoveryStatus.PUBLIC_KEY : AuthDiscoveryStatus.PASSWORD);
                if (key) requirePrivateKeyPromptStageLive(stage); else requirePasswordPromptStageLive(stage);
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_STEP");
            } catch (Error nativeFailure) { terminalFailure = "SSH_SESSION_STEP"; throw nativeFailure; }
        } finally { VaultCodec.wipe(expected); NATIVE_ENTRY.remove(); }
    }

    void finishPrivateKeyPrompt(Object stage) throws VaultException {
        requireOwnerThread(); requirePrivateKeyPromptStageLive(stage); privateKeyPromptStage = null;
    }

    void beginDiscoveredPassword(Object stage, byte[] password) throws VaultException {
        requireOwnerThread();
        enterNative();
        byte[] expected = null, received = null, owned = null;
        try {
            requireFreshEffectGuard();
            requireProtocolState(); requireProtocolLive();
            if (expectedSelectionReceipt == null || authenticationStage >= 4) throw phaseFailure();
            requirePasswordPromptStageLive(stage);
            SshSavedPassword.validateSecret(password);
            int original = callback.requireDiscoveredPasswordSelection();
            SshKexProof kex = callback.kexProof();
            SshHostKeyAuthority.Proof host = callback.hostProof();
            if (kex == null || host == null) throw SshAuthenticationSelection.invalid();
            expected = expectedSelectionReceipt.clone();
            callback.requireLocalEffectAdmission();
            owned = password.clone();
            passwordPromptStage = null;
            try {
                received = nativeBinding.beginDiscoveredPassword(nativeHandle, expected, owned);
                requireProtocolLive();
                validateAuthStageReceipt(received, 2, authenticationStage + 1, original, 1, kex, host);
                authenticationStage++;
                keyboardInteractiveAuthentication = false; keyboardStage = null;
                privateKeyAuthentication = false;
                authenticationResult = PasswordAuthResult.PENDING;
                phase = Phase.PASSWORD_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_SESSION_AUTH");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_SESSION_AUTH"; throw nativeFailure;
            }
        } finally {
            VaultCodec.wipe(expected); VaultCodec.wipe(received); VaultCodec.wipe(owned); NATIVE_ENTRY.remove();
        }
    }

    PasswordAuthResult passwordAuthResult() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (privateKeyAuthentication || keyboardInteractiveAuthentication
                    || discoveryResult == AuthDiscoveryResult.NONE_SUCCESS) throw phaseFailure();
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
            requireFreshEffectGuard();
            requireProtocolState();
            if (phase != Phase.SERVICE_READY || privateKeyPromptStage != null
                    || !discoveryAllows(AuthDiscoveryStatus.PUBLIC_KEY)) throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            if (!callback.authenticationSelectionAllows(AuthDiscoveryStatus.PUBLIC_KEY,
                    discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)) throw phaseFailure();
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

    void continuePrivateKeyAuth() throws VaultException {
        requireOwnerThread(); enterNative();
        byte[] expected = null, received = null;
        try {
            requireFreshEffectGuard();
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.FINISHED || !authenticationCanContinue() || authenticationStage >= 4 || privateKeyPromptStage != null
                    || expectedSelectionReceipt == null) throw phaseFailure();
            callback.requireAutomaticKeySelection();
            SshKexProof kex = callback.kexProof(); SshHostKeyAuthority.Proof host = callback.hostProof();
            if (kex == null || host == null) throw SshAuthenticationSelection.invalid();
            expected = expectedSelectionReceipt.clone();
            try {
                received = nativeBinding.continuePrivateKeyAuth(nativeHandle, expected);
                requireProtocolLive();
                validateAuthStageReceipt(received, 2, authenticationStage + 1, 4, 4, kex, host);
                authenticationStage++;
                keyboardInteractiveAuthentication = false; keyboardStage = null;
                privateKeyAuthentication = true; privateKeyResult = PrivateKeyAuthResult.PENDING;
                phase = Phase.PRIVATE_KEY_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_MFA_AUTH");
            } catch (Error nativeFailure) { terminalFailure = "SSH_MFA_AUTH"; throw nativeFailure; }
        } finally { VaultCodec.wipe(expected); VaultCodec.wipe(received); NATIVE_ENTRY.remove(); }
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

    void beginKeyboardInteractiveAuth() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireFreshEffectGuard();
            requireProtocolState();
            if (phase != Phase.SERVICE_READY || !discoveryAllows(AuthDiscoveryStatus.KEYBOARD_INTERACTIVE)) throw phaseFailure();
            if (expectedSelectionReceipt == null) throw SshAuthenticationSelection.invalid();
            requireProtocolLive();
            if (!callback.authenticationSelectionAllows(AuthDiscoveryStatus.KEYBOARD_INTERACTIVE,
                    discoveryResult == AuthDiscoveryResult.METHODS || discoveryResult == AuthDiscoveryResult.PARTIAL)) throw phaseFailure();
            try {
                nativeBinding.beginKeyboardInteractiveAuth(nativeHandle);
                requireProtocolLive();
                keyboardStage = new Object();
                keyboardInteractiveAuthentication = true;
                keyboardResult = KeyboardInteractiveAuthResult.PENDING;
                phase = Phase.KEYBOARD_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_KEYBOARD_AUTH");
            } catch (Error nativeFailure) { terminalFailure = "SSH_KEYBOARD_AUTH"; throw nativeFailure; }
        } finally { NATIVE_ENTRY.remove(); }
    }

    void continueKeyboardInteractiveAuth() throws VaultException {
        requireOwnerThread();
        enterNative();
        byte[] expected = null, received = null;
        try {
            requireFreshEffectGuard();
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.FINISHED || keyboardInteractiveAuthentication || keyboardStage != null
                    || expectedSelectionReceipt == null) throw phaseFailure();
            int continuation = callback.keyboardContinuationProof();
            int version = (continuation & 0x100) != 0 ? 2 : 1;
            if (!authenticationPartial() && !(version == 2 && authenticationRejected())) throw phaseFailure();
            int stage = authenticationStage + 1;
            if (stage > 4) throw phaseFailure();
            SshKexProof kex = callback.kexProof();
            SshHostKeyAuthority.Proof host = callback.hostProof();
            if (kex == null || host == null) throw SshAuthenticationSelection.invalid();
            expected = expectedSelectionReceipt.clone();
            try {
                received = nativeBinding.continueKeyboardInteractiveAuth(nativeHandle, expected);
                requireProtocolLive();
                validateAuthStageReceipt(received, version, stage, continuation & 0xff, 3, kex, host);
                authenticationStage = stage;
                keyboardInteractiveAuthentication = true;
                keyboardStage = new Object();
                keyboardResult = KeyboardInteractiveAuthResult.PENDING;
                phase = Phase.KEYBOARD_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_MFA_AUTH");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_MFA_AUTH"; throw nativeFailure;
            }
        } finally {
            VaultCodec.wipe(expected); VaultCodec.wipe(received); NATIVE_ENTRY.remove();
        }
    }

    void beginDiscoveredKeyboard() throws VaultException {
        requireOwnerThread();
        enterNative();
        byte[] expected = null, received = null;
        try {
            requireFreshEffectGuard();
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.SERVICE_READY || keyboardStage != null || expectedSelectionReceipt == null
                    || (discoveryResult != AuthDiscoveryResult.METHODS && discoveryResult != AuthDiscoveryResult.PARTIAL)
                    || (discoveryMethods & AuthDiscoveryStatus.KEYBOARD_INTERACTIVE) == 0) throw phaseFailure();
            callback.requireDiscoveredKeyboardSelection();
            SshKexProof kex = callback.kexProof();
            SshHostKeyAuthority.Proof host = callback.hostProof();
            if (kex == null || host == null) throw SshAuthenticationSelection.invalid();
            expected = expectedSelectionReceipt.clone();
            try {
                received = nativeBinding.beginDiscoveredKeyboard(nativeHandle, expected);
                requireProtocolLive();
                validateAuthStageReceipt(received, 2, 2, 4, 3, kex, host);
                authenticationStage = 2;
                keyboardInteractiveAuthentication = true;
                keyboardStage = new Object();
                keyboardResult = KeyboardInteractiveAuthResult.PENDING;
                phase = Phase.KEYBOARD_PENDING;
            } catch (IOException | LinkageError | RuntimeException nativeFailure) {
                throw protocolNativeFailure("SSH_KEYBOARD_AUTH");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_KEYBOARD_AUTH"; throw nativeFailure;
            }
        } finally { VaultCodec.wipe(expected); VaultCodec.wipe(received); NATIVE_ENTRY.remove(); }
    }

    private void validateAuthStageReceipt(byte[] received, int version, int stage, int original, int active,
            SshKexProof kex, SshHostKeyAuthority.Proof host) throws IOException {
        if (callback.kexProof() != kex || callback.hostProof() != host || received == null || received.length != 92)
            throw new IOException("SSH authentication stage does not match");
        byte[] plan = null, digest = null, actualDigest = null;
        try {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(received);
            if (buffer.getInt() != 0x43534150 || buffer.getShort() != version || buffer.getShort() != 0
                    || buffer.getInt() != stage || buffer.getInt() != original || buffer.getInt() != active)
                throw new IOException("SSH authentication stage does not match");
            plan = new byte[32]; buffer.get(plan);
            long cycle = buffer.getLong();
            digest = new byte[32]; buffer.get(digest); actualDigest = kex.sessionIdDigest();
            if (!MessageDigest.isEqual(plan, expectedSelectionReceipt) || cycle != kex.cycle
                    || !MessageDigest.isEqual(digest, actualDigest) || buffer.hasRemaining())
                throw new IOException("SSH authentication stage does not match");
        } finally { VaultCodec.wipe(plan); VaultCodec.wipe(digest); VaultCodec.wipe(actualDigest); }
    }

    Object keyboardStage() throws VaultException {
        requireOwnerThread();
        Object stage = keyboardStage;
        requireKeyboardStageLive(stage);
        return stage;
    }

    /** Metadata-only guard is safe for the event publisher; native entry remains owner-only. */
    void requireKeyboardStageLive(Object stage) throws VaultException {
        requireFreshEffectGuard();
        requireProtocolState(); requireProtocolLive();
        if (stage == null || keyboardStage != stage || !keyboardInteractiveAuthentication
                || phase != Phase.KEYBOARD_PENDING) throw phaseFailure();
    }

    KeyboardInteractiveAuthResult keyboardInteractiveAuthResult() throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            requireProtocolState();
            if (!keyboardInteractiveAuthentication || phase != Phase.KEYBOARD_PENDING && phase != Phase.FINISHED)
                throw phaseFailure();
            requireProtocolLive();
            return queryKeyboardInteractiveResult();
        } finally { NATIVE_ENTRY.remove(); }
    }

    private KeyboardInteractiveAuthResult queryKeyboardInteractiveResult() throws VaultException {
        try {
            KeyboardInteractiveAuthResult result = KeyboardInteractiveAuthResult.fromNative(
                    nativeBinding.keyboardInteractiveAuthResult(nativeHandle));
            requireProtocolLive();
            if (phase == Phase.KEYBOARD_PENDING) {
                if (result == KeyboardInteractiveAuthResult.NOT_STARTED
                        || keyboardResult == KeyboardInteractiveAuthResult.CHALLENGE
                        && (result == KeyboardInteractiveAuthResult.PENDING || result == KeyboardInteractiveAuthResult.SUCCESS))
                    throw new IOException("SSH keyboard-interactive state regressed");
                keyboardResult = result;
                if (result != KeyboardInteractiveAuthResult.PENDING && result != KeyboardInteractiveAuthResult.CHALLENGE) {
                    keyboardChallenge = null;
                    phase = Phase.FINISHED;
                }
            } else if (phase != Phase.FINISHED || result != keyboardResult) {
                throw new IOException("SSH keyboard-interactive state regressed");
            }
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_KEYBOARD_AUTH");
        } catch (Error nativeFailure) {
            terminalFailure = "SSH_KEYBOARD_AUTH";
            throw nativeFailure;
        }
    }

    SshKeyboardChallenge keyboardInteractiveChallenge() throws VaultException {
        requireOwnerThread();
        enterNative();
        byte[] wire = null;
        try {
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.KEYBOARD_PENDING || keyboardResult != KeyboardInteractiveAuthResult.CHALLENGE)
                throw phaseFailure();
            if (keyboardChallenge != null) return keyboardChallenge;
            try {
                wire = nativeBinding.keyboardInteractiveChallenge(nativeHandle);
                requireProtocolLive();
                SshKeyboardChallenge challenge = SshKeyboardChallenge.decode(wire);
                if (challenge.round != keyboardRound + 1) throw new IOException("SSH challenge round regressed");
                keyboardRound = challenge.round;
                keyboardChallenge = challenge;
                return challenge;
            } catch (IOException | LinkageError | RuntimeException malformed) {
                throw protocolNativeFailure("SSH_KEYBOARD_CHALLENGE");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_KEYBOARD_CHALLENGE";
                throw nativeFailure;
            }
        } finally { VaultCodec.wipe(wire); NATIVE_ENTRY.remove(); }
    }

    void respondKeyboardInteractive(SshKeyboardChallenge challenge, byte[] response) throws VaultException {
        requireOwnerThread();
        enterNative();
        byte[] owned = null;
        try {
            requireFreshEffectGuard();
            requireProtocolState(); requireProtocolLive();
            if (phase != Phase.KEYBOARD_PENDING || keyboardResult != KeyboardInteractiveAuthResult.CHALLENGE
                    || challenge == null || keyboardChallenge != challenge) throw phaseFailure();
            try {
                challenge.requireResponse(response);
                callback.requireLocalEffectAdmission();
                owned = response.clone();
                nativeBinding.respondKeyboardInteractive(nativeHandle, owned);
                requireProtocolLive();
                keyboardChallenge = null;
                keyboardResult = KeyboardInteractiveAuthResult.PENDING;
            } catch (IOException | LinkageError | RuntimeException malformed) {
                throw protocolNativeFailure("SSH_KEYBOARD_CHALLENGE");
            } catch (Error nativeFailure) {
                terminalFailure = "SSH_KEYBOARD_CHALLENGE";
                throw nativeFailure;
            }
        } finally { VaultCodec.wipe(owned); NATIVE_ENTRY.remove(); }
    }

    private boolean authenticationSucceeded() {
        return phase == Phase.FINISHED && (discoveryResult == AuthDiscoveryResult.NONE_SUCCESS
                || (keyboardInteractiveAuthentication
                ? keyboardResult == KeyboardInteractiveAuthResult.SUCCESS
                : privateKeyAuthentication ? privateKeyResult == PrivateKeyAuthResult.SUCCESS
                : authenticationResult == PasswordAuthResult.SUCCESS));
    }

    /** One-shot factory assembly admission; native still independently checks USERAUTH_SUCCESS. */
    static final class JumpAdmission {
        private final SshNativeSession parent;
        private final SshNativeRoleBootstrap.Issued previous;
        private final SshNativeRoleBootstrap.Issued next;
        private final SshSharedPublicView shared;
        private final SshRoleContext.PublicView view;
        private boolean consumed;

        private JumpAdmission(SshNativeSession parent, SshNativeRoleBootstrap.Issued previous,
                SshNativeRoleBootstrap.Issued next, SshSharedPublicView shared,
                SshRoleContext.PublicView view) {
            this.parent = parent; this.previous = previous; this.next = next;
            this.shared = shared; this.view = view;
        }

        void requireTrustParent(SshPasswordConnector.TrustCommitState state,
                SshNativeRoleBootstrap.Issued actualPrevious) throws VaultException {
            parent.requireOwnerThread();
            if (state == null || actualPrevious != previous || parent.jumpAdmission != this || consumed)
                throw phaseFailure();
            parent.requireProtocolState();
            parent.requireTransportLive();
            SshManagedKnownHostsProvider provider = state.providerFor(actualPrevious);
            if (!parent.callback.ownsIssued(actualPrevious) || !parent.callback.ownsTrustProvider(provider))
                throw phaseFailure();
        }

        void requireFixture() throws VaultException {
            parent.requireOwnerThread();
            if (shared == null || shared.hasManagedLease() || parent.nativeBinding instanceof ProductionNativeBinding)
                throw failure("SSH_JUMP_EFFECT_GUARD");
        }

        void consume(SshNativeRoleBootstrap.Issued actualPrevious, SshNativeRoleBootstrap.Issued actualNext,
                SshRoleContext.PublicView actualView) throws VaultException {
            parent.requireOwnerThread();
            if (actualPrevious != previous || actualNext != next || actualView != view || consumed
                    || parent.jumpAdmission != this) throw phaseFailure();
            enterNative();
            try {
                parent.requireJumpAdmissionLive(this);
                synchronized (parent.stateLock) {
                    if (parent.nativeClosed || parent.destroyed) throw failure("SSH_SESSION_CLOSED");
                    if (parent.terminalFailure != null) throw failure(parent.terminalFailure);
                    if (parent.cancellationRequested) throw failure("SSH_SESSION_CANCELLED");
                    if (consumed) throw phaseFailure();
                    consumed = true;
                }
            } finally { NATIVE_ENTRY.remove(); }
        }
    }

    JumpAdmission issueJumpAdmission(SshNativeRoleBootstrap.Issued previous,
            SshNativeRoleBootstrap.Issued next, SshSharedPublicView shared,
            SshRoleContext.PublicView originalView) throws VaultException {
        requireOwnerThread();
        enterNative();
        try {
            if (jumpAdmission != null) throw phaseFailure();
            JumpAdmission result = new JumpAdmission(this, previous, next, shared, originalView);
            requireJumpAdmissionLive(result);
            jumpAdmission = result;
            return result;
        } finally { NATIVE_ENTRY.remove(); }
    }

    private void requireJumpAdmissionLive(JumpAdmission admission) throws VaultException {
        SshNativeRoleBootstrap.Issued previous = admission.previous, next = admission.next;
        SshRoleContext.PublicView originalView = admission.view;
        if (admission.parent != this || previous == null || next == null || originalView == null
                || admission.shared == null || sharedView != admission.shared
                || !callback.ownsIssued(previous) || previous.token <= 0 || next.token <= 0
                || previous.token == next.token || previous.registry == null
                || previous.registry != next.registry || previous.role == null || next.role == null
                || previous.ownerUid != next.ownerUid || previous.role.program != SshRoleContext.Program.JUMP
                || previous.role.identityVersion != SshRoleContext.IDENTITY_V2
                || next.role.identityVersion != previous.role.identityVersion
                || next.role.hop != previous.role.hop + 1 || next.role.ownerPid != previous.role.ownerPid
                || next.role.deadline != previous.role.deadline
                || !next.role.connectionId.equals(previous.role.connectionId)
                || !next.role.chainIdentity.equals(previous.role.chainIdentity)
                || !previous.role.ownsPublicView(originalView) || !next.role.ownsPublicView(originalView)
                || !callback.ownsPublicView(originalView) || !originalView.active()
                || !authenticationSucceeded() || shellPhase != ShellPhase.DISABLED || jumpPhase != null
                || jumpChildOwner != null || expectedSelectionReceipt == null || established)
            throw phaseFailure();
        if (previous.role.hop == 0 ? ownedByJump != null
                : ownedByJump == null || ownedByJump.unavailable() || ownedByJump.child != this
                    || ownedByJump.parent.jumpChildOwner != ownedByJump || ownedByJump.callback != callback
                    || ownedByJump.shared != admission.shared || !ownedByJump.callbackAdopted
                    || !ownedByJump.viewAdopted) throw phaseFailure();
        previous.registry.requireOwned(previous.role);
        next.registry.requireOwned(next.role);
        byte[] before = null, after = null;
        try {
            before = previous.role.connectionBinding();
            after = next.role.connectionBinding();
            if (!MessageDigest.isEqual(before, after)) throw phaseFailure();
        } finally { VaultCodec.wipe(before); VaultCodec.wipe(after); }
        TerminalChain chain = terminalChain(this);
        if (chain.sessions.size() != previous.role.hop + 1) throw phaseFailure();
        for (int index = 0; index < chain.sessions.size(); index++) {
            SshNativeSession ancestor = chain.sessions.get(index);
            if (ancestor.sharedView != admission.shared || !ancestor.callback.ownsPublicView(originalView)
                    || ancestor.callback.roleHop() != index) throw phaseFailure();
            ancestor.requireTransportLive();
        }
    }

    void configureServerAlive(int interval, int countMax) throws VaultException {
        shellEntry("SSH_SESSION_KEEPALIVE", () -> {
            if (!authenticationSucceeded() || shellPhase != ShellPhase.DISABLED
                    || jumpPhase != null || expectedSelectionReceipt == null || serverAliveConfigured) throw phaseFailure();
            if (interval < 0 || countMax < 0) throw invalid();
            nativeBinding.configureServerAlive(nativeHandle, interval, countMax);
            requireTransportLive();
            serverAliveConfigured = true;
            return null;
        });
    }

    void configureNoPty() throws VaultException {
        shellEntry("SSH_SESSION_SHELL", () -> {
            if (!authenticationSucceeded() || shellPhase != ShellPhase.DISABLED
                    || jumpPhase != null || expectedSelectionReceipt == null || noPtyConfigured) throw phaseFailure();
            nativeBinding.configureNoPty(nativeHandle);
            requireTransportLive();
            noPtyConfigured = true;
            return null;
        });
    }

    void beginShell(int columns, int rows) throws VaultException {
        shellEntry("SSH_SESSION_SHELL", () -> {
            if (!authenticationSucceeded() || shellPhase != ShellPhase.DISABLED
                    || jumpPhase != null || expectedSelectionReceipt == null) throw phaseFailure();
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

    /** Begin a channel only for the exact child owner and its frozen role. */
    void beginJump(JumpChildOwner next) throws VaultException {
        if (next != null && next.effectFenceRegistered) throw failure("SSH_JUMP_EFFECT_GUARD");
        beginJumpInternal(next, 0);
    }

    void beginJump(JumpChildOwner next, long hopDeadline) throws VaultException {
        if (next == null || !next.effectFenceRegistered) throw failure("SSH_JUMP_EFFECT_GUARD");
        beginJumpInternal(next, hopDeadline);
    }

    private void beginJumpInternal(JumpChildOwner next, long hopDeadline) throws VaultException {
        shellEntry("SSH_SESSION_JUMP", () -> {
            if (!authenticationSucceeded() || shellPhase != ShellPhase.DISABLED || jumpPhase != null
                    || expectedSelectionReceipt == null || next == null || next != jumpChildOwner
                    || next.parent != this || next.unavailable() || next.attempted) throw phaseFailure();
            if (jumpAdmission != null && (!jumpAdmission.consumed
                    || !next.callback.ownsIssued(jumpAdmission.next))) throw phaseFailure();
            next.callback.requireAuthenticationSelectionLive();
            if (!next.view.active()) throw failure("SSH_FILE_VIEW");
            if (hopDeadline == 0) nativeBinding.beginJump(nativeHandle, next.callback.roleToken());
            else {
                long generation = next.captureProtectedJumpOpen(hopDeadline);
                nativeBinding.beginJumpGuarded(nativeHandle, next.callback.roleToken(), hopDeadline, generation);
                next.guardedHopDeadline = hopDeadline;
            }
            requireTransportLive();
            jumpPhase = JumpPhase.OPENING;
            return null;
        });
    }

    JumpStatus jumpStatus() throws VaultException {
        return shellEntry("SSH_SESSION_JUMP_STATUS", this::queryJumpStatus);
    }

    private JumpStatus queryJumpStatus() throws VaultException {
        if (jumpPhase == null) throw phaseFailure();
        try {
            JumpStatus result = new JumpStatus(nativeBinding.jumpStatus(nativeHandle));
            requireTransportLive();
            if (jumpPhase.terminal() && result.phase != jumpPhase
                    || jumpPhase == JumpPhase.OPEN && result.phase == JumpPhase.OPENING
                    || lastJumpStatus != null && (lastJumpStatus.remoteEof && !result.remoteEof
                            || lastJumpStatus.localEof && !result.localEof))
                throw new IOException("SSH jump status regressed");
            jumpPhase = result.phase;
            lastJumpStatus = result;
            return result;
        } catch (IOException | LinkageError | RuntimeException nativeFailure) {
            throw protocolNativeFailure("SSH_SESSION_JUMP_STATUS");
        } catch (Error nativeFailure) {
            recordProtocolError("SSH_SESSION_JUMP_STATUS");
            throw nativeFailure;
        }
    }

    /** Pump only the relay owned by this published child session. */
    void pumpJumpRelay() throws VaultException {
        shellEntry("SSH_SESSION_JUMP_RELAY", () -> {
            if (ownedByJump == null || ownedByJump.unavailable() || !ownedByJump.callbackAdopted
                    || !ownedByJump.viewAdopted) throw phaseFailure();
            nativeBinding.pumpJumpRelay(nativeHandle);
            requireTransportLive();
            return null;
        });
    }

    /** Retire this jump's auth/view only after its exact child is established. */
    void retainJumpTransport() throws VaultException {
        shellEntry("SSH_SESSION_JUMP_RETAIN", () -> {
            JumpChildOwner next = jumpChildOwner;
            if (jumpPhase == null || established || next == null || next.unavailable()
                    || next.child == null || !next.callbackAdopted || !next.viewAdopted
                    || !next.child.established || next.child.closed()) throw phaseFailure();
            JumpStatus status = queryJumpStatus();
            if (status.phase != JumpPhase.OPEN || status.remoteEof || status.localEof)
                throw phaseFailure();
            if (!next.child.readyForJumpRetention()) throw phaseFailure();
            nativeBinding.retainJumpTransport(nativeHandle);
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
            return null;
        });
    }

    /** Inspect the child without running its shell-confirmation publication. */
    private boolean readyForJumpRetention() throws IOException, VaultException {
        requireProtocolState();
        requireTransportLive();
        if (jumpPhase != null) {
            JumpStatus status = queryJumpStatus();
            return status.phase == JumpPhase.OPEN && !status.remoteEof && !status.localEof;
        }
        if (shellPhase == ShellPhase.DISABLED || shellPhase.terminal()) return false;
        ShellStatus status = new ShellStatus(nativeBinding.shellStatus(nativeHandle));
        requireTransportLive();
        return status.phase == ShellPhase.RUNNING && !status.remoteEof && !status.localEof
                && (lastShellStatus == null || !lastShellStatus.remoteEof && !lastShellStatus.localEof);
    }

    /** Called on the original owner before shell promotion; no UI or native entry may reenter. */
    void retireAuthenticationWith(AppSecrets.EffectGuard retirement) throws VaultException {
        requireOwnerThread();
        if (retirement == null || established || authenticationRetirement != null)
            throw phaseFailure();
        authenticationRetirement = retirement;
    }

    /** One owner-only action after the peer confirms the shell and before it is published. */
    void confirmShellWith(AppSecrets.EffectGuard confirmation) throws VaultException {
        requireOwnerThread();
        if (confirmation == null || established || shellConfirmationPending || !authenticationSucceeded())
            throw phaseFailure();
        shellConfirmation = confirmation;
        shellConfirmationPending = true;
    }

    void requireShellConfirmationLive() throws VaultException {
        requireOwnerThread();
        if (!shellConfirmationPending || shellPhase != ShellPhase.RUNNING) throw phaseFailure();
        requireTransportLive();
        if (!jumpAncestorsRetained()) throw phaseFailure();
    }

    ShellStatus shellStatus() throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_STATUS", this::queryShellStatus);
    }

    int writeShell(byte[] bytes) throws VaultException {
        return shellEntry("SSH_SESSION_SHELL_WRITE", () -> {
            requireRunningShell();
            if (shellEofAccepted || lastShellStatus.localEof) throw phaseFailure();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_SHELL_IO_BYTES) throw invalid();
            callback.requireLocalEffectAdmission();
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
            if (result.phase == ShellPhase.RUNNING && shellConfirmation != null
                    && jumpAncestorsRetained()) {
                AppSecrets.EffectGuard confirmation = shellConfirmation;
                shellConfirmation = null;
                try {
                    requireShellConfirmationLive();
                    confirmation.check();
                    requireShellConfirmationLive();
                    shellConfirmationPending = false;
                } catch (VaultException failure) {
                    synchronized (stateLock) { terminalFailure = failure.code; }
                    throw failure;
                }
            }
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

    private void requireFreshEffectGuard() throws VaultException {
        if (!callback.hasChildTrustSource()) return;
        try {
            if ("VAULT_COMMIT_UNCERTAIN".equals(callback.lastFailureCode()))
                throw failure("VAULT_COMMIT_UNCERTAIN");
            requireProtocolState();
            requireProtocolLive();
            callback.requireChildEffectGuard();
        } catch (VaultException denied) {
            if ("VAULT_COMMIT_UNCERTAIN".equals(denied.code)
                    || "VAULT_COMMIT_UNCERTAIN".equals(callback.lastFailureCode()))
                throw failure("VAULT_COMMIT_UNCERTAIN");
            requireProtocolState();
            requireProtocolLive();
            throw denied;
        }
    }

    private long captureFreshSelectedEffect() throws VaultException {
        if (!callback.hasChildTrustSource()) return 0;
        if (ownedByJump == null || !ownedByJump.effectFenceRegistered) {
            // Preserve lifecycle diagnostics and the lock-free role check for
            // child sources that have not installed the native effect fence.
            requireFreshEffectGuard();
            throw failure("SSH_JUMP_EFFECT_GUARD");
        }
        try {
            if ("VAULT_COMMIT_UNCERTAIN".equals(callback.lastFailureCode()))
                throw failure("VAULT_COMMIT_UNCERTAIN");
            requireProtocolState();
            requireProtocolLive();
            return ownedByJump.captureProtectedEffect();
        } catch (VaultException denied) {
            if ("VAULT_COMMIT_UNCERTAIN".equals(denied.code)
                    || "VAULT_COMMIT_UNCERTAIN".equals(callback.lastFailureCode()))
                throw failure("VAULT_COMMIT_UNCERTAIN");
            requireProtocolState();
            requireProtocolLive();
            throw denied;
        }
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
            callback.requireLocalEffectAdmission();
        } catch (VaultException lifecycle) { throw selectionLifecycleFailure(lifecycle); }
    }

    /** Java-side readiness only; native still checks the real relay/proof chain. */
    private boolean jumpAncestorsRetained() throws VaultException {
        for (JumpChildOwner link = ownedByJump; link != null; link = link.parent.ownedByJump) {
            SshNativeSession ancestor = link.parent;
            if (link.unavailable() || ancestor.jumpChildOwner != link || !ancestor.established
                    || !ancestor.authenticationRetired) return false;
            ancestor.requireTransportLive();
        }
        return true;
    }

    private VaultException protocolNativeFailure(String code) throws VaultException {
        if (!cancellationRequested && callback.changedPolicyRejected()) {
            synchronized (stateLock) {
                if (!cancellationRequested && view.active()) {
                    callback.requireLocalEffectAdmission();
                    terminalFailure = "SSH_HOST_CHANGED_POLICY";
                    return failure(terminalFailure);
                }
            }
        }
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
        callback.requireLocalEffectAdmission();
    }

    private VaultException selectionLifecycleFailure(VaultException lifecycle) {
        String code = "VAULT_COMMIT_UNCERTAIN".equals(lifecycle.code) ? lifecycle.code
                : cancellationRequested || "SSH_CALLBACK_CANCELLED".equals(lifecycle.code)
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
        requireCleanupOwnerThread();
        enterNative();
        try { closeInternal(); }
        finally { NATIVE_ENTRY.remove(); }
    }

    private void closeInternal() throws VaultException {
        boolean alreadyDestroyed;
        synchronized (stateLock) {
            alreadyDestroyed = destroyed;
            if (!alreadyDestroyed && jumpChildOwner != null)
                throw new VaultException("SSH_SESSION_CLEANUP", "The SSH jump child still owns its parent");
            if (closeInProgress || !nativeClosed && (cancellationInProgress
                    || cancellationNeedsCompletion || cancellationUnconfirmed != null))
                throw new VaultException("SSH_SESSION_CLEANUP", "SSH session cancellation is unconfirmed");
            closeInProgress = true;
        }
        try { closeAdmitted(alreadyDestroyed); }
        finally { synchronized (stateLock) { closeInProgress = false; } }
    }

    private void closeAdmitted(boolean alreadyDestroyed) throws VaultException {
        if (alreadyDestroyed) {
            if (ownedByJump != null) ownedByJump.childDestroyed(this);
            return;
        }
        if (ownedByJump != null) {
            if (ownedByJump.child != this || ownedByJump.parent.jumpChildOwner != ownedByJump)
                throw JumpChildOwner.cleanupFailure();
            ownedByJump.closing = true;
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
        shellConfirmation = null;
        shellConfirmationPending = false;
        synchronized (stateLock) { destroyed = true; }
        if (ownedByJump != null) ownedByJump.childDestroyed(this);
    }

    /** Drops previously read output at its eventual UI publication; grants no native entry. */
    boolean shellOutputPublicationLive() {
        if (!outputPublicationStateLive()) return false;
        try {
            if (!established) return false;
            callback.requireEstablishedSessionBound(callbackClaim);
            if (!jumpAncestorsRetained()) return false;
        }
        catch (VaultException lifecycle) { return false; }
        return authenticationRetired && !shellConfirmationPending && outputPublicationStateLive();
    }

    private boolean outputPublicationStateLive() {
        synchronized (stateLock) {
            return !nativeClosed && !destroyed && !cancellationRequested && terminalFailure == null;
        }
    }

    boolean closed() { synchronized (stateLock) { return destroyed; } }
    boolean cancellationRequested() { return cancellationRequested; }
    boolean ownsCallback(SshNativeCallbackAdapter candidate) { return callback == candidate; }

    private void requireOwnerThread() throws VaultException {
        requireCleanupOwnerThread();
        if (ownedByJump != null && ownedByJump.unavailable())
            throw failure("SSH_SESSION_CLOSED");
        if (jumpChildOwner != null && jumpChildOwner.unavailable())
            throw failure("SSH_SESSION_CLOSED");
    }

    private void requireCleanupOwnerThread() throws VaultException {
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
