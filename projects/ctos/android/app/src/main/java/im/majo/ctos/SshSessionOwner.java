package im.majo.ctos;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A service-owned serial executor; native sessions never migrate between owners. */
final class SshSessionOwner {
    interface Factory extends AutoCloseable {
        SshNativeSession create(BooleanSupplier cancelled) throws Exception;
        default void cancellationAction(Runnable action) { }
        default SshReconnectTarget reconnectTarget() { return null; }
        @Override void close() throws Exception;
    }
    interface Dispatcher { void post(Runnable action); }
    interface Lifecycle {
        void state(String state);
        void released();
    }

    final String id = UUID.randomUUID().toString();
    final int generation;
    final CompletableFuture<String> connected = new CompletableFuture<>();
    private final Factory factory;
    private final String mode;
    private final int columns, rows;
    private final SshTerminalState events;
    private final Dispatcher dispatcher;
    private final Lifecycle lifecycle;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicBoolean screenLocked = new AtomicBoolean();
    private volatile SshTerminalSession terminal;
    private volatile boolean released;
    private boolean cleanupReported;
    private int pendingWriteBytes;

    SshSessionOwner(Factory factory, String mode, int columns, int rows, SshTerminalState events,
            Dispatcher dispatcher, Lifecycle lifecycle) {
        if (factory == null || mode == null || mode.isEmpty() || mode.length() > 128
                || columns < 1 || columns > 4096 || rows < 1 || rows > 4096)
            throw new IllegalArgumentException("Invalid SSH terminal attachment");
        this.factory = factory;
        this.mode = mode;
        this.columns = columns;
        this.rows = rows;
        this.events = events;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        generation = SshTerminalState.nextGeneration();
        events.begin(generation, mode);
        factory.cancellationAction(this::requestStop);
    }

    void begin() {
        if (!begun.compareAndSet(false, true)) return;
        executor.execute(this::create);
    }

    void rejectStart() {
        cancelled.set(true);
        connected.completeExceptionally(new VaultException("SSH_SERVICE_START", "SSH service could not start"));
        if (begun.compareAndSet(false, true)) executor.execute(() -> closeUnattached(null));
        else requestStop();
    }

