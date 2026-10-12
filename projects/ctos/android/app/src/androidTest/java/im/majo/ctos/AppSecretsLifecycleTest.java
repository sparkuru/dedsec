package im.majo.ctos;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/** Real Main lifecycle forwarded to a scoped Bridge. Normal Main startup/maintenance remains real.
 * Only the scoped bridge receives fixture requests. SSH hooks below are synthetic, not a parser.
 * No quick/rekey/import/Root operation is requested and no production owner is acquired by this test. */
public final class AppSecretsLifecycleTest {
    private static final String PASSWORD = "isolated-lifecycle-fixture-master";
    private static final String TARGET = "target:fixture@example.invalid:22", CONNECTION = "fixture-connection";
    private static final byte[] CREDENTIAL = "isolated-synthetic-ssh".getBytes(StandardCharsets.US_ASCII);
    private static byte[] cipher, pureCipher;
    private Fixture fixture;
    @Rule public final TestName testName = new TestName();

    @BeforeClass public static void prepareSyntheticCipher() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences root = context.getSharedPreferences("root_access", Context.MODE_PRIVATE);
        System.out.println("Lifecycle normal-startup metadata root_auto_configured=" + root.contains("auto_start")
                + " root_auto_value=" + root.getBoolean("auto_start", true) + " root_auto_default=true");
        char[] password = PASSWORD.toCharArray(); byte[] salt = VaultEnvelope.random(16), key = null, plain = null;
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(
                new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Fixture:synthetic", "Fixture", "SHA1", 6, 30, seed),
                new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, "ssh", TARGET, "Fixture", "", 0, 0, CREDENTIAL)),
                VaultCollections.list());
                VaultManifest pure = new VaultManifest(VaultCollections.list(
                        new VaultManifest.Entry(VaultManifest.EntryType.TOTP, "totp", "Fixture:synthetic", "Fixture", "SHA1", 6, 30, seed)),
                        VaultCollections.list())) {
            key = VaultEnvelope.derive(password, salt); plain = manifest.encode();
            cipher = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plain).encode();
            VaultCodec.wipe(plain); plain = pure.encode();
            pureCipher = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plain).encode();
        } finally { Arrays.fill(password, '\0'); VaultCodec.wipe(seed); VaultCodec.wipe(key); VaultCodec.wipe(plain); VaultCodec.wipe(salt); }
    }
    @AfterClass public static void wipeSyntheticCipher() { VaultCodec.wipe(cipher); VaultCodec.wipe(pureCipher); cipher = null; pureCipher = null; }

    private static void syntheticValidator(VaultManifest manifest) throws VaultException {
        // Exact generated namespace fixture only; never accepts config/files or pretends to parse SSH.
        if (!manifest.files.isEmpty() || manifest.entries.size() != 2) throw VaultException.invalid();
        boolean totp = false, ssh = false;
        for (VaultManifest.Entry entry : manifest.entries) {
            if (entry.type == VaultManifest.EntryType.TOTP && entry.id.equals("totp")) totp = true;
            else if (entry.type == VaultManifest.EntryType.SSH_PASSWORD && entry.id.equals("ssh") && entry.identity.equals(TARGET)) ssh = true;
            else throw VaultException.invalid();
        }
        if (!totp || !ssh) throw VaultException.invalid();
    }
    private static SshVaultAdapter ssh(AppSecrets vault) {
        return new SshVaultAdapter(vault, (connection, target) -> {
            if (!CONNECTION.equals(connection) || !TARGET.equals(target)) throw new VaultException("HOST_KEY", "Synthetic fixture target not approved");
        });
    }
    private static final class Receipt implements MethodChannel.Result {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile Object value;
        volatile String error;
        volatile boolean mainThread = true;
        @Override public void success(Object result) { value = result; complete(); }
        @Override public void error(String code, String message, Object details) { error = code; complete(); }
        @Override public void notImplemented() { error = "NOT_IMPLEMENTED"; complete(); }
        private void complete() { mainThread &= Looper.myLooper() == Looper.getMainLooper(); calls.incrementAndGet(); done.countDown(); }
        void await() throws Exception { assertTrue("Scoped bridge completion timed out", done.await(25, TimeUnit.SECONDS)); assertTrue(mainThread); }
        void success() throws Exception { await(); assertNull(error); assertEquals(1, calls.get()); }
    }
    private static final class ReadGate {
        final AtomicBoolean used = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        final AtomicInteger remaining;
        ReadGate(int readNumber) { remaining = new AtomicInteger(readNumber); }
    }
    private static final class GatedStore implements AppSecrets.Store {
        final VaultStore actual;
        volatile ReadGate gate;
        GatedStore(File record) { actual = new VaultStore(record); }
        ReadGate arm(int readNumber) { ReadGate value = new ReadGate(readNumber); gate = value; return value; }
        @Override public byte[] read() throws IOException {
            ReadGate pending = gate;
            if (pending != null && pending.remaining.decrementAndGet() == 0 && pending.used.compareAndSet(false, true)) {
                pending.entered.countDown();
                try { if (!pending.released.await(20, TimeUnit.SECONDS)) throw new IOException("Fixture read barrier expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
            return actual.read();
        }
        @Override public byte[] quickMaterial() throws IOException { return actual.quickMaterial(); }
        @Override public void commit(byte[] bytes, byte[] wrapper, AppSecrets.EffectGuard guard) throws IOException, VaultException {
            actual.commit(bytes, wrapper, guard);
        }
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static boolean keyResident(AppSecrets vault) throws Exception {
        // Boolean residency only, never reads/clones/returns the material. Avoid triggering lazy expiry.
        synchronized (vault.session) { return field(vault.session, "key") != null; }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + 8000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
        assertTrue("Expected actual Activity callback was not delivered", condition.getAsBoolean());
    }

    private static final class Fixture implements Application.ActivityLifecycleCallbacks {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context target = instrumentation.getTargetContext();
        final Application application = (Application) target.getApplicationContext();
        final String id = UUID.randomUUID().toString(), preferencesName = "secrets_lifecycle_" + id;
        final File directory = new File(target.getCacheDir(), "ctos-lifecycle-" + id);
        final List<AppSecretsBridge> bridges = new ArrayList<>();
        final boolean pure;
        final AtomicInteger resumes = new AtomicInteger(), pauses = new AtomicInteger(), stops = new AtomicInteger();
        volatile Activity host;
        volatile AppSecretsBridge bridge;
        volatile Throwable callbackFailure;
        SharedPreferences preferences;
        GatedStore store;
        AppSecrets vault;
        VaultExportJournal journal;
        boolean registered, directoryOwned;
        int originalSecure;
        Fixture(boolean pure) { this.pure = pure; }
        void initialize() throws Exception {
            directoryOwned = directory.mkdir(); assertTrue("Fixture path must be newly owned", directoryOwned);
            System.out.println("Lifecycle fixture private_path=" + directory.getAbsolutePath() + " preferences=" + preferencesName);
            JSONObject marker = new JSONObject(); marker.put("fixture", "AppSecretsLifecycleTest"); marker.put("id", id);
            marker.put("preferences", preferencesName); marker.put("created", System.currentTimeMillis()); marker.put("pureTotp", pure);
            File owner = new File(directory, "fixture-owner.json"); assertTrue(owner.createNewFile());
            try (FileOutputStream output = new FileOutputStream(owner)) {
                output.write(marker.toString().getBytes(StandardCharsets.UTF_8)); output.flush(); output.getFD().sync();
            }
            File preferencesFile = new File(new File(target.getApplicationInfo().dataDir, "shared_prefs"), preferencesName + ".xml");
            assertFalse("Never adopt existing preferences", preferencesFile.exists());
            assertFalse("Never adopt existing preference recovery", new File(preferencesFile.getPath() + ".bak").exists());
            preferences = target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            store = new GatedStore(new File(directory, "vault.bin")); store.commit(pure ? pureCipher : cipher, null, () -> { });
            vault = new AppSecrets(store, SystemClock::elapsedRealtime);
            if (!pure) vault.attachSshValidator(AppSecretsLifecycleTest::syntheticValidator);
            journal = new VaultExportJournal(directory);
            instrumentation.runOnMainSync(() -> { application.registerActivityLifecycleCallbacks(this); registered = true; });
            Activity launched = instrumentation.startActivitySync(new Intent(target, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(() -> host == launched && resumes.get() > 0); assertCallbacks();
            assertTrue(vault.session.foreground());
        }
        void assertCallbacks() { if (callbackFailure != null) throw new AssertionError("Scoped lifecycle callback failed", callbackFailure); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (!(activity instanceof MainActivity)) return;
            try {
                if (host != null && host != activity) throw new IllegalStateException("Activity recreation is outside this fixture");
                host = activity; originalSecure = activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE;
                bridge = new AppSecretsBridge(activity, vault, preferences, journal); bridges.add(bridge);
            } catch (Throwable failure) { callbackFailure = failure; }
        }
        @Override public void onActivityResumed(Activity activity) {
            if (activity == host) { try { bridge.onResume(); } catch (Throwable failure) { callbackFailure = failure; } resumes.incrementAndGet(); }
        }
        @Override public void onActivityPaused(Activity activity) {
            if (activity == host) { try { bridge.onPause(); } catch (Throwable failure) { callbackFailure = failure; } pauses.incrementAndGet(); }
        }
        @Override public void onActivityStopped(Activity activity) {
            if (activity == host) { try { bridge.onStop(); } catch (Throwable failure) { callbackFailure = failure; } stops.incrementAndGet(); }
        }
        @Override public void onActivityDestroyed(Activity activity) {
            if (activity == host && bridge != null) {
                try { bridge.close(); } catch (Throwable failure) { callbackFailure = failure; }
            }
        }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        Receipt call(String method, Map<String, Object> args) {
            Receipt result = new Receipt();
            instrumentation.runOnMainSync(() -> assertTrue(bridge.handle(new MethodCall(method, args), result)));
            return result;
        }
        void settings(int seconds) throws Exception { call("secrets.settings", arguments("backgroundTimeoutSeconds", seconds)).success(); }
        void unlock() throws Exception { call("secrets.unlock", arguments("requestId", UUID.randomUUID().toString(), "password", PASSWORD)).success(); }
        void home() throws Exception {
            int previous = stops.get();
            target.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> stops.get() > previous); assertCallbacks(); assertFalse(vault.session.foreground());
        }
        void backToHost() throws Exception {
            int previous = resumes.get();
            target.startActivity(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            await(() -> resumes.get() > previous); assertCallbacks(); assertTrue(vault.session.foreground());
        }
        ExecutorService executor(AppSecretsBridge owner) throws Exception { return (ExecutorService) field(owner, "worker"); }
        void drain() throws Exception {
            CountDownLatch marker = new CountDownLatch(1); executor(bridge).execute(marker::countDown);
            assertTrue(marker.await(25, TimeUnit.SECONDS)); instrumentation.waitForIdleSync(); assertCallbacks();
        }
        void replaceBridge(AppSecrets core) {
            instrumentation.runOnMainSync(() -> { bridge = new AppSecretsBridge(host, core, preferences, journal); bridges.add(bridge); });
        }
        void close() throws Exception {
            if (!directoryOwned) return; // Failed mkdir never authorizes deletion of a pre-existing path.
            Throwable[] failure = {null};
            boolean workersFinished = true;
            if (store != null && store.gate != null) store.gate.released.countDown();
            instrumentation.runOnMainSync(() -> {
                try {
                    if (registered) { application.unregisterActivityLifecycleCallbacks(this); registered = false; }
                } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
                for (AppSecretsBridge owner : bridges) {
                    try { owner.close(); } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
                }
            });
            for (AppSecretsBridge owner : bridges) {
                try { assertTrue("Scoped worker still owns fixture data", executor(owner).awaitTermination(25, TimeUnit.SECONDS)); }
                catch (Throwable problem) { workersFinished = false; failure[0] = append(failure[0], problem); }
            }
            instrumentation.waitForIdleSync();
            if (vault != null) vault.lock();
            if (host != null) instrumentation.runOnMainSync(() -> {
                try {
                    Field root = MainActivity.class.getDeclaredField("root"); root.setAccessible(true);
                    System.out.println("Lifecycle normal-startup metadata root_active=" + root.getBoolean(host));
                } catch (ReflectiveOperationException ignored) { System.out.println("Lifecycle normal-startup metadata root_active=unobserved"); }
                try { host.getWindow().setFlags(originalSecure, WindowManager.LayoutParams.FLAG_SECURE); }
                catch (Throwable problem) { failure[0] = append(failure[0], problem); }
                finally {
                    try { host.finish(); } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
                }
            });
            if (host != null) {
                try { await(host::isDestroyed); } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
            }
            // Do not remove data still owned by a worker. The exact marker/path remain for recovery.
            if (!workersFinished) throw new AssertionError("Scoped fixture worker cleanup incomplete; retained " + directory, failure[0]);
            if (preferences != null) {
                try {
                    if (!preferences.edit().clear().commit()) failure[0] = append(failure[0], new IOException("Own preferences clear failed"));
                } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
                try {
                    if (!target.deleteSharedPreferences(preferencesName)) failure[0] = append(failure[0], new IOException("Own preferences delete failed"));
                } catch (Throwable problem) { failure[0] = append(failure[0], problem); }
            }
            boolean filesRemoved = true;
            for (String name : new String[]{"vault.bin", "vault.bin.new", "vault.bin.bak", "vault-export-journal.json", "vault-export-journal.json.new", "vault-export-journal.json.bak"}) {
                File owned = new File(directory, name);
                if (owned.exists() && !owned.delete()) { filesRemoved = false; failure[0] = append(failure[0], new IOException("Own fixture file remained: " + name)); }
            }
            File marker = new File(directory, "fixture-owner.json");
            if (filesRemoved && failure[0] == null) {
                if (marker.exists() && !marker.delete()) failure[0] = append(failure[0], new IOException("Own fixture marker remained"));
                if (directory.exists() && !directory.delete()) failure[0] = append(failure[0], new IOException("Own fixture directory remained"));
            }
            if (failure[0] != null) throw new AssertionError("Scoped fixture cleanup incomplete; retained marker path " + directory, failure[0]);
        }
    }
    private static Throwable append(Throwable previous, Throwable next) { if (previous == null) return next; previous.addSuppressed(next); return previous; }
    private static Map<String, Object> arguments(Object... fields) {
        Map<String, Object> args = new HashMap<>();
        for (int index = 0; index < fields.length; index += 2) args.put((String) fields[index], fields[index + 1]);
        return args;
    }
    @Before public void setup() throws Exception {
        fixture = new Fixture(testName.getMethodName().equals("zeroTimeoutWipesRealPreviewButPreservesNewLockedEpochRequest"));
        fixture.initialize();
    }
    @After public void cleanup() throws Exception { if (fixture != null) fixture.close(); }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked call) throws Exception {
        try { call.run(); fail("A stale/background operation must be rejected"); }
        catch (VaultException expected) { assertTrue(VaultCollections.set("VAULT_LOCKED", "VAULT_CHANGED", "VAULT_OPERATION", "VAULT_FOREGROUND_REQUIRED").contains(expected.code)); }
    }
    private static void bothConsumers(AppSecrets vault) throws Exception {
        TotpVaultAdapter totp = new TotpVaultAdapter(vault); SshVaultAdapter ssh = ssh(vault);
        assertEquals("287082", vault.totpCode(totp.request("totp"), "totp", 59));
        AtomicInteger received = new AtomicInteger();
        ssh.useCredential(ssh.credentialRequest("ssh", TARGET, CONNECTION), "ssh", TARGET, CONNECTION,
                bytes -> { assertArrayEquals(CREDENTIAL, bytes); received.incrementAndGet(); });
        assertEquals(1, received.get());
    }

    @Test public void actualHomeGraceRetainsTheSameSessionForTwoTypedConsumers() throws Exception {
        Fixture f = fixture; f.settings(10); f.unlock(); bothConsumers(f.vault);
        long epoch = f.vault.session.epoch();
        f.home(); assertTrue(keyResident(f.vault));
        rejected(() -> new TotpVaultAdapter(f.vault).request("totp"));
        rejected(() -> ssh(f.vault).credentialRequest("ssh", TARGET, CONNECTION));
        SystemClock.sleep(150); f.backToHost();
        assertEquals(epoch, f.vault.session.epoch()); bothConsumers(f.vault);
        f.settings(0); f.home(); assertFalse(keyResident(f.vault)); f.backToHost();
        assertFalse(f.vault.session.unlocked()); rejected(() -> new TotpVaultAdapter(f.vault).request("totp"));
    }
    @Test public void actualHomeExpiryUsesHandlerAndTimeoutSettingsNeverReviveKeys() throws Exception {
        Fixture f = fixture; f.settings(1); f.unlock(); f.home();
        SystemClock.sleep(1300); f.instrumentation.waitForIdleSync();
        assertFalse("Actual background Handler must wipe without a lazy query", keyResident(f.vault));
        f.settings(30); assertFalse(keyResident(f.vault)); f.backToHost(); assertFalse(f.vault.session.unlocked());
        f.unlock(); f.home(); SystemClock.sleep(1200); f.settings(1);
        assertFalse(keyResident(f.vault)); f.settings(60); assertFalse(keyResident(f.vault)); f.backToHost();
        assertFalse(f.vault.session.unlocked());
    }
    @Test public void manualLockCancelsLateAuthenticatedUnlockExactlyOnceWithoutWriting() throws Exception {
        Fixture f = fixture; f.unlock();
        VaultOperation totp = new TotpVaultAdapter(f.vault).request("totp");
        VaultOperation credential = ssh(f.vault).credentialRequest("ssh", TARGET, CONNECTION);
        f.call("secrets.lock", arguments()).success();
        rejected(() -> f.vault.totpCode(totp, "totp", 59));
        rejected(() -> ssh(f.vault).useCredential(credential, "ssh", TARGET, CONNECTION, bytes -> fail("Late credential")));
        byte[] before = f.store.actual.read(); ReadGate gate = f.store.arm(1);
        Receipt late = f.call("secrets.unlock", arguments("requestId", UUID.randomUUID().toString(), "password", PASSWORD));
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        Receipt lock = f.call("secrets.lock", arguments()); late.await(); assertEquals("VAULT_CANCELLED", late.error);
        gate.released.countDown(); lock.success(); f.drain();
        assertEquals(1, late.calls.get()); assertFalse(f.vault.session.unlocked());
        assertArrayEquals(before, f.store.actual.read()); VaultCodec.wipe(before);
    }
    @Test public void closedBridgeCannotAffectNewContextOrResubmitLatePickerWork() throws Exception {
        Fixture f = fixture; f.settings(5); f.unlock(); f.home();
        AppSecretsBridge old = f.bridge; Runnable oldTimer = (Runnable) field(old, "backgroundLock"); assertNotNull(oldTimer);
        ExecutorService oldExecutor = f.executor(old);
        f.instrumentation.runOnMainSync(() -> { old.close(); old.close(); });
        assertFalse(f.vault.session.foreground()); assertNull(field(old, "backgroundLock"));
        f.replaceBridge(f.vault); f.backToHost();
        long epoch = f.vault.session.epoch();
        SystemClock.sleep(5100); f.instrumentation.waitForIdleSync();
        f.instrumentation.runOnMainSync(() -> { old.onStop(); oldTimer.run(); old.close(); }); // Explicit direct stale-call regression.
        assertEquals(epoch, f.vault.session.epoch()); assertTrue(f.vault.session.foreground());
        assertTrue(oldExecutor.awaitTermination(5, TimeUnit.SECONDS));
        f.unlock(); bothConsumers(f.vault);
        AppSecretsBridge closing = f.bridge; ExecutorService closingExecutor = f.executor(closing);
        Receipt exported = new Receipt(); CountDownLatch workerFence = new CountDownLatch(1);
        // Deliberately pause the real main queue only to order this regression, not as a performance test.
        // export completes on its own worker and posts openPicker; close precedes that queued UI runnable.
        f.instrumentation.runOnMainSync(() -> {
            assertTrue(closing.handle(new MethodCall("secrets.exportPreview", arguments(
                    "requestId", UUID.randomUUID().toString(), "password", PASSWORD, "fileName", "fixture.ctvault")), exported));
            closingExecutor.execute(workerFence::countDown);
            try {
                assertTrue("Export worker fence timed out", workerFence.await(15, TimeUnit.SECONDS));
                Object request = field(closing, "active");
                assertNotNull(request); assertNotNull(field(request, "output"));
                assertEquals(-1, field(request, "uiCode")); assertEquals(1L, exported.done.getCount());
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            catch (ReflectiveOperationException inaccessible) { throw new AssertionError(inaccessible); }
            finally { closing.close(); }
        });
        assertTrue(closingExecutor.awaitTermination(20, TimeUnit.SECONDS)); f.instrumentation.waitForIdleSync(); exported.await();
        exported.success(); Map<?, ?> terminal = (Map<?, ?>) exported.value;
        assertEquals("cancelled", terminal.get("status")); assertEquals("not_prepared", terminal.get("phase"));
        assertEquals("VAULT_CANCELLED", terminal.get("errorCode")); assertTrue(((List<?>) terminal.get("remaining")).isEmpty());
        assertFalse(f.vault.session.foreground()); assertFalse(f.vault.session.unlocked());
        assertNull(f.journal.load()); assertArrayEquals(cipher, f.store.actual.read());
    }
    @Test public void freshScopedCoreRestoresOnlyOwnTimeoutSetting() throws Exception {
        Fixture f = fixture; f.settings(7); f.unlock(); AppSecretsBridge previous = f.bridge;
        f.instrumentation.runOnMainSync(previous::close); assertFalse(f.vault.session.foreground());
        assertTrue(f.executor(previous).awaitTermination(10, TimeUnit.SECONDS));
        AppSecrets fresh = new AppSecrets(f.store, SystemClock::elapsedRealtime); fresh.attachSshValidator(AppSecretsLifecycleTest::syntheticValidator);
        f.replaceBridge(fresh);
        f.instrumentation.runOnMainSync(() -> f.bridge.onResume()); // Direct reconstruction; not process-death/system callback evidence.
        assertEquals(7, fresh.session.timeout()); assertTrue(fresh.session.foreground()); assertFalse(fresh.session.unlocked());
        Receipt status = f.call("secrets.status", arguments()); status.success();
        assertEquals("locked", ((Map<?, ?>) status.value).get("state")); assertEquals(7, ((Map<?, ?>) status.value).get("backgroundTimeoutSeconds"));
        rejected(() -> new TotpVaultAdapter(fresh).request("totp")); rejected(() -> ssh(fresh).credentialRequest("ssh", TARGET, CONNECTION));
        f.unlock(); bothConsumers(fresh); assertFalse(f.vault.session.unlocked());
    }
    @Test public void zeroTimeoutWipesRealPreviewButPreservesNewLockedEpochRequest() throws Exception {
        Fixture f = fixture; assertTrue(f.pure); f.settings(0); f.unlock();
        VaultOperation stale = new TotpVaultAdapter(f.vault).request("totp");
        char[] sourcePassword = PASSWORD.toCharArray(), targetPassword = PASSWORD.toCharArray();
        AppSecrets.ImportPreview preview;
        try { preview = f.vault.preview(pureCipher, sourcePassword, targetPassword, "pure-fixture-preview", () -> { }); }
        finally { Arrays.fill(sourcePassword, '\0'); Arrays.fill(targetPassword, '\0'); }
        assertFalse(allZero(preview.source.key)); assertFalse(allZero(preview.source.plaintext));
        f.home(); f.drain();
        assertFalse(keyResident(f.vault));
        synchronized (f.vault) {
            assertNull(field(f.vault, "preview")); assertTrue(((Map<?, ?>) field(f.vault, "operations")).isEmpty());
        }
        assertTrue(allZero(preview.source.key)); assertTrue(allZero(preview.source.plaintext));
        assertTrue(allZero(preview.source.manifest.entries.get(0).secret));
        rejected(() -> f.vault.totpCode(stale, "totp", 59)); f.backToHost();

        // Already locked: a new authenticated request has no prior session key to expire on HOME.
        long epoch = f.vault.session.epoch(); ReadGate gate = f.store.arm(1);
        Receipt fresh = f.call("secrets.unlock", arguments("requestId", UUID.randomUUID().toString(), "password", PASSWORD));
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS)); f.home();
        assertEquals(epoch, f.vault.session.epoch()); assertEquals(1L, fresh.done.getCount());
        Object pending = field(f.bridge, "active"); assertNotNull(pending);
        assertFalse(((VaultRequestPhase) field(pending, "phase")).cancelled());
        f.backToHost(); gate.released.countDown(); fresh.success(); f.drain();
        assertTrue(f.vault.session.unlocked());
        assertEquals("287082", f.vault.totpCode(new TotpVaultAdapter(f.vault).request("totp"), "totp", 59));
        assertArrayEquals(pureCipher, f.store.actual.read());
    }
    private static boolean allZero(byte[] value) {
        for (byte element : value) if (element != 0) return false;
        return true;
    }
}
