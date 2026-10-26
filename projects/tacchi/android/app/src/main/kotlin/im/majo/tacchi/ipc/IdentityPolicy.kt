package im.majo.tacchi.ipc

data class PeerEvidence(
    val sameUser: Boolean,
    val uidPackages: Set<String>,
    val packageUid: Int,
    val signerDigests: List<String>,
    val localSignerDigests: List<String>,
)

/** Evidence must come from Binder/PackageManager, never the request bundle. */
object IdentityPolicy {
    fun accepts(uid: Int, expectedPackage: String, pin: String, evidence: PeerEvidence): Boolean =
        pin.length == 64 && evidence.sameUser && evidence.packageUid == uid &&
            evidence.uidPackages == setOf(expectedPackage) &&
            evidence.signerDigests == listOf(pin) && evidence.localSignerDigests == listOf(pin)
}
