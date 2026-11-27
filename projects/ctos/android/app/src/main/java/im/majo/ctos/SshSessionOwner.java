package im.majo.ctos;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** A service-owned serial executor; native sessions never migrate between owners. */
final class SshSessionOwner {
    private static final int NO_DEFERRED_RESULT = 0, DEFERRED_SCREEN_LOCK = 1, DEFERRED_SERVICE_START = 2;
    interface Factory extends AutoCloseable {
        SshNativeSession create(BooleanSupplier cancelled) throws Exception;
        default void cancellationAction(Runnable action) { }
        default SshReconnectTarget reconnectTarget() { return null; }
        /** Non-blocking metadata snapshot; no I/O, locks, callbacks, or waits. */
        default boolean tracksCredentialSave() { return false; }
        default boolean mayWriteTrust() { return false; }
        default Cancellation trustCancellation() { return null; }
        @Override void close() throws Exception;
    }
    /** One atomic ordering point for cancellation versus the owner accepting trust. */
    static final class Cancellation implements BooleanSupplier {
        private static final int CANCELLED = 1, STOP_REQUESTED = 2, INVALIDATION_CONFIRMED = 4;
        private static final int STOP_ORDINARY = 0, STOP_SCREEN = 8, STOP_SERVICE = 16;
        private static final int STOP_REASON_MASK = STOP_SCREEN | STOP_SERVICE;
        private static final int INVALIDATION_FAILED_BIT = 32;
        static final int INVALIDATION_NONE = 0, INVALIDATION_PENDING = 1,
                INVALIDATION_COMPLETE = 2, INVALIDATION_UNKNOWN = 3;
        private final AtomicInteger state = new AtomicInteger();
        private volatile boolean invalidationUnconfirmed;
        /** Each receipt is specific to one observer, not a reusable connection-wide bit. */
        static final class TrustReceipt {
            private final Cancellation owner;
            private final Object scope;
            private boolean confirmed;
            private TrustReceipt(Cancellation owner, Object scope) {
                this.owner = owner;
                this.scope = scope;
            }
        }
        @Override public boolean getAsBoolean() { return (state.get() & STOP_REQUESTED) != 0; }
        void cancel() { cancel(STOP_ORDINARY); }
        private void markStop(int reason) {
            state.getAndUpdate(value -> (value & STOP_REQUESTED) != 0 ? value : value | STOP_REQUESTED | reason);
        }
        private void cancel(int reason) {
            markStop(reason);
            synchronized (this) {
                if (invalidationUnconfirmed)
                    throw new IllegalStateException("SSH cancellation invalidation is unconfirmed");
                if ((state.get() & CANCELLED) != 0) return;
                invalidationUnconfirmed = true;
                try {
                    boolean begun = SshNativeCallbackAdapter.beginJumpEffectInvalidation();
                    try { state.getAndUpdate(value -> value | CANCELLED); }
                    finally { SshNativeCallbackAdapter.endJumpEffectInvalidation(begun); }
                    state.getAndUpdate(value -> value | INVALIDATION_CONFIRMED);
                    invalidationUnconfirmed = false;
                } catch (RuntimeException | Error failure) {
                    state.getAndUpdate(value -> value | INVALIDATION_FAILED_BIT);
                    throw failure;
                }
            }
        }
        boolean stopRequested() { return (state.get() & STOP_REQUESTED) != 0; }
        int stopReason() { return state.get() & STOP_REASON_MASK; }
        /** Preserve the first stop reason without waiting for native invalidation. */
        String stopCode() {
            int value = state.get();
            if ((value & STOP_REQUESTED) == 0) return null;
            int reason = value & STOP_REASON_MASK;
            if (reason == STOP_SCREEN) return "SSH_SCREEN_LOCKED";
            if (reason == STOP_SERVICE) return "SSH_SERVICE_START";
            return "SSH_CANCELLED";
        }
        boolean invalidationUnconfirmed() { return invalidationUnconfirmed; }
        boolean invalidationFailed() { return invalidationStatus() == INVALIDATION_UNKNOWN; }
        boolean invalidationPending() { return invalidationStatus() == INVALIDATION_PENDING; }
        int invalidationStatus() {
            int value = state.get();
            if ((value & STOP_REQUESTED) == 0) return INVALIDATION_NONE;
            if ((value & INVALIDATION_FAILED_BIT) != 0) return INVALIDATION_UNKNOWN;
            return (value & INVALIDATION_CONFIRMED) == 0 ? INVALIDATION_PENDING : INVALIDATION_COMPLETE;
        }
        synchronized boolean liveForJumpEffect() { return !getAsBoolean(); }
        boolean admitUnattachedCleanup() {
            while (true) {
                int value = state.get();
                if ((value & STOP_REQUESTED) != 0 && (value & INVALIDATION_CONFIRMED) == 0) return false;
                if (state.compareAndSet(value, value)) return true;
            }
        }
        /** Atomically orders output admission against this exact owner's cancellation. */
        boolean admitOutput() { return admitTerminalIo(); }
        /** Connector creation and Stop use the same non-blocking ordering point. */
        boolean admitFactoryStart() { return admitTerminalIo(); }
        boolean admitTerminalIo() {
            while (true) {
                int value = state.get();
                if ((value & STOP_REQUESTED) != 0) return false;
                if (state.compareAndSet(value, value)) return true;
            }
        }
        TrustReceipt trustReceipt(Object scope) {
            if (scope == null) throw new IllegalArgumentException("A trust receipt needs its owner");
            return new TrustReceipt(this, scope);
        }
        boolean confirmTrust(TrustReceipt receipt, Object scope) {
            if (receipt == null) return false;
            synchronized (receipt) {
                if (receipt.owner != this || receipt.scope != scope || receipt.confirmed) return false;
                while (true) {
                    int value = state.get();
                    if ((value & (CANCELLED | STOP_REQUESTED)) != 0) return false;
                    if (state.compareAndSet(value, value)) {
                        receipt.confirmed = true;
                        return true;
                    }
                }
            }
        }
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
    private final Cancellation cancelled;
    private final boolean mayWriteTrust;
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicBoolean screenLocked = new AtomicBoolean();
    private final AtomicBoolean cleanupPendingPublished = new AtomicBoolean();
    private volatile SshTerminalSession terminal;
    private volatile boolean released;
    private boolean creating;
    private boolean factoryAdmitted;
    private boolean cancellationActionBound;
    private boolean publishedStartEntered;
    private int deferredResult;
    private boolean cleanupReported;
    private int pendingWriteBytes;

