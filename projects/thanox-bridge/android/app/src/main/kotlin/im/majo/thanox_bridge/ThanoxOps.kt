package im.majo.thanox_bridge

import android.os.IBinder
import android.os.Parcel
import im.majo.thanox_bridge.ThanoxService.transact

/** Wire contract checked against Thanox 9.1's IThanos, IOps, Pkg and PermInfo. */
internal class ThanoxOps private constructor(private val binder: IBinder) {
    fun opName(code: Int): String? = transact(binder, OPS, 3, { it.writeInt(code) }) {
        it.readString()
    }

    fun opPermission(code: Int): String? = transact(binder, OPS, 4, { it.writeInt(code) }) {
        it.readString()
    }

    fun info(code: Int, packageName: String, userId: Int): OperationInfo? =
        transact(binder, OPS, 2, {
            it.writeInt(code)
            it.writePkg(packageName, userId)
        }) { reply ->
            OperationInfoWire.read(reply::dataAvail, reply::readInt, reply::readString)
        }

    fun setMode(code: Int, packageName: String, userId: Int, mode: String) {
        transact(binder, OPS, 1, {
            it.writeInt(code)
            it.writePkg(packageName, userId)
            it.writeString(mode)
        }) { Unit }
    }

    companion object {
        const val OP_COUNT = 121
        private const val OPS = "github.tornaco.android.thanos.core.ops.IOps"

        fun connect(): ThanoxOps {
            val service = ThanoxService.connect()
            val ops = transact(service, ThanoxService.ROOT, 31, {}) { it.readStrongBinder() }
                ?: throw IllegalStateException("Thanox 未提供权限操作接口")
            if (ops.interfaceDescriptor != OPS) {
                throw IllegalStateException("Thanox 权限操作接口不兼容")
            }
            return ThanoxOps(ops)
        }

        private fun Parcel.writePkg(packageName: String, userId: Int) {
            writeInt(1)
            writeString(packageName)
            writeInt(userId)
        }

    }
}
