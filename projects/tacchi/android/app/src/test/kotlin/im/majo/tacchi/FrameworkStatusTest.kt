package im.majo.tacchi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FrameworkStatusTest {
    private fun connection(api: Int = 101) = object : FrameworkConnection {
        override fun read() = FrameworkDetails(api, "Vector", "2.1", true)
    }

    @Test
    fun missingFrameworkDoesNotConfirmHost() {
        val snapshot = FrameworkStatus().snapshot()
        assertEquals("unavailable", snapshot["frameworkState"])
        assertFalse(snapshot.containsKey("hostState"))
    }

    @Test
    fun bindDeathAndRebindAreDistinct() {
        val status = FrameworkStatus()
        val service = connection()
        status.bind(service)
        assertEquals("connected", status.snapshot()["frameworkState"])
        status.died(service)
        assertEquals("disconnected", status.snapshot()["frameworkState"])
        status.bind(connection())
        assertEquals("connected", status.snapshot()["frameworkState"])
    }

    @Test
    fun olderApiCannotPass() {
        val status = FrameworkStatus()
        status.bind(connection(100))
        assertEquals("unsupported", status.snapshot()["frameworkState"])
    }

    @Test
    fun multipleFrameworksAreAmbiguousUntilOnlyOneRemains() {
        val status = FrameworkStatus()
        val first = connection()
        status.bind(first)
        status.bind(connection())
        assertEquals("ambiguous", status.snapshot()["frameworkState"])
        status.died(first)
        assertEquals("connected", status.snapshot()["frameworkState"])
    }

    @Test
    fun failedRemoteReadDoesNotBecomeConnected() {
        val status = FrameworkStatus()
        status.bind(object : FrameworkConnection {
            override fun read(): FrameworkDetails = throw FrameworkUnavailableException()
        })
        assertEquals("failed", status.snapshot()["frameworkState"])
    }

    @Test
    fun deathDuringReadInvalidatesSnapshot() {
        val status = FrameworkStatus()
        val connection = object : FrameworkConnection {
            override fun read(): FrameworkDetails {
                status.died(this)
                return FrameworkDetails(101, "Vector", "2.1", true)
            }
        }
        status.bind(connection)
        assertEquals("unknown", status.snapshot()["frameworkState"])
    }
}
