package im.majo.thanox_bridge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** A stale-selection guard, not an atomic authorization for a later Binder call. */
internal object TargetIdentity {
    fun fingerprint(
        packageName: String,
        userId: Int,
        uid: Int,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        versionCode: Long,
        sourceDir: String?,
        signingCertificates: List<ByteArray>,
    ): String? {
        if (packageName.isBlank() || userId < 0 || uid < 0 ||
            firstInstallTime <= 0 || lastUpdateTime <= 0 || sourceDir.isNullOrBlank() ||
            signingCertificates.isEmpty() || signingCertificates.any { it.isEmpty() }
        ) return null

        val signerDigests = signingCertificates.map(::sha256).sorted()
        val fields = ByteArrayOutputStream()
        DataOutputStream(fields).use {
            it.writeUTF(packageName)
            it.writeInt(userId)
            it.writeInt(uid)
            it.writeLong(firstInstallTime)
            it.writeLong(lastUpdateTime)
            it.writeLong(versionCode)
            it.writeUTF(sourceDir)
            it.writeInt(signerDigests.size)
            signerDigests.forEach(it::writeUTF)
        }
        return "v1:${sha256(fields.toByteArray())}"
    }

    fun matches(expected: String, actual: String?): Boolean =
        expected.matches(Regex("v1:[0-9a-f]{64}")) && expected == actual

    private fun sha256(input: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input)
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
