package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetIdentityTest {
    private fun fingerprint(
        packageName: String = "dev.test.target",
        userId: Int = 0,
        uid: Int = 10501,
        firstInstallTime: Long = 100,
        lastUpdateTime: Long = 200,
        versionCode: Long = 1,
        sourceDir: String? = "/data/app/first/base.apk",
        signers: List<ByteArray> = listOf(byteArrayOf(1), byteArrayOf(2)),
    ): String? = TargetIdentity.fingerprint(
        packageName, userId, uid, firstInstallTime, lastUpdateTime, versionCode, sourceDir, signers,
    )

    @Test
    fun signerOrderDoesNotChangeTheIdentity() {
        val original = fingerprint()
        assertNotNull(original)
        assertEquals(original, fingerprint(signers = listOf(byteArrayOf(2), byteArrayOf(1))))
        assertTrue(TargetIdentity.matches(original!!, original))
    }

    @Test
    fun sameUidUpdatesAndReplacementsInvalidateTheSelection() {
        val original = fingerprint()!!
        val changes = listOf(
            fingerprint(packageName = "dev.test.other"),
            fingerprint(userId = 10),
            fingerprint(uid = 10502),
            fingerprint(firstInstallTime = 101),
            fingerprint(lastUpdateTime = 201),
            fingerprint(versionCode = 2),
            fingerprint(sourceDir = "/data/app/second/base.apk"),
            fingerprint(signers = listOf(byteArrayOf(3))),
        )
        changes.forEach { assertFalse(TargetIdentity.matches(original, it)) }
    }

    @Test
    fun missingMetadataCannotAuthorizeAWrite() {
        val original = fingerprint()!!
        assertNull(fingerprint(firstInstallTime = 0))
        assertNull(fingerprint(lastUpdateTime = 0))
        assertNull(fingerprint(sourceDir = null))
        assertNull(fingerprint(signers = emptyList()))
        assertNull(fingerprint(signers = listOf(byteArrayOf())))
        assertFalse(TargetIdentity.matches(original, null))
        assertFalse(TargetIdentity.matches("invalid", "invalid"))
    }
}
