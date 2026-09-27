package im.majo.ctos;

import android.content.Context;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.Assert.*;

public class DeviceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test(timeout = 45000) public void bundledPythonRunsSdkWithoutRootAndRejectsUnknownScripts() throws Exception {
        PortablePackages packages = new PortablePackages(context());
        PythonRunner runner = new PythonRunner(context(), packages);
        try {
            assertTrue(runner.claim("catalog"));
            JSONObject catalog = runner.execute(null, null);
            assertEquals("3.13.9", catalog.getString("python"));
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < catalog.getJSONArray("scripts").length(); i++)
                ids.add(catalog.getJSONArray("scripts").getJSONObject(i).getString("id"));
            assertTrue(ids.containsAll(java.util.Arrays.asList("python.selftest", "text.digest", "tools.password",
                    "tools.encoder", "tools.ip", "tools.crypto", "tools.hftp")));
            assertEquals("python3", catalog.getString("terminal"));
            assertTrue(runner.claim("check"));
            JSONObject checked = runner.execute("python.selftest", null);
            assertEquals(checked.toString(), "completed", checked.getString("state"));
            assertTrue(checked.getJSONObject("data").getBoolean("sqliteCheck"));
            assertTrue(runner.claim("digest"));
            JSONObject digest = runner.execute("text.digest", java.util.Collections.singletonMap("text", "中文🙂"));
            assertEquals(3, digest.getJSONObject("data").getInt("characters"));
            assertTrue(runner.claim("device"));
            JSONObject device = runner.execute("device.info", null);
            assertEquals(android.os.Build.MODEL, device.getJSONObject("data").getJSONObject("data").getString("model"));
            assertFalse(DeviceSnapshot.collect(context()).has("cpu"));
            assertTrue(runner.claim("unknown"));
            try { runner.execute("unknown.script", null); fail("Unknown scripts must fail"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Unknown script")); }
            assertTrue(runner.claim("after-failure"));
            assertEquals("completed", runner.execute("memory.snapshot", null).getString("state"));
        } finally { runner.close(); }
    }

    @Test(timeout = 30000) public void pythonCancellationAndTimeoutRecycleTheProcess() throws Exception {
        PortablePackages packages = new PortablePackages(context());
        packages.prepare();
        PythonRunner runner = new PythonRunner(context(), packages);
        try {
            assertTrue(runner.claim("cancel"));
            assertFalse(runner.claim("duplicate"));
            runner.cancel("cancel");
            assertEquals("cancelled", runner.execute("python.selftest", null).getString("state"));
            assertTrue(runner.claim("retry"));
            assertEquals("completed", runner.execute("python.selftest", null).getString("state"));
            assertTrue(runner.claim("running-cancel"));
            java.util.concurrent.FutureTask<JSONObject> active = new java.util.concurrent.FutureTask<>(
                    () -> runner.execute("python.selftest", null));
            new Thread(active, "ctos-test-python").start();
            java.lang.reflect.Field process = PythonRunner.class.getDeclaredField("process");
            process.setAccessible(true);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean cancelledRunning = false;
            while (SystemClock.elapsedRealtime() < deadline && !active.isDone()) {
                synchronized (runner) {
                    if (process.get(runner) != null) {
                        runner.cancel("running-cancel");
                        cancelledRunning = true;
                        break;
                    }
                }
                SystemClock.sleep(1);
            }
            assertTrue("Must cancel an actual launched Python process", cancelledRunning);
            assertEquals("cancelled", active.get(5, java.util.concurrent.TimeUnit.SECONDS).getString("state"));
            assertTrue(runner.claim("after-running-cancel"));
            assertEquals("completed", runner.execute("python.selftest", null).getString("state"));
        } finally { runner.close(); }
        PythonRunner timeout = new PythonRunner(context(), packages, 1);
        try {
            assertTrue(timeout.claim("timeout"));
            assertEquals("timed_out", timeout.execute("python.selftest", null).getString("state"));
            assertTrue(timeout.claim("after-timeout"));
            timeout.cancel("after-timeout");
            assertEquals("cancelled", timeout.execute("python.selftest", null).getString("state"));
        } finally { timeout.close(); }
    }

