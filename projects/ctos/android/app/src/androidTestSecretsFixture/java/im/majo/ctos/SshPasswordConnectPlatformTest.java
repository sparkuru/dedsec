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
 */
public final class SshPasswordConnectPlatformTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    private Method dispatch;
    private String activeRequest, connectedRequest, protection;
    private String pendingImportTicket;
    private Object oldSshSink, oldTerminalSink;
    private SshPasswordConnectBridge ssh;
    private final AtomicBoolean running = new AtomicBoolean();
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
        String path = InstrumentationRegistry.getArguments().getString("ctosSshKeyRequestFile");
        Assume.assumeTrue("Explicit independent key-only TCP fixture request was not provided", path != null);
        assertTrue("Invalid key fixture request path", path.matches("/data/local/tmp/ctos-ssh-key-[A-Za-z0-9_-]{1,64}\\.json"));
        Context context = instrumentation.getTargetContext();
        assertEquals("im.majo.ctos.secretsfixture", context.getPackageName());
        assertTrue(context.getApplicationContext() instanceof SecretsFixtureApplication);
        assertFalse(context.getSharedPreferences("root_access", Context.MODE_PRIVATE).getBoolean("auto_start", true));
        validateNotificationDeniedOptIn(context);
        validatePhysicalLockOptIns();
        try {
            assertNotEquals("Fixture must use an isolated UID", context.getApplicationInfo().uid,
                    context.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException absentFormalApp) { /* Isolated fixture only. */ }
        File vaultFile = new File(context.getNoBackupFilesDir(), "app-secrets.bin");
        resetOwnedKeyFixtureVault(vaultFile);
        assertFalse("Fresh isolated fixture app data is required", vaultFile.exists());
        assertFalse("A previous backup requires explicit reconciliation", new File(vaultFile.getPath() + ".bak").exists());
        assertFalse("A previous pending write requires explicit reconciliation", new File(vaultFile.getPath() + ".new").exists());
        JSONObject request = readKeyRequest(path);
        byte[] privateKey = null;
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
            SshKeyMaterial.Result material = SshKeyMaterial.inspect(privateKey, SystemClock.elapsedRealtime() + 60_000);
            assertEquals(SshKeyMaterial.Status.PARSED, material.status);
            assertTrue("Actual native public fingerprint is required", material.fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"));
            activity = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            preparePhysicalLockEntry();
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
            Map<String, Object> keyPreview = pickImport("privateKey", ".ssh/id_fixture", "id_fixture", privateKey);
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
            String keyId = (String)key.get("id");
            checkedHost(library, keyId, host, port, username);
            assertPersistedKey(secrets.sshVault(), privateKey, material, keyId, host, port, username, false);

            connectKey(request, keyId, null);
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
            connectKey(request, keyId, selected);
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
                keyFixtureCleanup(failure, this::stopTerminal);
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
                "replacing", "issues", "canCommit"), preview.keySet());
        assertEquals(kind, preview.get("kind")); assertEquals(path, preview.get("path")); assertEquals(name, preview.get("label"));
        assertEquals(replacing, preview.get("replacing")); assertEquals(true, preview.get("canCommit"));
        assertTrue("Import must have a real bound candidate", preview.get("binding") instanceof String
                && ((String)preview.get("binding")).matches("[0-9a-f]{64}"));
        assertFalse(((String)preview.get("ticketId")).isEmpty()); assertTrue(((List<?>)preview.get("issues")).isEmpty());
    }

    private void commitImport(Map<String, Object> preview) throws Exception {
        Map<String, Object> reply = map(call("ssh.importCommit", VaultCollections.map("requestId", id(),
                "ticketId", preview.get("ticketId"), "binding", preview.get("binding"), "replace", preview.get("replacing"))));
        assertEquals(java.util.Set.of("committed"), reply.keySet()); assertEquals(true, reply.get("committed"));
        pendingImportTicket = null;
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
        running.set(false); activeRequest = id();
        Map<String, Object> arguments = configured == null
                ? VaultCollections.map("requestId", activeRequest, "host", request.getString("host"), "port", request.getInt("port"),
                        "username", request.getString("username"), "keyId", keyId, "columns", 80, "rows", 24)
                : VaultCollections.map("requestId", activeRequest, "alias", configured.get("alias"), "binding", configured.get("binding"),
                        "username", "", "keyId", keyId, "columns", 80, "rows", 24);
        Map<String, Object> reply = map(call(configured == null ? "ssh.keyConnect" : "ssh.configConnect", arguments));
        assertEquals(activeRequest, reply.get("requestId")); assertEquals("connected", reply.get("status"));
        assertTrue("Actual authenticated PTY must be running before completion", running.get());
        connectedRequest = activeRequest; activeRequest = null;
    }

    private void assertPersistedKey(AppSecrets vault, byte[] privateKey, SshKeyMaterial.Result material,
            String keyId, String host, int port, String username, boolean connected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot source = SshPasswordManifestValidator.within(deadline, () -> vault.sshLibrarySnapshot(context))) {
            assertEquals(1, source.manifest.keyBindings.size());
            VaultSshMetadata.KeyBinding binding = source.manifest.keyBindings.get(0); assertEquals(keyId, binding.id);
            assertNull(binding.passphraseEntryId); assertTrue(binding.certificateFileIds.isEmpty());
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
            assertEquals("Only the exact key fixture fields are accepted", 5, request.length()); return request;
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
                                && "running".equals(event.get("state"))) running.set(true);
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
