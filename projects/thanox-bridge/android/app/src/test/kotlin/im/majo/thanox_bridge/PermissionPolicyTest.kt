package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val legacy = ThanoxProfile.ANDROID_16_THANOX_9_1
    private val board = ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6
    private val allStates = setOf("ALLOW_ALWAYS", "ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY")

    private fun verifiedStates(
        profile: ThanoxProfile,
        code: Int,
        permission: String?,
        info: OperationInfo,
        targetSdk: Int,
        isSystemApp: Boolean,
    ): Set<String> = PermissionPolicy.verifiedStates(
        profile, code,
        when (code) {
            3 -> "VIBRATE"
            26 -> "CAMERA"
            27 -> "RECORD_AUDIO"
            else -> null
        },
        permission, info, targetSdk, isSystemApp,
    )

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
    fun sharedUidAndMissingIdentityLeaveVerifiedModesReadOnly() {
        val camera = OperationInfo("DENY", true, true, true)
        val states = setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY")
        assertEquals(
            listOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"),
            PermissionPolicy.allowedStatesForTarget(camera, states, true, null),
        )
        assertTrue(PermissionPolicy.allowedStatesForTarget(camera, states, false, null).isEmpty())
        assertTrue(PermissionPolicy.allowedStatesForTarget(camera, states, true, "shared.uid").isEmpty())
        assertTrue(PermissionPolicy.allowedStatesForTarget(camera, states, true, "").isEmpty())
        assertTrue(PermissionPolicy.allowedStatesForTarget(camera, states, false, "shared.uid").isEmpty())
    }

    @Test
    fun verifiedModesRequireMatchingOperationPermissionAndTarget() {
        val camera = OperationInfo("NOT_SET", true, true, true)
        val cameraStates = verifiedStates(
            legacy, 26, "android.permission.CAMERA", camera, 35, false,
        )
        assertEquals(setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"), cameraStates)
        assertTrue("ALLOW_ALWAYS" !in cameraStates)
        assertTrue(verifiedStates(legacy, 26, "android.permission.CAMERA", camera, 34, false).isEmpty())
        assertTrue(verifiedStates(legacy, 26, "android.permission.CAMERA", camera, 35, true).isEmpty())
        assertTrue(verifiedStates(legacy, 26, "android.permission.VIBRATE", camera, 35, false).isEmpty())
        assertTrue(verifiedStates(legacy, 26, "android.permission.CAMERA", camera.copy(supportsOneTime = false), 35, false).isEmpty())

        val vibration = OperationInfo("ALLOW_ALWAYS", false, false, false)
        assertEquals(
            setOf("ALLOW_ALWAYS", "IGNORE"),
            verifiedStates(legacy, 3, "android.permission.VIBRATE", vibration, 35, false),
        )
        assertTrue(verifiedStates(legacy, 3, "android.permission.VIBRATE", vibration.copy(isRuntimePermission = true), 35, false).isEmpty())
    }

    @Test
    fun microphoneRequiresVerifiedIdentityAndCapabilities() {
        val microphone = OperationInfo("DENY", true, true, true)
        val permission = "android.permission.RECORD_AUDIO"
        val states = verifiedStates(legacy, 27, permission, microphone, 35, false)
        assertEquals(setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"), states)
        assertEquals(
            listOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"),
            PermissionPolicy.allowedStates(microphone, states),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.validateOperation(permission, setOf(permission), microphone, "ALLOW_ALWAYS", states)
        }
        assertTrue(verifiedStates(legacy, 26, permission, microphone, 35, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, "android.permission.CAMERA", microphone, 35, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone, 34, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone, 36, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone, 35, true).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone.copy(isRuntimePermission = false), 35, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone.copy(hasBackgroundPermission = false), 35, false).isEmpty())
        assertTrue(verifiedStates(legacy, 27, permission, microphone.copy(supportsOneTime = false), 35, false).isEmpty())
    }

    @Test
    fun verifiedProfilesUseTheirOwnCameraAndMicrophoneModes() {
        val legacyInfo = OperationInfo("DENY", true, true, true)
        val boardInfo = OperationInfo("DENY", false, true, true)
        listOf(
            26 to "android.permission.CAMERA",
            27 to "android.permission.RECORD_AUDIO",
        ).forEach { (code, permission) ->
            val legacyStates = verifiedStates(legacy, code, permission, legacyInfo, 35, false)
            val boardStates = verifiedStates(board, code, permission, boardInfo, 35, false)
            assertEquals(setOf("ALLOW_FOREGROUND_ONLY", "ASK", "IGNORE", "DENY"), legacyStates)
            assertEquals(setOf("ALLOW_ALWAYS", "ASK", "IGNORE", "DENY"), boardStates)
            assertEquals(
                listOf("ALLOW_ALWAYS", "ASK", "IGNORE", "DENY"),
                PermissionPolicy.allowedStates(boardInfo, boardStates),
            )
            assertThrows(IllegalArgumentException::class.java) {
                PermissionPolicy.validateOperation(permission, setOf(permission), legacyInfo, "ALLOW_ALWAYS", legacyStates)
            }
            assertThrows(IllegalArgumentException::class.java) {
                PermissionPolicy.validateOperation(
                    permission, setOf(permission), boardInfo, "ALLOW_FOREGROUND_ONLY", boardStates,
                )
            }
            assertTrue(verifiedStates(board, code, permission, legacyInfo, 35, false).isEmpty())
            assertTrue(verifiedStates(legacy, code, permission, boardInfo, 35, false).isEmpty())
            assertTrue(verifiedStates(board, code, permission, boardInfo.copy(isRuntimePermission = false), 35, false).isEmpty())
            assertTrue(verifiedStates(board, code, permission, boardInfo.copy(supportsOneTime = false), 35, false).isEmpty())
            assertTrue(verifiedStates(board, code, permission, boardInfo, 34, false).isEmpty())
            assertTrue(verifiedStates(board, code, permission, boardInfo, 35, true).isEmpty())
        }
    }

    @Test
    fun bothProfilesRequireTheSameVerifiedVibrationCapabilities() {
        val vibrate = OperationInfo("ALLOW_ALWAYS", false, false, false)
        for (profile in listOf(legacy, board)) {
            val verified = verifiedStates(profile, 3, "android.permission.VIBRATE", vibrate, 35, false)
            assertEquals(setOf("ALLOW_ALWAYS", "IGNORE"), verified)
            assertTrue(verifiedStates(profile, 3, "android.permission.VIBRATE", vibrate.copy(hasBackgroundPermission = true), 35, false).isEmpty())
            assertTrue(verifiedStates(profile, 3, "android.permission.VIBRATE", vibrate.copy(isRuntimePermission = true), 35, false).isEmpty())
            assertTrue(verifiedStates(profile, 3, "android.permission.VIBRATE", vibrate.copy(supportsOneTime = true), 35, false).isEmpty())
        }
    }

    @Test
    fun eachProfileAllowsOnlyItsIndependentlyVerifiedTargetSdks() {
        val boardRuntime = OperationInfo("DENY", false, true, true)
        val legacyRuntime = OperationInfo("DENY", true, true, true)
        val vibration = OperationInfo("ALLOW_ALWAYS", false, false, false)
        val operations = listOf(
            Triple(26, "android.permission.CAMERA", boardRuntime),
            Triple(27, "android.permission.RECORD_AUDIO", boardRuntime),
            Triple(3, "android.permission.VIBRATE", vibration),
        )
        for ((code, permission, boardInfo) in operations) {
            val expected = if (code == 3) setOf("ALLOW_ALWAYS", "IGNORE")
                else setOf("ALLOW_ALWAYS", "ASK", "IGNORE", "DENY")
            for (sdk in listOf(30, 35)) {
                assertEquals(expected, verifiedStates(board, code, permission, boardInfo, sdk, false))
            }
            for (sdk in listOf(0, 28, 29, 31, 32, 33, 34, 36, 100)) {
                assertTrue(verifiedStates(board, code, permission, boardInfo, sdk, false).isEmpty())
            }
            val legacyInfo = if (code == 3) vibration else legacyRuntime
            assertTrue(verifiedStates(legacy, code, permission, legacyInfo, 35, false).isNotEmpty())
            for (sdk in listOf(0, 29, 30, 31, 32, 33, 34, 36, 100)) {
                assertTrue(verifiedStates(legacy, code, permission, legacyInfo, sdk, false).isEmpty())
            }
        }
    }

    @Test
    fun operationNamesMustMatchTheVerifiedCodeAndPermissionInBothProfiles() {
        val operations = listOf(
            Triple(26, "CAMERA", "android.permission.CAMERA"),
            Triple(27, "RECORD_AUDIO", "android.permission.RECORD_AUDIO"),
            Triple(3, "VIBRATE", "android.permission.VIBRATE"),
        )
        for (profile in listOf(legacy, board)) {
            for ((code, name, permission) in operations) {
                val background = code != 3 && profile == legacy
                val runtime = code != 3
                val info = OperationInfo("DENY", background, runtime, runtime)
                val exact = PermissionPolicy.verifiedStates(profile, code, name, permission, info, 35, false)
                assertTrue(exact.isNotEmpty())
                for (incorrect in listOf(null, "", name.lowercase(), "UNRELATED")) {
                    val unverified = PermissionPolicy.verifiedStates(
                        profile, code, incorrect, permission, info, 35, false,
                    )
                    assertTrue(unverified.isEmpty())
                    assertTrue(PermissionPolicy.allowedStatesForTarget(info, unverified, true, null).isEmpty())
                    assertThrows(IllegalArgumentException::class.java) {
                        PermissionPolicy.validateOperation(permission, setOf(permission), info, "IGNORE", unverified)
                    }
                }
            }
        }
    }

    @Test
    fun changedOperationMappingBeforeWriteIsRejectedAndAfterWriteIsUncertain() {
        val info = OperationInfo("DENY", false, true, true)
        val permission = "android.permission.CAMERA"
        for (changeStage in listOf("before", "write", "read")) {
            var name: String? = if (changeStage == "before") "WRONG" else "CAMERA"
            var wrote = false
            var read = false
            val error = assertThrows(Exception::class.java) {
                PermissionPolicy.guardedWrite(
                    verify = {
                        val states = PermissionPolicy.verifiedStates(board, 26, name, permission, info, 35, false)
                        PermissionPolicy.validateOperation(permission, setOf(permission), info, "IGNORE", states)
                    },
                    write = {
                        wrote = true
                        if (changeStage == "write") name = null
                    },
                    read = {
                        read = true
                        if (changeStage == "read") name = "WRONG"
                        "IGNORE"
                    },
                )
            }
            assertEquals(changeStage != "before", wrote)
            assertEquals(changeStage == "read", read)
            if (changeStage != "before") {
                assertTrue(error is IllegalStateException && error.message!!.contains("无法确认"))
            } else {
                assertTrue(error is IllegalArgumentException)
            }
        }
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
    fun sharedUidIsRejectedBeforeWritingOrReadingBack() {
        PermissionPolicy.validateUnsharedUserId(null)
        for (sharedUserId in listOf("shared.uid", "")) {
            var wrote = false
            var read = false
            val error = assertThrows(IllegalArgumentException::class.java) {
                PermissionPolicy.guardedWrite(
                    verify = { PermissionPolicy.validateUnsharedUserId(sharedUserId) },
                    write = { wrote = true },
                    read = { read = true },
                )
            }
            assertEquals(PermissionPolicy.SHARED_UID_READ_ONLY, error.message)
            assertEquals(false, wrote)
            assertEquals(false, read)
        }
    }

    @Test
    fun sharedUidDiscoveredAfterWriteOrReadbackIsUncertain() {
        for (changeDuringRead in listOf(false, true)) {
            var sharedUserId: String? = null
            var wrote = false
            var read = false
            val error = assertThrows(IllegalStateException::class.java) {
                PermissionPolicy.guardedWrite(
                    verify = { PermissionPolicy.validateUnsharedUserId(sharedUserId) },
                    write = {
                        wrote = true
                        if (!changeDuringRead) sharedUserId = "shared.uid"
                    },
                    read = {
                        read = true
                        sharedUserId = "shared.uid"
                        "DENY"
                    },
                )
            }
            assertTrue(error.message!!.contains("无法确认"))
            assertEquals(PermissionPolicy.SHARED_UID_READ_ONLY, error.cause?.message)
            assertTrue(wrote)
            assertEquals(changeDuringRead, read)
        }
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
