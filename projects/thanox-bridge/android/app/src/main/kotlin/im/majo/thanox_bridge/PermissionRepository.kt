package im.majo.thanox_bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process
import java.util.Locale

@Suppress("DEPRECATION")
internal class PermissionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packages = context.packageManager
    private val currentUserId = Process.myUid() / USER_ID_RANGE

    fun listApps(): Map<String, Any> = AndroidAppList.load(currentUserId) {
        packages.getInstalledApplications(0).map { app ->
            AndroidAppCandidate(app.packageName, app.uid) { label(app) }
        }
    }

    fun findApp(packageName: String): Map<String, Any>? {
        val app = try {
            packages.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        if (app.packageName != packageName || app.uid < 0 || app.uid / USER_ID_RANGE != currentUserId) {
            return null
        }
        return appEntry(app)
    }

    private fun appEntry(app: ApplicationInfo): Map<String, Any> = mapOf(
        "label" to label(app),
        "packageName" to app.packageName,
        "uid" to app.uid,
        "userId" to currentUserId,
        "sources" to listOf("Android"),
    )

    fun appDetails(packageName: String, userId: Int, uid: Int): Map<String, Any?> {
        val packageInfo = checkedPackage(packageName, userId, uid)
        val app = packageInfo.applicationInfo!!
        val identityToken = fingerprintFor(packageInfo, userId)
        val sharedUserId = packageInfo.sharedUserId
        val permissionNames = packageInfo.requestedPermissions ?: emptyArray()
        val permissionFlags = packageInfo.requestedPermissionsFlags
        if (permissionNames.isNotEmpty() &&
            (permissionFlags == null || permissionFlags.size < permissionNames.size)
        ) {
            throw IllegalStateException("Android 未返回完整的权限状态")
        }
        val permissions = permissionNames.mapIndexed { index, name ->
            val info = permissionInfo(name)
            mapOf(
                "name" to name,
                "label" to permissionLabel(info, name),
                "granted" to (permissionFlags!![index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0),
                "protection" to protection(info),
            )
        }

        var serviceError: String? = null
        var coverageNote: String? = if (sharedUserId != null) PermissionPolicy.SHARED_UID_READ_ONLY else null
        val operations = try {
            val ops = ThanoxOps.connect(appContext)
            requireThanoxUid(packageName, userId, app.uid, ops)
            val result = readOps(
                ops, app, userId, permissionNames.toSet(), identityToken != null,
                sharedUserId,
            )
            requireThanoxUid(packageName, userId, app.uid, ops)
            coverageNote = result.second
            result.first
        } catch (error: Exception) {
            serviceError = error.message ?: "无法读取 Thanox 权限操作"
            emptyList()
        }
        if (identityToken != null) {
            val current = checkedPackage(packageName, userId, uid)
            if (!TargetIdentity.matches(identityToken, fingerprintFor(current, userId)) ||
                current.sharedUserId != sharedUserId
            ) {
                throw IllegalStateException("应用已更新、重装或共享 UID 状态已变化，请重新加载")
            }
        }
        return mapOf(
            "label" to label(app),
            "packageName" to packageName,
            "uid" to app.uid,
            "userId" to userId,
            "identityToken" to identityToken,
            "permissions" to permissions,
            "ops" to operations,
            "serviceError" to serviceError,
            "coverageNote" to coverageNote,
        )
    }

    fun setOpMode(
        packageName: String,
        uid: Int,
        userId: Int,
        identityToken: String,
        code: Int,
        mode: String,
    ): Map<String, Any> {
        val packageInfo = checkedPackage(packageName, userId, uid)
        requireIdentity(identityToken, packageInfo, userId)
        PermissionPolicy.validateUnsharedUserId(packageInfo.sharedUserId)
        val app = packageInfo.applicationInfo!!
        require(code in 0 until ThanoxOps.OP_COUNT) { "不支持的权限操作" }
        val declared = packageInfo.requestedPermissions?.toSet() ?: emptySet()
        val ops = ThanoxOps.connect(appContext)
        requireThanoxUid(packageName, userId, app.uid, ops)
        val name = ops.opName(code)
        val permission = ops.opPermission(code)
        val before = ops.info(code, packageName, userId)
            ?: throw IllegalStateException("Thanox 未返回此操作的状态")
        val verifiedStates = verifiedStates(ops.profile, app, code, name, permission, before)
        PermissionPolicy.validateOperation(permission, declared, before, mode, verifiedStates)
        val after = PermissionPolicy.guardedWrite(
            verify = {
                val current = checkedPackage(packageName, userId, uid)
                requireIdentity(identityToken, current, userId)
                PermissionPolicy.validateUnsharedUserId(current.sharedUserId)
                val currentApp = current.applicationInfo!!
                val currentDeclared = current.requestedPermissions?.toSet() ?: emptySet()
                val currentName = ops.opName(code)
                val currentPermission = ops.opPermission(code)
                PermissionPolicy.validateOperation(
                    currentPermission, currentDeclared, before, mode,
                    verifiedStates(ops.profile, currentApp, code, currentName, currentPermission, before),
                )
                requireThanoxUid(packageName, userId, currentApp.uid, ops)
            },
            write = { ops.setMode(code, packageName, userId, mode) },
            read = {
                ops.info(code, packageName, userId)
                    ?: throw IllegalStateException("操作后无法读取实际状态")
            },
        )
        return mapOf(
            "requestedState" to mode,
            "observedState" to after.state,
            "matched" to (after.state == mode),
        )
    }

    private fun readOps(
        ops: ThanoxOps,
        app: ApplicationInfo,
        userId: Int,
        declared: Set<String>,
        identityAvailable: Boolean,
        sharedUserId: String?,
    ): Pair<List<Map<String, Any?>>, String> {
        val result = mutableListOf<Map<String, Any?>>()
        var failures = 0
        var successfulReads = 0
        for (code in 0 until ThanoxOps.OP_COUNT) {
            try {
                val permission = ops.opPermission(code)
                if (permission != null && permission !in declared) continue
                val info = ops.info(code, app.packageName, userId)
                successfulReads++
                if (info != null) {
                    val name = try {
                        ops.opName(code)
                    } catch (_: Exception) {
                        failures++
                        null
                    }
                    result += mapOf(
                        "code" to code,
                        "name" to (name?.takeIf { it.isNotBlank() } ?: "操作 $code"),
                        "permission" to permission,
                        "state" to info.state,
                        "runtime" to info.isRuntimePermission,
                        "allowedStates" to PermissionPolicy.allowedStatesForTarget(
                            info,
                            verifiedStates(ops.profile, app, code, name, permission, info),
                            identityAvailable,
                            sharedUserId,
                        ),
                    )
                }
            } catch (_: Exception) {
                failures++
            }
        }
        if (successfulReads == 0 && failures > 0) {
            throw IllegalStateException("Thanox 无法读取应用权限操作")
        }
        val note = buildString {
            append("Thanox ${ops.profile.serviceVersion} 的查询范围为操作 0–120；其他操作可能未列出。")
            if (failures > 0) append(" 有 $failures 项操作无法读取。")
            append(" 仅已验证的相机、麦克风和振动操作可修改，限目标 SDK ${ops.profile.targetSdkDescription} 的非系统应用。")
            if (!identityAvailable) append(" 无法核验应用安装及签名信息，当前只可查看。")
            if (sharedUserId != null) append(" ${PermissionPolicy.SHARED_UID_READ_ONLY}")
        }
        val sorted = result.sortedWith(
            compareBy<Map<String, Any?>> { (it["allowedStates"] as List<*>).isEmpty() }
                .thenBy { (it["name"] as String).lowercase(Locale.getDefault()) },
        )
        return sorted to note
    }

    private fun checkedPackage(packageName: String, userId: Int, uid: Int): PackageInfo {
        require(userId == currentUserId) { "暂不支持其他 Android 用户" }
        require(packageName.isNotBlank()) { "缺少应用包名" }
        val info = packages.getPackageInfo(
            packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val app = info.applicationInfo ?: throw IllegalStateException("Android 未返回应用信息")
        require(info.packageName == packageName && app.packageName == packageName) { "应用包名不匹配" }
        require(app.uid >= 0 && app.uid / USER_ID_RANGE == userId) { "应用不属于当前用户" }
        PermissionPolicy.validateTarget(currentUserId, userId, uid, app.uid)
        return info
    }

    private fun fingerprintFor(info: PackageInfo, userId: Int): String? {
        val app = info.applicationInfo ?: return null
        val certificates = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: return null
        return TargetIdentity.fingerprint(
            info.packageName, userId, app.uid, info.firstInstallTime, info.lastUpdateTime,
            info.longVersionCode, app.sourceDir, certificates,
        )
    }

    private fun requireIdentity(expected: String, info: PackageInfo, userId: Int) {
        require(TargetIdentity.matches(expected, fingerprintFor(info, userId))) {
            "应用已更新、重装或身份无法核验，本次未修改"
        }
    }

    private fun requireThanoxUid(packageName: String, userId: Int, androidUid: Int, ops: ThanoxOps) {
        require(userId == 0) { "Thanox 应用身份核验暂仅支持 Android 用户 0" }
        val thanoxUid = try {
            ops.exactUid(packageName, userId)
        } catch (error: Exception) {
            throw IllegalStateException("无法从 Thanox 核验应用 UID", error)
        }
        PermissionPolicy.validateThanoxUid(userId, androidUid, thanoxUid)
    }

    private fun label(app: ApplicationInfo): String =
        try {
            packages.getApplicationLabel(app).toString().ifBlank { app.packageName }
        } catch (_: RuntimeException) {
            app.packageName
        }

    private fun permissionInfo(name: String): PermissionInfo? =
        try {
            packages.getPermissionInfo(name, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun permissionLabel(info: PermissionInfo?, name: String): String =
        try {
            info?.loadLabel(packages)?.toString()?.ifBlank { name } ?: name
        } catch (_: RuntimeException) {
            name
        }

    private fun protection(info: PermissionInfo?): String = when (
        info?.protectionLevel?.and(PermissionInfo.PROTECTION_MASK_BASE)
    ) {
        PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
        PermissionInfo.PROTECTION_NORMAL -> "normal"
        PermissionInfo.PROTECTION_SIGNATURE -> "signature"
        null -> "unknown"
        else -> "other"
    }

    private fun verifiedStates(
        profile: ThanoxProfile,
        app: ApplicationInfo,
        code: Int,
        name: String?,
        permission: String?,
        info: OperationInfo,
    ): Set<String> = PermissionPolicy.verifiedStates(
        profile,
        code,
        name,
        permission,
        info,
        app.targetSdkVersion,
        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
    )

}
