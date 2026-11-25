package im.majo.thanox_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThanoxCompatibilityTest {
    private val boardPackage = ThanoxPackageVersion("github.tornaco.android.thanos", "9.6-prc", 3404474L)
    private val phonePackage = ThanoxPackageVersion("github.tornaco.android.thanos.pro", "9.1-row", 3380725L)
    private val board = "rk3568_firefly_aioj"
    private val phone = "PLR110"

    @Test
    fun selectsOnlyVerifiedDeviceServiceAndPackageCombinations() {
        assertEquals(
            ThanoxProfile.ANDROID_16_THANOX_9_1,
            ThanoxCompatibility.select(36, phone, "9.1", phonePackage),
        )
        assertEquals(
            ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6,
            ThanoxCompatibility.select(30, board, "9.6", boardPackage),
        )
        assertEquals(phonePackage.packageName, ThanoxCompatibility.packageForDevice(36, phone))
        assertEquals(boardPackage.packageName, ThanoxCompatibility.packageForDevice(30, board))
        assertNull(ThanoxCompatibility.packageForDevice(36, null))
        assertNull(ThanoxCompatibility.packageForDevice(36, "other"))
        assertNull(ThanoxCompatibility.packageForDevice(30, "other"))
        assertNull(ThanoxCompatibility.packageForDevice(31, board))
    }

    @Test
    fun targetSdkDescriptionsMatchTheProfileLimits() {
        assertEquals("35", ThanoxProfile.ANDROID_16_THANOX_9_1.targetSdkDescription)
        assertEquals("30 或 35", ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6.targetSdkDescription)
    }

    @Test
    fun rejectsMissingOrMismatchedBoardPackageMetadata() {
        assertNull(ThanoxCompatibility.select(30, board, "9.6", null))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(packageName = null)))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(packageName = "other")))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(versionName = null)))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(versionName = "9.6-row")))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(versionCode = 0L)))
        assertNull(ThanoxCompatibility.select(30, board, "9.6", boardPackage.copy(versionCode = 3404475L)))
    }

    @Test
    fun rejectsMissingOrMismatchedPhonePackageMetadata() {
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", null))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(packageName = null)))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(packageName = "other")))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(versionName = null)))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(versionName = "9.1-prc")))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(versionCode = 0L)))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", phonePackage.copy(versionCode = 3380726L)))
    }

    @Test
    fun rejectsDifferentAndroidProductOrServiceVersion() {
        assertNull(ThanoxCompatibility.select(29, board, "9.6", boardPackage))
        assertNull(ThanoxCompatibility.select(31, board, "9.6", boardPackage))
        assertNull(ThanoxCompatibility.select(30, "other", "9.6", boardPackage))
        assertNull(ThanoxCompatibility.select(30, null, "9.6", boardPackage))
        assertNull(ThanoxCompatibility.select(30, board, "9.1", boardPackage))
        assertNull(ThanoxCompatibility.select(30, board, null, boardPackage))
        assertNull(ThanoxCompatibility.select(36, phone, "9.6", phonePackage))
        assertNull(ThanoxCompatibility.select(36, phone, null, phonePackage))
        assertNull(ThanoxCompatibility.select(36, board, "9.1", phonePackage))
        assertNull(ThanoxCompatibility.select(36, "other", "9.1", phonePackage))
        assertNull(ThanoxCompatibility.select(36, null, "9.1", phonePackage))
        assertNull(ThanoxCompatibility.select(36, phone, "9.1", boardPackage))
    }
}