    SshSessionOwner(Factory factory, String mode, int columns, int rows, SshTerminalState events,
            Dispatcher dispatcher, Lifecycle lifecycle) {
        this(factory, mode, columns, rows, events, dispatcher, lifecycle, false);
    }

    SshSessionOwner(Factory factory, String mode, int columns, int rows, SshTerminalState events,
            Dispatcher dispatcher, Lifecycle lifecycle, boolean deferCancellationAction) {
        if (factory == null || mode == null || mode.isEmpty() || mode.length() > 128
                || columns < 1 || columns > 4096 || rows < 1 || rows > 4096)
            throw new IllegalArgumentException("Invalid SSH terminal attachment");
        this.factory = factory;
        Cancellation sharedCancellation = factory.trustCancellation();
        cancelled = sharedCancellation == null ? new Cancellation() : sharedCancellation;
        mayWriteTrust = factory.mayWriteTrust();
        this.mode = mode;
        this.columns = columns;
        this.rows = rows;
        this.events = events;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        generation = SshTerminalState.nextGeneration();
        events.begin(generation, mode, cancelled);
        if (!deferCancellationAction) bindCancellationAction();
    }

    private void bindCancellationAction() {
        synchronized (this) {
            if (cancellationActionBound) return;
            cancellationActionBound = true;
        }
        factory.cancellationAction(this::requestStop);
    }

