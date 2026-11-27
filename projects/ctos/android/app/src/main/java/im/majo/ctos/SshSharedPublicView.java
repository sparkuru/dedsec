package im.majo.ctos;

import java.util.IdentityHashMap;

/** Owns the original public lease while independently owned sessions retire their borrows.
 * Other native consumers, such as a config evaluator, retain their separate cleanup duties. */
final class SshSharedPublicView implements AutoCloseable {
    private static final int CANCEL_IDLE = 0, CANCEL_RETRYABLE = 1, CANCEL_INVALIDATING = 2,
            CANCEL_REVOKING = 3, CANCEL_CONFIRMED = 4, CANCEL_UNCONFIRMED = 5;
    private final Thread owner = Thread.currentThread();
    private final SshRoleContext.PublicView roleView;
    private final SshPublicFileLease managedLease;
    private final SshNativeSession.ViewBinding source;
    private final Runnable revoke;
    private final IdentityHashMap<SshNativeCallbackAdapter, Binding> bindings = new IdentityHashMap<>();
    private boolean sealed;
    private boolean cancelled;
    private boolean closed;
    private boolean closing;
    private int cancellationState;
    private Throwable cancellationFailure;
    private Throwable closeFailure;
    private int pending;

    SshSharedPublicView(SshPublicFileLease lease) {
        if (lease == null) throw new IllegalArgumentException("Missing SSH public view");
        managedLease = lease;
        roleView = lease;
        source = new SshNativeSession.ViewBinding() {
            @Override public long token() throws VaultException { return lease.nativeToken(); }
            @Override public boolean active() { return lease.active(); }
            @Override public void close() throws VaultException { lease.close(); }
        };
        revoke = lease::revoke;
    }

    /** JVM fixture seam; production always retains the exact managed lease above. */
    SshSharedPublicView(SshRoleContext.PublicView roleView, SshNativeSession.ViewBinding source,
            Runnable revoke) {
        if (roleView == null || source == null || roleView != source || revoke == null)
            throw new IllegalArgumentException("Missing SSH public view");
        managedLease = null;
        this.roleView = roleView;
        this.source = source;
        this.revoke = revoke;
    }

    boolean hasManagedLease() { return managedLease != null && roleView == managedLease; }

    Binding issue(SshNativeCallbackAdapter callback) throws VaultException {
        requireOwner();
        if (callback == null || callback.eventLoopThread() != owner
                || !callback.ownsPublicView(roleView)) throw invalid();
        callback.requireNativeSessionLive();
        synchronized (this) {
            if (sealed || cancelled || closed || !roleView.active() || !source.active())
                throw unavailable();
            if (bindings.containsKey(callback) || bindings.size() >= SshRoleContext.MAX_HOPS)
                throw invalid();
            Binding binding = new Binding(callback);
            bindings.put(callback, binding);
            pending++;
            return binding;
        }
    }

    void seal() throws VaultException {
        requireOwner();
        synchronized (this) { sealed = true; }
    }