    @Test public void cloneLauncherAliasMapsToCloneUidProfile() {
        Map<Integer, Integer> users = Collector.userIdsBySerial(
                "UserInfo{0:Owner:4c13} serialNo=0 isPrimary=true\n" +
                "UserInfo{999:MultiApp:4001010} serialNo=10 isPrimary=false parentId=0\n");
        assertEquals(Integer.valueOf(999), users.get(10));
        Map<String, String> aliases = Collector.launcherAliases(
                "Row: 23 title=QQ, intent=#Intent;component=com.tencent.mobileqq/.Splash;end, profileId=0\n" +
                "Row: 24 title=tim, intent=#Intent;component=com.tencent.mobileqq/.Splash;end, profileId=10\n", users);
        assertEquals("tim", aliases.get("999:com.tencent.mobileqq"));
        assertNull(aliases.get("0:com.tencent.mobileqq"));
    }

    @Test(timeout = 30000) public void backgroundActivityCancelsWorkbenchAndAllowsNewRun() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Activity activity = instrumentation.startActivitySync(new android.content.Intent(
                context(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("pythonRunner");
            field.setAccessible(true);
            PythonRunner runner = (PythonRunner) field.get(activity);
            assertTrue(runner.claim("background"));
            java.lang.reflect.Field cancelled = PythonRunner.class.getDeclaredField("cancelled");
            cancelled.setAccessible(true);
            instrumentation.runOnMainSync(() -> assertTrue(activity.moveTaskToBack(true)));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean stopped = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                synchronized (runner) { stopped = cancelled.getBoolean(runner); }
                if (stopped) break;
                SystemClock.sleep(10);
            }
            assertTrue("Activity.onStop must cancel the pending workbench task", stopped);
            assertEquals("cancelled", runner.execute("python.selftest", null).getString("state"));
            assertTrue(runner.claim("after-background"));
            assertEquals("completed", runner.execute("python.selftest", null).getString("state"));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test(timeout = 45000) public void rootCountersAndConnections() throws Exception {
        JSONObject identity = Collector.authorizeRoot();
        assertEquals(identity.toString(), 0, identity.getInt("exit"));
        assertTrue(identity.getString("output").contains("uid=0("));
        JSONObject snapshot = Collector.interfaces(true);
        assertEquals(snapshot.toString(), 0, snapshot.getInt("exit"));
        assertEquals("root / procfs + ip", snapshot.getString("source"));
        assertTrue(snapshot.getJSONArray("interfaces").length() > 0);
        ConnectivityManager connectivity = context().getSystemService(ConnectivityManager.class);
        LinkProperties link = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        assertNotNull("No active network", link);
        String activeInterface = link.getInterfaceName();
        assertTrue(snapshot.getString("routes").contains(activeInterface));
        boolean found = false;
        for (int i = 0; i < snapshot.getJSONArray("interfaces").length(); i++) {
            JSONObject item = snapshot.getJSONArray("interfaces").getJSONObject(i);
            if (!item.getString("name").equals(activeInterface)) continue;
            found = true;
            assertTrue(item.getLong("rx") > 0);
            assertTrue(item.getJSONArray("addresses").length() > 0);
        }
        assertTrue(found);
        JSONObject connections = Collector.connections(context(), true);
        assertEquals(connections.toString(), 0, connections.getInt("exit"));
        assertFalse(connections.toString(), connections.getBoolean("partial"));
        assertTrue(connections.getString("output").contains("tcp"));
        assertNotNull(connections.getJSONObject("apps"));
        Collector.closeRoot();
    }

    @Test(timeout = 45000) public void rootCollectorReusesSessionAndNeverAutoReopens() throws Exception {
        Collector.authorizeRoot();
        try {
            String first = Collector.command("echo $PPID", true, 5).getString("output").trim();
            for (int i = 0; i < 8; i++) {
                assertEquals(first, Collector.command("echo $PPID", true, 5).getString("output").trim());
                assertEquals(0, Collector.interfaces(true).getInt("exit"));
            }
            assertEquals(7, Collector.command("exit 7", true, 5).getInt("exit"));
            assertEquals(0, Collector.command("id", true, 5).getInt("exit"));
            try {
                Collector.command("sleep 3", true, 1);
                fail("Hung command must time out");
            } catch (java.util.concurrent.TimeoutException expected) { }
            try {
                Collector.command("id", true, 5);
                fail("Timed-out Root session must not reopen");
            } catch (IllegalStateException expected) { }
        } finally { Collector.closeRoot(); }
        try {
            Collector.command("id", true, 5);
            fail("Closed Root session must not start su automatically");
        } catch (IllegalStateException expected) { }
    }

    @Test(timeout = 45000) public void appAndRootPtyAreInteractive() throws Exception {
        verifyPty(false);
        verifyPty(true);
    }

    private void verifyPty(boolean root) throws Exception {
        int[] process = Pty.start(root, 70, 20, context().getFilesDir().getAbsolutePath(),
                new PortablePackages(context()).shellRc());
        try {
            // Magisk initializes a second PTY and flushes input before its first prompt.
            readUntil(process[0], root ? "# " : "$ ");
            Pty.resize(process[0], 83, 27);
            Pty.write(process[0], "id; tty; stty size; printf '\\nCTOS_READY\\n'\n".getBytes(StandardCharsets.UTF_8));
            String output = readUntil(process[0], "\r\nCTOS_READY\r\n");
            assertTrue(output, output.contains(root ? "uid=0(" : "uid=" + android.os.Process.myUid() + "("));
            assertTrue(output, output.matches("(?s).*[/]pts[/][0-9]+.*"));
            assertTrue(output, output.contains("27 83"));
            Pty.write(process[0], "python3 --version; python3 -c 'import sqlite3, ssl, ctos_sdk; print(6*7)'; printf '\\nCTOS_PYTHON\\n'\n".getBytes(StandardCharsets.UTF_8));
            String python = readUntil(process[0], "\r\nCTOS_PYTHON\r\n");
            assertTrue(python, python.contains("Python 3.13.9"));
            assertTrue(python, python.replace("\r", "").contains("\n42\n"));
            Pty.write(process[0], "python3 -c 'import time; time.sleep(30)'\n".getBytes(StandardCharsets.UTF_8));
            SystemClock.sleep(300);
            Pty.write(process[0], new byte[]{3});
            readUntil(process[0], root ? "# " : "$ ");
            Pty.write(process[0], "printf '\\nCTOS_INTERRUPTED\\n'\n".getBytes(StandardCharsets.UTF_8));
            readUntil(process[0], "\r\nCTOS_INTERRUPTED\r\n");
            Pty.write(process[0], "exit\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, Pty.waitFor(process[1]));
        } finally { Pty.close(process[0]); }
    }

    private String readUntil(int fd, String marker) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        while (SystemClock.elapsedRealtime() < deadline) {
            byte[] data = Pty.read(fd);
            if (data == null) break;
            collected.write(data, 0, data.length);
            String output = new String(collected.toByteArray(), StandardCharsets.UTF_8);
            if (output.replace("\r", "").contains(marker.replace("\r", ""))) return output;
        }
        throw new AssertionError("PTY marker missing: " + new String(collected.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void appNetworkSnapshotWorksWithoutSystemBridge() throws Exception {
        JSONObject snapshot = NetworkSnapshot.collect(context());
        assertEquals(android.os.Process.myUid(), snapshot.getInt("uid"));
        assertTrue(snapshot.getJSONArray("networks").length() > 0);
        assertFalse(snapshot.has("module"));
        assertFalse(snapshot.has("moduleActive"));
        JSONObject interfaces = NetworkSnapshot.apiInterfaces(snapshot, "app / Android API");
        assertEquals("app / Android API", interfaces.getString("source"));
        assertTrue(interfaces.getJSONArray("interfaces").length() > 0);
        ConnectivityManager connectivity = context().getSystemService(ConnectivityManager.class);
        LinkProperties active = connectivity.getLinkProperties(connectivity.getActiveNetwork());
        assertNotNull("No active network", active);
        assertTrue(interfaces.toString(), interfaces.getString("routes").contains(active.getInterfaceName()));
    }
}
