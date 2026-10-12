package im.majo.ctos;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VaultSessionTest {
    private long now;
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
}
