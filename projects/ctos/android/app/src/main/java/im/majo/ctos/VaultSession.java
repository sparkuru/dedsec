package im.majo.ctos;

/** Monotonic foreground session; deadlines are checked at every protected effect. */
final class VaultSession {
    interface Clock { long now(); }
    interface Revocation {
        boolean active();
        void revoke();
    }
    private final Clock clock;
    private long backgroundAt = -1;
    private boolean foreground;
    private int timeoutSeconds = 30;
    private long epoch;
    private byte[] key;
    private Revocation revocation;

    VaultSession(Clock clock) { this.clock = clock; }

    synchronized long epoch() { check(); return epoch; }
    synchronized boolean unlocked() { check(); return key != null; }
    synchronized boolean foreground() { return foreground; }
    synchronized int timeout() { return timeoutSeconds; }
    synchronized long remainingBackgroundMillis() {
        return backgroundAt < 0 ? 0 : Math.max(0, timeoutSeconds * 1000L - (clock.now() - backgroundAt));
    }

    synchronized void unlock(byte[] material) throws VaultException {
        if (!foreground) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to unlock");
        lock();
        key = material.clone();
    }
    synchronized boolean unlockIfEpoch(long expected, byte[] material) {
        check();
        if (epoch != expected || !foreground) return false;
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
        check();
        if (foreground == value) return;
        foreground = value;
        if (value) backgroundAt = -1;
        else { backgroundAt = clock.now(); check(); }
    }

    synchronized void timeout(int seconds) throws VaultException {
        if (seconds < 0 || seconds > 86400) throw new VaultException("PARAMETERS", "Invalid background timeout");
        check();
        timeoutSeconds = seconds;
        check();
    }

    synchronized void lock() {
        VaultCodec.wipe(key);
        key = null;
        epoch++;
        Revocation owned = revocation;
        revocation = null;
        if (owned != null) owned.revoke();
    }

    synchronized void attachRevocation(long expected, Revocation owned) throws VaultException {
        assertEpoch(expected);
        if (key == null || !foreground)
            throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (revocation != null && revocation.active())
            throw new VaultException("BUSY", "An SSH file view is already active");
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
        if (epoch != expected || key == null || !foreground || owned == null
                || revocation != owned || !owned.active()) return false;
        epoch++;
        return true;
    }

    private void check() {
        if (key != null && backgroundAt >= 0
                && clock.now() - backgroundAt >= timeoutSeconds * 1000L) lock();
    }
}
