package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppListTest {
    private fun app(packageName: String, uid: Int, label: String) =
        AndroidAppCandidate(packageName, uid) { label }

    @Test
    fun listsOnlyValidCurrentUserAndroidAppsAndResolvesOnlyTheirLabels() {
        var queries = 0
        val result = AndroidAppList.load(0) {
            queries++
            listOf(
                app("dev.test.zulu", 10502, "Zulu"),
                app("dev.test.alpha", 10501, "alpha"),
                app("dev.test.blanklabel", 10503, ""),
                AndroidAppCandidate("dev.test.otheruser", 1_010_504) { error("other user label read") },
                AndroidAppCandidate("dev.test.invaliduid", -1) { error("invalid UID label read") },
                AndroidAppCandidate("", 10505) { error("invalid package label read") },
            )
        }
        assertEquals(1, queries)
        assertEquals(
            listOf(
                mapOf(
                    "label" to "alpha", "packageName" to "dev.test.alpha", "uid" to 10501,
                    "userId" to 0, "sources" to listOf("Android"),
                ),
                mapOf(
                    "label" to "dev.test.blanklabel", "packageName" to "dev.test.blanklabel", "uid" to 10503,
                    "userId" to 0, "sources" to listOf("Android"),
                ),
                mapOf(
                    "label" to "Zulu", "packageName" to "dev.test.zulu", "uid" to 10502,
                    "userId" to 0, "sources" to listOf("Android"),
                ),
            ),
            result["apps"],
        )
    }

    @Test
    fun currentUserMayBeAProfileOtherThanUserZero() {
        val result = AndroidAppList.load(10) {
            listOf(
                app("dev.test.owner", 10501, "Owner"),
                app("dev.test.profile", 1_010_502, "Profile"),
            )
        }
        assertEquals(
            listOf(
                mapOf(
                    "label" to "Profile", "packageName" to "dev.test.profile", "uid" to 1_010_502,
                    "userId" to 10, "sources" to listOf("Android"),
                ),
            ),
            result["apps"],
        )
    }

    @Test
    fun coverageNoticeDoesNotInferAccessFromTheNumberOfEntries() {
        val candidate = app("dev.test.one", 10501, "One")
        val results = listOf(emptyList(), listOf(candidate), listOf(candidate, app("dev.test.two", 10502, "Two")))
            .map { entries -> AndroidAppList.load(0) { entries } }
        val notices = results.map { it["notice"] as String }.toSet()
        assertEquals(1, notices.size)
        val notice = notices.single()
        assertTrue(notice.contains("访问设置"))
        assertTrue(notice.contains("可能不完整"))
        assertFalse(notice.contains("已拒绝"))
        assertFalse(notice.contains("已允许"))
        assertEquals(listOf(0, 1, 2), results.map { (it["apps"] as List<*>).size })
    }

    @Test
    fun androidEnumerationFailureIsReportedWithoutAFallbackList() {
        val denied = SecurityException("device rejected Android query")
        var queries = 0
        val error = assertThrows(IllegalStateException::class.java) {
            AndroidAppList.load(0) {
                queries++
                throw denied
            }
        }
        assertEquals(1, queries)
        assertSame(denied, error.cause)
        assertTrue(error.message!!.contains("无法读取 Android 应用列表"))
        assertTrue(error.message!!.contains("访问设置"))
    }
}
