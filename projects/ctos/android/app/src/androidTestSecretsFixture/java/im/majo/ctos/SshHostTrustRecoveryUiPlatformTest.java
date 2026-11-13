package im.majo.ctos;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

/** Exercises the real protected Flutter route against a disposable changing-key peer. */
public final class SshHostTrustRecoveryUiPlatformTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String TAG = "CtosHostRecoveryUi";
    private static final String USER = "ctos-test";
    private static final String PASSWORD = "ctos-test-password";
    private static final String ANSWER = "ctos-test-code";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private SshPasswordUiPlatformTest.Ui ui;
    private String phase = "initial";
    private boolean keyboard;

    @Test public void changedHostCanBeReviewedForgottenAndRetriedThroughRealUi() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("An explicit synthetic changed-host UI run was not requested",
                "true".equals(arguments.getString("ctosHostRecoveryUi")));
        String oldFingerprint = arguments.getString("ctosOldFingerprint", "");
        String newFingerprint = arguments.getString("ctosNewFingerprint", "");
        int port = Integer.parseInt(arguments.getString("ctosHostRecoveryPort", "0"));
        keyboard = "true".equals(arguments.getString("ctosRecoveryKeyboard"));
        assertTrue("A bounded synthetic reverse port is required", port >= 15480 && port <= 15579);
        assertTrue("An exact old fingerprint is required", oldFingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
        assertTrue("An exact new fingerprint is required", newFingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
        assertNotEquals("The synthetic host key must actually change", oldFingerprint, newFingerprint);

        Context target = instrumentation.getTargetContext();
        assertEquals(TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        PackageInfo info = target.getPackageManager().getPackageInfo(TARGET, 0);
        assertNull("The isolated fixture must not share an application UID", info.sharedUserId);
        assertFalse("Root auto-start must remain disabled", target.getSharedPreferences("root_access", Context.MODE_PRIVATE)
                .getBoolean("auto_start", true));
        File vault = new File(target.getNoBackupFilesDir(), "app-secrets.bin");
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            assertFalse("Fresh isolated fixture data is required", new File(vault.getPath() + suffix).exists());
        }

        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        ui = new SshPasswordUiPlatformTest.Ui(automation);
        try {
            stage("launch");
            activity = instrumentation.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ui.waitText("终端", 15000);
            ui.click("终端");
            ui.waitText("选择终端会话", 10000);
            ui.click("SSH 连接");

            stage("create_isolated_vault");
            ui.waitText("设置秘密库", 15000);
            stage("choose_vault_creation");
            ui.click("创建秘密库");
            stage("wait_master_field");
            ui.waitField("主口令", 10000);
            assertSecure();
            String master = "Host-Recovery-Fixture-" + UUID.randomUUID();
            stage("enter_master");
            ui.setText("主口令", master, true);
            stage("confirm_master");
            ui.setText("确认主口令", master, true);
            stage("submit_vault_creation");
            ui.click("创建秘密库");
            stage("wait_connection_form");
            waitForSshForm();
            assertSecure();

            stage("form_hierarchy");
            ui.waitText("连接资料与密码管理", 10000);
            ui.click("连接资料与密码管理");
            ui.waitText("私钥与配置", 5000);
            ui.waitText("忘记全部 SSH 密码", 5000);
            ui.click("连接资料与密码管理");

            stage("first_host_key");
            ui.setText("主机", "127.0.0.1", false);
            ui.setText("端口", Integer.toString(port), false);
            ui.setText("用户名", USER, false);
            selectKeyboardIfRequested();
            connect();
            ui.waitText("确认主机密钥", 15000);
            ui.waitText("127.0.0.1:" + port, 5000);
            ui.waitText(oldFingerprint, 5000);
            ui.click("信任并保存");
            answerKeyboardIfRequested();
            ui.waitField("命令输入", 30000);
            ui.click("关闭会话");
            ui.waitText("选择终端会话", 10000);
            ui.click("SSH 连接");
            waitForSshForm();
            selectKeyboardIfRequested();

            stage("changed_cancel");
            connect();
            ui.waitText("服务器主机密钥已变化", 15000);
            ui.waitText("127.0.0.1:" + port, 5000);
            ui.click("取消");
            waitForSshForm();

            stage("changed_view");
            connect();
            ui.waitText("服务器主机密钥已变化", 15000);
            ui.click("查看旧指纹");
            ui.waitText("主机信任", 10000);
            ui.waitText(oldFingerprint, 10000);
            ui.click("返回");
            waitForSshForm();

            stage("changed_forget_cancel");
            connect();
            ui.waitText("服务器主机密钥已变化", 15000);
            ui.click("忘记原有指纹");
            ui.waitText("忘记这些旧指纹？", 15000);
            ui.waitText(oldFingerprint, 5000);
            assertFalse("Forgetting must not navigate to the host trust page", ui.has("主机信任"));
            ui.click("取消");
            waitForSshForm();

            stage("changed_forget_confirm");
            connect();
            ui.waitText("服务器主机密钥已变化", 15000);
            ui.click("忘记原有指纹");
            ui.waitText("忘记这些旧指纹？", 15000);
            ui.waitText(oldFingerprint, 5000);
            assertFalse("Forgetting must remain in the connection context", ui.has("主机信任"));
            ui.click("忘记旧指纹");
            ui.waitText("已忘记旧指纹，请重新连接并核对新指纹。", 10000);
            ui.click("知道了");
            waitForSshForm();

            stage("new_host_key");
            connect();
            ui.waitText("确认主机密钥", 15000);
            ui.waitText("127.0.0.1:" + port, 5000);
            ui.waitText(newFingerprint, 5000);
            ui.click("信任并保存");
            answerKeyboardIfRequested();
            ui.waitField("命令输入", 30000);
            ui.click("关闭会话");
            ui.waitText("选择终端会话", 10000);
            stage("pass");
        } catch (Throwable failure) {
            Log.i(TAG, "failureClass=" + failure.getClass().getSimpleName());
            throw new AssertionError("Actual host recovery UI failed at phase: " + phase, failure);
        } finally {
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void readOwnFixtureStateForReconciliation() throws Exception {
        Context target = instrumentation.getTargetContext();
        assertEquals(TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        boolean vault = false, journal = false;
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            vault |= new File(target.getNoBackupFilesDir(), "app-secrets.bin" + suffix).exists();
            journal |= new File(target.getNoBackupFilesDir(), "vault-export-journal.json" + suffix).exists();
        }
        Log.i(TAG, "own_state vaultPresent=" + vault + " journalPresent=" + journal
                + " persistedGrantCount=" + target.getContentResolver().getPersistedUriPermissions().size());
        assertFalse("An owned export journal must be reconciled", journal);
    }

    private void waitForSshForm() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        int attempt = 0;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (ui.has("开启本设备快捷解锁？")) ui.tryClick("暂不开启");
            if (ui.has("连接目标")) {
                ui.waitField("主机", 10000);
                return;
            }
            ui.sweep(attempt++);
            android.os.SystemClock.sleep(100);
        }
        fail("The real SSH form was not available");
    }

    private void selectKeyboardIfRequested() {
        if (!keyboard) return;
        ui.click("认证方式");
        ui.waitText("选择认证方式", 10000);
        ui.click("服务器交互式认证");
    }

    private void connect() {
        if (!keyboard) {
            ui.waitField("登录密码", 10000);
            ui.setText("登录密码", PASSWORD, true);
        }
        ui.click("连接");
    }

    private void answerKeyboardIfRequested() {
        if (!keyboard) return;
        ui.waitField("回答 1", 15000);
        ui.setText("回答 1", ANSWER, true);
        ui.click("提交回答");
    }

    private void assertSecure() {
        AtomicInteger result = new AtomicInteger();
        instrumentation.runOnMainSync(() -> result.set(activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
        assertTrue("Sensitive UI must protect the native window", result.get() != 0);
    }

    private void stage(String next) {
        phase = next;
        Log.i(TAG, "phase=" + next);
    }
}
