package im.majo.tacchi.ipc

import java.util.UUID

data class ProbeTicket(val id: String, val revision: Long, val deadline: Long)

/** In-memory authorization expires on manager process death. All transitions are serialized. */
class ProbeLedger(private val now: () -> Long) {
    private var revision = 0L
    private var ticket: ProbeTicket? = null
    private var state = "unknown"
    private var error: String? = null
    private var hostSeen: Long? = null
    private var lastBegin: Long? = null
    private var callWindow = now()
    private var calls = 0

    @Synchronized fun allowCall(): Boolean {
        if (now() - callWindow >= 1_000L) {
            callWindow = now()
            calls = 0
        }
        if (calls >= 32) return false
        calls++
        return true
    }

    @Synchronized fun loaded() { hostSeen = now() }

    @Synchronized fun begin(): ProbeTicket? {
        expire()
        val time = now()
        if (ticket != null || lastBegin?.let { time - it < 1_000L } == true) return null
        lastBegin = time
        revision++
        return ProbeTicket(UUID.randomUUID().toString(), revision, time + 90_000L).also {
            ticket = it
            state = "running"
            error = null
            hostSeen = time
        }
    }

    @Synchronized fun valid(candidate: ProbeTicket): Boolean {
        expire()
        return ticket == candidate && state == "running"
    }

    @Synchronized fun finish(candidate: ProbeTicket, result: String): Boolean {
        if (result !in RESULTS || !valid(candidate)) return false
        state = when (result) {
            "passed" -> "passed"
            "canceled" -> "canceled"
            else -> "failed"
        }
        error = result.takeUnless { it == "passed" }
        ticket = null
        hostSeen = now()
        return true
    }

    @Synchronized fun cancel() {
        revision++
        ticket = null
        state = "canceled"
        error = "canceled"
    }

    @Synchronized fun snapshot(): Map<String, Any?> {
        expire()
        val seen = hostSeen
        return mapOf(
            "hostState" to when {
                seen == null -> "unknown"
                now() - seen >= 90_000L -> "stale"
                else -> "confirmed"
            },
            "probeState" to state,
            "hostUpdatedElapsedMs" to seen,
            "errorCode" to error,
        )
    }

    private fun expire() {
        if (ticket?.let { now() >= it.deadline } == true) {
            ticket = null
            state = "expired"
            error = "expired"
        }
    }

    companion object {
        val RESULTS = setOf("passed", "canceled", "hardware_unavailable", "auth_failed", "crypto_failed")
    }
}
