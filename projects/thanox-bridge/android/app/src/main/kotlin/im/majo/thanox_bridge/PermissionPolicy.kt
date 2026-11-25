package im.majo.thanox_bridge

internal const val USER_ID_RANGE = 100_000

internal data class OperationInfo(
    val state: String,
    val hasBackgroundPermission: Boolean,
    val isRuntimePermission: Boolean,
    val supportsOneTime: Boolean,
)

internal object PermissionPolicy {
    const val SHARED_UID_READ_ONLY = "此应用声明了共享 UID，修改可能影响其他应用，当前只可查看。"

    private val knownStates = setOf(
        "DEFAULT", "NOT_SET", "DENY", "IGNORE", "ASK", "ALLOW_ALWAYS",
        "ALLOW_FOREGROUND_ONLY", "UNKNOWN",
    )

    fun verifiedStates(
        profile: ThanoxProfile,
        code: Int,
        name: String?,
        permission: String?,
        info: OperationInfo,
        targetSdk: Int,
        isSystemApp: Boolean,
    ): Set<String> {
        if (targetSdk !in profile.targetSdks || isSystemApp) return emptySet()
        val verifiedRuntimeOperation =
            ((code == 26 && name == "CAMERA" && permission == "android.permission.CAMERA") ||
                (code == 27 && name == "RECORD_AUDIO" && permission == "android.permission.RECORD_AUDIO")) &&
                info.isRuntimePermission && info.supportsOneTime
        return when {
            verifiedRuntimeOperation &&
                profile == ThanoxProfile.ANDROID_16_THANOX_9_1 &&
                info.hasBackgroundPermission ->
                setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY")
            verifiedRuntimeOperation &&
                profile == ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6 &&
                !info.hasBackgroundPermission ->
                setOf("ALLOW_ALWAYS", "ASK", "IGNORE", "DENY")
            code == 3 && name == "VIBRATE" && permission == "android.permission.VIBRATE" &&
                !info.isRuntimePermission && !info.hasBackgroundPermission && !info.supportsOneTime ->
                setOf("ALLOW_ALWAYS", "IGNORE")
            else -> emptySet()
        }
    }

    fun allowedStates(info: OperationInfo, verifiedStates: Set<String>): List<String> {
        if (info.state !in knownStates || info.state == "UNKNOWN") return emptyList()
        val supported = if (!info.isRuntimePermission) listOf("ALLOW_ALWAYS", "IGNORE") else buildList {
            add("ALLOW_ALWAYS")
            if (info.hasBackgroundPermission) add("ALLOW_FOREGROUND_ONLY")
            if (info.supportsOneTime) add("ASK")
            add("IGNORE")
            add("DENY")
        }
        return supported.filter { it in verifiedStates }
    }

    fun allowedStatesForTarget(
        info: OperationInfo,
        verifiedStates: Set<String>,
        identityAvailable: Boolean,
        sharedUserId: String?,
    ): List<String> = if (identityAvailable && sharedUserId == null) {
        allowedStates(info, verifiedStates)
    } else {
        emptyList()
    }

    fun validateUnsharedUserId(sharedUserId: String?) {
        require(sharedUserId == null) { SHARED_UID_READ_ONLY }
    }

    fun validateTarget(currentUser: Int, userId: Int, requestedUid: Int, actualUid: Int) {
        require(userId == currentUser) { "暂不支持其他 Android 用户" }
        require(requestedUid == actualUid) { "应用身份已改变，请重新加载" }
    }

    fun validateThanoxUid(userId: Int, androidUid: Int, thanoxUid: Int) {
        require(userId == 0) { "Thanox 应用身份核验暂仅支持 Android 用户 0" }
        require(androidUid >= 0 && androidUid / USER_ID_RANGE == userId) { "Android 应用 UID 无效" }
        require(thanoxUid >= 0 && thanoxUid / USER_ID_RANGE == userId) {
            "Thanox 未返回有效的当前用户应用 UID"
        }
        require(thanoxUid == androidUid) { "Android 与 Thanox 的应用 UID 不一致" }
    }

    fun <T> guardedWrite(verify: () -> Unit, write: () -> Unit, read: () -> T): T {
        verify()
        return try {
            write()
            verify()
            val result = read()
            verify()
            result
        } catch (error: Exception) {
            throw IllegalStateException("操作可能已发出，但结果或应用身份无法确认，请重新检查", error)
        }
    }

    fun validateOperation(
        permission: String?,
        declared: Set<String>,
        info: OperationInfo,
        mode: String,
        verifiedStates: Set<String>,
    ) {
        require(permission == null || permission in declared) { "此应用未声明该权限" }
        require(mode in allowedStates(info, verifiedStates)) { "该状态尚未验证或当前权限不支持" }
    }
}
