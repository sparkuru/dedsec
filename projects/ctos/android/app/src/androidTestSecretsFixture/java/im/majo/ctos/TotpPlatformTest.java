package im.majo.ctos;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.StandardMethodCodec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Independent fixture UID only. All ciphertext/seeds are synthetic, no production Store or aliases.
 * Bitmap input is an owned synthetic file URI, not evidence of SAF grants or camera capture.
 * EventChannel uses the real codec over an instrumented loopback messenger, not a Dart subscription.
 * The one clipboard test explicitly replaces the clipboard with its own synthetic code; no prior
 * clipboard read, restoration, clearing, listener or timer exists. */
public final class TotpPlatformTest {
    private static final String PACKAGE = "im.majo.ctos.secretsfixture";
    private static final String PASSWORD = "synthetic-totp-platform-password";
    private static final String BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI_ONE = "otpauth://totp/Fixture%3Aalice?issuer=Fixture&secret=" + BASE32;
    private static final String URI_TWO = "otpauth://totp/Fixture%3Abob?issuer=Fixture&secret=MFRGGZDFMZTWQ2LK";
    private static final String ORIGINAL = "fixture-original";
    private static final AppSecrets.EffectGuard OK = () -> { };
    private static byte[] ciphertext;
    private Fixture fixture;