    /** The service must publish this exact owner before invoking this method. */
    CompletableFuture<String> startPublished(Runnable start) {
        synchronized (this) {
            if (publishedStartEntered) return connected;
            publishedStartEntered = true;
        }
        try {
            bindCancellationAction();
            if (cancelled.admitFactoryStart()) start.run();
            else { completeIfFactoryNeverAdmitted(); begin(); }
        } catch (RuntimeException | Error failure) {
            try { rejectStart(); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
        return connected;
    }

    void begin() {
        if (!begun.compareAndSet(false, true)) return;
        executor.execute(this::create);
    }

    void rejectStart() {
        boolean waitForTrust;
        Throwable cancellationFailure = null;
        synchronized (this) {
            cancelled.markStop(Cancellation.STOP_SERVICE);
            waitForTrust = mayWriteTrust && (creating || deferredResult != NO_DEFERRED_RESULT)
                    || terminal != null && !connected.isDone();
            if (waitForTrust && deferredResult == NO_DEFERRED_RESULT) deferredResult = DEFERRED_SERVICE_START;
            try { cancelled.cancel(Cancellation.STOP_SERVICE); }
            catch (RuntimeException | Error failure) { cancellationFailure = failure; }
        }
        if (!waitForTrust)
            connected.completeExceptionally(stopFailure());
        if (cancellationFailure != null) cancellationFailed(cancellationFailure);
        if (begun.compareAndSet(false, true)) executor.execute(() -> closeUnattached(null));
        else requestStop();
    }

    private void create() {
        SshNativeSession session = null;
        boolean transferred = false;
        try {
            boolean admitted;
            synchronized (this) {
                admitted = !cancelled.getAsBoolean();
                if (admitted) { creating = true; factoryAdmitted = true; }
            }
            if (!admitted) { closeUnattached(null); return; }
            session = factory.create(cancelled);
            if (session == null) throw new IllegalStateException("SSH session is required");
            events.reconnectTarget(generation, factory.reconnectTarget());
            SshTerminalSession controller = new SshTerminalSession(session, factory, executor,
                    new SshTerminalSession.Sink() {
                        @Override public void data(byte[] bytes, Runnable consumed) {
                            dispatcher.post(() -> {
                                try {
                                    SshTerminalSession current = terminal;
                                    if (current != null && current.publicationLive())
                                        events.data(generation, bytes);
                                } finally { consumed.run(); }
                            });
                        }
                        @Override public void state(String state, Long exitStatus, String reason) {
                            terminalState(state, exitStatus, reason);
                        }
                        @Override public void startupFailure(String reason) { terminalStartupFailure(reason); }
                        @Override public void cancellationUnconfirmed() { terminalCancellationUnconfirmed(); }
                    }, this::release, cancelled);
            synchronized (this) {
                terminal = controller;
                if (screenLocked.get()) controller.markScreenLocked();
                creating = false;
            }
            transferred = true;
            if (cancelled.getAsBoolean()) {
                completeDeferredResult();
                controller.stop();
            } else controller.start(columns, rows);
        } catch (Exception failure) {
            // Order proof diagnostics against Stop and screen-lock cancellation.
            synchronized (this) {
                Exception reported = failure instanceof VaultException ? failure
                        : new VaultException("SSH_FAILED", "SSH terminal could not start");
                boolean uncertain = reported instanceof VaultException
                        && "VAULT_COMMIT_UNCERTAIN".equals(((VaultException)reported).code);
                boolean trackedSaveFailure = reported instanceof VaultException && factory.tracksCredentialSave()
                        && SshTerminalSession.isCredentialSaveFailureCode(((VaultException)reported).code);
                if (!uncertain && deferredResult != NO_DEFERRED_RESULT
                        && cancelled.stopReason() == Cancellation.STOP_ORDINARY) {
                    if (!trackedSaveFailure) reported = stopFailure();
                } else if (!uncertain && deferredResult == DEFERRED_SCREEN_LOCK) {
                    reported = new VaultException("SSH_SCREEN_LOCKED", "SSH ended when the screen locked");
                } else if (!uncertain && deferredResult == DEFERRED_SERVICE_START) {
                    reported = new VaultException("SSH_SERVICE_START", "SSH service could not start");
                } else if (reported instanceof VaultException
                        && SshHostKeyAuthority.PROOF_INVALID.equals(((VaultException) reported).code)
                        && cancelled.getAsBoolean()) {
                    reported = stopFailure();
                }
                creating = false;
                connected.completeExceptionally(reported);
            }
            publishFailure("SSH_TERMINAL_START");
            if (!transferred) closeUnattached(session);
        } catch (Error failure) {
            synchronized (this) {
                creating = false;
                completeDeferredResult();
                connected.completeExceptionally(failure);
            }
            publishFailure("SSH_TERMINAL_START");
            if (!transferred) closeUnattached(session);
            throw failure;
        }
    }

    boolean matches(String expected) { return id.equals(expected); }

    boolean screenLocked() { return screenLocked.get(); }

    boolean stopForScreenLock(String expectedId) {
        return stopForScreenLock(expectedId, false);
    }

    /** A new locked-screen admission still reports its own screen-lock result. */
    boolean stopForScreenLockForLockedAdmission(String expectedId) {
        return stopForScreenLock(expectedId, true);
    }

    private boolean stopForScreenLock(String expectedId, boolean lockedAdmission) {
        boolean waitForTrust;
        boolean tracksSave;
        Throwable cancellationFailure = null;
        synchronized (this) {
            if (!matches(expectedId) || released || screenLocked.get()) return false;
            if (terminal != null && !terminal.markScreenLocked()) return false;
            screenLocked.set(true);
            cancelled.markStop(Cancellation.STOP_SCREEN);
            tracksSave = factory.tracksCredentialSave();
            waitForTrust = mayWriteTrust && (creating || deferredResult != NO_DEFERRED_RESULT)
                    || terminal != null && !connected.isDone();
            if (waitForTrust && !tracksSave && deferredResult == NO_DEFERRED_RESULT)
                deferredResult = DEFERRED_SCREEN_LOCK;
            try { cancelled.cancel(Cancellation.STOP_SCREEN); }
            catch (RuntimeException | Error failure) { cancellationFailure = failure; }
        }
        if (!tracksSave && !waitForTrust)
            connected.completeExceptionally(stopFailure());
        if (cancellationFailure != null) {
            cancellationFailed(cancellationFailure, lockedAdmission);
            return true;
        }
        dispatcher.post(() -> {
            if (released) return;
            SshTerminalSession current = terminal;
            Runnable publication = () -> deliverState("failed", null, "SSH_SCREEN_LOCKED", false);
            if (current == null) publication.run();
            else current.publishScreenLockPending(publication);
        });
        requestStop();
        return true;
    }

    private void completeDeferredResult() {
        synchronized (this) {
            if (deferredResult != NO_DEFERRED_RESULT && cancelled.stopReason() == Cancellation.STOP_ORDINARY)
                connected.completeExceptionally(stopFailure());
            else if (deferredResult == DEFERRED_SCREEN_LOCK)
                connected.completeExceptionally(new VaultException("SSH_SCREEN_LOCKED", "SSH ended when the screen locked"));
            else if (deferredResult == DEFERRED_SERVICE_START)
                connected.completeExceptionally(new VaultException("SSH_SERVICE_START", "SSH service could not start"));
        }
    }

    private static String terminalStartFailure(String reason, boolean tracksSave) {
        if (SshTerminalSession.isCredentialSaveFailureCode(reason) || "SSH_SCREEN_LOCKED".equals(reason))
            return reason;
        if (tracksSave && ("SSH_SESSION_CANCELLED".equals(reason) || "SSH_CALLBACK_CANCELLED".equals(reason)))
            return reason;
        return "SSH_TERMINAL_START";
    }

    private VaultException stopFailure() {
        String reason = cancelled.stopCode();
        if ("SSH_SERVICE_START".equals(reason))
            return new VaultException("SSH_SERVICE_START", "SSH service could not start");
        if ("SSH_SCREEN_LOCKED".equals(reason))
            return new VaultException("SSH_SCREEN_LOCKED", "SSH ended when the screen locked");
        return new VaultException("SSH_CANCELLED", "SSH connection was cancelled");
    }

    CompletableFuture<Void> write(int expected, byte[] bytes) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        final byte[] copy;
        synchronized (this) {
            if (expected != generation || cancelled.stopRequested() || bytes.length > SshTerminalSession.INPUT_LIMIT - pendingWriteBytes) {
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
        try {
            synchronized (this) {
                if (released) return;
                cancelled.cancel();
            }
        } catch (RuntimeException | Error failure) { cancellationFailed(failure); }
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

    private void cancellationFailed(Throwable failure) {
        cancellationFailed(failure, false);
    }

    private void cancellationFailed(Throwable failure, boolean lockedAdmission) {
        boolean secondaryFailure = false;
        try { publishFailure("SSH_TERMINAL_CLEANUP_PENDING"); }
        catch (RuntimeException | Error publication) {
            secondaryFailure = true;
            if (publication != failure) failure.addSuppressed(publication);
        }
        try {
            SshTerminalSession current = terminal;
            if (current != null) current.sharedCancellationFailed();
            else if (!begun.get()) dispatcher.post(() -> { completeIfFactoryNeverAdmitted(); begin(); });
            else completeIfFactoryNeverAdmitted();
        } catch (RuntimeException | Error scheduling) {
            secondaryFailure = true;
            if (scheduling != failure) failure.addSuppressed(scheduling);
        }
        // A new admission may report its own locked-screen result only if no other step failed.
        if (lockedAdmission && !secondaryFailure && cancelled.invalidationFailed()) return;
        if (failure instanceof Error) throw (Error)failure;
        throw (RuntimeException)failure;
    }

    private void completeIfFactoryNeverAdmitted() {
        boolean complete;
        synchronized (this) { complete = !factoryAdmitted && terminal == null; }
        if (complete) connected.completeExceptionally(stopFailure());
    }

    private SshTerminalSession requireTerminal(int expected) {
        if (expected != generation || cancelled.stopRequested() || terminal == null || terminal.closed())
            throw new IllegalStateException("Terminal generation is stale or closed");
        return terminal;
    }

    private void closeUnattached(SshNativeSession session) {
        if (!cancelled.admitUnattachedCleanup()) {
            if (cancelled.invalidationFailed()) {
                if (!cleanupReported) { cleanupReported = true; publishFailure("SSH_TERMINAL_CLEANUP_PENDING"); }
            } else executor.schedule(() -> closeUnattached(session), 100, TimeUnit.MILLISECONDS);
            return;
        }
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
        connected.completeExceptionally(stopFailure());
        release();
    }

    private void publishFailure(String reason) {
        String selectedReason = screenLocked.get() && !"SSH_TERMINAL_CLEANUP_PENDING".equals(reason)
                ? "SSH_SCREEN_LOCKED" : reason;
        dispatchState("failed", null, selectedReason);
    }

    private void terminalState(String value, Long exitStatus, String reason) {
        if ("running".equals(value) && !cancelled.admitOutput()) {
            if (cancelled.invalidationFailed()) connected.completeExceptionally(stopFailure());
            return;
        }
        try { dispatchState(value, exitStatus, reason); }
        finally {
            if ("running".equals(value)) connected.complete(mode);
            else if (!"SSH_TERMINAL_CLEANUP_PENDING".equals(reason)
                    || !factory.tracksCredentialSave() && (terminal == null || !terminal.startupFailureUncertain())) {
                String code = reason == null && cancelled.stopRequested() ? stopFailure().code
                        : terminalStartFailure(reason, factory.tracksCredentialSave());
                if (cancelled.stopRequested() && !"VAULT_COMMIT_UNCERTAIN".equals(code)
                        && (cancelled.stopReason() == Cancellation.STOP_SERVICE
                        || cancelled.stopReason() == Cancellation.STOP_ORDINARY && "SSH_SCREEN_LOCKED".equals(code)))
                    code = stopFailure().code;
                connected.completeExceptionally(new VaultException(code, "SSH terminal closed before running"));
            }
        }
    }

    private void terminalStartupFailure(String reason) {
        if (!SshTerminalSession.isCredentialSaveFailureCode(reason)
                || !"VAULT_COMMIT_UNCERTAIN".equals(reason) && !factory.tracksCredentialSave()) return;
        if (!"VAULT_COMMIT_UNCERTAIN".equals(reason) && cancelled.stopRequested()
                && cancelled.stopReason() == Cancellation.STOP_SERVICE) {
            connected.completeExceptionally(stopFailure());
        } else connected.completeExceptionally(new VaultException(reason, "SSH terminal could not start"));
    }

    private void terminalCancellationUnconfirmed() { connected.completeExceptionally(stopFailure()); }

    private void dispatchState(String value, Long exitStatus, String reason) {
        boolean pending = "SSH_TERMINAL_CLEANUP_PENDING".equals(reason);
        if (pending && !cleanupPendingPublished.compareAndSet(false, true)) return;
        dispatcher.post(() -> {
            if (deliverState(value, exitStatus, reason, pending)) lifecycle.state(value);
        });
    }

    private boolean deliverState(String value, Long exitStatus, String reason, boolean pending) {
        synchronized (events) {
            if (pending && released) return false;
            if (!pending && ("failed".equals(value) || "running".equals(value))
                    && cleanupPendingPublished.get()) return false;
            events.state(generation, value, exitStatus, reason);
            return true;
        }
    }

    private void release() {
        if (released) return;
        released = true;
        terminal = null;
        executor.shutdown();
        dispatcher.post(lifecycle::released);
    }
}
