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
            instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, HftpService.class).putExtra("host", "127.0.0.1").putExtra("port", port)));
            JSONObject state = awaitState("running");
            boolean visible = false;
            for (android.service.notification.StatusBarNotification notification : context.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                if (notification.getId() == 1602) visible = true;
            assertTrue("A visible service notification is required", visible);
            assertEquals(401, request(port, "/", null, null));
            String authorization = "Basic " + Base64.getEncoder().encodeToString(("ctos:" + state.getString("password")).getBytes(StandardCharsets.UTF_8));
            assertEquals(200, request(port, "/" + imported.getName(), authorization, null));
            instrumentation.runOnMainSync(() -> assertTrue(activity.moveTaskToBack(true)));
            SystemClock.sleep(500);
            assertEquals("running", new JSONObject(HftpService.status()).getString("state"));
            assertEquals(200, request(port, "/" + imported.getName(), authorization, null));
            assertEquals(400, request(port, "/%2e%2e/secret", authorization, null));
            assertEquals(201, request(port, "/" + uploaded.getName(), authorization, "upload".getBytes(StandardCharsets.UTF_8)));
            assertEquals(409, request(port, "/" + uploaded.getName(), authorization, "replace".getBytes(StandardCharsets.UTF_8)));
            assertEquals("upload", new String(Files.readAllBytes(uploaded.toPath()), StandardCharsets.UTF_8));
            assertEquals("shared", new String(Files.readAllBytes(imported.toPath()), StandardCharsets.UTF_8));
            context.stopService(new Intent(context, HftpService.class));
            awaitState("stopped");
            try { request(port, "/", authorization, null); fail("Owned listener must close"); }
            catch (java.io.IOException expected) { /* The stopped service no longer accepts connections. */ }
        } finally {
            context.stopService(new Intent(context, HftpService.class));
            instrumentation.runOnMainSync(activity::finish);
            imported.delete(); uploaded.delete();
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
