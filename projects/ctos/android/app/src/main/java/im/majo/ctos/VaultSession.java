package im.majo.ctos;

/** Monotonic foreground session; deadlines are checked at every protected effect. */
final class VaultSession {
    interface Clock { long now(); }
    interface Revocation {
        boolean active();
        void revoke();
        /** Native revocation was acknowledged; outstanding borrowers may still own it. */
        default boolean revocationConfirmed() { return false; }
    }
    interface Invalidation {
        int begin();
        void end();
    }
    private static final Invalidation NATIVE_INVALIDATION = new Invalidation() {
        @Override public int begin() { return SshNativeCallbackAdapter.beginJumpCleanupInvalidation(); }
        @Override public void end() { SshNativeCallbackAdapter.endJumpEffectInvalidation(true); }
    };
    private final Clock clock;
    private final Invalidation invalidation;
    private long backgroundAt = -1;
    private boolean foreground;
    private int timeoutSeconds = 30;
    private long epoch;
    private byte[] key;
    private Revocation revocation;
    private Throwable invalidationFailure;
    private boolean invalidationRetryable;
    private boolean locking;

    VaultSession(Clock clock) { this(clock, NATIVE_INVALIDATION); }
    VaultSession(Clock clock, Invalidation invalidation) {
        this.clock = clock;
        this.invalidation = invalidation;
    }

    synchronized long epoch() { check(); return epoch; }
    /** Non-blocking metadata for callers already ordering cleanup under this monitor. */
    synchronized long cleanupEpoch() { return epoch; }
    synchronized boolean invalidationUnconfirmed() { return invalidationFailure != null; }
    synchronized boolean unlocked() { check(); return key != null; }
    synchronized boolean foreground() { return foreground; }
    synchronized int timeout() { return timeoutSeconds; }
    synchronized long remainingBackgroundMillis() {
        return backgroundAt < 0 ? 0 : Math.max(0, timeoutSeconds * 1000L - (clock.now() - backgroundAt));
    }

    synchronized void unlock(byte[] material) throws VaultException {
        if (!foreground) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to unlock");
        if (invalidationFailure != null) throw unavailable();
        lock();
        key = material.clone();
    }
    synchronized boolean unlockIfEpoch(long expected, byte[] material) {
        check();
        if (invalidationFailure != null || epoch != expected || !foreground) return false;
        lock();
        key = material.clone();
        return true;
    }

    synchronized byte[] key() throws VaultException {
        check();
        if (!foreground) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to use secrets");
        if (key == null) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        return key.clone();
    }

    synchronized void assertEpoch(long expected) throws VaultException {
        check();
        if (epoch != expected) throw new VaultException("VAULT_CHANGED", "The vault context changed; try again");
    }

    synchronized void foreground(boolean value) {
        try {
            check();
            if (foreground == value) return;
            if (value) {
                foreground = true;
                backgroundAt = -1;
            } else invalidate(false, () -> {
                foreground = false;
                backgroundAt = clock.now();
                check();
            });
        } catch (RuntimeException | Error failure) {
            if (!value) {
                foreground = false;
                backgroundAt = clock.now();
            }
            throw failure;
        }
    }

    synchronized void timeout(int seconds) throws VaultException {
        if (seconds < 0 || seconds > 86400) throw new VaultException("PARAMETERS", "Invalid background timeout");
        check();
        timeoutSeconds = seconds;
        check();
    }

    synchronized void lock() {
        if (locking) throw new IllegalStateException("Reentrant vault revocation");
        locking = true;
        try {
            Revocation owned = revocation;
            invalidate(true, () -> {
                lockLocally();
                if (owned != null) {
                    if (owned.active()) owned.revoke();
                    if (!owned.revocationConfirmed())
                        throw new IllegalStateException("SSH file view revocation is unconfirmed");
                }
            });
            revocation = null;
        } finally { locking = false; }
    }

    synchronized void attachRevocation(long expected, Revocation owned) throws VaultException {
        assertEpoch(expected);
        if (key == null || !foreground)
            throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (revocation != null) {
            if (revocation.active()) throw new VaultException("BUSY", "An SSH file view is already active");
            if (!revocation.revocationConfirmed()) {
                lockLocally();
                invalidationFailure = new IllegalStateException("SSH file view revocation is unconfirmed");
                throw unavailable();
            }
        }
        revocation = owned;
    }

    synchronized Revocation attachedRevocation(long expected) throws VaultException {
        assertEpoch(expected);
        if (key == null || !foreground) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        return revocation;
    }

    /** Only the exact bound trust writer may retain its already attached loan. */
    synchronized boolean advanceTrustEpoch(long expected, Revocation owned) {
        check();
        if (invalidationFailure != null || epoch != expected || key == null || !foreground || owned == null
                || revocation != owned || !owned.active()) return false;
        invalidate(false, () -> epoch++);
        return true;
    }

    private void lockLocally() {
        wipeKey();
        epoch++;
    }

    private void wipeKey() {
        VaultCodec.wipe(key);
        key = null;
    }

    /* A successful begin is paired once, even if a nested revocation fails.
     * An exception from begin/end is never a reason to repeat that JNI call. */
    private void invalidate(boolean retryAllowed, Runnable change) {
        if (invalidationFailure != null && !(retryAllowed && invalidationRetryable))
            throw new IllegalStateException("Vault revocation is unconfirmed", invalidationFailure);
        long initialEpoch = epoch;
        boolean begun = false;
        boolean unstarted = false;
        Throwable failure = null;
        try {
            int result = invalidation.begin();
            if (result == -1) {
                unstarted = true;
                throw new IllegalStateException("Vault invalidation did not begin");
            }
            if (result != 0 && result != 1)
                throw new IllegalStateException("Invalid vault invalidation result");
            begun = result == 1;
            change.run();
        } catch (RuntimeException | Error caught) {
            failure = caught;
            if (caught == invalidationFailure && invalidationRetryable) unstarted = true;
        }
        if (begun) {
            try { invalidation.end(); }
            catch (RuntimeException | Error endFailure) {
                if (failure == null) failure = endFailure;
                else if (failure != endFailure) failure.addSuppressed(endFailure);
                unstarted = false;
            }
        }
        if (failure != null) {
            if (epoch == initialEpoch) lockLocally();
            else wipeKey();
            invalidationFailure = failure;
            invalidationRetryable = unstarted;
            if (failure instanceof Error) throw (Error) failure;
            throw (RuntimeException) failure;
        }
        invalidationFailure = null;
        invalidationRetryable = false;
    }

    private static VaultException unavailable() {
        return new VaultException("VAULT_LOCKED", "The previous vault session is not safely closed");
    }

    private void check() {
        if (key != null && backgroundAt >= 0
                && clock.now() - backgroundAt >= timeoutSeconds * 1000L) lock();
    }
}