    /** Cancellation revokes the registered lease without waiting for session cleanup. */
    void cancel() {
        synchronized (this) {
            if (closed) return;
            cancelled = true;
            sealed = true;
            if (cancellationState == CANCEL_CONFIRMED) return;
            if (cancellationState == CANCEL_UNCONFIRMED || closeFailure != null)
                throw new IllegalStateException("SSH shared view cancellation is unconfirmed",
                        cancellationFailure != null ? cancellationFailure : closeFailure);
            if (closing || cancellationState == CANCEL_INVALIDATING || cancellationState == CANCEL_REVOKING) {
                if (closing && cancellationState == CANCEL_IDLE) cancellationState = CANCEL_RETRYABLE;
                throw new IllegalStateException("SSH shared view cleanup is in progress");
            }
            cancellationState = CANCEL_INVALIDATING;
        }
        final int begun;
        try { begun = SshNativeCallbackAdapter.beginJumpCleanupInvalidation(); }
        catch (RuntimeException | Error failure) { failCancellation(failure); throw failure; }
        if (begun == -1) {
            synchronized (this) { cancellationState = CANCEL_RETRYABLE; }
            throw new UnsatisfiedLinkError("SSH shared view invalidation did not begin");
        }
        if (begun != 0 && begun != 1) {
            IllegalStateException failure = new IllegalStateException("SSH shared view invalidation is unconfirmed");
            failCancellation(failure);
            throw failure;
        }
        if (begun == 1) {
            try { SshNativeCallbackAdapter.endJumpEffectInvalidation(true); }
            catch (RuntimeException | Error failure) { failCancellation(failure); throw failure; }
        }
        synchronized (this) { cancellationState = CANCEL_REVOKING; }
        try { revoke.run(); }
        catch (RuntimeException | Error failure) { failCancellation(failure); throw failure; }
        synchronized (this) { cancellationState = CANCEL_CONFIRMED; }
    }

    private void failCancellation(Throwable failure) {
        synchronized (this) {
            cancellationState = CANCEL_UNCONFIRMED;
            cancellationFailure = failure;
        }
    }

    @Override public void close() throws VaultException {
        requireOwner();
        synchronized (this) {
            if (closed) return;
            if (!sealed || pending != 0 || closing || closeFailure != null
                    || cancellationState != CANCEL_IDLE && cancellationState != CANCEL_CONFIRMED)
                throw new VaultException("SSH_SESSION_CLEANUP", "SSH sessions still own the shared view");
            closing = true;
        }
        try {
            source.close();
            synchronized (this) { closed = true; }
        } catch (RuntimeException | Error failure) {
            synchronized (this) { closeFailure = failure; }
            throw failure;
        } finally {
            synchronized (this) { closing = false; }
        }
    }

    final class Binding implements SshNativeSession.ViewBinding {
        private final SshNativeCallbackAdapter callback;
        private boolean adopted;
        private boolean retired;

        private Binding(SshNativeCallbackAdapter callback) { this.callback = callback; }

        @Override public long token() throws VaultException {
            requireOwner();
            synchronized (SshSharedPublicView.this) {
                if (retired || cancelled || closed || !roleView.active() || !source.active())
                    throw unavailable();
                return source.token();
            }
        }

        @Override public boolean active() {
            synchronized (SshSharedPublicView.this) {
                return !retired && !cancelled && !closed && roleView.active() && source.active();
            }
        }

        void requireForOpen(SshNativeCallbackAdapter actual) throws VaultException {
            requireOwner();
            synchronized (SshSharedPublicView.this) {
                if (actual != callback || retired || adopted) throw invalid();
            }
        }

        void nativeAdopted(SshNativeCallbackAdapter actual) throws VaultException {
            requireOwner();
            synchronized (SshSharedPublicView.this) {
                if (actual != callback || retired) throw invalid();
                adopted = true;
            }
        }

        /** A positive native handle keeps this binding until that owner cleans up. */
        void abortUnadopted() throws VaultException {
            requireOwner();
            synchronized (SshSharedPublicView.this) {
                if (adopted || retired) return;
                retired = true;
                pending--;
                bindings.remove(callback);
            }
        }

        @Override public void close() throws VaultException {
            requireOwner();
            synchronized (SshSharedPublicView.this) {
                if (retired) return;
                if (!adopted) throw invalid();
                retired = true;
                pending--;
            }
        }
    }

    private void requireOwner() throws VaultException {
        if (Thread.currentThread() != owner)
            throw new VaultException("SSH_SESSION_THREAD", "SSH cleanup requires its owner");
    }

    private static VaultException invalid() {
        return new VaultException("SSH_SESSION_ARGUMENTS", "The shared SSH view binding is invalid");
    }

    private static VaultException unavailable() {
        return new VaultException("SSH_FILE_VIEW", "The SSH file view was revoked");
    }
}
