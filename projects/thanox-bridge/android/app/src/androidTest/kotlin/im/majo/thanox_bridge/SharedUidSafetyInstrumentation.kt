package im.majo.thanox_bridge

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.security.MessageDigest

@Suppress("DEPRECATION")
class SharedUidSafetyInstrumentation : Instrumentation() {
    private lateinit var options: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        options = Bundle(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val resultCode = try {
            SameRootConnectionChecks.run()
            checkSharedUidWrite()
            result.putString("stream", "\nSame-root Binder safety: PASS\nShared UID safety: PASS\n")
            Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString(
                "stream",
                "\nShared UID safety: FAIL ${error.javaClass.simpleName}: ${error.message}\n",
            )
            Activity.RESULT_CANCELED
        }
        finish(resultCode, result)
    }

    private fun checkSharedUidWrite() {
        val (expectedProfile, requestedMode) = when {
            Build.VERSION.SDK_INT == 36 && Build.PRODUCT == "PLR110" ->
                ThanoxProfile.ANDROID_16_THANOX_9_1 to "ALLOW_FOREGROUND_ONLY"
            Build.VERSION.SDK_INT == 30 && Build.PRODUCT == ThanoxCompatibility.BOARD_PRODUCT ->
                ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6 to "ALLOW_ALWAYS"
            else -> error("This test requires a verified device and Android version")
        }
        check(Process.myUid() / USER_ID_RANGE == 0) { "This test requires Android user 0" }
        check(targetContext.packageName == "im.majo.thanox_bridge") { "Unexpected target application" }
        val expectedUid = options.getString("expectedUid")?.toIntOrNull()
            ?: error("Missing or invalid expectedUid instrumentation argument")
        check(expectedUid in 10_000 until USER_ID_RANGE) { "Expected UID must identify a user-0 app" }
        val expectedSigner = options.getString("fixtureSignerSha256")
            ?: error("Missing fixtureSignerSha256 instrumentation argument")
        check(expectedSigner.matches(Regex("[0-9a-f]{64}"))) { "Invalid fixture signer SHA-256" }

        val packages = targetContext.packageManager
        val fixtures = listOf(TARGET_A, TARGET_B).map { packageName ->
            val info = packages.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES,
            )
            checkFixture(info, packageName, expectedUid, expectedSigner)
            packageName
        }
        checkDenied(packages, fixtures, "before write")

        val ops = ThanoxOps.connect(targetContext)
        check(ops.profile == expectedProfile) { "Unexpected Thanox compatibility profile" }
        val info = ops.info(26, TARGET_A, 0) ?: error("CAMERA operation information missing")
        val verifiedStates = PermissionPolicy.verifiedStates(
            ops.profile, 26, ops.opName(26), ops.opPermission(26), info, 35, false,
        )
        check(requestedMode in PermissionPolicy.allowedStates(info, verifiedStates)) {
            "CAMERA mode is not independently permitted by the verified profile"
        }

        val repository = PermissionRepository(targetContext)
        val details = repository.appDetails(TARGET_A, 0, expectedUid)
        check(details["serviceError"] == null) { "Thanox detail read failed: ${details["serviceError"]}" }
        val identityToken = (details["identityToken"] as? String)?.takeIf { it.isNotBlank() }
            ?: error("Fixture identity token unavailable")
        val camera = (details["ops"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.singleOrNull {
            it["code"] == 26 && it["permission"] == CAMERA
        } ?: error("CAMERA operation missing from fixture details")
        check((camera["allowedStates"] as? List<*>)?.isEmpty() == true) {
            "CAMERA unexpectedly exposes writable states"
        }
        check((details["coverageNote"] as? String)?.contains("共享 UID") == true) {
            "Shared UID restriction is missing from the coverage note"
        }

        val failure = try {
            repository.setOpMode(TARGET_A, expectedUid, 0, identityToken, 26, requestedMode)
            null
        } catch (error: Exception) {
            error
        }
        checkDenied(packages, fixtures, "after write attempt")
        check(failure is IllegalArgumentException && failure.message?.contains("共享 UID") == true) {
            "Expected a shared UID rejection; got ${failure?.javaClass?.simpleName ?: "success"}"
        }
    }

    private fun checkFixture(
        info: PackageInfo,
        packageName: String,
        expectedUid: Int,
        expectedSigner: String,
    ) {
        val app = info.applicationInfo ?: error("Fixture application info missing: $packageName")
        check(info.packageName == packageName && app.packageName == packageName) { "Fixture package mismatch" }
        check(app.uid == expectedUid) { "Fixture UID mismatch: $packageName" }
        check(info.sharedUserId == SHARED_USER_ID) { "Fixture shared user ID mismatch: $packageName" }
        check(app.targetSdkVersion == 35) { "Fixture target SDK mismatch: $packageName" }
        check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
            "Fixture must not be a system application: $packageName"
        }
        check(app.flags and ApplicationInfo.FLAG_HAS_CODE == 0) { "Fixture must have no code: $packageName" }
        check(info.requestedPermissions?.contains(CAMERA) == true) { "Fixture CAMERA declaration missing: $packageName" }
        val signers = info.signingInfo?.apkContentsSigners ?: error("Fixture signer unavailable: $packageName")
        check(signers.size == 1 && sha256(signers.single().toByteArray()) == expectedSigner) {
            "Fixture signer mismatch: $packageName"
        }
    }

    private fun checkDenied(packages: PackageManager, fixtures: List<String>, stage: String) {
        fixtures.forEach { packageName ->
            check(packages.checkPermission(CAMERA, packageName) == PackageManager.PERMISSION_DENIED) {
                "CAMERA is not denied for $packageName $stage"
            }
        }
    }

    private fun sha256(input: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(input)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TARGET_A = "im.majo.thanoxbridge.test.shareduida20261123"
        private const val TARGET_B = "im.majo.thanoxbridge.test.shareduidb20261123"
        private const val SHARED_USER_ID = "im.majo.thanoxbridge.test.shareduid20261123"
        private const val CAMERA = "android.permission.CAMERA"
    }
}
