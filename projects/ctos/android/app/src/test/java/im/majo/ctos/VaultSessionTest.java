package im.majo.ctos;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class VaultSessionTest {
    private static final class Lease implements VaultSession.Revocation {
        boolean active = true;
        int revoked;
        @Override public boolean active() { return active; }
        @Override public void revoke() { active = false; revoked++; }
        @Override public boolean revocationConfirmed() { return !active; }
    }
    private long now;

    private static final class Invalidation implements VaultSession.Invalidation {
        int result = 1;
        int begins, ends, failBeginAt = -1, failEndAt = -1, unstartedAt = -1;
        final RuntimeException beginFailure = new IllegalStateException("begin failed");
        final RuntimeException endFailure = new IllegalStateException("end failed");
        @Override public int begin() {
            begins++;
            if (begins == failBeginAt) throw beginFailure;
            return begins == unstartedAt ? -1 : result;
        }
        @Override public void end() {
            ends++;
            if (ends == failEndAt) throw endFailure;
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private VaultSession session(Invalidation invalidation) throws Exception {
        VaultSession session = new VaultSession(() -> now, invalidation);
        session.foreground(true);
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 0x5a);
        session.unlock(material);
        return session;
    }

    private static void lockedWithOwner(VaultSession session, byte[] oldKey,
            long oldEpoch, VaultSession.Revocation owned) throws Exception {
        assertArrayEquals(new byte[oldKey.length], oldKey);
        assertFalse(session.unlocked());
        assertEquals(oldEpoch + 1, session.epoch());
        assertSame(owned, field(session, "revocation"));
        assertFalse(session.unlockIfEpoch(session.epoch(), new byte[32]));
        try { session.unlock(new byte[32]); fail("unconfirmed cleanup must block unlock"); }
        catch (VaultException expected) { assertEquals("VAULT_LOCKED", expected.code); }
        try { session.attachRevocation(session.epoch(), new Lease()); fail("unconfirmed owner must remain"); }
        catch (VaultException expected) { assertEquals("VAULT_LOCKED", expected.code); }
    }
    private VaultSession session() throws Exception {
        VaultSession session = new VaultSession(() -> now);
        session.foreground(true);
        session.unlock(new byte[32]);
        return session;
    }

    @Test public void defaultBackgroundDeadlineIsMonotonicAndForegroundNavigationReuses() throws Exception {
        VaultSession session = session();
        session.foreground(true);
        assertTrue(session.unlocked());
        session.foreground(false);
        now = 29999;
        session.foreground(true);
        assertTrue(session.unlocked());
        session.foreground(false);
        now = 59999;
        session.foreground(true);
        assertFalse(session.unlocked());
    }

    @Test public void delayedCallbackAndLargerSettingCannotRestoreExpiredKey() throws Exception {
        VaultSession session = session();
        session.foreground(false);
        now = 31000;
        session.timeout(90);
        // The old deadline must be checked before changing a timeout.
        assertFalse(session.unlocked());
        session.foreground(true);
        assertFalse(session.unlocked());
    }

    @Test public void shorteningUsesOriginalBackgroundOrigin() throws Exception {
        VaultSession session = session();
        now = 100;
        session.foreground(false);
        now = 10100;
        session.timeout(5);
        assertFalse(session.unlocked());
        assertEquals(0, session.remainingBackgroundMillis());
    }

    @Test public void zeroImmediatelyLocksAndRestartIsLocked() throws Exception {
        VaultSession session = session();
        session.timeout(0);
        session.foreground(false);
        assertFalse(session.unlocked());
        VaultSession restarted = new VaultSession(() -> now);
        restarted.timeout(0);
        assertFalse(restarted.unlocked());
    }

    @Test public void timeoutBoundsRejectWithoutChangingSettings() throws Exception {
        VaultSession session = session();
        for (int value : new int[] {-1, 86401}) {
            try { session.timeout(value); fail(); } catch (VaultException failure) { assertEquals("PARAMETERS", failure.code); }
        }
        assertEquals(30, session.timeout());
    }

    @Test public void backgroundOperationRequiresForegroundEvenInsideGrace() throws Exception {
        VaultSession session = session();
        session.foreground(false);
        assertTrue(session.unlocked());
        try { session.key(); fail(); } catch (VaultException error) { assertEquals("VAULT_FOREGROUND_REQUIRED", error.code); }
    }

    @Test public void lockedStartUnlockWithZeroGraceKeepsRequestEpochThroughExternalUi() throws Exception {
        VaultSession session = new VaultSession(() -> now);
        session.foreground(true);
        session.timeout(0);
        long admitted = session.epoch();
        session.foreground(false);
        now = 1000;
        assertFalse(session.unlocked());
        assertEquals(admitted, session.epoch());
        session.foreground(true);
        session.assertEpoch(admitted);
        session.unlock(new byte[32]);
        assertTrue(session.unlocked());
    }

    @Test public void protectedLeaseRevokesOnDirectLockAndReplacementUnlock() throws Exception {
        VaultSession session = session();
        Lease first = new Lease();
        session.attachRevocation(session.epoch(), first);
        session.lock();
        assertFalse(first.active); assertEquals(1, first.revoked);
        session.lock(); assertEquals(1, first.revoked);
        session.unlock(new byte[32]);
        Lease next = new Lease();
        session.attachRevocation(session.epoch(), next);
        session.unlock(new byte[32]);
        assertFalse(next.active); assertEquals(1, next.revoked);
    }

    @Test public void ordinaryRepeatedLocksEachAdvanceTheEpoch() throws Exception {
        VaultSession session = session();
        long epoch = session.epoch();
        session.lock(); assertEquals(epoch + 1, session.epoch());
        session.lock(); assertEquals(epoch + 2, session.epoch());
    }

    @Test public void expiredSessionRevokesLeaseAndRejectsLateRegistration() throws Exception {
        VaultSession session = session();
        long epoch = session.epoch();
        Lease owned = new Lease(); session.attachRevocation(epoch, owned);
        session.foreground(false); now = 30000;
        assertFalse(session.unlocked()); assertFalse(owned.active); assertEquals(1, owned.revoked);
        try { session.attachRevocation(epoch, new Lease()); fail(); }
        catch (VaultException failure) { assertEquals("VAULT_CHANGED", failure.code); }
    }

    @Test public void ownTrustCommitAdvancesEpochWithoutExtendingOrReplacingItsLoan() throws Exception {
        VaultSession session = session();
        Lease first = new Lease();
        long epoch = session.epoch();
        session.attachRevocation(epoch, first);
        assertSame(first, session.attachedRevocation(epoch));
        assertFalse(session.advanceTrustEpoch(epoch, new Lease()));
        assertEquals(epoch, session.epoch());
        assertTrue(session.advanceTrustEpoch(epoch, first));
        assertEquals(epoch + 1, session.epoch());
        assertSame(first, session.attachedRevocation(epoch + 1));
        assertTrue(first.active); assertEquals(0, first.revoked);
        assertFalse(session.advanceTrustEpoch(epoch, first));
        session.lock();
        assertFalse(first.active); assertEquals(1, first.revoked);
        assertFalse(session.advanceTrustEpoch(epoch + 1, first));
        assertFalse(session.unlocked());
    }

    @Test public void trustContinuationCannotResurrectRevokedReplacedOrBackgroundState() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            VaultSession session = session();
            Lease first = new Lease(); long epoch = session.epoch();
            session.attachRevocation(epoch, first);
            if (mode == 0) first.revoke();
            if (mode == 1) { first.revoke(); session.attachRevocation(epoch, new Lease()); }
            if (mode == 2) session.foreground(false);
            if (mode == 3) { session.lock(); session.unlock(new byte[32]); }
            assertFalse(session.advanceTrustEpoch(epoch, first));
            assertEquals(mode == 3 ? epoch + 2 : epoch, session.epoch());
            session.lock();
        }
    }

    @Test public void activeLeaseCannotBeReplacedAndClosedLeaseCan() throws Exception {
        VaultSession session = session();
        Lease first = new Lease(); session.attachRevocation(session.epoch(), first);
        Lease second = new Lease();
        try { session.attachRevocation(session.epoch(), second); fail(); }
        catch (VaultException failure) { assertEquals("BUSY", failure.code); }
        assertTrue(first.active); assertTrue(second.active);
        first.revoke(); session.attachRevocation(session.epoch(), second);
        session.lock(); assertFalse(second.active); assertEquals(1, first.revoked);
    }

    @Test public void unknownBeginOrEndLocksLocallyAndNeverRepeatsInvalidation() throws Exception {
        for (boolean failEnd : new boolean[]{false, true}) {
            Invalidation invalidation = new Invalidation();
            VaultSession session = session(invalidation);
            Lease owned = new Lease();
            long epoch = session.epoch();
            session.attachRevocation(epoch, owned);
            byte[] oldKey = (byte[]) field(session, "key");
            if (failEnd) invalidation.failEndAt = invalidation.ends + 1;
            else invalidation.failBeginAt = invalidation.begins + 1;
            try { session.lock(); fail(); }
            catch (IllegalStateException expected) {
                assertSame(failEnd ? invalidation.endFailure : invalidation.beginFailure, expected);
            }
            int begins = invalidation.begins, ends = invalidation.ends;
            lockedWithOwner(session, oldKey, epoch, owned);
            assertEquals(failEnd ? 1 : 0, owned.revoked);
            try { session.lock(); fail(); }
            catch (IllegalStateException expected) {
                assertSame(failEnd ? invalidation.endFailure : invalidation.beginFailure, expected.getCause());
            }
            assertEquals(begins, invalidation.begins);
            assertEquals(ends, invalidation.ends);
        }
    }

    @Test public void definitelyUnstartedInvalidationCanBeRetriedByOriginalSession() throws Exception {
        Invalidation invalidation = new Invalidation();
        VaultSession session = session(invalidation);
        Lease owned = new Lease(); long epoch = session.epoch();
        session.attachRevocation(epoch, owned);
        byte[] oldKey = (byte[]) field(session, "key");
        invalidation.unstartedAt = invalidation.begins + 1;
        try { session.lock(); fail(); } catch (IllegalStateException expected) { }
        lockedWithOwner(session, oldKey, epoch, owned);
        assertTrue(owned.active);
        assertEquals(0, owned.revoked);
        session.lock();
        assertEquals(1, owned.revoked);
        assertNull(field(session, "revocation"));
        session.unlock(new byte[32]);
        assertTrue(session.unlocked());
    }

    @Test public void failingRevokerAndOuterEndKeepOriginalFailureAndOwner() throws Exception {
        for (boolean localInactive : new boolean[]{false, true}) {
            Invalidation invalidation = new Invalidation();
            VaultSession session = session(invalidation);
            RuntimeException revokeFailure = new IllegalStateException("revoke failed");
            int[] calls = {0}; boolean[] active = {true};
            VaultSession.Revocation owned = new VaultSession.Revocation() {
                @Override public boolean active() { return active[0]; }
                @Override public void revoke() {
                    calls[0]++;
                    if (localInactive) active[0] = false;
                    throw revokeFailure;
                }
            };
            long epoch = session.epoch(); session.attachRevocation(epoch, owned);
            byte[] oldKey = (byte[]) field(session, "key");
            invalidation.failEndAt = invalidation.ends + 1;
            try { session.lock(); fail(); }
            catch (IllegalStateException expected) {
                assertSame(revokeFailure, expected);
                assertArrayEquals(new Throwable[]{invalidation.endFailure}, expected.getSuppressed());
            }
            int begins = invalidation.begins, ends = invalidation.ends;
            lockedWithOwner(session, oldKey, epoch, owned);
            try { session.lock(); fail(); } catch (IllegalStateException expected) { }
            assertEquals(1, calls[0]);
            assertEquals(begins, invalidation.begins);
            assertEquals(ends, invalidation.ends);
        }
    }

    @Test public void inactiveOwnerWithoutConfirmationCannotBeReplacedOrRetried() throws Exception {
        Invalidation invalidation = new Invalidation();
        VaultSession session = session(invalidation);
        boolean[] active = {true}; int[] calls = {0};
        VaultSession.Revocation owned = new VaultSession.Revocation() {
            @Override public boolean active() { return active[0]; }
            @Override public void revoke() { calls[0]++; active[0] = false; }
        };
        long epoch = session.epoch(); session.attachRevocation(epoch, owned);
        byte[] oldKey = (byte[]) field(session, "key");
        owned.revoke(); // Local inactivity is not native revocation confirmation.
        try { session.attachRevocation(epoch, new Lease()); fail(); }
        catch (VaultException expected) { assertEquals("VAULT_LOCKED", expected.code); }
        lockedWithOwner(session, oldKey, epoch, owned);
        try { session.lock(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, calls[0]);
    }

    @Test public void inFlightRevocationIsObservedAfterItsAcknowledgement() throws Exception {
        for (boolean lock : new boolean[]{false, true}) {
            VaultSession session = session(new Invalidation());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            CountDownLatch observing = new CountDownLatch(1);
            class PausedLease implements VaultSession.Revocation {
                volatile boolean active = true;
                boolean confirmed;
                @Override public boolean active() { return active; }
                @Override public synchronized void revoke() {
                    active = false;
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("revocation timeout");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    confirmed = true;
                }
                @Override public boolean revocationConfirmed() {
                    observing.countDown();
                    synchronized (this) { return confirmed; }
                }
            }
            PausedLease owned = new PausedLease(); Lease replacement = new Lease();
            long epoch = session.epoch(); session.attachRevocation(epoch, owned);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread revoker = new Thread(() -> {
                try { owned.revoke(); } catch (Throwable failure) { error.set(failure); }
            });
            Thread user = new Thread(() -> {
                try {
                    if (lock) session.lock();
                    else session.attachRevocation(epoch, replacement);
                } catch (Throwable failure) { error.set(failure); }
            });
            revoker.setDaemon(true); user.setDaemon(true);
            revoker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                user.start();
                assertTrue(observing.await(5, TimeUnit.SECONDS));
                assertTrue(user.isAlive());
            } finally {
                release.countDown();
                revoker.join(5000);
                if (user.getState() != Thread.State.NEW) user.join(5000);
            }
            assertFalse(revoker.isAlive()); assertFalse(user.isAlive());
            assertNull(error.get());
            if (lock) {
                assertFalse(session.unlocked());
                assertNull(field(session, "revocation"));
                session.unlock(new byte[32]);
            } else assertSame(replacement, session.attachedRevocation(epoch));
        }
    }

    @Test public void backgroundAndNestedTimeoutFailuresLeaveSessionLockedAndBackground() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Invalidation invalidation = new Invalidation();
            VaultSession session = session(invalidation);
            Lease owned = new Lease(); long epoch = session.epoch();
            session.attachRevocation(epoch, owned);
            byte[] oldKey = (byte[]) field(session, "key");
            if (mode == 0) invalidation.failBeginAt = invalidation.begins + 1;
            if (mode == 1) invalidation.failEndAt = invalidation.ends + 1;
            if (mode == 2) {
                session.timeout(0);
                invalidation.failBeginAt = invalidation.begins + 2;
            }
            try { session.foreground(false); fail(); } catch (IllegalStateException expected) { }
            assertFalse(session.foreground());
            assertArrayEquals(new byte[oldKey.length], oldKey);
            assertFalse(session.unlocked());
            assertEquals(epoch + 1, session.epoch());
            assertSame(owned, field(session, "revocation"));
            if (mode == 2) assertEquals(2, invalidation.ends); // Initial unlock and outer background.
            session.foreground(true);
            lockedWithOwner(session, oldKey, epoch, owned);
        }
    }

    @Test public void trustEpochInvalidationFailureAlsoLocksAndRetainsOwner() throws Exception {
        for (boolean failEnd : new boolean[]{false, true}) {
            Invalidation invalidation = new Invalidation();
            VaultSession session = session(invalidation);
            Lease owned = new Lease(); long epoch = session.epoch();
            session.attachRevocation(epoch, owned);
            byte[] oldKey = (byte[]) field(session, "key");
            if (failEnd) invalidation.failEndAt = invalidation.ends + 1;
            else invalidation.failBeginAt = invalidation.begins + 1;
            try { session.advanceTrustEpoch(epoch, owned); fail(); }
            catch (IllegalStateException expected) { }
            lockedWithOwner(session, oldKey, epoch, owned);
            assertEquals(0, owned.revoked);
        }
    }
}
