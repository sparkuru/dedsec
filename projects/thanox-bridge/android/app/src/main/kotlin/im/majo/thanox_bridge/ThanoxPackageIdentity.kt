package im.majo.thanox_bridge

import android.os.IBinder
import im.majo.thanox_bridge.ThanoxService.transact

/** Read-only exact UID wire contract checked against the supported IPkgManager profiles. */
internal object ThanoxPackageIdentity {
    private const val PKG_MANAGER = "github.tornaco.android.thanos.core.pm.IPkgManager"

    private fun manager(service: ThanoxService.Connection): IBinder {
        val binder = transact(service.binder, ThanoxService.ROOT, 4, {}) { it.readStrongBinder() }
            ?: throw IllegalStateException("Thanox 未提供应用管理接口")
        if (binder.interfaceDescriptor != PKG_MANAGER) {
            throw IllegalStateException("Thanox 应用管理接口不兼容")
        }
        return binder
    }

    fun exactUid(service: ThanoxService.Connection, packageName: String, userId: Int): Int {
        require(userId == 0) { "Thanox 应用身份核验暂仅支持 Android 用户 0" }
        require(packageName.isNotBlank()) { "缺少应用包名" }
        return transact(manager(service), PKG_MANAGER, 2, {
            it.writeInt(1)
            it.writeString(packageName)
            it.writeInt(userId)
        }) { reply ->
            require(reply.dataAvail() == Int.SIZE_BYTES) { "Thanox 返回的应用 UID 格式无效" }
            val uid = reply.readInt()
            require(reply.dataAvail() == 0) { "Thanox 返回的应用 UID 格式无效" }
            uid
        }
    }
}
