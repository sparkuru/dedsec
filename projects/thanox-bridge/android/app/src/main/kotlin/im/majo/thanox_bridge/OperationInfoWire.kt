package im.majo.thanox_bridge

internal object OperationInfoWire {
    fun read(
        dataAvail: () -> Int,
        readInt: () -> Int,
        readString: () -> String?,
    ): OperationInfo? {
        fun intField(): Int {
            require(dataAvail() >= Int.SIZE_BYTES) { "Thanox 返回的权限操作信息不完整" }
            return readInt()
        }

        fun stringField(): String {
            require(dataAvail() >= Int.SIZE_BYTES) { "Thanox 返回的权限操作信息不完整" }
            return readString() ?: throw IllegalStateException("Thanox 返回了空的权限操作信息")
        }

        fun booleanField(): Boolean = when (intField()) {
            0 -> false
            1 -> true
            else -> throw IllegalStateException("Thanox 返回了无效的权限操作标记")
        }

        val present = intField()
        require(present == 0 || present == 1) { "Thanox 返回的权限操作信息无效" }
        val result = if (present == 0) {
            null
        } else {
            val state = stringField()
            // The API 36 device's PermInfo format stores each writeByte flag in an int slot.
            val background = booleanField()
            val runtime = booleanField()
            val oneTime = booleanField()
            stringField() // Access summary is not needed for this view.
            OperationInfo(state, background, runtime, oneTime)
        }
        require(dataAvail() == 0) { "Thanox 返回了未知的权限操作信息格式" }
        return result
    }
}
