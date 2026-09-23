package im.majo.ctos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import androidx.core.content.ContextCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DeviceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test(timeout = 45000) public void rootCountersAndConnections() throws Exception {
        JSONObject identity = Collector.authorizeRoot();
        assertEquals(identity.toString(), 0, identity.getInt("exit"));
        assertTrue(identity.getString("output").contains("uid=0("));
        JSONObject snapshot = Collector.interfaces(true);
        assertEquals(snapshot.toString(), 0, snapshot.getInt("exit"));
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

    @Test(timeout = 30000) public void appAndRootPtyAreInteractive() throws Exception {
        verifyPty(false);
        verifyPty(true);
    }

    private void verifyPty(boolean root) throws Exception {
        int[] process = Pty.start(root, 70, 20, context().getFilesDir().getAbsolutePath());
        try {
            // Magisk initializes a second PTY and flushes input before its first prompt.
            readUntil(process[0], root ? "# " : "$ ");
            Pty.resize(process[0], 83, 27);
            Pty.write(process[0], "id; tty; stty size; printf '\\nCTOS_READY\\n'\n".getBytes(StandardCharsets.UTF_8));
            String output = readUntil(process[0], "\r\nCTOS_READY\r\n");
            assertTrue(output, output.contains(root ? "uid=0(" : "uid=" + android.os.Process.myUid() + "("));
            assertTrue(output, output.matches("(?s).*[/]pts[/][0-9]+.*"));
            assertTrue(output, output.contains("27 83"));
            Pty.write(process[0], "sleep 30\n".getBytes(StandardCharsets.UTF_8));
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

    @Test public void vectorBridgeReturnsSystemIdentity() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        String nonce = UUID.randomUUID().toString();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.equals(intent.getStringExtra("nonce"))) return;
                if (Build.VERSION.SDK_INT >= 34 && getSentFromUid() != 1000) return;
                try { result.set(new JSONObject(intent.getStringExtra("snapshot"))); received.countDown(); }
                catch (Exception error) { throw new AssertionError(error); }
            }
        };
        ContextCompat.registerReceiver(context(), receiver, new IntentFilter("im.majo.ctos.RESULT"),
                "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED);
        try {
            context().sendBroadcast(new Intent("im.majo.ctos.QUERY").setPackage("android").putExtra("nonce", nonce));
            assertTrue("Vector bridge did not answer. Enable system scope and reboot.", received.await(8, TimeUnit.SECONDS));
            assertEquals(1000, result.get().getInt("uid"));
            assertEquals("Vector / system_server", result.get().getString("source"));
            assertTrue(result.get().getJSONArray("networks").length() > 0);
        } finally { context().unregisterReceiver(receiver); }
    }
}
