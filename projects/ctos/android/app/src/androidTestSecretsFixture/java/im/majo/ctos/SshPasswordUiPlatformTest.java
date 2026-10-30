package im.majo.ctos;

import static org.junit.Assert.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Actual Flutter controls only: no channel invocation, reflection, event substitution or clipboard. */
public final class SshPasswordUiPlatformTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String TAG = "CtosSshUiFixture";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Activity activity;
    private UiAutomation automation;
    private Ui ui;
    private String phase = "initial";

    @Test public void actualFlutterPasswordFormTrustAndShell() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("ctosSshPasswordRequestFile");
        Assume.assumeTrue("An explicit independent synthetic SSH request was not provided", path != null);
        assertTrue("Invalid fixture request path", path.matches("/data/local/tmp/ctos-ssh-password-[A-Za-z0-9_-]{1,64}\\.json"));
        Context target = instrumentation.getTargetContext();
        assertEquals(TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        PackageInfo info = target.getPackageManager().getPackageInfo(TARGET, 0);
        assertNull("The fixture must not share an application UID", info.sharedUserId);
        assertFalse("Root auto-start must remain disabled", target.getSharedPreferences("root_access", Context.MODE_PRIVATE)
                .getBoolean("auto_start", true));
        File vault = new File(target.getNoBackupFilesDir(), "app-secrets.bin");
        for (String suffix : new String[]{"", ".bak", ".new"})
            assertFalse("Fresh isolated fixture data is required", new File(vault.getPath() + suffix).exists());
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        ui = new Ui(automation);
        JSONObject request = readRequest(path);
        try {
            String host = publicString(request, "host"), username = publicString(request, "username");
            String fingerprint = publicString(request, "fingerprint");
            assertTrue("Explicit complete expected fingerprint is required", fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
            assertTrue("Synthetic password is required", request.opt("password") instanceof String);
            int port = request.getInt("port");
            assertTrue("Synthetic endpoint port is invalid", port > 0 && port <= 65535);
            stage("launch");
            activity = instrumentation.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ui.waitText("终端", 15000);
            int initialSecure = secureFlags();
            stage("terminal_picker");
            ui.click("终端"); ui.waitText("选择终端会话", 10000); ui.click("SSH 连接");
            stage("create_vault");
            ui.waitText("设置秘密库", 15000); ui.click("创建秘密库");
            ui.waitField("主口令", 10000); assertSecure();
            String master = "SSH-UI-Fixture-" + UUID.randomUUID();
            ui.setText("主口令", master, true); ui.setText("确认主口令", master, true);
            ui.click("创建秘密库");
            long readyDeadline = SystemClock.elapsedRealtime() + 45000;
            while (SystemClock.elapsedRealtime() < readyDeadline && !ui.has("保存主机信息；密码仅本次使用。")) {
                if (ui.has("开启本设备快捷解锁？")) ui.click("暂不开启");
                SystemClock.sleep(100);
            }
            stage("ssh_form");
            ui.waitText("保存主机信息；密码仅本次使用。", 1000);
            ui.waitField("主机", 10000); assertSecure();
            ui.setText("主机", host, false); ui.setText("端口", Integer.toString(port), false);
            ui.setText("用户名", username, false); ui.setText("登录密码", request.getString("password"), true);
            assertSecure();
            ui.click("连接");
            stage("exact_host_review");
            ui.waitText("确认主机密钥", 15000);
            ui.waitText(host + ":" + port, 3000); ui.waitText(fingerprint, 3000);
            assertTrue("The actual host-key algorithm must be visible", ui.hasAlgorithm());
            // Check the complete public binding again immediately before the real button action.
            assertTrue("The exact offered endpoint changed", ui.has(host + ":" + port));
            assertTrue("The exact offered fingerprint changed", ui.has(fingerprint));
            ui.click("信任并保存");
            stage("terminal_running");
            ui.waitText("ssh", 30000); ui.waitField("命令输入", 10000);
            assertFalse("The password form must leave after the actual terminal handoff", ui.has("保存主机信息；密码仅本次使用。"));
            waitSecureFlags(initialSecure);
            stage("actual_command_input");
            String first = UUID.randomUUID().toString().substring(0, 8);
            String second = UUID.randomUUID().toString().substring(0, 8);
            String marker = first + second;
            String command = "printf '%s%s\\n' '" + first + "' '" + second + "'";
            assertFalse("A plain command echo must not satisfy the output assertion", command.contains(marker));
            ui.setText("命令输入", command, false); ui.click("执行命令");
            stage("rendered_output");
            ui.assertRenderedOutput(marker);
            stage("close_own_session");
            ui.click("关闭会话"); ui.waitText("选择终端会话", 10000);
            stage("pass");
        } catch (Throwable failure) {
            // Never attach raw input/JSON/framework exception payloads to instrumentation output.
            Log.i(TAG, "failureClass=" + failure.getClass().getSimpleName());
            throw new AssertionError("Actual SSH UI fixture failed at phase: " + phase);
        } finally {
            request.remove("password");
            if (activity != null) {
                try {
                    if (ui.has("确认主机密钥")) ui.tryClick("拒绝");
                    if (ui.has("取消连接")) ui.tryClick("取消连接");
                    if (ui.has("关闭会话")) ui.tryClick("关闭会话");
                } catch (Throwable cleanup) { Log.i(TAG, "phase=ui_cleanup_deferred_to_activity"); }
                finally { instrumentation.runOnMainSync(activity::finish); }
            }
        }
    }

    private int secureFlags() {
        AtomicInteger result = new AtomicInteger();
        instrumentation.runOnMainSync(() -> result.set(activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
        return result.get();
    }
    private void assertSecure() { assertTrue("The actual sensitive route must protect the native window", secureFlags() != 0); }
    private void waitSecureFlags(int expected) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            if (secureFlags() == expected) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The actual route did not release its native window protection");
    }
    private void stage(String next) { phase = next; Log.i(TAG, "phase=" + next); }
    private static String publicString(JSONObject request, String key) throws Exception {
        assertTrue("Required public fixture metadata is missing", request.opt(key) instanceof String);
        return request.getString(key);
    }
    private JSONObject readRequest(String path) throws Exception {
        byte[] metadata = shellBytes("stat -c %a:%u " + path, 128);
        try {
            String ownership = new String(metadata, StandardCharsets.US_ASCII).trim();
            assertTrue("Fixture request must be owner-only and owned by shell/root", ownership.matches("600:(2000|0)"));
        } finally { VaultCodec.wipe(metadata); }
        byte[] bytes = shellBytes("cat " + path, 16384);
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (org.json.JSONException failure) { throw new AssertionError("Invalid synthetic SSH fixture request"); }
        finally { VaultCodec.wipe(bytes); }
    }
    private byte[] shellBytes(String command, int maximum) throws Exception {
        byte[] scratch = new byte[maximum + 1];
        try (ParcelFileDescriptor fd = automation.executeShellCommand(command);
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            int length = 0, count;
            while ((count = input.read(scratch, length, scratch.length - length)) >= 0) {
                length += count; assertTrue("Fixture input exceeded its bounded size", length <= maximum);
            }
            return java.util.Arrays.copyOf(scratch, length);
        } finally { VaultCodec.wipe(scratch); }
    }

    private interface Match { boolean accepts(AccessibilityNodeInfo node); }
    static final class Ui {
        private final UiAutomation automation;
        Ui(UiAutomation automation) { this.automation = automation; }
        void assertRenderedOutput(String marker) {
            boolean observed = false;
            long outputDeadline = SystemClock.elapsedRealtime() + 15000;
            for (int attempt = 0; attempt < 4 && SystemClock.elapsedRealtime() < outputDeadline; attempt++) {
                SystemClock.sleep(250); click("选择输出"); waitText("当前输出", 5000);
                observed = waitPublicSubstring(marker, 1500);
                backToTerminal();
                if (observed) break;
            }
            assertTrue("The real rendered SSH output did not contain the independently executed split marker", observed);
        }
        private void backToTerminal() {
            assertTrue("Back must start on the actual output snapshot route", has("当前输出"));
            assertTrue("The actual AppBar Back control must accept its advertised action",
                    tryClick("Back") || tryClick("返回"));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                if (!has("当前输出") && has("选择输出")) return;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            fail("The actual output route did not return to the terminal");
        }
        private AccessibilityNodeInfo find(Match match, boolean unique) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return null;
            if (!TARGET.contentEquals(String.valueOf(root.getPackageName()))) { root.recycle(); return null; }
            int window = root.getWindowId();
            List<AccessibilityNodeInfo> nodes = new ArrayList<>(); nodes.add(root);
            AccessibilityNodeInfo result = null;
            try {
                for (int index = 0; index < nodes.size() && index < 1500; index++) {
                    AccessibilityNodeInfo node = nodes.get(index);
                    if (node.getWindowId() == window && TARGET.contentEquals(String.valueOf(node.getPackageName()))
                            && node.isVisibleToUser() && match.accepts(node)) {
                        if (!unique) return AccessibilityNodeInfo.obtain(node);
                        if (result != null) { result.recycle(); return null; }
                        result = AccessibilityNodeInfo.obtain(node);
                    }
                    for (int child = 0; child < node.getChildCount(); child++) {
                        if (nodes.size() >= 1500) { if (result != null) result.recycle(); return null; }
                        AccessibilityNodeInfo next = node.getChild(child); if (next != null) nodes.add(next);
                    }
                }
                return result;
            } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
        }
        private static boolean exact(AccessibilityNodeInfo node, String expected) {
            for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription(), node.getHintText(), node.getTooltipText()}) {
                if (value == null) continue;
                for (String part : value.toString().split("\\n|, ")) if (part.trim().equals(expected)) return true;
            }
            return false;
        }
        private static boolean field(AccessibilityNodeInfo node, String label) {
            for (CharSequence value : new CharSequence[]{node.getHintText(), node.getContentDescription()}) {
                if (value == null) continue;
                String text = value.toString().trim();
                if (text.equals(label) || text.startsWith(label + "\n") || text.startsWith(label + ", ")) return true;
            }
            return false;
        }
        boolean has(String label) {
            AccessibilityNodeInfo node = find(n -> !n.isEditable() && exact(n, label), false);
            if (node == null) return false;
            node.recycle(); return true;
        }
        boolean hasAlgorithm() {
            AccessibilityNodeInfo node = find(n -> !n.isEditable()
                    && (algorithm(n.getText()) || algorithm(n.getContentDescription())), false);
            if (node == null) return false;
            node.recycle(); return true;
        }
        private static boolean algorithm(CharSequence value) {
            if (value == null || value.length() > 512) return false;
            for (String component : value.toString().split("\\n|, ")) {
                String name = component.trim();
                if (name.matches("[A-Za-z0-9@._+\\-]{1,64}")
                        && (name.startsWith("ssh-") || name.startsWith("ecdsa-") || name.startsWith("sk-"))) return true;
            }
            return false;
        }
        void waitText(String label, long timeout) {
            long deadline = SystemClock.elapsedRealtime() + timeout;
            for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
                if (has(label)) return;
                sweep(attempt); SystemClock.sleep(100);
            }
            fail("Required public UI phase unavailable: " + label);
        }
        void waitField(String label, long timeout) {
            AccessibilityNodeInfo node = editor(label, timeout);
            assertNotNull("Required editable UI control unavailable", node); node.recycle();
        }
        private AccessibilityNodeInfo editor(String label, long timeout) {
            long deadline = SystemClock.elapsedRealtime() + timeout;
            for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
                AccessibilityNodeInfo node = find(n -> n.isEditable() && n.isEnabled() && field(n, label), true);
                if (node != null) return node;
                sweep(attempt); SystemClock.sleep(100);
            }
            return null;
        }
        void click(String label) {
            long deadline = SystemClock.elapsedRealtime() + 12000;
            for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
                if (tryClick(label)) return;
                sweep(attempt); SystemClock.sleep(100);
            }
            fail("Required control did not accept its advertised click: " + label);
        }
        boolean tryClick(String label) {
            AccessibilityNodeInfo node = find(n -> !n.isEditable() && n.isEnabled() && exact(n, label)
                    && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK), true);
            if (node == null) return false;
            try { return node.refresh() && node.isVisibleToUser() && node.isEnabled()
                    && exact(node, label) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
            finally { node.recycle(); }
        }
        void setText(String label, String value, boolean secret) {
            AccessibilityNodeInfo node = editor(label, 12000);
            assertNotNull("The exact labelled input must be visible", node);
            Bundle arguments = new Bundle();
            try {
                assertEquals("The real editor must expose the expected password masking flag", secret, node.isPassword());
                if (node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS))
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!node.isFocused() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                    assertTrue("The editor rejected its own focus action", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                long deadline = SystemClock.elapsedRealtime() + 5000;
                boolean focused = false;
                do {
                    if (node.refresh() && node.isFocused()
                            && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                        focused = true; break;
                    }
                    SystemClock.sleep(100);
                } while (SystemClock.elapsedRealtime() < deadline);
                assertTrue("Advertised set-text requires actual editor focus", focused);
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                assertTrue("The actual editor rejected set-text", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                deadline = SystemClock.elapsedRealtime() + 5000; int stable = 0;
                do {
                    if (node.refresh() && valueMatches(node, value, secret, label.equals("命令输入"))) {
                        if (++stable >= 3) return;
                    } else stable = 0;
                    SystemClock.sleep(100);
                } while (SystemClock.elapsedRealtime() < deadline);
                fail("The actual editor did not retain its input");
            } finally { arguments.clear(); node.recycle(); }
        }
        private static boolean valueMatches(AccessibilityNodeInfo node, String expected, boolean secret, boolean terminal) {
            CharSequence actual = node.getText();
            if (actual == null) return false;
            if (secret) return node.isPassword() && actual.length() == expected.length() && !expected.contentEquals(actual);
            String text = actual.toString();
            return expected.equals(terminal ? text.replace("\u200b", "") : text);
        }
        boolean waitPublicSubstring(String expected, long timeout) {
            long deadline = SystemClock.elapsedRealtime() + timeout;
            for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
                assertTrue("Public output observation must remain on the actual snapshot route", has("当前输出"));
                // Read-only Flutter selection nodes can report editable. Exclude writable
                // editors by their real actions; the current snapshot route remains mandatory.
                AccessibilityNodeInfo node = find(n -> !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
                        && (contains(n.getText(), expected) || contains(n.getContentDescription(), expected)), false);
                if (node != null) { node.recycle(); return true; }
                sweep(attempt); SystemClock.sleep(100);
            }
            return false;
        }
        private static boolean contains(CharSequence value, String expected) {
            return value != null && value.toString().contains(expected);
        }
        private void sweep(int attempt) {
            if (attempt % 5 != 0) return;
            AccessibilityNodeInfo.AccessibilityAction action = attempt % 30 == 0
                    ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;
            AccessibilityNodeInfo node = find(n -> !n.isEditable() && n.isEnabled() && n.isScrollable()
                    && n.getActionList().contains(action), true);
            if (node == null) return;
            try {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (bounds.width() >= 64 && bounds.height() >= 64 && node.refresh()) node.performAction(action.getId());
            } finally { node.recycle(); }
        }
    }
}
