package im.majo.ctos;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/**
 * Opt-in real MainActivity/shared VaultStore/managed SSH/TCP fixture. Event sinks are the
 * observation boundary; native proof, protection, request admission and durable writes are real.
 * Requires fresh isolated fixture app data and an independently provisioned synthetic SSH server.
 * Argument ctosSshPasswordRequestFile is a shell-owned 0600 JSON file at
 * /data/local/tmp/ctos-ssh-password-NAME.json with host, port, username, password, fingerprint.
 * The password never appears in instrumentation arguments or diagnostic messages. Approval is
 * permitted only for the explicitly supplied complete SHA256 fingerprint and exact endpoint.
 */
public final class SshPasswordConnectPlatformTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private Method dispatch;
    private String activeRequest, protection;
    private Object oldSshSink, oldTerminalSink;
    private SshPasswordConnectBridge ssh;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger reviews = new AtomicInteger();
    private final StringBuilder output = new StringBuilder();

    @Test public void actualBridgeNewThenKnownPasswordShellAndProtectionRelease() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("ctosSshPasswordRequestFile");
        Assume.assumeTrue("Explicit independent TCP fixture request was not provided", path != null);
        assertTrue("Invalid fixture request path", path.matches("/data/local/tmp/ctos-ssh-password-[A-Za-z0-9_-]{1,64}\\.json"));
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse(context.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        File vaultFile = new File(context.getNoBackupFilesDir(), "app-secrets.bin");
        assertFalse("Fresh isolated fixture app data is required", vaultFile.exists());
        assertFalse("A previous backup requires explicit reconciliation", new File(vaultFile.getPath() + ".bak").exists());
        JSONObject request = readRequest(path);
        String host = request.getString("host"), username = request.getString("username");
        int port = request.getInt("port");
        String fingerprint = request.getString("fingerprint");
        assertTrue("Explicit expected fingerprint is required", fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
        new SshPasswordProfile(host, port, username);
        try {
            activity = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dispatch = MainActivity.class.getDeclaredMethod("call", MethodCall.class, MethodChannel.Result.class);
            dispatch.setAccessible(true);
            ssh = (SshPasswordConnectBridge)field(activity, "sshPasswordConnect");
            AppSecretsBridge secrets = (AppSecretsBridge)field(activity, "appSecrets");
            assertSame(AppSecretsOwner.acquire(activity), secrets.sshVault());
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            String master = "Fixture-" + UUID.randomUUID();
            call("secrets.create", VaultCollections.map("requestId", id(), "password", master, "confirmation", master));
            assertTrue(vaultFile.exists());
            installObservers(host, port, fingerprint);
            Map<String, Object> empty = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals("", empty.get("host")); assertEquals("", empty.get("username"));
            connect(request);
            assertEquals(1, reviews.get());
            releaseProtection();
            assertShellRoundTrip(); // Form privacy ends; this established shell must remain usable.
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            Map<String, Object> saved = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals(host, saved.get("host")); assertEquals(port, ((Number)saved.get("port")).intValue());
            assertEquals(username, saved.get("username"));
            connect(request);
            assertEquals("An already saved matching host must not request approval again", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
        } finally {
            request.remove("password");
            if (activity != null) {
                try {
                    if (activeRequest != null) call("ssh.cancel", VaultCollections.map("requestId", activeRequest));
                    call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
                    releaseProtection(); call("secrets.lock", java.util.Collections.emptyMap());
                } finally {
                    instrumentation.runOnMainSync(() -> {
                        try {
                            if (ssh != null) setField(ssh, "sink", oldSshSink);
                            setField(activity, "terminalSink", oldTerminalSink);
                        } catch (Exception failure) { throw new AssertionError("Fixture observation cleanup failed"); }
                        activity.finish();
                    });
                }
            }
            synchronized (output) { output.setLength(0); }
        }
    }

    private void connect(JSONObject request) throws Exception {
        running.set(false); activeRequest = id();
        Map<String, Object> connected = map(call("ssh.passwordConnect", VaultCollections.map(
                "requestId", activeRequest, "host", request.getString("host"), "port", request.getInt("port"),
                "username", request.getString("username"), "password", request.getString("password"),
                "columns", 80, "rows", 24)));
        assertEquals(activeRequest, connected.get("requestId")); assertEquals("connected", connected.get("status"));
        assertTrue("The actual terminal RUNNING event must precede connected completion", running.get());
        activeRequest = null;
    }

    private void installObservers(String host, int port, String fingerprint) throws Exception {
        oldSshSink = field(ssh, "sink"); oldTerminalSink = field(activity, "terminalSink");
        instrumentation.runOnMainSync(() -> {
            try {
                setField(ssh, "sink", new EventChannel.EventSink() {
                    @Override public void success(Object value) {
                        Map<?, ?> event = (Map<?, ?>)value;
                        if (!"hostKey".equals(event.get("type"))) return;
                        reviews.incrementAndGet();
                        boolean exact = host.equals(event.get("host"))
                                && ((Number)event.get("port")).intValue() == port
                                && fingerprint.equals(event.get("fingerprint"));
                        // This is the explicit fixture operator's pinned decision, never a default approval.
                        CompletableFuture<Object> answer = invoke("ssh.hostKeyDecision", VaultCollections.map(
                                "requestId", event.get("requestId"), "ticketId", event.get("ticketId"),
                                "binding", event.get("binding"), "accept", exact));
                        assertTrue("Host decision must bypass the blocked terminal owner", answer.isDone());
                        assertTrue("The exact live ticket must be accepted", (Boolean)map(answer.join()).get("accepted"));
                        assertTrue("The offered key must match the independent server fingerprint", exact);
                    }
                    @Override public void error(String code, String message, Object details) { throw new AssertionError(code); }
                    @Override public void endOfStream() { }
                });
                setField(activity, "terminalSink", new EventChannel.EventSink() {
                    @Override public void success(Object value) {
                        Map<?, ?> event = (Map<?, ?>)value;
                        if ("state".equals(event.get("type")) && "running".equals(event.get("state"))) running.set(true);
                        if ("data".equals(event.get("type"))) synchronized (output) {
                            output.append(new String((byte[])event.get("bytes"), StandardCharsets.UTF_8));
                            if (output.length() > 65536) output.delete(0, output.length() - 65536);
                        }
                    }
                    @Override public void error(String code, String message, Object details) { throw new AssertionError(code); }
                    @Override public void endOfStream() { }
                });
            } catch (Exception failure) { throw new AssertionError("Fixture observations could not be installed"); }
        });
    }

    private void assertShellRoundTrip() throws Exception {
        String first = UUID.randomUUID().toString().substring(0, 8), second = UUID.randomUUID().toString().substring(0, 8);
        synchronized (output) { output.setLength(0); }
        call("terminalWrite", VaultCollections.map("text", "printf '%s%s\\n' '" + first + "' '" + second + "'\n"));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized (output) { if (output.indexOf(first + second) >= 0) return; }
            SystemClock.sleep(20);
        }
        fail("The independently authenticated shell did not return the synthetic marker");
    }
    private void releaseProtection() throws Exception {
        if (protection == null) return;
        call("secrets.protect", VaultCollections.map("action", "release", "owner", protection)); protection = null;
    }
    private void waitReleased() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (field(activity, "sshFactory") == null && field(activity, "sshCancellation") == null) return;
            SystemClock.sleep(20);
        }
        fail("SSH owner cleanup did not confirm release");
    }
    private CompletableFuture<Object> invoke(String method, Map<String, Object> arguments) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        try {
            dispatch.invoke(activity, new MethodCall(method, arguments), new MethodChannel.Result() {
                @Override public void success(Object value) { result.complete(value); }
                @Override public void error(String code, String message, Object details) {
                    result.completeExceptionally(new AssertionError("Fixed host failure: " + code));
                }
                @Override public void notImplemented() { result.completeExceptionally(new AssertionError("Missing host operation")); }
            });
        } catch (Exception failure) { result.completeExceptionally(new AssertionError("Host dispatch failed")); }
        return result;
    }
    private Object call(String method, Map<String, Object> arguments) throws Exception {
        CompletableFuture<Object>[] result = new CompletableFuture[1];
        instrumentation.runOnMainSync(() -> result[0] = invoke(method, arguments));
        return result[0].get(75, TimeUnit.SECONDS);
    }
    private JSONObject readRequest(String path) throws Exception {
        byte[] bytes = new byte[16385];
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand("cat " + path);
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            int size = 0, count;
            while ((count = input.read(bytes, size, bytes.length - size)) >= 0) {
                size += count; assertTrue("Fixture request is oversized", size <= 16384);
            }
            try {
                JSONObject request = new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8));
                assertTrue("Synthetic password is required", request.opt("password") instanceof String);
                return request;
            } catch (org.json.JSONException failure) { throw new AssertionError("Invalid fixture request"); }
        } finally { VaultCodec.wipe(bytes); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>)value; }
    private static String id() { return "ssh_fixture_" + UUID.randomUUID(); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
