package im.majo.ctos;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/** Real release Flutter controls, in the opt-in fixture UID. No channel injection or Dart test entry. */
public final class AppSecretsUiFlowTest {
    private static final String TARGET = "im.majo.ctos.secretsfixture";
    private static final String TAG = "CtosSecretsUiFixture";
    private static final String MANUAL_SECURE_TEST = "freshFixtureManualSecureQuickAuthenticationFlow";
    private static final String MANUAL_SECURE_ARGUMENT = "ctosManualSecureQuickAuth";
    private static final long HUMAN_AUTH_TIMEOUT_MILLIS = 90000;
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context target;
    private UiAutomation automation;
    private Activity activity;
    private String runId;
    private String scope = TARGET;
    private boolean manualSecureFlow;
    private static final String DOCUMENTS = "com.android.documentsui";
    private static final String FIXTURE_LABEL = "ctOS secrets fixture";
    private final List<AccessibilityNodeInfo> boundEditors = new ArrayList<>();
    private String[] boundLabels = new String[0], boundValues = new String[0];

    @Before public void setup() throws Exception {
        manualSecureFlow = MANUAL_SECURE_TEST.equals(testName.getMethodName());
        runId = UUID.randomUUID().toString();
        if (manualSecureFlow) {
            String optIn = InstrumentationRegistry.getArguments().getString(MANUAL_SECURE_ARGUMENT);
            if (optIn == null) stage("manual_secure_not_run_missing_opt_in");
            org.junit.Assume.assumeTrue("Manual secure-device authentication was not requested", optIn != null);
            assertEquals("Manual authentication requires an explicit true argument", "true", optIn);
        }
        target = instrumentation.getTargetContext();
        assertEquals("Wrong instrumentation target; no fixture effects authorized", TARGET, target.getPackageName());
        assertEquals(TARGET + ".test", instrumentation.getContext().getPackageName());
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        assertTrue("Fixture needs an application UID", Process.myUid() >= Process.FIRST_APPLICATION_UID);
        PackageInfo info = target.getPackageManager().getPackageInfo(TARGET, 0);
        assertNull("A shared UID is not an isolated fixture", info.sharedUserId);
        assertTrue(target.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse("Root auto-start must be disabled before MainActivity", target.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        KeyguardManager keyguard = target.getSystemService(KeyguardManager.class);
        assertNotNull(keyguard);
        if (manualSecureFlow) {
            assertTrue("An already-secure device is required; this fixture never changes system security", keyguard.isDeviceSecure());
            assertFreshVault();
            assertFalse("A previous fixture marker requires explicit reconciliation", new File(target.getNoBackupFilesDir(), "secrets-ui-flow-owned.marker").exists());
            assertNoOwnQuickAliases();
        } else {
            assertFalse("This B fixture only covers the unavailable shortcut path", keyguard.isDeviceSecure());
        }
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        assertNoParentGrant();
    }
    @After public void closeActivity() {
        try {
            if (!manualSecureFlow && target != null && TARGET.equals(target.getPackageName()) && automation != null) fixtureNodeDiagnostics("after_test");
        } finally {
            try { clearEditors(); }
            finally { if (activity != null) instrumentation.runOnMainSync(activity::finish); }
        }
    }
    private void stage(String value) { Log.i(TAG, "run=" + runId + " phase=" + value); }
    private void launchSettings() {
        activity = instrumentation.startActivitySync(new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitText("ROOT 未连接", 15000);
        click("设置"); click("安全"); waitText("安全", 15000);
    }
    @Test public void semanticsProbeWithoutCreatingVault() throws Exception {
        assertFreshVault(); launchSettings(); waitText("尚未创建", 10000);
        click("创建秘密库"); waitEditors("主口令", "确认主口令");
        setEditor(0, "Probe-" + runId); setEditor(1, "Probe-" + runId);
        click("取消"); waitText("尚未创建", 10000);
        assertFreshVault(); stage("semantics_probe_pass_no_vault_created");
    }
    @Test public void readOwnFixtureDiagnostics() {
        // setup already asserted the actual fixed package, UID, Application and own Root preference.
        // This method does not launch or navigate an Activity, input text, invoke a vault API,
        // query a document provider, or modify a grant. Only this fixture UID is inspected.
        boolean vaultPresent = ownStatePresent("app-secrets.bin"), journalPresent = ownStatePresent("vault-export-journal.json");
        Log.i(TAG, "run=" + runId + " diagnostic=own_state uid=" + Process.myUid()
                + " vaultPresent=" + vaultPresent + " journalPresent=" + journalPresent + " markerPresent="
                + new File(target.getNoBackupFilesDir(), "secrets-ui-flow-owned.marker").exists()
                + " persistedGrantCount=" + target.getContentResolver().getPersistedUriPermissions().size());
        fixtureNodeDiagnostics("read_only");
        assertFalse("An outstanding own export journal must be reconciled before any reset", journalPresent);
        assertNoParentGrant();
        stage("own_fixture_read_only_diagnostics_pass_no_export_journal");
    }
    @Test public void readPrimaryStorageMapping() throws Exception {
        // No Activity, picker, directory query, navigation, grant or settings write.
        readOwnFixtureDiagnostics();
        String label = derivedPrimaryRootLabel();
        StorageVolume volume = target.getSystemService(StorageManager.class).getPrimaryStorageVolume();
        String description = volume.getDescription(target);
        Log.i(TAG, "run=" + runId + " diagnostic=primary_storage_mapping primary=" + volume.isPrimary()
                + " emulated=" + volume.isEmulated() + " derivedRootLabelLength=" + label.length()
                + " publicDescriptionEqual=" + label.equals(description));
        stage("source_derived_primary_storage_mapping_pass_no_picker_or_settings_write");
    }
    @Test public void freshFixtureManagementOnlyFlow() throws Exception { freshFlow(false); }
    @Test public void freshFixtureEndToEndFlow() throws Exception { freshFlow(true); }
    /** Select this method and -e ctosManualSecureQuickAuth true; a human completes both system prompts.
     * No credential UI is traversed or acted on. Failure retains the exact fixture state for review.
     */
    @Test public void freshFixtureManualSecureQuickAuthenticationFlow() throws Exception {
        assertTrue(manualSecureFlow);
        assertFreshVault();
        File marker = new File(target.getNoBackupFilesDir(), "secrets-ui-flow-owned.marker");
        assertTrue("Refuse unknown or previously used fixture data", marker.createNewFile());
        stage("manual_secure_fresh_fixture_start");
        launchSettings(); waitText("尚未创建", 10000);
        String password = "ManualFixture-" + UUID.randomUUID();
        byte[] contentBinding = null, wrapper = null;
        try {
            click("创建秘密库"); waitEditors("主口令", "确认主口令");
            setEditor(0, password); setEditor(1, password); clickSubmit("创建秘密库");
            waitText("开启本设备快捷解锁？", 30000); click("暂不开启");
            waitText("秘密库已创建。", 30000);
            byte[] initial = cipher();
            contentBinding = authenticatedEmptyContentBinding(initial, password);
            AppSecrets core = AppSecretsOwner.acquire(target);
            assertTrue("The real MainActivity owner must have an unlocked session", core.session.unlocked());
            assertRealSessionContent(core, contentBinding);
            assertNull(new VaultStore(target).quickMaterial());
            stage("manual_secure_create_and_decline_initial_offer_pass");

            click("开启本设备快捷解锁"); waitEditors("当前主口令");
            setEditor(0, password); clickSubmit("开启本设备快捷解锁"); clearEditors();
            awaitHumanAuthentication("manual_secure_enable_await_human", "本设备快捷解锁已开启。", core);
            wrapper = new VaultStore(target).quickMaterial();
            assertNotNull("Enrollment must persist a real device wrapper", wrapper);
            assertTrue(wrapper.length > 0);
            assertSameEmptyContent(initial, password, contentBinding);
            assertRealSessionContent(core, contentBinding);
            stage("manual_secure_real_wrapper_persisted_pass");

            click("立即锁定"); waitText("秘密库已锁定。", 10000);
            assertFalse(core.session.unlocked());
            click("解锁秘密库");
            awaitHumanAuthentication("manual_secure_unlock_await_human", "秘密库已解锁。", core);
            assertArrayEquals("Quick unlock must keep the persisted wrapper", wrapper, new VaultStore(target).quickMaterial());
            assertSameEmptyContent(initial, password, contentBinding);
            assertRealSessionContent(core, contentBinding);
            stage("manual_secure_real_quick_unlock_pass");

            click("关闭本设备快捷解锁"); waitEditors("当前主口令");
            setEditor(0, password); click("取消"); waitText("安全", 10000);
            assertTrue(core.session.unlocked());
            assertArrayEquals(wrapper, new VaultStore(target).quickMaterial());
            assertSameEmptyContent(initial, password, contentBinding);
            stage("manual_secure_disable_app_cancel_preserves_state_pass");

            click("关闭本设备快捷解锁"); waitEditors("当前主口令");
            setEditor(0, "WrongFixture-" + UUID.randomUUID()); clickSubmit("关闭本设备快捷解锁");
            waitText("口令错误或文件损坏，请重新输入。", 30000);
            assertTrue("Wrong management password must not change the real session", core.session.unlocked());
            assertArrayEquals(wrapper, new VaultStore(target).quickMaterial());
            assertSameEmptyContent(initial, password, contentBinding);
            stage("manual_secure_disable_wrong_password_preserves_state_pass");
            setEditor(0, password); clickSubmit("关闭本设备快捷解锁");
            waitText("本设备快捷解锁已关闭，秘密库已锁定。", 30000);
            assertFalse("Disabling the wrapper must lock the real owner session", core.session.unlocked());
            assertNull("Disabling must persist absence of the wrapper", new VaultStore(target).quickMaterial());
            awaitNoOwnQuickAliases();
            assertSameEmptyContent(initial, password, contentBinding);
            stage("manual_secure_disable_correct_password_locked_and_persisted_pass");

            click("解锁秘密库"); waitEditors("当前主口令");
            setEditor(0, password); clickSubmit("解锁秘密库");
            waitText("秘密库已解锁。", 30000);
            assertTrue("Master-password recovery must unlock the real owner session", core.session.unlocked());
            assertNull(new VaultStore(target).quickMaterial());
            assertSameEmptyContent(initial, password, contentBinding);
            assertRealSessionContent(core, contentBinding);
            assertNoParentGrant();
            stage("manual_secure_complete_empty_vault_pass_fixture_retained_for_reconciliation");
        } finally { clearEditors(); VaultCodec.wipe(contentBinding); VaultCodec.wipe(wrapper); }
    }
    private void awaitHumanAuthentication(String phase, String successLabel, AppSecrets core) throws Exception {
        stage(phase);
        // Do not even request accessibility roots while waiting for the human.
        // Observe only the actual own wrapper/session, then await the Flutter receipt.
        long deadline = SystemClock.elapsedRealtime() + HUMAN_AUTH_TIMEOUT_MILLIS;
        boolean completed = false;
        do {
            byte[] persisted = new VaultStore(target).quickMaterial();
            try { completed = persisted != null && core.session.unlocked(); }
            finally { VaultCodec.wipe(persisted); }
            if (completed) break;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Human system authentication did not complete; retained fixture needs reconciliation", completed);
        waitText(successLabel, 10000);
        assertTrue("System authentication must unlock the actual process-owned session", core.session.unlocked());
    }
    private void assertRealSessionContent(AppSecrets core, byte[] expectedBinding) throws Exception {
        byte[] key = core.session.key(), plaintext = null, binding = null;
        try {
            VaultEnvelope envelope = core.current(); assertNotNull(envelope);
            plaintext = envelope.decrypt(key); binding = VaultCodec.digest(plaintext);
            assertArrayEquals("The real session key must authenticate the unchanged library", expectedBinding, binding);
        } finally { VaultCodec.wipe(key); VaultCodec.wipe(plaintext); VaultCodec.wipe(binding); }
    }
    private void assertNoOwnQuickAliases() throws Exception {
        assertFalse("An own shortcut key requires explicit reconciliation", hasOwnQuickAlias());
    }
    private void awaitNoOwnQuickAliases() throws Exception {
        // Bridge completion and its owned asynchronous key cleanup are distinct.
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            if (!hasOwnQuickAlias()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Own shortcut key cleanup did not complete; retained state requires reconciliation");
    }
    private boolean hasOwnQuickAlias() throws Exception {
        assertEquals(TARGET, target.getPackageName());
        assertEquals(target.getApplicationInfo().uid, Process.myUid());
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements())
            if (aliases.nextElement().startsWith("ctos.vault.quick.")) return true;
        return false;
    }
    private byte[] authenticatedEmptyContentBinding(byte[] ciphertext, String password) throws Exception {
        char[] input = password.toCharArray();
        try (AppSecrets.Verified verified = AppSecrets.verify(ciphertext, input)) {
            assertEquals("This manual fixture covers an empty synthetic library only", 0, verified.manifest.entries.size());
            assertEquals("No business files are created by this fixture", 0, verified.manifest.files.size());
            return VaultCodec.digest(verified.plaintext);
        } finally { Arrays.fill(input, '\0'); }
    }
    private void assertSameEmptyContent(byte[] initial, String password, byte[] expectedBinding) throws Exception {
        byte[] current = cipher(), binding = null;
        try {
            assertArrayEquals("Shortcut operations must preserve portable ciphertext", initial, current);
            binding = authenticatedEmptyContentBinding(current, password);
            assertArrayEquals("Authenticated library content must be unchanged", expectedBinding, binding);
        } finally { VaultCodec.wipe(current); VaultCodec.wipe(binding); }
    }
    private void freshFlow(boolean includeSaf) throws Exception {
        assertFreshVault();
        File marker = new File(target.getNoBackupFilesDir(), "secrets-ui-flow-owned.marker");
        assertTrue("Refuse unknown or previously used fixture data", marker.createNewFile());
        stage("fresh_fixture_end_to_end_start");
        launchSettings(); waitText("尚未创建", 10000);
        String original = "Original-" + runId, changed = "Changed-" + runId;
        click("创建秘密库"); waitEditors("主口令", "确认主口令"); setEditor(0, original); setEditor(1, "Mismatch-" + runId);
        clickSubmit("创建秘密库"); waitText("两次口令不一致", 10000); fixtureNodeDiagnostics("mismatch_validation_visible"); assertFreshVault();
        setEditor(1, original); clickSubmit("创建秘密库"); waitText("秘密库已创建。", 30000);
        byte[] initial = cipher(); authenticate(initial, original); stage("create_and_confirmation_pass");
        click("更换主口令"); waitEditors("当前主口令", "新主口令", "确认新主口令"); setEditor(0, original); click("取消"); waitText("安全", 10000);
        assertArrayEquals(initial, cipher()); stage("management_cancel_preserves_cipher");
        click("立即锁定"); waitText("秘密库已锁定。", 10000); waitText("已锁定", 10000);
        click("解锁秘密库"); waitEditors("当前主口令"); setEditor(0, "Wrong-" + runId); clickSubmit("解锁秘密库"); waitText("口令错误或文件损坏，请重新输入。", 30000);
        assertArrayEquals(initial, cipher()); setEditor(0, original); clickSubmit("解锁秘密库"); waitText("秘密库已解锁。", 30000); waitText("已解锁", 10000); stage("manual_lock_wrong_password_correct_unlock_pass");
        click("更换主口令"); waitEditors("当前主口令", "新主口令", "确认新主口令"); setEditor(0, original); setEditor(1, changed); setEditor(2, changed); clickSubmit("更换主口令"); waitText("主口令已更换，旧备份仍使用旧口令。", 30000);
        byte[] rekeyed = cipher(); assertFalse(java.util.Arrays.equals(initial, rekeyed)); authenticate(rekeyed, changed);
        try { authenticate(rekeyed, original); fail("Old password must fail after rekey"); } catch (VaultException expected) { assertEquals("VAULT_AUTHENTICATION", expected.code); }
        stage("rekey_new_password_pass_old_password_rejected");
        if (!includeSaf) { stage("management_only_pass_no_picker_fixture_uid_retained_for_reviewer_cleanup"); return; }
        String leaf = "ctos-secrets-ui-" + runId, filename = "fixture-" + runId + ".ctos";
        startExport(changed, filename); chooseExportFolder(leaf, true);
        waitText("加密备份已保存。", 30000); click("返回");
        Uri tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/ctos/" + leaf);
        assertEquals("Actual picker must grant this exact, newly created directory", android.content.pm.PackageManager.PERMISSION_GRANTED,
                target.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        assertNoParentGrant();
        Uri backup = onlyBackup(tree, filename); byte[] exported = readBackup(backup); authenticate(exported, changed);
        assertArrayEquals(rekeyed, exported); stage("actual_saf_new_export_verified");
        String latest = "Latest-" + runId;
        click("更换主口令"); waitEditors("当前主口令", "新主口令", "确认新主口令"); setEditor(0, changed); setEditor(1, latest); setEditor(2, latest); clickSubmit("更换主口令"); waitText("主口令已更换，旧备份仍使用旧口令。", 30000);
        byte[] latestCipher = cipher(); authenticate(latestCipher, latest);
        startExport(latest, filename); chooseExportFolder(leaf, false); waitText("确认覆盖此同名文件", 30000);
        assertArrayEquals("Collision preview must preserve original bytes", exported, readBackup(backup));
        click("取消"); waitText("导出已取消，本次暂存已清理。", 30000); click("返回");
        assertArrayEquals(exported, readBackup(backup)); stage("actual_saf_collision_cancel_preserves_old_bytes");
        startExport(latest, filename); chooseExportFolder(leaf, false); waitText("确认覆盖此同名文件", 30000);
        assertArrayEquals(exported, readBackup(backup)); click("确认覆盖此同名文件"); click("覆盖并导出");
        waitText("加密备份已保存。", 30000); click("返回"); backup = onlyBackup(tree, filename);
        byte[] overwritten = readBackup(backup); assertArrayEquals(latestCipher, overwritten); authenticate(overwritten, latest);
        stage("actual_saf_explicit_overwrite_verified_no_residue");
        String destination = "Destination-" + runId;
        click("更换主口令"); waitEditors("当前主口令", "新主口令", "确认新主口令"); setEditor(0, latest); setEditor(1, destination); setEditor(2, destination);
        clickSubmit("更换主口令"); waitText("主口令已更换，旧备份仍使用旧口令。", 30000);
        byte[] destinationCipher = cipher(); authenticate(destinationCipher, destination);
        assertFalse(Arrays.equals(overwritten, destinationCipher));
        startImport("Wrong-" + runId, destination); chooseImportFile(leaf, filename); waitText("口令错误或文件损坏，请重新输入。", 30000);
        assertArrayEquals(destinationCipher, cipher()); click("取消"); waitText("安全", 10000);
        startImport(latest, "Wrong-" + runId); chooseImportFile(leaf, filename); waitText("口令错误或文件损坏，请重新输入。", 30000);
        assertArrayEquals(destinationCipher, cipher()); click("取消"); waitText("安全", 10000);
        startImport(latest, destination); chooseImportFile(leaf, filename); waitText("审阅加密备份", 30000);
        click("取消导入"); waitText("安全", 10000); assertArrayEquals(destinationCipher, cipher());
        stage("actual_saf_import_wrong_source_target_and_cancel_preserve_cipher");
        startImport(latest, destination); chooseImportFile(leaf, filename); waitText("审阅加密备份", 30000);
        click("确认覆盖全部现有库内容"); click("确认导入"); waitText("备份已导入，使用来源备份主口令解锁。", 30000);
        byte[] importedCipher = cipher();
        verifyImportedGeneration(overwritten, destinationCipher, importedCipher, latest);
        waitText("已锁定", 10000);
        try { authenticate(importedCipher, destination); fail("Destination password must cease to unlock the imported source vault"); }
        catch (VaultException expected) { assertEquals("VAULT_AUTHENTICATION", expected.code); }
        click("解锁秘密库"); waitEditors("当前主口令"); setEditor(0, destination); clickSubmit("解锁秘密库"); waitText("口令错误或文件损坏，请重新输入。", 30000);
        assertArrayEquals(importedCipher, cipher()); setEditor(0, latest); clickSubmit("解锁秘密库"); waitText("秘密库已解锁。", 30000); waitText("已解锁", 10000);
        assertArrayEquals(importedCipher, cipher());
        stage("actual_saf_import_confirm_and_settings_return_verified");
        assertNoParentGrant();
        // Cleanup only after all host operations have returned terminal outcomes. On test failure,
        // leave the exact UUID leaf for reviewer reconciliation; never race an unknown worker.
        backup = onlyBackup(tree, filename); assertArrayEquals(overwritten, readBackup(backup));
        assertTrue(DocumentsContract.deleteDocument(target.getContentResolver(), backup));
        assertEquals(0, childCount(tree));
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        assertTrue(DocumentsContract.deleteDocument(target.getContentResolver(), folder));
        target.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                target.checkUriPermission(tree, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION));
        assertNoParentGrant();
        stage("exact_owned_saf_leaf_cleanup_pass");
        // The fixture package and exact marker are intentionally retained for reviewer-owned UID cleanup.
        // No production data, aliases, broad file cleanup, or Root command is used by this test.
        stage("end_to_end_pass_fixture_uid_retained_for_reviewer_cleanup");
    }
    private void assertFreshVault() {
        for (String name : new String[] {"app-secrets.bin", "app-secrets.bin.bak", "app-secrets.bin.new", "vault-export-journal.json", "vault-export-journal.json.bak", "vault-export-journal.json.new"}) {
            assertFalse("Fresh fixture required; no clearing is permitted", new File(target.getNoBackupFilesDir(), name).exists());
        }
    }
    private boolean ownStatePresent(String name) {
        for (String suffix : new String[] { "", ".bak", ".new" })
            if (new File(target.getNoBackupFilesDir(), name + suffix).exists()) return true;
        return false;
    }
    private void startExport(String password, String filename) {
        click("导出加密备份"); waitEditors("当前主口令", "备份文件名"); setEditor(0, password); setEditor(1, filename);
        clickSubmit("验证并选择保存位置");
    }
    private void startImport(String sourcePassword, String targetPassword) {
        click("导入加密备份"); waitEditors("来源备份主口令", "当前秘密库主口令"); setEditor(0, sourcePassword); setEditor(1, targetPassword);
        clickSubmit("选择备份并验证");
    }
    private void documentsScope() {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                String pkg = String.valueOf(root.getPackageName()); root.recycle();
                if (DOCUMENTS.equals(pkg) || "com.google.android.documentsui".equals(pkg)) { scope = pkg; return; }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Actual system DocumentsUI did not become active");
    }
    private void downloadDirectory() throws Exception {
        // The installed B provider derives primary root.title from the public device_name.
        // With one root DocumentsUI deliberately hides the drawer navigation icon.
        // Bind the exact current breadcrumb to that rule; never guess an arbitrary root.
        String rootLabel = derivedPrimaryRootLabel();
        AccessibilityNodeInfo breadcrumb = find(n -> n.getWindowId() == activeScopeWindowId()
                && (scope + ":id/horizontal_breadcrumb").equals(n.getViewIdResourceName()));
        assertNotNull("Exact primary breadcrumb must be available before navigation", breadcrumb);
        AccessibilityNodeInfo rootTitle = null;
        try {
            List<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(AccessibilityNodeInfo.obtain(breadcrumb));
            try {
                for (int index = 0; index < queue.size() && index < 100; index++) {
                    AccessibilityNodeInfo node = queue.get(index);
                    if (node.isVisibleToUser() && node.getText() != null && node.getText().length() > 0) {
                        assertEquals("Primary title must stay inside the active system picker", activeScopeWindowId(), node.getWindowId());
                        assertTrue("Primary title must remain in the exact system package", scope.contentEquals(String.valueOf(node.getPackageName())));
                        assertTrue("Current breadcrumb root does not match the source-derived primary label", rootLabel.contentEquals(node.getText()));
                        rootTitle = AccessibilityNodeInfo.obtain(node); break;
                    }
                    for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
                }
            } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
            assertNotNull("Primary root breadcrumb title unavailable", rootTitle);
            if (!breadcrumbEquals()) assertTrue("Exact primary breadcrumb root was not actionable", clickPrimaryBreadcrumbRoot(rootTitle, breadcrumb));
        } finally { breadcrumb.recycle(); if (rootTitle != null) rootTitle.recycle(); }
        assertBreadcrumb();
        clickExact("Download"); assertBreadcrumb("Download");
        clickExact("ctos"); assertBreadcrumb("Download", "ctos");
    }
    private boolean clickPrimaryBreadcrumbRoot(AccessibilityNodeInfo title, AccessibilityNodeInfo breadcrumb) {
        Rect breadcrumbBounds = new Rect(); breadcrumb.getBoundsInScreen(breadcrumbBounds);
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(title);
        try {
            for (int depth = 0; node != null && depth < 4; depth++) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (!scope.contentEquals(String.valueOf(node.getPackageName())) || node.getWindowId() != activeScopeWindowId()
                        || !node.isVisibleToUser() || bounds.isEmpty() || !breadcrumbBounds.contains(bounds)) return false;
                if (node.isEnabled() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (node.equals(breadcrumb)) return false;
                AccessibilityNodeInfo parent = node.getParent(); node.recycle(); node = parent;
            }
            return false;
        } finally { if (node != null) node.recycle(); }
    }
    private void chooseExportFolder(String leaf, boolean create) throws Exception {
        documentsScope(); downloadDirectory();
        if (create) {
            assertBreadcrumb("Download", "ctos");
            if (!tryClickResource(scope + ":id/option_menu_create_dir")) {
                clickAny("More options", "更多选项"); clickAny("New folder", "新建文件夹");
            }
            waitEditors(1); setEditor(0, leaf); clickAny("OK", "确定");
            // The installed PickActivity asynchronously opens its newly created directory.
            // Its final breadcrumb deliberately has no CLICK action. Wait for that exact
            // automatic navigation instead of trying to click a stale parent-list entry.
            assertBreadcrumbWithin(10000, "Download", "ctos", leaf);
        } else {
            clickExact(leaf);
            assertBreadcrumb("Download", "ctos", leaf);
        }
        assertBreadcrumb("Download", "ctos", leaf);
        clickAny("Use this folder", "使用此文件夹");
        int dialogWindow = assertGrantDialog(leaf);
        AccessibilityNodeInfo allow = find(n -> n.getWindowId() == dialogWindow && n.isEnabled()
                && ("android:id/button1".equals(n.getViewIdResourceName()) || (scope + ":id/button1").equals(n.getViewIdResourceName())));
        assertNotNull("Verified system dialog positive action unavailable; no grant authorized", allow);
        try {
            assertTrue("Verified consent must still be the active system window", allow.refresh() && allow.getWindowId() == activeScopeWindowId()
                    && scope.contentEquals(String.valueOf(allow.getPackageName())) && allow.isVisibleToUser() && allow.isEnabled()
                    && allow.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            assertTrue("Verified system dialog positive action rejected", allow.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        }
        finally { allow.recycle(); }
        scope = TARGET;
    }
    private void chooseImportFile(String leaf, String filename) throws Exception {
        documentsScope(); downloadDirectory(); clickExact(leaf); assertBreadcrumb("Download", "ctos", leaf);
        clickOwnedImportDocument(leaf, filename); scope = TARGET;
    }
    private void clickOwnedImportDocument(String leaf, String filename) {
        assertTrue("Owned backup selection requires the actual system picker", DOCUMENTS.equals(scope) || "com.google.android.documentsui".equals(scope));
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do {
            if (!breadcrumbEquals("Download", "ctos", leaf)) { SystemClock.sleep(100); continue; }
            AccessibilityNodeInfo title = find(n -> "android:id/title".equals(n.getViewIdResourceName())
                    && "android.widget.TextView".contentEquals(String.valueOf(n.getClassName())) && n.isEnabled()
                    && n.getText() != null && filename.contentEquals(n.getText()));
            if (title == null) { SystemClock.sleep(100); continue; }
            AccessibilityNodeInfo row = AccessibilityNodeInfo.obtain(title);
            try {
                for (int depth = 0; row != null && depth < 12; depth++) {
                    if (!row.refresh() || row.getWindowId() != activeScopeWindowId()
                            || !scope.contentEquals(String.valueOf(row.getPackageName())) || !row.isVisibleToUser()) break;
                    if ((scope + ":id/dir_list").equals(row.getViewIdResourceName())) break;
                    if ((scope + ":id/item_root").equals(row.getViewIdResourceName())) {
                        // The real B document row is depth 6 and its delegate advertises CLICK.
                        // Only this explicit row boundary may be clicked; never its list or screen.
                        Rect titleBounds = new Rect(), rowBounds = new Rect();
                        if (!title.refresh() || !"android:id/title".equals(title.getViewIdResourceName())
                                || title.getText() == null || !filename.contentEquals(title.getText())
                                || !title.isVisibleToUser() || !title.isEnabled() || title.getWindowId() != row.getWindowId()
                                || !scope.contentEquals(String.valueOf(title.getPackageName()))) break;
                        title.getBoundsInScreen(titleBounds); row.getBoundsInScreen(rowBounds);
                        AccessibilityNodeInfo list = row.getParent();
                        boolean rowProven;
                        try {
                            rowProven = list != null && (scope + ":id/dir_list").equals(list.getViewIdResourceName())
                                    && "androidx.recyclerview.widget.RecyclerView".contentEquals(String.valueOf(list.getClassName()))
                                    && list.getWindowId() == row.getWindowId() && scope.contentEquals(String.valueOf(list.getPackageName()));
                        } finally { if (list != null) list.recycle(); }
                        if (!rowProven || !"android.widget.FrameLayout".contentEquals(String.valueOf(row.getClassName()))
                                || titleBounds.isEmpty() || rowBounds.isEmpty() || !rowBounds.contains(titleBounds)
                                || !breadcrumbEquals("Download", "ctos", leaf)) break;
                        if (!title.refresh() || !"android:id/title".equals(title.getViewIdResourceName())
                                || !"android.widget.TextView".contentEquals(String.valueOf(title.getClassName()))
                                || title.getText() == null || !filename.contentEquals(title.getText())
                                || !title.isVisibleToUser() || !title.isEnabled() || title.getWindowId() != activeScopeWindowId()
                                || !scope.contentEquals(String.valueOf(title.getPackageName())) || !titleBelongsToBoundDocumentRow(title, row)) break;
                        if (!row.refresh() || row.getWindowId() != activeScopeWindowId()
                                || !scope.contentEquals(String.valueOf(row.getPackageName())) || !row.isVisibleToUser() || !row.isEnabled()
                                || !(scope + ":id/item_root").equals(row.getViewIdResourceName())
                                || !"android.widget.FrameLayout".contentEquals(String.valueOf(row.getClassName()))
                                || row.getWindowId() != title.getWindowId()
                                || !row.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) break;
                        title.getBoundsInScreen(titleBounds); row.getBoundsInScreen(rowBounds);
                        if (titleBounds.isEmpty() || rowBounds.isEmpty() || !rowBounds.contains(titleBounds)) break;
                        assertTrue("Exact owned document row rejected its advertised CLICK", row.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                        return;
                    }
                    AccessibilityNodeInfo parent = row.getParent(); row.recycle(); row = parent;
                }
            } finally { title.recycle(); if (row != null) row.recycle(); }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Exact owned document row and its advertised CLICK were unavailable within the proven directory");
    }
    private boolean titleBelongsToBoundDocumentRow(AccessibilityNodeInfo title, AccessibilityNodeInfo row) {
        AccessibilityNodeInfo ancestor = AccessibilityNodeInfo.obtain(title);
        try {
            for (int depth = 0; ancestor != null && depth < 12; depth++) {
                if (!ancestor.refresh() || ancestor.getWindowId() != activeScopeWindowId()
                        || !scope.contentEquals(String.valueOf(ancestor.getPackageName())) || !ancestor.isVisibleToUser()) return false;
                if ((scope + ":id/dir_list").equals(ancestor.getViewIdResourceName())) return false;
                if ((scope + ":id/item_root").equals(ancestor.getViewIdResourceName())) return ancestor.equals(row);
                AccessibilityNodeInfo parent = ancestor.getParent(); ancestor.recycle(); ancestor = parent;
            }
            return false;
        } finally { if (ancestor != null) ancestor.recycle(); }
    }
    private void assertNoParentGrant() {
        assertTrue("The fixture must never persist picker grants", target.getContentResolver().getPersistedUriPermissions().isEmpty());
        for (String id : new String[] { "primary:", "primary:Download", "primary:Download/ctos" }) {
            Uri parent = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id);
            for (int mode : new int[] { Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION })
                assertEquals("A parent grant is outside the exact owned fixture leaf", android.content.pm.PackageManager.PERMISSION_DENIED,
                        target.checkUriPermission(parent, Process.myPid(), Process.myUid(), mode));
        }
    }
    private boolean breadcrumbEquals(String... suffix) {
        AccessibilityNodeInfo breadcrumb = find(n -> (scope + ":id/horizontal_breadcrumb").equals(n.getViewIdResourceName()));
        if (breadcrumb == null) return false;
        List<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(breadcrumb); List<String> titles = new ArrayList<>();
        try {
            for (int index = 0; index < queue.size() && index < 100; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (node.isVisibleToUser() && node.getText() != null && node.getText().length() > 0) titles.add(node.getText().toString());
                for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
            }
            if (queue.size() > 100 || titles.size() != suffix.length + 1) return false;
            try { if (!derivedPrimaryRootLabel().equals(titles.get(0))) return false; }
            catch (Exception unavailable) { return false; }
            return titles.subList(1, titles.size()).equals(Arrays.asList(suffix));
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private String derivedPrimaryRootLabel() throws Exception {
        assertEquals("Primary mapping is confined to the fixed fixture UID", TARGET, target.getPackageName());
        StorageManager manager = target.getSystemService(StorageManager.class);
        assertNotNull("Public primary storage metadata unavailable", manager);
        StorageVolume primary = manager.getPrimaryStorageVolume();
        assertNotNull("No primary storage volume", primary);
        assertTrue("Only the installed provider's primary emulated title rule is supported", primary.isPrimary() && primary.isEmulated());
        android.content.pm.ProviderInfo provider = target.getPackageManager().resolveContentProvider("com.android.externalstorage.documents", 0);
        assertNotNull("Expected platform storage provider unavailable", provider);
        assertEquals("com.android.externalstorage", provider.packageName);
        PackageInfo version = target.getPackageManager().getPackageInfo(provider.packageName, 0);
        assertEquals("Primary title rule was reviewed only for this installed platform provider", 30L, version.getLongVersionCode());
        assertEquals("11", version.versionName);
        String label = Settings.Global.getString(target.getContentResolver(), "device_name");
        if (label == null || label.isEmpty()) {
            Resources resources = target.getPackageManager().getResourcesForApplication(provider.packageName);
            int id = resources.getIdentifier("root_internal_storage", "string", provider.packageName);
            assertTrue("Installed provider primary title fallback unavailable", id != 0);
            label = resources.getString(id);
        }
        assertTrue("Primary root label must be bounded and nonempty", label != null && !label.isEmpty() && label.length() <= 256);
        for (int index = 0; index < label.length(); index++) assertFalse("Primary root label contains unsupported controls", Character.isISOControl(label.charAt(index)));
        return label;
    }
    private void assertBreadcrumb(String... suffix) {
        assertBreadcrumbWithin(3000, suffix);
    }
    private void assertBreadcrumbWithin(long milliseconds, String... suffix) {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        do { if (breadcrumbEquals(suffix)) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Current system directory cannot be proved from its exact bounded breadcrumb; no grant authorized");
    }
    private int assertGrantDialog(String leaf) throws Exception {
        Resources resources = target.getPackageManager().getResourcesForApplication(scope);
        int titleId = resources.getIdentifier("open_tree_dialog_title", "string", scope);
        int messageId = resources.getIdentifier("open_tree_dialog_message", "string", scope);
        assertTrue("Unsupported system grant dialog; no grant authorized", titleId != 0 && messageId != 0);
        String expectedTitle = resources.getString(titleId, FIXTURE_LABEL, leaf), expectedMessage = resources.getString(messageId, FIXTURE_LABEL, leaf);
        long deadline = SystemClock.elapsedRealtime() + 3000;
        do {
            AccessibilityNodeInfo title = find(n -> ("android:id/alertTitle".equals(n.getViewIdResourceName()) || (scope + ":id/alertTitle").equals(n.getViewIdResourceName()))
                    && n.getText() != null && expectedTitle.contentEquals(n.getText()));
            AccessibilityNodeInfo message = find(n -> ("android:id/message".equals(n.getViewIdResourceName()) || (scope + ":id/message").equals(n.getViewIdResourceName()))
                    && n.getText() != null && expectedMessage.contentEquals(n.getText()));
            boolean matched = title != null && message != null && title.getWindowId() == message.getWindowId();
            int windowId = title == null ? -1 : title.getWindowId();
            if (title != null) title.recycle(); if (message != null) message.recycle();
            if (matched) return windowId;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("System grant dialog did not exactly bind the fixed fixture label and this UUID directory; no grant authorized");
        throw new AssertionError("Unreachable");
    }
    private Uri onlyBackup(Uri tree, String filename) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = target.getContentResolver().query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            assertNotNull("Selected directory enumeration unavailable", cursor);
            assertTrue("Exactly one owned backup expected", cursor.moveToNext());
            assertEquals(filename, cursor.getString(1)); String id = cursor.getString(0);
            assertFalse("Export must leave no temporary or rollback documents", cursor.moveToNext());
            assertFalse("Loading enumeration cannot prove cleanup", cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false));
            Uri result = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            assertTrue(DocumentsContract.isChildDocument(target.getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), result));
            return result;
        }
    }
    private int childCount(Uri tree) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = target.getContentResolver().query(children, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null)) {
            assertNotNull(cursor); int count = 0;
            while (cursor.moveToNext()) { assertTrue("Unexpected directory contents; refuse broad cleanup", ++count <= 2); }
            assertFalse(cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)); return count;
        }
    }
    private byte[] readBackup(Uri uri) throws Exception {
        try (InputStream input = target.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull(input); byte[] buffer = new byte[4096]; int size;
            while ((size = input.read(buffer)) != -1) {
                assertTrue("Synthetic backup exceeded expected bound", output.size() + size <= 1024 * 1024); output.write(buffer, 0, size);
            }
            return output.toByteArray();
        }
    }
    private byte[] cipher() throws Exception { byte[] value = new VaultStore(target).read(); assertNotNull(value); return value; }
    private void verifyImportedGeneration(byte[] backup, byte[] destination, byte[] imported, String sourcePassword) throws Exception {
        VaultEnvelope source = VaultEnvelope.parse(backup), previous = VaultEnvelope.parse(destination), restored = VaultEnvelope.parse(imported);
        // Production import keeps source password material, creates a new local identity,
        // encrypts the complete authenticated manifest with a new nonce, and removes wrappers.
        assertNotEquals("Import must not reuse the source vault identity", source.id, restored.id);
        assertNotEquals("Import must not reuse the destination vault identity", previous.id, restored.id);
        assertEquals("Imported local identity starts at its first generation", 1L, restored.generation);
        assertArrayEquals("Source password salt remains portable", source.salt, restored.salt);
        assertFalse("Import must use a fresh authenticated nonce", Arrays.equals(source.nonce, restored.nonce));
        assertFalse("Source material context must not bind the new local vault", Arrays.equals(source.materialBinding(), restored.materialBinding()));
        assertFalse("Destination material context must not bind the imported vault", Arrays.equals(previous.materialBinding(), restored.materialBinding()));
        char[] password = sourcePassword.toCharArray(); byte[] key = null, sourcePlaintext = null, restoredPlaintext = null;
        try {
            key = VaultEnvelope.derive(password, source.salt);
            sourcePlaintext = source.decrypt(key); restoredPlaintext = restored.decrypt(key);
            try (VaultManifest sourceManifest = VaultManifest.decode(sourcePlaintext); VaultManifest restoredManifest = VaultManifest.decode(restoredPlaintext)) {
                assertTrue("The complete authenticated manifest must be preserved", java.security.MessageDigest.isEqual(sourcePlaintext, restoredPlaintext));
                assertEquals("This fixture covers an empty library only", 0, sourceManifest.entries.size());
                assertEquals("This fixture has no business files", 0, sourceManifest.files.size());
                assertEquals(sourceManifest.entries.size(), restoredManifest.entries.size());
                assertEquals(sourceManifest.files.size(), restoredManifest.files.size());
            }
        } finally { Arrays.fill(password, '\0'); VaultCodec.wipe(key); VaultCodec.wipe(sourcePlaintext); VaultCodec.wipe(restoredPlaintext); }
        assertNull("Import must not restore a device shortcut wrapper", new VaultStore(target).quickMaterial());
    }
    private void authenticate(byte[] ciphertext, String password) throws Exception {
        VaultEnvelope envelope = VaultEnvelope.parse(ciphertext); char[] input = password.toCharArray(); byte[] key = null, plain = null;
        try { key = VaultEnvelope.derive(input, envelope.salt); plain = envelope.decrypt(key); assertTrue(plain.length > 0); }
        finally { java.util.Arrays.fill(input, '\0'); VaultCodec.wipe(key); VaultCodec.wipe(plain); }
    }
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> values = new ArrayList<>();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null) { if (scope.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) values.add(root); else root.recycle(); }
        return values;
    }
    private interface Match { boolean accepts(AccessibilityNodeInfo node); }
    private AccessibilityNodeInfo find(Match match) {
        int activeWindow = activeScopeWindowId();
        if (activeWindow < 0) return null;
        List<AccessibilityNodeInfo> queue = roots(); AccessibilityNodeInfo result = null; int visited = 0;
        try {
            for (int index = 0; index < queue.size() && visited++ < 1500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (node.getWindowId() == activeWindow && scope.contentEquals(String.valueOf(node.getPackageName()))
                        && node.isVisibleToUser() && match.accepts(node)) { result = AccessibilityNodeInfo.obtain(node); break; }
                for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
            }
            return result;
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private static boolean text(AccessibilityNodeInfo node, String value) {
        String expected = value.toLowerCase(Locale.ROOT);
        return node.getText() != null && node.getText().toString().toLowerCase(Locale.ROOT).contains(expected)
                || node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase(Locale.ROOT).contains(expected)
                || node.getHintText() != null && node.getHintText().toString().toLowerCase(Locale.ROOT).contains(expected);
    }
    private void waitText(String value, long milliseconds) {
        assertEquals("Expected application state must come from the fixture package", TARGET, scope);
        long deadline = SystemClock.elapsedRealtime() + milliseconds; int attempts = 0;
        do {
            int activeWindow = activeScopeWindowId();
            AccessibilityNodeInfo node = activeWindow < 0 ? null : find(n -> n.getWindowId() == activeWindow && semanticExact(n, value));
            if (node != null) { node.recycle(); return; }
            if (attempts++ % 4 == 0) { if (attempts % 36 == 1) scrollToStart(); else scroll(); }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Expected fixture phase did not appear: " + value);
    }
    private static boolean semanticExact(AccessibilityNodeInfo node, String expected) {
        for (CharSequence candidate : new CharSequence[] { node.getText(), node.getContentDescription(), node.getHintText(), node.getTooltipText() }) {
            if (candidate == null) continue;
            for (String component : candidate.toString().split("\\n|, ")) if (component.trim().equals(expected)) return true;
        }
        return false;
    }
    private void clickSubmit(String label) {
        clickFixtureControl(label, true);
    }
    private void clickFixtureControl(String label, boolean buttonOnly) {
        assertEquals("A form submission is authorized only inside the fixture", TARGET, scope);
        long deadline = SystemClock.elapsedRealtime() + 10000; int attempts = 0;
        do {
            int activeWindow = activeScopeWindowId();
            AccessibilityNodeInfo node = activeWindow < 0 ? null : find(n -> n.getWindowId() == activeWindow && n.isEnabled() && !n.isEditable() && fixtureRole(n, label, buttonOnly)
                    && semanticExact(n, label) && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            if (node != null) {
                try { nodeMetadata(node, "direct_fixture_control", null, null); assertTrue("Direct fixture control action rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return; }
                finally { node.recycle(); }
            }
            if (attempts++ % 4 == 0) { if (attempts % 36 == 1) scrollToStart(); else scroll(); }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The exact fixture control's own advertised CLICK and role were unavailable");
    }
    private static boolean fixtureRole(AccessibilityNodeInfo node, String label, boolean buttonOnly) {
        if ("android.widget.Button".contentEquals(String.valueOf(node.getClassName()))) return true;
        if (buttonOnly) return false;
        if ("设置".equals(label)) return "android.view.View".contentEquals(String.valueOf(node.getClassName()))
                || "android.widget.RadioButton".contentEquals(String.valueOf(node.getClassName()));
        if ("安全".equals(label)) return "android.view.View".contentEquals(String.valueOf(node.getClassName()));
        return Arrays.asList("确认覆盖此同名文件", "确认覆盖全部现有库内容").contains(label)
                && "android.widget.CheckBox".contentEquals(String.valueOf(node.getClassName())) && node.isCheckable();
    }
    private void click(String value) {
        if (TARGET.equals(scope)) { clickFixtureControl(value, false); return; }
        long deadline = SystemClock.elapsedRealtime() + 10000;
        for (int attempts = 0; SystemClock.elapsedRealtime() < deadline; attempts++) {
            if (tryClickAny(value)) return;
            if (attempts % 3 == 0) scroll(); SystemClock.sleep(150);
        }
        fail("Clickable fixture control not found: " + value);
    }
    private void clickExact(String value) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do {
            AccessibilityNodeInfo node = find(n -> n.isEnabled() && hasClickTarget(n)
                    && (n.getText() != null && value.contentEquals(n.getText()) || n.getContentDescription() != null && value.contentEquals(n.getContentDescription())));
            if (node != null) {
                try { if (clickTarget(node)) return; } finally { node.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Exact owned system document was not actionable");
    }
    private boolean clickTarget(AccessibilityNodeInfo source) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(source);
        try {
            for (int depth = 0; node != null && depth < 6; depth++) {
                if (!node.refresh() || node.getWindowId() != activeScopeWindowId()
                        || !scope.contentEquals(String.valueOf(node.getPackageName())) || !node.isVisibleToUser()) return false;
                if (node.isEnabled() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                        && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = node.getParent(); node.recycle(); node = parent;
                if (node != null && !scope.contentEquals(String.valueOf(node.getPackageName()))) break;
            }
            return false;
        } finally { if (node != null) node.recycle(); }
    }
    private boolean tryClickAny(String... values) {
        for (String value : values) {
            AccessibilityNodeInfo node = find(n -> text(n, value) && n.isEnabled() && hasClickTarget(n));
            if (node == null) continue;
            try { if (clickTarget(node)) return true; } finally { node.recycle(); }
        }
        return false;
    }
    private boolean hasClickTarget(AccessibilityNodeInfo source) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(source);
        try {
            for (int depth = 0; node != null && depth < 6; depth++) {
                if (node.getWindowId() != activeScopeWindowId() || !scope.contentEquals(String.valueOf(node.getPackageName()))
                        || !node.isVisibleToUser()) return false;
                if (node.isEnabled() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = node.getParent(); node.recycle(); node = parent;
                if (node != null && !scope.contentEquals(String.valueOf(node.getPackageName()))) break;
            }
            return false;
        } finally { if (node != null) node.recycle(); }
    }
    private void clickAny(String... values) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do { if (tryClickAny(values)) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Expected system semantic action unavailable");
    }
    private boolean tryClickResource(String id) {
        AccessibilityNodeInfo node = find(n -> id.equals(n.getViewIdResourceName()) && n.isEnabled());
        if (node == null) return false;
        try {
            return node.refresh() && node.getWindowId() == activeScopeWindowId()
                    && scope.contentEquals(String.valueOf(node.getPackageName())) && node.isVisibleToUser() && node.isEnabled()
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally { node.recycle(); }
    }
    private void clickResource(String id) { assertTrue("System navigation semantic action unavailable", tryClickResource(id)); }
    private void scroll() {
        scrollDirection(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }
    private void scrollToStart() {
        for (int step = 0; step < 12; step++) {
            if (!scrollDirection(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)) return;
            SystemClock.sleep(80);
        }
    }
    private boolean scrollDirection(AccessibilityNodeInfo.AccessibilityAction action) {
        int windowId = activeScopeWindowId();
        if (windowId < 0) return false;
        AccessibilityNodeInfo active = automation.getRootInActiveWindow(); Rect windowBounds = new Rect();
        if (active == null) return false;
        try { if (active.getWindowId() != windowId || !scope.contentEquals(String.valueOf(active.getPackageName()))) return false; active.getBoundsInScreen(windowBounds); }
        finally { active.recycle(); }
        AccessibilityNodeInfo node = enclosingFormScroll(windowId, windowBounds, action);
        if (node == null) node = uniquePageScroll(windowId, windowBounds, action);
        if (node == null) return false;
        try { scrollMetadata(node, "scroll_action"); return node.performAction(action.getId()); } finally { node.recycle(); }
    }
    private boolean verticalScrollRole(AccessibilityNodeInfo node, int windowId, Rect windowBounds,
            AccessibilityNodeInfo.AccessibilityAction action) {
        if (node.getWindowId() != windowId || !scope.contentEquals(String.valueOf(node.getPackageName()))
                || node.isEditable() || !node.isEnabled() || !node.isScrollable() || !node.getActionList().contains(action)) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (bounds.width() < 64 || bounds.height() < 64 || !windowBounds.contains(bounds)) return false;
        if ("android.widget.ScrollView".contentEquals(String.valueOf(node.getClassName()))) return true;
        AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        return TARGET.equals(scope) && "android.view.View".contentEquals(String.valueOf(node.getClassName()))
                && collection != null && collection.getRowCount() > 0 && collection.getRowCount() <= 4096
                && collection.getColumnCount() == 0 && !collection.isHierarchical()
                && collection.getSelectionMode() == AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE;
    }
    private AccessibilityNodeInfo enclosingFormScroll(int windowId, Rect windowBounds, AccessibilityNodeInfo.AccessibilityAction action) {
        for (int index = 0; index < boundEditors.size(); index++) {
            AccessibilityNodeInfo editor = boundEditors.get(index);
            if (!editor.refresh() || editor.getWindowId() != windowId || !editor.isEditable()
                    || !scope.contentEquals(String.valueOf(editor.getPackageName()))
                    || boundLabels[index] == null || !fieldLabel(editor, boundLabels[index])) continue;
            AccessibilityNodeInfo parent = editor.getParent();
            try {
                for (int depth = 0; parent != null && depth < 12; depth++) {
                    if (verticalScrollRole(parent, windowId, windowBounds, action)) return AccessibilityNodeInfo.obtain(parent);
                    AccessibilityNodeInfo next = parent.getParent(); parent.recycle(); parent = next;
                }
            } finally { if (parent != null) parent.recycle(); }
        }
        return null;
    }
    private AccessibilityNodeInfo uniquePageScroll(int windowId, Rect windowBounds, AccessibilityNodeInfo.AccessibilityAction action) {
        List<AccessibilityNodeInfo> queue = roots(); AccessibilityNodeInfo match = null;
        try {
            for (int index = 0; index < queue.size() && index < 1500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (node.isVisibleToUser() && verticalScrollRole(node, windowId, windowBounds, action)
                        && (knownToolbarPage(node, windowBounds) || pageAnchor(node))) {
                    if (match != null) { match.recycle(); return null; }
                    match = AccessibilityNodeInfo.obtain(node);
                }
                for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
            }
            if (queue.size() > 1500) { if (match != null) match.recycle(); return null; }
            return match;
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private boolean pageAnchor(AccessibilityNodeInfo parent) {
        List<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(AccessibilityNodeInfo.obtain(parent));
        try {
            for (int index = 0; index < queue.size() && index < 500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                for (String label : new String[] { "安全", "尚未创建", "已锁定", "已解锁", "创建秘密库", "更换主口令", "解锁秘密库",
                        "确认覆盖此同名文件", "确认覆盖全部现有库内容", "加密备份已保存。", "导出已取消，本次暂存已清理。" })
                    if (semanticExact(node, label)) return true;
                for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
            }
            return false;
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private boolean knownToolbarPage(AccessibilityNodeInfo scroll, Rect windowBounds) {
        if (!TARGET.equals(scope)) return false;
        Rect scrollBounds = new Rect(); scroll.getBoundsInScreen(scrollBounds);
        AccessibilityNodeInfo title = find(n -> {
            if (!TARGET.contentEquals(String.valueOf(n.getPackageName())) || n.getWindowId() != scroll.getWindowId() || n.isEditable()
                    || n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) return false;
            Rect bounds = new Rect(); n.getBoundsInScreen(bounds);
            if (bounds.isEmpty() || !windowBounds.contains(bounds) || bounds.bottom > scrollBounds.top) return false;
            for (String label : new String[] { "安全", "创建秘密库", "解锁秘密库", "更换主口令", "导出加密备份", "导入加密备份", "审阅加密备份" })
                if (semanticExact(n, label)) return true;
            return false;
        });
        if (title == null) return false;
        title.recycle(); return true;
    }
    private int activeScopeWindowId() {
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (active == null) return -1;
        try { return scope.contentEquals(String.valueOf(active.getPackageName())) ? active.getWindowId() : -1; }
        finally { active.recycle(); }
    }
    private void scrollMetadata(AccessibilityNodeInfo node, String event) {
        if (manualSecureFlow) return;
        if (!TARGET.equals(scope) || !TARGET.contentEquals(String.valueOf(node.getPackageName()))) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        AccessibilityNodeInfo active = automation.getRootInActiveWindow(); boolean toolbarPageProven = false;
        if (active != null) {
            try {
                if (TARGET.contentEquals(String.valueOf(active.getPackageName())) && active.getWindowId() == node.getWindowId()) {
                    Rect windowBounds = new Rect(); active.getBoundsInScreen(windowBounds);
                    toolbarPageProven = knownToolbarPage(node, windowBounds);
                }
            } finally { active.recycle(); }
        }
        List<Integer> actions = new ArrayList<>(); for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) actions.add(action.getId());
        Log.i(TAG, "run=" + runId + " diagnostic=" + event + " scrollWindow=" + node.getWindowId()
                + " activeFixtureWindow=" + activeScopeWindowId() + " nodeHash=" + node.hashCode() + " role=" + node.getClassName()
                + " bounds=" + bounds.toShortString() + " scrollable=" + node.isScrollable() + " editable=" + node.isEditable()
                + " toolbarPageProven=" + toolbarPageProven
                + " actions=" + actions + " collectionRows=" + (collection == null ? -1 : collection.getRowCount())
                + " collectionColumns=" + (collection == null ? -1 : collection.getColumnCount())
                + " collectionHierarchical=" + (collection != null && collection.isHierarchical())
                + " collectionSelectionMode=" + (collection == null ? -1 : collection.getSelectionMode()));
    }
    private void clearEditors() {
        for (AccessibilityNodeInfo node : boundEditors) node.recycle();
        boundEditors.clear(); boundLabels = new String[0]; boundValues = new String[0];
    }
    private static boolean fieldLabel(AccessibilityNodeInfo node, String label) {
        for (CharSequence candidate : new CharSequence[] { node.getHintText(), node.getContentDescription() }) {
            if (candidate == null) continue;
            String value = candidate.toString().trim();
            if (value.equals(label) || value.startsWith(label + "\n") || value.startsWith(label + ", ")) return true;
        }
        return false;
    }
    private void waitEditors(int count) { waitEditors(count, null); }
    private void waitEditors(String... labels) { waitEditors(labels.length, labels); }
    private void waitEditors(int count, String[] labels) {
        clearEditors();
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            List<AccessibilityNodeInfo> values = editors(); List<AccessibilityNodeInfo> ordered = new ArrayList<>();
            try {
                if (values.size() == count) {
                    if (labels == null) ordered.addAll(values);
                    else for (String label : labels) {
                        AccessibilityNodeInfo match = null;
                        for (AccessibilityNodeInfo node : values) if (fieldLabel(node, label)) { if (match != null) { match = null; break; } match = node; }
                        if (match == null || ordered.contains(match)) break;
                        ordered.add(match);
                    }
                    if (ordered.size() == count) {
                        for (AccessibilityNodeInfo node : ordered) boundEditors.add(AccessibilityNodeInfo.obtain(node));
                        boundLabels = labels == null ? new String[count] : labels.clone(); boundValues = new String[count];
                        for (int i = 0; i < count; i++) nodeMetadata(boundEditors.get(i), "editor_bound_" + i, boundLabels[i], null);
                        return;
                    }
                }
            } finally { for (AccessibilityNodeInfo node : values) node.recycle(); }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Exactly labeled, distinct editable fields could not be bound safely");
    }
    private List<AccessibilityNodeInfo> editors() {
        List<AccessibilityNodeInfo> queue = roots(), result = new ArrayList<>();
        try {
            for (int index = 0; index < queue.size() && index < 1500; index++) { AccessibilityNodeInfo n = queue.get(index); if(n.isVisibleToUser() && n.isEditable() && !result.contains(n)) result.add(AccessibilityNodeInfo.obtain(n)); for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)queue.add(c);} }
            return result;
        } finally { for(AccessibilityNodeInfo n:queue)n.recycle(); }
    }
    private void setEditor(int index, String value) {
        assertTrue("Stable bound editable field unavailable", index < boundEditors.size());
        AccessibilityNodeInfo node = boundEditors.get(index);
        assertTrue("Bound editable node expired before input", node.refresh());
        assertTrue("Bound editable node no longer belongs to the selected scope", scope.contentEquals(String.valueOf(node.getPackageName())));
        assertTrue("Bound field lost its exact label", boundLabels[index] == null || fieldLabel(node, boundLabels[index]));
        // A completed submission clears/unfocuses controllers in Dart. Android's cached
        // isFocused can lag behind that frame, so always acquire focus through the exact
        // field's own advertised CLICK, after busy/disabled state has actually ended.
        long clickableDeadline = SystemClock.elapsedRealtime() + 5000; boolean clicked = false;
        do {
            if (node.refresh() && scope.contentEquals(String.valueOf(node.getPackageName()))
                    && node.getWindowId() == activeScopeWindowId()
                    && node.isEnabled() && node.isEditable()
                    && (boundLabels[index] == null || fieldLabel(node, boundLabels[index]))
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { clicked = true; break; }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < clickableDeadline);
        assertTrue("Enabled, editable exact field did not accept its advertised focus click", clicked);
        long focusDeadline = SystemClock.elapsedRealtime() + 5000;
        boolean focusedAction = false;
        do {
            if (node.refresh() && node.isFocused() && node.isEditable()
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) { focusedAction = true; break; }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < focusDeadline);
        nodeMetadata(node, "editor_focus_and_advertised_set_text_" + index, boundLabels[index], null);
        assertTrue("Input focus and advertised SET_TEXT are required; predicted bridge values are not controller evidence", focusedAction);
        // Focus change causes a real Flutter semantics rebuild. Re-check earlier fields after it,
        // rather than trusting Android AccessibilityBridge's optimistic SET_TEXT cache.
        for (int previous = 0; previous < boundEditors.size(); previous++) {
            if (previous == index || boundValues[previous] == null) continue;
            AccessibilityNodeInfo earlier = boundEditors.get(previous); boolean retained = false;
            long retainedDeadline = SystemClock.elapsedRealtime() + 3000;
            do {
                if (earlier.refresh() && earlier.getText() != null && earlier.getText().length() == boundValues[previous].length()) { retained = true; break; }
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < retainedDeadline);
            nodeMetadata(earlier, "editor_retained_after_focus_change_" + previous, boundLabels[previous], boundValues[previous]);
            assertTrue("Previously entered field was not retained after the real focus-change rebuild", retained);
        }
        Bundle arguments = new Bundle(); arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        // ensureVisible may rebuild semantics after earlier-field checks. Wait for the
        // same exact field's input action to return, then dispatch once without another
        // intervening diagnostic or field refresh. No unadvertised SET_TEXT fallback.
        long dispatchDeadline = SystemClock.elapsedRealtime() + 5000;
        boolean dispatched = false, reacquiredFocus = false;
        do {
            if (node.refresh() && scope.contentEquals(String.valueOf(node.getPackageName()))
                    && node.getWindowId() == activeScopeWindowId() && node.isEnabled() && node.isEditable()
                    && (boundLabels[index] == null || fieldLabel(node, boundLabels[index]))) {
                if (node.isFocused() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                    assertTrue("Set-text action rejected", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                    dispatched = true; break;
                }
                if (!node.isFocused() && !reacquiredFocus
                        && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                    assertTrue("Exact field focus reacquisition rejected", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                    reacquiredFocus = true;
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < dispatchDeadline);
        assertTrue("Never invoke unadvertised SET_TEXT: exact active field did not become ready before the input deadline", dispatched);
        boundValues[index] = value;
        long deadline = SystemClock.elapsedRealtime() + 5000;
        int stableSamples = 0;
        do {
            if (node.refresh() && node.getText() != null && node.getText().length() == value.length()) {
                if (++stableSamples >= 3) { nodeMetadata(node, "editor_value_updated_" + index, boundLabels[index], value); return; }
            } else stableSamples = 0;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        nodeMetadata(node, "editor_update_timeout_" + index, boundLabels[index], value);
        fail("Bound field's text length did not reflect the requested input");
    }
    private void nodeMetadata(AccessibilityNodeInfo node, String event, String expectedLabel, String expectedValue) {
        if (manualSecureFlow) return;
        if (!TARGET.equals(scope) || !TARGET.contentEquals(String.valueOf(node.getPackageName()))) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); List<Integer> actions = new ArrayList<>();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) actions.add(action.getId());
        Log.i(TAG, "run=" + runId + " diagnostic=" + event + " window=" + node.getWindowId() + " nodeHash=" + node.hashCode()
                + " bounds=" + bounds.toShortString() + " role=" + node.getClassName() + " actions=" + actions
                + " editable=" + node.isEditable() + " focused=" + node.isFocused() + " password=" + node.isPassword()
                + " textLength=" + (node.getText() == null ? -1 : node.getText().length())
                + " expectedValueEqual=" + (expectedValue != null && expectedValue.contentEquals(node.getText() == null ? "" : node.getText()))
                + " expectedLabelMatch=" + (expectedLabel != null && fieldLabel(node, expectedLabel))
                + " emptyValidation=" + text(node, "请输入") + " mismatchValidation=" + text(node, "两次口令不一致")
                + " lockButtonLabel=" + semanticExact(node, "立即锁定") + " unlockButtonLabel=" + semanticExact(node, "解锁秘密库"));
    }
    private void fixtureNodeDiagnostics(String event) {
        if (manualSecureFlow) return;
        if (!TARGET.equals(scope)) { stage("fixture_node_diagnostics_skipped_nonfixture_scope"); return; }
        List<AccessibilityNodeInfo> queue = roots(); int recorded = 0, scrollRecorded = 0;
        boolean firstEmpty = false, confirmationEmpty = false, mismatch = false;
        boolean lockButton = false, lockClickable = false, unlockButton = false, unlockClickable = false;
        boolean lockedStatus = false, unlockedStatus = false, lockMessage = false, unlockMessage = false;
        try {
            for (int index = 0; index < queue.size() && index < 1500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (scrollRecorded < 8 && node.isVisibleToUser() && node.isScrollable()) { scrollMetadata(node, event + "_scroll_role"); scrollRecorded++; }
                if (node.isVisibleToUser()) {
                    firstEmpty |= semanticExact(node, "请输入主口令"); confirmationEmpty |= semanticExact(node, "请输入确认主口令");
                    mismatch |= semanticExact(node, "两次口令不一致");
                    boolean button = "android.widget.Button".contentEquals(String.valueOf(node.getClassName()));
                    boolean actionable = node.isEnabled() && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    lockButton |= button && semanticExact(node, "立即锁定"); lockClickable |= button && semanticExact(node, "立即锁定") && actionable;
                    unlockButton |= button && semanticExact(node, "解锁秘密库"); unlockClickable |= button && semanticExact(node, "解锁秘密库") && actionable;
                    lockedStatus |= semanticExact(node, "已锁定"); unlockedStatus |= semanticExact(node, "已解锁");
                    lockMessage |= semanticExact(node, "秘密库已锁定。"); unlockMessage |= semanticExact(node, "秘密库已解锁。");
                }
                if (recorded < 16 && node.isVisibleToUser() && (node.isEditable() || "android.widget.Button".contentEquals(String.valueOf(node.getClassName())))) {
                    int bound = boundEditors.indexOf(node);
                    nodeMetadata(node, event, bound < 0 ? null : boundLabels[bound], bound < 0 ? null : boundValues[bound]); recorded++;
                }
                for (int child = 0; child < node.getChildCount(); child++) { AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next); }
            }
            Log.i(TAG, "run=" + runId + " diagnostic=" + event + " validationFirstEmpty=" + firstEmpty
                    + " validationConfirmationEmpty=" + confirmationEmpty + " validationMismatch=" + mismatch
                    + " lockButtonVisible=" + lockButton + " lockButtonActionable=" + lockClickable
                    + " unlockButtonVisible=" + unlockButton + " unlockButtonActionable=" + unlockClickable
                    + " lockedStatusVisible=" + lockedStatus + " unlockedStatusVisible=" + unlockedStatus
                    + " lockMessageVisible=" + lockMessage + " unlockMessageVisible=" + unlockMessage
                    + " fixtureActivityPresent=" + (activity != null) + " fixtureActivityFinishing=" + (activity != null && activity.isFinishing()));
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
}
