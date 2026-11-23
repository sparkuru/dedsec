package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val allStates = setOf("ALLOW_ALWAYS", "ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY")

    @Test
    fun runtimeOptionsReflectCapabilities() {
        val basic = OperationInfo("DENY", false, true, false)
        assertEquals(listOf("ALLOW_ALWAYS", "IGNORE", "DENY"), PermissionPolicy.allowedStates(basic, allStates))

        val extended = OperationInfo("DENY", true, true, true)
        assertEquals(
            listOf("ALLOW_ALWAYS", "ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"),
            PermissionPolicy.allowedStates(extended, allStates),
        )
    }

    @Test
    fun nonRuntimeAndUnknownOperationsDoNotExposeUnsupportedModes() {
        val appOp = OperationInfo("DEFAULT", true, false, true)
        assertEquals(listOf("ALLOW_ALWAYS", "IGNORE"), PermissionPolicy.allowedStates(appOp, allStates))
        assertTrue(PermissionPolicy.allowedStates(appOp.copy(state = "UNKNOWN"), allStates).isEmpty())
        assertTrue(PermissionPolicy.allowedStates(appOp.copy(state = "FUTURE_STATE"), allStates).isEmpty())
        assertTrue(PermissionPolicy.allowedStates(appOp, emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateOperation(null, emptySet(), appOp, "DENY", allStates)
        }
    }

    @Test
    fun verifiedModesRequireMatchingOperationPermissionAndTarget() {
        val camera = OperationInfo("NOT_SET", true, true, true)
        val cameraStates = PermissionPolicy.verifiedStates(
            26, "android.permission.CAMERA", camera, 35, false,
        )
        assertEquals(setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"), cameraStates)
        assertTrue("ALLOW_ALWAYS" !in cameraStates)
        assertTrue(PermissionPolicy.verifiedStates(26, "android.permission.CAMERA", camera, 34, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(26, "android.permission.CAMERA", camera, 35, true).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(26, "android.permission.VIBRATE", camera, 35, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(26, "android.permission.CAMERA", camera.copy(supportsOneTime = false), 35, false).isEmpty())

        val vibration = OperationInfo("ALLOW_ALWAYS", false, false, false)
        assertEquals(
            setOf("ALLOW_ALWAYS", "IGNORE"),
            PermissionPolicy.verifiedStates(3, "android.permission.VIBRATE", vibration, 35, false),
        )
        assertTrue(PermissionPolicy.verifiedStates(3, "android.permission.VIBRATE", vibration.copy(isRuntimePermission = true), 35, false).isEmpty())
    }

    @Test
    fun microphoneRequiresVerifiedIdentityAndCapabilities() {
        val microphone = OperationInfo("DENY", true, true, true)
        val permission = "android.permission.RECORD_AUDIO"
        val states = PermissionPolicy.verifiedStates(27, permission, microphone, 35, false)
        assertEquals(setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"), states)
        assertEquals(
            listOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"),
            PermissionPolicy.allowedStates(microphone, states),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateOperation(permission, setOf(permission), microphone, "ALLOW_ALWAYS", states)
        }
        assertTrue(PermissionPolicy.verifiedStates(26, permission, microphone, 35, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, "android.permission.CAMERA", microphone, 35, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone, 34, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone, 36, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone, 35, true).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone.copy(isRuntimePermission = false), 35, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone.copy(hasBackgroundPermission = false), 35, false).isEmpty())
        assertTrue(PermissionPolicy.verifiedStates(27, permission, microphone.copy(supportsOneTime = false), 35, false).isEmpty())
    }

    @Test
    fun targetAndDeclaredPermissionAreValidated() {
        val info = OperationInfo("DENY", false, true, false)
        PermissionPolicy.validateTarget(0, 0, 10501, 10501)
        PermissionPolicy.validateOperation("android.permission.CAMERA", setOf("android.permission.CAMERA"), info, "ALLOW_ALWAYS", allStates)
        PermissionPolicy.validateOperation(null, emptySet(), info, "DENY", allStates)

        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateTarget(0, 10, 10501, 10501)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateTarget(0, 0, 10501, 10502)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateOperation("android.permission.CAMERA", emptySet(), info, "ALLOW_ALWAYS", allStates)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateOperation("android.permission.CAMERA", setOf("android.permission.CAMERA"), info, "ALLOW_ALWAYS", emptySet())
        }
    }

    @Test
    fun thanoxUidMustMatchAValidVerifiedUserAndAndroidUid() {
        PermissionPolicy.validateThanoxUid(0, 10501, 10501)
        listOf(
            Triple(0, 10501, 10502),
            Triple(0, 10501, -1),
            Triple(0, 10501, 1_010_501),
            Triple(0, -1, -1),
            Triple(10, 1_010_501, 1_010_501),
        ).forEach { (userId, androidUid, thanoxUid) ->
            assertThrows(IllegalArgumentException::class.java) {
                PermissionPolicy.validateThanoxUid(userId, androidUid, thanoxUid)
            }
        }
    }

    @Test
    fun guardedWriteNeverRunsOnFailedInitialIdentityCheck() {
        var wrote = false
        var read = false
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.guardedWrite(
                verify = { PermissionPolicy.validateThanoxUid(0, 10501, 10502) },
                write = { wrote = true },
                read = { read = true },
            )
        }
        assertEquals(false, wrote)
        assertEquals(false, read)
        assertThrows(IllegalStateException::class.java) {
            PermissionPolicy.guardedWrite(
                verify = { throw IllegalStateException("lookup unavailable") },
                write = { wrote = true },
                read = { read = true },
            )
        }
        assertEquals(false, wrote)
        assertEquals(false, read)
    }

    @Test
    fun guardedWriteRechecksBeforeAndAfterMutationAndReadback() {
        val events = mutableListOf<String>()
        val result = PermissionPolicy.guardedWrite(
            verify = { events.add("verify") },
            write = { events.add("write") },
            read = { events.add("read"); "DENY" },
        )
        assertEquals("DENY", result)
        assertEquals(listOf("verify", "write", "verify", "read", "verify"), events)
    }

    @Test
    fun failedPostWriteIdentityCheckOrReadbackReportsUncertainty() {
        var verifications = 0
        var wrote = false
        var read = false
        val postWriteFailure = assertThrows(IllegalStateException::class.java) {
            PermissionPolicy.guardedWrite(
                verify = {
                    verifications++
                    if (verifications == 2) throw IllegalStateException("lookup unavailable")
                },
                write = { wrote = true },
                read = { read = true },
            )
        }
        assertTrue(postWriteFailure.message!!.contains("无法确认"))
        assertTrue(wrote)
        assertEquals(false, read)

        val readbackFailure = assertThrows(IllegalStateException::class.java) {
            PermissionPolicy.guardedWrite(
                verify = {}, write = {}, read = { throw IllegalStateException("no readback") },
            )
        }
        assertTrue(readbackFailure.message!!.contains("无法确认"))

        verifications = 0
        val finalCheckFailure = assertThrows(IllegalStateException::class.java) {
            PermissionPolicy.guardedWrite(
                verify = {
                    verifications++
                    if (verifications == 3) throw IllegalStateException("identity changed")
                },
                write = {}, read = { "DENY" },
            )
        }
        assertTrue(finalCheckFailure.message!!.contains("无法确认"))
    }
}
