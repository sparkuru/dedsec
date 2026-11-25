package im.majo.thanox_bridge

internal enum class ThanoxProfile(val serviceVersion: String, val targetSdks: Set<Int>) {
    ANDROID_16_THANOX_9_1("9.1", setOf(35)),
    BOARD_ANDROID_11_THANOX_9_6("9.6", setOf(30, 35));

    val targetSdkDescription: String
        get() = targetSdks.sorted().joinToString(" 或 ")
}

internal data class ThanoxPackageVersion(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
)

internal object ThanoxCompatibility {
    const val PHONE_PRODUCT = "PLR110"
    const val PHONE_PACKAGE = "github.tornaco.android.thanos.pro"
    const val BOARD_PRODUCT = "rk3568_firefly_aioj"
    const val BOARD_PACKAGE = "github.tornaco.android.thanos"

    fun packageForDevice(api: Int, product: String?): String? = when {
        api == 36 && product == PHONE_PRODUCT -> PHONE_PACKAGE
        api == 30 && product == BOARD_PRODUCT -> BOARD_PACKAGE
        else -> null
    }

    fun select(
        api: Int,
        product: String?,
        serviceVersion: String?,
        packageVersion: ThanoxPackageVersion?,
    ): ThanoxProfile? = when {
        api == 36 && product == PHONE_PRODUCT && serviceVersion == "9.1" &&
            packageVersion?.packageName == PHONE_PACKAGE &&
            packageVersion.versionName == "9.1-row" && packageVersion.versionCode == 3380725L ->
            ThanoxProfile.ANDROID_16_THANOX_9_1
        api == 30 && product == BOARD_PRODUCT && serviceVersion == "9.6" &&
            packageVersion?.packageName == BOARD_PACKAGE &&
            packageVersion.versionName == "9.6-prc" && packageVersion.versionCode == 3404474L ->
            ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6
        else -> null
    }
}
