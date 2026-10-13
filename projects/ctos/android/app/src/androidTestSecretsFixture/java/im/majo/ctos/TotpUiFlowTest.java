package im.majo.ctos;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real release Flutter TOTP controls in a fresh independent UID; no business channel injection. */
public final class TotpUiFlowTest {
    private static final String TARGET = TotpFixtureUi.TARGET;
    private static final String PUBLIC_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context target;
    private UiAutomation automation;
    private TotpFixtureUi ui;
    private Activity activity;
    private AppSecrets core;
    private String runId, password, account, renamed, imageName, inspectionStage;
    private File marker;
    private Uri image;
    private byte[] imageDigest;
    private boolean imageRequested, clipboardRequested, inspectionRequested, pickerInspectionRequested, successful;

    @Before public void setup() throws Exception {
        String inspect = InstrumentationRegistry.getArguments().getString("ctosTotpInspectOwnedFailure");
        assertTrue("Invalid inspection opt-in", inspect == null || "true".equals(inspect) || "false".equals(inspect));
        inspectionRequested = "true".equals(inspect);
        String pickerOption = InstrumentationRegistry.getArguments().getString("ctosTotpInspectPicker");
        assertTrue("Invalid read-only picker inspection opt-in", pickerOption == null || "true".equals(pickerOption) || "false".equals(pickerOption));
        pickerInspectionRequested = "true".equals(pickerOption);
        assertTrue("Picker inspection is allowed only in the selected read-only inspector", !pickerInspectionRequested || inspectionRequested);
        String optIn = InstrumentationRegistry.getArguments().getString("ctosTotpUiFixture");
        org.junit.Assume.assumeTrue("Real TOTP fixture was not explicitly requested", inspectionRequested || optIn != null);
        if (!inspectionRequested) assertTrue("Explicit TOTP UI opt-in must be true", "true".equals(optIn));
        String method = inspectionRequested ? "inspectOwnedUiFailureSnapshot" : "freshFixtureTotpUiFlow";
        assertTrue("Fixture must run only its explicitly selected method", (getClass().getName() + "#" + method)
                .equals(InstrumentationRegistry.getArguments().getString("class")));
        imageRequested = "true".equals(InstrumentationRegistry.getArguments().getString("ctosTotpImageUi"));
        String imageOption = InstrumentationRegistry.getArguments().getString("ctosTotpImageUi");
        assertTrue("Invalid image opt-in", imageOption == null || "true".equals(imageOption) || "false".equals(imageOption));
        if (imageRequested) assertTrue("Owned MediaStore image fixture requires API29+", Build.VERSION.SDK_INT >= 29);
        String clipboardOption = InstrumentationRegistry.getArguments().getString("ctosTotpClipboardCopy");
        assertTrue("Invalid clipboard opt-in", clipboardOption == null || "true".equals(clipboardOption) || "false".equals(clipboardOption));
        clipboardRequested = "true".equals(clipboardOption);
        target = instrumentation.getTargetContext();
        assertTrue("Wrong target package", TARGET.equals(target.getPackageName()));
        assertTrue("Wrong test package", (TARGET + ".test").equals(instrumentation.getContext().getPackageName()));
        assertTrue("Wrong target UID", target.getApplicationInfo().uid == Process.myUid());
        assertTrue("Fixture cannot use a system UID", Process.myUid() >= Process.FIRST_APPLICATION_UID);
        PackageInfo own = target.getPackageManager().getPackageInfo(TARGET, 0);
        assertNull("Shared UID cannot isolate test state", own.sharedUserId);
        assertNull("Test package cannot use a shared UID", target.getPackageManager().getPackageInfo(TARGET + ".test", 0).sharedUserId);
        assertTrue("Fixture Application must disable Root before launch", target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse("Fixture Root startup must remain disabled", target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        assertTrue("Production and fixture UIDs must differ", target.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid != Process.myUid());
        if (inspectionRequested) {
            assertFalse("Inspection may not enable image or clipboard effects", imageRequested || clipboardRequested);
            runId = InstrumentationRegistry.getArguments().getString("ctosTotpOwnedRunId");
            inspectionStage = InstrumentationRegistry.getArguments().getString("ctosTotpOwnedStage");
            assertTrue("Inspection requires the recorded exact run and stage", ("manual".equals(inspectionStage) && "7756af71-aa02-49cc-b389-49796f510ca6".equals(runId)
                    || "renamed".equals(inspectionStage) && ("fb90694e-4460-47f4-bea4-7bd0b6813027".equals(runId)
                            || "194d0477-054a-41ca-93a4-db37b9bc9072".equals(runId)
                            || "1162c6aa-3d2e-4d85-a0cc-46f1ca7107e3".equals(runId)
                            || "cf2acdd4-9979-4f1a-8e88-38e609ac9f9f".equals(runId)
                            || "7320559f-979b-42bc-b49d-2f3ed6df4be0".equals(runId)
                            || "189af0eb-91aa-4d6f-9e51-4006db65f3b9".equals(runId)
                            || "cadf6c22-0fdb-4289-a5e2-f3fa873f0fd7".equals(runId))
                    || "image_commit".equals(inspectionStage) && ("fe187afc-3053-4121-b465-bbefd4d89b93".equals(runId)
                            || "864d391c-bfdf-43a1-b9c6-26a151e27ab3".equals(runId)))
                    && runId.equals(UUID.fromString(runId).toString()));
            password = "TotpFixture-" + runId; account = "alice-" + runId.substring(0, 8); renamed = "Renamed-" + runId.substring(0, 8);
            marker = new File(target.getNoBackupFilesDir(), "totp-ui-flow-owned.marker");
            assertTrue("Only the recorded fixture's existing empty marker may be inspected", marker.isFile() && marker.length() == 0);
            assertFalse("Other fixture ownership cannot be inspected", new File(target.getNoBackupFilesDir(), "secrets-ui-flow-owned.marker").exists());
            assertTrue("Inspection requires an existing own vault", new File(target.getNoBackupFilesDir(), "app-secrets.bin").isFile());
            for (String file : new String[]{"app-secrets.bin.bak", "app-secrets.bin.new", "vault-export-journal.json", "vault-export-journal.json.bak", "vault-export-journal.json.new"})
                assertFalse("Pending or unrelated fixture state requires separate reconciliation", new File(target.getNoBackupFilesDir(), file).exists());
            assertNull("This failed pre-copy run cannot have quick enrollment", new VaultStore(target).quickMaterial());
            assertTrue("This failed pre-image run cannot hold persistent grants", target.getContentResolver().getPersistedUriPermissions().isEmpty());
            return;
        }
        for (String file : new String[]{"app-secrets.bin", "vault-export-journal.json"})
            for (String suffix : new String[]{"", ".bak", ".new"}) assertFalse("Existing fixture state needs reconciliation", new File(target.getNoBackupFilesDir(), file + suffix).exists());
        for (String file : new String[]{"secrets-ui-flow-owned.marker", "totp-ui-flow-owned.marker"})
            assertFalse("Old fixture marker requires reconciliation", new File(target.getNoBackupFilesDir(), file).exists());
        assertTrue("No preexisting persistent grants are allowed", target.getContentResolver().getPersistedUriPermissions().isEmpty());
        runId = UUID.randomUUID().toString();
        password = "TotpFixture-" + runId;
        account = "alice-" + runId.substring(0, 8);
        renamed = "Renamed-" + runId.substring(0, 8);
        marker = new File(target.getNoBackupFilesDir(), "totp-ui-flow-owned.marker");
        assertTrue("Refuse reused fixture ownership", marker.createNewFile());
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        ui = new TotpFixtureUi(automation);
        phase("fresh_independent_uid_preconditions_pass");
    }

    @After public void cleanup() throws Exception {
        try {
            if (image != null) {
                requireOwnedImage();
                assertTrue("Only the exact newly inserted image may be removed", target.getContentResolver().delete(image, null, null) == 1);
                try (Cursor rows = target.getContentResolver().query(image, new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                    assertNotNull("Exact owned media deletion needs a successful verification query", rows);
                    assertTrue("Owned image must be absent after deletion", rows.getCount() == 0);
                }
                image = null;
                phase("exact_owned_media_image_removed");
            }
        } finally {
            VaultCodec.wipe(imageDigest);
            if (ui != null) ui.close();
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            if (core != null) core.lock();
            password = null;
            // The marked own UID empty vault (or failure state) remains for the
            // coordinating session to reconcile before uninstalling this fixture.
            if (runId != null) phase(inspectionRequested ? "inspection_finished_vault_retained_unchanged"
                    : successful ? "complete_empty_fixture_ready_for_uninstall" : "failure_fixture_retained_for_reconciliation");
        }
    }

    @Test(timeout = 360000) public void freshFixtureTotpUiFlow() throws Exception {
        assertFalse("Inspection cannot execute the actual UI flow", inspectionRequested);
        activity = instrumentation.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        core = AppSecretsOwner.acquire(target);
        ui.click("工作台"); ui.click("Authenticator");
        ui.waitText("设置秘密库", 15000);
        ui.click("创建秘密库"); ui.beginForm();
        ui.setText("主口令", password); ui.setText("确认主口令", password); ui.submit("创建秘密库");
        ui.waitText("开启本设备快捷解锁？", 30000); ui.click("暂不开启"); ui.beginForm();
        ui.waitText("还没有验证码。添加账号后可在此查看和复制。", 15000);
        assertTrue(core.session.unlocked()); assertNull(new VaultStore(target).quickMaterial());
        assertTrue(readEntries().isEmpty()); protection(true);
        phase("real_bootstrap_create_skip_quick_return_to_totp_pass");

        byte[] original = cipher();
        try {
            manualPreview();
            assertTrue("Preview must not persist a seed", readEntries().isEmpty());
            assertTrue("Preview must preserve exact ciphertext", Arrays.equals(original, cipher()));
            ui.toolbarBack("取消添加", "添加验证码");
            ui.waitText("还没有验证码。添加账号后可在此查看和复制。", 10000);
            assertTrue("Cancelled preview must preserve exact ciphertext", Arrays.equals(original, cipher()));
        } finally { VaultCodec.wipe(original); }
        phase("manual_preview_explicit_cancel_no_persist_pass");
        manualPreview(); ui.submit("确认添加"); ui.beginForm();
        waitListEntry(account); checkCurrentCode();
        List<EntryView> entries = readEntries();
        assertTrue("Exactly one confirmed synthetic entry must exist", entries.size() == 1);
        String entryId = entries.get(0).id;
        assertTrue(entries.get(0).identity.equals("Fixture:" + account));
        checkCurrentCode();
        phase("manual_confirm_correct_rfc_current_code_countdown_pass");
        if (clipboardRequested) {
            copyOwnSyntheticCode(); phase("explicitly_authorized_actual_own_synthetic_copy_pass");
        } else phase("copy_not_run_pending_explicit_approval");

        ui.click(account + "更多操作"); ui.click("重命名"); ui.beginForm();
        ui.setText("显示名称", renamed); ui.submit("确认重命名"); ui.beginForm(); waitListEntry(renamed);
        entries = readEntries();
        assertTrue(entries.size() == 1 && entries.get(0).id.equals(entryId) && entries.get(0).label.equals(renamed)
                && entries.get(0).identity.equals("Fixture:" + account));
        checkCurrentCode(); phase("rename_preserves_identity_id_and_seed_pass");

        homeAndReturn(false); checkCurrentCode(); phase("real_home_grace_return_fresh_code_pass");
        ui.click("秘密库设置"); ui.click("立即锁定"); ui.waitText("秘密库已锁定。", 10000);
        assertFalse(core.session.unlocked());
        ui.toolbarBack("Back", "安全");
        ui.waitText("秘密库已锁定，请重新解锁。", 10000); assertNoVisibleCode();
        ui.click("解锁并刷新"); unlockPassword(); waitListEntry(renamed); checkCurrentCode();
        phase("actual_shared_manual_lock_code_hide_password_return_pass");

        cameraRecovery();
        if (imageRequested) actualImageFlow(entryId);

        ui.click(renamed + "更多操作"); ui.click("删除"); ui.waitText("删除验证码？", 10000);
        ui.click("取消"); waitListEntry(renamed);
        assertTrue(readEntries().size() == 1);
        ui.click(renamed + "更多操作"); ui.click("删除"); ui.submit("确认删除");
        ui.waitText("还没有验证码。添加账号后可在此查看和复制。", 15000);
        assertTrue(readEntries().isEmpty()); assertNoVisibleCode();
        phase("explicit_delete_cancel_preserves_then_confirm_removes_pass");

        setBackground(0); homeAndReturn(true);
        ui.waitText("秘密库已锁定，请重新解锁。", 10000); assertNoVisibleCode();
        ui.click("解锁并刷新"); unlockPassword();
        ui.waitText("还没有验证码。添加账号后可在此查看和复制。", 15000);
        setBackground(30); ui.click("秘密库设置"); ui.click("立即锁定"); ui.waitText("秘密库已锁定。", 10000);
        assertFalse(core.session.unlocked()); assertTrue(readEntries().isEmpty());
        assertTrue(target.getContentResolver().getPersistedUriPermissions().isEmpty());
        ui.toolbarBack("Back", "安全"); ui.click("返回工作台"); ui.waitText("工作台", 10000); protection(false);
        assertFalse(target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        successful = true;
        phase("real_zero_timeout_return_and_final_empty_locked_cleanup_pass");
    }


    @Test(timeout = 30000) public void inspectOwnedUiFailureSnapshot() throws Exception {
        assertTrue("Existing-state inspection needs its own explicit opt-in", inspectionRequested);
        byte[] encrypted = cipher(), after = null;
        try {
            long generation = VaultEnvelope.parse(encrypted).generation;
            List<EntryView> entries = readEntries();
            if ("image_commit".equals(inspectionStage)) {
                assertOwnedImageEntries(entries, null, false);
            } else {
                assertTrue("Failed run may contain only its exact public synthetic entry", "renamed".equals(inspectionStage) ? entries.size() == 1 : entries.size() <= 1);
                if (!entries.isEmpty()) assertTrue("Only this recorded run's exact original account may exist", ("Fixture:" + account).equals(entries.get(0).identity)
                        && ("renamed".equals(inspectionStage) ? renamed : account).equals(entries.get(0).label));
            }
            after = cipher();
            assertTrue("Inspection must preserve exact existing ciphertext", Arrays.equals(encrypted, after));
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encrypted);
            try {
                StringBuilder digest = new StringBuilder(); for (byte value : hash) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
                Log.i("CtosTotpUiFixture", "run=" + runId + " phase=owned_failure_authenticated_snapshot entry_count=" + entries.size()
                        + " generation=" + generation + " cipher_sha256=" + digest + " grant_count=" + target.getContentResolver().getPersistedUriPermissions().size());
            } finally { VaultCodec.wipe(hash); }
            if (pickerInspectionRequested) inspectPickerReadOnly();
        } finally { VaultCodec.wipe(encrypted); VaultCodec.wipe(after); }
    }
    private void waitListEntry(String title) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        int attempt = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo heading = ui.unique(n -> TotpFixtureUi.exact(n, "Authenticator") && !n.isEditable()
                    && !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            AccessibilityNodeInfo adding = ui.find(n -> TotpFixtureUi.exact(n, "添加验证码") && !n.isEditable()
                    && !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            boolean listTitle = heading != null, addRoute = adding != null;
            if (heading != null) heading.recycle(); if (adding != null) adding.recycle();
            if (listTitle && !addRoute && !ui.has("核验操作结果") && !ui.has("确认账号") && !ui.has("重命名验证码") && !ui.has("删除验证码？")) {
                AccessibilityNodeInfo action = ui.unique(n -> TotpFixtureUi.exact(n, title + "更多操作") && n.isEnabled() && !n.isEditable()
                        && "android.widget.Button".contentEquals(String.valueOf(n.getClassName()))
                        && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
                if (action != null) {
                    try {
                        if (action.refresh() && action.isVisibleToUser() && action.isEnabled() && action.getWindowId() == ui.activeWindow()
                                && TARGET.contentEquals(String.valueOf(action.getPackageName())) && !action.isEditable()
                                && "android.widget.Button".contentEquals(String.valueOf(action.getClassName()))
                                && TotpFixtureUi.exact(action, title + "更多操作")
                                && action.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) return;
                    } finally { action.recycle(); }
                }
                ui.sweepAuthenticatorList(attempt++);
            }
            SystemClock.sleep(100);
        }
        fail("Confirmed list route did not publish its own exact entry action");
    }
    private void phase(String name) { Log.i("CtosTotpUiFixture", "run=" + runId + " phase=" + name); }
    private void manualPreview() {
        ui.click("添加验证码"); ui.waitText("手动添加", 10000); ui.beginForm();
        ui.setText("服务名称", "Fixture"); ui.setText("账号", account); ui.setText("密钥", PUBLIC_SEED);
        ui.submit("检查并预览"); ui.beginForm(); ui.waitText("确认账号", 15000); ui.waitText("Fixture:" + account, 10000);
    }
    private void unlockPassword() {
        ui.beginForm(); ui.setText("当前主口令", password); ui.submit("解锁秘密库"); ui.beginForm();
    }
    private void setBackground(int seconds) {
        ui.click("秘密库设置"); ui.beginForm(); ui.setText("秒数", Integer.toString(seconds));
        ui.submit("保存时间设置"); ui.waitText("时间设置已保存。", 10000); ui.beginForm();
        assertTrue("Actual UI must update the native timeout", core.session.timeout() == seconds);
        ui.toolbarBack("Back", "安全"); ui.waitText("Authenticator", 10000);
    }
    private void homeAndReturn(boolean mustLock) {
        assertTrue("Only the current fixture may be sent Home", ui.activeWindow() >= 0);
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (core.session.foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertFalse("Actual Activity background lifecycle did not arrive", core.session.foreground());
        if (mustLock) assertFalse("Zero timeout must immediately expire the actual session", core.session.unlocked());
        else assertTrue("Within grace the real session must remain usable", core.session.unlocked());
        instrumentation.runOnMainSync(() -> activity.startActivity(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK)));
        deadline = SystemClock.elapsedRealtime() + 15000;
        while (ui.activeWindow() < 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue("Own fixture must return to the active window", ui.activeWindow() >= 0);
    }
    private void protection(boolean expected) {
        AtomicInteger flags = new AtomicInteger();
        long deadline = SystemClock.elapsedRealtime() + (expected ? 0 : 10000);
        do {
            instrumentation.runOnMainSync(() -> flags.set(activity.getWindow().getAttributes().flags));
            if (((flags.get() & WindowManager.LayoutParams.FLAG_SECURE) != 0) == expected) break;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Real window protection owner state mismatch", ((flags.get() & WindowManager.LayoutParams.FLAG_SECURE) != 0) == expected);
    }
    private void assertNoVisibleCode() {
        AccessibilityNodeInfo code = ui.find(n -> semanticCode(n) != null);
        if (code != null) code.recycle();
        assertNull("Locked/empty current UI must not publish a code", code);
    }
    private static String semanticCode(AccessibilityNodeInfo node) {
        for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription()}) {
            if (value == null) continue;
            for (String component : value.toString().split("\n|, ")) if (component.matches("验证码 [0-9]{6}")) return component.substring(4);
        }
        return null;
    }
    private void checkCurrentCode() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) {
            long before = System.currentTimeMillis() / 1000;
            if (before % 30 > 26) { SystemClock.sleep(200); continue; }
            String expected = rfcCode(before);
            AccessibilityNodeInfo code = ui.find(n -> expected.equals(semanticCode(n)));
            if (code != null) {
                code.recycle();
                long after = System.currentTimeMillis() / 1000;
                if (before / 30 == after / 30) {
                    AccessibilityNodeInfo countdown = ui.find(n -> {
                        for (int remaining = Math.max(1, 30 - (int) (after % 30) - 2); remaining <= Math.min(30, 30 - (int) (before % 30) + 2); remaining++)
                            if (TotpFixtureUi.exact(n, remaining + " 秒后更新")) return true;
                        return false;
                    });
                    if (countdown != null) { countdown.recycle(); return; }
                }
            }
            SystemClock.sleep(100);
        }
        fail("Real current code/countdown did not match the independent RFC clock oracle");
    }
    private static String rfcCode(long seconds) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(RFC_SEED, "HmacSHA1"));
        byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array());
        try {
            int offset = digest[digest.length - 1] & 15;
            int binary = (digest[offset] & 127) << 24 | (digest[offset + 1] & 255) << 16 | (digest[offset + 2] & 255) << 8 | digest[offset + 3] & 255;
            return String.format(Locale.ROOT, "%06d", binary % 1000000);
        } finally { VaultCodec.wipe(digest); }
    }
    private void copyOwnSyntheticCode() throws Exception {
        assertTrue("Actual global clipboard write needs explicit runner authorization", clipboardRequested);
        checkCurrentCode(); long before = System.currentTimeMillis() / 1000;
        ui.submit("复制验证码"); ui.waitText("验证码已复制。", 10000);
        assertTrue("Own copy must be inspected only while fixture remains foreground", ui.activeWindow() >= 0 && core.session.foreground());
        ClipboardManager clipboard = target.getSystemService(ClipboardManager.class);
        assertNotNull(clipboard);
        // No prior clipboard read/save/restore, listener, clear or cleanup timer.
        ClipDescription receipt = clipboard.getPrimaryClipDescription();
        assertTrue("Post-receipt description must identify the just-written synthetic clip before content inspection", receipt != null
                && "ctOS code".contentEquals(receipt.getLabel()) && receipt.getExtras() != null
                && receipt.getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        ClipData own = clipboard.getPrimaryClip();
        assertNotNull("Acknowledged own copy must create a clip", own);
        assertTrue("Only the just-written synthetic clip is accepted", own.getItemCount() == 1 && "ctOS code".contentEquals(own.getDescription().getLabel()));
        CharSequence text = own.getItemAt(0).getText(); long after = System.currentTimeMillis() / 1000;
        assertTrue("Only current own synthetic RFC code may be inspected", text != null
                && (rfcCode(before).contentEquals(text) || rfcCode(after).contentEquals(text)));
        assertTrue("Own copy must advertise sensitive data", own.getDescription().getExtras() != null
                && own.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
    }
    private byte[] cipher() throws Exception {
        for (String suffix : new String[]{".bak", ".new"}) assertFalse("Oracle cannot recover a pending AtomicFile", new File(target.getNoBackupFilesDir(), "app-secrets.bin" + suffix).exists());
        byte[] data = new VaultStore(target).read(); assertNotNull(data); return data;
    }
    private List<EntryView> readEntries() throws Exception {
        byte[] encrypted = cipher(), key = null, plain = null;
        char[] input = password.toCharArray();
        try {
            VaultEnvelope envelope = VaultEnvelope.parse(encrypted); key = VaultEnvelope.derive(input, envelope.salt); plain = envelope.decrypt(key);
            try (VaultManifest manifest = VaultManifest.decode(plain)) {
                assertTrue("Fresh fixture may not create SSH files", manifest.files.isEmpty());
                List<EntryView> rows = new ArrayList<>();
                for (VaultManifest.Entry entry : manifest.entries) {
                    assertTrue("Only supported public synthetic TOTP may persist", entry.type == VaultManifest.EntryType.TOTP
                            && Arrays.equals(entry.secret, RFC_SEED) && "SHA1".equals(entry.algorithm) && entry.digits == 6 && entry.period == 30);
                    rows.add(new EntryView(entry.id, entry.identity, entry.label));
                }
                return rows;
            }
        } finally { Arrays.fill(input, '\0'); VaultCodec.wipe(encrypted); VaultCodec.wipe(key); VaultCodec.wipe(plain); }
    }
    private static final class EntryView {
        final String id, identity, label;
        EntryView(String id, String identity, String label) { this.id = id; this.identity = identity; this.label = label; }
    }

    private void cameraRecovery() throws Exception {
        ui.click("添加验证码"); ui.waitText("相机扫码", 10000); ui.submit("相机扫码");
        long deadline = SystemClock.elapsedRealtime() + 15000;
        boolean recovered = false, denied = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            String owner = root == null ? "" : String.valueOf(root.getPackageName());
            if (root != null) root.recycle();
            if (owner.equals("com.android.permissioncontroller") || owner.equals("com.google.android.permissioncontroller")) {
                ui.scope(owner);
                AccessibilityNodeInfo request = ui.find(n -> {
                    CharSequence text = n.getText();
                    return text != null && text.toString().contains("ctOS secrets fixture");
                });
                assertNotNull("Camera permission request must name our own fixture", request); request.recycle();
                AccessibilityNodeInfo deny = ui.unique(n -> (owner + ":id/permission_deny_button").equals(n.getViewIdResourceName())
                        && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())) && n.isEnabled());
                assertNotNull("Only the own camera request denial control is permitted", deny);
                try {
                    assertTrue("Own denial control must remain in the active permission window", deny.refresh() && deny.getWindowId() == ui.activeWindow());
                    AccessibilityNodeInfo freshRequest = ui.unique(n -> {
                        CharSequence text = n.getText();
                        return n.getWindowId() == deny.getWindowId() && text != null && text.toString().contains("ctOS secrets fixture");
                    });
                    assertNotNull("Fresh active permission request must still name our fixture", freshRequest);
                    try {
                        assertTrue("Fresh permission label must survive to the denial action", freshRequest.refresh() && freshRequest.isVisibleToUser()
                                && freshRequest.getWindowId() == ui.activeWindow() && freshRequest.getWindowId() == deny.getWindowId()
                                && freshRequest.getText() != null && freshRequest.getText().toString().contains("ctOS secrets fixture"));
                        systemClick(deny, owner); denied = true;
                    } finally { freshRequest.recycle(); }
                } finally { deny.recycle(); ui.scope(TARGET); }
            } else if (owner.equals(TARGET)) {
                if (ui.has("相机权限未开启，可使用图片或手动添加。") || ui.has("相机暂时不可用，可使用图片或手动添加。")) {
                    recovered = true; break;
                }
            }
            SystemClock.sleep(100);
        }
        if (!recovered) cameraFailureDiagnostic(denied);
        assertTrue("No-camera/denied permission must recover to the real add page", recovered);
        assertTrue("Camera failure must not persist an entry", readEntries().size() == 1);
        ui.toolbarBack("取消添加", "添加验证码"); waitListEntry(renamed); checkCurrentCode();
        phase(denied ? "actual_own_camera_permission_denial_recovery_pass" : "actual_no_hardware_camera_recovery_pass");
    }

    private void cameraFailureDiagnostic(boolean denied) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        String owner = root == null ? "none" : String.valueOf(root.getPackageName());
        if (root != null) root.recycle();
        if (!owner.matches("[A-Za-z0-9_.]{1,150}")) owner = "unknown";
        ui.scope(TARGET);
        StringBuilder states = new StringBuilder();
        String[] errors = {"相机权限未开启，可使用图片或手动添加。", "相机暂时不可用，可使用图片或手动添加。", "操作已取消。",
                "秘密库已锁定，请重新解锁。", "秘密库或操作已失效，请重新开始。", "请回到前台后重试。", "操作超时，请重试。", "无法监听秘密库状态，请返回后重试。", "已有操作进行中，请稍后重试。"};
        for (int index = 0; index < errors.length; index++) states.append(" own_error_").append(index).append('=').append(ui.has(errors[index]));
        String[] buttons = {"相机扫码", "从图片读取", "检查并预览", "取消添加"};
        for (int index = 0; index < buttons.length; index++) {
            final String label = buttons[index];
            AccessibilityNodeInfo node = ui.find(n -> TotpFixtureUi.exact(n, label) && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())));
            states.append(" own_button_").append(index).append("_visible=").append(node != null);
            if (node != null) {
                try { states.append("_enabled=").append(node.isEnabled()).append("_click=")
                        .append(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)); }
                finally { node.recycle(); }
            }
        }
        // Only package metadata and booleans for fixed app-owned labels, never raw UI text/inputs.
        Log.i("CtosTotpUiFixture", "run=" + runId + " phase=camera_recovery_bounded_failure_diagnostic active_package=" + owner
                + " denied=" + denied + " native_scanner_title=" + ui.has("扫描 TOTP 二维码") + " own_foreground=" + core.session.foreground() + " own_unlocked=" + core.session.unlocked() + states);
    }
    private void actualImageFlow(String baselineId) throws Exception {
        setBackground(120); createOwnedImage();
        byte[] before = cipher();
        try {
            ui.click("添加验证码"); ui.waitText("从图片读取", 10000); ui.submit("从图片读取");
            selectOwnedDocument();
            phase("actual_exact_owned_uuid_document_row_selected");
            try { ui.waitText("确认账号", 20000); }
            catch (AssertionError failure) { ownImageRecoveryDiagnostic(); throw failure; }
            ui.waitText("FixtureScanA:alpha", 10000); ui.waitText("FixtureScanB:beta", 10000);
            List<EntryView> entries = readEntries();
            assertTrue("Real image preview must not auto-import any candidate", entries.size() == 1 && baselineId.equals(entries.get(0).id));
            byte[] current = cipher();
            try { assertTrue("Image preview must preserve exact ciphertext", Arrays.equals(before, current)); }
            finally { VaultCodec.wipe(current); }
            AccessibilityNodeInfo confirmation = ui.unique(n -> TotpFixtureUi.exact(n, "确认添加") && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())));
            assertNotNull("Explicit image candidate confirmation must exist", confirmation);
            try { assertFalse("Multiple candidates require explicit selection", confirmation.isEnabled()); } finally { confirmation.recycle(); }
            ui.click("FixtureScanA:alpha"); ui.submit("确认添加"); waitAfterImageCommit(baselineId);
            entries = readEntries(); assertOwnedImageEntries(entries, baselineId, true);
            requireOwnedImage(); byte[] after = ownedImageDigest();
            try { assertTrue("Picker must not change our source image", MessageDigest.isEqual(imageDigest, after)); }
            finally { VaultCodec.wipe(after); }
            assertTrue("This path must not create persisted grants", target.getContentResolver().getPersistedUriPermissions().isEmpty());
            ui.click("alpha更多操作"); ui.click("删除"); ui.submit("确认删除"); waitListEntry(renamed);
            long deletionDeadline = SystemClock.elapsedRealtime() + 10000;
            while (ui.has("alpha更多操作") && SystemClock.elapsedRealtime() < deletionDeadline) SystemClock.sleep(100);
            assertFalse("Confirmed image deletion must remove its actual list action", ui.has("alpha更多操作"));
            entries = readEntries(); assertTrue(entries.size() == 1 && baselineId.equals(entries.get(0).id));
            // Same-UID owned media is not evidence of temporary grant enforcement.
            phase("actual_saf_owned_png_two_qr_preview_single_explicit_commit_delete_pass_grant_enforcement_unproven");
        } finally { VaultCodec.wipe(before); }
        setBackground(30); checkCurrentCode();
    }


    private void assertOwnedImageEntries(List<EntryView> entries, String baselineId, boolean requireChosen) {
        assertTrue("Only the baseline and explicitly chosen public image entry may exist", entries.size() >= 1 && entries.size() <= 2);
        EntryView baseline = null, chosen = null;
        for (EntryView entry : entries) {
            if (("Fixture:" + account).equals(entry.identity) && renamed.equals(entry.label)) {
                assertNull("The exact baseline may appear only once", baseline); baseline = entry;
            } else if ("FixtureScanA:alpha".equals(entry.identity) && "alpha".equals(entry.label)) {
                assertNull("The explicitly chosen image entry may appear only once", chosen); chosen = entry;
            } else fail("Unexpected entry in the owned image-stage fixture");
        }
        assertNotNull("Owned renamed baseline must remain present", baseline);
        if (baselineId != null) assertTrue("Image commit must preserve the original baseline ID", baselineId.equals(baseline.id));
        if (requireChosen) assertNotNull("Successful image confirmation must persist exactly the chosen entry", chosen);
        if (chosen != null) assertTrue("Chosen entry must have its own distinct ID", !baseline.id.equals(chosen.id));
        // readEntries authenticated every entry's exact RFC seed and profile.
    }
    private void waitAfterImageCommit(String baselineId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        int attempt = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean acknowledgement = ui.has("核验操作结果");
            if (acknowledgement && ui.has("验证码已添加。请返回验证器核验条目。")) {
                final int acknowledgementWindow = ui.activeWindow();
                assertOwnedImageEntries(readEntries(), baselineId, true); protection(true);
                ui.exactSuccessAck(acknowledgementWindow, "核验操作结果", "验证码已添加。请返回验证器核验条目。");
                long removed = SystemClock.elapsedRealtime() + 10000;
                while (ui.has("核验操作结果") && SystemClock.elapsedRealtime() < removed) SystemClock.sleep(100);
                assertFalse("Exact protected success acknowledgement must be dismissed", ui.has("核验操作结果"));
                ui.waitText("添加验证码", 10000); ui.toolbarBack("取消添加", "添加验证码"); waitListEntry("alpha");
                phase("actual_exact_success_ack_verified_two_entries_then_returned_to_list_pass"); return;
            }
            if (!acknowledgement) {
                AccessibilityNodeInfo alpha = ui.unique(n -> TotpFixtureUi.exact(n, "alpha更多操作") && n.isEnabled() && !n.isEditable()
                        && "android.widget.Button".contentEquals(String.valueOf(n.getClassName()))
                        && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
                if (alpha != null) {
                    alpha.recycle(); waitListEntry("alpha"); return;
                }
                ui.sweepAuthenticatorList(attempt++);
            }
            SystemClock.sleep(100);
        }
        imageCommitDiagnostic(); fail("Image confirmation must return to the exact list or the exact protected completed-write acknowledgement");
    }
    private void imageCommitDiagnostic() {
        AccessibilityNodeInfo add = ui.unique(n -> TotpFixtureUi.exact(n, "添加验证码") && !n.isEditable()
                && !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        AccessibilityNodeInfo list = ui.unique(n -> TotpFixtureUi.exact(n, "Authenticator") && !n.isEditable()
                && !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        boolean addHeading = add != null, listHeading = list != null;
        if (add != null) add.recycle(); if (list != null) list.recycle();
        Log.i("CtosTotpUiFixture", "run=" + runId + " phase=image_commit_bounded_failure_diagnostic ack_title=" + ui.has("核验操作结果")
                + " exact_completed_write_text=" + ui.has("验证码已添加。请返回验证器核验条目。")
                + " add_heading=" + addHeading + " list_heading=" + listHeading
                + " exact_alpha_action_visible_count=" + ui.count(n -> TotpFixtureUi.exact(n, "alpha更多操作")
                        && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())))
                + " exact_baseline_action_visible_count=" + ui.count(n -> TotpFixtureUi.exact(n, renamed + "更多操作")
                        && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())))
                + " own_scrollable_visible_count=" + ui.count(n -> n.isScrollable() && !n.isEditable())
                + " own_foreground=" + core.session.foreground() + " own_unlocked=" + core.session.unlocked());
    }
    private void ownImageRecoveryDiagnostic() {
        ui.scope(TARGET); StringBuilder states = new StringBuilder();
        String[] errors = {"无法读取此图片，请选择其他图片。", "未发现受支持的验证码，请选择其他图片或手动添加。",
                "输入无效或参数不受支持。请检查账号与密钥，仅支持 SHA-1、6 位、30 秒 TOTP。", "此验证码类型或参数不受支持。仅支持 SHA-1、6 位、30 秒 TOTP。",
                "内容超过限制，请减少图片大小或验证码数量。", "操作已取消。", "秘密库已锁定，请重新解锁。", "秘密库或操作已失效，请重新开始。",
                "请回到前台后重试。", "操作超时，请重试。", "已有操作进行中，请稍后重试。", "无法监听秘密库状态，请返回后重试。", "操作未完成，请重试。"};
        for (int index = 0; index < errors.length; index++) states.append(" own_error_").append(index).append('=').append(ui.has(errors[index]));
        String[] buttons = {"从图片读取", "相机扫码", "检查并预览", "确认添加", "取消添加"};
        for (int index = 0; index < buttons.length; index++) {
            final String label = buttons[index];
            AccessibilityNodeInfo node = ui.find(n -> TotpFixtureUi.exact(n, label) && "android.widget.Button".contentEquals(String.valueOf(n.getClassName())));
            states.append(" own_button_").append(index).append("_visible=").append(node != null);
            if (node != null) {
                try { states.append("_enabled=").append(node.isEnabled()).append("_click=")
                        .append(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)); }
                finally { node.recycle(); }
            }
        }
        AccessibilityNodeInfo progress = ui.find(n -> "android.widget.ProgressBar".contentEquals(String.valueOf(n.getClassName())));
        boolean progressVisible = progress != null; if (progress != null) progress.recycle();
        Log.i("CtosTotpUiFixture", "run=" + runId + " phase=own_image_recovery_bounded_failure_diagnostic own_active_window=" + (ui.activeWindow() >= 0)
                + " own_foreground=" + core.session.foreground() + " own_unlocked=" + core.session.unlocked() + " own_progress_visible=" + progressVisible + states);
    }
    private void createOwnedImage() throws Exception {
        imageName = "ctos-totp-" + runId + ".png";
        ContentValues metadata = new ContentValues();
        metadata.put(MediaStore.MediaColumns.DISPLAY_NAME, imageName);
        metadata.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        metadata.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/");
        metadata.put(MediaStore.MediaColumns.IS_PENDING, 1);
        image = target.getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), metadata);
        assertNotNull("Only a new owned MediaStore item may be used", image);
        // image is captured before writing, so every later failure has exact cleanup ownership.
        requireOwnedImage();
        Bitmap bitmap = Bitmap.createBitmap(920, 440, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            for (int code = 0; code < 2; code++) {
                String issuer = code == 0 ? "FixtureScanA" : "FixtureScanB", name = code == 0 ? "alpha" : "beta";
                BitMatrix qr = new MultiFormatWriter().encode("otpauth://totp/" + issuer + ":" + name + "?secret=" + PUBLIC_SEED + "&issuer=" + issuer, BarcodeFormat.QR_CODE, 384, 384);
                for (int y = 0; y < qr.getHeight(); y++) for (int x = 0; x < qr.getWidth(); x++)
                    bitmap.setPixel(28 + code * 480 + x, 28 + y, qr.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            try (OutputStream output = target.getContentResolver().openOutputStream(image, "w")) {
                assertNotNull(output); assertTrue("Own synthetic PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { bitmap.recycle(); }
        ContentValues published = new ContentValues(); published.put(MediaStore.MediaColumns.IS_PENDING, 0);
        assertTrue("Only our exact media item may be published", target.getContentResolver().update(image, published, null, null) == 1);
        imageDigest = ownedImageDigest();
        try (Cursor row = target.getContentResolver().query(image, new String[]{MediaStore.MediaColumns.TITLE}, null, null, null)) {
            assertNotNull("Exact owned image title metadata must be readable", row); assertTrue(row.getCount() == 1 && row.moveToFirst());
            String title = row.getString(0), stem = imageName.substring(0, imageName.length() - 4);
            Log.i("CtosTotpUiFixture", "run=" + runId + " phase=owned_media_title_metadata title_full_name=" + imageName.equals(title)
                    + " title_own_stem=" + stem.equals(title));
        }
        phase("exact_uuid_owned_synthetic_two_qr_png_published");
    }
    private void requireOwnedImage() {
        assertNotNull(image); assertNotNull(imageName);
        assertTrue("Only MediaStore owns this image fixture", "content".equals(image.getScheme()) && "media".equals(image.getAuthority()));
        try (Cursor row = target.getContentResolver().query(image,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            assertNotNull("Exact owned image is unavailable", row);
            assertTrue("Exact URI must identify one owned synthetic image", row.getCount() == 1 && row.moveToFirst()
                    && imageName.equals(row.getString(0)) && "image/png".equals(row.getString(1)) && TARGET.equals(row.getString(2)));
        }
    }
    private byte[] ownedImageDigest() throws Exception {
        requireOwnedImage(); MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = target.getContentResolver().openInputStream(image)) {
            assertNotNull(input); byte[] buffer = new byte[8192]; int total = 0, count;
            try {
                while ((count = input.read(buffer)) != -1) { total += count; assertTrue("Own synthetic PNG exceeds fixture bound", total <= 1024 * 1024); digest.update(buffer, 0, count); }
                assertTrue(total > 0); return digest.digest();
            } finally { VaultCodec.wipe(buffer); }
        }
    }
    private void selectOwnedDocument() {
        long deadline = SystemClock.elapsedRealtime() + 15000; String owner = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                String observed = String.valueOf(root.getPackageName()); root.recycle();
                if (observed.equals("com.android.documentsui") || observed.equals("com.google.android.documentsui")) { owner = observed; break; }
            }
            SystemClock.sleep(100);
        }
        assertNotNull("Actual DocumentsUI picker must be foreground", owner);
        ui.scope(owner);
        try {
            ensurePickerListMode(owner, true);
            AccessibilityNodeInfo title = ownDocumentTitle(owner);
            if (title == null) {
                final String picker = owner;
                AccessibilityNodeInfo search = ui.unique(n -> ((picker + ":id/option_menu_search").equals(n.getViewIdResourceName())
                        || (picker + ":id/menu_search").equals(n.getViewIdResourceName())) && n.isEnabled() && !n.isEditable());
                assertNotNull("Picker must expose exact owned filename or its native search control", search);
                try { systemClick(search, owner); } finally { search.recycle(); }
                AccessibilityNodeInfo editor = null; deadline = SystemClock.elapsedRealtime() + 5000;
                while (editor == null && SystemClock.elapsedRealtime() < deadline) {
                    // AOSP SearchBot and this board's DocumentsUI APK bind its AppCompat
                    // editor in the picker package; framework SearchView uses android:id.
                    editor = ui.unique(n -> isPickerSearchEditor(n, picker));
                    if (editor == null) SystemClock.sleep(100);
                }
                assertNotNull("Native picker search editor must be explicit", editor);
                try {
                    assertTrue("Exact native picker editor must survive to focus", editor.refresh() && isPickerSearchEditor(editor, picker));
                    systemClick(editor, owner); deadline = SystemClock.elapsedRealtime() + 3000;
                    while ((!editor.refresh() || !isPickerSearchEditor(editor, picker) || !editor.isFocused() || !editor.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
                            && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                    assertTrue("Own-name native search requires focused advertised SET_TEXT", editor.refresh() && isPickerSearchEditor(editor, picker) && editor.isFocused()
                            && editor.getWindowId() == ui.activeWindow() && editor.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT));
                    Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, imageName);
                    assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
                    deadline = SystemClock.elapsedRealtime() + 3000;
                    while ((!editor.refresh() || !isPickerSearchEditor(editor, picker) || editor.getText() == null || !imageName.contentEquals(editor.getText())) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                    assertTrue("Picker query must contain only our exact owned UUID filename", editor.refresh() && isPickerSearchEditor(editor, picker) && editor.getText() != null && imageName.contentEquals(editor.getText())
                            && editor.isFocused() && editor.getWindowId() == ui.activeWindow());
                    submitOwnedPickerQuery(editor, picker);
                } finally { editor.recycle(); }
                deadline = SystemClock.elapsedRealtime() + 10000;
                while (title == null && SystemClock.elapsedRealtime() < deadline) {
                    ensurePickerListMode(owner, false);
                    title = ownDocumentTitle(owner); if (title == null) SystemClock.sleep(100);
                }
            }
            if (title == null) pickerStructureDiagnostic();
            assertNotNull("Exact newly created image must appear in the real picker", title);
            try {
                AccessibilityNodeInfo row = null, cursor = AccessibilityNodeInfo.obtain(title);
                try {
                    for (int depth = 0; cursor != null && depth <= 6; depth++) {
                        if ((owner + ":id/item_root").equals(cursor.getViewIdResourceName())) { row = AccessibilityNodeInfo.obtain(cursor); break; }
                        AccessibilityNodeInfo parent = cursor.getParent(); cursor.recycle(); cursor = parent;
                    }
                } finally { if (cursor != null) cursor.recycle(); }
                assertNotNull("Only DocumentsUI's exact item_root containing our UUID title may be selected", row);
                try {
                    assertTrue("Owned title must remain in the active picker row", title.refresh() && title.isVisibleToUser()
                            && title.getWindowId() == ui.activeWindow() && TotpFixtureUi.exact(title, imageName));
                    clickOwnedDocumentRow(title, row, owner);
                } finally { row.recycle(); }
            } finally { title.recycle(); }
        } finally { ui.scope(TARGET); }
    }


    private void ensurePickerListMode(String owner, boolean required) {
        assertTrue("View mode belongs only to the active known picker", (owner.equals("com.android.documentsui") || owner.equals("com.google.android.documentsui"))
                && owner.equals(ui.scope()) && ui.activeWindow() >= 0);
        int originalWindow = ui.activeWindow(); long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            AccessibilityNodeInfo list = ui.unique(n -> (owner + ":id/sub_menu_list").equals(n.getViewIdResourceName()) && !n.isEditable());
            AccessibilityNodeInfo grid = ui.unique(n -> (owner + ":id/sub_menu_grid").equals(n.getViewIdResourceName()) && !n.isEditable());
            try {
                assertFalse("Native picker view actions must be unambiguous", list != null && grid != null);
                if (grid != null) {
                    assertTrue("Visible grid action must prove the same picker's current list mode", grid.refresh() && grid.isVisibleToUser() && grid.isEnabled()
                            && !grid.isEditable() && (owner + ":id/sub_menu_grid").equals(grid.getViewIdResourceName())
                            && owner.contentEquals(String.valueOf(grid.getPackageName())) && grid.getWindowId() == originalWindow && grid.getWindowId() == ui.activeWindow()
                            && grid.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
                    return;
                }
                if (list != null) {
                    assertTrue("Only the exact native list-mode action may be invoked", list.refresh() && !list.isEditable()
                            && (owner + ":id/sub_menu_list").equals(list.getViewIdResourceName()) && list.getWindowId() == originalWindow);
                    systemClick(list, owner);
                    long transition = SystemClock.elapsedRealtime() + 5000;
                    do {
                        AccessibilityNodeInfo changed = ui.unique(n -> (owner + ":id/sub_menu_grid").equals(n.getViewIdResourceName())
                                && !n.isEditable() && n.isEnabled() && n.getWindowId() == originalWindow
                                && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
                        if (changed != null) {
                            try {
                                if (changed.refresh() && changed.isVisibleToUser() && changed.isEnabled() && !changed.isEditable()
                                        && owner.contentEquals(String.valueOf(changed.getPackageName())) && changed.getWindowId() == ui.activeWindow()
                                        && changed.getWindowId() == originalWindow && (owner + ":id/sub_menu_grid").equals(changed.getViewIdResourceName())
                                        && changed.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                                    phase("picker_explicit_native_list_mode_selected"); return;
                                }
                            } finally { changed.recycle(); }
                        }
                        SystemClock.sleep(100);
                    } while (SystemClock.elapsedRealtime() < transition);
                    fail("Native list view switch did not expose its exact inverse action");
                }
            } finally { if (list != null) list.recycle(); if (grid != null) grid.recycle(); }
            if (!required) return; // Expanded search may temporarily hide both view actions.
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Known native picker view action is required before document selection");
    }
    private void inspectPickerReadOnly() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        ui = new TotpFixtureUi(automation);
        imageName = "ctos-totp-" + runId + ".png";
        pickerStructureDiagnostic();
    }
    private void pickerStructureDiagnostic() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        String observed = root == null ? "" : String.valueOf(root.getPackageName());
        int originalWindow = root == null ? -1 : root.getWindowId();
        if (root != null) root.recycle();
        if (!observed.equals("com.android.documentsui") && !observed.equals("com.google.android.documentsui")) {
            phase("picker_structure_not_available"); return;
        }
        final String owner = observed, previousScope = ui.scope(); ui.scope(owner);
        try {
            AccessibilityNodeInfo editor = ui.unique(n -> isPickerSearchEditor(n, owner) && n.getText() != null && imageName.contentEquals(n.getText()));
            if (editor == null) { phase("picker_structure_not_available"); return; }
            try {
                if (!editor.refresh() || !isPickerSearchEditor(editor, owner) || editor.getWindowId() != originalWindow
                        || editor.getText() == null || !imageName.contentEquals(editor.getText())) { phase("picker_structure_not_available"); return; }
                StringBuilder summary = new StringBuilder();
                AccessibilityNodeInfo listAction = ui.unique(n -> (owner + ":id/sub_menu_list").equals(n.getViewIdResourceName()) && !n.isEditable());
                AccessibilityNodeInfo gridAction = ui.unique(n -> (owner + ":id/sub_menu_grid").equals(n.getViewIdResourceName()) && !n.isEditable());
                summary.append(" list_action_visible=").append(listAction != null).append(" grid_action_visible=").append(gridAction != null);
                if (listAction != null) listAction.recycle(); if (gridAction != null) gridAction.recycle();
                AccessibilityNodeInfo directory = ui.unique(n -> (owner + ":id/dir_list").equals(n.getViewIdResourceName()) && !n.isEditable());
                summary.append(" dir_list_visible=").append(directory != null);
                if (directory != null) {
                    try { summary.append(" dir_list_child_count=").append(Math.min(1500, directory.getChildCount()))
                            .append(" dir_list_child_count_truncated=").append(directory.getChildCount() > 1500); }
                    finally { directory.recycle(); }
                }
                String[] roots = {"Recent", "Recents", "最近", "Images", "图片", "Downloads", "下载", "Pictures", "图片目录"};
                for (int index = 0; index < roots.length; index++) {
                    final String label = roots[index];
                    summary.append(" root_").append(index).append("_visible=").append(ui.has(label));
                    summary.append(" root_").append(index).append("_framework_title_count=")
                            .append(ui.count(n -> "android:id/title".equals(n.getViewIdResourceName()) && TotpFixtureUi.exact(n, label)));
                }
                AccessibilityNodeInfo rootsList = ui.unique(n -> (owner + ":id/roots_list").equals(n.getViewIdResourceName()));
                summary.append(" roots_list_visible=").append(rootsList != null);
                if (rootsList != null) {
                    try {
                        String role = String.valueOf(rootsList.getClassName());
                        summary.append(" roots_list_native_list=").append("android.widget.ListView".equals(role))
                                .append(" roots_list_recycler=").append("androidx.recyclerview.widget.RecyclerView".equals(role));
                    } finally { rootsList.recycle(); }
                }
                String stem = imageName.substring(0, imageName.length() - 4);
                summary.append(" own_full_title_count=").append(ui.count(n -> "android:id/title".equals(n.getViewIdResourceName()) && TotpFixtureUi.exact(n, imageName)))
                        .append(" own_stem_title_count=").append(ui.count(n -> "android:id/title".equals(n.getViewIdResourceName()) && TotpFixtureUi.exact(n, stem)))
                        .append(" own_full_textview_count=").append(ui.count(n -> "android:id/title".equals(n.getViewIdResourceName())
                                && "android.widget.TextView".contentEquals(String.valueOf(n.getClassName())) && TotpFixtureUi.exact(n, imageName)))
                        .append(" own_stem_textview_count=").append(ui.count(n -> "android:id/title".equals(n.getViewIdResourceName())
                                && "android.widget.TextView".contentEquals(String.valueOf(n.getClassName())) && TotpFixtureUi.exact(n, stem)));
                if (!editor.refresh() || !isPickerSearchEditor(editor, owner) || editor.getWindowId() != originalWindow
                        || editor.getText() == null || !imageName.contentEquals(editor.getText())) { phase("picker_structure_not_available"); return; }
                Log.i("CtosTotpUiFixture", "run=" + runId + " phase=picker_structure_bounded_read_only own_query_full_name=true"
                        + " picker_android=" + owner.equals("com.android.documentsui") + " picker_google=" + owner.equals("com.google.android.documentsui")
                        + " editor_framework_resource=" + "android:id/search_src_text".equals(editor.getViewIdResourceName())
                        + " editor_owner_resource=" + (owner + ":id/search_src_text").equals(editor.getViewIdResourceName())
                        + " editor_edittext=" + "android.widget.EditText".contentEquals(String.valueOf(editor.getClassName()))
                        + " editor_autocomplete=" + "android.widget.AutoCompleteTextView".contentEquals(String.valueOf(editor.getClassName())) + summary);
            } finally { editor.recycle(); }
        } finally { ui.scope(previousScope); }
    }
    private void submitOwnedPickerQuery(AccessibilityNodeInfo editor, String owner) {
        assertTrue("Search key down requires the focused exact own UUID query", editor.refresh() && isPickerSearchEditor(editor, owner)
                && editor.isFocused() && editor.getText() != null && imageName.contentEquals(editor.getText()));
        int originalWindow = editor.getWindowId();
        long downTime = SystemClock.uptimeMillis();
        assertTrue("UiAutomation search key down was rejected", automation.injectInputEvent(
                new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0), true));
        boolean fresh = editor.refresh();
        String resource = editor.getViewIdResourceName(), role = String.valueOf(editor.getClassName());
        boolean knownResource = (owner + ":id/search_src_text").equals(resource) || "android:id/search_src_text".equals(resource);
        boolean knownRole = "android.widget.EditText".equals(role) || "android.widget.AutoCompleteTextView".equals(role);
        boolean sameOwner = owner.equals(ui.scope()) && owner.contentEquals(String.valueOf(editor.getPackageName()));
        boolean sameWindow = editor.getWindowId() == originalWindow && ui.activeWindow() == originalWindow;
        boolean ownQuery = editor.getText() != null && imageName.contentEquals(editor.getText());
        Log.i("CtosTotpUiFixture", "run=" + runId + " phase=picker_submit_post_down_diagnostic refreshed=" + fresh
                + " resource=" + knownResource + " role=" + knownRole + " editable=" + editor.isEditable()
                + " enabled=" + editor.isEnabled() + " visible=" + editor.isVisibleToUser() + " owner=" + sameOwner
                + " original_window=" + sameWindow + " own_query=" + ownQuery + " focused=" + editor.isFocused());
        // Search submission can clear focus on DOWN. Release only that accepted
        // key in the same bound picker/window with the unchanged own query.
        assertTrue("Paired search key up must stay in the same exact own UUID editor and original picker window", editor.refresh()
                && isPickerSearchEditor(editor, owner) && editor.getWindowId() == originalWindow
                && editor.getText() != null && imageName.contentEquals(editor.getText()));
        assertTrue("UiAutomation search key up was rejected", automation.injectInputEvent(
                new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0), true));
    }
    private boolean isPickerSearchEditor(AccessibilityNodeInfo node, String owner) {
        String resource = node.getViewIdResourceName(), role = String.valueOf(node.getClassName());
        return ((owner + ":id/search_src_text").equals(resource) || "android:id/search_src_text".equals(resource))
                && ("android.widget.EditText".equals(role) || "android.widget.AutoCompleteTextView".equals(role))
                && node.isEditable() && node.isEnabled() && node.isVisibleToUser()
                && owner.equals(ui.scope()) && owner.contentEquals(String.valueOf(node.getPackageName()))
                && node.getWindowId() == ui.activeWindow();
    }
    private void clickOwnedDocumentRow(AccessibilityNodeInfo title, AccessibilityNodeInfo row, String owner) {
        assertTrue("Owned document row must remain actionable in the active picker", row.refresh() && row.isVisibleToUser() && row.isEnabled()
                && owner.equals(ui.scope()) && owner.contentEquals(String.valueOf(row.getPackageName()))
                && row.getWindowId() == ui.activeWindow() && (owner + ":id/item_root").equals(row.getViewIdResourceName())
                && row.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertTrue("Owned UUID title must freshly identify its document", title.refresh() && title.isVisibleToUser()
                && owner.contentEquals(String.valueOf(title.getPackageName())) && title.getWindowId() == row.getWindowId()
                && "android:id/title".equals(title.getViewIdResourceName()) && TotpFixtureUi.exact(title, imageName));
        AccessibilityNodeInfo cursor = AccessibilityNodeInfo.obtain(title); boolean sameRow = false;
        try {
            for (int depth = 0; cursor != null && depth <= 6; depth++) {
                assertTrue("Refreshed title ancestry must stay inside its actual picker window", cursor.getWindowId() == row.getWindowId()
                        && owner.contentEquals(String.valueOf(cursor.getPackageName())));
                if ((owner + ":id/item_root").equals(cursor.getViewIdResourceName())) { sameRow = row.equals(cursor); break; }
                AccessibilityNodeInfo parent = cursor.getParent(); cursor.recycle(); cursor = parent;
            }
        } finally { if (cursor != null) cursor.recycle(); }
        Rect titleBounds = new Rect(), rowBounds = new Rect(); title.getBoundsInScreen(titleBounds); row.getBoundsInScreen(rowBounds);
        assertTrue("The refreshed exact UUID title must still belong to this same refreshed row", sameRow && !titleBounds.isEmpty()
                && rowBounds.contains(titleBounds) && row.getWindowId() == ui.activeWindow());
        assertTrue("Exact freshly bound document row rejected its advertised click", row.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private AccessibilityNodeInfo ownDocumentTitle(String owner) {
        return ui.unique(n -> "android:id/title".equals(n.getViewIdResourceName()) && TotpFixtureUi.exact(n, imageName) && !n.isEditable());
    }
    private void systemClick(AccessibilityNodeInfo node, String owner) {
        assertTrue("Only a bound foreground system control may act", owner.equals(ui.scope()) && node.refresh()
                && owner.contentEquals(String.valueOf(node.getPackageName())) && node.getWindowId() == ui.activeWindow()
                && node.isVisibleToUser() && node.isEnabled() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertTrue("Bound system control rejected advertised click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
}