    private static void assertIndependentUid() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        assertEquals("Only the opt-in fixture package may run", PACKAGE, target.getPackageName());
        assertEquals(PACKAGE + ".test", instrumentation.getContext().getPackageName());
        assertEquals(target.getApplicationInfo().uid, android.os.Process.myUid());
        assertNull(target.getPackageManager().getPackageInfo(PACKAGE, 0).sharedUserId);
        assertNull(target.getPackageManager().getPackageInfo(PACKAGE + ".test", 0).sharedUserId);
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertNotEquals("Fixture and production must have distinct UIDs",
                target.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid, android.os.Process.myUid());
    }

    @BeforeClass public static void prepareCiphertext() throws Exception {
        assertIndependentUid();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Only the explicitly installed independent fixture package is allowed", PACKAGE, target.getPackageName());
        int productionUid = target.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid;
        assertNotEquals("Fixture and production must have distinct UIDs", productionUid, target.getApplicationInfo().uid);
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII), salt = VaultEnvelope.random(16);
        char[] password = PASSWORD.toCharArray(); byte[] key = null, plaintext = null;
        try (VaultManifest manifest = new VaultManifest(VaultCollections.list(new VaultManifest.Entry(
                VaultManifest.EntryType.TOTP, ORIGINAL, "Fixture:original", "Original", "SHA1", 6, 30, seed)), VaultCollections.list())) {
            key = VaultEnvelope.derive(password, salt); plaintext = manifest.encode();
            ciphertext = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plaintext).encode();
        } finally { Arrays.fill(password, '\0'); VaultCodec.wipe(seed); VaultCodec.wipe(salt); VaultCodec.wipe(key); VaultCodec.wipe(plaintext); }
        System.out.println("TOTP fixture independent_uid=true synthetic_data_only=true root_disabled=true");
    }
    @AfterClass public static void wipeCiphertext() { VaultCodec.wipe(ciphertext); ciphertext = null; }
    @Before public void setup() throws Exception { fixture = new Fixture(); fixture.open(); }
    @After public void cleanup() throws Exception { if (fixture != null) fixture.close(); }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { assertTrue(value instanceof Map); return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Object value) { assertTrue(value instanceof List); return (List<Map<String, Object>>) value; }
    private static String request() { return UUID.randomUUID().toString(); }
    private static ByteBuffer incoming(ByteBuffer buffer) { ByteBuffer readable = buffer.duplicate(); readable.flip(); return readable; }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void await(String message, BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }

    private static final class Receipt implements MethodChannel.Result {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger completions = new AtomicInteger();
        volatile Object value;
        volatile String error;
        volatile boolean main = true;
        @Override public void success(Object result) {
            value = StandardMethodCodec.INSTANCE.decodeEnvelope(incoming(StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(result))); complete();
        }
        @Override public void error(String code, String message, Object details) { error = code; complete(); }
        @Override public void notImplemented() { error = "NOT_IMPLEMENTED"; complete(); }
        private void complete() { main &= Looper.myLooper() == Looper.getMainLooper(); completions.incrementAndGet(); done.countDown(); }
        void await() throws Exception { assertTrue("Host request timed out", done.await(75, TimeUnit.SECONDS)); assertTrue(main); assertEquals(1, completions.get()); }
        Object success() throws Exception { await(); assertNull("Unexpected host error: " + error, error); return value; }
        void rejects(String code) throws Exception { await(); assertEquals(code, error); assertNull(value); }
    }
    private static final class ReadGate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    }
    private static final class MemoryStore implements AppSecrets.Store {
        volatile byte[] bytes;
        volatile ReadGate gate;
        int commits;
        MemoryStore(byte[] initial) { bytes = initial == null ? null : initial.clone(); }
        @Override public byte[] read() throws IOException {
            ReadGate blocked = gate;
            if (blocked != null) {
                gate = null; blocked.entered.countDown();
                try { if (!blocked.release.await(30, TimeUnit.SECONDS)) throw new IOException("Owned fixture barrier expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
            byte[] current = bytes; return current == null ? null : current.clone();
        }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] next, byte[] wrapper, AppSecrets.EffectGuard guard) throws VaultException {
            guard.beforeCommit(); VaultCodec.wipe(bytes); bytes = next.clone(); commits++;
        }
        void close() { if (gate != null) gate.release.countDown(); VaultCodec.wipe(bytes); bytes = null; }
    }
    private static final class Events implements BinaryMessenger {
        final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        final Map<String, BinaryMessageHandler> handlers = new HashMap<>();
        @Override public void send(String channel, ByteBuffer message) { send(channel, message, null); }
        @Override public void send(String channel, ByteBuffer message, BinaryReply reply) {
            assertEquals("ctos/secrets", channel);
            Map<String, Object> event = map(StandardMethodCodec.INSTANCE.decodeEnvelope(incoming(message)));
            assertEquals(VaultCollections.set("epoch", "unlocked", "foreground"), event.keySet());
            assertTrue(event.get("epoch") instanceof Number); assertTrue(event.get("unlocked") instanceof Boolean); assertTrue(event.get("foreground") instanceof Boolean);
            received.add(event); if (reply != null) reply.reply(null);
        }
        @Override public void setMessageHandler(String channel, BinaryMessageHandler handler) { if (handler == null) handlers.remove(channel); else handlers.put(channel, handler); }
        void listen() {
            BinaryMessageHandler handler = handlers.get("ctos/secrets"); assertNotNull(handler);
            handler.onMessage(incoming(StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("listen", null))),
                    reply -> assertNull(StandardMethodCodec.INSTANCE.decodeEnvelope(incoming(reply))));
        }
        boolean matches(long epoch, boolean unlocked) {
            return received.stream().anyMatch(value -> ((Number) value.get("epoch")).longValue() == epoch && Boolean.valueOf(unlocked).equals(value.get("unlocked")));
        }
    }
    private static final class Fixture implements Application.ActivityLifecycleCallbacks {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context target = instrumentation.getTargetContext();
        final Application application = (Application) target.getApplicationContext();
        final String preferencesName = "totp_platform_" + UUID.randomUUID();
        final File directory = new File(target.getCacheDir(), "totp-platform-" + UUID.randomUUID());
        final List<File> files = new ArrayList<>();
        final MemoryStore store = new MemoryStore(ciphertext);
        final AppSecrets vault = new AppSecrets(store, SystemClock::elapsedRealtime);
        final Events events = new Events();
        SharedPreferences preferences;
        volatile Activity host;
        volatile AppSecretsBridge bridge;
        volatile Throwable callbackFailure;
        boolean directoryOwned, registered;
        int originalSecure;
        void open() throws Exception {
            assertIndependentUid();
            assertEquals(PACKAGE, target.getPackageName());
            assertFalse(target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
            directoryOwned = directory.mkdir(); assertTrue("Do not adopt an existing path", directoryOwned);
            File preferencesFile = new File(new File(target.getApplicationInfo().dataDir, "shared_prefs"), preferencesName + ".xml");
            assertFalse(preferencesFile.exists()); assertFalse(new File(preferencesFile + ".bak").exists());
            preferences = target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            instrumentation.runOnMainSync(() -> { application.registerActivityLifecycleCallbacks(this); registered = true; });
            Activity launched = instrumentation.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await("Own Main lifecycle did not resume", () -> host == launched && vault.session.foreground());
            assertCallbacks();
            call("secrets.unlock", args("requestId", request(), "password", PASSWORD)).success();
            long epoch = vault.session.epoch(); await("No actual EventChannel unlock envelope", () -> events.matches(epoch, true));
        }
        void assertCallbacks() { if (callbackFailure != null) throw new AssertionError("Owned bridge lifecycle failed", callbackFailure); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (!(activity instanceof MainActivity)) return;
            try {
                if (host != null) throw new IllegalStateException("Unexpected Activity recreation");
                host = activity; originalSecure = activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE;
                bridge = new AppSecretsBridge(activity, vault, preferences, new VaultExportJournal(directory));
                bridge.attachEvents(events); events.listen();
            } catch (Throwable failure) { callbackFailure = failure; }
        }
        @Override public void onActivityResumed(Activity activity) { if (activity == host) { try { bridge.onResume(); } catch (Throwable failure) { callbackFailure = failure; } } }
        @Override public void onActivityPaused(Activity activity) { if (activity == host) { try { bridge.onPause(); } catch (Throwable failure) { callbackFailure = failure; } } }
        @Override public void onActivityStopped(Activity activity) { if (activity == host) { try { bridge.onStop(); } catch (Throwable failure) { callbackFailure = failure; } } }
        @Override public void onActivityDestroyed(Activity activity) { if (activity == host && bridge != null) bridge.close(); }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        Receipt call(String name, Map<String, Object> arguments) {
            Receipt receipt = new Receipt();
            MethodCall call = StandardMethodCodec.INSTANCE.decodeMethodCall(incoming(StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall(name, arguments))));
            instrumentation.runOnMainSync(() -> assertTrue(bridge.handle(call, receipt))); return receipt;
        }
        Map<String, Object> list() throws Exception {
            for (int i = 0; i < 3; i++) {
                Receipt result = call("secrets.totpList", args("requestId", "read_" + request())); result.await();
                if ("TOTP_TIME_CHANGED".equals(result.error)) continue;
                assertNull(result.error); return map(result.value);
            }
            throw new AssertionError("Wall clock repeatedly changed while reading");
        }
        Map<String, Object> preview(String uri) throws Exception { return map(call("secrets.totpManualPreview", args("requestId", request(), "uri", uri)).success()); }
        String add(String uri) throws Exception {
            Map<String, Object> preview = preview(uri), candidate = rows(preview.get("candidates")).get(0);
            return (String) map(call("secrets.totpCommit", args("requestId", request(), "previewId", preview.get("previewId"), "candidateId", candidate.get("candidateId"))).success()).get("entryId");
        }
        File file(String name) throws IOException { File file = new File(directory, name); assertTrue(file.createNewFile()); files.add(file); return file; }
        void drain() throws Exception {
            CountDownLatch done = new CountDownLatch(1); ((ExecutorService) field(bridge, "worker")).execute(done::countDown);
            assertTrue(done.await(75, TimeUnit.SECONDS)); instrumentation.waitForIdleSync(); assertCallbacks();
        }
        void close() throws Exception {
            if (store.gate != null) store.gate.release.countDown();
            instrumentation.runOnMainSync(() -> {
                if (registered) { application.unregisterActivityLifecycleCallbacks(this); registered = false; }
                if (bridge != null) bridge.close();
            });
            if (bridge != null) assertTrue("Worker still owns test resources", ((ExecutorService) field(bridge, "worker")).awaitTermination(75, TimeUnit.SECONDS));
            vault.lock(); store.close();
            instrumentation.runOnMainSync(() -> { if (host != null) { host.getWindow().setFlags(originalSecure, WindowManager.LayoutParams.FLAG_SECURE); host.finish(); } });
            instrumentation.waitForIdleSync();
            if (directoryOwned) {
                for (File file : files) assertTrue("Owned synthetic image cleanup failed", !file.exists() || file.delete());
                assertTrue("Unexpected fixture file retained", directory.list() != null && directory.list().length == 0);
                assertTrue(directory.delete());
            }
            assertTrue(target.deleteSharedPreferences(preferencesName));
            assertCallbacks();
        }
    }

    @Test public void actualAndroidBitmapOfflineMultiQrAndInputFailuresStayBounded() throws Exception {
        File image = fixture.file("synthetic-original-qr.png");
        Bitmap bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[1200 * 400]; Arrays.fill(pixels, 0xffffffff);
        try {
            draw(pixels, 1200, 0, URI_ONE); draw(pixels, 1200, 400, URI_TWO); draw(pixels, 1200, 800, "otpauth-migration://synthetic-unsupported");
            bitmap.setPixels(pixels, 0, 1200, 0, 0, 1200, 400);
            try (FileOutputStream out = new FileOutputStream(image)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        } finally { Arrays.fill(pixels, 0); bitmap.eraseColor(0); bitmap.recycle(); }
        byte[] original = digest(image);
        try (TotpQrImages.Decoded decoded = TotpQrImages.decode(fixture.target.getContentResolver(), Uri.fromFile(image), OK)) {
            assertEquals(2, decoded.inputs.size()); assertEquals(1, decoded.invalid);
            assertEquals(VaultCollections.set("Fixture:alice", "Fixture:bob"), new java.util.HashSet<>(Arrays.asList(decoded.inputs.get(0).identity, decoded.inputs.get(1).identity)));
        }
        assertArrayEquals("Selected synthetic source must stay unchanged", original, digest(image));
        expect("VAULT_CANCELLED", () -> TotpQrImages.decode(fixture.target.getContentResolver(), Uri.fromFile(image), () -> { throw new VaultException("VAULT_CANCELLED", "Owned cancellation"); }));
        File wide = fixture.file("synthetic-oversized.png"); Bitmap tooWide = Bitmap.createBitmap(8193, 1, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(wide)) { assertTrue(tooWide.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        finally { tooWide.recycle(); }
        expect("TOTP_LIMIT", () -> TotpQrImages.decode(fixture.target.getContentResolver(), Uri.fromFile(wide), OK));
        System.out.println("TOTP actual_android_bitmap multi_valid=2 unsupported=1 original_unchanged=true selected_owned_file_uri=true saf_grant_not_tested=true");
    }

    @Test public void typedBridgeUsesMetadataPreviewAndPreservesEntryIdentityThroughRenameDelete() throws Exception {
        byte[] before = fixture.store.read(); Map<String, Object> preview = fixture.preview(URI_ONE);
        assertArrayEquals(before, fixture.store.read());
        assertEquals(VaultCollections.set("previewId", "candidates", "invalidCodes"), preview.keySet());
        Map<String, Object> candidate = rows(preview.get("candidates")).get(0);
        assertEquals(VaultCollections.set("candidateId", "identity", "label", "algorithm", "digits", "period"), candidate.keySet());
        String added = (String) map(fixture.call("secrets.totpCommit", args("requestId", request(), "previewId", preview.get("previewId"), "candidateId", candidate.get("candidateId"))).success()).get("entryId");
        assertNotEquals(ORIGINAL, added); assertEquals(2, rows(fixture.list().get("entries")).size());
        fixture.call("secrets.totpRename", args("requestId", request(), "entryId", added, "label", "Renamed synthetic")).success();
        try (AppSecrets.Verified verified = AppSecrets.verify(fixture.store.read(), PASSWORD.toCharArray())) {
            VaultManifest.Entry changed = verified.manifest.entries.stream().filter(entry -> entry.id.equals(added)).findFirst().get();
            assertEquals("Fixture:alice", changed.identity); assertEquals("Renamed synthetic", changed.label);
            assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), changed.secret);
            assertEquals(2, verified.manifest.entries.size());
        }
        fixture.call("secrets.totpDelete", args("requestId", request(), "entryId", added)).success();
        List<Map<String, Object>> entries = rows(fixture.list().get("entries")); assertEquals(1, entries.size()); assertEquals(ORIGINAL, entries.get(0).get("entryId"));
        assertEquals(VaultCollections.set("entryId", "identity", "label", "code"), entries.get(0).keySet());
        fixture.call("secrets.totpDelete", args("requestId", "read_" + request(), "entryId", ORIGINAL)).rejects("PARAMETERS");
    }

    @Test public void cancelLockAndExpiredOwnedPreviewNeverChangeCiphertext() throws Exception {
        byte[] before = fixture.store.read(); Map<String, Object> cancelled = fixture.preview(URI_ONE);
        fixture.call("secrets.totpCancel", args("previewId", cancelled.get("previewId"))).success();
        commit(fixture, cancelled).rejects("VAULT_CHANGED"); assertArrayEquals(before, fixture.store.read());
        Map<String, Object> locked = fixture.preview(URI_ONE);
        fixture.call("secrets.lock", args()).success(); long epoch = fixture.vault.session.epoch();
        await("Missing locked EventChannel envelope", () -> fixture.events.matches(epoch, false));
        commit(fixture, locked).rejects("VAULT_CHANGED"); fixture.call("secrets.totpList", args("requestId", "read_" + request())).rejects("VAULT_LOCKED");
        fixture.call("secrets.unlock", args("requestId", request(), "password", PASSWORD)).success();
        Map<String, Object> expired = fixture.preview(URI_ONE); fixture.drain();
        Object previews = field(fixture.bridge, "totpPreviews"); Field deadline = previews.getClass().getDeclaredField("deadline"); deadline.setAccessible(true);
        synchronized (previews) { deadline.setLong(previews, SystemClock.elapsedRealtime() - 1); }
        commit(fixture, expired).rejects("VAULT_EXPIRED"); assertArrayEquals(before, fixture.store.read());
        System.out.println("TOTP preview cancel_lock_expiry_no_write=true forced_owned_deadline=true real_120s_wait=false");
    }

    @Test public void cancelledBlockedNativeReadCompletesOnceAndCannotPublishLateCodes() throws Exception {
        byte[] before = fixture.store.read(); ReadGate gate = new ReadGate(); fixture.store.gate = gate;
        String id = "read_" + request(); Receipt read = fixture.call("secrets.totpList", args("requestId", id));
        try {
            assertTrue("Fixture read did not enter the owned gate", gate.entered.await(15, TimeUnit.SECONDS));
            fixture.call("secrets.cancel", args("requestId", id)).success(); read.rejects("VAULT_CANCELLED");
        } finally { gate.release.countDown(); }
        fixture.drain(); assertEquals(1, read.completions.get()); assertNull(read.value); assertArrayEquals(before, fixture.store.read());
        assertEquals(1, rows(fixture.list().get("entries")).size());
    }

    @Test public void actualNonemptyEncryptedTotpRestoreAuthenticatesAndReplacesOnlyOwnedMemoryTarget() throws Exception {
        fixture.add(URI_TWO); fixture.drain();
        char[] sourcePassword = PASSWORD.toCharArray(), targetPassword = "synthetic-distinct-target-password".toCharArray();
        byte[] backup = null, oldTarget = null, damaged = null;
        MemoryStore targetStore = new MemoryStore(null); AppSecrets target = new AppSecrets(targetStore, SystemClock::elapsedRealtime);
        try {
            backup = fixture.vault.export(sourcePassword, OK); target.session.foreground(true); target.create(targetPassword, targetPassword, OK);
            byte[] seed = "abcdefghijabcdefghij".getBytes(StandardCharsets.US_ASCII);
            try { target.saveTotp(target.beginTotpSave("target-only"), "target-only", "Fixture:target", "Target only", seed, OK); }
            finally { VaultCodec.wipe(seed); }
            oldTarget = targetStore.read(); final byte[] source = backup;
            expect("VAULT_AUTHENTICATION", () -> target.preview(source, targetPassword, targetPassword, request(), OK));
            expect("VAULT_AUTHENTICATION", () -> target.preview(source, sourcePassword, sourcePassword, request(), OK));
            damaged = backup.clone(); damaged[damaged.length - 1] ^= 1; final byte[] tampered = damaged;
            expect("VAULT_AUTHENTICATION", () -> target.preview(tampered, sourcePassword, targetPassword, request(), OK));
            assertArrayEquals(oldTarget, targetStore.read());
            AppSecrets.ImportPreview cancelled = target.preview(backup, sourcePassword, targetPassword, request(), OK); target.cancelPreview(cancelled.id);
            expect("VAULT_EXPIRED", () -> target.importCommit(cancelled.id, false, OK)); assertArrayEquals(oldTarget, targetStore.read());
            AppSecrets.ImportPreview review = target.preview(backup, sourcePassword, targetPassword, request(), OK); assertNull(review.reviewMap());
            target.importCommit(review.id, false, OK); assertFalse(target.session.unlocked()); assertNull(targetStore.quickMaterial());
            try (AppSecrets.Verified restored = AppSecrets.verify(targetStore.read(), sourcePassword); AppSecrets.Verified sourceView = AppSecrets.verify(backup, sourcePassword)) {
                assertEquals(2, restored.manifest.entries.size()); assertTrue(restored.manifest.files.isEmpty());
                assertArrayEquals(sourceView.plaintext, restored.plaintext); assertNotEquals(sourceView.envelope.id, restored.envelope.id);
                assertEquals(1, restored.envelope.generation); assertFalse(Arrays.equals(backup, targetStore.read()));
            }
            expect("VAULT_AUTHENTICATION", () -> target.unlock(targetPassword, OK)); target.unlock(sourcePassword, OK);
            assertEquals(2, rows(target.totpList(target.beginTotpList(), System.currentTimeMillis() / 1000).get("entries")).size());
            System.out.println("TOTP actual_android_cipher nonempty_restore_entries=2 fresh_local_identity=true source_password=true mixed_ssh_not_tested=true");
        } finally { target.lock(); targetStore.close(); Arrays.fill(sourcePassword, '\0'); Arrays.fill(targetPassword, '\0'); VaultCodec.wipe(backup); VaultCodec.wipe(oldTarget); VaultCodec.wipe(damaged); }
    }

    @Test public void actualBridgeWindowOwnersRetainProtectionThroughNestedReleaseAndBackground() throws Exception {
        String first = (String) map(fixture.call("secrets.protect", args("action", "acquire")).success()).get("owner");
        String second = (String) map(fixture.call("secrets.protect", args("action", "acquire")).success()).get("owner");
        fixture.call("secrets.protect", args("action", "release", "owner", first)).success(); assertSecure(fixture, true);
        fixture.instrumentation.runOnMainSync(() -> fixture.bridge.onPause());
        fixture.call("secrets.protect", args("action", "release", "owner", second)).success(); assertSecure(fixture, true);
        fixture.instrumentation.runOnMainSync(() -> fixture.bridge.onResume()); assertSecure(fixture, fixture.originalSecure != 0);
        System.out.println("TOTP real_window_flag owners_background=true injected_bridge_lifecycle=true actual_home=false screenshot_recording_recents_not_verified=true");
    }

    @Test public void explicitNativeCopyWritesOnlyTheOwnedSyntheticCurrentCodeWithSensitiveHint() throws Exception {
        org.junit.Assume.assumeTrue("Actual global clipboard copying requires separate explicit opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("ctosTotpClipboardCopy", "false")));
        long before = System.currentTimeMillis() / 1000;
        Map<String, Object> result = map(fixture.call("secrets.totpCopy", args("requestId", request(), "entryId", ORIGINAL)).success());
        assertEquals(Boolean.TRUE, result.get("copied")); long after = System.currentTimeMillis() / 1000;
        fixture.instrumentation.runOnMainSync(() -> {
            ClipboardManager manager = fixture.host.getSystemService(ClipboardManager.class); assertNotNull(manager);
            // This is the first clipboard read: the host has acknowledged our explicit synthetic write.
            android.content.ClipDescription description = manager.getPrimaryClipDescription();
            assertNotNull("ROM did not allow foreground own-clip description", description);
            assertEquals("ctOS code", description.getLabel().toString());
            ClipData own = manager.getPrimaryClip(); assertNotNull("ROM did not allow foreground own-clip readback", own);
            assertEquals("ctOS code", own.getDescription().getLabel().toString()); assertEquals(1, own.getItemCount());
            String actual = own.getItemAt(0).getText().toString();
            byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
            try {
                assertTrue("Readback differs from the known synthetic seed/current window",
                        actual.equals(AppSecrets.generateTotp(seed, before)) || actual.equals(AppSecrets.generateTotp(seed, after)));
            } catch (VaultException failure) { throw new AssertionError(failure); }
            finally { VaultCodec.wipe(seed); }
            assertNotNull(own.getDescription().getExtras()); assertTrue(own.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        });
        System.out.println("TOTP explicit_synthetic_copy=true own_postwrite_readback=true sensitive_hint=true prior_clipboard_read=false cleanup=false");
    }

    @Test public void cameraCapabilityProbeAndPrivateActivityDeclarationsDoNotClaimHardwareScanning() throws Exception {
        CameraManager camera = fixture.target.getSystemService(CameraManager.class); assertNotNull(camera);
        String[] ids = camera.getCameraIdList(); assertNotNull(ids);
        ActivityInfo scanner = fixture.target.getPackageManager().getActivityInfo(new android.content.ComponentName(fixture.target, TotpCameraActivity.class), 0);
        ActivityInfo reader = fixture.target.getPackageManager().getActivityInfo(new android.content.ComponentName(fixture.target, TotpImageActivity.class), 0);
        assertFalse(scanner.exported); assertFalse(reader.exported);
        boolean permission = fixture.target.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        System.out.println("TOTP camera_device_count=" + ids.length + " camera_permission_already_granted=" + permission
                + " hardware_scan=" + (ids.length == 0 ? "unsupported" : "not_exercised") + " permission_changes=false");
    }

    private static Receipt commit(Fixture fixture, Map<String, Object> preview) {
        return fixture.call("secrets.totpCommit", args("requestId", request(), "previewId", preview.get("previewId"), "candidateId", rows(preview.get("candidates")).get(0).get("candidateId")));
    }
    private static void assertSecure(Fixture fixture, boolean expected) {
        fixture.instrumentation.runOnMainSync(() -> assertEquals(expected, (fixture.host.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(String code, Checked operation) throws Exception {
        try { operation.run(); fail("Expected " + code); } catch (VaultException failure) { assertEquals(code, failure.code); }
    }
    private static void draw(int[] pixels, int width, int x, String text) throws Exception {
        BitMatrix qr = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400);
        for (int row = 0; row < 400; row++) for (int col = 0; col < 400; col++) pixels[row * width + x + col] = qr.get(col, row) ? 0xff000000 : 0xffffffff;
    }
    private static byte[] digest(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            try { while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
            finally { VaultCodec.wipe(buffer); }
        }
        return digest.digest();
    }
}
