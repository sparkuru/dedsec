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
        try {
            assertNotEquals("Fixture must use an isolated UID", context.getApplicationInfo().uid,
                    context.getPackageManager().getApplicationInfo("im.majo.ctos", 0).uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException absentFormalApp) { /* Isolated fixture only. */ }
        File vaultFile = new File(context.getNoBackupFilesDir(), "app-secrets.bin");
        assertFalse("Fresh isolated fixture app data is required", vaultFile.exists());
        assertFalse("A previous backup requires explicit reconciliation", new File(vaultFile.getPath() + ".bak").exists());
        assertFalse("A previous pending write requires explicit reconciliation", new File(vaultFile.getPath() + ".new").exists());
        JSONObject request = readKeyRequest(path);
        byte[] privateKey = null;
        boolean created = false, observed = false;
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
            dispatch = MainActivity.class.getDeclaredMethod("call", MethodCall.class, MethodChannel.Result.class);
            dispatch.setAccessible(true);
            ssh = (SshPasswordConnectBridge)field(activity, "sshPasswordConnect");
            AppSecretsBridge secrets = (AppSecretsBridge)field(activity, "appSecrets");
            assertSame(AppSecretsOwner.acquire(activity), secrets.sshVault());
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
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
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            // Manual Account/config and trust writes change the selection binding: reload it.
            library = map(call("ssh.library", VaultCollections.map("requestId", id())));
            Map<String, Object> selected = checkedHost(library, keyId, host, port, username);
            assertPersistedKey(secrets.sshVault(), privateKey, material, keyId, host, port, username, true);
            connectKey(request, keyId, selected);
            assertEquals("The matching persisted host must use native KNOWN without new approval", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
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
        } finally {
            request.remove("privateKey"); VaultCodec.wipe(privateKey);
            try { SshImportFixtureProvider.clear(); }
            finally {
                if (activity != null) {
                    try {
                        if (activeRequest != null) call("ssh.cancel", VaultCollections.map("requestId", activeRequest));
                        if (pendingImportTicket != null) call("ssh.importCancel", VaultCollections.map("ticketId", pendingImportTicket));
                        call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
                        releaseProtection(); call("secrets.lock", java.util.Collections.emptyMap());
                    } finally {
                        final boolean restore = observed;
                        instrumentation.runOnMainSync(() -> {
                            try {
                                if (restore) { setField(ssh, "sink", oldSshSink); setField(activity, "terminalSink", oldTerminalSink); }
                            } catch (Exception failure) { throw new AssertionError("Fixture observation cleanup failed"); }
                            activity.finish();
                        });
                    }
                }
                synchronized (output) { output.setLength(0); }
                if (created) for (String suffix : new String[] {"", ".bak", ".new"}) {
                    File owned = new File(vaultFile.getPath() + suffix);
                    assertTrue("Own fresh fixture vault cleanup failed", !owned.exists() || owned.delete());
                }
            }
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
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
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
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
            protection = (String)map(call("secrets.protect", VaultCollections.map("action", "acquire"))).get("owner");
            Map<String, Object> saved = map(call("ssh.defaults", VaultCollections.map("requestId", id())));
            assertEquals(host, saved.get("host")); assertEquals(port, ((Number)saved.get("port")).intValue());
            assertEquals(username, saved.get("username"));
            assertEquals(false, saved.get("savedPassword"));
            connect(request, false, true);
            String priorConnection = connectedRequest;
            assertEquals("An already saved matching host must not request approval again", 1, reviews.get());
            releaseProtection(); assertShellRoundTrip();
            call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
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
                    call("terminalStop", java.util.Collections.emptyMap()); waitReleased();
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
        oldSshSink = field(ssh, "sink"); oldTerminalSink = field(activity, "terminalSink");
        instrumentation.runOnMainSync(() -> {
            try {
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
                        if ("state".equals(event.get("type")) && "running".equals(event.get("state"))) running.set(true);
                        if ("data".equals(event.get("type"))) synchronized (output) {
                            output.append(new String((byte[])event.get("bytes"), StandardCharsets.UTF_8));
                            if (output.length() > 65536) output.delete(0, output.length() - 65536);
                        }
                    }
                    @Override public void error(String code, String message, Object details) { throw new AssertionError(code); }
                    @Override public void endOfStream() { }
                });
            } catch (Exception failure) { throw new AssertionError("Fixture observations could not be installed"); }
        });
    }

    private void assertShellRoundTrip() throws Exception {
        String first = UUID.randomUUID().toString().substring(0, 8), second = UUID.randomUUID().toString().substring(0, 8);
        synchronized (output) { output.setLength(0); }
        call("terminalWrite", VaultCollections.map("text", "printf '%s%s\\n' '" + first + "' '" + second + "'\n"));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized (output) { if (output.indexOf(first + second) >= 0) return; }
            SystemClock.sleep(20);
        }
        fail("The independently authenticated shell did not return the synthetic marker");
    }
    private void releaseProtection() throws Exception {
        if (protection == null) return;
        call("secrets.protect", VaultCollections.map("action", "release", "owner", protection)); protection = null;
    }
    private void waitReleased() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (field(activity, "sshFactory") == null && field(activity, "sshCancellation") == null) return;
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
