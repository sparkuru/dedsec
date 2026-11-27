package im.majo.ctos;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Serial owner of one authenticated session. The sink acknowledges consumed or dropped output. */
final class SshTerminalSession {
    static final int INPUT_LIMIT = 65536;
    static final int OUTPUT_LIMIT = 32768;
    private static final int CANCEL_RETRY_LIMIT = 3;
    private static final int PORT_CANCEL_NONE = 0, PORT_CANCEL_PENDING = 1,
            PORT_CANCEL_CONFIRMED = 2, PORT_CANCEL_UNCONFIRMED = 3, PORT_CANCEL_CLEANUP_ONLY = 4,
            PORT_CANCEL_RETRYABLE = 5, PORT_CANCEL_RETRY_REQUESTED = 6, PORT_CANCEL_WAIT = 7;
    private static final java.util.Set<String> ESTABLISHED_FAILURE_CODES = java.util.Set.of(
            "SSH_ROLE_EXPIRED", "SSH_SESSION_EXPIRED", "SSH_SESSION_CLOSED", "SSH_SESSION_CANCELLED",
            "SSH_SESSION_COMPLETE", "SSH_SESSION_THREAD", "SSH_SESSION_REENTRANT", "SSH_SESSION_PHASE",
            "SSH_SESSION_STEP", "SSH_SESSION_SHELL_STATUS", "SSH_SESSION_SHELL_READ", "SSH_SESSION_SHELL_WRITE",
            "SSH_SESSION_SHELL_RESIZE", "SSH_SESSION_SHELL_EOF", "SSH_SESSION_REQUIRED",
            "SSH_CALLBACK_CLOSED", "SSH_CALLBACK_CLOSING", "SSH_CALLBACK_CANCELLED", "SSH_CALLBACK_SESSION");
    private static final java.util.Set<String> CREDENTIAL_SAVE_FAILURE_CODES = java.util.Set.of(
            "SSH_CREDENTIAL_SAVE", "SSH_CANCELLED", "SSH_TIMED_OUT", "VAULT_CHANGED", "VAULT_EXPIRED",
            "VAULT_CANCELLED", "VAULT_LOCKED", "VAULT_FOREGROUND_REQUIRED", "VAULT_IO", "VAULT_COMMIT_UNCERTAIN");

    /** Fixed public codes only; exception messages and unrecognized codes never become terminal reasons. */
    static boolean isEstablishedFailureCode(String code) {
        return code != null && ESTABLISHED_FAILURE_CODES.contains(code);
    }

    static boolean isCredentialSaveFailureCode(String code) {
        return code != null && CREDENTIAL_SAVE_FAILURE_CODES.contains(code);
    }

    interface Sink {
        void data(byte[] bytes, Runnable consumed);
        void state(String state, Long exitStatus, String reason);
        default void startupFailure(String reason) { }
        default void cancellationUnconfirmed() { }
    }

    interface Scheduler { void later(Runnable turn, long delayMillis); }

    /** JVM-only seam; production attachment always receives the actual typed native session. */
    interface Port {
        void begin(int columns, int rows) throws Exception;
        Status status() throws Exception;
        void step() throws Exception;
        int write(byte[] bytes) throws Exception;
        byte[] read(int maximum) throws Exception;
        boolean resize(int columns, int rows) throws Exception;
        boolean publicationLive();
        void cancel() throws Exception;
        void close() throws Exception;
    }

    static final class Status {
        final SshNativeSession.ShellPhase phase;
        final int buffered;
        final Long exitStatus;
        Status(SshNativeSession.ShellPhase phase, int buffered, Long exitStatus) {
            this.phase = phase;
            this.buffered = buffered;
            this.exitStatus = exitStatus;
        }
    }

