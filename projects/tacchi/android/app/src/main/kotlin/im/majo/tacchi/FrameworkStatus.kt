package im.majo.tacchi

internal data class FrameworkDetails(
    val api: Int,
    val name: String,
    val version: String,
    val remoteStorage: Boolean,
)

internal interface FrameworkConnection {
    fun read(): FrameworkDetails
}

internal class FrameworkUnavailableException : RuntimeException()

internal class FrameworkStatus {
    private val connections = linkedSetOf<FrameworkConnection>()
    private var observedDeath = false

    @Synchronized
    fun bind(connection: FrameworkConnection) {
        connections.add(connection)
    }

    @Synchronized
    fun died(connection: FrameworkConnection) {
        if (connections.remove(connection)) observedDeath = true
    }

    fun snapshot(): Map<String, Any?> {
        val (current, emptyState) = synchronized(this) {
            connections.toList() to if (observedDeath) "disconnected" else "unavailable"
        }
        if (current.isEmpty()) return mapOf("frameworkState" to emptyState)
        if (current.size != 1) return mapOf("frameworkState" to "ambiguous")
        return try {
            val details = current.single().read()
            synchronized(this) {
                if (connections.toList() != current) return mapOf("frameworkState" to "unknown")
            }
            mapOf(
                "frameworkState" to if (details.api >= 101) "connected" else "unsupported",
                "frameworkApi" to details.api,
                "frameworkName" to boundedLabel(details.name),
                "frameworkVersion" to boundedLabel(details.version),
                "remoteStorage" to details.remoteStorage,
            )
        } catch (_: FrameworkUnavailableException) {
            mapOf("frameworkState" to "failed")
        }
    }

    private fun boundedLabel(value: String): String = value.filterNot { it.isISOControl() }.take(64)
}
