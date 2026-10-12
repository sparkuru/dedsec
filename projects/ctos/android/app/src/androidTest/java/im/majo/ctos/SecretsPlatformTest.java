package im.majo.ctos;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;

/** Actual Android helper/crypto checks; all vault contents use this test's volatile store. */
public final class SecretsPlatformTest {
    private Activity activity;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private static final class MemoryStore implements AppSecrets.Store {
        byte[] bytes, wrapper;
        int commits;
        @Override public byte[] read() { return bytes == null ? null : bytes.clone(); }
        @Override public byte[] quickMaterial() { return wrapper == null ? null : wrapper.clone(); }
        @Override public void commit(byte[] next, byte[] quick, AppSecrets.EffectGuard guard) throws VaultException {
            guard.beforeCommit(); VaultCodec.wipe(bytes); VaultCodec.wipe(wrapper);
            bytes = next.clone(); wrapper = quick == null ? null : quick.clone(); commits++;
        }
        void close() { VaultCodec.wipe(bytes); VaultCodec.wipe(wrapper); }
    }
    private Activity launch() {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        return activity;
    }
    @After public void finishActivity() {
        if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
    }
    @Test public void actualWindowPreservesNestedInheritedAndBackgroundOwners() {
        Activity host = launch();
        instrumentation.runOnMainSync(() -> {
            int original = host.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE;
            try {
                host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                SensitiveWindow window = new SensitiveWindow(host);
                String first = window.acquire(), second = window.acquire();
                window.release(first); window.release(first);
                assertSecure(host, true);
                window.background(true); window.release(second); assertSecure(host, true);
                window.background(false); assertSecure(host, false);
                host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                SensitiveWindow inherited = new SensitiveWindow(host);
                String owner = inherited.acquire(); inherited.release(owner); assertSecure(host, true);
            } finally { host.getWindow().setFlags(original, WindowManager.LayoutParams.FLAG_SECURE); }
        });
    }
    private static void assertSecure(Activity host, boolean expected) {
        assertEquals(expected, (host.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
    @Test public void insecureDeviceRefusesQuickAuthenticationBeforeAnyWorkerOrStoreEffect() {
        Activity host = launch();
        KeyguardManager keyguard = host.getSystemService(KeyguardManager.class);
        org.junit.Assume.assumeTrue("This fixture only exercises a device without a secure credential", keyguard != null && !keyguard.isDeviceSecure());
        MemoryStore store = new MemoryStore(); AppSecrets own = new AppSecrets(store, SystemClock::elapsedRealtime);
        own.session.foreground(true);
        instrumentation.runOnMainSync(() -> {
            AppAuthentication auth = new AppAuthentication(host, new Handler(Looper.getMainLooper()),
                    ignored -> { throw new AssertionError("Unavailable authentication must not dispatch work"); }, own);
            assertEquals(false, auth.availability().get("quickUnlockAvailable"));
            try {
                auth.start("isolated-unavailable", "enable", null, null, new byte[32], () -> { },
                        ignored -> { throw new AssertionError("Unavailable authentication has no completion"); });
                fail();
            } catch (VaultException expected) { assertEquals("VAULT_UNAVAILABLE", expected.code); }
            assertFalse(auth.managedUi()); assertEquals(0, store.commits);
        });
        own.lock(); store.close();
    }
    @Test public void actualAndroidKdfCipherManagementAndPortableRestoreUseOnlyOwnMemoryStore() throws Exception {
        MemoryStore store = new MemoryStore(); AppSecrets own = new AppSecrets(store, SystemClock::elapsedRealtime);
        char[] first = "isolated-native-fixture-original".toCharArray(), next = "isolated-native-fixture-next".toCharArray();
        byte[] backup = null;
        long started = SystemClock.elapsedRealtime();
        try {
            own.session.foreground(true); own.create(first, first, () -> { }); assertTrue(own.session.unlocked());
            System.out.println("Isolated Android create elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            TotpVaultAdapter totp = new TotpVaultAdapter(own);
            byte[] syntheticSeed = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try { totp.save(totp.saveRequest("native-totp"), "native-totp", "Fixture:synthetic", "Fixture", syntheticSeed, () -> { }); }
            finally { VaultCodec.wipe(syntheticSeed); }
            assertEquals("287082", own.totpCode(totp.request("native-totp"), "native-totp", 59));
            backup = own.export(first, () -> { });
            byte[] unchanged = store.read();
            try { own.export(next, () -> { }); fail(); }
            catch (VaultException expected) { assertEquals("VAULT_AUTHENTICATION", expected.code); }
            assertArrayEquals(unchanged, store.read()); VaultCodec.wipe(unchanged);
            own.changePassword(first, next, next, () -> { });
            try (AppSecrets.Verified fresh = AppSecrets.verify(store.read(), next);
                    AppSecrets.Verified historic = AppSecrets.verify(backup, first)) {
                assertArrayEquals(historic.plaintext, fresh.plaintext);
            }
            try { AppSecrets.verify(store.read(), first).close(); fail(); }
            catch (VaultException expected) { assertEquals("VAULT_AUTHENTICATION", expected.code); }
            AppSecrets.ImportPreview preview = own.preview(backup, first, next, "isolated-native-restore", () -> { });
            own.importCommit(preview.id, false, () -> { }); assertFalse(own.session.unlocked());
            own.unlock(first, () -> { }); assertTrue(own.session.unlocked());
            try (AppSecrets.Verified restored = AppSecrets.verify(store.read(), first)) { assertEquals(1, restored.manifest.entries.size()); }
            assertEquals("287082", own.totpCode(totp.request("native-totp"), "native-totp", 59));
            assertEquals(4, store.commits);
            System.out.println("Isolated Android create/auth/export/change/restore elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            own.session.timeout(0); own.session.foreground(false); assertFalse(own.session.unlocked());
            own.session.foreground(true); own.unlock(first, () -> { }); own.session.timeout(1);
            own.session.foreground(false); SystemClock.sleep(1100); assertFalse(own.session.unlocked());
        } finally { own.lock(); store.close(); VaultCodec.wipe(backup); Arrays.fill(first, '\0'); Arrays.fill(next, '\0'); }
    }
}
