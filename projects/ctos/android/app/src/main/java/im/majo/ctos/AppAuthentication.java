package im.majo.ctos;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** System verification owns only a single bound key operation, never an app-wide verified flag. */
final class AppAuthentication {
    private static final String ALIAS_PREFIX = "ctos.vault.quick.";
    private static final java.util.Set<String> candidates = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Activity activity;
    private final Handler main;
    private final Executor worker;
    private final AppSecrets vault;
    private final KeyguardManager keyguard;
    private Request active;
    private boolean foreground;

    interface Completion { void complete(VaultException failure); }
    private static final class Request {
        final String id;
        final String operation;
        final VaultEnvelope envelope;
        final String alias;
        final long epoch;
        final long deadline;
        final byte[] input;
        final byte[] nonce;
        final AppSecrets.EffectGuard guard;
        final Completion completion;
        final CancellationSignal cancellation = new CancellationSignal();
        Cipher cipher;
        int uiCode = -1;
        volatile boolean finished;
        boolean awaitingSuccess;
        Runnable timeout;

        Request(String id, String operation, VaultEnvelope envelope, String alias, long epoch,
                long deadline, byte[] input, byte[] nonce, AppSecrets.EffectGuard guard, Completion completion) {
            this.id = id;
            this.operation = operation;
            this.envelope = envelope;
            this.alias = alias;
            this.epoch = epoch;
            this.deadline = deadline;
            this.input = input;
            this.nonce = nonce;
            this.guard = guard;
            this.completion = completion;
        }
    }

    AppAuthentication(Activity activity, Handler main, Executor worker, AppSecrets vault) {
        this.activity = activity;
        this.main = main;
        this.worker = worker;
        this.vault = vault;
        keyguard = activity.getSystemService(KeyguardManager.class);
    }

