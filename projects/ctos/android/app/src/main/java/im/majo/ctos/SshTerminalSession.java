package im.majo.ctos;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Serial owner of one authenticated session. The sink acknowledges consumed or dropped output. */
final class SshTerminalSession {
    static final int INPUT_LIMIT = 65536;
    static final int OUTPUT_LIMIT = 32768;

    interface Sink {
        void data(byte[] bytes, Runnable consumed);
        void state(String state, Long exitStatus, String reason);
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
        private final AutoCloseable resources;
        NativePort(SshNativeSession session, AutoCloseable resources) {
            this.session = session;
            this.resources = resources;
        }
        @Override public void begin(int columns, int rows) throws Exception { session.beginShell(columns, rows); }
        @Override public Status status() throws Exception {
            SshNativeSession.ShellStatus value = session.shellStatus();
            return new Status(value.phase, value.bufferedBytes,
                    value.exitStatusKnown ? value.exitStatus : null);
        }
        @Override public void step() throws Exception { session.step(); }
        @Override public int write(byte[] bytes) throws Exception { return session.writeShell(bytes); }
        @Override public byte[] read(int maximum) throws Exception { return session.readShell(maximum); }
        @Override public boolean resize(int columns, int rows) throws Exception {
            return session.resizeShell(columns, rows);
        }
        @Override public boolean publicationLive() { return session.shellOutputPublicationLive(); }
        @Override public void cancel() throws Exception { session.cancel(); }
        @Override public void close() throws Exception {
            session.close();
            resources.close();
        }
    }

    private final Port port;
    private final Scheduler scheduler;
    private final Sink sink;
    private final Runnable released;
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
    private boolean releasedOnce;
    private String failure;
    private Long exitStatus;
    private volatile byte[] inFlight;

    SshTerminalSession(SshNativeSession session, AutoCloseable resources,
            ScheduledExecutorService owner, Sink sink, Runnable released) {
        this(new NativePort(session, resources), (turn, delay) -> owner.schedule(turn, delay, TimeUnit.MILLISECONDS),
                sink, released);
    }

    SshTerminalSession(Port port, Scheduler scheduler, Sink sink, Runnable released) {
        this.port = port;
        this.scheduler = scheduler;
        this.sink = sink;
        this.released = released;
    }

    // All methods except requestCancel/publicationLive execute on the session's original owner.
    void start(int columns, int rows) {
        try {
            if (cancelled) { closeTurn(); return; }
            port.begin(columns, rows); // Native SUCCESS gate remains authoritative.
            schedule(0);
        } catch (Exception error) { fail("SSH_TERMINAL_START"); }
        catch (Error error) { fail("SSH_TERMINAL_START"); throw error; }
    }

    void write(byte[] bytes) {
        if (!running || closing || cancelled || closed) throw new IllegalStateException("Terminal is closed");
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
        if (closing || cancelled || closed) return;
        pendingColumns = columns;
        pendingRows = rows;
        schedule(0);
    }

    /** The only cross-thread mutation; cancellation fences output before owner cleanup runs. */
    void requestCancel() {
        cancelled = true;
        try { port.cancel(); }
        catch (Exception ignored) { /* Owner close remains mandatory. */ }
        catch (Error error) {
            scheduler.later(this::closeTurn, 0);
            throw error;
        }
    }

    void stop() {
        requestCancel();
        closing = true;
        closeTurn();
    }

    boolean publicationLive() { return !cancelled && !closed && port.publicationLive(); }
    boolean closed() { return closed; }
    int queuedInputBytes() { return queuedInput; }
    int outputInFlightBytes() { byte[] bytes = inFlight; return bytes == null ? 0 : bytes.length; }

    private void schedule(long delay) {
        if (scheduled || closed) return;
        scheduled = true;
        scheduler.later(() -> {
            scheduled = false;
            if (closing || cancelled) closeTurn(); else pump();
        }, delay);
    }

    private void pump() {
        if (closed) return;
        try {
            Status status = port.status();
            if (status.phase == SshNativeSession.ShellPhase.REJECTED) {
                fail("SSH_TERMINAL_REJECTED"); return;
            }
            if (!running && status.phase == SshNativeSession.ShellPhase.RUNNING) {
                running = true;
                sink.state("running", null, null);
            }
            if (status.phase == SshNativeSession.ShellPhase.RUNNING) {
                if (!input.isEmpty()) {
                    byte[] head = input.peek();
                    byte[] chunk = Arrays.copyOf(head, Math.min(head.length, OUTPUT_LIMIT));
                    int accepted;
                    try { accepted = port.write(chunk); }
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
                if (pendingColumns != 0 && port.resize(pendingColumns, pendingRows)) pendingColumns = 0;
            }
            if (!status.phase.terminal()) {
                port.step(); // One bounded native turn, then yield to write/resize/stop commands.
                status = port.status();
                if (!running && status.phase == SshNativeSession.ShellPhase.RUNNING) {
                    running = true;
                    sink.state("running", null, null);
                }
            }
            if (status.phase == SshNativeSession.ShellPhase.REJECTED) {
                fail("SSH_TERMINAL_REJECTED"); return;
            }
            if (running && inFlight == null && status.buffered > 0) {
                byte[] bytes = port.read(OUTPUT_LIMIT);
                if (bytes == null || bytes.length > OUTPUT_LIMIT) throw new IllegalStateException("Invalid SSH output");
                if (bytes.length > 0) {
                    if (!publicationLive()) { Arrays.fill(bytes, (byte) 0); fail("SSH_TERMINAL_REVOKED"); return; }
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
        } catch (Exception error) { fail("SSH_TERMINAL_IO"); }
        catch (Error error) { fail("SSH_TERMINAL_IO"); throw error; }
    }

    private void fail(String reason) {
        failure = reason;
        closing = true;
        try { requestCancel(); }
        finally { closeTurn(); }
    }

    private void closeTurn() {
        if (closed) return;
        closing = true;
        for (byte[] bytes : input) Arrays.fill(bytes, (byte) 0);
        input.clear();
        queuedInput = 0;
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
        closed = true;
        try { sink.state(failure == null ? "closed" : "failed", exitStatus, failure); }
        finally { releaseAfterAcknowledgement(); }
    }

    private void releaseAfterAcknowledgement() {
        if (inFlight == null && !releasedOnce) {
            releasedOnce = true;
            released.run();
        }
    }
}