    private static final class NativePort implements Port {
        private final SshNativeSession session;
        private final SshNativeSession.TerminalChain chain;
        private final AutoCloseable resources;
        NativePort(SshNativeSession session, AutoCloseable resources) throws VaultException {
            this.session = session;
            this.chain = SshNativeSession.terminalChain(session);
            this.resources = resources;
        }
        @Override public void begin(int columns, int rows) throws Exception { session.beginShell(columns, rows); }
        @Override public Status status() throws Exception {
            SshNativeSession.ShellStatus value = chain.status();
            return new Status(value.phase, value.bufferedBytes,
                    value.exitStatusKnown ? value.exitStatus : null);
        }
        @Override public void step() throws Exception { chain.step(); }
        @Override public int write(byte[] bytes) throws Exception { return session.writeShell(bytes); }
        @Override public byte[] read(int maximum) throws Exception { return session.readShell(maximum); }
        @Override public boolean resize(int columns, int rows) throws Exception {
            return session.resizeShell(columns, rows);
        }
        @Override public boolean publicationLive() { return session.shellOutputPublicationLive(); }
        @Override public void cancel() throws Exception { chain.cancel(); }
        int cancelForTerminal() throws VaultException { return chain.cancelForTerminal(); }
        int retryCancellationForTerminal() throws VaultException { return chain.retryCancellationForTerminal(); }
        @Override public void close() throws Exception {
            chain.close();
            resources.close();
        }
    }

    private final Port port;
    private final Scheduler scheduler;
    private final Sink sink;
    private final Runnable released;
    private final SshSessionOwner.Cancellation outputCancellation;
    private final AtomicInteger portCancellation = new AtomicInteger();
    private final ArrayDeque<byte[]> input = new ArrayDeque<>();
    private int queuedInput;
    private int pendingColumns;
    private int pendingRows;
    private boolean running;
    private volatile boolean cancelled;
    private volatile boolean closed;
    private boolean closing;
    private boolean scheduled;
    private boolean cleanupReported;
    private int cancelRetryAttempts;
    private boolean releasedOnce;
    private boolean screenLocked;
    private String failure;
    private String startupFailure;
    private Long exitStatus;
    private volatile byte[] inFlight;

    SshTerminalSession(SshNativeSession session, AutoCloseable resources,
            ScheduledExecutorService owner, Sink sink, Runnable released) throws VaultException {
        this(session, resources, owner, sink, released, new SshSessionOwner.Cancellation());
    }

    SshTerminalSession(SshNativeSession session, AutoCloseable resources,
            ScheduledExecutorService owner, Sink sink, Runnable released,
            SshSessionOwner.Cancellation outputCancellation) throws VaultException {
        this(session, resources, (turn, delay) -> owner.schedule(turn, delay, TimeUnit.MILLISECONDS),
                sink, released, outputCancellation);
    }

    /** JVM scheduling seam; the production path still uses the exact NativePort. */
    SshTerminalSession(SshNativeSession session, AutoCloseable resources,
            Scheduler scheduler, Sink sink, Runnable released,
            SshSessionOwner.Cancellation outputCancellation) throws VaultException {
        this(new NativePort(session, resources), scheduler, sink, released, outputCancellation);
    }

    SshTerminalSession(Port port, Scheduler scheduler, Sink sink, Runnable released) {
        this(port, scheduler, sink, released, new SshSessionOwner.Cancellation());
    }

    SshTerminalSession(Port port, Scheduler scheduler, Sink sink, Runnable released,
            SshSessionOwner.Cancellation outputCancellation) {
        if (outputCancellation == null) throw new IllegalArgumentException("Output cancellation is required");
        this.port = port;
        this.scheduler = scheduler;
        this.sink = sink;
        this.released = released;
        this.outputCancellation = outputCancellation;
    }

    // All methods except requestCancel/publicationLive execute on the session's original owner.
    void start(int columns, int rows) {
        try {
            if (cancelled) { closeTurn(); return; }
            if (!admitPort()) return;
            port.begin(columns, rows); // Native SUCCESS gate remains authoritative.
            schedule(0);
        } catch (Exception error) { fail("SSH_TERMINAL_START"); }
        catch (Error error) { failPreservingError("SSH_TERMINAL_START", error); throw error; }
    }