    Map<String, Object> availability() {
        Map<String, Object> map = new HashMap<>();
        boolean secure = keyguard != null && keyguard.isDeviceSecure();
        boolean available = secure;
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager manager = activity.getSystemService(BiometricManager.class);
            available = secure && manager != null && manager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }
        map.put("quickUnlockAvailable", available);
        map.put("quickUnlockMethods", Build.VERSION.SDK_INT >= 30
                ? VaultCollections.list("strong_biometric", "device_credential") : VaultCollections.list("device_credential"));
        map.put("quickUnlockReason", available ? "" : "A secure device lock or supported strong authentication is required");
        return map;
    }

    boolean managedUi() { return active != null && !active.finished; }

    void start(String requestId, String operation, VaultEnvelope envelope, byte[] wrapper, byte[] key, AppSecrets.EffectGuard guard,
            Completion completion) throws VaultException {
        if (active != null) throw new VaultException("BUSY", "System authentication is already active");
        if (!vault.session.foreground()) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app");
        if (!Boolean.TRUE.equals(availability().get("quickUnlockAvailable"))) {
            throw new VaultException("VAULT_UNAVAILABLE", "Secure system authentication is unavailable");
        }
        if (envelope == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
        String alias;
        byte[] input;
        byte[] nonce = null;
        if (operation.equals("enable")) {
            if (key == null) throw VaultException.authentication();
            alias = ALIAS_PREFIX + UUID.randomUUID();
            input = key.clone();
        } else {
            byte[] material = wrapper;
            if (material == null) throw new VaultException("VAULT_UNAVAILABLE", "Shortcut unlock is not enabled");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(material))) {
                if (in.readInt() != 1) throw VaultException.invalid();
                alias = VaultCodec.readText(in, 128);
                byte[] binding = new byte[32];
                in.readFully(binding);
                if (!alias.startsWith(ALIAS_PREFIX) || !MessageDigest.isEqual(binding, envelope.materialBinding())) {
                    throw new VaultException("VAULT_EXPIRED", "Shortcut material no longer matches the vault");
                }
                nonce = new byte[12];
                in.readFully(nonce);
                input = VaultCodec.readBytes(in, 64);
                if (input.length != 48 || in.available() != 0) throw VaultException.invalid();
            } catch (java.io.IOException error) { throw VaultException.invalid(); }
        }
        Request request = new Request(requestId, operation, envelope, alias, vault.session.epoch(),
                android.os.SystemClock.elapsedRealtime() + 120000, input, nonce, guard, completion);
        active = request;
        if (operation.equals("enable")) candidates.add(alias);
        request.timeout = () -> { if (active == request) finish(request,
                new VaultException("VAULT_TIMED_OUT", "System authentication timed out")); };
        main.postDelayed(request.timeout, 120000);
        worker.execute(() -> {
            try {
                if (operation.equals("enable")) generate(alias);
                if (Build.VERSION.SDK_INT >= 30) request.cipher = cipher(request);
                main.post(() -> prompt(request));
            } catch (Exception error) { main.post(() -> finish(request,
                    new VaultException("VAULT_UNAVAILABLE", "Shortcut key is unavailable; use the master password"))); }
        });
    }

    private void prompt(Request request) {
        if (active != request || request.finished) return;
        try {
            request.guard.check();
            vault.session.assertEpoch(request.epoch);
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity).setTitle("解锁 ctOS 秘密库")
                        .setSubtitle(request.operation.equals("enable") ? "登记本设备快捷解锁" : "使用本设备快捷解锁")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL).build();
                prompt.authenticate(new BiometricPrompt.CryptoObject(request.cipher), request.cancellation,
                        activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                if (result.getCryptoObject() == null || result.getCryptoObject().getCipher() != request.cipher) {
                                    finish(request, new VaultException("VAULT_FAILED", "Authentication operation did not match"));
                                } else success(request);
                            }
                            @Override public void onAuthenticationError(int code, CharSequence message) {
                                String error = code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                                        || code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
                                        ? "VAULT_LOCKED_OUT" : code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                        || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ? "VAULT_CANCELLED" : "VAULT_UNAVAILABLE";
                                finish(request, new VaultException(error, "System authentication did not complete"));
                            }
                        });
            } else {
                // API28/29 cannot combine per-use CryptoObject with lockscreen credentials.
                // A bounded auth-required key is actually used after the explicit system credential flow.
                Intent intent = keyguard.createConfirmDeviceCredentialIntent("ctOS 秘密库", "验证本设备锁屏凭证");
                if (intent == null) throw new VaultException("VAULT_UNAVAILABLE", "Device credential verification is unavailable");
                request.uiCode = VaultUiRequests.allocate();
                activity.startActivityForResult(intent, request.uiCode);
            }
        } catch (Exception error) { finish(request, error instanceof VaultException
                ? (VaultException) error : new VaultException("VAULT_UNAVAILABLE", "System authentication is unavailable")); }
    }

    boolean onActivityResult(int requestCode, int resultCode) {
        Request request = active;
        if (request == null || request.uiCode != requestCode) return false;
        if (request != null) {
            if (resultCode == Activity.RESULT_OK) success(request);
            else finish(request, new VaultException("VAULT_CANCELLED", "System authentication was cancelled"));
        }
        return true;
    }

    void onPause() { foreground = false; }
    void onResume() {
        foreground = true;
        if (active != null && active.awaitingSuccess) perform(active);
    }
    private void success(Request request) {
        if (active != request || request.finished) return;
        request.awaitingSuccess = true;
        if (foreground) perform(request);
    }

    private void perform(Request request) {
        if (active != request || request.finished) return;
        request.awaitingSuccess = false;
        worker.execute(() -> {
            VaultException failure = null;
            byte[] output = null;
            try {
                request.guard.check();
                if (request.finished) throw new VaultException("VAULT_CANCELLED", "Authentication was cancelled");
                vault.session.assertEpoch(request.epoch);
                if (android.os.SystemClock.elapsedRealtime() >= request.deadline) {
                    throw new VaultException("VAULT_EXPIRED", "Authentication expired");
                }
                if (Build.VERSION.SDK_INT < 30) request.cipher = cipher(request);
                // AAD may advance the protected Keystore operation; submit it only after authentication.
                request.cipher.updateAAD(request.envelope.materialBinding());
                output = request.cipher.doFinal(request.input);
                request.guard.check();
                if (request.finished) throw new VaultException("VAULT_CANCELLED", "Authentication was cancelled");
                if (request.operation.equals("enable")) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(buffer);
                    out.writeInt(1);
                    VaultCodec.writeText(out, request.alias);
                    out.write(request.envelope.materialBinding());
                    out.write(request.cipher.getIV());
                    VaultCodec.writeBytes(out, output);
                    vault.setQuick(request.envelope, buffer.toByteArray(), request.epoch, request.guard);
                } else vault.quickUnlock(request.envelope, output, request.epoch, request.guard);
            } catch (Exception error) {
                failure = error instanceof VaultException ? (VaultException) error
                        : new VaultException("VAULT_UNAVAILABLE", "Shortcut key is unavailable; use the master password");
            } finally { VaultCodec.wipe(output); }
            VaultException result = failure;
            main.post(() -> finish(request, result));
        });
    }

    private static Cipher cipher(Request request) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(request.alias, null);
        if (key == null) throw new java.security.InvalidKeyException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (request.operation.equals("enable")) cipher.init(Cipher.ENCRYPT_MODE, key);
        else cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, request.nonce));
        return cipher;
    }

    @SuppressWarnings("deprecation")
    private static void generate(String alias) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0,
                KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        else spec.setUserAuthenticationValidityDurationSeconds(120);
        generator.init(spec.build());
        generator.generateKey();
    }

    void cancel(String requestId) {
        if (active != null && (requestId == null || active.id.equals(requestId))) {
            finish(active, new VaultException("VAULT_CANCELLED", "System authentication was cancelled"));
        }
    }

    private void finish(Request request, VaultException failure) {
        if (request.finished) return;
        request.finished = true;
        if (request.timeout != null) main.removeCallbacks(request.timeout);
        request.cancellation.cancel();
        VaultCodec.wipe(request.input);
        if (active == request) active = null;
        if (request.operation.equals("enable")) {
            candidates.remove(request.alias);
            cleanupDisabledKeys();
        }
        request.completion.complete(failure);
    }

    void cleanupDisabledKeys() {
        worker.execute(() -> {
            try {
                String retained = null;
                byte[] wrapper = vault.quickMaterial();
                if (wrapper != null) {
                    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wrapper))) {
                        if (in.readInt() != 1) return;
                        retained = VaultCodec.readText(in, 128);
                    }
                }
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                java.util.Enumeration<String> aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (alias.startsWith(ALIAS_PREFIX) && !alias.equals(retained) && !candidates.contains(alias)) store.deleteEntry(alias);
                }
            } catch (Exception ignored) { /* Persisted disabled state remains authoritative. Retry on next start. */ }
        });
    }

    private static void deleteAlias(String alias) {
        try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); }
        catch (Exception ignored) { /* Orphan key has no enabled wrapper; cleanup retries on startup. */ }
    }
}
