package im.majo.tacchi.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityPolicyTest {
    private val pin = "a".repeat(64)
    private val valid = PeerEvidence(true, setOf("dev.tacchi.probe"), 10001, listOf(pin), listOf(pin))

    private fun accepts(evidence: PeerEvidence) = IdentityPolicy.accepts(10001, "dev.tacchi.probe", pin, evidence)

    @Test fun onlyExpectedSinglePackageAndPinnedSignersAreAccepted() { assertTrue(accepts(valid)) }
    @Test fun otherUserIsRejected() { assertFalse(accepts(valid.copy(sameUser = false))) }
    @Test fun uidMismatchIsRejected() { assertFalse(accepts(valid.copy(packageUid = 10002))) }
    @Test fun wrongPackageAndSharedUidAreRejected() {
        assertFalse(accepts(valid.copy(uidPackages = setOf("impostor"))))
        assertFalse(accepts(valid.copy(uidPackages = setOf("dev.tacchi.probe", "shared"))))
        assertFalse(accepts(valid.copy(uidPackages = emptySet())))
    }
    @Test fun wrongRemoteOrLocalCertificateIsRejected() {
        assertFalse(accepts(valid.copy(signerDigests = listOf("b".repeat(64)))))
        assertFalse(accepts(valid.copy(localSignerDigests = listOf("b".repeat(64)))))
        assertFalse(accepts(valid.copy(signerDigests = listOf(pin, pin))))
        assertFalse(accepts(valid.copy(signerDigests = emptyList())))
    }
}
