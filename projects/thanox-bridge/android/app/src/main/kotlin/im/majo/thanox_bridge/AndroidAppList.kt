package im.majo.thanox_bridge

import java.util.Locale

internal data class AndroidAppCandidate(
    val packageName: String,
    val uid: Int,
    val label: () -> String,
)

/** Only the calling user's Android-visible apps are used to construct this list. */
internal object AndroidAppList {
    private const val NOTICE =
        "Android 列表可能不完整，可检查设备的应用列表访问设置。"

    fun load(currentUserId: Int, queryAndroid: () -> List<AndroidAppCandidate>): Map<String, Any> {
        val apps = try {
            queryAndroid().asSequence()
                .filter { it.packageName.isNotBlank() && it.uid >= 0 && it.uid / USER_ID_RANGE == currentUserId }
                .map { it to it.label().ifBlank { it.packageName } }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }
                .map { (app, label) ->
                    mapOf(
                        "label" to label,
                        "packageName" to app.packageName,
                        "uid" to app.uid,
                        "userId" to currentUserId,
                        "sources" to listOf("Android"),
                    )
                }
                .toList()
        } catch (error: Exception) {
            throw IllegalStateException("无法读取 Android 应用列表。请检查设备的应用列表访问设置。", error)
        }
        return mapOf("apps" to apps, "notice" to NOTICE)
    }
}