    void write(byte[] bytes) {
        if (!running || closing || cancelled || closed || !outputCancellation.admitTerminalIo())
            throw new IllegalStateException("Terminal is closed");
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > INPUT_LIMIT - queuedInput)
            throw new IllegalStateException("Terminal input queue is full; retry after output progresses");
        input.add(bytes.clone());
        queuedInput += bytes.length;
        schedule(0);
    }

    void resize(int columns, int rows) {
        if (columns < 1 || columns > 4096 || rows < 1 || rows > 4096)
            throw new IllegalArgumentException("Invalid terminal dimensions");
        if (closing || cancelled || closed || !outputCancellation.admitTerminalIo()) return;
        pendingColumns = columns;
        pendingRows = rows;
        schedule(0);
    }

    /** The only cross-thread mutation; cancellation fences output before owner cleanup runs. */
    void requestCancel() {
        try { outputCancellation.cancel(); }
        catch (RuntimeException | Error failure) {
            try { sharedCancellationFailed(); }
            catch (RuntimeException | Error scheduling) {
                if (scheduling != failure) failure.addSuppressed(scheduling);
            }
            throw failure;
        }
        boolean ownsPortCancellation = portCancellation.compareAndSet(PORT_CANCEL_NONE, PORT_CANCEL_PENDING);
        cancelled = true;
        if (!ownsPortCancellation) {
            if (port instanceof NativePort
                    && portCancellation.compareAndSet(PORT_CANCEL_RETRYABLE, PORT_CANCEL_RETRY_REQUESTED)) {
                try { scheduleCancellationClose(); }
                catch (RuntimeException | Error failure) {
                    portCancellation.compareAndSet(PORT_CANCEL_RETRY_REQUESTED, PORT_CANCEL_RETRYABLE);
                    throw failure;
                }
            }
            return;
        }
        try {
            if (port instanceof NativePort)
                portCancellation.set(nativeCancellationState(((NativePort)port).cancelForTerminal()));
            else {
                port.cancel();
                portCancellation.set(PORT_CANCEL_CONFIRMED);
            }
        }
        catch (Exception failure) {
            portCancellation.set(PORT_CANCEL_UNCONFIRMED);
        }
        catch (Error error) {
            portCancellation.set(PORT_CANCEL_UNCONFIRMED);
            try { scheduleCancellationClose(); }
            catch (RuntimeException scheduling) { error.addSuppressed(scheduling); }
            throw error;
        }
        scheduleCancellationClose();
    }

    private static int nativeCancellationState(int status) {
        if (status == SshNativeSession.TerminalChain.CANCEL_CONFIRMED) return PORT_CANCEL_CONFIRMED;
        if (status == SshNativeSession.TerminalChain.CANCEL_RETRYABLE) return PORT_CANCEL_RETRYABLE;
        if (status == SshNativeSession.TerminalChain.CANCEL_CLEANUP_ONLY) return PORT_CANCEL_CLEANUP_ONLY;
        if (status == SshNativeSession.TerminalChain.CANCEL_WAIT) return PORT_CANCEL_WAIT;
        throw new IllegalStateException("Invalid SSH session cancellation status");
    }

    private void scheduleCancellationClose() {
        if (closed) return;
        try { scheduler.later(this::closeTurn, 0); }
        catch (java.util.concurrent.RejectedExecutionException failure) {
            if (!closed) throw failure;
        }
    }

    void sharedCancellationFailed() { scheduleCancellationClose(); }

    void stop() {
        // The owner may queue this after a cross-thread requestCancel().
        // Do not mistake that same Stop for a new retry request.
        if (!cancelled) requestCancel();
        closing = true;
        closeTurn();
    }

    boolean publicationLive() {
        return !cancelled && !closed && !outputCancellation.stopRequested() && port.publicationLive();
    }
    boolean closed() { return closed; }
    boolean startupFailureUncertain() { return "VAULT_COMMIT_UNCERTAIN".equals(startupFailure); }
    synchronized boolean markScreenLocked() {
        if (closed || screenLocked) return false;
        screenLocked = true;
        return true;
    }

    /** Runs on the owner's serial UI dispatcher, outside the native-owner metadata monitor. */
    void publishScreenLockPending(Runnable publication) {
        synchronized (this) { if (!screenLocked || closed) return; }
        publication.run();
    }
    int queuedInputBytes() { return queuedInput; }
    int outputInFlightBytes() { byte[] bytes = inFlight; return bytes == null ? 0 : bytes.length; }

    private void schedule(long delay) {
        if (scheduled || closed) return;
        scheduled = true;
        scheduler.later(() -> {
            scheduled = false;
            if (closing || cancelled || outputCancellation.stopRequested()) closeTurn(); else pump();
        }, delay);
    }

    private void pump() {
        if (closed) return;
        try {
            if (!admitPort()) return;
            Status status = port.status();
            if (status.phase == SshNativeSession.ShellPhase.REJECTED) {
                fail("SSH_TERMINAL_REJECTED"); return;
            }
            if (unpublishedClosedOutput(status)) return;
            if (!running && status.phase == SshNativeSession.ShellPhase.RUNNING) {
                if (!admitRunning()) return;
                running = true;
                sink.state("running", null, null);
            }
            if (status.phase == SshNativeSession.ShellPhase.RUNNING) {
                if (!input.isEmpty()) {
                    byte[] head = input.peek();
                    byte[] chunk = Arrays.copyOf(head, Math.min(head.length, OUTPUT_LIMIT));
                    int accepted;
                    try {
                        if (!admitPort()) return;
                        accepted = port.write(chunk);
                    }
                    finally { Arrays.fill(chunk, (byte) 0); }
                    if (accepted < 0 || accepted > Math.min(head.length, OUTPUT_LIMIT))
                        throw new IllegalStateException("Invalid SSH input acceptance");
                    if (accepted > 0) {
                        input.remove();
                        queuedInput -= accepted;
                        if (accepted < head.length) input.addFirst(Arrays.copyOfRange(head, accepted, head.length));
                        Arrays.fill(head, (byte) 0);
                    }
                }
                if (pendingColumns != 0) {
                    if (!admitPort()) return;
                    if (port.resize(pendingColumns, pendingRows)) pendingColumns = 0;
                }
            }
            if (!status.phase.terminal()) {
                if (!admitPort()) return;
                port.step(); // One bounded native turn, then yield to write/resize/stop commands.
                if (!admitPort()) return;
                status = port.status();
                if (unpublishedClosedOutput(status)) return;
                if (!running && status.phase == SshNativeSession.ShellPhase.RUNNING) {
                    if (!admitRunning()) return;
                    running = true;
                    sink.state("running", null, null);
                }
            }
            if (status.phase == SshNativeSession.ShellPhase.REJECTED) {
                fail("SSH_TERMINAL_REJECTED"); return;
            }
            if (running && inFlight == null && status.buffered > 0) {
                if (!admitPort()) return;
                byte[] bytes = port.read(OUTPUT_LIMIT);
                if (bytes == null || bytes.length > OUTPUT_LIMIT) throw new IllegalStateException("Invalid SSH output");
                if (bytes.length > 0) {
                    if (!publicationLive()) {
                        Arrays.fill(bytes, (byte) 0);
                        if (outputCancellation.stopRequested()) closeTurn();
                        else fail("SSH_TERMINAL_REVOKED");
                        return;
                    }
                    inFlight = bytes;
                    sink.data(bytes, () -> scheduler.later(() -> {
                        if (inFlight == bytes) {
                            Arrays.fill(bytes, (byte) 0);
                            inFlight = null;
                            if (closed) releaseAfterAcknowledgement(); else schedule(0);
                        }
                    }, 0));
                }
            }
            // Keep native ownership live until the last main-thread publication is acknowledged.
            if (status.phase == SshNativeSession.ShellPhase.CLOSED && status.buffered == 0 && inFlight == null) {
                exitStatus = status.exitStatus;
                closing = true;
                closeTurn();
            } else schedule(10);
        } catch (Exception error) {
            String code = error instanceof VaultException ? ((VaultException)error).code : null;
            fail(isEstablishedFailureCode(code) || !running && isCredentialSaveFailureCode(code)
                    ? code : "SSH_TERMINAL_IO");
        }
        catch (Error error) { failPreservingError("SSH_TERMINAL_IO", error); throw error; }
    }

    private boolean unpublishedClosedOutput(Status status) {
        if (running || status.phase != SshNativeSession.ShellPhase.CLOSED || status.buffered == 0)
            return false;
        fail("SSH_TERMINAL_IO");
        return true;
    }

    private boolean admitRunning() {
        if (!cancelled && port.publicationLive() && outputCancellation.admitOutput()) return true;
        if (outputCancellation.stopRequested()) { closeTurn(); return false; }
        fail("SSH_TERMINAL_REVOKED");
        return false;
    }

    private boolean admitPort() {
        if (outputCancellation.admitTerminalIo()) return true;
        closeTurn();
        return false;
    }

    private void fail(String reason) {
        failure = reason;
        closing = true;
        if (!running && isCredentialSaveFailureCode(reason)) startupFailure = reason;
        finishFailedTurn();
    }

    private void finishFailedTurn() {
        if (outputCancellation.stopRequested()) { closeTurn(); return; }
        try { requestCancel(); }
        catch (RuntimeException | Error primary) {
            try { closeTurn(); }
            catch (RuntimeException | Error cleanup) {
                if (primary != cleanup) primary.addSuppressed(cleanup);
            }
            throw primary;
        }
        closeTurn();
    }

    private void failPreservingError(String reason, Error primary) {
        try { fail(reason); }
        catch (RuntimeException | Error cleanup) {
            if (primary != cleanup) primary.addSuppressed(cleanup);
        }
    }

    private void closeTurn() {
        if (closed) return;
        closing = true;
        for (byte[] bytes : input) Arrays.fill(bytes, (byte) 0);
        input.clear();
        queuedInput = 0;
        int cancellation = portCancellation.get();
        int invalidation = outputCancellation.invalidationStatus();
        if (cancelled && invalidation == SshSessionOwner.Cancellation.INVALIDATION_COMPLETE
                && port instanceof NativePort) {
            if (cancellation == PORT_CANCEL_RETRY_REQUESTED
                    && portCancellation.compareAndSet(PORT_CANCEL_RETRY_REQUESTED, PORT_CANCEL_RETRYABLE)) {
                cancelRetryAttempts = 0;
                cancellation = PORT_CANCEL_RETRYABLE;
            }
            if ((cancellation == PORT_CANCEL_WAIT
                    || cancellation == PORT_CANCEL_RETRYABLE && cancelRetryAttempts < CANCEL_RETRY_LIMIT)
                    && portCancellation.compareAndSet(cancellation, PORT_CANCEL_PENDING)) {
                try {
                    int result = nativeCancellationState(((NativePort)port).retryCancellationForTerminal());
                    if (result == PORT_CANCEL_RETRYABLE) cancelRetryAttempts++;
                    portCancellation.set(result);
                } catch (Exception failure) {
                    portCancellation.set(PORT_CANCEL_UNCONFIRMED);
                } catch (Error error) {
                    portCancellation.set(PORT_CANCEL_UNCONFIRMED);
                    try { scheduleCancellationClose(); }
                    catch (RuntimeException scheduling) { error.addSuppressed(scheduling); }
                    throw error;
                }
                cancellation = portCancellation.get();
            }
        }
        if (invalidation != SshSessionOwner.Cancellation.INVALIDATION_NONE && (!cancelled
                || invalidation != SshSessionOwner.Cancellation.INVALIDATION_COMPLETE
                || cancellation != PORT_CANCEL_CONFIRMED && cancellation != PORT_CANCEL_CLEANUP_ONLY)) {
            boolean unknown = invalidation == SshSessionOwner.Cancellation.INVALIDATION_UNKNOWN
                    || cancellation == PORT_CANCEL_UNCONFIRMED;
            boolean retryExhausted = cancellation == PORT_CANCEL_RETRYABLE
                    && cancelRetryAttempts >= CANCEL_RETRY_LIMIT;
            if (unknown) {
                if (startupFailure != null) sink.startupFailure(startupFailure);
                sink.cancellationUnconfirmed();
            } else if (invalidation == SshSessionOwner.Cancellation.INVALIDATION_PENDING
                    || cancellation == PORT_CANCEL_PENDING || cancellation == PORT_CANCEL_RETRY_REQUESTED
                    || cancellation == PORT_CANCEL_WAIT
                    || cancellation == PORT_CANCEL_RETRYABLE && !retryExhausted) schedule(100);
            if (retryExhausted && startupFailure != null) sink.startupFailure(startupFailure);
            if (!cleanupReported && (unknown || retryExhausted)) {
                cleanupReported = true;
                sink.state("failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
            }
            return;
        }
        try { port.close(); }
        catch (Error error) {
            schedule(100);
            throw error;
        }
        catch (Exception error) {
            schedule(100);
            if (!cleanupReported) {
                cleanupReported = true;
                sink.state("failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
            }
            return; // Retain the original owner and handle until every close stage confirms.
        }
        String finalState, finalReason;
        synchronized (this) {
            closed = true;
            finalState = screenLocked || failure == null ? "closed" : "failed";
            finalReason = screenLocked ? "SSH_SCREEN_LOCKED" : failure;
        }
        try {
            if (startupFailure != null) sink.startupFailure(startupFailure);
            sink.state(finalState, exitStatus, finalReason);
        }
        finally { releaseAfterAcknowledgement(); }
    }

    private void releaseAfterAcknowledgement() {
        if (inFlight == null && !releasedOnce) {
            releasedOnce = true;
            released.run();
        }
    }
}
