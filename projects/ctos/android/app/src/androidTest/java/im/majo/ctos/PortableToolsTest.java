package im.majo.ctos;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class PortableToolsTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test(timeout = 20000) public void terminalFileOutputsAreExclusive() throws Exception {
        PortablePackages.Package mounted = new PortablePackages(context()).get("python");
        String code = "import os, tempfile\nfrom pathlib import Path\nfrom ctos_tools.cli import _write\n"
                + "with tempfile.TemporaryDirectory(dir='.') as directory:\n"
                + " p = Path(directory) / 'output'\n"
                + " _write(p, b'original')\n"
                + " try:\n  _write(p, b'replacement')\n  raise AssertionError('Output was replaced')\n"
                + " except FileExistsError:\n  pass\n"
                + " assert p.read_bytes() == b'original'\n"
                + " assert len(list(p.parent.iterdir())) == 1\n"
                + "print('exclusive output passed')\n";
        ProcessBuilder builder = new ProcessBuilder(mounted.tools.get("python3"), "-P", "-S", "-c", code)
                .directory(context().getFilesDir()).redirectErrorStream(true);
        builder.environment().putAll(mounted.environment);
        Process process = builder.start();
        try {
            assertTrue("File operation must finish", process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
            assertEquals(new String(output.toByteArray(), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally { process.destroyForcibly(); }
    }

    @Test(timeout = 45000) public void cryptoAndEncoderPreserveSourcesAndRejectTampering() throws Exception {
        File store = ToolFiles.store(context());
        File input = new File(store, UUID.randomUUID().toString().replace("-", "") + ".input");
        File encryptedInput = new File(store, UUID.randomUUID().toString().replace("-", "") + ".input");
        java.util.List<File> owned = new java.util.ArrayList<>(java.util.Arrays.asList(input, encryptedInput));
        byte[] original = new byte[]{0, 1, 2, (byte) 255, 10};
        Files.write(input.toPath(), original);
        PythonRunner runner = new PythonRunner(context(), new PortablePackages(context()));
        try {
            assertTrue(runner.claim("encrypt"));
            JSONObject encrypted = runner.execute("tools.crypto", parameters("operation", "encrypt", "file", input.getName(), "password", "test-only-secret"));
            assertEquals(encrypted.optString("stderr"), "completed", encrypted.getString("state"));
            File envelope = new File(store, encrypted.getJSONObject("data").getJSONObject("artifact").getString("token"));
            owned.add(envelope);
            byte[] ciphertext = Files.readAllBytes(envelope.toPath());
            Files.write(encryptedInput.toPath(), ciphertext);
            assertTrue(runner.claim("decrypt"));
            JSONObject decrypted = runner.execute("tools.crypto", parameters("operation", "decrypt", "file", encryptedInput.getName(), "password", "test-only-secret"));
            assertEquals(decrypted.optString("stderr"), "completed", decrypted.getString("state"));
            File output = new File(store, decrypted.getJSONObject("data").getJSONObject("artifact").getString("token"));
            owned.add(output);
            assertArrayEquals(original, Files.readAllBytes(output.toPath()));
            ciphertext[ciphertext.length - 1] ^= 1;
            Files.write(encryptedInput.toPath(), ciphertext);
            int before = store.list().length;
            assertTrue(runner.claim("tamper"));
            JSONObject rejected = runner.execute("tools.crypto", parameters("operation", "decrypt", "file", encryptedInput.getName(), "password", "test-only-secret"));
            assertEquals("failed", rejected.getString("state"));
            assertFalse(rejected.getString("stderr").contains("test-only-secret"));
            assertEquals(before, store.list().length);
            assertArrayEquals(original, Files.readAllBytes(input.toPath()));
            assertTrue(runner.claim("encode"));
            JSONObject encoded = runner.execute("tools.encoder", parameters("operation", "base64", "direction", "encode", "file", input.getName()));
            assertEquals(encoded.optString("stderr"), "completed", encoded.getString("state"));
            File converted = new File(store, encoded.getJSONObject("data").getJSONObject("artifact").getString("token"));
            owned.add(converted);
            assertArrayEquals(Base64.getEncoder().encode(original), Files.readAllBytes(converted.toPath()));
            assertTrue(runner.claim("path"));
            assertEquals("failed", runner.execute("tools.crypto", parameters("operation", "encrypt", "file", "../outside", "password", "s")).getString("state"));
        } finally { runner.close(); for (File file : owned) file.delete(); }
    }

    @Test(timeout = 60000) public void hftpRemainsVisibleInBackgroundAndStopsItsOwnedListener() throws Exception {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        Context context = context();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        File imported = new File(ToolFiles.library(context), "test-" + UUID.randomUUID() + ".bin");
        File uploaded = new File(imported.getParentFile(), "upload-" + UUID.randomUUID() + ".bin");
        Files.write(imported.toPath(), "shared".getBytes(StandardCharsets.UTF_8));
        int port;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = unused.getLocalPort(); }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                assertEquals("Grant notifications through the approved test setup", android.content.pm.PackageManager.PERMISSION_GRANTED,
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class).putExtra("host", "127.0.0.1").putExtra("port", port).putExtra("maxUploadMiB", 1)));
            JSONObject state = awaitState("running");
            boolean visible = false;
            for (android.service.notification.StatusBarNotification notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if (notification.getId() == 1602) visible = true;
            assertTrue("A visible service notification is required", visible);
            assertEquals(200, request(port, "/", null, null));
            assertEquals(200, request(port, "/?diagnostic-query-must-not-appear", "diagnostic-auth-must-not-appear", null));
            assertFalse(state.has("password"));
            assertFalse(state.has("user"));
            assertFalse("Ordinary HFTP must stay independent of Root", state.getBoolean("rootRelay"));
            assertFalse(state.getBoolean("relayReady"));
            assertEquals("1", state.getString("maxUploadMiB"));
            assertEquals(HftpConfig.DEFAULT_DIRECTORY, state.getString("directoryName"));
            String authorization = null;
            assertEquals(200, request(port, "/" + imported.getName(), authorization, null));
            assertEquals(413, declaredUpload(port, "/oversize.bin", 2 * 1024 * 1024));
            instrumentation.runOnMainSync(() -> assertTrue(activity.moveTaskToBack(true)));
            SystemClock.sleep(500);
            assertEquals("running", new JSONObject(HftpService.status()).getString("state"));
            assertEquals(200, request(port, "/" + imported.getName(), authorization, null));
            assertEquals(400, request(port, "/%2e%2e/secret", authorization, null));
            assertEquals(201, request(port, "/" + uploaded.getName(), authorization, "diagnostic-body-must-not-appear".getBytes(StandardCharsets.UTF_8)));
            assertEquals(409, request(port, "/" + uploaded.getName(), authorization, "replace".getBytes(StandardCharsets.UTF_8)));
            assertEquals("diagnostic-body-must-not-appear", new String(Files.readAllBytes(uploaded.toPath()), StandardCharsets.UTF_8));
            assertEquals("shared", new String(Files.readAllBytes(imported.toPath()), StandardCharsets.UTF_8));
            String diagnostics = awaitLog("-> 409");
            assertTrue(diagnostics.contains("Listening on 127.0.0.1:" + port));
            assertTrue(diagnostics.contains("upload complete:"));
            assertTrue(diagnostics.contains("download complete:"));
            assertFalse(diagnostics.contains("diagnostic-query-must-not-appear"));
            assertFalse(diagnostics.contains("diagnostic-auth-must-not-appear"));
            assertFalse(diagnostics.contains("diagnostic-body-must-not-appear"));
            assertEquals("running", new JSONObject(HftpService.clearLogs()).getString("state"));
            assertEquals(200, request(port, "/", null, null));
            awaitLog("-> 200");
            instrumentation.runOnMainSync(() -> {
                HftpBridge bridge = new HftpBridge(activity);
                try {
                    RecordedResult stopped = new RecordedResult();
                    assertTrue(bridge.handle(new io.flutter.plugin.common.MethodCall("hftpStop", null), stopped));
                    assertTrue(stopped.completed);
                    assertNull(stopped.error);
                    assertEquals("stopping", new JSONObject(HftpService.status()).optString("state"));
                    assertTrue("Cleanup must block a replacement session", HftpService.active());
                } catch (Exception error) { throw new AssertionError(error); }
                finally { bridge.close(); }
            });
            awaitState("stopped");
            assertEquals("", new JSONObject(HftpService.status()).getString("reason"));
            assertFalse(HftpService.active());
            assertFalse(new JSONObject(HftpService.status()).getBoolean("closing"));
            assertTrue(new JSONObject(HftpService.status()).getJSONArray("logs").toString().contains("HFTP stopped"));
            assertTrue(new JSONObject(HftpService.status()).getJSONArray("logs").toString().contains("-> 200"));
            assertEquals(0, new JSONObject(HftpService.clearLogs()).getJSONArray("logs").length());
            try { request(port, "/", authorization, null); fail("Owned listener must close"); }
            catch (java.io.IOException expected) { /* The stopped service no longer accepts connections. */ }
        } finally {
            context.stopService(new Intent(context, HftpService.class));
            instrumentation.runOnMainSync(activity::finish);
            imported.delete(); uploaded.delete();
        }
    }

    @Test(timeout = 45000) public void stopRequestFencesRealProcessExitBeforeAndroidDestroy() throws Exception {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        assertFalse("Do not interrupt a pending directory operation", HftpBridge.preparing());
        Context context = context();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        int port;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = unused.getLocalPort(); }
        try {
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class)
                    .putExtra("host", "127.0.0.1").putExtra("port", port).putExtra("maxUploadMiB", 1)));
            awaitState("running");
            DeferredStopContext deferred = new DeferredStopContext(context);
            instrumentation.runOnMainSync(() -> {
                HftpService.requestStop(deferred);
                HftpService.requestStop(deferred);
                HftpBridge bridge = new HftpBridge(activity);
                try {
                    RecordedResult duplicate = new RecordedResult();
                    bridge.handle(new io.flutter.plugin.common.MethodCall("hftpStart", null), duplicate);
                    assertEquals("HFTP", duplicate.error);
                } finally { bridge.close(); }
            });
            assertTrue(deferred.requested);
            assertEquals("stopping", new JSONObject(HftpService.status()).getString("state"));
            assertEquals("", new JSONObject(HftpService.status()).getString("reason"));
            assertTrue(new JSONObject(HftpService.status()).getBoolean("closing"));

            // Deliberately defer Android's destruction, then exit only this test's App child.
            java.lang.reflect.Field ownerField = HftpService.class.getDeclaredField("current");
            ownerField.setAccessible(true);
            Object owner = ownerField.get(null);
            assertNotNull(owner);
            java.lang.reflect.Field processField = HftpService.class.getDeclaredField("process");
            processField.setAccessible(true);
            Process owned = (Process) processField.get(owner);
            assertNotNull(owned);
            java.util.List<Thread> readers = new java.util.ArrayList<>();
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.getName().equals("ctos-hftp")) readers.add(thread);
            assertEquals("Exactly this service owns the launch reader", 1, readers.size());
            owned.destroyForcibly();
            assertTrue(owned.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            readers.get(0).join(5000);
            assertFalse("Reader must have posted its actual exit callback", readers.get(0).isAlive());
            instrumentation.waitForIdleSync();
            JSONObject stopping = new JSONObject(HftpService.status());
            assertEquals("A real late EOF must not turn requested stop into failure", "stopping", stopping.getString("state"));
            assertEquals("", stopping.getString("reason"));
            int requests = 0;
            org.json.JSONArray logs = stopping.getJSONArray("logs");
            for (int index = 0; index < logs.length(); index++) if (logs.getString(index).contains("Stop requested;")) requests++;
            assertEquals("Repeated stop is idempotent", 1, requests);
            assertFalse(logs.toString().contains("Service process exited"));
            context.stopService(new Intent(context, HftpService.class));
            awaitState("stopped");
            assertEquals("", new JSONObject(HftpService.status()).getString("reason"));
            assertFalse(HftpService.active());
        } finally {
            context.stopService(new Intent(context, HftpService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test(timeout = 45000) public void replacementDuringDetachedCleanupCannotOwnTheOldSession() throws Exception {
        assertReplacementDuringCleanup(false);
        assertReplacementDuringCleanup(true);
    }

    private void assertReplacementDuringCleanup(boolean failedCleanup) throws Exception {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        Context context = context();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        DelayedReapProcess delayed = null;
        int port;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = unused.getLocalPort(); }
        try {
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class)
                    .putExtra("host", "127.0.0.1").putExtra("port", port).putExtra("maxUploadMiB", 1)));
            awaitState("running");
            java.lang.reflect.Field ownerField = HftpService.class.getDeclaredField("current");
            ownerField.setAccessible(true);
            Object oldOwner = ownerField.get(null);
            assertNotNull(oldOwner);
            java.lang.reflect.Field processField = HftpService.class.getDeclaredField("process");
            processField.setAccessible(true);
            delayed = new DelayedReapProcess((Process) processField.get(oldOwner));
            processField.set(oldOwner, delayed);
            String retainedReason = failedCleanup ? "Controlled relay failure while closing" : "";
            String expectedState = failedCleanup ? "failed" : "stopping";
            instrumentation.runOnMainSync(() -> {
                if (!failedCleanup) HftpService.requestStop(context);
                else try {
                    java.lang.reflect.Field sessionField = HftpService.class.getDeclaredField("session");
                    sessionField.setAccessible(true);
                    java.lang.reflect.Method fail = HftpService.class.getDeclaredMethod("fail", Object.class, String.class);
                    fail.setAccessible(true);
                    fail.invoke(oldOwner, sessionField.get(oldOwner), retainedReason);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue("Cleanup must run in its own thread", delayed.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(HftpService.active());
            assertTrue(new JSONObject(HftpService.status()).getBoolean("closing"));
            // Bypass Bridge on purpose: Android creates a fresh, empty service while old cleanup owns the claim.
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class)
                    .putExtra("host", "127.0.0.1").putExtra("port", port).putExtra("maxUploadMiB", 1)));
            awaitLog("Start ignored while the previous HFTP session is closing");
            assertSame("The rejected instance cannot replace ownership", oldOwner, ownerField.get(null));
            assertEquals(expectedState, new JSONObject(HftpService.status()).getString("state"));
            assertEquals(retainedReason, new JSONObject(HftpService.status()).getString("reason"));
            delayed.release.countDown();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (HftpService.active() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            instrumentation.waitForIdleSync();
            assertFalse(HftpService.active());
            assertFalse(new JSONObject(HftpService.status()).getBoolean("closing"));
            long notificationDeadline = SystemClock.elapsedRealtime() + 2000;
            boolean visible;
            do {
                visible = false;
                for (android.service.notification.StatusBarNotification notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                    if (notification.getId() == 1602) visible = true;
                if (visible) SystemClock.sleep(25);
            } while (visible && SystemClock.elapsedRealtime() < notificationDeadline);
            assertFalse("Rejected foreground service must remove its transient notification", visible);
            assertEquals(failedCleanup ? "failed" : "stopped", new JSONObject(HftpService.status()).getString("state"));
            assertEquals(retainedReason, new JSONObject(HftpService.status()).getString("reason"));
            if (failedCleanup) {
                instrumentation.runOnMainSync(() -> HftpService.requestStop(context));
                awaitState("stopped");
                assertEquals("", new JSONObject(HftpService.status()).getString("reason"));
            }
        } finally {
            if (delayed != null) delayed.release.countDown();
            context.stopService(new Intent(context, HftpService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void unownedStopCannotPublishStateOrAppendDiagnostics() throws Exception {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            synchronized (HftpService.class) {
                try {
                    java.lang.reflect.Field logsField = HftpService.class.getDeclaredField("logs");
                    logsField.setAccessible(true);
                    HftpLogs diagnostics = (HftpLogs) logsField.get(null);
                    java.lang.reflect.Field ownerField = HftpLogs.class.getDeclaredField("owner");
                    ownerField.setAccessible(true);
                    Object previousOwner = ownerField.get(diagnostics);
                    String previousState = HftpService.status();
                    try {
                        // A newly created process has no log owner or service session yet.
                        ownerField.set(diagnostics, null);
                        java.lang.reflect.Method beginStop = HftpService.class.getDeclaredMethod("beginStop");
                        beginStop.setAccessible(true);
                        beginStop.invoke(new HftpService());
                        assertEquals("An unowned stop must preserve state and diagnostics", previousState, HftpService.status());
                        assertFalse(HftpService.active());
                    } finally { ownerField.set(diagnostics, previousOwner); }
                } catch (Exception error) { throw new AssertionError(error); }
            }
        });
    }

    @Test(timeout = 30000) public void explicitStopClearsAnAlreadyFailedStartupWithoutClearingLogs() throws Exception {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        Context context = context();
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertEquals("The collision fixture must match Python's IPv4 bind", "127.0.0.1", occupied.getInetAddress().getHostAddress());
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class)
                    .putExtra("host", "127.0.0.1").putExtra("port", occupied.getLocalPort()).putExtra("maxUploadMiB", 1)));
            JSONObject failed = awaitState("failed");
            assertTrue(failed.getString("reason").contains("Python startup failed:"));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (HftpService.active() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
            assertFalse("Failed session must release its resources before replacement", HftpService.active());
            instrumentation.runOnMainSync(() -> HftpService.requestStop(context));
            JSONObject stopped = awaitState("stopped");
            assertEquals("", stopped.getString("reason"));
            assertFalse(stopped.getBoolean("closing"));
            assertTrue("Diagnostics survive explicit stop", stopped.getJSONArray("logs").toString().contains("Python startup failed:"));
            assertFalse(HftpService.active());
        } finally {
            context.stopService(new Intent(context, HftpService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private static final class DeferredStopContext extends android.content.ContextWrapper {
        boolean requested;
        DeferredStopContext(Context context) { super(context); }
        @Override public boolean stopService(Intent intent) { requested = true; return true; }
    }

    private static final class DelayedReapProcess extends Process {
        private final Process delegate;
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        DelayedReapProcess(Process delegate) { this.delegate = delegate; }
        @Override public java.io.OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return delegate.waitFor(timeout, unit);
        }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public void destroy() { delegate.destroy(); }
        @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
    }

    @Test public void hftpLogsAreBoundedSanitizedAndOwnedByOneSession() throws Exception {
        HftpLogs diagnostics = new HftpLogs();
        Object first = new Object(), second = new Object();
        diagnostics.begin(first);
        StringBuilder longLine = new StringBuilder();
        for (int index = 0; index < 700; index++) longLine.append("\u4e2d\"\n\u202e");
        for (int index = 0; index < 400; index++) diagnostics.append(first, "INFO", longLine.toString());
        org.json.JSONArray snapshot = diagnostics.snapshot();
        assertTrue(snapshot.length() <= 200);
        assertTrue(snapshot.toString().getBytes(StandardCharsets.UTF_8).length <= 32768);
        for (int index = 0; index < snapshot.length(); index++) {
            String line = snapshot.getString(index);
            assertTrue(line.length() <= 512);
            assertFalse(line.contains("\n"));
            assertFalse(line.contains("\u202e"));
        }
        diagnostics.clear();
        assertEquals(0, diagnostics.snapshot().length());
        for (int index = 0; index < 400; index++) diagnostics.append(first, "INFO", "short " + index);
        assertEquals(200, diagnostics.snapshot().length());
        assertTrue(diagnostics.snapshot().getString(0).contains("short 200"));
        diagnostics.clear();
        diagnostics.event(first, new JSONObject().put("type", "log").put("event", "request")
                .put("method", "GET").put("path", "/public.txt?secret-query").put("remote", "127.0.0.1")
                .put("Authorization", "secret-auth").put("body", "secret-body"));
        String requestLog = diagnostics.snapshot().getString(0);
        assertTrue(requestLog, requestLog.contains("GET /public.txt"));
        assertFalse(diagnostics.snapshot().toString().contains("secret"));
        diagnostics.event(first, new JSONObject().put("type", "log").put("event", "traceback").put("message", "private raw output"));
        assertEquals(1, diagnostics.snapshot().length());
        diagnostics.event(first, new JSONObject().put("type", "log").put("event", "request")
                .put("method", "PUT\nsecret-header").put("path", "/public\n\u202e.txt?secret-query").put("remote", "::1"));
        String ipv6 = diagnostics.snapshot().getString(1);
        assertTrue(ipv6.contains("::1 OTHER /public??.txt"));
        assertFalse(ipv6.contains("secret"));
        diagnostics.begin(second);
        diagnostics.append(first, "ERROR", "old delayed callback");
        diagnostics.event(first, new JSONObject().put("type", "log").put("event", "connection").put("remote", "127.0.0.1"));
        assertEquals(0, diagnostics.snapshot().length());
        diagnostics.append(second, "INFO", "current session");
        assertEquals(1, diagnostics.snapshot().length());
        diagnostics.clear();
        diagnostics.append(second, "INFO", "still active after clear");
        assertEquals(1, diagnostics.snapshot().length());
    }

    @Test public void hftpConfigurationAndDocumentPathsAreBounded() throws Exception {
        HftpConfig configured = new HftpConfig("0.0.0.0", 7888, 32, "", "ignored");
        assertEquals("7888", configured.json().getString("port"));
        assertEquals(HftpConfig.DEFAULT_DIRECTORY, configured.directoryName);
        assertFalse(configured.rootRelay);
        assertFalse(configured.json().getBoolean("rootRelay"));
        HftpConfig root = new HftpConfig("0.0.0.0", 7888, 32, "", "", true);
        assertTrue(root.directory("", "").rootRelay);
        assertTrue(root.json().getBoolean("rootRelay"));
        try { new HftpConfig("127.0.0.1", 7888, 32, "", "", true); fail("Root relay accepted loopback mode"); }
        catch (IllegalArgumentException expected) { }
        for (int value : new int[]{0, 1025}) {
            try { new HftpConfig("0.0.0.0", 7888, value, "", ""); fail("Invalid limit accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { new HftpConfig("192.0.2.9", 7888, 32, "", ""); fail("Non-bind address accepted"); }
        catch (IllegalArgumentException expected) { }
        HftpConfig.validateTree(android.provider.DocumentsContract.buildTreeDocumentUri(HftpConfig.EXTERNAL_STORAGE_AUTHORITY, "primary:Download/test-only"));
        HftpConfig.validateTree(android.provider.DocumentsContract.buildTreeDocumentUri(HftpConfig.DOWNLOADS_AUTHORITY, "msd:123"));
        try { HftpConfig.validateTree(android.provider.DocumentsContract.buildDocumentUri(HftpConfig.DOWNLOADS_AUTHORITY, "123")); fail("Non-tree URI accepted"); }
        catch (IllegalArgumentException expected) { }
        try { HftpConfig.validateTree(android.net.Uri.parse("content://cloud.documents/tree/example")); fail("Cloud tree accepted"); }
        catch (IllegalArgumentException expected) { }
        for (String name : new String[]{"..", ".private", "a/b", "a\\b", "a\n", ""}) {
            try { HftpDocuments.parts(new org.json.JSONArray().put(name)); fail("Invalid document name accepted"); }
            catch (java.io.IOException expected) { }
        }
        assertArrayEquals(new String[]{"folder", "file.bin"}, HftpDocuments.parts(new org.json.JSONArray().put("folder").put("file.bin")));
    }

    @Test public void rootOperationsRejectMissingMismatchedAndUnknownCapabilitiesBeforeEffects() throws Exception {
        assertFalse("Omitted Root flag must never reuse persisted authorization", HftpBridge.rootRequested(null));
        assertFalse(HftpBridge.rootRequested(false));
        assertTrue(HftpBridge.rootRequested(true));
        for (Object flag : new Object[]{"true", "false", 1, 0}) {
            try { HftpBridge.rootRequested(flag); fail("Non-boolean Root request accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        ToolExecutionContext app = ToolExecutionContext.declare("hftp", ToolExecutionContext.Requirement.APP);
        assertEquals(ToolExecutionContext.Requirement.APP, app.requirement);
        for (String operation : new String[]{null, "shell", "hftp.networkRelay; arbitrary", "hftp.privateFiles"}) {
            try { ToolExecutionContext.declare(operation, ToolExecutionContext.Requirement.ROOT); fail("Unknown Root operation accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { ToolExecutionContext.declare("hftp.networkRelay", ToolExecutionContext.Requirement.APP); fail("Mismatched requirement accepted"); }
        catch (IllegalArgumentException expected) { }
        try { ToolExecutionContext.declare("hftp", ToolExecutionContext.Requirement.ROOT); fail("Python granted Root capability"); }
        catch (IllegalArgumentException expected) { }
        try { ToolExecutionContext.declare("hftp", null); fail("Missing requirement accepted"); }
        catch (IllegalArgumentException expected) { }
        // Null Context proves these denials precede Android, executable and su effects.
        for (ToolExecutionContext execution : new ToolExecutionContext[]{null, app}) {
            try { new RootOperationAdapter(null, execution, null, 7888, 12345, (level, message) -> fail("Unexpected Root log"), reason -> fail("Unexpected Root effect")); fail("Undeclared Root execution accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        ToolExecutionContext root = ToolExecutionContext.declare("hftp.networkRelay", ToolExecutionContext.Requirement.ROOT);
        try { new RootOperationAdapter(null, root, null, 7888, 12345, (level, message) -> {}, reason -> {}); fail("Missing network accepted"); }
        catch (RootOperationAdapter.RelayException expected) { }
        assertEquals("'/apk/path with space/a'\\''b/libctos_hftp_relay.so'", RootOperationAdapter.quote("/apk/path with space/a'b/libctos_hftp_relay.so"));
    }

    @Test(timeout = 10000) public void nativeRootRelayRejectsAppUidBeforeNetworkEffects() throws Exception {
        assertTrue("This check must run in the ordinary App process", android.os.Process.myUid() >= 10000);
        File helper = new File(context().getApplicationInfo().nativeLibraryDir, "libctos_hftp_relay.so");
        assertTrue("The fixed APK relay must be packaged", helper.isFile() && helper.canExecute());
        Process child = new ProcessBuilder(helper.getAbsolutePath(), "192.0.2.1", "24", "17888", "17889", "1").start();
        try {
            assertTrue("An App execution must be rejected immediately", child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
            try (java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                JSONObject denied = new JSONObject(output.readLine());
                assertEquals("failed", denied.getString("state"));
                assertEquals("root_required", denied.getString("reason"));
                assertEquals(android.system.OsConstants.EPERM, denied.getInt("errno"));
                assertNull("No readiness or network event may follow a capability denial", output.readLine());
            }
            assertEquals(1, child.exitValue());
        } finally { child.destroyForcibly(); }
    }

    @Test public void rootRelayCpuLeaseReleasesAfterCloseAndConstructorFailure() throws Exception {
        assertFalse(isRelayCpuLeaseRow(new StringBuilder("Wake Locks: size=0")));
        assertFalse(isRelayCpuLeaseRow(new StringBuilder("ACQ (partial) 'im.majo.ctos:RootHftpRelay'")));
        assertFalse(isRelayCpuLeaseRow(new StringBuilder("PARTIAL_WAKE_LOCK 'another.owner:Lock' uid=10000")));
        assertFalse(isRelayCpuLeaseRow(new StringBuilder("PARTIAL_WAKE_LOCK 'im.majo.ctos:RootHftpRelayTest' uid=10000")));
        assertTrue(isRelayCpuLeaseRow(new StringBuilder("    PARTIAL_WAKE_LOCK 'im.majo.ctos:RootHftpRelay' uid=10000")));
        android.os.PowerManager power = context().getApplicationContext().getSystemService(android.os.PowerManager.class);
        assertNotNull(power);
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                context().checkSelfPermission(android.Manifest.permission.WAKE_LOCK));
        android.os.PowerManager.WakeLock lock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "im.majo.ctos:RootHftpRelayTest");
        java.util.List<String> logs = new java.util.ArrayList<>();
        try {
            RootOperationAdapter.CpuLease lease = new RootOperationAdapter.CpuLease(lock, (level, message) -> logs.add(message));
            assertTrue("The App must own its CPU lease while the relay is active", lock.isHeld());
            lease.close();
            assertFalse("Closing the relay must release CPU ownership", lock.isHeld());
            lease.close();
            assertEquals("Repeated close must not acquire or release again", 2, logs.size());
            try {
                new RootOperationAdapter.CpuLease(lock, (level, message) -> {
                    assertTrue("Failure occurs after real acquisition", lock.isHeld());
                    throw new IllegalStateException("controlled acquisition log failure");
                });
                fail("A controlled startup failure must propagate");
            } catch (IllegalStateException expected) { }
            assertFalse("A throwing constructor must not strand a wake lock", lock.isHeld());
            RootOperationAdapter.CpuLease throwingClose = new RootOperationAdapter.CpuLease(lock, (level, message) -> {
                if (message.endsWith("released")) throw new IllegalStateException("controlled release log failure");
            });
            throwingClose.close();
            assertFalse("Diagnostic failure must occur after release", lock.isHeld());
            throwingClose.close();
        } finally { if (lock.isHeld()) lock.release(); }
    }

    @Test(timeout = 20000) public void rootRelayCpuLeaseTracksActualReadyFailureAndClose() throws Exception {
        org.junit.Assume.assumeTrue("Actual Root startup requires explicit scoped test selection",
                "true".equals(InstrumentationRegistry.getArguments().getString("hftpRootWakeLock")));
        assertFalse("Do not overlap a user-owned HFTP service", HftpService.active());
        RootOperationAdapter.WifiTarget wifi = RootOperationAdapter.WifiTarget.select(context());
        ToolExecutionContext execution = ToolExecutionContext.declare("hftp.networkRelay", ToolExecutionContext.Requirement.ROOT);
        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.function.BiConsumer<String, String> logger = (level, message) -> logs.add(message);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        int lanPort;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            lanPort = unused.getLocalPort();
        }
        int backendPort;
        RootOperationAdapter running = null;
        android.os.PowerManager.WakeLock owned = null;
        try (java.net.ServerSocket backend = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            backendPort = backend.getLocalPort();
            try {
                running = new RootOperationAdapter(context(), execution, wifi, lanPort, backendPort, logger, failure::set);
                owned = relayCpuLock(running);
                assertTrue("Acquisition must precede Root readiness", owned.isHeld());
                running.awaitReady();
                assertTrue("The ready relay must keep its App CPU lease", owned.isHeld());
                assertNull(failure.get());
                backend.setSoTimeout(2000);
                try (java.net.Socket probe = backend.accept()) { }
            } finally { if (running != null) running.close(); }
            RootOperationAdapter interrupted = new RootOperationAdapter(context(), execution, wifi, lanPort, backendPort, logger, failure::set);
            android.os.PowerManager.WakeLock interruptedLock = relayCpuLock(interrupted);
            try {
                interrupted.awaitReady();
                try (java.net.Socket probe = backend.accept()) { }
                assertTrue(interruptedLock.isHeld());
                Thread.currentThread().interrupt();
                interrupted.close();
                assertTrue("Cleanup must preserve the caller's interrupt", Thread.currentThread().isInterrupted());
                assertFalse("Interrupted reap must still release CPU ownership", interruptedLock.isHeld());
            } finally { Thread.interrupted(); interrupted.close(); }
            java.util.concurrent.atomic.AtomicBoolean rejectedCallback = new java.util.concurrent.atomic.AtomicBoolean();
            RootOperationAdapter callbackFailure = new RootOperationAdapter(context(), execution, wifi, lanPort, backendPort,
                    (level, message) -> {
                        if (message.endsWith("released") || message.equals("Root relay failure callback rejected"))
                            throw new IllegalStateException("controlled cleanup diagnostic failure");
                        logs.add(message);
                    }, reason -> {
                        rejectedCallback.set(true);
                        throw new IllegalStateException("controlled failure callback rejection");
                    });
            android.os.PowerManager.WakeLock callbackLock = relayCpuLock(callbackFailure);
            try {
                callbackFailure.awaitReady();
                try (java.net.Socket probe = backend.accept()) { }
                assertTrue(callbackLock.isHeld());
                java.lang.reflect.Method report = RootOperationAdapter.class.getDeclaredMethod("reportFailure", String.class);
                report.setAccessible(true);
                report.invoke(callbackFailure, "Controlled relay failure");
                assertTrue(rejectedCallback.get());
                assertFalse("Rejected failure and logging callbacks must still close CPU ownership", callbackLock.isHeld());
                java.lang.reflect.Field process = RootOperationAdapter.class.getDeclaredField("process");
                process.setAccessible(true);
                assertFalse("Rejected callbacks must not strand the owned relay process",
                        ((Process) process.get(callbackFailure)).isAlive());
            } finally { callbackFailure.close(); }
        }
        assertNotNull(owned);
        assertFalse("Explicit close must release the real relay's wake lock", owned.isHeld());
        assertTrue(logs.contains("Root relay CPU wake lock released"));
        RootOperationAdapter rejected = new RootOperationAdapter(context(), execution, wifi, lanPort, backendPort, logger, failure::set);
        android.os.PowerManager.WakeLock failedLock = relayCpuLock(rejected);
        try {
            assertTrue(failedLock.isHeld());
            try { rejected.awaitReady(); fail("The closed backend must fail readiness"); }
            catch (RootOperationAdapter.RelayException expected) { }
            assertFalse("Failed readiness must release without waiting for Service cleanup", failedLock.isHeld());
        } finally { rejected.close(); }
        assertFalse(failedLock.isHeld());
    }

    @Test public void rootRelayConstructorFailureReleasesItsAppWakeLock() throws Exception {
        assertFalse("Do not overlap a user-owned HFTP service", HftpService.active());
        assertFalse("Start with no existing relay CPU lease", relayCpuLeaseVisible());
        ToolExecutionContext execution = ToolExecutionContext.declare("hftp.networkRelay", ToolExecutionContext.Requirement.ROOT);
        RootOperationAdapter.WifiTarget wifi = RootOperationAdapter.WifiTarget.select(context());
        java.util.concurrent.atomic.AtomicBoolean acquired = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            new RootOperationAdapter(context(), execution, wifi, 17888, 17889, (level, message) -> {
                try { assertTrue("Failure occurs after the real App lock is visible", relayCpuLeaseVisible()); }
                catch (Exception error) { throw new AssertionError(error); }
                acquired.set(true);
                throw new IllegalStateException("controlled adapter construction failure");
            }, reason -> fail("No Root process may be started after construction failure"));
            fail("The controlled constructor failure must propagate");
        } catch (IllegalStateException expected) { }
        assertTrue(acquired.get());
        assertFalse("Throwing construction must release its own App lock", relayCpuLeaseVisible());
    }

    private boolean relayCpuLeaseVisible() throws Exception {
        android.os.ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("dumpsys power");
        try (java.io.Reader input = new java.io.BufferedReader(new java.io.InputStreamReader(
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            boolean oversized = false;
            int count = 0, value;
            while ((value = input.read()) != -1) {
                if (++count > 2 * 1024 * 1024) throw new java.io.IOException("Power diagnostic exceeded the test bound");
                if (value == '\n') {
                    if (!oversized && isRelayCpuLeaseRow(line)) return true;
                    line.setLength(0);
                    oversized = false;
                } else if (line.length() >= 4096) oversized = true;
                else line.append((char) value);
            }
            return !oversized && isRelayCpuLeaseRow(line);
        }
    }

    private boolean isRelayCpuLeaseRow(StringBuilder line) {
        String text = line.toString().trim();
        return text.startsWith("PARTIAL_WAKE_LOCK ")
                && text.contains("'im.majo.ctos:RootHftpRelay'");
    }

    private android.os.PowerManager.WakeLock relayCpuLock(RootOperationAdapter adapter) throws Exception {
        java.lang.reflect.Field lease = RootOperationAdapter.class.getDeclaredField("cpuLease");
        lease.setAccessible(true);
        java.lang.reflect.Field lock = RootOperationAdapter.CpuLease.class.getDeclaredField("lock");
        lock.setAccessible(true);
        return (android.os.PowerManager.WakeLock) lock.get(lease.get(adapter));
    }

    @Test public void selectedLocalDirectoryProviderIsConfirmed() throws Exception {
        String expected = InstrumentationRegistry.getArguments().getString("hftpExpectedProvider");
        org.junit.Assume.assumeTrue("Requires an explicitly selected isolated directory", expected != null);
        HftpConfig selected = HftpConfig.load(context());
        assertFalse("Choose the isolated directory before checking its provider", selected.treeUri.isEmpty());
        android.net.Uri uri = android.net.Uri.parse(selected.treeUri);
        assertEquals(expected, uri.getAuthority());
        HftpConfig.requirePermission(context(), uri);
        new HftpDocuments(context(), uri, selected.maxUploadMiB * 1024 * 1024L);
        android.os.Bundle evidence = new android.os.Bundle();
        evidence.putString("stream", "HFTP local provider: " + uri.getAuthority() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
    }

    @Test public void documentRequestsPreserveOwnersAndLockSharedLibraryWhilePreparing() {
        assertFalse("Do not interrupt a user-started HFTP service", HftpService.active());
        assertFalse("Do not interrupt an existing directory operation", HftpBridge.preparing());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            PickerActivity activity = new PickerActivity();
            ToolFiles files = new ToolFiles(activity);
            HftpBridge bridge = new HftpBridge(activity);
            try {
                RecordedResult original = new RecordedResult(), duplicate = new RecordedResult();
                files.handle(new io.flutter.plugin.common.MethodCall("toolFilePick", null), original);
                files.handle(new io.flutter.plugin.common.MethodCall("toolFilePick", null), duplicate);
                assertEquals("FILES", duplicate.error);
                assertFalse(original.completed);
                assertTrue(files.onActivityResult(activity.request, android.app.Activity.RESULT_CANCELED, null));
                assertTrue("The original picker must still receive cancellation", original.completed);

                RecordedResult selection = new RecordedResult();
                bridge.handle(new io.flutter.plugin.common.MethodCall("hftpPickDirectory", null), selection);
                assertTrue(HftpBridge.preparing());
                for (String method : new String[]{"hftpImport", "hftpClearShare"}) {
                    RecordedResult blocked = new RecordedResult();
                    files.handle(new io.flutter.plugin.common.MethodCall(method, null), blocked);
                    assertEquals("FILES", blocked.error);
                    assertTrue(HftpBridge.preparing());
                }
                assertTrue(bridge.onActivityResult(activity.request, android.app.Activity.RESULT_CANCELED, null));
                assertTrue(selection.completed);
                assertFalse(HftpBridge.preparing());
            } finally { bridge.close(); files.close(); }
        });
    }

    private final class PickerActivity extends android.app.Activity {
        int request;
        @Override public void startActivityForResult(Intent intent, int requestCode) { request = requestCode; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return context().getSharedPreferences(name, mode);
        }
    }

    private static final class RecordedResult implements io.flutter.plugin.common.MethodChannel.Result {
        boolean completed;
        String error;
        @Override public void success(Object value) { completed = true; }
        @Override public void error(String code, String message, Object details) { completed = true; error = code; }
        @Override public void notImplemented() { throw new AssertionError("Unexpected unsupported method"); }
    }

    private int declaredUpload(int port, String path, long size) throws Exception {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            String header = "PUT " + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\nX-ctos-upload: 1\r\nContent-Length: " + size + "\r\n\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
            java.io.BufferedReader response = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return Integer.parseInt(response.readLine().split(" ")[1]);
        }
    }

    private static Map<String, String> parameters(String... values) {
        Map<String, String> result = new java.util.HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private JSONObject awaitState(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject value = new JSONObject(HftpService.status());
            if (value.getString("state").equals(expected)) return value;
            if (value.getString("state").equals("failed")) fail(value.optString("reason"));
            SystemClock.sleep(50);
        }
        throw new AssertionError("Service did not reach " + expected);
    }

    private String awaitLog(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String value = new JSONObject(HftpService.status()).getJSONArray("logs").toString();
            if (value.contains(expected)) return value;
            SystemClock.sleep(25);
        }
        throw new AssertionError("Service log did not contain " + expected);
    }

    private int request(int port, String path, String authorization, byte[] body) throws Exception {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            java.io.OutputStream output = socket.getOutputStream();
            String headers = (body == null ? "GET" : "PUT") + " " + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\n";
            if (authorization != null) headers += "Authorization: " + authorization + "\r\n";
            if (body != null) headers += "X-ctos-upload: 1\r\nContent-Length: " + body.length + "\r\n";
            output.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
            if (body != null) output.write(body);
            output.flush();
            java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = input.readLine();
            if (line == null) throw new java.io.IOException("Listener closed");
            return Integer.parseInt(line.split(" ")[1]);
        }
    }
}
