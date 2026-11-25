package im.majo.thanox_bridge

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel

internal object ThanoxService {
    const val ROOT = "github.tornaco.android.thanos.core.IThanos"

    internal data class Connection(val binder: IBinder, val profile: ThanoxProfile)

    @Suppress("DEPRECATION")
    fun connect(context: Context): Connection {
        val api = Build.VERSION.SDK_INT
        val product = Build.PRODUCT
        val packageName = ThanoxCompatibility.packageForDevice(api, product)
            ?: throw IllegalStateException("当前设备与 Android 版本尚未验证")
        val packageVersion = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            ThanoxPackageVersion(info.packageName, info.versionName, info.longVersionCode)
        } catch (error: Exception) {
            throw IllegalStateException("无法核验 Thanox 安装版本", error)
        }
        val service = try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            getService.invoke(null, "tv_input") as? IBinder
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("无法访问 Thanox 服务", error)
        } ?: throw IllegalStateException("Thanox 服务未运行")

        if (service.interfaceDescriptor != ROOT) {
            throw IllegalStateException("Thanox 服务接口不兼容")
        }
        val version = transact(service, ROOT, 19, {}) { it.readString() }
        val identity = transact(service, ROOT, 20, {}) { it.readString() }
        val profile = ThanoxCompatibility.select(api, product, version, packageVersion)
        if (profile == null || identity != "I am Thanox!!!") {
            throw IllegalStateException("Thanox 版本或身份不兼容：${version ?: "未知"}")
        }
        return Connection(service, profile)
    }

    fun <T> transact(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            if (!binder.transact(code, data, reply, 0)) {
                throw IllegalStateException("Thanox 不支持请求的操作")
            }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
