package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationInfoWireTest {
    private class Fields(private val fields: List<Any?>, private val extraBytes: Int = 0) {
        private var index = 0

        fun dataAvail(): Int = (fields.size - index) * Int.SIZE_BYTES + extraBytes
        fun readInt(): Int = fields[index++] as Int
        fun readString(): String? = fields[index++] as String?
        fun read(): OperationInfo? = OperationInfoWire.read(::dataAvail, ::readInt, ::readString)
    }

    private val valid: List<Any?> = listOf(1, "DENY", 1, 1, 0, "summary")

    @Test
    fun readsBothPresentAndAbsentOperations() {
        assertEquals(OperationInfo("DENY", true, true, false), Fields(valid).read())
        assertNull(Fields(listOf(0)).read())
    }

    @Test
    fun rejectsInvalidOrTrailingPresenceMarkers() {
        listOf(listOf(-1), listOf(2), listOf(0, 0)).forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) { Fields(fields).read() }
        }
        assertThrows(IllegalArgumentException::class.java) { Fields(listOf(0), extraBytes = 1).read() }
    }

    @Test
    fun rejectsTruncatedFieldsAndMissingStrings() {
        for (size in valid.indices) {
            assertThrows(IllegalArgumentException::class.java) { Fields(valid.take(size)).read() }
        }
        assertThrows(IllegalArgumentException::class.java) { Fields(emptyList(), extraBytes = 3).read() }
        assertThrows(IllegalStateException::class.java) { Fields(listOf(1, null, 1, 1, 0, "summary")).read() }
        assertThrows(IllegalStateException::class.java) { Fields(listOf(1, "DENY", 1, 1, 0, null)).read() }
    }

    @Test
    fun rejectsNonBooleanFlagsAndTrailingBytes() {
        for (index in 2..4) {
            for (invalid in listOf(-1, 2, 256)) {
                val fields = valid.toMutableList()
                fields[index] = invalid
                assertThrows(IllegalStateException::class.java) { Fields(fields).read() }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { Fields(valid, extraBytes = 1).read() }
    }
}
