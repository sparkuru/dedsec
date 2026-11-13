package im.majo.ctos;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assume;
import org.junit.Test;

/** Tests a refused loopback connection through the actual isolated Flutter SSH form. */
public final class SshUnavailableUiPlatformTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String TAG = "CtosSshUnavailableUi";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private String phase = "initial";

    @Test public void unavailableKeyboardEndpointShowsConnectionFailure() throws Exception {
        Assume.assumeTrue("Explicit isolated unavailable-endpoint UI run is required",
                "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshUnavailableUi")));
        Context target = instrumentation.getTargetContext();
        assertEquals(TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        PackageInfo info = target.getPackageManager().getPackageInfo(TARGET, 0);
        assertNull(info.sharedUserId);
        assertFalse(target.getSharedPreferences("root_access", Context.MODE_PRIVATE)
                .getBoolean("auto_start", true));
        File vault = new File(target.getNoBackupFilesDir(), "app-secrets.bin");
        for (String suffix : new String[]{"", ".bak", ".new"})
            assertFalse("Fresh isolated fixture is required", new File(vault.getPath() + suffix).exists());

        FileDescriptor reserved = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);
        try {
            // A bound TCP socket without listen reserves an exact loopback endpoint
            // while the real connector receives a genuine connection refusal.
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            Os.bind(reserved, loopback, 0);
            int port = ((InetSocketAddress) Os.getsockname(reserved)).getPort();
            assertTrue(port > 0 && port <= 65535);
            stage("native_tcp_refusal");
            SshTcpConnector connector = new SshTcpConnector();
            try {
                connector.connect(new SshPasswordProfile("127.0.0.1", port, "ctos-test"),
                        SystemClock.elapsedRealtime() + 10000, () -> { });
                fail("A reserved non-listening endpoint cannot connect");
            } catch (VaultException expected) {
                assertEquals("SSH_CONNECT", expected.code);
            } finally {
                connector.close();
            }

            UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(service);
            SshPasswordUiPlatformTest.Ui ui = new SshPasswordUiPlatformTest.Ui(automation);
            stage("create_vault");
            activity = instrumentation.startActivitySync(new Intent(target, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            stage("wait_terminal_entry");
            ui.waitText("终端", 15000);
            ui.click("终端");
            stage("wait_terminal_picker");
            ui.waitText("选择终端会话", 10000);
            ui.click("SSH 连接");
            stage("wait_vault_setup");
            ui.waitText("设置秘密库", 15000);
            ui.click("创建秘密库");
            stage("wait_vault_master");
            ui.waitField("主口令", 10000);
            String master = "Unavailable-Ui-Fixture-" + UUID.randomUUID();
            ui.setText("主口令", master, true);
            ui.setText("确认主口令", master, true);
            stage("submit_vault_setup");
            ui.click("创建秘密库");
            stage("wait_ssh_form");
            long deadline = SystemClock.elapsedRealtime() + 45000;
            while (SystemClock.elapsedRealtime() < deadline && !ui.has("连接目标")) {
                if (ui.has("开启本设备快捷解锁？")) ui.click("暂不开启");
                SystemClock.sleep(100);
            }
            ui.waitText("连接目标", 1000);
            stage("keyboard_form");
            ui.setText("主机", "127.0.0.1", false);
            ui.setText("端口", Integer.toString(port), false);
            ui.setText("用户名", "ctos-test", false);
            ui.click("认证方式");
            ui.waitText("选择认证方式", 10000);
            ui.click("服务器交互式认证");
            ui.click("连接");
            stage("wait_error");
            deadline = SystemClock.elapsedRealtime() + 20000;
            boolean refused = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ui.has("无法连接到主机，请检查地址、端口和网络。")) {
                    stage("specific_connect_error");
                    assertFalse("No host-key dialog is possible before a TCP connection",
                            ui.has("服务器主机密钥已变化"));
                    refused = true;
                    break;
                }
                if (ui.has("SSH 连接未完成，请检查地址和凭据后重试。")) {
                    stage("unexpected_generic_error");
                    fail("A connection refusal produced only the generic error");
                }
                SystemClock.sleep(100);
            }
            assertTrue("No connection failure was visible", refused);

            stage("handshake_disconnect");
            AtomicBoolean accepted = new AtomicBoolean();
            try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
                listener.setSoTimeout(15000);
                Thread peer = new Thread(() -> {
                    try (Socket socket = listener.accept()) { accepted.set(true); }
                    catch (java.io.IOException ignored) { }
                }, "ctos-unavailable-ui-peer");
                peer.start();
                try {
                    ui.setText("端口", Integer.toString(listener.getLocalPort()), false);
                    ui.click("连接");
                    deadline = SystemClock.elapsedRealtime() + 20000;
                    boolean explained = false;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (ui.has("SSH 连接协商或通信失败，请检查服务器状态和网络后重试。")) {
                            explained = true;
                            break;
                        }
                        if (ui.has("SSH 连接未完成，请检查地址和凭据后重试。")) {
                            stage("handshake_generic_error");
                            fail("A disconnected SSH peer produced only the generic error");
                        }
                        SystemClock.sleep(100);
                    }
                    assertTrue("The synthetic peer must accept the connection", accepted.get());
                    assertTrue("A disrupted SSH negotiation must be explained", explained);
                    assertFalse("A peer without a host key cannot trigger host review", ui.has("服务器主机密钥已变化"));
                    stage("pass");
                } finally {
                    listener.close();
                    peer.join(2000);
                }
            }
        } catch (Throwable failure) {
            Log.i(TAG, "failureClass=" + failure.getClass().getSimpleName());
            throw new AssertionError("Unavailable SSH UI fixture failed at phase: " + phase);
        } finally {
            Os.close(reserved);
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
        }
    }

    private void stage(String next) {
        phase = next;
        Log.i(TAG, "phase=" + next);
    }
}
