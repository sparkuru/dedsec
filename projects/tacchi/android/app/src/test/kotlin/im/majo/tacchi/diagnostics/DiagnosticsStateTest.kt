package im.majo.tacchi.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsStateTest {
    private val fields: Map<String, Any?> = mapOf(
        "frameworkState" to "unavailable", "hostState" to "unknown", "probeState" to "unknown")
    private val success = Diagnostics(FrameworkState.CONNECTED, HostState.CONFIRMED, ProbeState.PASSED, 101)

    @Test fun connectedFrameworkDoesNotInventHostEvidence() {
        val decoded = Diagnostics.decode(fields + mapOf("frameworkState" to "connected", "frameworkApi" to 101))
        assertEquals(FrameworkState.CONNECTED, decoded.framework)
        assertEquals(HostState.UNKNOWN, decoded.host)
        assertEquals(ProbeState.UNKNOWN, decoded.probe)
    }

    @Test fun malformedOrContradictoryNativeFieldsAreRejected() {
        val invalid = listOf(
            mapOf("frameworkState" to "connected"),
            mapOf("frameworkState" to "connected", "frameworkApi" to 100),
            mapOf("frameworkState" to "unsupported", "frameworkApi" to 101),
            mapOf("frameworkState" to "unexpected"),
            mapOf("frameworkApi" to "101"),
            mapOf("frameworkApi" to -1),
            mapOf("hostState" to true),
            mapOf("probeState" to "payment_completed"),
            mapOf("errorCode" to "private exception text"),
        )
        invalid.forEach { input ->
            try {
                Diagnostics.decode(fields + input)
                fail("Invalid fields accepted: ${input.keys}")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun supportedStatesAndBoundedErrorCodesSurviveDecode() {
        val decoded = Diagnostics.decode(fields + mapOf("frameworkState" to "disconnected", "hostState" to "stale",
            "probeState" to "failed", "errorCode" to "hardware_unavailable"))
        assertEquals(FrameworkState.DISCONNECTED, decoded.framework)
        assertEquals(HostState.STALE, decoded.host)
        assertEquals(ProbeState.FAILED, decoded.probe)
        assertEquals("hardware_unavailable", decoded.errorCode)
    }

    @Test fun initialAndPausedStatesContainNoSuccess() {
        val session = DiagnosticsSession()
        assertEquals(DiagnosticsScreenState(), session.state)
        assertNull(session.refresh())
        val token = session.resume()!!
        assertTrue(session.complete(token, success, 1234))
        session.pause()
        assertEquals(DiagnosticsScreenState(), session.state)
        assertNull(session.refresh())
    }

    @Test fun refreshClearsOldEvidenceAndTimestampBeforeRead() {
        val session = DiagnosticsSession()
        session.complete(session.resume()!!, success, 1234)
        session.refresh()
        assertEquals(Diagnostics(), session.state.diagnostics)
        assertNull(session.state.readAtMillis)
        assertTrue(session.state.loading)
        assertFalse(session.state.readFailed)
    }

    @Test fun failedOrTimedOutReadCannotRetainSuccessOrAcceptLateSuccess() {
        val session = DiagnosticsSession()
        session.complete(session.resume()!!, success, 1234)
        val token = session.refresh()!!
        assertTrue(session.fail(token))
        assertEquals(DiagnosticsScreenState(readFailed = true), session.state)
        assertFalse(session.complete(token, success, 2345))
    }

    @Test fun laterReadWinsOverEarlierCompletionAndFailure() {
        val session = DiagnosticsSession()
        val first = session.resume()!!
        val second = session.refresh()!!
        assertFalse(session.complete(first, success, 1234))
        assertFalse(session.fail(first))
        val unavailable = Diagnostics(FrameworkState.UNAVAILABLE)
        assertTrue(session.complete(second, unavailable, 2345))
        assertEquals(unavailable, session.state.diagnostics)
        assertEquals(2345L, session.state.readAtMillis)
    }

    @Test fun pauseAndResumeInvalidateBackgroundCompletion() {
        val session = DiagnosticsSession()
        val beforePause = session.resume()!!
        session.pause()
        assertFalse(session.complete(beforePause, success, 1234))
        val resumed = session.resume()!!
        assertFalse(session.complete(beforePause, success, 1234))
        assertTrue(session.complete(resumed, success, 2345))
    }

    @Test fun destroyRejectsEveryLaterTransition() {
        val session = DiagnosticsSession()
        val token = session.resume()!!
        session.destroy()
        assertFalse(session.fail(token))
        assertFalse(session.complete(token, success, 1234))
        assertNull(session.resume())
        assertEquals(DiagnosticsScreenState(), session.state)
    }

    @Test fun duplicateCompletionCannotReplaceTimestamp() {
        val session = DiagnosticsSession()
        val token = session.resume()!!
        assertTrue(session.complete(token, success, 1234))
        assertFalse(session.complete(token, Diagnostics(), 2345))
        assertFalse(session.fail(token))
        assertEquals(1234L, session.state.readAtMillis)
    }
}
