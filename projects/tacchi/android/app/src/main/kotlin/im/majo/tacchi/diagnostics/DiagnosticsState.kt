package im.majo.tacchi.diagnostics

enum class FrameworkState { UNKNOWN, UNAVAILABLE, CONNECTED, DISCONNECTED, UNSUPPORTED, AMBIGUOUS, FAILED }
enum class HostState { UNKNOWN, CONFIRMED, STALE }
enum class ProbeState { UNKNOWN, RUNNING, PASSED, FAILED, CANCELED, EXPIRED }

data class Diagnostics(
    val framework: FrameworkState = FrameworkState.UNKNOWN,
    val host: HostState = HostState.UNKNOWN,
    val probe: ProbeState = ProbeState.UNKNOWN,
    val api: Int? = null,
    val errorCode: String? = null,
) {
    companion object {
        fun decode(fields: Map<String, Any?>): Diagnostics {
            val api = fields["frameworkApi"]
            require(api == null || (api is Int && api > 0))
            val error = fields["errorCode"]
            require(error == null || error in setOf("canceled", "expired", "hardware_unavailable", "auth_failed", "crypto_failed"))
            val framework = decodeState<FrameworkState>(fields["frameworkState"])
            require(framework != FrameworkState.CONNECTED || (api is Int && api >= 101))
            require(framework != FrameworkState.UNSUPPORTED || (api is Int && api < 101))
            return Diagnostics(framework, decodeState<HostState>(fields["hostState"]),
                decodeState<ProbeState>(fields["probeState"]), api as Int?, error as String?)
        }

        private inline fun <reified T : Enum<T>> decodeState(value: Any?): T {
            require(value is String)
            return enumValues<T>().firstOrNull { it.name.lowercase(java.util.Locale.ROOT) == value }
                ?: throw IllegalArgumentException("Invalid diagnostic state")
        }
    }
}

data class DiagnosticsScreenState(
    val diagnostics: Diagnostics = Diagnostics(),
    val loading: Boolean = false,
    val readFailed: Boolean = false,
    val readAtMillis: Long? = null,
)

/** Main-thread state machine: callbacks must own the current foreground read generation. */
class DiagnosticsSession {
    var state = DiagnosticsScreenState()
        private set
    private var generation = 0L
    private var resumed = false
    private var destroyed = false

    fun resume(): Long? {
        if (destroyed) return null
        resumed = true
        return refresh()
    }

    fun refresh(): Long? {
        if (!resumed || destroyed) return null
        generation++
        state = DiagnosticsScreenState(loading = true)
        return generation
    }

    fun complete(token: Long, result: Diagnostics, readAtMillis: Long): Boolean {
        if (!owns(token)) return false
        state = DiagnosticsScreenState(diagnostics = result, readAtMillis = readAtMillis)
        return true
    }

    fun fail(token: Long): Boolean {
        if (!owns(token)) return false
        state = DiagnosticsScreenState(readFailed = true)
        return true
    }

    fun pause() {
        resumed = false
        generation++
        state = DiagnosticsScreenState()
    }

    fun destroy() {
        pause()
        destroyed = true
    }

    private fun owns(token: Long) = !destroyed && resumed && state.loading && token == generation
}
