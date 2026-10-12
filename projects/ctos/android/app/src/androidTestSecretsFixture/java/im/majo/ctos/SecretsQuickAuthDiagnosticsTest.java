package im.majo.ctos;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import org.junit.Before;
import org.junit.Test;

/** Isolated capability/key-initialization diagnostics; never authenticates or constructs a vault owner. */
public final class SecretsQuickAuthDiagnosticsTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String ARGUMENT = "ctosQuickAuthDiagnostics";
    private static final String KEY_ARGUMENT = "ctosQuickAuthKeyProbe";
    private static final String OWN_STATE_ARGUMENT = "ctosQuickAuthOwnState";
    private static final String OWN_UID_ARGUMENT = "ctosQuickAuthExpectedUid";
    private static final String TEST_UID_ARGUMENT = "ctosQuickAuthExpectedTestUid";
    private static final String OWN_ALIAS_ARGUMENT = "ctosQuickAuthOwnAlias";
    private static final String PROBE_ALIAS_PREFIX = "ctos.fixture.authprobe.";
    private static final String TAG = "CtosQuickAuthDiag";
    private Context target;
    private String runId;

    @Before public void requireExplicitFixture() throws Exception {
        String optIn = InstrumentationRegistry.getArguments().getString(ARGUMENT);
        org.junit.Assume.assumeTrue("Quick-auth diagnostics were not requested", optIn != null);
        assertEquals("Diagnostics require an explicit true argument", "true", optIn);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        assertEquals("Only the independent fixture is authorized", TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        assertTrue("Fixture requires an application UID", Process.myUid() >= Process.FIRST_APPLICATION_UID);
        PackageManager packages = target.getPackageManager();
        PackageInfo app = packages.getPackageInfo(TARGET, 0);
        PackageInfo test = packages.getPackageInfo(TARGET + ".test", 0);
        assertNull("The fixture must not share a UID", app.sharedUserId);
        assertNull("The test package must not share a UID", test.sharedUserId);
        assertNotEquals("Installed fixture and test packages must be independent", app.applicationInfo.uid, test.applicationInfo.uid);
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse("Own Root auto-start must already be disabled",
                target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        runId = UUID.randomUUID().toString();
    }

    @Test public void readPublicQuickAuthenticationCapabilities() {
        PackageManager packages = target.getPackageManager();
        KeyguardManager keyguard = target.getSystemService(KeyguardManager.class);
        BiometricManager biometric = Build.VERSION.SDK_INT >= 29
                ? target.getSystemService(BiometricManager.class) : null;
        Log.i(TAG, "run=" + runId + " phase=public_services api=" + Build.VERSION.SDK_INT
                + " keyguardServicePresent=" + (keyguard != null)
                + " biometricServicePresent=" + (biometric != null)
                + " secureLockScreenFeature=" + packages.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
                + " fingerprintFeature=" + packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                + " faceFeature=" + packages.hasSystemFeature(PackageManager.FEATURE_FACE)
                + " irisFeature=" + packages.hasSystemFeature(PackageManager.FEATURE_IRIS));
        assertNotNull("Public keyguard service is unavailable", keyguard);
        Log.i(TAG, "run=" + runId + " phase=public_keyguard"
                + " isDeviceSecure=" + keyguard.isDeviceSecure()
                + " isKeyguardSecure=" + keyguard.isKeyguardSecure()
                + " isKeyguardLocked=" + keyguard.isKeyguardLocked()
                + " isDeviceLocked=" + keyguard.isDeviceLocked());
        if (Build.VERSION.SDK_INT >= 30 && biometric != null) {
            Log.i(TAG, "run=" + runId + " phase=public_can_authenticate"
                    + " strongOrCredential=" + biometric.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    + " credential=" + biometric.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    + " strong=" + biometric.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG));
        } else {
            Log.i(TAG, "run=" + runId + " phase=public_authenticator_flags_unavailable");
        }
        // Completion denotes this read-only diagnostic, never successful authentication.
        Log.i(TAG, "run=" + runId + " phase=read_only_diagnostics_complete");
    }

    @Test public void readOwnFixtureReconciliationMetadata() throws Exception {
        String optIn = InstrumentationRegistry.getArguments().getString(OWN_STATE_ARGUMENT);
        org.junit.Assume.assumeTrue("Own-state reconciliation diagnostics were not requested", optIn != null);
        assertEquals("Own-state diagnostics require their separate explicit true argument", "true", optIn);
        assertEquals("The current own UID must match the explicit inventory", expectedUid(OWN_UID_ARGUMENT), Process.myUid());
        PackageInfo test = target.getPackageManager().getPackageInfo(TARGET + ".test", 0);
        assertEquals("The current test UID must match the explicit inventory", expectedUid(TEST_UID_ARGUMENT), test.applicationInfo.uid);
        String alias = InstrumentationRegistry.getArguments().getString(OWN_ALIAS_ARGUMENT);
        if (alias != null) assertTrue("Only one exact known own shortcut UUID is authorized", alias.matches(
                "ctos\\.vault\\.quick\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        File directory = target.getNoBackupFilesDir();
        boolean[] before = knownOwnStatePresence(directory);
        boolean marker = new File(directory, "secrets-ui-flow-owned.marker").exists();
        Log.i(TAG, "run=" + runId + " phase=own_known_file_presence"
                + " vaultPresent=" + before[0] + " vaultBakPresent=" + before[1] + " vaultNewPresent=" + before[2]
                + " journalPresent=" + before[3] + " journalBakPresent=" + before[4] + " journalNewPresent=" + before[5]
                + " markerPresent=" + marker);
        boolean complete = true;
        // AtomicFile.openRead can restore .bak or remove .new. Never invoke it on
        // such a failed snapshot; the known presence metadata remains available.
        if (before[1] || before[2]) {
            Log.i(TAG, "run=" + runId + " phase=own_wrapper_presence wrapperRead=false atomicRecoveryPresent=true");
        } else {
            byte[] wrapper = null;
            try {
                wrapper = new VaultStore(target).quickMaterial();
                Log.i(TAG, "run=" + runId + " phase=own_wrapper_presence wrapperRead=true wrapperPresent=" + (wrapper != null));
            } catch (Exception failure) {
                complete = false;
                logKeyFailure("own_wrapper_read", failure);
                Log.i(TAG, "run=" + runId + " phase=own_wrapper_presence wrapperRead=false readFailed=true");
            } finally { VaultCodec.wipe(wrapper); }
        }
        assertArrayEquals("A diagnostic read must preserve all six known file presences", before, knownOwnStatePresence(directory));
        assertEquals("A diagnostic read must preserve the fixed marker", marker,
                new File(directory, "secrets-ui-flow-owned.marker").exists());
        Log.i(TAG, "run=" + runId + " phase=own_persisted_grants persistedGrantCount="
                + target.getContentResolver().getPersistedUriPermissions().size());
        if (alias != null) {
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                Log.i(TAG, "run=" + runId + " phase=own_exact_alias_presence aliasSpecified=true aliasRead=true aliasPresent="
                        + store.containsAlias(alias));
            } catch (Exception failure) {
                complete = false;
                logKeyFailure("own_exact_alias_read", failure);
                Log.i(TAG, "run=" + runId + " phase=own_exact_alias_presence aliasSpecified=true aliasRead=false");
            }
        } else {
            Log.i(TAG, "run=" + runId + " phase=own_exact_alias_presence aliasSpecified=false aliasRead=false");
        }
        KeyguardManager keyguard = target.getSystemService(KeyguardManager.class);
        assertNotNull("Public keyguard service is unavailable", keyguard);
        Log.i(TAG, "run=" + runId + " phase=own_public_keyguard"
                + " isDeviceSecure=" + keyguard.isDeviceSecure() + " isKeyguardSecure=" + keyguard.isKeyguardSecure()
                + " isKeyguardLocked=" + keyguard.isKeyguardLocked() + " isDeviceLocked=" + keyguard.isDeviceLocked());
        Log.i(TAG, "run=" + runId + " phase=own_state_read_only_diagnostic_complete readsSucceeded=" + complete);
        assertTrue("Own-state reads failed; preserve the fixture for explicit reconciliation", complete);
    }

    private int expectedUid(String argument) {
        String value = InstrumentationRegistry.getArguments().getString(argument);
        assertTrue("An explicit decimal application UID inventory is required", value != null && value.matches("[0-9]{1,10}"));
        int uid = -1;
        try { uid = Integer.parseInt(value); }
        catch (NumberFormatException ignored) { fail("Expected UID is outside the application UID range"); }
        assertTrue("Expected UID must be an application UID", uid >= Process.FIRST_APPLICATION_UID);
        return uid;
    }

    private boolean[] knownOwnStatePresence(File directory) {
        return new boolean[] {
                new File(directory, "app-secrets.bin").exists(),
                new File(directory, "app-secrets.bin.bak").exists(),
                new File(directory, "app-secrets.bin.new").exists(),
                new File(directory, "vault-export-journal.json").exists(),
                new File(directory, "vault-export-journal.json.bak").exists(),
                new File(directory, "vault-export-journal.json.new").exists()
        };
    }

    @Test public void probeOwnAuthenticationKeyInitialization() {
        String optIn = InstrumentationRegistry.getArguments().getString(KEY_ARGUMENT);
        org.junit.Assume.assumeTrue("Own-key initialization probe was not requested", optIn != null);
        assertEquals("Key initialization requires its separate explicit true argument", "true", optIn);
        assertTrue("This probe covers the original API30+ per-use policy only", Build.VERSION.SDK_INT >= 30);
        KeyguardManager keyguard = target.getSystemService(KeyguardManager.class);
        assertNotNull(keyguard);
        assertTrue("An already-secure device is required; no security setting is changed", keyguard.isDeviceSecure());
        // One public run UUID reconstructs the exact nonproduction alias for reconciliation.
        // Never enumerate aliases, and never delete a key that failed the preabsence gate.
        String alias = PROBE_ALIAS_PREFIX + runId;
        KeyStore store = null;
        boolean ownAliasEstablished = false, supported = false, cleanupConfirmed = true;
        String phase = "store_load";
        try {
            keyPhase(phase);
            store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            phase = "alias_preabsence"; keyPhase(phase);
            assertFalse("The exact unique probe alias already exists; preserve it", store.containsAlias(alias));
            ownAliasEstablished = true;
            phase = "generator_instance"; keyPhase(phase);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            phase = "key_policy_build"; keyPhase(phase);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL).build();
            phase = "generator_init"; keyPhase(phase); generator.init(spec);
            phase = "generate_key"; keyPhase(phase); generator.generateKey();
            phase = "store_get_key"; keyPhase(phase);
            Key key = store.getKey(alias, null);
            if (!(key instanceof SecretKey)) throw new GeneralSecurityException();
            logKeyMetadata((SecretKey) key);
            phase = "cipher_instance"; keyPhase(phase);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            phase = "cipher_init_encrypt"; keyPhase(phase); cipher.init(Cipher.ENCRYPT_MODE, key);
            phase = "update_public_aad"; keyPhase(phase); cipher.updateAAD(new byte[32]);
            // Frameworks may defer an operation failure until doFinal. This probe
            // deliberately does no crypto/authenticated effect and launches no prompt.
            supported = true;
        } catch (Exception failure) {
            logKeyFailure(phase, failure);
        } finally {
            if (ownAliasEstablished) {
                keyPhase("cleanup_exact_alias_attempt");
                try {
                    store.deleteEntry(alias);
                    cleanupConfirmed = !store.containsAlias(alias);
                    Log.i(TAG, "run=" + runId + " phase=cleanup_exact_alias_result attempted=true"
                            + " aliasAbsent=" + cleanupConfirmed);
                } catch (Exception failure) {
                    cleanupConfirmed = false;
                    logKeyFailure("cleanup_exact_alias", failure);
                    Log.i(TAG, "run=" + runId + " phase=cleanup_exact_alias_result attempted=true aliasAbsent=unknown");
                }
            } else {
                Log.i(TAG, "run=" + runId + " phase=cleanup_exact_alias_result attempted=false ownAliasEstablished=false");
            }
            Log.i(TAG, "run=" + runId + " phase=key_initialization_diagnostic_complete"
                    + " keyInitSupported=" + supported + " cleanupConfirmed=" + cleanupConfirmed
                    + " authenticationTested=false");
            assertTrue("Exact probe cleanup could not be confirmed; reconcile this fixture before reuse", cleanupConfirmed);
        }
    }

    private void keyPhase(String phase) { Log.i(TAG, "run=" + runId + " phase=" + phase); }

    @SuppressWarnings("deprecation")
    private void logKeyMetadata(SecretKey key) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyInfo info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
            Log.i(TAG, "run=" + runId + " phase=public_key_metadata"
                    + " authenticationRequired=" + info.isUserAuthenticationRequired()
                    + " authenticationType=" + info.getUserAuthenticationType()
                    + " authenticationValiditySeconds=" + info.getUserAuthenticationValidityDurationSeconds()
                    + " insideSecureHardware=" + info.isInsideSecureHardware());
        } catch (Exception failure) { logKeyFailure("public_key_metadata", failure); }
    }

    private void logKeyFailure(String phase, Exception failure) {
        keyPhase(phase + "_failure");
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String type = current.getClass().getName();
            if (type.length() > 160) type = type.substring(0, 160);
            Log.i(TAG, "run=" + runId + " phase=" + phase + "_failure_class"
                    + " causeDepth=" + depth + " exceptionClass=" + type);
            current = current.getCause();
        }
    }
}
