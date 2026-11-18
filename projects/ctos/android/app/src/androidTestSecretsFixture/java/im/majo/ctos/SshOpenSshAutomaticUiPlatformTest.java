package im.majo.ctos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/**
 * Opt-in default Flutter UI against two independently provisioned OpenSSH/PAM listeners.
 * ctosSshOpenSshAutomaticRequest names a shell/root-owned 0600 regular JSON file at
 * /data/local/tmp/ctos-ssh-openssh-auto-NAME.json. Fields: keyboardPort, passwordPort,
 * username, password, fingerprint and marker. Both listeners must use the same host key;
 * their methods must be KBI-only and password,KBI respectively. No server is started here.
 */
public final class SshOpenSshAutomaticUiPlatformTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String TAG = "CtosSshOpenSshAutoUi";
    private static final String HOST = "127.0.0.1";
    private static final String PROMPT = "输入登录密码";
    private static final String PASSWORD = "登录密码";
    private static final String CANCEL = "取消连接";
    private static final String CONNECT = "连接";
    private static final String CONTINUE = "继续";
    private static final String PICKER = "选择终端会话";
    private static final String FORM = "连接目标";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private SshPasswordUiPlatformTest.Ui ui;
    private String phase = "initial";
    private int initialSecure;

    @Test
    public void defaultKeyboardAndPasswordContinuationThroughFlutter() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("ctosSshOpenSshAutomaticRequest");
        Assume.assumeTrue("Explicit isolated OpenSSH UI request required", path != null);
        assertTrue("Invalid fixture request path",
                path.matches("/data/local/tmp/ctos-ssh-openssh-auto-[A-Za-z0-9_-]{1,64}\\.json"));
        Context target = instrumentation.getTargetContext();
        requireFreshIsolatedApp(target);
        UiAutomation automation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        ui = new SshPasswordUiPlatformTest.Ui(automation);
        try {
            stage("load_isolated_request");
            Request request = readRequest(automation, path);
            stage("create_isolated_vault");
            launchAndCreateVault(target);
            stage("keyboard_only_host_review");
            fillAndConnect(request, request.keyboardPort);
            acceptHost(request, request.keyboardPort);
            stage("keyboard_only_actual_questions");
            answerPam(request);
            assertShellAndClose(request.marker);
            openConnectionForm();
            stage("password_keyboard_host_review");
            fillAndConnect(request, request.passwordPort);
            acceptHost(request, request.passwordPort);
            stage("first_password");
            ui.waitText(PROMPT, 15000);
            ui.waitField(PASSWORD, 5000);
            assertFalse("The first-factor prompt must precede server questions", ui.has("服务器请求"));
            assertSecure();
            ui.setText(PASSWORD, request.password, true);
            ui.click(CONTINUE);
            stage("continued_actual_questions");
            answerPam(request);
            assertShellAndClose(request.marker + "_mfa");
            stage("pass");
        } catch (Throwable failure) {
            Log.i(TAG, "failureClass=" + failure.getClass().getSimpleName());
            int driverLine = Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().equals(getClass().getName()))
                    .mapToInt(StackTraceElement::getLineNumber).findFirst().orElse(-1);
            throw new AssertionError("OpenSSH automatic UI failed at phase " + phase + " driverLine=" + driverLine);
        } finally {
            if (activity != null) {
                try {
                    if (ui.has("确认主机密钥")) ui.tryClick("拒绝");
                    if (ui.has(CANCEL)) ui.tryClick(CANCEL);
                    if (ui.has("关闭会话")) ui.tryClick("关闭会话");
                } catch (RuntimeException cleanup) {
                    Log.i(TAG, "phase=ui_cleanup_deferred_to_activity");
                } finally {
                    instrumentation.runOnMainSync(activity::finish);
                }
            }
        }
    }

    private void acceptHost(Request request, int port) {
        ui.waitText("确认主机密钥", 15000);
        ui.waitText(HOST + ":" + port, 5000);
        ui.waitText(request.fingerprint, 5000);
        assertTrue("The host-key algorithm must be visible", ui.hasAlgorithm());
        assertFalse("Login credentials must follow host verification", ui.has(PROMPT));
        assertFalse("Server questions must follow host verification", ui.has("服务器请求"));
        assertSecure();
        assertTrue("The exact endpoint must remain visible", ui.has(HOST + ":" + port));
        assertTrue("The exact fingerprint must remain visible", ui.has(request.fingerprint));
        ui.click("信任并保存");
    }

    private void answerPam(Request request) {
        ui.waitText("服务器请求", 15000);
        ui.waitText("Password:", 5000);
        ui.waitField("回答 1", 5000);
        assertFalse("Server questions must use the KBI form", ui.has(PROMPT));
        assertFalse("An unanswered question must not open a terminal", ui.has("关闭会话"));
        assertSecure();
        ui.setText("回答 1", request.password, true);
        dismissInputMethodIfVisible();
        ui.click("提交回答");
        ui.waitText(CONTINUE, 15000);
        assertTrue("PAM's zero-prompt round must remain a server request", ui.has("服务器请求"));
        assertFalse("The previous prompt must be retired", ui.has("Password:"));
        assertFalse("A zero-prompt round must have no answer action", ui.has("提交回答"));
        assertFalse("The zero-prompt round must await an explicit action", ui.has("关闭会话"));
        assertSecure();
        ui.click(CONTINUE);
    }

    private void requireFreshIsolatedApp(Context target) throws Exception {
        assertEquals(TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        assertNull(target.getPackageManager().getPackageInfo(TARGET, 0).sharedUserId);
        assertFalse("Automatic Root startup must be disabled", target
                .getSharedPreferences("root_access", Context.MODE_PRIVATE)
                .getBoolean("auto_start", true));
        File vault = new File(target.getNoBackupFilesDir(), "app-secrets.bin");
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            assertFalse("Fresh isolated fixture data required", new File(vault.getPath() + suffix).exists());
        }
    }

    private void launchAndCreateVault(Context target) {
        activity = instrumentation.startActivitySync(
                new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui.waitText("终端", 15000);
        initialSecure = secureFlags();
        ui.click("终端");
        ui.waitText(PICKER, 10000);
        ui.click("SSH 连接");
        ui.waitText("设置秘密库", 15000);
        ui.click("创建秘密库");
        ui.waitField("主口令", 10000);
        assertSecure();
        String master = "Auto-Password-UI-Fixture-" + UUID.randomUUID();
        ui.setText("主口令", master, true);
        ui.setText("确认主口令", master, true);
        ui.click("创建秘密库");
        long deadline = SystemClock.elapsedRealtime() + 45000;
        for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
            if (ui.has("开启本设备快捷解锁？")) ui.tryClick("暂不开启");
            if (ui.has(FORM)) {
                assertSecure();
                ui.waitField("主机", 10000);
                return;
            }
            ui.sweep(attempt);
            SystemClock.sleep(100);
        }
        fail("The automatic SSH connection form did not become available");
    }

    private void openConnectionForm() {
        ui.waitText(PICKER, 10000);
        ui.click("SSH 连接");
        ui.waitText(FORM, 15000);
        assertSecure();
    }

    private void fillAndConnect(Request request, int port) {
        ui.waitText(FORM, 12000);
        ui.waitField("主机", 10000);
        assertFalse("Automatic entry must not request a password before connecting", ui.has(PROMPT));
        ui.setText("主机", HOST, false);
        ui.setText("端口", Integer.toString(port), false);
        ui.setText("用户名", request.username, false);
        ui.click(CONNECT);
    }

    private void assertShellAndClose(String marker) {
        ui.waitField("命令输入", 30000);
        assertFalse("The password prompt must dismiss on handoff", ui.has(PROMPT));
        waitSecure(initialSecure);
        String command = "printf '%s%s\\n' '" + marker.substring(0, marker.length() / 2)
                + "' '" + marker.substring(marker.length() / 2) + "'";
        assertFalse("A command echo must not satisfy output verification", command.contains(marker));
        ui.setText("命令输入", command, false);
        ui.click("执行命令");
        ui.assertRenderedOutput(marker);
        ui.click("关闭会话");
        ui.waitText(PICKER, 10000);
    }

    private int secureFlags() {
        AtomicInteger result = new AtomicInteger();
        instrumentation.runOnMainSync(() -> result.set(
                activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
        return result.get();
    }

    private void assertSecure() {
        assertTrue("The actual sensitive route must protect the Android window", secureFlags() != 0);
    }

    private void dismissInputMethodIfVisible() {
        UiAutomation automation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        boolean visible = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) visible = true;
            } finally {
                window.recycle();
            }
        }
        if (visible) {
            assertTrue("The visible input method must accept Back",
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK));
            SystemClock.sleep(300);
        }
    }

    private void waitSecure(int expected) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (secureFlags() == expected) return;
            SystemClock.sleep(100);
        }
        fail("The sensitive window did not return to its original protection state");
    }

    private void stage(String next) {
        phase = next;
        Log.i(TAG, "phase=" + next);
    }

    private static Request readRequest(UiAutomation automation, String path) throws Exception {
        byte[] ownership = shellBytes(automation, "stat -c %F:%a:%u " + path, 128);
        try {
            assertTrue("Fixture request must be owner-only and owned by shell or root",
                    new String(ownership, StandardCharsets.US_ASCII).trim().matches("regular file:600:(2000|0)"));
        } finally {
            Arrays.fill(ownership, (byte) 0);
        }

        byte[] bytes = shellBytes(automation, "cat " + path, 8192);
        try {
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            assertEquals("Unexpected request fields", 6, request.length());
            assertTrue("Ports must be JSON integers", request.opt("keyboardPort") instanceof Integer
                    && request.opt("passwordPort") instanceof Integer);
            for (String field : new String[]{"username", "password", "fingerprint", "marker"})
                assertTrue("Fixture text must be JSON strings", request.opt(field) instanceof String);
            int keyboardPort = request.getInt("keyboardPort");
            int passwordPort = request.getInt("passwordPort");
            String username = request.getString("username");
            String password = request.getString("password");
            String fingerprint = request.getString("fingerprint");
            String marker = request.getString("marker");
            assertTrue("Bounded distinct reverse ports are required", keyboardPort >= 15480 && keyboardPort <= 15579
                    && passwordPort >= 15480 && passwordPort <= 15579 && keyboardPort != passwordPort);
            assertTrue("The fixture username must be explicit", username.matches("[A-Za-z0-9_-]{1,32}"));
            assertTrue("The fixture password must be explicit", !password.isEmpty() && password.length() <= 128);
            assertTrue("An exact public fingerprint is required", fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
            assertTrue("A bounded public server marker is required", marker.matches("[A-Za-z0-9_-]{8,64}"));
            return new Request(keyboardPort, passwordPort, username, password, fingerprint, marker);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] shellBytes(UiAutomation automation, String command, int limit) throws Exception {
        byte[] scratch = new byte[limit + 1];
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            int length = 0;
            int count;
            while ((count = input.read(scratch, length, scratch.length - length)) >= 0) {
                length += count;
                assertTrue("Fixture request exceeded its byte limit", length <= limit);
            }
            return Arrays.copyOf(scratch, length);
        } finally {
            Arrays.fill(scratch, (byte) 0);
        }
    }

    private static final class Request {
        final int keyboardPort, passwordPort;
        final String username;
        final String password;
        final String fingerprint;
        final String marker;

        Request(int keyboardPort, int passwordPort, String username, String password, String fingerprint, String marker) {
            this.keyboardPort = keyboardPort;
            this.passwordPort = passwordPort;
            this.username = username;
            this.password = password;
            this.fingerprint = fingerprint;
            this.marker = marker;
        }
    }
}