    private void create() {
        SshNativeSession session = null;
        boolean transferred = false;
        try {
            if (cancelled.get()) { closeUnattached(null); return; }
            session = factory.create(cancelled::get);
            if (session == null) throw new IllegalStateException("SSH session is required");
            events.reconnectTarget(generation, factory.reconnectTarget());
            SshTerminalSession controller = new SshTerminalSession(session, factory, executor,
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) {
                            dispatcher.post(() -> {
                                try {
                                    SshTerminalSession current = terminal;
                                    if (!cancelled.get() && current != null && current.publicationLive()) events.data(generation, bytes);
                                } finally { consumed.run(); }
                            });
                        }
                        @Override public void state(String state, Long exitStatus, String reason) {
                            dispatcher.post(() -> {
                                events.state(generation, state, exitStatus, reason);
                                lifecycle.state(state);
                            });
                            if ("running".equals(state)) connected.complete(mode);
                            else connected.completeExceptionally(new VaultException("SSH_TERMINAL_START",
                                    "SSH terminal closed before running"));
                        }
                    }, this::release);
            synchronized (this) {
                terminal = controller;
                if (screenLocked.get()) controller.markScreenLocked();
            }
            transferred = true;
            if (cancelled.get()) controller.stop(); else controller.start(columns, rows);
        } catch (Exception failure) {
            connected.completeExceptionally(failure instanceof VaultException ? failure
                    : new VaultException("SSH_FAILED", "SSH terminal could not start"));
            publishFailure("SSH_TERMINAL_START");
            if (!transferred) closeUnattached(session);
        } catch (Error failure) {
            connected.completeExceptionally(failure);
            publishFailure("SSH_TERMINAL_START");
            if (!transferred) closeUnattached(session);
            throw failure;
        }
    }

    boolean matches(String expected) { return id.equals(expected); }

    boolean screenLocked() { return screenLocked.get(); }

    boolean stopForScreenLock(String expectedId) {
        synchronized (this) {
            if (!matches(expectedId) || released || screenLocked.get()) return false;
            if (terminal != null && !terminal.markScreenLocked()) return false;
            screenLocked.set(true);
            cancelled.set(true);
        }
        connected.completeExceptionally(new VaultException("SSH_SCREEN_LOCKED", "SSH ended when the screen locked"));
        dispatcher.post(() -> {
            if (released) return;
            SshTerminalSession current = terminal;
            Runnable publication = () -> events.state(generation, "failed", null, "SSH_SCREEN_LOCKED");
            if (current == null) publication.run();
            else current.publishScreenLockPending(publication);
        });
        requestStop();
        return true;
    }

    CompletableFuture<Void> write(int expected, byte[] bytes) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        final byte[] copy;
        synchronized (this) {
            if (expected != generation || cancelled.get() || bytes.length > SshTerminalSession.INPUT_LIMIT - pendingWriteBytes) {
                result.completeExceptionally(new IllegalStateException("Terminal input is stale or full"));
                return result;
            }
            copy = bytes.clone();
            pendingWriteBytes += copy.length;
        }
        try {
            executor.execute(() -> {
                try {
                    requireTerminal(expected).write(copy);
                    result.complete(null);
                } catch (Exception failure) { result.completeExceptionally(failure); }
                finally { releasePendingWrite(copy); }
            });
        } catch (RuntimeException failure) { releasePendingWrite(copy); result.completeExceptionally(failure); }
        return result;
    }

    private synchronized void releasePendingWrite(byte[] bytes) {
        pendingWriteBytes -= bytes.length;
        Arrays.fill(bytes, (byte) 0);
    }

    CompletableFuture<Void> resize(int expected, int width, int height) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { requireTerminal(expected).resize(width, height); result.complete(null); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    CompletableFuture<Void> stop(int expected) {
        if (expected != generation) {
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException("Terminal generation is stale"));
            return rejected;
        }
        requestStop();
        return CompletableFuture.completedFuture(null);
    }

    void requestStop() {
        if (released) return;
        cancelled.set(true);
        dispatcher.post(() -> { if (!released) lifecycle.state("stopping"); });
        SshTerminalSession current = terminal;
        if (current != null) current.requestCancel();
        if (!begun.get()) {
            // A constructor-time cancellation must not outrun service publication of this owner.
            dispatcher.post(this::begin);
            return;
        }
        try { executor.execute(() -> { if (terminal != null) terminal.stop(); }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Cleanup already confirmed. */ }
    }

    private SshTerminalSession requireTerminal(int expected) {
        if (expected != generation || cancelled.get() || terminal == null || terminal.closed())
            throw new IllegalStateException("Terminal generation is stale or closed");
        return terminal;
    }

    private void closeUnattached(SshNativeSession session) {
        try {
            if (session != null) session.close();
            factory.close();
        } catch (Exception failure) {
            if (!cleanupReported) { cleanupReported = true; publishFailure("SSH_TERMINAL_CLEANUP_PENDING"); }
            executor.schedule(() -> closeUnattached(session), 100, TimeUnit.MILLISECONDS);
            return;
        } catch (Error failure) {
            executor.schedule(() -> closeUnattached(session), 100, TimeUnit.MILLISECONDS);
            throw failure;
        }
        if (screenLocked.get()) dispatcher.post(() -> events.state(generation, "closed", null, "SSH_SCREEN_LOCKED"));
        connected.completeExceptionally(new VaultException(screenLocked.get() ? "SSH_SCREEN_LOCKED" : "SSH_CANCELLED",
                "SSH connection was cancelled"));
        release();
    }

    private void publishFailure(String reason) {
        String selectedReason = screenLocked.get() && !"SSH_TERMINAL_CLEANUP_PENDING".equals(reason)
                ? "SSH_SCREEN_LOCKED" : reason;
        dispatcher.post(() -> {
            events.state(generation, "failed", null, selectedReason);
            lifecycle.state("failed");
        });
    }

    private void release() {
        if (released) return;
        released = true;
        terminal = null;
        executor.shutdown();
        dispatcher.post(lifecycle::released);
    }
}
