package im.majo.tacchi.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import im.majo.tacchi.BuildConfig
import java.security.MessageDigest

object PeerIdentity {
    const val MANAGER = "im.majo.tacchi"
    const val HOST = "dev.tacchi.probe"
    const val AUTHORITY = "$MANAGER.probe"

    fun accepts(context: Context, uid: Int, expectedPackage: String): Boolean {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toSet() ?: return false
        return try {
            val peer = pm.getPackageInfo(expectedPackage, PackageManager.GET_SIGNING_CERTIFICATES)
            val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val peerSigners = peer.signingInfo?.apkContentsSigners ?: return false
            val ownSigners = own.signingInfo?.apkContentsSigners ?: return false
            IdentityPolicy.accepts(uid, expectedPackage, BuildConfig.PROBE_SIGNER_SHA256, PeerEvidence(
                sameUser = UserHandle.getUserHandleForUid(uid) == Process.myUserHandle(),
                uidPackages = packages,
                packageUid = peer.applicationInfo?.uid ?: -1,
                signerDigests = peerSigners.map { fingerprint(it.toByteArray()) },
                localSignerDigests = ownSigners.map { fingerprint(it.toByteArray()) },
            ))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun fingerprint(certificate: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate).joinToString("") { "%02x".format(it) }

    fun managerAvailable(context: Context): Boolean {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return false
        return provider.packageName == MANAGER && accepts(context, provider.applicationInfo.uid, MANAGER)
    }
}
