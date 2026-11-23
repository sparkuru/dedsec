package im.majo.thanox_bridge

import android.os.Build
import android.os.IBinder
import android.os.Parcel

internal object ThanoxService {
    const val ROOT = "github.tornaco.android.thanos.core.IThanos"

    fun connect(): IBinder {
        if (Build.VERSION.SDK_INT != 36) {
            throw IllegalStateException("当前仅支持已验证的 Android 16 与 Thanox 9.1")
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
        if (version != "9.1" || identity != "I am Thanox!!!") {
            throw IllegalStateException("Thanox 版本或身份不兼容：${version ?: "未知"}")
        }
        return service
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
