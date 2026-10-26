package im.majo.tacchi.ipc

import org.junit.Assert.*
import org.junit.Test

class ProbeLedgerTest {
    private var clock = 10_000L
    private val ledger = ProbeLedger { clock }

    @Test fun noHostEvidenceIsUnknown() {
        assertEquals("unknown", ledger.snapshot()["hostState"])
        assertEquals("unknown", ledger.snapshot()["probeState"])
    }

    @Test fun singleActiveRequestAndOneResult() {
        val ticket = ledger.begin()!!
        assertNull(ledger.begin())
        assertTrue(ledger.valid(ticket))
        assertTrue(ledger.finish(ticket, "passed"))
        assertFalse(ledger.finish(ticket, "passed"))
        assertEquals("passed", ledger.snapshot()["probeState"])
    }

    @Test fun wrongIdentityFieldsAndUnknownResultDoNotConsumeAuthorization() {
        val ticket = ledger.begin()!!
        assertFalse(ledger.valid(ticket.copy(id = "other")))
        assertFalse(ledger.valid(ticket.copy(revision = ticket.revision + 1)))
        assertFalse(ledger.valid(ticket.copy(deadline = ticket.deadline + 1)))
        assertFalse(ledger.finish(ticket, "untrusted free text"))
        assertTrue(ledger.valid(ticket))
    }

    @Test fun deadlineIsExclusiveAndStalesHostEvidence() {
        val ticket = ledger.begin()!!
        clock = ticket.deadline
        assertFalse(ledger.finish(ticket, "passed"))
        assertEquals("expired", ledger.snapshot()["probeState"])
        assertEquals("stale", ledger.snapshot()["hostState"])
    }

    @Test fun cancelAndNewRevisionRejectLateSuccess() {
        val old = ledger.begin()!!
        ledger.cancel()
        assertFalse(ledger.finish(old, "passed"))
        clock += 1_000L
        val current = ledger.begin()!!
        assertNotEquals(old.id, current.id)
        assertTrue(current.revision > old.revision)
        assertFalse(ledger.finish(old, "passed"))
        assertTrue(ledger.finish(current, "canceled"))
        assertEquals("canceled", ledger.snapshot()["probeState"])
    }

    @Test fun restartedManagerRejectsOldAuthorization() {
        val ticket = ledger.begin()!!
        val restarted = ProbeLedger { clock }
        assertFalse(restarted.valid(ticket))
        assertEquals("unknown", restarted.snapshot()["hostState"])
    }

    @Test fun requestRateIsBoundedAndRecoversAfterWindow() {
        repeat(32) { assertTrue(ledger.allowCall()) }
        assertFalse(ledger.allowCall())
        clock += 1_000L
        assertTrue(ledger.allowCall())
    }
}
