package im.majo.tacchi.probe

import android.app.Activity
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import im.majo.tacchi.ipc.ProbeClient
import im.majo.tacchi.ipc.ProbeTicket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Owned only by the foreground fixture Activity; no credential or cipher leaves this process. */
class HardwareProbe(private val activity: Activity, private val display: (String) -> Unit) {
    private val client = ProbeClient(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var ticket: ProbeTicket? = null
    private var keyAlias: String? = null
    private var cleartext: ByteArray? = null
    private var encrypted: ByteArray? = null
    private var cancellation: CancellationSignal? = null
    private var generation = 0L
    private var stage = Stage.IDLE
    private val expiry = Runnable { cancel() }

    fun loaded() {
        show(if (client.loaded()) "模块已加载；管理端已确认" else "模块已加载；管理端不可用或身份不匹配")
    }

    fun start() {
        if (stage != Stage.IDLE || activity.isFinishing || activity.isDestroyed) return
        val granted = client.begin()
        if (granted == null) {
            show("无法开始：管理端不可用、身份不匹配或已有探针")
            return
        }
        ticket = granted
        generation++
        stage = Stage.ENCRYPT
        handler.postDelayed(expiry, 90_000L)
        try {
            val alias = "tacchi.p0.synthetic-roundtrip"
            keyAlias = alias
            // The fixture has one serialized main-process probe; remove its exact orphan after process death.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(-1)
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(spec.build())
            val key = generator.generateKey()
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val hardware = if (Build.VERSION.SDK_INT >= 31) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
            if (!hardware || !info.isUserAuthenticationRequired ||
                !info.isUserAuthenticationRequirementEnforcedBySecureHardware ||
                info.userAuthenticationValidityDurationSeconds != -1 ||
                !info.isInvalidatedByBiometricEnrollment ||
                (Build.VERSION.SDK_INT >= 30 && info.userAuthenticationType != KeyProperties.AUTH_BIOMETRIC_STRONG)) {
                finish("hardware_unavailable")
                return
            }
            cleartext = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            authenticate(cipher, key, Stage.ENCRYPT, generation)
        } catch (_: Exception) {
            finish("hardware_unavailable")
        }
    }

    fun cancel() {
        if (stage != Stage.IDLE) finish("canceled")
    }

    private fun authenticate(cipher: Cipher, key: SecretKey, expected: Stage, token: Long) {
        try {
            authenticatePrompt(cipher, key, expected, token)
        } catch (_: Exception) {
            if (generation == token && stage == expected) finish("auth_failed")
        }
    }

    // Executes in the fixture UID (whose manifest declares USE_BIOMETRIC), not the manager APK UID.
    @SuppressLint("MissingPermission")
    private fun authenticatePrompt(cipher: Cipher, key: SecretKey, expected: Stage, token: Long) {
        if (!current(token, expected)) return
        if (activity.checkSelfPermission(Manifest.permission.USE_BIOMETRIC) != PackageManager.PERMISSION_GRANTED) {
            finish("auth_failed")
            return
        }
        val signal = CancellationSignal()
        cancellation = signal
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(if (expected == Stage.ENCRYPT) "tacchi 测试加密" else "tacchi 测试解密")
            .setDescription("只处理本进程生成的测试数据；每次操作单独认证")
            .setNegativeButton("取消", activity.mainExecutor) { _, _ ->
                if (generation == token && stage == expected) cancel()
            }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        show(if (expected == Stage.ENCRYPT) "等待加密认证" else "等待解密认证")
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), signal, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!current(token, expected)) return
                    if (result.cryptoObject?.cipher !== cipher) {
                        finish("auth_failed")
                        return
                    }
                    try {
                        if (expected == Stage.ENCRYPT) {
                            encrypted = cipher.doFinal(cleartext ?: return finish("crypto_failed"))
                            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
                            decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, cipher.iv))
                            stage = Stage.DECRYPT
                            handler.post {
                                if (current(token, Stage.DECRYPT)) authenticate(decrypt, key, Stage.DECRYPT, token)
                            }
                        } else {
                            val recovered = cipher.doFinal(encrypted ?: return finish("crypto_failed"))
                            val matches = try { MessageDigest.isEqual(recovered, cleartext) }
                            finally { recovered.fill(0) }
                            finish(if (matches) "passed" else "crypto_failed")
                        }
                    } catch (_: Exception) { finish("crypto_failed") }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (generation == token && stage == expected) {
                        finish(if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) "canceled" else "auth_failed")
                    }
                }
            })
    }

    private fun current(token: Long, expected: Stage): Boolean {
        if (generation != token || stage != expected) return false
        val active = ticket
        if (activity.isFinishing || activity.isDestroyed || active == null || !client.valid(active)) {
            finish("canceled")
            return false
        }
        return true
    }

    private fun finish(result: String) {
        if (stage == Stage.IDLE) return
        val completed = ticket
        stage = Stage.IDLE
        generation++
        ticket = null
        handler.removeCallbacks(expiry)
        runCatching { cancellation?.cancel() }
        cancellation = null
        cleartext?.fill(0)
        cleartext = null
        encrypted?.fill(0)
        encrypted = null
        var cleaned = true
        keyAlias?.let { alias ->
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            catch (_: Exception) { cleaned = false }
        }
        keyAlias = null
        val outcome = if (cleaned) result else "crypto_failed"
        val acknowledged = completed != null && client.finish(completed, outcome)
        show(if (acknowledged) "探针结果：$outcome" else "探针已停止；管理端未确认结果")
    }

    private enum class Stage { IDLE, ENCRYPT, DECRYPT }

    private fun show(message: String) {
        // A destroyed fixture view must not prevent session/key cleanup or crash its process.
        runCatching { display(message) }
    }
}
