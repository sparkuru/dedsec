package im.majo.ctos;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
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
import java.util.List;
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
 * The separate ctosSshKeyRequestFile opt-in uses the same public fields plus base64 privateKey
 * (no password), at /data/local/tmp/ctos-ssh-key-NAME.json, for a publickey-only server.
 * ctosSshEncryptedKeyRequestFile uses that exact path/ownership boundary and adds passphrase;
 * it exercises the actual encrypted-key import, one-use/save/reuse/forget product bridges.
 */
public final class SshPasswordConnectPlatformTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private Method dispatch;
    private String activeRequest, connectedRequest, protection;
    private String pendingImportTicket;
    private String pendingDeleteTicket;
    private String pendingTrustTicket;
    private Object oldSshSink, oldTerminalSink;
    private SshPasswordConnectBridge ssh;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean observedSshTerminal = new AtomicBoolean();
    private final AtomicInteger reviews = new AtomicInteger();
    private final StringBuilder output = new StringBuilder();
    private android.app.PendingIntent backgroundStaleStop;
    private volatile int diagnosticGeneration;
    private final java.util.concurrent.atomic.AtomicReference<String> firstDiagnosticTerminal =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Explicitly opted-in platform IO observations; no key, vault, Activity or network. */
    @Test public void actualMemfdPermissionDiagnostics() throws Exception {
        Assume.assumeTrue("Explicit memfd diagnostics opt-in was not provided", "true".equals(
                InstrumentationRegistry.getArguments().getString("ctosSshMemfdDiagnostics")));
        Assume.assumeTrue("Public Os.memfd_create requires API30+", android.os.Build.VERSION.SDK_INT >= 30);
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        memfdPermissionDiagnostic(1, true);
        memfdPermissionDiagnostic(9, false);
    }

    private void memfdPermissionDiagnostic(int flags, boolean required) throws Exception {
        java.io.FileDescriptor descriptor = null;
        try {
            int createErrno = 0;
            try { descriptor = android.system.Os.memfd_create("ctos-fixture-io", flags); }
            catch (android.system.ErrnoException failure) { createErrno = failure.errno; }
            if (createErrno == 0 && (descriptor == null || !descriptor.valid())) createErrno = -1;
            memfdDiagnostic(flags, "CREATE", createErrno, -1);
            if (createErrno != 0) {
                if (required) throw new AssertionError("Synthetic memfd creation failed errno=" + createErrno);
                return;
            }
            int statErrno = 0, mode = -1;
            try { mode = android.system.Os.fstat(descriptor).st_mode; }
            catch (android.system.ErrnoException failure) { statErrno = failure.errno; }
            memfdDiagnostic(flags, "FSTAT", statErrno, mode);
            int chmodErrno = 0;
            try { android.system.Os.fchmod(descriptor, 0600); }
            catch (android.system.ErrnoException failure) { chmodErrno = failure.errno; }
            memfdDiagnostic(flags, "FCHMOD_0600", chmodErrno, -1);
            statErrno = 0; mode = -1;
            try { mode = android.system.Os.fstat(descriptor).st_mode; }
            catch (android.system.ErrnoException failure) { statErrno = failure.errno; }
            memfdDiagnostic(flags, "FSTAT_AFTER", statErrno, mode);
            int truncateErrno = 0;
            try { android.system.Os.ftruncate(descriptor, 128); }
            catch (android.system.ErrnoException failure) { truncateErrno = failure.errno; }
            memfdDiagnostic(flags, "FTRUNCATE_128", truncateErrno, -1);
        } finally {
            if (descriptor != null && descriptor.valid()) {
                int closeErrno = 0;
                try { android.system.Os.close(descriptor); }
                catch (android.system.ErrnoException failure) { closeErrno = failure.errno; }
                memfdDiagnostic(flags, "CLOSE", closeErrno, -1);
                assertEquals("Synthetic memfd exact descriptor cleanup failed", 0, closeErrno);
            }
        }
    }

    private void memfdDiagnostic(int flags, String stage, int errno, int mode) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("stream", "memfd flags=" + flags + " stage=" + stage
                + " errno=" + errno + " mode=" + mode + "\n");
        instrumentation.sendStatus(0, status);
    }

    /** Production native validation of the first manual CONFIG, without a store or connection. */
    @Test public void actualKeyOnlyManualCandidateValidation() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("ctosSshKeyRequestFile");
        Assume.assumeTrue("Explicit synthetic RSA request was not provided", path != null);
        assertTrue("Invalid key fixture request path", path.matches("/data/local/tmp/ctos-ssh-key-[A-Za-z0-9_-]{1,64}\\.json"));
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        JSONObject request = null;
        byte[] privateKey = null;
        String stage = "REQUEST";
        SshPasswordManifestValidator validator = new SshPasswordManifestValidator();
        try {
            request = readKeyRequest(path);
            stage = "DECODE_SYNTHETIC_KEY";
            privateKey = java.util.Base64.getDecoder().decode(request.getString("privateKey"));
            request.remove("privateKey");
            assertTrue("Synthetic key must fit the native input bound", privateKey.length > 0
                    && privateKey.length <= SshKeyMaterial.MAX_BYTES);
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            stage = "INSPECT_RSA";
            SshKeyMaterial.Result material = SshKeyMaterial.inspect(privateKey, deadline);
            assertEquals("Synthetic private key must parse", SshKeyMaterial.Status.PARSED, material.status);
            assertEquals("This regression requires actual synthetic RSA material", "ssh-rsa", material.algorithm);
            try (VaultManifest empty = VaultManifest.empty()) {
                stage = "IMPORT_KEY_ONLY";
                try (SshImportProposal imported = SshImportProposal.create(empty, "privateKey", ".ssh/id_rsa",
                        privateKey, deadline); VaultManifest keyOnly = imported.proposal.applyTo(empty)) {
                    assertEquals(3, keyOnly.files.size()); assertEquals(1, keyOnly.keyBindings.size());
                    assertTrue(keyOnly.accounts.isEmpty());
                    for (VaultManifest.FileObject file : keyOnly.files)
                        assertNotEquals("The source must have no CONFIG", VaultManifest.FileType.CONFIG, file.type);
                    stage = "VALIDATE_KEY_ONLY";
                    validator.validate(keyOnly);
                    SshConnectionMaterial.Key key = SshConnectionMaterial.key(keyOnly, keyOnly.keyBindings.get(0).id);
                    SshPasswordProfile profile = new SshPasswordProfile("192.168.9.5", 22, "wkyuu");
                    stage = "PROPOSE_FIRST_MANUAL_CONFIG";
                    try (SshBundleProposal proposal = SshConnectionMaterial.connectionProposal(keyOnly, profile, key, true)) {
                        assertNotNull("Manual connection must propose its CONFIG and KEY Account", proposal);
                        stage = "APPLY_FIRST_MANUAL_CONFIG";
                        try (VaultManifest candidate = proposal.applyTo(keyOnly)) {
                            assertEquals(4, candidate.files.size()); assertEquals(1, candidate.accounts.size());
                            assertTrue(new SshConnectionMaterial.KeyAccount(profile, key).matches(candidate.accounts.get(0)));
                            VaultManifest.FileObject config = SshConnectionMaterial.file(candidate, SshConnectionMaterial.MANUAL);
                            assertNotNull(config);
                            assertEquals(java.util.Set.of(".ssh/known_hosts", ".ssh/id_rsa"),
                                    new java.util.HashSet<>(config.references));
                            stage = "VALIDATE_FIRST_MANUAL_CONFIG";
                            try { validator.validate(candidate); }
                            catch (Throwable failure) {
                                throw new AssertionError("Synthetic key-only manual validation failed stage=" + stage
                                        + " failure=" + fixedKeyValidationFailure(failure)
                                        + " diagnostics=" + syntheticKeyValidationDiagnostics(candidate));
                            }
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            if (failure instanceof AssertionError && failure.getMessage() != null
                    && failure.getMessage().startsWith("Synthetic key-only manual validation failed stage="))
                throw (AssertionError)failure;
            throw new AssertionError("Synthetic key-only manual validation failed stage=" + stage
                    + " failure=" + fixedKeyValidationFailure(failure));
        } finally {
            if (request != null) request.remove("privateKey");
            VaultCodec.wipe(privateKey);
            try { validator.retryCleanup(); }
            catch (Throwable failure) {
                throw new AssertionError("Synthetic key-only validation cleanup failed " + fixedKeyValidationFailure(failure));
            }
        }
    }

    private static String fixedKeyValidationFailure(Throwable failure) {
        StringBuilder result = new StringBuilder(failure.getClass().getSimpleName());
        for (int depth = 0; failure != null && depth < 5; depth++, failure = failure.getCause()) {
            if (failure instanceof VaultException) {
                String code = ((VaultException)failure).code;
                result.append(":").append(code.matches("[A-Z_]{1,64}") ? code : "UNKNOWN_CODE");
            }
            if (failure instanceof SshConfigEvaluator.EvaluationException) {
                SshConfigEvaluator.Diagnostic diagnostic = ((SshConfigEvaluator.EvaluationException)failure).diagnostic;
                result.append(":native(").append(diagnostic.status).append(',').append(diagnostic.reason)
                        .append(',').append(diagnostic.object).append(',').append(diagnostic.line)
                        .append(',').append(diagnostic.errorNumber).append(')');
            }
        }
        return result.toString();
    }

    /** Synthetic-only public ordinals/statuses; no key bytes, config body or raw exception text. */
    private static String syntheticKeyValidationDiagnostics(VaultManifest manifest) {
        StringBuilder result = new StringBuilder();
        try { SshPasswordProfile.checkShape(manifest); result.append("shape=OK;"); }
        catch (Throwable failure) { result.append("shape=").append(fixedKeyValidationFailure(failure)).append(';'); }
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        try {
            SshManifestInspector.Report report = new SshManifestInspector().inspect(manifest, deadline,
                    new SshManifestInspector.Cancellation());
            int ordinal = 0;
            for (SshManifestInspector.Entry entry : report.entries)
                result.append("material(").append(++ordinal).append(',').append(entry.type.name()).append(',')
                        .append(entry.status.name()).append(',').append(entry.keyKind.name()).append(',')
                        .append(entry.records).append(',').append(entry.line).append(");");
        } catch (Throwable failure) { result.append("material=").append(fixedKeyValidationFailure(failure)).append(';'); }
        try (SshManifestConfigInspector inspector = new SshManifestConfigInspector()) {
            SshManifestConfigInspector.Report report = inspector.inspectResourceAudit(manifest, deadline,
                    new SshManifestConfigInspector.Cancellation());
            for (SshManifestConfigInspector.Root root : report.roots) {
                VaultManifest.FileObject file = manifest.files.get(root.object - 1);
                java.util.List<String> expected = SshConnectionMaterial.references(manifest, root.object, root.resourceAudit);
                result.append("config(").append(root.object).append(",refs=").append(file.references.size())
                        .append(",exact=").append(new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(file.references)))
                        .append(");");
                for (SshConfigEvaluator.Resource resource : root.resourceAudit.resources)
                    result.append("resource(").append(resource.kind).append(',').append(resource.parent).append(',')
                            .append(resource.line).append(',').append(resource.state).append(',').append(resource.target).append(");");
            }
        } catch (Throwable failure) { result.append("config=").append(fixedKeyValidationFailure(failure)).append(';'); }
        return result.toString();
    }

    /**
     * Separately opted-in publickey-only TCP regression. The ActivityMonitor replaces only the
     * document-picker result; actual MainActivity.onActivityResult, ContentResolver stream,
     * import ticket/commit, encrypted store, native proof/USERAUTH and PTY all execute.
     * This does not exercise DocumentsUI, external SAF grants or human Flutter interaction.
     */
    @Test public void actualSafKeyImportManualThenConfigAndStaleBinding() throws Exception {
        actualSafKeyFixture(false);
    }

    @Test public void actualSafEncryptedKeyImportTransientSavedAndForget() throws Exception {
        actualSafKeyFixture(true);
    }

    /** Same-public clear/encrypted siblings are independent managed objects, not fallbacks. */
    @Test public void actualSafEncryptedKeyDeletionAndReimport() throws Exception {
        actualSafKeyFixture(true, true);
    }

    @Test public void actualSafConfigFirstIndependentKeyAndStaleBinding() throws Exception {
        Assume.assumeTrue("Explicit config-first fixture method is required",
                "im.majo.ctos.SshPasswordConnectPlatformTest#actualSafConfigFirstIndependentKeyAndStaleBinding".equals(
                        InstrumentationRegistry.getArguments().getString("class")));
        actualSafKeyFixture(true, false, true);
    }

    private void actualSafKeyFixture(boolean encrypted) throws Exception {
        actualSafKeyFixture(encrypted, false);
    }

    private void actualSafKeyFixture(boolean encrypted, boolean deletion) throws Exception {
        actualSafKeyFixture(encrypted, deletion, false);
    }

    private void actualSafKeyFixture(boolean encrypted, boolean deletion, boolean configFirst) throws Exception {
        String path = InstrumentationRegistry.getArguments().getString(
                encrypted ? "ctosSshEncryptedKeyRequestFile" : "ctosSshKeyRequestFile");
        Assume.assumeTrue("Explicit independent key-only TCP fixture request was not provided", path != null);
        assertTrue("Invalid key fixture request path", path.matches("/data/local/tmp/ctos-ssh-key-[A-Za-z0-9_-]{1,64}\\.json"));
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse(context.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        if (encrypted) {
            for (String option : new String[] {"ctosSshBackground", "ctosSshPhysicalLock", "ctosSshNotificationsDenied",
                    "ctosSshNotificationChannelDenied", "ctosSshManualDeviceUnlock", "ctosSshScreenOffDiagnostics"})
                assertFalse("Encrypted-key fixture has a separate bounded foreground scope", "true".equals(
                        InstrumentationRegistry.getArguments().getString(option)));
        } else {
            validateNotificationDeniedOptIn(context);
            validatePhysicalLockOptIns();
        }
        try {
            assertNotEquals("Fixture must use an isolated UID", context.getApplicationInfo().uid,
                    context.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException absentFormalApp) { /* Isolated fixture only. */ }
        File vaultFile = new File(context.getNoBackupFilesDir(), "app-secrets.bin");
        resetOwnedKeyFixtureVault(vaultFile);
        assertFalse("Fresh isolated fixture app data is required", vaultFile.exists());
        assertFalse("A previous backup requires explicit reconciliation", new File(vaultFile.getPath() + ".bak").exists());
        assertFalse("A previous pending write requires explicit reconciliation", new File(vaultFile.getPath() + ".new").exists());
        JSONObject request = readKeyRequest(path, encrypted);
        byte[] privateKey = null, passphrase = null;
        boolean created = false, observed = false;
        Throwable primaryFailure = null;
        try {
            String host = request.getString("host"), username = request.getString("username");
            int port = request.getInt("port");
            String fingerprint = request.getString("fingerprint");
            assertTrue("Explicit expected host fingerprint is required", fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
            new SshPasswordProfile(host, port, username);
            try { privateKey = java.util.Base64.getDecoder().decode(request.getString("privateKey")); }
            catch (IllegalArgumentException invalid) { throw new AssertionError("Invalid synthetic private-key encoding"); }
            finally { request.remove("privateKey"); }
            assertTrue("Synthetic private key exceeds the bounded input", privateKey.length > 0 && privateKey.length <= SshKeyMaterial.MAX_BYTES);
            if (encrypted) {
                passphrase = SshKeyPassphrase.bytes(request.getString("passphrase"), false);
                request.remove("passphrase");
            }
            SshKeyMaterial.Result material = SshKeyMaterial.inspect(privateKey, passphrase, SystemClock.elapsedRealtime() + 60_000);
            assertEquals(SshKeyMaterial.Status.PARSED, material.status);
            assertEquals("The supplied synthetic key must match the selected encrypted mode", encrypted, material.encrypted);
            assertTrue("Actual native public fingerprint is required", material.fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
            activity = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!encrypted) preparePhysicalLockEntry();
            dispatch = MainActivity.class.getDeclaredMethod("call", MethodCall.class, MethodChannel.Result.class);
            dispatch.setAccessible(true);
            ssh = (SshPasswordConnectBridge)field(activity, "sshPasswordConnect");
            AppSecretsBridge secrets = (AppSecretsBridge)field(activity, "appSecrets");
            assertSame(AppSecretsOwner.acquire(activity), secrets.sshVault());
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            String master = "Fixture-" + UUID.randomUUID();
            created = true; // Fresh absence above proves any partial create is this fixture's file.
            call("secrets.create", VaultCollections.map("requestId", id(), "password", master, "confirmation", master));
            assertTrue(vaultFile.exists());
            installObservers(host, port, fingerprint); observed = true;
            if (configFirst) {
                assertConfigFirstIndependentKey(request, privateKey, passphrase, material, secrets.sshVault(), vaultFile);
                return;
            }
            Map<String, Object> keyPreview = pickImport("privateKey", ".ssh/id_fixture", "id_fixture", privateKey);
            if (encrypted) keyPreview = unlockEncryptedImport(keyPreview, passphrase, vaultFile, true);
            assertPreview(keyPreview, "privateKey", ".ssh/id_fixture", "id_fixture", false);
            assertEquals(material.algorithm, keyPreview.get("algorithm"));
            assertEquals(material.fingerprint, keyPreview.get("fingerprint"));
            commitImport(keyPreview);
            String quotedUser = username.replace("\\", "\\\\").replace("\"", "\\\"");
            String config = "Host fixturekey\n    HostName " + host + "\n    User \"" + quotedUser
                    + "\"\n    Port " + port + "\n    IdentityFile ~/.ssh/id_fixture\n"
                    + "    IdentitiesOnly yes\n    UserKnownHostsFile ~/.ssh/known_hosts\n";
            byte[] configBytes = config.getBytes(StandardCharsets.UTF_8);
            Map<String, Object> configPreview;
            try { configPreview = pickImport("config", ".ssh/config", "config", configBytes); }
            finally { VaultCodec.wipe(configBytes); }
            assertPreview(configPreview, "config", ".ssh/config", "config", false);
            assertEquals("", configPreview.get("algorithm")); assertEquals("", configPreview.get("fingerprint"));
            commitImport(configPreview);
            Map<String, Object> library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            List<?> keys = (List<?>)library.get("keys"); assertEquals(1, keys.size());
            Map<String, Object> key = map(keys.get(0));
            assertEquals("id_fixture", key.get("label")); assertEquals(".ssh/id_fixture", key.get("path"));
            assertEquals(material.algorithm, key.get("algorithm")); assertEquals(material.fingerprint, key.get("fingerprint"));
            assertEquals(encrypted, key.get("encrypted")); assertEquals(false, key.get("hasSavedPassphrase"));
            String keyId = (String)key.get("id");
            checkedHost(library, keyId, host, port, username);
            assertPersistedKey(secrets.sshVault(), privateKey, material, keyId, host, port, username, false);

            if (deletion) {
                assertEncryptedKeyDeletion(request, privateKey, passphrase, material, keyId,
                        secrets.sshVault(), vaultFile, config);
                return;
            }

            connectKey(request, keyId, null, passphrase, false, false);
            assertEquals("First actual offered host must require exact approval", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
            if ("true".equals(InstrumentationRegistry.getArguments().getString("ctosSshBackground")))
                assertBackgroundRecreationAndNotificationStop(master, request, keyId, host, port, fingerprint);
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            // Manual Account/config and trust writes change the selection binding: reload it.
            library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            Map<String, Object> selected = checkedHost(library, keyId, host, port, username);
            assertPersistedKey(secrets.sshVault(), privateKey, material, keyId, host, port, username, true);
            connectKey(request, keyId, selected, passphrase, false, encrypted);
            assertEquals("The matching persisted host must use native KNOWN without new approval", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
            if (backgroundStaleStop != null) {
                int current = SshSessionService.generation();
                backgroundStaleStop.send();
                SystemClock.sleep(200);
                assertEquals("An old notification must not stop a later connection", current, SshSessionService.generation());
                assertShellRoundTrip();
            }
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            if (encrypted) assertEncryptedKeyPersistence(request, privateKey, passphrase, material, keyId, secrets.sshVault(), vaultFile);
            selected = checkedHost(map(call("ssh.library", VaultCollections.map("requestId", id()))), keyId, host, port, username);
            Map<String, Object> original = map(call("ssh.configRead", VaultCollections.map("requestId", id(), "path", ".ssh/config")));
            assertEquals(config, original.get("content"));
            Map<String, Object> edited = map(call("ssh.configPreview", VaultCollections.map("requestId", id(),
                    "path", ".ssh/config", "content", config + "# fixture selection changed\n")));
            pendingImportTicket = (String)edited.get("ticketId");
            assertPreview(edited, "config", ".ssh/config", "config", true); commitImport(edited);
            Map<String, Object> fresh = checkedHost(map(call("ssh.library", VaultCollections.map("requestId", id()))), keyId, host, port, username);
            assertNotEquals("An edited source must invalidate the old row", selected.get("binding"), fresh.get("binding"));
            running.set(false);
            try { connectKey(request, keyId, selected); fail("An old config row must fail before connection"); }
            catch (java.util.concurrent.ExecutionException rejected) {
                assertEquals("Fixed host failure: VAULT_CHANGED", rejected.getCause().getMessage()); activeRequest = null;
            }
            assertFalse("Stale config must not establish a terminal", running.get());
            assertEquals(1, reviews.get()); waitReleased();
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            if (screenOffDiagnosticsRequested()) try { persistDiagnosticTerminal(); }
            catch (Exception | Error diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally {
            request.remove("passphrase"); VaultCodec.wipe(passphrase);
            try { cleanupKeyFixture(request, privateKey, vaultFile, created, observed); }
            catch (Exception | Error cleanupFailure) {
                if (primaryFailure != null) {
                    if (primaryFailure != cleanupFailure) primaryFailure.addSuppressed(cleanupFailure);
                }
                else throw cleanupFailure;
            }
        }
    }

    private void resetOwnedKeyFixtureVault(File vaultFile) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("ctosSshResetOwnedFixtureVault"))) return;
        assertNull("Owned fixture reset must precede Activity attachment", activity);
        assertNull("Owned fixture reset must precede bridge initialization", dispatch);
        assertFalse("Owned fixture reset requires no active SSH service", SshSessionService.active());
        instrumentation.runOnMainSync(() -> {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitor monitor =
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();
            for (androidx.test.runner.lifecycle.Stage stage : androidx.test.runner.lifecycle.Stage.values())
                if (stage != androidx.test.runner.lifecycle.Stage.DESTROYED)
                    assertTrue("Owned fixture reset requires no attached Activity", monitor.getActivitiesInStage(stage).isEmpty());
        });
        int count = 0;
        long bytes = 0;
        String[] suffixes = {"", ".bak", ".new"};
        for (String suffix : suffixes) {
            java.nio.file.Path owned = new File(vaultFile.getPath() + suffix).toPath();
            if (!java.nio.file.Files.exists(owned, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            assertTrue("Owned fixture reset refuses nonregular files and symbolic links",
                    java.nio.file.Files.isRegularFile(owned, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            bytes += java.nio.file.Files.readAttributes(owned, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).size();
            count++;
        }
        for (String suffix : suffixes) {
            File owned = new File(vaultFile.getPath() + suffix);
            if (java.nio.file.Files.exists(owned.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                assertTrue("Owned fixture vault reset deletion failed", owned.delete());
        }
        android.os.Bundle status = new android.os.Bundle();
        status.putString("ctos_stage", "owned_fixture_vault_reset");
        status.putInt("ctos_owned_reset_file_count", count);
        status.putLong("ctos_owned_reset_bytes", bytes);
        status.putString("stream", "ctos_stage=owned_fixture_vault_reset file_count=" + count + " bytes=" + bytes + "\n");
        instrumentation.sendStatus(0, status);
    }

    private void cleanupKeyFixture(JSONObject request, byte[] privateKey, File vaultFile,
            boolean created, boolean observed) throws Exception {
        cleanupKeyFixture(request, privateKey, vaultFile, created, observed, true);
    }

    private void cleanupKeyFixture(JSONObject request, byte[] privateKey, File vaultFile,
            boolean created, boolean observed, boolean stopOwnedTerminal) throws Exception {
        Throwable[] failure = new Throwable[1];
        try {
            keyFixtureCleanup(failure, () -> request.remove("privateKey"));
            keyFixtureCleanup(failure, () -> VaultCodec.wipe(privateKey));
            keyFixtureCleanup(failure, SshImportFixtureProvider::clear);
            if (activity != null && dispatch != null) {
                if (activeRequest != null) keyFixtureCleanup(failure,
                        () -> call("ssh.cancel", VaultCollections.map("requestId", activeRequest)));
                if (pendingImportTicket != null) keyFixtureCleanup(failure,
                        () -> call("ssh.importCancel", VaultCollections.map("ticketId", pendingImportTicket)));
                if (pendingDeleteTicket != null) keyFixtureCleanup(failure,
                        () -> call("ssh.keyDeleteCancel", VaultCollections.map("ticketId", pendingDeleteTicket)));
                if (stopOwnedTerminal) keyFixtureCleanup(failure, this::stopTerminal);
                keyFixtureCleanup(failure, this::waitReleased);
                keyFixtureCleanup(failure, this::releaseProtection);
                keyFixtureCleanup(failure, () -> call("secrets.lock", java.util.Collections.emptyMap()));
            }
        } finally {
            try {
                if (activity != null) keyFixtureCleanup(failure, () -> instrumentation.runOnMainSync(() -> {
                    try {
                        if (observed) {
                            try { setField(ssh, "sink", oldSshSink); }
                            finally { setField(activity, "terminalSink", oldTerminalSink); }
                        }
                    } catch (Exception unavailable) {
                        throw new AssertionError("Fixture observation cleanup failed");
                    } finally { activity.finish(); }
                }));
            } finally {
                try { keyFixtureCleanup(failure, () -> { synchronized (output) { output.setLength(0); } }); }
                finally {
                    if (created) for (String suffix : new String[] {"", ".bak", ".new"}) {
                        File owned = new File(vaultFile.getPath() + suffix);
                        keyFixtureCleanup(failure, () -> assertTrue("Own fresh fixture vault cleanup failed",
                                !owned.exists() || owned.delete()));
                    }
                }
            }
        }
        if (failure[0] instanceof Error) throw (Error)failure[0];
        if (failure[0] != null) throw (Exception)failure[0];
    }

    private interface KeyFixtureCleanup { void run() throws Exception; }

    private static void keyFixtureCleanup(Throwable[] primary, KeyFixtureCleanup cleanup) {
        try { cleanup.run(); }
        catch (Exception | Error failure) {
            if (primary[0] == null) primary[0] = failure;
            else if (primary[0] != failure) primary[0].addSuppressed(failure);
        }
    }

    private Map<String, Object> pickImport(String kind, String path, String name, byte[] body) throws Exception {
        Uri uri = SshImportFixtureProvider.seed(name, body);
        IntentFilter filter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("*/*");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(filter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)), true);
        try {
            Map<String, Object> preview = map(call("ssh.importFile", VaultCollections.map("requestId", id(), "kind", kind, "path", path)));
            pendingImportTicket = (String)preview.get("ticketId");
            assertEquals("The production picker request must pass through the fixture monitor", 1, monitor.getHits());
            return preview;
        } finally { instrumentation.removeMonitor(monitor); SshImportFixtureProvider.clear(); }
    }

    private void assertPreview(Map<String, Object> preview, String kind, String path, String name, boolean replacing) {
        assertEquals(java.util.Set.of("ticketId", "binding", "kind", "path", "label", "algorithm", "fingerprint",
                "replacing", "issues", "canCommit", "needsPassphrase"), preview.keySet());
        assertEquals(kind, preview.get("kind")); assertEquals(path, preview.get("path")); assertEquals(name, preview.get("label"));
        assertEquals(replacing, preview.get("replacing")); assertEquals(true, preview.get("canCommit"));
        assertEquals(false, preview.get("needsPassphrase"));
        assertTrue("Import must have a real bound candidate", preview.get("binding") instanceof String
                && ((String)preview.get("binding")).matches("[0-9a-f]{64}"));
        assertFalse(((String)preview.get("ticketId")).isEmpty());
        List<?> issues = (List<?>)preview.get("issues");
        if (replacing && kind.equals("privateKey")) {
            assertEquals(1, issues.size()); assertEquals("SSH_KEY_REPLACED", map(issues.get(0)).get("code"));
        } else assertTrue(issues.isEmpty());
    }

    private Map<String, Object> unlockEncryptedImport(Map<String, Object> preview, byte[] passphrase,
            File vaultFile, boolean verifyWrongPassphrase) throws Exception {
        return unlockEncryptedImport(preview, passphrase, vaultFile, verifyWrongPassphrase, ".ssh/id_fixture");
    }

    private Map<String, Object> unlockEncryptedImport(Map<String, Object> preview, byte[] passphrase,
            File vaultFile, boolean verifyWrongPassphrase, String path) throws Exception {
        assertEquals(java.util.Set.of("ticketId", "binding", "kind", "path", "label", "algorithm", "fingerprint",
                "replacing", "issues", "canCommit", "needsPassphrase"), preview.keySet());
        assertEquals("privateKey", preview.get("kind")); assertEquals(path, preview.get("path"));
        assertEquals("", preview.get("algorithm")); assertEquals("", preview.get("fingerprint"));
        assertEquals(false, preview.get("canCommit")); assertEquals(true, preview.get("needsPassphrase"));
        assertTrue(((List<?>)preview.get("issues")).isEmpty());
        assertTrue(preview.get("binding") instanceof String && ((String)preview.get("binding")).matches("[0-9a-f]{64}"));
        byte[] before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try {
            if (verifyWrongPassphrase) {
                String wrong = "ctos-fixture-intentionally-wrong-" + UUID.randomUUID();
                byte[] wrongBytes = wrong.getBytes(StandardCharsets.UTF_8);
                try { assertFalse("The negative fixture phrase must differ", java.util.Arrays.equals(wrongBytes, passphrase)); }
                finally { VaultCodec.wipe(wrongBytes); }
                assertFixedHostFailure("SSH_KEY_BAD_PASSPHRASE", "ssh.importUnlock", VaultCollections.map(
                        "requestId", id(), "ticketId", preview.get("ticketId"), "binding", preview.get("binding"), "passphrase", wrong));
                assertCiphertextUnchanged(vaultFile, before);
            }
            Map<String, Object> unlocked = map(call("ssh.importUnlock", VaultCollections.map("requestId", id(),
                    "ticketId", preview.get("ticketId"), "binding", preview.get("binding"),
                    "passphrase", new String(passphrase, StandardCharsets.UTF_8))));
            pendingImportTicket = (String)unlocked.get("ticketId");
            assertNotEquals("Unlock must publish a fresh exact commit ticket", preview.get("ticketId"), unlocked.get("ticketId"));
            assertEquals(false, unlocked.get("needsPassphrase")); assertEquals(true, unlocked.get("canCommit"));
            assertCiphertextUnchanged(vaultFile, before);
            return unlocked;
        } finally { VaultCodec.wipe(before); }
    }

    private static void assertCiphertextUnchanged(File vaultFile, byte[] expected) throws Exception {
        byte[] actual = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try { assertTrue("Rejected or preview-only work must preserve the original vault ciphertext",
                java.util.Arrays.equals(expected, actual)); }
        finally { VaultCodec.wipe(actual); }
    }

    private void assertFixedHostFailure(String code, String method, Map<String, Object> arguments) throws Exception {
        try { call(method, arguments); fail("The synthetic rejected operation must not succeed"); }
        catch (java.util.concurrent.ExecutionException rejected) {
            assertEquals("Fixed host failure: " + code, rejected.getCause().getMessage());
        }
    }

    private void commitImport(Map<String, Object> preview) throws Exception {
        Map<String, Object> reply = map(call("ssh.importCommit", VaultCollections.map("requestId", id(),
                "ticketId", preview.get("ticketId"), "binding", preview.get("binding"), "replace", preview.get("replacing"))));
        assertEquals(java.util.Set.of("committed"), reply.keySet()); assertEquals(true, reply.get("committed"));
        pendingImportTicket = null;
    }

    private void assertConfigFirstIndependentKey(JSONObject request, byte[] privateKey, byte[] phrase,
            SshKeyMaterial.Result material, AppSecrets vault, File vaultFile) throws Exception {
        String user = request.getString("username").replace("\\", "\\\\").replace("\"", "\\\"");
        String[] hints = {"/home/source/.ssh/desktop key one", "/home/source/.ssh/desktop-key-two"};
        StringBuilder config = new StringBuilder();
        for (int i = 0; i < 6; i++) config.append("Host imported-").append(i).append("\n    HostName ")
                .append(request.getString("host")).append("\n    User \"").append(user).append("\"\n    Port ")
                .append(request.getInt("port")).append("\n    IdentityFile \"").append(hints[i % 2])
                .append("\"\n    IdentitiesOnly yes\n    UserKnownHostsFile ~/.ssh/known_hosts\n");
        String content = config.toString(); byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            Map<String, Object> preview = pickImport("config", ".ssh/config", "config.bin", bytes);
            assertEquals(true, preview.get("canCommit")); commitImport(preview);
        } finally { VaultCodec.wipe(bytes); }
        Map<String, Object> library = map(call("ssh.library", VaultCollections.map("requestId", id())));
        assertTrue(((List<?>)library.get("keys")).isEmpty());
        assertEquals(6, ((List<?>)library.get("hosts")).size());
        for (int i = 0; i < 6; i++) {
            Map<String, Object> row = deletionHost(library, "imported-" + i);
            assertFalse(row.containsKey("error")); assertEquals(List.of(), row.get("keyIds"));
            assertEquals(request.getString("host"), row.get("host"));
            assertEquals(List.of(hints[i % 2]), row.get("identityHints"));
        }
        Map<String, Object> stale = deletionHost(library, "imported-0");
        byte[] before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try {
            assertFixedHostFailure("SSH_KEY_MISSING", "ssh.configConnect", VaultCollections.map("requestId", id(),
                    "alias", stale.get("alias"), "binding", stale.get("binding"), "keyId", "absent-key", "username", "",
                    "columns", 80, "rows", 24, "passphrase", "synthetic-unused", "useSavedPassphrase", false, "savePassphrase", true));
            assertEquals(0, reviews.get()); assertCiphertextUnchanged(vaultFile, before);
        } finally { VaultCodec.wipe(before); }
        Map<String, Object> keyPreview = pickImport("privateKey", ".ssh/id_fixture", "id_fixture", privateKey);
        keyPreview = unlockEncryptedImport(keyPreview, phrase, vaultFile, true); commitImport(keyPreview);
        library = map(call("ssh.library", VaultCollections.map("requestId", id())));
        String keyId = (String)map(((List<?>)library.get("keys")).get(0)).get("id");
        for (int i = 0; i < 6; i++) assertEquals(List.of(keyId), deletionHost(library, "imported-" + i).get("keyIds"));
        Map<String, Object> selected = deletionHost(library, "imported-0");
        before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try {
            assertDeletionConnectRefused(request, keyId, stale, phrase, true, "VAULT_CHANGED");
            assertEquals(0, reviews.get()); assertCiphertextUnchanged(vaultFile, before);
        } finally { VaultCodec.wipe(before); }
        connectKey(request, keyId, selected, phrase, false, true);
        assertEquals(1, reviews.get()); releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
        protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
        assertPersistedKey(vault, privateKey, material, keyId, request.getString("host"), request.getInt("port"),
                request.getString("username"), true, phrase);
        Map<String, Object> actual = map(call("ssh.configRead", VaultCollections.map("requestId", id(), "path", ".ssh/config")));
        assertEquals(content, actual.get("content"));
        selected = deletionHost(map(call("ssh.library", VaultCollections.map("requestId", id()))), "imported-0");
        Map<String, Object> edit = map(call("ssh.configPreview", VaultCollections.map("requestId", id(),
                "path", ".ssh/config", "content", content + "# synthetic revision\n")));
        pendingImportTicket = (String)edit.get("ticketId"); assertEquals(true, edit.get("canCommit")); commitImport(edit);
        before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try {
            assertDeletionConnectRefused(request, keyId, selected, phrase, true, "VAULT_CHANGED");
            assertEquals(1, reviews.get()); assertCiphertextUnchanged(vaultFile, before);
        } finally { VaultCodec.wipe(before); }
    }

    private Map<String, Object> checkedHost(Map<String, Object> library, String keyId, String host, int port, String username) {
        assertEquals(true, library.get("hasConfig"));
        List<?> hosts = (List<?>)library.get("hosts"); assertEquals(1, hosts.size());
        Map<String, Object> row = map(hosts.get(0));
        assertFalse("The selected actual plan must be supported", row.containsKey("error"));
        assertEquals("fixturekey", row.get("alias")); assertEquals(host, row.get("host"));
        assertEquals(port, ((Number)row.get("port")).intValue()); assertEquals(username, row.get("username"));
        assertEquals(List.of(keyId), row.get("keyIds"));
        assertTrue(((String)row.get("binding")).matches("[0-9a-f]{64}")); return row;
    }

    private void connectKey(JSONObject request, String keyId, Map<String, Object> configured) throws Exception {
        connectKey(request, keyId, configured, null, false, false);
    }

    private void connectKey(JSONObject request, String keyId, Map<String, Object> configured,
            byte[] passphrase, boolean useSavedPassphrase, boolean savePassphrase) throws Exception {
        running.set(false); activeRequest = id();
        Map<String, Object> arguments = new java.util.HashMap<>(configured == null
                ? VaultCollections.map("requestId", activeRequest, "host", request.getString("host"), "port", request.getInt("port"),
                        "username", request.getString("username"), "keyId", keyId, "columns", 80, "rows", 24)
                : VaultCollections.map("requestId", activeRequest, "alias", configured.get("alias"), "binding", configured.get("binding"),
                        "username", "", "keyId", keyId, "columns", 80, "rows", 24));
        if (passphrase != null || useSavedPassphrase || savePassphrase) {
            arguments.put("passphrase", passphrase == null ? "" : new String(passphrase, StandardCharsets.UTF_8));
            arguments.put("useSavedPassphrase", useSavedPassphrase); arguments.put("savePassphrase", savePassphrase);
        }
        try {
            Map<String, Object> reply = map(call(configured == null ? "ssh.keyConnect" : "ssh.configConnect", arguments));
            assertEquals(activeRequest, reply.get("requestId")); assertEquals("connected", reply.get("status"));
            assertTrue("Actual authenticated PTY must be running before completion", running.get());
            connectedRequest = activeRequest; activeRequest = null;
        } finally { arguments.remove("passphrase"); }
    }

    private void assertEncryptedKeyPersistence(JSONObject request, byte[] privateKey, byte[] passphrase,
            SshKeyMaterial.Result material, String keyId, AppSecrets vault, File vaultFile) throws Exception {
        String host = request.getString("host"), username = request.getString("username"); int port = request.getInt("port");
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, true, passphrase);
        Map<String, Object> library = map(call("ssh.library", VaultCollections.map("requestId", id())));
        assertEquals(true, map(((List<?>)library.get("keys")).get(0)).get("hasSavedPassphrase"));
        Map<String, Object> selected = checkedHost(library, keyId, host, port, username);
        connectKey(request, keyId, selected, null, true, false);
        assertEquals("Saved phrase reconnect must still use the actual KNOWN host", 1, reviews.get());
        releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
        protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");

        Map<String, Object> forgotten = map(call("ssh.forgetKeyPassphrase", VaultCollections.map(
                "requestId", id(), "keyId", keyId, "all", false)));
        assertEquals(java.util.Set.of("forgotten"), forgotten.keySet()); assertEquals(true, forgotten.get("forgotten"));
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, true);
        library = map(call("ssh.library", VaultCollections.map("requestId", id())));
        assertEquals(false, map(((List<?>)library.get("keys")).get(0)).get("hasSavedPassphrase"));
        selected = checkedHost(library, keyId, host, port, username);
        byte[] beforeMissing = java.nio.file.Files.readAllBytes(vaultFile.toPath());
        try {
            try { connectKey(request, keyId, selected, null, true, false); fail("Forgotten phrase must not authenticate"); }
            catch (java.util.concurrent.ExecutionException rejected) {
                assertEquals("Fixed host failure: SSH_KEY_PASSPHRASE_MISSING", rejected.getCause().getMessage());
                activeRequest = null;
            }
            assertFalse("Missing saved phrase must not establish a terminal", running.get()); waitReleased();
            assertEquals("Missing phrase must fail before new host approval", 1, reviews.get());
            assertCiphertextUnchanged(vaultFile, beforeMissing);
        } finally { VaultCodec.wipe(beforeMissing); }

        selected = checkedHost(map(call("ssh.library", VaultCollections.map("requestId", id()))), keyId, host, port, username);
        connectKey(request, keyId, selected, passphrase, false, false);
        assertEquals(1, reviews.get()); releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
        protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, true);

        // Save again, then replace the container with the same actual public identity. The old
        // phrase link must disappear even when the public-key-based selection ID stays identical.
        selected = checkedHost(map(call("ssh.library", VaultCollections.map("requestId", id()))), keyId, host, port, username);
        connectKey(request, keyId, selected, passphrase, false, true);
        releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
        protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, true, passphrase);
        Map<String, Object> replacement = pickImport("privateKey", ".ssh/id_fixture", "id_fixture", privateKey);
        replacement = unlockEncryptedImport(replacement, passphrase, vaultFile, false);
        assertPreview(replacement, "privateKey", ".ssh/id_fixture", "id_fixture", true);
        assertEquals(material.algorithm, replacement.get("algorithm")); assertEquals(material.fingerprint, replacement.get("fingerprint"));
        commitImport(replacement);
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, true);
        library = map(call("ssh.library", VaultCollections.map("requestId", id())));
        Map<String, Object> replacementKey = map(((List<?>)library.get("keys")).get(0));
        assertEquals(keyId, replacementKey.get("id")); assertEquals(false, replacementKey.get("hasSavedPassphrase"));
        checkedHost(library, keyId, host, port, username); assertEquals(1, reviews.get());
    }

    private void assertEncryptedKeyDeletion(JSONObject request, byte[] privateKey, byte[] passphrase,
            SshKeyMaterial.Result material, String keyId, AppSecrets vault, File vaultFile,
            String originalConfig) throws Exception {
        String clearPath = InstrumentationRegistry.getArguments().getString("ctosSshKeyRequestFile");
        assertNotNull("Deletion regression also requires an explicitly supplied clear sibling request", clearPath);
        assertTrue("Invalid clear sibling request path", clearPath.matches("/data/local/tmp/ctos-ssh-key-[A-Za-z0-9_-]{1,64}\\.json"));
        JSONObject clearRequest = readKeyRequest(clearPath);
        byte[] clearKey = null, trust = null;
        try {
            for (String field : new String[] {"host", "port", "username", "fingerprint"})
                assertEquals("Sibling requests must name the same explicit synthetic endpoint", request.get(field), clearRequest.get(field));
            try { clearKey = java.util.Base64.getDecoder().decode(clearRequest.getString("privateKey")); }
            catch (IllegalArgumentException invalid) { throw new AssertionError("Invalid clear sibling private-key encoding"); }
            finally { clearRequest.remove("privateKey"); }
            assertTrue("Clear sibling exceeds the bounded input", clearKey.length > 0 && clearKey.length <= SshKeyMaterial.MAX_BYTES);
            SshKeyMaterial.Result clearMaterial = SshKeyMaterial.inspect(clearKey, SystemClock.elapsedRealtime() + 60_000);
            assertEquals(SshKeyMaterial.Status.PARSED, clearMaterial.status); assertFalse(clearMaterial.encrypted);
            assertEquals("Sibling must carry the same actual public identity", material.fingerprint, clearMaterial.fingerprint);

            // This real login creates the phrase, exact KEY Account, trust and generated manual CONFIG.
            connectKey(request, keyId, null, passphrase, false, true);
            assertEquals(1, reviews.get()); releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            assertPersistedKey(vault, privateKey, material, keyId, request.getString("host"), request.getInt("port"),
                    request.getString("username"), true, passphrase);
            trust = deletionTrust(vault);

            Map<String, Object> sibling = pickImport("privateKey", ".ssh/id_other", "id_other", clearKey);
            assertPreview(sibling, "privateKey", ".ssh/id_other", "id_other", false); commitImport(sibling);
            sibling = pickImport("privateKey", ".ssh/id_other_encrypted", "id_other_encrypted", privateKey);
            sibling = unlockEncryptedImport(sibling, passphrase, vaultFile, false, ".ssh/id_other_encrypted");
            assertPreview(sibling, "privateKey", ".ssh/id_other_encrypted", "id_other_encrypted", false); commitImport(sibling);
            Map<String, Object> library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            String clearId = (String)deletionKey(library, ".ssh/id_other").get("id");
            String encryptedId = (String)deletionKey(library, ".ssh/id_other_encrypted").get("id");
            assertNotEquals(keyId, clearId); assertNotEquals(keyId, encryptedId); assertNotEquals(clearId, encryptedId);

            String target = "    HostName " + request.getString("host") + "\n    Port " + request.getInt("port")
                    + "\n    User \"" + request.getString("username").replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"\n    IdentitiesOnly yes\n    UserKnownHostsFile ~/.ssh/known_hosts\n";
            String included = "Host deletionmixed\n" + target + "    IdentityFile ~/.ssh/id_fixture\n"
                    + "    IdentityFile ~/.ssh/id_other\n    IdentityFile ~/.ssh/id_other_encrypted\n"
                    + "Host deletionunaffected\n" + target + "    IdentityFile ~/.ssh/id_other\n";
            deletionImportConfig(".ssh/includes/deletion.conf", included);
            String root = "Include ~/.ssh/includes/deletion.conf\n" + originalConfig;
            deletionImportConfig(".ssh/config", root);
            Map<String, String> configs = new java.util.LinkedHashMap<>();
            configs.put(".ssh/config", root); configs.put(".ssh/includes/deletion.conf", included);
            library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            Map<String, Object> oldMixed = deletionHost(library, "deletionmixed");
            assertFalse(oldMixed.containsKey("error"));
            assertEquals(java.util.Set.of(keyId, clearId, encryptedId), new java.util.HashSet<>((List<?>)oldMixed.get("keyIds")));

            byte[] before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
            Map<String, Object> preview;
            try {
                preview = deletionPreview(keyId, material, true, true);
                assertCiphertextUnchanged(vaultFile, before);
                assertEquals(false, map(call("ssh.importCancel", VaultCollections.map("ticketId", preview.get("ticketId")))).get("cancelled"));
                assertFixedHostFailure("VAULT_CHANGED", "ssh.importCommit", VaultCollections.map("requestId", id(),
                        "ticketId", preview.get("ticketId"), "binding", preview.get("binding"), "replace", false));
                assertFixedHostFailure("VAULT_CHANGED", "ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                        "ticketId", preview.get("ticketId"), "binding", "0000000000000000000000000000000000000000000000000000000000000000"));
                assertCiphertextUnchanged(vaultFile, before);
                assertEquals(true, map(call("ssh.keyDeleteCancel", VaultCollections.map("ticketId", preview.get("ticketId")))).get("cancelled"));
                pendingDeleteTicket = null;
                assertFixedHostFailure("VAULT_CHANGED", "ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                        "ticketId", preview.get("ticketId"), "binding", preview.get("binding")));
                assertCiphertextUnchanged(vaultFile, before);
            } finally { VaultCodec.wipe(before); }

            preview = deletionPreview(keyId, material, true, true); commitDeletion(preview);
            assertFixedHostFailure("VAULT_CHANGED", "ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                    "ticketId", preview.get("ticketId"), "binding", preview.get("binding")));
            assertDeletionGraph(vault, privateKey, clearKey, keyId, clearId, encryptedId, configs, true, false, trust);
            assertDeletionNativeResourcePolicy(vault);
            library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            Map<String, Object> mixed = assertDeletionHints(library, "deletionmixed");
            assertDeletionHints(library, "fixturekey");
            Map<String, Object> unaffected = deletionHost(library, "deletionunaffected");
            assertFalse(unaffected.containsKey("error"));
            assertEquals(java.util.Set.of(clearId, encryptedId), new java.util.HashSet<>((List<?>)unaffected.get("keyIds")));
            before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
            try {
                assertEquals(java.util.Set.of(clearId, encryptedId), new java.util.HashSet<>((List<?>)mixed.get("keyIds")));
                assertDeletionConnectRefused(request, keyId, null, passphrase, false, "SSH_KEY_MISSING");
                assertDeletionConnectRefused(request, clearId, oldMixed, null, false, "VAULT_CHANGED");
                assertCiphertextUnchanged(vaultFile, before);
                // Imported identity hints remain editable after the selected key was removed.
                for (String invalidConfig : List.of(root, "Include ~/.ssh/includes/deletion.conf\n")) {
                    // The second edit has no direct missing identity: strictness must cover the child.
                    call("ssh.configRead", VaultCollections.map("requestId", id(), "path", ".ssh/config"));
                    Map<String, Object> rejected = map(call("ssh.configPreview", VaultCollections.map("requestId", id(),
                            "path", ".ssh/config", "content", invalidConfig)));
                    pendingImportTicket = (String)rejected.get("ticketId");
                    assertEquals(true, rejected.get("canCommit"));
                    assertTrue(((List<?>)rejected.get("issues")).isEmpty());
                    call("ssh.importCancel", VaultCollections.map("ticketId", pendingImportTicket)); pendingImportTicket = null;
                }
                assertCiphertextUnchanged(vaultFile, before);
            } finally { VaultCodec.wipe(before); }

            Map<String, Object> restored = pickImport("privateKey", ".ssh/id_fixture", "id_fixture", privateKey);
            assertFixedHostFailure("VAULT_CHANGED", "ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                    "ticketId", restored.get("ticketId"), "binding", restored.get("binding")));
            restored = unlockEncryptedImport(restored, passphrase, vaultFile, false);
            assertPreview(restored, "privateKey", ".ssh/id_fixture", "id_fixture", false); commitImport(restored);
            assertDeletionGraph(vault, privateKey, clearKey, keyId, clearId, encryptedId, configs, false, false, trust);
            library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            assertFalse(deletionHost(library, "deletionmixed").containsKey("error"));
            assertEquals(false, deletionKey(library, ".ssh/id_fixture").get("hasSavedPassphrase"));

            // Recreate only the Account/manual CONFIG by real transient authentication, then
            // make the generated CONFIG an actual user Include dependency before deletion.
            connectKey(request, keyId, null, passphrase, false, false);
            assertEquals(1, reviews.get()); releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            String withManual = "Include ~/.ssh/ctos-manual\n" + root;
            deletionImportConfig(".ssh/config", withManual); configs.put(".ssh/config", withManual);
            byte[] manual = deletionConfigBytes(vault, SshConnectionMaterial.MANUAL);
            try {
                preview = deletionPreview(keyId, material, false, false); commitDeletion(preview);
                assertDeletionGraph(vault, privateKey, clearKey, keyId, clearId, encryptedId, configs, true, true, trust);
                byte[] retained = deletionConfigBytes(vault, SshConnectionMaterial.MANUAL);
                try { assertArrayEquals("Inbound Include must preserve the generated CONFIG bytes", manual, retained); }
                finally { VaultCodec.wipe(retained); }
            } finally { VaultCodec.wipe(manual); }
            assertEquals("Only the first real offered host requires review", 1, reviews.get());
        } finally { clearRequest.remove("privateKey"); VaultCodec.wipe(clearKey); VaultCodec.wipe(trust); }
    }

    private void deletionImportConfig(String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            Map<String, Object> preview = pickImport("config", path, SshImportProposal.basename(path), bytes);
            assertEquals(true, preview.get("canCommit")); commitImport(preview);
        } finally { VaultCodec.wipe(bytes); }
    }

    private Map<String, Object> deletionPreview(String keyId, SshKeyMaterial.Result material,
            boolean phrase, boolean removesManual) throws Exception {
        Map<String, Object> preview = map(call("ssh.keyDeletePreview", VaultCollections.map("requestId", id(), "keyId", keyId)));
        pendingDeleteTicket = (String)preview.get("ticketId");
        assertEquals(java.util.Set.of("ticketId", "binding", "keyId", "label", "path", "algorithm", "fingerprint",
                "publicPath", "hasSavedPassphrase", "accountCount", "configPaths", "removesManualConfig"), preview.keySet());
        assertEquals(keyId, preview.get("keyId")); assertEquals("id_fixture", preview.get("label"));
        assertEquals(".ssh/id_fixture", preview.get("path")); assertEquals(".ssh/id_fixture.pub", preview.get("publicPath"));
        assertEquals(material.algorithm, preview.get("algorithm")); assertEquals(material.fingerprint, preview.get("fingerprint"));
        assertEquals(phrase, preview.get("hasSavedPassphrase")); assertEquals(1, ((Number)preview.get("accountCount")).intValue());
        assertEquals(removesManual, preview.get("removesManualConfig"));
        assertEquals(java.util.Set.of(".ssh/config", ".ssh/includes/deletion.conf"), new java.util.HashSet<>((List<?>)preview.get("configPaths")));
        assertTrue(((String)preview.get("binding")).matches("[0-9a-f]{64}")); return preview;
    }

    private void commitDeletion(Map<String, Object> preview) throws Exception {
        Map<String, Object> reply = map(call("ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                "ticketId", preview.get("ticketId"), "binding", preview.get("binding"))));
        assertEquals(java.util.Set.of("deleted"), reply.keySet()); assertEquals(true, reply.get("deleted")); pendingDeleteTicket = null;
    }

    private static Map<String, Object> deletionKey(Map<String, Object> library, String path) {
        for (Object value : (List<?>)library.get("keys")) {
            Map<String, Object> key = map(value); if (path.equals(key.get("path"))) return key;
        }
        throw new AssertionError("Required synthetic managed key row is missing");
    }

    private static Map<String, Object> deletionHost(Map<String, Object> library, String alias) {
        for (Object value : (List<?>)library.get("hosts")) {
            Map<String, Object> host = map(value); if (alias.equals(host.get("alias"))) return host;
        }
        throw new AssertionError("Required synthetic Host alias is missing");
    }

    private static Map<String, Object> assertDeletionHints(Map<String, Object> library, String alias) {
        Map<String, Object> host = deletionHost(library, alias);
        assertFalse(host.containsKey("error")); assertFalse(((List<?>)host.get("keyIds")).isEmpty());
        assertTrue(((List<?>)host.get("identityHints")).contains("~/.ssh/id_fixture")); return host;
    }

    private void assertDeletionConnectRefused(JSONObject request, String keyId, Map<String, Object> configured,
            byte[] phrase, boolean save, String code) throws Exception {
        try { connectKey(request, keyId, configured, phrase, false, save); fail("Missing identity must not reach authentication"); }
        catch (java.util.concurrent.ExecutionException rejected) {
            assertEquals("Fixed host failure: " + code, rejected.getCause().getMessage()); activeRequest = null;
        }
        assertFalse("Rejected selection must not publish a running terminal", running.get());
        assertEquals("Rejected selection must fail before offered host review", 1, reviews.get()); waitReleased();
    }

    private byte[] deletionTrust(AppSecrets vault) throws Exception { return deletionConfigBytes(vault, SshConnectionMaterial.HOSTS); }

    private byte[] deletionConfigBytes(AppSecrets vault, String path) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            VaultManifest.FileObject file = SshConnectionMaterial.file(source.manifest, path); assertNotNull(file);
            return file.content.clone();
        } finally { vault.cancelBundleContext(context); vault.retryPasswordValidationCleanup(); }
    }

    private void assertDeletionGraph(AppSecrets vault, byte[] encryptedKey, byte[] clearKey, String deletedId,
            String clearId, String encryptedId, Map<String, String> configs, boolean deleted,
            boolean manualPresent, byte[] trust) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            VaultManifest manifest = source.manifest;
            assertEquals(deleted ? 2 : 3, manifest.keyBindings.size());
            assertTrue("Deleted phrase and reimport must not resurrect saved entries", manifest.entries.isEmpty());
            assertTrue("Deleted KEY Accounts and reimport must not resurrect runtime associations", manifest.accounts.isEmpty());
            assertEquals(!deleted, SshConnectionMaterial.file(manifest, ".ssh/id_fixture") != null);
            assertEquals(!deleted, SshConnectionMaterial.file(manifest, ".ssh/id_fixture.pub") != null);
            assertEquals(!deleted, manifest.keyBindings.stream().anyMatch(binding -> deletedId.equals(binding.id)));
            for (String path : new String[] {".ssh/id_other", ".ssh/id_other_encrypted"}) {
                VaultManifest.FileObject file = SshConnectionMaterial.file(manifest, path); assertNotNull(file);
                assertArrayEquals("Deleting one path must preserve its same-public sibling's exact container",
                        path.equals(".ssh/id_other") ? clearKey : encryptedKey, file.content);
                assertNotNull(SshConnectionMaterial.file(manifest, path + ".pub"));
                SshConnectionMaterial.Key key = SshConnectionMaterial.key(manifest, path.equals(".ssh/id_other") ? clearId : encryptedId);
                assertEquals(path, key.path); assertFalse(key.hasSavedPassphrase);
            }
            for (Map.Entry<String, String> config : configs.entrySet()) {
                VaultManifest.FileObject file = SshConnectionMaterial.file(manifest, config.getKey()); assertNotNull(file);
                assertArrayEquals("Deletion/reimport must preserve every user CONFIG byte",
                        config.getValue().getBytes(StandardCharsets.UTF_8), file.content);
                assertEquals("Reimport must refresh all actual direct identity references", !deleted, file.references.contains(".ssh/id_fixture"));
                assertFalse(file.references.contains(".ssh/id_fixture.pub"));
            }
            assertEquals(manualPresent, SshConnectionMaterial.file(manifest, SshConnectionMaterial.MANUAL) != null);
            if (manualPresent) assertFalse(SshConnectionMaterial.file(manifest, SshConnectionMaterial.MANUAL).references.contains(".ssh/id_fixture"));
            assertArrayEquals("Deletion must preserve actual approved host trust", trust,
                    SshConnectionMaterial.file(manifest, SshConnectionMaterial.HOSTS).content);
            if (!deleted) assertArrayEquals(encryptedKey, SshConnectionMaterial.file(manifest, ".ssh/id_fixture").content);
        } finally { vault.cancelBundleContext(context); vault.retryPasswordValidationCleanup(); }
    }

    private void assertDeletionNativeResourcePolicy(AppSecrets vault) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        SshPasswordManifestValidator validator = new SshPasswordManifestValidator();
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            deletionNativeResourceCase(source.manifest, validator, deadline, "IdentityFile ~/.ssh/deletion-absent", true);
            deletionNativeResourceCase(source.manifest, validator, deadline, "CertificateFile ~/.ssh/deletion-absent", false);
            deletionNativeResourceCase(source.manifest, validator, deadline, "IdentityFile %h", true);
            deletionNativeResourceCase(source.manifest, validator, deadline, "IdentityFile relative-key", true);
            deletionNativeResourceCase(source.manifest, validator, deadline, "IdentityFile ~/.ssh/known_hosts", true);
            deletionNativeResourceCase(source.manifest, validator, deadline, "UserKnownHostsFile ~/.ssh/deletion-absent", false);
            deletionNativeResourceCase(source.manifest, validator, deadline, "Include ~/.ssh/deletion-absent", false);
        } finally {
            try { validator.retryCleanup(); }
            finally { vault.cancelBundleContext(context); vault.retryPasswordValidationCleanup(); }
        }
    }

    private void deletionNativeResourceCase(VaultManifest source, SshPasswordManifestValidator validator,
            long deadline, String declaration, boolean allowed) throws Exception {
        String path = ".ssh/deletion-policy";
        byte[] content = ("Host deletionpolicy\n    " + declaration + "\n").getBytes(StandardCharsets.UTF_8);
        VaultManifest.FileObject config = new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                SshConnectionMaterial.id("config", path), path, SshConnectionMaterial.id("config-owner", path),
                content, List.of(SshConnectionMaterial.HOSTS));
        try (SshBundleProposal.Change change = SshBundleProposal.Change.add(config);
                SshBundleProposal proposal = new SshBundleProposal(List.of(change));
                VaultManifest candidate = proposal.applyTo(source)) {
            boolean accepted = false;
            try { SshPasswordManifestValidator.within(deadline, () -> { validator.validate(candidate); return null; }); accepted = true; }
            catch (VaultException rejected) {
                if (allowed) throw new AssertionError("Bounded absent static IdentityFile must remain representable: "
                        + fixedKeyValidationFailure(rejected));
            }
            assertEquals("Identity hints grant no resource-read authority: " + declaration, allowed, accepted);
        } finally { VaultCodec.wipe(content); VaultCodec.wipe(config.content); }
    }

    private void assertPersistedKey(AppSecrets vault, byte[] privateKey, SshKeyMaterial.Result material,
            String keyId, String host, int port, String username, boolean connected) throws Exception {
        assertPersistedKey(vault, privateKey, material, keyId, host, port, username, connected, null);
    }

    private void assertPersistedKey(AppSecrets vault, byte[] privateKey, SshKeyMaterial.Result material,
            String keyId, String host, int port, String username, boolean connected, byte[] savedPassphrase) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            assertEquals(1, source.manifest.keyBindings.size());
            VaultSshMetadata.KeyBinding binding = source.manifest.keyBindings.get(0); assertEquals(keyId, binding.id);
            if (savedPassphrase == null) {
                assertNull(binding.passphraseEntryId); assertTrue(source.manifest.entries.isEmpty());
            } else {
                assertEquals(1, source.manifest.entries.size());
                VaultManifest.Entry phrase = SshKeyPassphrase.entry(source.manifest, binding);
                assertNotNull(phrase); assertTrue("Only the explicitly saved synthetic phrase may persist",
                        java.util.Arrays.equals(savedPassphrase, phrase.secret));
            }
            assertTrue(binding.certificateFileIds.isEmpty());
            VaultManifest.FileObject actualPrivate = SshConnectionMaterial.file(source.manifest, ".ssh/id_fixture");
            VaultManifest.FileObject actualPublic = SshConnectionMaterial.file(source.manifest, ".ssh/id_fixture.pub");
            assertEquals(VaultManifest.FileType.PRIVATE_KEY, actualPrivate.type);
            assertEquals(actualPrivate.id, binding.privateFileId); assertEquals(actualPublic.id, binding.publicFileId);
            assertTrue("Encrypted store must retain the exact imported private bytes", java.util.Arrays.equals(privateKey, actualPrivate.content));
            byte[] line = material.publicLine();
            try { assertTrue("Persisted public data must be actual native exported key material", java.util.Arrays.equals(line, actualPublic.content)); }
            finally { VaultCodec.wipe(line); }
            SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(source.manifest, binding);
            assertEquals(material.fingerprint, key.fingerprint); assertEquals(material.algorithm, key.algorithm);
            assertEquals(material.encrypted, key.encrypted); assertEquals(savedPassphrase != null, key.hasSavedPassphrase);
            assertEquals(connected ? 1 : 0, source.manifest.accounts.size());
            VaultManifest.FileObject trust = SshConnectionMaterial.file(source.manifest, ".ssh/known_hosts");
            assertNotNull(trust); assertEquals(connected, trust.content.length > 0);
            if (connected) {
                VaultSshMetadata.Account account = source.manifest.accounts.get(0);
                assertEquals(VaultSshMetadata.AuthKind.KEY, account.authKind);
                assertEquals(keyId, account.credentialId); assertEquals(host, account.host); assertEquals(port, account.port);
                assertEquals(username, account.username);
                assertTrue(new SshConnectionMaterial.KeyAccount(new SshPasswordProfile(host, port, username), key).matches(account));
            }
        } finally { vault.cancelBundleContext(context); vault.retryPasswordValidationCleanup(); }
    }

    private JSONObject readKeyRequest(String path) throws Exception {
        return readKeyRequest(path, false);
    }

    private JSONObject readKeyRequest(String path, boolean encrypted) throws Exception {
        byte[] ownership = keyShellBytes("stat -c %a:%u:%F " + path, 128);
        try { assertEquals("Fixture request must be a shell-owned 0600 regular file", "600:2000:regular file",
                new String(ownership, StandardCharsets.US_ASCII).trim()); }
        finally { VaultCodec.wipe(ownership); }
        byte[] bytes = keyShellBytes("cat " + path, 1_500_000);
        try {
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            assertTrue("Required synthetic private key is missing", request.opt("privateKey") instanceof String);
            assertTrue("Required public fixture metadata is missing", request.opt("host") instanceof String
                    && request.opt("username") instanceof String && request.opt("fingerprint") instanceof String
                    && request.opt("port") instanceof Integer);
            if (encrypted) assertTrue("Required synthetic key passphrase is missing", request.opt("passphrase") instanceof String);
            assertEquals("Only the exact key fixture fields are accepted", encrypted ? 6 : 5, request.length()); return request;
        } catch (org.json.JSONException invalid) { throw new AssertionError("Invalid synthetic key fixture request"); }
        finally { VaultCodec.wipe(bytes); }
    }

    private byte[] keyShellBytes(String command, int maximum) throws Exception {
        byte[] scratch = new byte[maximum + 1];
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            int length = 0, count;
            while ((count = input.read(scratch, length, scratch.length - length)) >= 0) {
                length += count; assertTrue("Fixture request is oversized", length <= maximum);
            }
            return java.util.Arrays.copyOf(scratch, length);
        } finally { VaultCodec.wipe(scratch); }
    }

    /**
     * Opt-in real changed-key recovery, prepared separately from the JNI-only material tests.
     * ctosSshHostTrustRequestFile names a shell-owned0600 regular file under
     * /data/local/tmp/ctos-ssh-host-trust-NAME.json. Exact fields: host,port,username,password,
     * oldPublicKey (one synthetic ssh-ed25519 public line without host/comment/newline),
     * oldFingerprint, fingerprint (the independently observed NEW server fingerprint).
     * The separately provisioned synthetic server serves the NEW Ed25519 key throughout.
     */
    @Test public void actualBridgeChangedTrustForgetThenNewAndKnownPasswordShell() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("ctosSshHostTrustRequestFile");
        Assume.assumeTrue("Explicit independent host-trust fixture request was not provided", path != null);
        assertTrue("Invalid host-trust fixture request path",
                path.matches("/data/local/tmp/ctos-ssh-host-trust-[A-Za-z0-9_-]{1,64}\\.json"));
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse(context.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        try {
            assertNotEquals("Host-trust fixture must never share the formal application's UID", android.os.Process.myUid(),
                    context.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException absentFormalApp) { /* Isolated fixture only. */ }
        assertFalse("No existing SSH service may be interrupted by this fixture", SshSessionService.active());
        File vaultFile = new File(context.getNoBackupFilesDir(), "app-secrets.bin");
        for (String suffix : new String[] {"", ".bak", ".new"})
            assertFalse("Fresh isolated fixture app data is required", new File(vaultFile.getPath() + suffix).exists());
        JSONObject request = readHostTrustRequest(path);
        String host = request.getString("host"), username = request.getString("username");
        int port = request.getInt("port"), otherPort = port == 65535 ? 65534 : port + 1;
        String oldFingerprint = request.getString("oldFingerprint"), fingerprint = request.getString("fingerprint");
        String publicKey = request.getString("oldPublicKey");
        String lookup = hostTrustLookup(host, port);
        String retained = "# synthetic host-trust fixture\r\n" + hostTrustLookup(host, otherPort) + " " + publicKey
                + "\r\nctos-unrelated.invalid " + publicKey + "\n# retained without final newline";
        byte[] remaining = retained.getBytes(StandardCharsets.US_ASCII);
        byte[] original = ("# synthetic host-trust fixture\r\n" + lookup + " " + publicKey + "\n"
                + retained.substring(retained.indexOf('\n') + 1)).getBytes(StandardCharsets.US_ASCII);
        boolean created = false, observed = false;
        Throwable primaryFailure = null;
        try {
            activity = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            dispatch = MainActivity.class.getDeclaredMethod("call", MethodCall.class, MethodChannel.Result.class);
            dispatch.setAccessible(true);
            ssh = (SshPasswordConnectBridge)field(activity, "sshPasswordConnect");
            AppSecretsBridge secrets = (AppSecretsBridge)field(activity, "appSecrets");
            assertSame(AppSecretsOwner.acquire(activity), secrets.sshVault());
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            String master = "Fixture-" + UUID.randomUUID();
            created = true;
            call("secrets.create", VaultCollections.map("requestId", id(), "password", master, "confirmation", master));
            seedHostTrustFixture(secrets.sshVault(), new SshPasswordProfile(host, port, username), original);
            installObservers(host, port, fingerprint); observed = true;
            assertTrustQuery(host, port, oldFingerprint, true, true);
            assertTrustQuery(host, otherPort, oldFingerprint, true, true);
            assertTrustQuery("ctos-unrelated.invalid", 22, oldFingerprint, true, true);

            byte[] before = java.nio.file.Files.readAllBytes(vaultFile.toPath());
            try {
                Map<String, Object> preview = trustPreview(host, port, "password", oldFingerprint);
                assertEquals(false, map(call("ssh.importCancel", VaultCollections.map("ticketId", preview.get("ticketId")))).get("cancelled"));
                assertFixedHostFailure("VAULT_CHANGED", "ssh.keyDeleteCommit", VaultCollections.map("requestId", id(),
                        "ticketId", preview.get("ticketId"), "binding", preview.get("binding")));
                assertEquals(true, map(call("ssh.hostTrustForgetCancel", VaultCollections.map("ticketId", pendingTrustTicket))).get("cancelled"));
                pendingTrustTicket = null;
                assertFixedHostFailure("VAULT_CHANGED", "ssh.hostTrustForgetCommit", VaultCollections.map("requestId", id(),
                        "ticketId", preview.get("ticketId"), "binding", preview.get("binding")));
                assertCiphertextUnchanged(vaultFile, before);
                try { connect(request); fail("The actual changed server key must be refused"); }
                catch (java.util.concurrent.ExecutionException rejected) {
                    assertEquals("Fixed host failure: SSH_HOST_KEY_CHANGED", rejected.getCause().getMessage()); activeRequest = null;
                }
                assertFalse("A changed key must not publish RUNNING", running.get());
                assertEquals("Changed trust must not be converted into a NEW review", 0, reviews.get());
                waitReleased(); assertCiphertextUnchanged(vaultFile, before);
            } finally { VaultCodec.wipe(before); }

            Map<String, Object> configPreview = trustPreview(host, port, "config", oldFingerprint);
            trustCommit(configPreview);
            assertTrustBodies(secrets.sshVault(), original, remaining);
            assertTrustQuery(host, port, oldFingerprint, true, false);
            Map<String, Object> passwordPreview = trustPreview(host, port, "password", oldFingerprint);
            trustCommit(passwordPreview);
            assertFixedHostFailure("VAULT_CHANGED", "ssh.hostTrustForgetCommit", VaultCollections.map("requestId", id(),
                    "ticketId", passwordPreview.get("ticketId"), "binding", passwordPreview.get("binding")));
            assertTrustBodies(secrets.sshVault(), remaining, remaining);
            assertTrustQuery(host, port, oldFingerprint, false, false);
            assertTrustQuery(host, otherPort, oldFingerprint, true, true);
            assertTrustQuery("ctos-unrelated.invalid", 22, oldFingerprint, true, true);
            assertEquals("Management must never approve a key or start a connection", 0, reviews.get());
            assertFalse("Forgetting old trust must not automatically connect", SshSessionService.active());

            connect(request);
            assertEquals("The fresh actual server key needs its own exact pinned NEW approval", 1, reviews.get());
            assertFixedHostFailure("BUSY", "ssh.hostTrustQuery", VaultCollections.map("requestId", id(), "host", host, "port", port));
            assertFixedHostFailure("BUSY", "ssh.hostTrustForgetPreview", VaultCollections.map("requestId", id(),
                    "host", host, "port", port, "group", "password"));
            releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            assertTrustQuery(host, port, fingerprint, true, false);
            assertTrustQuery(host, otherPort, oldFingerprint, true, true);
            assertTrustQuery("ctos-unrelated.invalid", 22, oldFingerprint, true, true);
            byte[] preservedConfigTrust = deletionConfigBytes(secrets.sshVault(), SshConnectionMaterial.HOSTS);
            try { assertArrayEquals("NEW trust saving must preserve the other exact trust object", remaining, preservedConfigTrust); }
            finally { VaultCodec.wipe(preservedConfigTrust); }
            connect(request);
            assertEquals("The actual saved NEW key must be reused as KNOWN without another review", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip(); stopTerminal(); waitReleased();
        } catch (Exception | Error failure) { primaryFailure = failure; throw failure; }
        finally {
            request.remove("password");
            Throwable[] cleanupFailure = {primaryFailure};
            if (activity != null && dispatch != null && pendingTrustTicket != null)
                keyFixtureCleanup(cleanupFailure, () -> call("ssh.hostTrustForgetCancel", VaultCollections.map("ticketId", pendingTrustTicket)));
            pendingTrustTicket = null;
            boolean ownVault = created, ownObservers = observed;
            boolean ownedTerminal = connectedRequest != null || observedSshTerminal.get();
            // Pre-auth refusal has only an owned connection request, not an attached shell.
            // Cancel that request and still await service/native cleanup without a stale terminalStop.
            keyFixtureCleanup(cleanupFailure,
                    () -> cleanupKeyFixture(request, null, vaultFile, ownVault, ownObservers, ownedTerminal));
            VaultCodec.wipe(original); VaultCodec.wipe(remaining);
            if (primaryFailure == null && cleanupFailure[0] != null) {
                if (cleanupFailure[0] instanceof Error) throw (Error)cleanupFailure[0];
                throw (Exception)cleanupFailure[0];
            }
        }
    }

    private JSONObject readHostTrustRequest(String path) throws Exception {
        byte[] ownership = keyShellBytes("stat -c %a:%u:%F " + path, 128);
        try { assertEquals("Fixture request must be a shell-owned0600 regular file", "600:2000:regular file",
                new String(ownership, StandardCharsets.US_ASCII).trim()); }
        finally { VaultCodec.wipe(ownership); }
        byte[] encoded = keyShellBytes("cat " + path, 32768);
        try {
            JSONObject request = new JSONObject(new String(encoded, StandardCharsets.UTF_8));
            assertEquals("Only the exact host-trust fixture fields are accepted", 7, request.length());
            for (String field : new String[] {"host", "username", "password", "oldPublicKey", "oldFingerprint", "fingerprint"})
                assertTrue("Required synthetic host-trust fixture field is missing", request.opt(field) instanceof String);
            assertTrue("An exact synthetic port is required", request.opt("port") instanceof Integer);
            new SshPasswordProfile(request.getString("host"), request.getInt("port"), request.getString("username"));
            assertNotEquals("The preservation target must differ from the supplied connection", "ctos-unrelated.invalid",
                    request.getString("host").toLowerCase(java.util.Locale.ROOT));
            assertTrue("A bounded single synthetic Ed25519 public line is required",
                    request.getString("oldPublicKey").matches("ssh-ed25519 [A-Za-z0-9+/]{1,4096}={0,2}"));
            for (String field : new String[] {"oldFingerprint", "fingerprint"})
                assertTrue("An explicit complete synthetic fingerprint is required", request.getString(field).matches("SHA256:[A-Za-z0-9+/]{43}"));
            assertNotEquals("The independently supplied old and new keys must differ",
                    request.getString("oldFingerprint"), request.getString("fingerprint"));
            return request;
        } catch (org.json.JSONException invalid) { throw new AssertionError("Invalid synthetic host-trust fixture request"); }
        finally { VaultCodec.wipe(encoded); }
    }

    private static String hostTrustLookup(String host, int port) {
        return (port == 22 ? host : "[" + host + "]:" + port).toLowerCase(java.util.Locale.ROOT);
    }

    private void seedHostTrustFixture(AppSecrets vault, SshPasswordProfile profile, byte[] body) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        List<VaultManifest.FileObject> files = new java.util.ArrayList<>();
        List<SshBundleProposal.Change> changes = new java.util.ArrayList<>();
        byte[] config = profile.configBytes();
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            assertTrue("Only the fresh empty fixture vault may be seeded", source.manifest.files.isEmpty() && source.manifest.entries.isEmpty());
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.CONFIG, SshPasswordProfile.CONFIG_ID,
                    SshPasswordProfile.CONFIG_PATH, SshPasswordProfile.CONFIG_IDENTITY, config, List.of(SshPasswordProfile.HOSTS_PATH)));
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS, SshPasswordProfile.HOSTS_ID,
                    SshPasswordProfile.HOSTS_PATH, SshPasswordProfile.HOSTS_IDENTITY, body, List.of()));
            files.add(new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                    SshConnectionMaterial.id("hosts", SshConnectionMaterial.HOSTS), SshConnectionMaterial.HOSTS,
                    SshConnectionMaterial.id("hosts-owner", SshConnectionMaterial.HOSTS), body, List.of()));
            for (VaultManifest.FileObject file : files) changes.add(SshBundleProposal.Change.add(file));
            try (SshBundleProposal proposal = new SshBundleProposal(changes)) {
                try (VaultManifest candidate = proposal.applyTo(source.manifest)) { vault.validateSshCandidate(context, candidate); }
                SshVaultAdapter adapter = new SshVaultAdapter(vault, (connection, target) -> { throw VaultException.invalid(); },
                        trust -> { throw VaultException.invalid(); }, exact -> assertSame(proposal, exact));
                VaultOperation operation = SshPasswordManifestValidator.within(deadline, () -> adapter.bundleRequest(proposal, context));
                try { SshPasswordManifestValidator.within(deadline, () -> { adapter.commitBundle(operation, proposal, () -> { }); return null; }); }
                finally { adapter.cancel(operation); }
            }
        } finally {
            try { vault.cancelBundleContext(context); vault.retryPasswordValidationCleanup(); }
            finally {
                for (SshBundleProposal.Change change : changes) change.close();
                for (VaultManifest.FileObject file : files) VaultCodec.wipe(file.content);
                VaultCodec.wipe(config);
            }
        }
    }

    private void assertTrustQuery(String host, int port, String fingerprint, boolean password, boolean config) throws Exception {
        Map<String, Object> query = map(call("ssh.hostTrustQuery", VaultCollections.map("requestId", id(), "host", host, "port", port)));
        assertEquals(java.util.Set.of("host", "port", "groups"), query.keySet());
        assertEquals(host, query.get("host")); assertEquals(port, query.get("port"));
        List<?> groups = (List<?>)query.get("groups"); assertEquals(2, groups.size());
        for (int index = 0; index < 2; index++) {
            Map<String, Object> group = map(groups.get(index)); boolean expected = index == 0 ? password : config;
            assertEquals(index == 0 ? "password" : "config", group.get("group"));
            assertEquals(true, group.get("exists")); assertEquals(expected, group.get("canForget"));
            List<?> records = (List<?>)group.get("records"); assertEquals(expected ? 1 : 0, records.size());
            if (expected) {
                Map<String, Object> record = map(records.get(0)); assertEquals(fingerprint, record.get("fingerprint"));
                assertEquals("none", record.get("reason")); assertEquals("none", record.get("marker"));
            }
        }
    }

    private Map<String, Object> trustPreview(String host, int port, String group, String oldFingerprint) throws Exception {
        Map<String, Object> preview = map(call("ssh.hostTrustForgetPreview", VaultCollections.map("requestId", id(),
                "host", host, "port", port, "group", group)));
        pendingTrustTicket = (String)preview.get("ticketId");
        assertEquals(host, preview.get("host")); assertEquals(port, preview.get("port")); assertEquals(group, preview.get("group"));
        List<?> rows = (List<?>)preview.get("records"); assertEquals(1, rows.size());
        assertEquals(oldFingerprint, map(rows.get(0)).get("fingerprint")); assertEquals("none", map(rows.get(0)).get("reason"));
        return preview;
    }

    private void trustCommit(Map<String, Object> preview) throws Exception {
        AppSecrets vault = ((AppSecretsBridge)field(activity, "appSecrets")).sshVault();
        long revision = vault.contentRevision;
        Map<String, Object> reply = map(call("ssh.hostTrustForgetCommit", VaultCollections.map("requestId", id(),
                "ticketId", preview.get("ticketId"), "binding", preview.get("binding"))));
        assertEquals(java.util.Set.of("forgotten"), reply.keySet()); assertEquals(true, reply.get("forgotten"));
        assertEquals("One reviewed commit must consume exactly one content revision", revision + 1, vault.contentRevision);
        pendingTrustTicket = null;
    }

    private void assertTrustBodies(AppSecrets vault, byte[] password, byte[] config) throws Exception {
        for (String path : new String[] {SshPasswordProfile.HOSTS_PATH, SshConnectionMaterial.HOSTS}) {
            byte[] actual = deletionConfigBytes(vault, path);
            try { assertArrayEquals("Only reviewed target bytes may change", path.equals(SshPasswordProfile.HOSTS_PATH) ? password : config, actual); }
            finally { VaultCodec.wipe(actual); }
        }
    }

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
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            String master = "Fixture-" + UUID.randomUUID();
            call("secrets.create", VaultCollections.map("requestId", id(), "password", master, "confirmation", master));
            assertTrue(vaultFile.exists());
            installObservers(host, port, fingerprint);
            Map<String, Object> empty = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals("", empty.get("host")); assertEquals("", empty.get("username"));
            assertEquals(false, empty.get("savedPassword"));
            connect(request);
            assertEquals(1, reviews.get());
            releaseProtection();
            assertShellRoundTrip(); // Form privacy ends; this established shell must remain usable.
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            Map<String, Object> saved = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals(host, saved.get("host")); assertEquals(port, ((Number)saved.get("port")).intValue());
            assertEquals(username, saved.get("username"));
            assertEquals(false, saved.get("savedPassword"));
            connect(request, false, true);
            String priorConnection = connectedRequest;
            assertEquals("An already saved matching host must not request approval again", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
            stopTerminal(); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            Map<String, Object> status = passwordStatus(request);
            assertEquals(java.util.Set.of("saved"), status.keySet()); assertEquals(true, status.get("saved"));
            Map<String, Object> savedDefaults = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals(true, savedDefaults.get("savedPassword"));
            assertEquals(java.util.Set.of("host", "port", "username", "savedPassword"), savedDefaults.keySet());
            connect(request, true, false);
            assertEquals("Saved password still requires actual matching KNOWN host proof", 1, reviews.get());
            assertEquals("A prior request cannot stop the current shell", false,
                    map(call("ssh.cancel", VaultCollections.map("requestId", priorConnection))).get("cancelled"));
            try {
                connect(request, true, false);
                fail("An occupied terminal must reject another connection");
            } catch (java.util.concurrent.ExecutionException rejected) {
                assertTrue(rejected.getCause() instanceof AssertionError);
                assertEquals("Fixed host failure: SSH_FAILED", rejected.getCause().getMessage());
                activeRequest = null;
            }
            releaseProtection(); assertShellRoundTrip();
            assertEquals("The successful handoff retains exact cancellation", true,
                    map(call("ssh.cancel", VaultCollections.map("requestId", connectedRequest))).get("cancelled"));
            waitReleased();
            assertEquals(false, map(call("ssh.cancel", VaultCollections.map("requestId", connectedRequest))).get("cancelled"));
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            Map<String, Object> forgotten = map(call("ssh.forgetPassword", VaultCollections.map("requestId", id(),
                    "host", host, "port", port, "username", username, "all", false)));
            assertEquals(java.util.Set.of("forgotten"), forgotten.keySet()); assertEquals(true, forgotten.get("forgotten"));
            assertEquals(false, passwordStatus(request).get("saved"));
            AppSecrets vault = secrets.sshVault();
            AppSecrets.BundleContext contextRead = vault.newBundleContext(SystemClock.elapsedRealtime() + 10000, () -> { });
            try (SshPasswordProfile.Snapshot snapshot = SshPasswordManifestValidator.within(
                    SystemClock.elapsedRealtime() + 10000, () -> vault.passwordProfile(contextRead))) {
                assertTrue("Forgetting passwords retains saved host trust", snapshot.knownHosts.content.length > 0);
                assertTrue(snapshot.accounts.isEmpty());
            } finally { vault.cancelBundleContext(contextRead); vault.retryPasswordValidationCleanup(); }
        } finally {
            request.remove("password");
            if (activity != null) {
                try {
                    if (activeRequest != null) call("ssh.cancel", VaultCollections.map("requestId", activeRequest));
                    stopTerminal(); waitReleased();
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
        connect(request, false, false);
    }

    private Map<String, Object> passwordStatus(JSONObject request) throws Exception {
        return map(call("ssh.passwordStatus", VaultCollections.map("requestId", id(),
                "host", request.getString("host"), "port", request.getInt("port"), "username", request.getString("username"))));
    }

    private void connect(JSONObject request, boolean useSaved, boolean save) throws Exception {
        running.set(false); activeRequest = id();
        Map<String, Object> connected = map(call("ssh.passwordConnect", VaultCollections.map(
                "requestId", activeRequest, "host", request.getString("host"), "port", request.getInt("port"),
                "username", request.getString("username"), "password", useSaved ? "" : request.getString("password"),
                "useSavedPassword", useSaved, "savePassword", save, "columns", 80, "rows", 24)));
        assertEquals(activeRequest, connected.get("requestId")); assertEquals("connected", connected.get("status"));
        assertTrue("The actual terminal RUNNING event must precede connected completion", running.get());
        connectedRequest = activeRequest;
        activeRequest = null;
    }

    private void installObservers(String host, int port, String fingerprint) throws Exception {
        awaitTerminalSubscription();
        instrumentation.runOnMainSync(() -> {
            try {
                oldSshSink = field(ssh, "sink"); oldTerminalSink = field(activity, "terminalSink");
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
                        captureDiagnosticTerminal(event);
                        if (("state".equals(event.get("type")) || "snapshot".equals(event.get("type")))
                                && "running".equals(event.get("state"))) {
                            running.set(true);
                            if (Boolean.TRUE.equals(event.get("remote"))) observedSshTerminal.set(true);
                        }
                        if ("data".equals(event.get("type")) || "snapshot".equals(event.get("type"))) synchronized (output) {
                            output.append(new String((byte[])event.get("bytes"), StandardCharsets.UTF_8));
                            if (output.length() > 65536) output.delete(0, output.length() - 65536);
                        }
                    }
                    @Override public void error(String code, String message, Object details) { throw new AssertionError(code); }
                    @Override public void endOfStream() { }
                });
                SshTerminalState.Listener listener = (SshTerminalState.Listener)field(activity, "sshListener");
                assertNotNull("The actual Flutter terminal subscription must exist before replay", listener);
                SshSessionService.subscribe(listener);
            } catch (Exception failure) { throw new AssertionError("Fixture observations could not be installed"); }
        });
    }

    private void captureDiagnosticTerminal(Map<?, ?> event) {
        if (diagnosticGeneration <= 0 || !Boolean.TRUE.equals(event.get("remote")) || !(event.get("generation") instanceof Number)
                || ((Number)event.get("generation")).intValue() != diagnosticGeneration
                || !("state".equals(event.get("type")) || "snapshot".equals(event.get("type")))
                || !("closed".equals(event.get("state")) || "failed".equals(event.get("state")))) return;
        Object raw = event.get("reason");
        String reason = raw == null ? "none" : raw instanceof String && (SshTerminalSession.isEstablishedFailureCode((String)raw)
                || java.util.Set.of("SSH_TERMINAL_IO", "SSH_TERMINAL_REJECTED",
                "SSH_TERMINAL_REVOKED", "SSH_TERMINAL_CLEANUP_PENDING", "SSH_TERMINAL_START", "SSH_SERVICE_START",
                "SSH_FAILED", "SSH_CANCELLED", "SSH_CLOSED", "SSH_SCREEN_LOCKED").contains(raw)) ? (String)raw : "redacted";
        firstDiagnosticTerminal.compareAndSet(null, "terminal_event=observed elapsed_realtime_ms="
                + SystemClock.elapsedRealtime() + " uptime_ms=" + SystemClock.uptimeMillis() + " generation="
                + diagnosticGeneration + " state=" + event.get("state") + " reason=" + reason + "\n");
    }

    private void persistDiagnosticTerminal() throws Exception {
        Context target = instrumentation.getTargetContext();
        assertEquals("Terminal diagnostics require the isolated target", "im.majo.ctos.secretsfixture", target.getPackageName());
        File directory = target.getExternalFilesDir(null);
        assertNotNull("Owned terminal diagnostic directory unavailable", directory);
        String notice = firstDiagnosticTerminal.get();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new File(directory, "ctos-screen-off-diagnostic.txt"), false)) {
            output.write((notice == null ? "terminal_event=unobserved\n" : notice).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void awaitTerminalSubscription() {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        AtomicBoolean subscribed = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                try {
                    subscribed.set(!activity.isDestroyed() && field(activity, "sshListener") != null
                            && field(activity, "terminalSink") != null);
                } catch (Exception failure) { throw new AssertionError("Flutter terminal readiness could not be observed", failure); }
            });
            if (subscribed.get()) return;
            SystemClock.sleep(20);
        }
        fail("The actual Flutter terminal EventChannel did not subscribe within 15 seconds after Activity creation");
    }

    /** Opt-in Home/recreation with notification Stop, denied permission or a disabled SSH channel. */
    private void assertBackgroundRecreationAndNotificationStop(String master, JSONObject request, String keyId,
            String host, int port, String fingerprint)
            throws Exception {
        boolean notificationsDenied = notificationsUnavailableRequested();
        if (notificationsDenied) assertNotificationsUnavailable(activity);
        int generation = SshSessionService.generation();
        assertTrue(generation > 0);
        AppSecrets vault = ((AppSecretsBridge)field(activity, "appSecrets")).sshVault();
        call("secrets.settings", VaultCollections.map("backgroundTimeoutSeconds", 2));
        Object service = staticField(SshSessionService.class, "service");
        android.os.PowerManager.WakeLock lock = (android.os.PowerManager.WakeLock)field(service, "wakeLock");
        assertTrue("Only the active SSH service holds its CPU lock", lock.isHeld());
        if (physicalLockRequested()) {
            Object oldRegistration = staticField(SshSessionService.class, "screenOff");
            assertNotNull("The active owner must register its own screen-off receiver", oldRegistration);
            android.content.BroadcastReceiver oldReceiver = (android.content.BroadcastReceiver)field(oldRegistration, "receiver");
            assertPhysicalScreenLock(vault, generation, lock);
            Map<?, ?> hint = (Map<?, ?>)((SshTerminalState)staticField(SshSessionService.class, "EVENTS")).snapshot().get("reconnect");
            assertEquals(java.util.Set.of("mode", "host", "port", "username", "keyId"), hint.keySet());
            assertEquals("key", hint.get("mode"));
            assertEquals(host, hint.get("host")); assertEquals(port, hint.get("port"));
            assertEquals(request.getString("username"), hint.get("username")); assertEquals(keyId, hint.get("keyId"));
            recreateTerminalActivity();
            android.app.UiAutomation automation = instrumentation.getUiAutomation(
                    android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo accessibility = automation.getServiceInfo();
            accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(accessibility);
            SshPasswordUiPlatformTest.Ui ui = new SshPasswordUiPlatformTest.Ui(automation);
            ui.waitText("终端", 15000); ui.click("终端");
            ui.waitText("已因锁屏断开", 10000); ui.waitText("重新连接", 10000);
            assertFalse("System unlock and Activity recreation must never reconnect", SshSessionService.active());
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            call("secrets.unlock", VaultCollections.map("requestId", id(), "password", master));
            releaseProtection();
            assertFalse("Vault unlock alone must never reconnect SSH", SshSessionService.active());
            ui.click("重新连接");
            ui.waitText("关闭会话", 30000); ui.waitField("命令输入", 10000);
            assertTrue("An explicit fresh authenticated connection must use a new generation",
                    SshSessionService.generation() > generation);
            assertFalse("The unchanged pinned host must not unexpectedly require another approval", ui.has("确认主机密钥"));
            running.set(false);
            installObservers(host, port, fingerprint);
            assertTrue("The real reconnect control must establish native RUNNING", running.get());
            assertEquals("The fresh connection must recheck the unchanged trusted host", 1, reviews.get());
            generation = SshSessionService.generation();
            service = staticField(SshSessionService.class, "service");
            lock = (android.os.PowerManager.WakeLock)field(service, "wakeLock");
            assertTrue("The new connection must own its own CPU lock", lock.isHeld());
            instrumentation.runOnMainSync(() -> oldReceiver.onReceive(instrumentation.getTargetContext(),
                    new Intent(Intent.ACTION_SCREEN_OFF)));
            assertEquals("A delayed old-owner screen-off callback must not stop a fresh connection",
                    generation, SshSessionService.generation());
            assertShellRoundTrip();
            physicalStage("screen_lock_explicit_reconnect_pass", "screen_lock");
        }
        instrumentation.runOnMainSync(() -> assertTrue(activity.moveTaskToBack(true)));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (vault.session.foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertFalse("Home must leave the actual Activity foreground", vault.session.foreground());
        SystemClock.sleep(2500);
        assertFalse("The configured background timeout must still lock the vault", vault.session.unlocked());
        assertEquals("Vault lock must retain the same authenticated shell", generation, SshSessionService.generation());
        String retainedMarker = assertShellRoundTrip();

        android.app.Notification notification = null;
        if (notificationsDenied) {
            assertNotificationsUnavailable(activity);
            instrumentation.runOnMainSync(() -> activity.startActivity(new Intent(activity, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        } else {
            android.app.NotificationManager notifications = activity.getSystemService(android.app.NotificationManager.class);
            for (android.service.notification.StatusBarNotification posted : notifications.getActiveNotifications())
                if (SshSessionService.CHANNEL.equals(posted.getNotification().getChannelId())) notification = posted.getNotification();
            assertNotNull("The actual foreground service notification must exist", notification);
            assertEquals("SSH 会话", notification.extras.getString(android.app.Notification.EXTRA_TITLE));
            String text = notification.extras.getString(android.app.Notification.EXTRA_TEXT);
            assertFalse("Notification content must not expose the endpoint", text.contains(host));
            assertNotNull(notification.actions);
            assertEquals(2, notification.actions.length);
            backgroundStaleStop = notification.actions[1].actionIntent;
            notification.contentIntent.send();
        }
        deadline = SystemClock.elapsedRealtime() + 10000;
        while (!vault.session.foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue("Returning must resume the actual Activity", vault.session.foreground());
        if (!notificationsDenied) {
            instrumentation.runOnMainSync(() -> {
                assertNull("Notification return must not inject an unintended Flutter deep-link route",
                        activity.getIntent().getData());
                assertTrue("The notification must retain a session-specific PendingIntent identity",
                        activity.getIntent().getType().startsWith("application/vnd.ctos.ssh-session."));
            });
        }
        assertEquals(generation, SshSessionService.generation());
        assertFalse("Returning must not unlock the vault", vault.session.unlocked());

        recreateTerminalActivity();
        assertEquals("Activity recreation must retain the original SSH generation", generation, SshSessionService.generation());
        assertTrue(lock.isHeld());
        assertRecreatedFlutterTerminal(retainedMarker, notificationsDenied ? null : notification.contentIntent);
        assertEquals("Real Flutter navigation/input must retain the original SSH generation",
                generation, SshSessionService.generation());
        assertFalse(((AppSecretsBridge)field(activity, "appSecrets")).sshVault().session.unlocked());
        if (notificationsDenied) {
            assertNotificationsUnavailable(activity);
            SshPasswordUiPlatformTest.Ui ui = new SshPasswordUiPlatformTest.Ui(instrumentation.getUiAutomation(
                    android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES));
            ui.waitText("通知未开启，无法从通知停止；请在此关闭会话。", 10000);
            ui.click("关闭会话");
            ui.waitText("选择终端会话", 10000);
        } else {
            running.set(false);
            installObservers(host, port, fingerprint);
            assertTrue("A new subscription must restore actual RUNNING state", running.get());
            assertShellRoundTrip();
            backgroundStaleStop.send();
        }
        waitReleased();
        assertFalse("Confirmed Stop must release the service-owned CPU lock", lock.isHeld());
        assertFalse("Stopping an established shell must not unlock the vault", vault.session.unlocked());
        if (notificationsDenied) installObservers(host, port, fingerprint);
        protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
        call("secrets.unlock", VaultCollections.map("requestId", id(), "password", master));
        releaseProtection();
        call("secrets.settings", VaultCollections.map("backgroundTimeoutSeconds", 30));
    }

    private void recreateTerminalActivity() throws Exception {
        Instrumentation.ActivityMonitor recreated = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(activity::recreate);
            Activity replacement = instrumentation.waitForMonitorWithTimeout(recreated, 15000);
            assertTrue("Activity recreation must produce a new UI owner", replacement instanceof MainActivity);
            activity = (MainActivity)replacement;
        } finally { instrumentation.removeMonitor(recreated); }
        ssh = (SshPasswordConnectBridge)field(activity, "sshPasswordConnect");
        awaitTerminalSubscription();
    }

    private boolean notificationPermissionDeniedRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshNotificationsDenied"));
    }

    private boolean notificationChannelDeniedRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshNotificationChannelDenied"));
    }

    private boolean notificationsUnavailableRequested() {
        return notificationPermissionDeniedRequested() || notificationChannelDeniedRequested();
    }

    private void validateNotificationDeniedOptIn(Context context) {
        if (!notificationsUnavailableRequested()) return;
        assertFalse("Notification permission-denied and channel-denied probes must run separately",
                notificationPermissionDeniedRequested() && notificationChannelDeniedRequested());
        assertTrue("Notification-denied probe requires ctosSshBackground=true", "true".equals(
                InstrumentationRegistry.getArguments().getString("ctosSshBackground")));
        assertFalse("Notification-denied and physical-lock probes must run separately", "true".equals(
                InstrumentationRegistry.getArguments().getString("ctosSshPhysicalLock")));
        if (notificationPermissionDeniedRequested())
            assertTrue("Notification runtime-permission denial requires API33+", android.os.Build.VERSION.SDK_INT >= 33);
        else assertTrue("Notification channel-denied probe requires API28+", android.os.Build.VERSION.SDK_INT >= 28);
        assertNotificationsUnavailable(context);
    }

    private void assertNotificationsUnavailable(Context context) {
        if (notificationPermissionDeniedRequested()) {
            assertEquals("POST_NOTIFICATIONS must already be denied on the isolated fixture",
                    android.content.pm.PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
        } else {
            assertTrue("A disabled notification channel requires its explicit opt-in", notificationChannelDeniedRequested());
            android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
            android.app.NotificationChannel channel = notifications.getNotificationChannel(SshSessionService.CHANNEL);
            assertNotNull("The isolated SSH notification channel must already exist", channel);
            assertEquals("The actual SSH channel must already be disabled in system Settings",
                    android.app.NotificationManager.IMPORTANCE_NONE, channel.getImportance());
        }
        assertFalse("The actual application/channel notification status must be disabled",
                SshSessionService.notificationsEnabled(context));
    }

    private boolean physicalLockRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshBackground"))
                && "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshPhysicalLock"));
    }

    private void validatePhysicalLockOptIns() {
        if (manualDeviceUnlockRequested())
            assertTrue("Manual device unlock requires background and physical-lock opt-ins", physicalLockRequested());
        if (screenOffDiagnosticsRequested())
            assertTrue("Screen-off diagnostics require background and physical-lock opt-ins", physicalLockRequested());
        assertNull("Screen-off hold probes were replaced by lock disconnection",
                InstrumentationRegistry.getArguments().getString("ctosSshScreenOffHoldSeconds"));
        assertNull("Foreground idle probes were replaced by lock disconnection",
                InstrumentationRegistry.getArguments().getString("ctosSshForegroundIdleSeconds"));
    }

    private boolean manualDeviceUnlockRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshManualDeviceUnlock"));
    }

    private void preparePhysicalLockEntry() {
        if (!physicalLockRequested()) return;
        android.app.KeyguardManager keyguard = activity.getSystemService(android.app.KeyguardManager.class);
        assertTrue("The physical lock fixture requires an existing secure device lock", keyguard.isDeviceSecure());
        if (keyguard.isDeviceLocked()) awaitExternalDeviceUnlock(keyguard, "initial");
    }

    private void assertPhysicalScreenLock(AppSecrets vault, int generation, android.os.PowerManager.WakeLock lock)
            throws Exception {
        if (screenOffDiagnosticsRequested()) {
            firstDiagnosticTerminal.set(null);
            diagnosticGeneration = generation;
        }
        android.app.KeyguardManager keyguard = activity.getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager power = activity.getSystemService(android.os.PowerManager.class);
        android.app.UiAutomation automation = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertTrue("The physical lock fixture requires an existing secure device lock", keyguard.isDeviceSecure());
        assertFalse("Physical lock must begin on an actually unlocked device", keyguard.isDeviceLocked());
        assertTrue("The synthetic vault must be unlocked before the actual screen-off event", vault.session.unlocked());
        boolean completed = false;
        try {
            injectPowerKey(automation, android.view.KeyEvent.KEYCODE_SLEEP);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while ((power.isInteractive() || !keyguard.isDeviceLocked() || !keyguard.isKeyguardLocked()
                    || vault.session.unlocked()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertFalse("Actual screen-off must be observed", power.isInteractive());
            assertTrue("The secure device must actually be locked", keyguard.isDeviceLocked() && keyguard.isKeyguardLocked());
            assertFalse("Screen-off must still lock the actual vault", vault.session.unlocked());
            waitReleased();
            assertFalse("Screen-lock cleanup must release the old service CPU lock", lock.isHeld());
            Map<String, Object> snapshot = ((SshTerminalState)staticField(SshSessionService.class, "EVENTS")).snapshot();
            assertEquals("closed", snapshot.get("state"));
            assertEquals("SSH_SCREEN_LOCKED", snapshot.get("reason"));
            assertEquals(generation, ((Number)snapshot.get("generation")).intValue());
            assertNotNull("The exact successful selection must offer a RAM-only reconnect hint", snapshot.get("reconnect"));
            try { SshSessionService.write(generation, new byte[] {'\n'}).get(2, TimeUnit.SECONDS); fail("Old input must be fenced"); }
            catch (java.util.concurrent.ExecutionException expected) { /* Exact old owner is closed. */ }
            physicalStage("screen_lock_closed", "screen_lock");
            awaitExternalDeviceUnlock(keyguard, "screen_lock");
            assertFalse("Device credential unlock must not unlock the app vault", vault.session.unlocked());
            assertFalse("Normal device unlock must not reconnect SSH", SshSessionService.active());
            assertFalse("The old service CPU lock must remain released", lock.isHeld());
            physicalStage("screen_lock_disconnect_pass", "screen_lock");
            completed = true;
        } finally {
            if (!completed) {
                try {
                    injectPowerKey(automation, android.view.KeyEvent.KEYCODE_WAKEUP);
                    if (keyguard.isDeviceLocked()) awaitExternalDeviceUnlock(keyguard, "failure_recovery");
                } catch (Exception | AssertionError recoveryFailure) {
                    physicalStage("unlock_recovery_unavailable", "failure_recovery");
                }
            }
        }
    }

    /** Optional failure notice contains only the first exact-generation public terminal metadata. */
    private boolean screenOffDiagnosticsRequested() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("ctosSshScreenOffDiagnostics"));
    }

    private void awaitExternalDeviceUnlock(android.app.KeyguardManager keyguard, String gate) {
        android.app.UiAutomation automation = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        injectPowerKey(automation, android.view.KeyEvent.KEYCODE_WAKEUP);
        AtomicBoolean dismissedError = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> keyguard.requestDismissKeyguard(activity,
                new android.app.KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissError() { dismissedError.set(true); }
                    @Override public void onDismissCancelled() { dismissedError.set(true); }
                }));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        if (manualDeviceUnlockRequested()) {
            android.os.PowerManager power = activity.getSystemService(android.os.PowerManager.class);
            assertTrue("Manual unlock requires an existing secure device lock", keyguard.isDeviceSecure());
            while ((!power.isInteractive() || keyguard.isDeviceLocked() && !keyguard.isKeyguardLocked())
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            assertTrue("Manual unlock requires an actually interactive device", power.isInteractive());
            if (keyguard.isDeviceLocked()) {
                assertTrue("Manual unlock must use the actual public keyguard", keyguard.isKeyguardLocked());
                physicalStage("await_manual_device_unlock", gate);
            }
        } else {
            while (!systemCredentialEntryVisible(automation) && SystemClock.elapsedRealtime() < deadline
                    && keyguard.isDeviceLocked() && !dismissedError.get()) SystemClock.sleep(100);
            assertTrue("A real SystemUI credential entry could not be observed; no bypass is permitted",
                    keyguard.isDeviceLocked() && systemCredentialEntryVisible(automation));
            physicalStage("await_device_unlock", gate);
        }
        deadline = SystemClock.elapsedRealtime() + 120000;
        while (keyguard.isDeviceLocked() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertFalse("External device authentication did not complete within 120 seconds", keyguard.isDeviceLocked());
        deadline = SystemClock.elapsedRealtime() + 10000;
        AppSecrets vault = AppSecretsOwner.acquire(activity);
        while (!vault.session.foreground() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue("The actual Activity must return to the foreground after device unlock", vault.session.foreground());
        physicalStage("device_unlocked", gate);
    }

    private static void injectPowerKey(android.app.UiAutomation automation, int keyCode) {
        long now = SystemClock.uptimeMillis();
        boolean down = automation.injectInputEvent(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0), true);
        boolean up = automation.injectInputEvent(new android.view.KeyEvent(now, SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_UP, keyCode, 0), true);
        assertTrue("The real power-key pair must be accepted by UiAutomation", down && up);
    }

    private static boolean systemCredentialEntryVisible(android.app.UiAutomation automation) {
        android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        nodes.add(root);
        try {
            if (!"com.android.systemui".contentEquals(String.valueOf(root.getPackageName()))) return false;
            for (int index = 0; index < nodes.size() && index < 512; index++) {
                android.view.accessibility.AccessibilityNodeInfo node = nodes.get(index);
                int inputClass = node.getInputType() & android.text.InputType.TYPE_MASK_CLASS;
                int variation = node.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
                boolean passwordInput = inputClass == android.text.InputType.TYPE_CLASS_NUMBER
                        && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        || inputClass == android.text.InputType.TYPE_CLASS_TEXT
                        && (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
                if (node.isVisibleToUser() && "com.android.systemui".contentEquals(String.valueOf(node.getPackageName()))
                        && (node.isPassword() || node.isEditable() && passwordInput)) return true;
                for (int child = 0; child < node.getChildCount() && nodes.size() < 512; child++) {
                    android.view.accessibility.AccessibilityNodeInfo next = node.getChild(child);
                    if (next != null) nodes.add(next);
                }
            }
            return false;
        } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) node.recycle(); }
    }

    private void physicalStage(String stage, String gate) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("ctos_stage", stage);
        status.putString("ctos_unlock_gate", gate);
        instrumentation.sendStatus(0, status);
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value.get(null);
    }

    private void assertRecreatedFlutterTerminal(String retainedMarker, android.app.PendingIntent notificationReturn)
            throws Exception {
        android.app.UiAutomation automation = instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo accessibility = automation.getServiceInfo();
        accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(accessibility);
        SshPasswordUiPlatformTest.Ui ui = new SshPasswordUiPlatformTest.Ui(automation);
        logRecreatedSnapshot(retainedMarker);
        try {
            android.util.Log.i("CtosSshBackgroundFixture", "phase=recreated_flutter_terminal");
            ui.waitText("终端", 15000); ui.click("终端");
            ui.waitText("关闭会话", 10000); ui.waitField("命令输入", 10000);
            android.util.Log.i("CtosSshBackgroundFixture", "phase=retained_rendered_output");
            ui.assertRenderedOutput(retainedMarker);

            android.util.Log.i("CtosSshBackgroundFixture", "phase=recreated_actual_input");
            String first = UUID.randomUUID().toString().substring(0, 8);
            String second = UUID.randomUUID().toString().substring(0, 8);
            String marker = first + second;
            String command = "printf '%s%s\\n' '" + first + "' '" + second + "'";
            assertFalse("A plain command echo must not satisfy the output assertion", command.contains(marker));
            ui.setText("命令输入", command, false); ui.click("执行命令");
            ui.assertRenderedOutput(marker);

            android.util.Log.i("CtosSshBackgroundFixture", notificationReturn == null
                    ? "phase=recreated_denied_notification_navigation" : "phase=recreated_notification_navigation");
            revealOverviewNavigation(ui);
            ui.click("概览");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (ui.has("关闭会话") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertFalse("Overview navigation must leave the actual terminal controls", ui.has("关闭会话"));
            if (notificationReturn == null) {
                assertTrue("Only an explicit denied-notification probe may omit the notification return",
                        notificationsUnavailableRequested());
                assertNotificationsUnavailable(activity);
                ui.click("终端");
                ui.waitText("通知未开启，无法从通知停止；请在此关闭会话。", 10000);
            } else notificationReturn.send();
            ui.waitText("关闭会话", 10000); ui.waitField("命令输入", 10000);
            android.util.Log.i("CtosSshBackgroundFixture", "phase=recreated_flutter_pass");
        } catch (AssertionError failure) {
            diagnoseRecreatedUi(ui, automation);
            throw failure;
        }
    }

    private void revealOverviewNavigation(SshPasswordUiPlatformTest.Ui ui) {
        assertTrue("Keyboard dismissal must start on the actual terminal route", ui.has("关闭会话"));
        assertFalse("Keyboard dismissal must not start on the output snapshot route", ui.has("当前输出"));
        if (fixtureImeVisible()) {
            navigationStage("navigation_ime_visible_before_back");
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (fixtureImeVisible() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertFalse("Normal Back must hide the actual terminal IME before navigation", fixtureImeVisible());
            navigationStage("navigation_ime_hidden_after_back");
        } else navigationStage("navigation_ime_already_hidden");
        ui.waitText("概览", 10000);
        assertTrue("Keyboard dismissal must retain the terminal route", ui.has("关闭会话"));
        navigationStage("navigation_overview_visible");
    }

    private boolean fixtureImeVisible() {
        AtomicBoolean visible = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> {
            assertFalse("Keyboard observation requires the current fixture Activity", activity.isDestroyed());
            android.view.View decor = activity.getWindow().getDecorView();
            assertTrue("Keyboard observation requires the attached fixture window", decor.isAttachedToWindow());
            assertTrue("Keyboard observation requires the focused fixture window", activity.hasWindowFocus());
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            assertNotNull("Actual window insets are required before keyboard dismissal", insets);
            if (android.os.Build.VERSION.SDK_INT >= 30) visible.set(insets.isVisible(android.view.WindowInsets.Type.ime()));
            else visible.set(insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom());
        });
        return visible.get();
    }

    private void navigationStage(String stage) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("ctos_stage", stage);
        status.putString("stream", "ctos_navigation_stage=" + stage + "\n");
        instrumentation.sendStatus(0, status);
    }

    private void logRecreatedSnapshot(String retainedMarker) throws Exception {
        SshTerminalState events = (SshTerminalState)staticField(SshSessionService.class, "EVENTS");
        Map<String, Object> snapshot = events.snapshot();
        byte[] bytes = snapshot == null ? null : (byte[])snapshot.get("bytes");
        byte[] marker = retainedMarker.getBytes(StandardCharsets.UTF_8);
        try {
            boolean present = false;
            if (bytes != null) for (int offset = 0; offset <= bytes.length - marker.length && !present; offset++) {
                boolean match = true;
                for (int index = 0; index < marker.length; index++)
                    if (bytes[offset + index] != marker[index]) { match = false; break; }
                present = match;
            }
            Object rawState = snapshot == null ? null : snapshot.get("state");
            String state = "running".equals(rawState) || "closed".equals(rawState) || "failed".equals(rawState)
                    ? (String)rawState : "none";
            Number generation = snapshot != null && snapshot.get("generation") instanceof Number
                    ? (Number)snapshot.get("generation") : 0;
            android.util.Log.i("CtosSshBackgroundFixture", "snapshot state=" + state + " generation="
                    + generation.longValue() + " bytesLen=" + (bytes == null ? 0 : bytes.length)
                    + " retainedMarkerPresent=" + present);
        } finally { VaultCodec.wipe(bytes); VaultCodec.wipe(marker); }
    }

    private void diagnoseRecreatedUi(SshPasswordUiPlatformTest.Ui ui, android.app.UiAutomation automation) {
        try {
            android.util.Log.i("CtosSshBackgroundFixture", "visible picker=" + ui.has("选择终端会话")
                    + " appShell=" + ui.has("应用 Shell") + " sshEntry=" + ui.has("SSH 连接")
                    + " ssh=" + ui.has("ssh") + " output=" + ui.has("选择输出")
                    + " switch=" + ui.has("换用") + " close=" + ui.has("关闭会话"));
            if (!fixtureUiActive(automation)) return;
            android.graphics.Bitmap screenshot = automation.takeScreenshot();
            if (screenshot == null) return;
            try {
                if (!fixtureUiActive(automation)) return;
                File directory = instrumentation.getContext().getExternalFilesDir(null);
                if (directory == null) return;
                File target = new File(directory, "ctos-background-ui.png");
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                    assertTrue("The owned fixture screenshot must encode as PNG",
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                }
                android.util.Log.i("CtosSshBackgroundFixture", "screenshot=ctos-background-ui.png");
            } finally { screenshot.recycle(); }
        } catch (Exception | AssertionError diagnosticFailure) {
            android.util.Log.i("CtosSshBackgroundFixture", "diagnostics=unavailable");
        }
    }

    private static boolean fixtureUiActive(android.app.UiAutomation automation) {
        android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        try { return "im.majo.ctos.secretsfixture".contentEquals(String.valueOf(root.getPackageName())); }
        finally { root.recycle(); }
    }

    private String assertShellRoundTrip() throws Exception {
        String first = UUID.randomUUID().toString().substring(0, 8), second = UUID.randomUUID().toString().substring(0, 8);
        synchronized (output) { output.setLength(0); }
        call("terminalWrite", VaultCollections.map("generation", SshSessionService.generation(), "text", "printf '%s%s\\n' '" + first + "' '" + second + "'\n"));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized (output) { if (output.indexOf(first + second) >= 0) return first + second; }
            SystemClock.sleep(20);
        }
        throw new AssertionError("The independently authenticated shell did not return the synthetic marker");
    }
    private void releaseProtection() throws Exception {
        if (protection == null) return;
        call("secrets.protect", VaultCollections.map("action", "release", "owner", protection)); protection = null;
    }
    private void stopTerminal() throws Exception {
        int generation = SshSessionService.generation();
        call("terminalStop", generation == 0 ? java.util.Collections.emptyMap()
                : VaultCollections.map("generation", generation));
    }
    private void waitReleased() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!SshSessionService.active()) return;
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
