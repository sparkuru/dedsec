package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class SshJumpDeadlineTest {
    @Test public void nextHopStartsItsOwnBudgetAfterAnEarlierConfirmation() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        long requestDeadline = 10_000;
        assertEquals(3_000, SshPasswordConnector.jumpTransportDeadline(clock::get, requestDeadline, 2_000, () -> { }));
        clock.set(5_500); // Time spent confirming the preceding host is not this hop's connect time.
        assertEquals(7_500, SshPasswordConnector.jumpTransportDeadline(clock::get, requestDeadline, 2_000, () -> { }));
        clock.set(9_500);
        assertEquals(requestDeadline,
                SshPasswordConnector.jumpTransportDeadline(clock::get, requestDeadline, 2_000, () -> { }));
    }

    @Test public void overflowNeverExtendsTheOriginalDeadline() throws Exception {
        assertEquals(Long.MAX_VALUE, SshPasswordConnector.jumpTransportDeadline(
                () -> Long.MAX_VALUE - 5, Long.MAX_VALUE, 10, () -> { }));
    }

    @Test public void cancellationAndExpiredRequestsCannotStartAHop() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        try {
            SshPasswordConnector.jumpTransportDeadline(() -> { clockReads.incrementAndGet(); return 1_000; },
                    10_000, 2_000, () -> { throw new VaultException("SSH_CANCELLED", "cancelled"); });
            fail("an already cancelled request started a hop");
        } catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        assertEquals(0, clockReads.get());
        AtomicInteger checks = new AtomicInteger();
        try {
            SshPasswordConnector.jumpTransportDeadline(() -> 1_000, 10_000, 2_000, () -> {
                if (checks.incrementAndGet() == 2) throw new VaultException("SSH_CANCELLED", "cancelled");
            });
            fail("cancellation during deadline calculation was ignored");
        } catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        try {
            SshPasswordConnector.jumpTransportDeadline(() -> 10_000, 10_000, 2_000, () -> { });
            fail("expired original request was renewed");
        } catch (VaultException expected) { assertEquals("SSH_TIMED_OUT", expected.code); }
    }

    @Test public void preparationOverrunDoesNotInvokeTheJumpOpenAction() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger opens = new AtomicInteger();
        long hopDeadline = SshPasswordConnector.jumpTransportDeadline(clock::get, 20_000, 2_000, () -> { });
        clock.set(3_000); // Role and trust preparation finished after this hop's budget.
        try {
            SshPasswordConnector.beginJumpBeforeDeadline(clock::get, hopDeadline, () -> { }, opens::incrementAndGet);
            fail("expired preparation opened a jump channel");
        } catch (VaultException expected) { assertEquals("SSH_TIMED_OUT", expected.code); }
        assertEquals(0, opens.get());
        AtomicLong onTimeClock = new AtomicLong(2_999);
        SshPasswordConnector.beginJumpBeforeDeadline(onTimeClock::get, hopDeadline, () -> { }, opens::incrementAndGet);
        assertEquals(1, opens.get());
    }

    @Test public void cancellationAfterClockReadPrecedesTimeoutAndDoesNotOpen() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger opens = new AtomicInteger();
        AppSecrets.EffectGuard guard = () -> {
            if (cancelled.get()) throw new VaultException("SSH_CANCELLED", "cancelled");
        };
        try {
            SshPasswordConnector.jumpTransportDeadline(() -> { cancelled.set(true); return 10_000; },
                    10_000, 2_000, guard);
            fail("cancellation during clock read was ignored");
        } catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        cancelled.set(false);
        try {
            SshPasswordConnector.beginJumpBeforeDeadline(() -> { cancelled.set(true); return 10_000; },
                    10_000, guard, opens::incrementAndGet);
            fail("cancelled preparation opened a jump channel");
        } catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        assertEquals(0, opens.get());
        cancelled.set(false);
        AtomicInteger reads = new AtomicInteger();
        try {
            SshPasswordConnector.beginJumpBeforeDeadline(() -> {
                if (reads.incrementAndGet() == 2) { cancelled.set(true); return 10_000; }
                return 9_999;
            }, 10_000, guard, opens::incrementAndGet);
            fail("cancellation during the final clock read was ignored");
        } catch (VaultException expected) { assertEquals("SSH_CANCELLED", expected.code); }
        assertEquals(0, opens.get());
    }

    @Test public void trustDiagnosticUsesHopDeadlineOnlyDuringTransport() throws Exception {
        AtomicLong clock = new AtomicLong(2_999);
        VaultException original = new VaultException("SSH_HOST_CHANGED_POLICY", "changed");
        try {
            SshPasswordConnector.jumpTrustDiagnostic(original, () -> false, () -> false,
                    () -> SshPasswordConnector.checkJumpDiagnosticLive(true, clock::get, 3_000, () -> { }),
                    () -> { clock.set(3_000); return false; });
            fail("a host refusal hid the current hop's expired connect timeout");
        } catch (VaultException expected) { assertEquals("SSH_TIMED_OUT", expected.code); }
        assertEquals("SSH_JUMP_HOST_TRUST_REQUIRED", SshPasswordConnector.jumpTrustDiagnostic(
                original, () -> false, () -> false,
                () -> SshPasswordConnector.checkJumpDiagnosticLive(false, clock::get, 3_000, () -> { }),
                () -> true).code);
        try {
            SshPasswordConnector.checkJumpDiagnosticLive(false, clock::get, 3_000,
                    () -> { throw new VaultException("SSH_TIMED_OUT", "original request expired"); });
            fail("authentication extended the original deadline");
        } catch (VaultException expected) { assertEquals("SSH_TIMED_OUT", expected.code); }
    }
}
