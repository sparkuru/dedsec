package im.majo.ctos;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Protected metadata/import operations; raw private files stay in bounded owned host buffers. */
final class SshProfilesBridge implements AutoCloseable {
    interface Admission { void admit(String id) throws VaultException; }
    private final Activity activity;
    private final AppSecretsBridge secrets;
    private final AppSecrets vault;
    private final BooleanSupplier connecting;
    private final Consumer<Map<String, Object>> emit;
    private final Admission admission;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Request> requests = new HashMap<>();
    private final Set<Integer> pickerCodes = new HashSet<>();
    private volatile boolean closed;
    private Request input, reading, editing, committing;
    private Ticket ticket;
    private boolean cleanupQueued;

    private final class Request {
        final String id;
        final long epoch, revision, deadline;
        final MethodChannel.Result result;
        final AppSecrets.BundleContext context;
        volatile boolean cancelled, delivered, handedOff, finished;
        boolean pickerResultReceived;
        int picker;
        String kind, path;
        Request originalEdit;
        Request(String id, MethodChannel.Result result, long deadline) throws VaultException {
            this.id = id; this.result = result; this.deadline = deadline;
            epoch = vault.session.epoch(); revision = vault.contentRevision;
            context = vault.newBundleContext(deadline, this::check);
        }
        void check() throws VaultException {
            if (closed || cancelled) throw failure("SSH_CANCELLED");
            if (SystemClock.elapsedRealtime() >= deadline) throw failure("SSH_TIMED_OUT");
            secrets.requireSshConsumer(); vault.session.assertEpoch(epoch);
            if (vault.contentRevision != revision) throw failure("VAULT_CHANGED");
            if (context != null) vault.requirePasswordValidationOrigin(context);
            if (originalEdit != null) originalEdit.check();
        }
        void cancel() {
            cancelled = true;
            vault.cancelBundleContext(context);
            if (originalEdit != null) originalEdit.cancel();
        }
    }

    private static final class Ticket {
        final String id = "import-" + UUID.randomUUID();
        final Object owner;
        final String kind, path, label, algorithm, fingerprint, binding;
        final boolean replacing;
        final SshImportProposal imported;
        final List<Map<String, Object>> issues;
        volatile boolean cancelled;
        Ticket(Object owner, String kind, String path, SshImportProposal imported,
                List<Map<String, Object>> issues) {
            this.owner = owner; this.kind = kind; this.path = path; this.imported = imported;
            label = imported == null ? SshImportProposal.basename(path) : imported.label;
            algorithm = imported == null ? "" : imported.algorithm;
            fingerprint = imported == null ? "" : imported.fingerprint;
            replacing = imported != null && imported.replacing;
            binding = imported == null ? SshConnectionMaterial.hex(VaultCodec.digest(
                    ("blocked-import\0" + id).getBytes(StandardCharsets.UTF_8))) : imported.proposal.binding;
            this.issues = issues;
        }
        Map<String, Object> metadata() {
            return VaultCollections.map("ticketId", id, "binding", binding, "kind", kind, "path", path,
                    "label", label, "algorithm", algorithm, "fingerprint", fingerprint,
                    "replacing", replacing, "issues", issues, "canCommit", imported != null);
        }
        void close() { if (imported != null) imported.close(); }
    }

    SshProfilesBridge(Activity activity, AppSecretsBridge secrets, BooleanSupplier connecting,
            Consumer<Map<String, Object>> emit, Admission admission) {
        this.activity = activity; this.secrets = secrets; vault = secrets.sshVault();
        this.connecting = connecting; this.emit = emit; this.admission = admission;
    }

    boolean busy() { return input != null || committing != null || ticket != null; }

    boolean handle(MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "ssh.library": case "ssh.importFile": case "ssh.importCommit":
            case "ssh.importCancel": case "ssh.configRead": case "ssh.configPreview": break;
            default: return false;
        }
        try {
            if (closed) throw failure("SSH_CANCELLED");
            switch (call.method) {
                case "ssh.library": read(call, result, false); break;
                case "ssh.configRead": read(call, result, true); break;
                case "ssh.importFile": pick(call, result); break;
                case "ssh.configPreview": editPreview(call, result); break;
                case "ssh.importCommit": commit(call, result); break;
                case "ssh.importCancel": {
                    exact(call, "ticketId"); String id = identifier(call, "ticketId");
                    Ticket current = ticket;
                    boolean cancelled = current != null && current.id.equals(id);
                    if (cancelled) cancelTicket(current);
                    result.success(VaultCollections.map("cancelled", cancelled)); break;
                }
                default: throw failure("PARAMETERS");
            }
        } catch (VaultException failure) { error(result, failure.code); }
        catch (RuntimeException | LinkageError failure) { error(result, "SSH_IMPORT_FAILED"); }
        return true;
    }

    private Request request(MethodCall call, MethodChannel.Result result) throws VaultException {
        String id = identifier(call, "requestId");
        admission.admit(id); secrets.requireSshConsumer();
        Request request = new Request(id, result, SystemClock.elapsedRealtime() + 120_000);
        requests.put(id, request);
        return request;
    }

    private void read(MethodCall call, MethodChannel.Result result, boolean config) throws VaultException {
        if (config) exact(call, "requestId", "path"); else exact(call, "requestId");
        if (connecting.getAsBoolean() || committing != null) throw failure("BUSY");
        String path = config ? managedPath(text(call, "path")) : null;
        if (config && (path.equals(SshConnectionMaterial.HOSTS) || path.equals(SshConnectionMaterial.MANUAL)))
            throw failure("SSH_CONFIG_UNSUPPORTED");
        Request request = request(call, result); request.path = path;
        Request old = config ? editing : reading;
        if (old != null && !old.handedOff) old.cancel();
        if (config) editing = request; else reading = request;
        worker.execute(() -> {
            Object metadata = null; String code = null;
            try {
                request.check();
                metadata = SshPasswordManifestValidator.within(request.deadline, () -> {
                    try (SshConnectionMaterial.Snapshot source = vault.sshLibrarySnapshot(request.context)) {
                        if (!config) return library(source, request);
                        VaultManifest.FileObject file = SshConnectionMaterial.file(source.manifest, path);
                        if (file != null && file.type != VaultManifest.FileType.CONFIG) throw failure("SSH_CONFIG_UNSUPPORTED");
                        return VaultCollections.map("path", path, "content", file == null ? "" : configText(file.content));
                    }
                });
            } catch (Throwable failure) { code = code(failure); }
            Object reply = metadata; String failed = code;
            main.post(() -> {
                if (request.finished) return;
                try {
                    request.check();
                    if (failed != null) throw failure(failed);
                    request.finished = request.delivered = true;
                    result.success(reply);
                    main.postDelayed(() -> monitor(request), 100);
                } catch (VaultException failure) { finish(request, null, failure.code); }
            });
            cleanup();
        });
    }

    private Map<String, Object> library(SshConnectionMaterial.Snapshot source, Request request) throws Exception {
        List<Map<String, Object>> keys = new ArrayList<>(), hosts = new ArrayList<>();
        List<SshConnectionMaterial.Key> material = new ArrayList<>();
        for (VaultSshMetadata.KeyBinding binding : source.manifest.keyBindings) {
            request.check();
            SshConnectionMaterial.Key key = new SshConnectionMaterial.Key(source.manifest, binding);
            material.add(key);
            keys.add(VaultCollections.map("id", key.id, "label", SshImportProposal.basename(key.path),
                    "path", key.path, "algorithm", key.algorithm, "fingerprint", key.fingerprint));
        }
        boolean configured = SshConnectionMaterial.file(source.manifest, SshConnectionMaterial.ROOT) != null;
        if (configured) {
            long deadline = Math.min(request.deadline, SystemClock.elapsedRealtime() + 60_000);
            SshConnectionMaterial.CatalogScan scan = SshConnectionMaterial.catalog(source.manifest,
                    SshConnectionMaterial.ROOT, deadline, request::check);
            int count = Math.max(1, scan.catalog.entries.size());
            SshConnectionMaterial.Limits share = new SshConnectionMaterial.Limits(
                    (SshConfigEvaluator.MAX_OPENS - scan.usage.opens) / count,
                    (SshConfigEvaluator.MAX_READ_BYTES - scan.usage.readBytes) / count,
                    (SshConfigEvaluator.MAX_LINES - scan.usage.lines) / count);
            for (SshConfigEvaluator.Entry alias : scan.catalog.entries) {
                request.check();
                try {
                    SshConfigEvaluator.DirectPlan plan = SshConnectionMaterial.plan(source.manifest,
                            SshConnectionMaterial.ROOT, alias.alias, "", deadline, request::check, share);
                    List<String> eligible = new ArrayList<>();
                    if (plan.pubkeyAuthentication)
                        for (String path : plan.identityFiles)
                            for (SshConnectionMaterial.Key key : material)
                                if (key.path.equals(path) && !eligible.contains(key.id)) eligible.add(key.id);
                    Map<String, Object> row = VaultCollections.map("alias", alias.alias, "host", plan.hostname,
                            "port", plan.port, "username", plan.user, "keyIds", eligible,
                            "binding", SshConnectionMaterial.configBinding(source, alias.alias));
                    if (eligible.isEmpty()) row.put("error", "SSH_KEY_MISSING");
                    hosts.add(row);
                } catch (IOException | VaultException failure) {
                    hosts.add(VaultCollections.map("alias", alias.alias, "host", "", "port", 22,
                            "username", "", "keyIds", List.of(), "error", configCode(failure),
                            "binding", SshConnectionMaterial.configBinding(source, alias.alias)));
                }
            }
        }
        return VaultCollections.map("keys", keys, "hosts", hosts, "hasConfig", configured);
    }

    private void pick(MethodCall call, MethodChannel.Result result) throws VaultException {
        exact(call, "requestId", "kind", "path");
        String kind = text(call, "kind"), path = text(call, "path");
        if (!kind.equals("privateKey") && !kind.equals("config")) throw failure("PARAMETERS");
        if (!path.isEmpty()) managedPath(path);
        if (connecting.getAsBoolean() || busy()) throw failure("BUSY");
        Request request = request(call, result); request.kind = kind; request.path = path;
        request.picker = VaultUiRequests.allocate(); pickerCodes.add(request.picker); input = request;
        try {
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), request.picker);
            main.postDelayed(() -> expire(request), 120_000);
        } catch (RuntimeException failure) { input = null; finish(request, null, "SSH_IMPORT_UNAVAILABLE"); }
    }

    boolean onActivityResult(int code, int resultCode, Intent data) {
        if (!pickerCodes.contains(code)) return false;
        Request request = input;
        if (request == null || request.picker != code || request.cancelled || request.pickerResultReceived) return true;
        request.pickerResultReceived = true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            input = null; finish(request, null, "SSH_CANCELLED"); return true;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) {
            input = null; request.cancel(); finish(request, null, "SSH_IMPORT_UNAVAILABLE"); return true;
        }
        awaitForeground(request, uri, SystemClock.elapsedRealtime() + 3000);
        return true;
    }

    private void awaitForeground(Request request, Uri uri, long waitDeadline) {
        try { request.check(); }
        catch (VaultException failure) {
            if (failure.code.equals("VAULT_FOREGROUND_REQUIRED") && SystemClock.elapsedRealtime() < waitDeadline
                    && !closed && !request.cancelled) {
                main.postDelayed(() -> awaitForeground(request, uri, waitDeadline), 50); return;
            }
            if (input == request) input = null;
            finish(request, null, failure.code); return;
        }
        worker.execute(() -> {
            byte[] body = null;
            try {
                request.check();
                String name = displayName(uri);
                if (request.path.isEmpty()) request.path = SshImportProposal.defaultPath(request.kind, name);
                try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException("SSH input unavailable");
                    body = readBounded(stream, request);
                }
                prepare(request, body);
            } catch (Throwable failure) { publishBlocked(request, failure); }
            finally { VaultCodec.wipe(body); cleanup(); }
        });
    }

    private void editPreview(MethodCall call, MethodChannel.Result result) throws VaultException {
        exact(call, "requestId", "path", "content");
        String path = managedPath(text(call, "path"));
        byte[] body = configBytes(text(call, "content"));
        try {
            if (connecting.getAsBoolean() || busy()) throw failure("BUSY");
            Request original = editing;
            if (original == null || !path.equals(original.path) || !original.delivered || original.handedOff)
                throw failure("VAULT_CHANGED");
            original.check(); admission.admit(identifier(call, "requestId"));
            Request request = new Request(identifier(call, "requestId"), result, original.deadline);
            request.kind = "config"; request.path = path;
            byte[] owned = body.clone();
            SshPreviewHandoff.submit(
                    () -> vault.inheritSshEditContext(original.context, request.context),
                    () -> {
                        request.originalEdit = original;
                        requests.put(request.id, request); input = request; original.handedOff = true;
                    },
                    () -> worker.execute(() -> {
                        try { original.check(); prepare(request, owned); }
                        catch (Throwable failure) { publishBlocked(request, failure); }
                        finally { VaultCodec.wipe(owned); cleanup(); }
                    }),
                    () -> {
                        request.cancel(); request.finished = true;
                        if (input == request) input = null;
                        if (editing == original && original.cancelled) editing = null;
                        VaultCodec.wipe(owned);
                    });
        } finally { VaultCodec.wipe(body); }
    }

    private void prepare(Request request, byte[] body) throws Exception {
        request.check();
        if (request.originalEdit != null) request.originalEdit.check();
        SshImportProposal proposal = null;
        try {
            proposal = SshPasswordManifestValidator.within(request.deadline, () -> {
                try (SshConnectionMaterial.Snapshot source = vault.sshLibrarySnapshot(request.context)) {
                    if (request.originalEdit != null) {
                        Request original = request.originalEdit;
                        if (source.epoch != original.epoch || source.revision != original.revision)
                            throw failure("VAULT_CHANGED");
                        original.check();
                    }
                    SshImportProposal imported = SshImportProposal.create(source.manifest, request.kind,
                            request.path, body, Math.min(request.deadline, SystemClock.elapsedRealtime() + 60_000));
                    try (VaultManifest candidate = imported.proposal.applyTo(source.manifest)) {
                        vault.validateSshCandidate(request.context, candidate);
                    } catch (Throwable failure) { imported.close(); throw failure; }
                    return imported;
                }
            });
            request.check();
            List<Map<String, Object>> issues = new ArrayList<>();
            if (proposal.replacing && proposal.kind.equals("privateKey"))
                issues.add(issue("SSH_KEY_REPLACED", request.path, 0));
            Ticket prepared = new Ticket(request, request.kind, request.path, proposal, issues);
            proposal = null;
            main.post(() -> publish(request, prepared));
        } finally { if (proposal != null) proposal.close(); }
    }

    private void publishBlocked(Request request, Throwable failure) {
        String code = code(failure);
        if (code.startsWith("VAULT_") || code.equals("SSH_CANCELLED") || code.equals("SSH_TIMED_OUT")) {
            main.post(() -> { if (input == request) input = null; finish(request, null, code); }); return;
        }
        String path = request.path;
        if (path == null || path.isEmpty()) path = request.kind.equals("config") ? SshConnectionMaterial.ROOT : ".ssh/id_imported";
        int line = 0;
        if (failure instanceof SshConfigEvaluator.EvaluationException)
            line = Math.min(16384, ((SshConfigEvaluator.EvaluationException)failure).diagnostic.line);
        Ticket blocked = new Ticket(request, request.kind, path, null, List.of(issue(configCode(failure), path, line)));
        main.post(() -> publish(request, blocked));
    }

    private void publish(Request request, Ticket prepared) {
        try {
            request.check();
            if (input != request || ticket != null || request.finished) throw failure("SSH_CANCELLED");
            request.finished = request.delivered = true; request.handedOff = true;
            input = null; ticket = prepared;
            request.result.success(prepared.metadata());
            main.postDelayed(() -> expireTicket(prepared), Math.max(1, request.deadline - SystemClock.elapsedRealtime()));
        } catch (VaultException failure) {
            prepared.cancelled = true; worker.execute(prepared::close);
            if (input == request) input = null;
            finish(request, null, failure.code);
        }
    }

    private void commit(MethodCall call, MethodChannel.Result result) throws VaultException {
        exact(call, "requestId", "ticketId", "binding", "replace");
        String id = identifier(call, "requestId"), ticketId = identifier(call, "ticketId"), binding = text(call, "binding");
        Object replace = call.argument("replace");
        Ticket selected = ticket;
        if (selected == null || selected.cancelled || !selected.id.equals(ticketId)
                || !selected.binding.equals(binding) || !(replace instanceof Boolean)
                || (Boolean)replace != selected.replacing || selected.imported == null) throw failure("VAULT_CHANGED");
        if (connecting.getAsBoolean() || committing != null) throw failure("BUSY");
        Request origin = (Request)selected.owner; origin.check(); admission.admit(id);
        Request request = new Request(id, result, origin.deadline);
        requests.put(id, request); committing = request;
        worker.execute(() -> {
            boolean written = false; String failed = null;
            VaultOperation operation = null;
            java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
            AppSecrets.EffectGuard effect = new AppSecrets.EffectGuard() {
                @Override public void check() throws VaultException {
                    request.check(); origin.check(); if (selected.cancelled) throw failure("SSH_CANCELLED");
                }
                @Override public void beforeCommit() throws VaultException {
                    check(); if (!claimed.compareAndSet(false, true)) throw failure("VAULT_OPERATION");
                }
            };
            try {
                SshVaultAdapter adapter = new SshVaultAdapter(vault, (c, t) -> { throw failure("VAULT_OPERATION"); },
                        p -> { throw failure("VAULT_OPERATION"); }, exact -> {
                            effect.check(); if (exact != selected.imported.proposal) throw failure("VAULT_OPERATION");
                        });
                operation = SshPasswordManifestValidator.within(origin.deadline,
                        () -> adapter.bundleRequest(selected.imported.proposal, origin.context));
                VaultOperation exact = operation;
                SshPasswordManifestValidator.within(origin.deadline, () -> {
                    adapter.commitBundle(exact, selected.imported.proposal, effect); return null;
                });
                written = true;
            } catch (Throwable failure) { failed = code(failure); }
            finally {
                vault.cancelOperation(operation); origin.cancel(); vault.cancelBundleContext(request.context); selected.close();
            }
            boolean committed = written; String code = failed;
            main.post(() -> {
                if (ticket == selected) ticket = null;
                if (committing == request) committing = null;
                String reason = code;
                if (reason == null) {
                    try {
                        if (closed || request.cancelled || selected.cancelled) throw failure("SSH_CANCELLED");
                        secrets.requireSshConsumer();
                        if (SystemClock.elapsedRealtime() >= origin.deadline) throw failure("SSH_TIMED_OUT");
                        vault.session.assertEpoch(origin.epoch + 1);
                        if (vault.contentRevision != origin.revision + 1) throw failure("VAULT_CHANGED");
                    } catch (VaultException failure) { reason = failure.code; }
                }
                if (reason != null && committed) { vault.session.lock(); reason = "VAULT_COMMIT_UNCERTAIN"; }
                finish(request, reason == null ? VaultCollections.map("committed", true) : null, reason);
            });
            cleanup();
        });
    }

    boolean cancel(String id) {
        Request request = requests.get(id);
        if (request == null) return false;
        if (request.handedOff && (ticket == null || ticket.owner != request)) return false;
        if (request.handedOff) return false;
        request.cancel();
        if (input == request && request.picker != 0) { input = null; finish(request, null, "SSH_CANCELLED"); }
        if (request == committing && ticket != null) { ticket.cancelled = true; ((Request)ticket.owner).cancel(); }
        if (reading == request) reading = null;
        if (editing == request) editing = null;
        return true;
    }
    void cancelReads() {
        if (reading != null && !reading.handedOff) { reading.cancel(); reading = null; }
        if (editing != null && !editing.handedOff) { editing.cancel(); editing = null; }
    }
    void cancelPending() {
        cancelReads();
        if (input != null) cancel(input.id);
        if (ticket != null) cancelTicket(ticket);
        if (committing != null) cancel(committing.id);
    }
    private void cancelTicket(Ticket selected) {
        selected.cancelled = true; ((Request)selected.owner).cancel();
        if (committing == null) {
            if (ticket == selected) ticket = null;
            worker.execute(selected::close);
        }
    }
    private void monitor(Request request) {
        if (closed || request.cancelled || request.handedOff || reading != request && editing != request) return;
        try { request.check(); main.postDelayed(() -> monitor(request), 100); }
        catch (VaultException failure) {
            request.cancel();
            emit.accept(VaultCollections.map("type", "state", "requestId", request.id,
                    "state", "cancelled", "reason", failure.code));
        }
    }
    private void expire(Request request) {
        if (input == request && !request.finished) { request.cancel(); input = null; finish(request, null, "SSH_TIMED_OUT"); }
    }
    private void expireTicket(Ticket selected) {
        if (ticket == selected && SystemClock.elapsedRealtime() >= ((Request)selected.owner).deadline) cancelTicket(selected);
    }
    private void finish(Request request, Object value, String code) {
        if (request.finished) return;
        request.finished = true;
        if (code == null) request.result.success(value); else error(request.result, code);
    }
    private void cleanup() {
        try { vault.retryPasswordValidationCleanup(); if (closed) worker.shutdown(); }
        catch (VaultException failure) {
            if (!cleanupQueued) {
                cleanupQueued = true;
                worker.schedule(() -> { cleanupQueued = false; cleanup(); }, 100, TimeUnit.MILLISECONDS);
            }
        }
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return "id_imported";
    }
    private static byte[] readBounded(InputStream stream, Request request) throws IOException, VaultException {
        byte[] chunk = new byte[8192];
        try (VaultCodec.SecretBuffer output = new VaultCodec.SecretBuffer(VaultManifest.MAX_FILE_BYTES + 1)) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                request.check();
                if (read == 0) continue;
                if (output.size() > VaultManifest.MAX_FILE_BYTES - read) throw failure("SSH_IMPORT_LIMIT");
                output.write(chunk, 0, read);
            }
            request.check(); return output.toByteArray();
        } finally { VaultCodec.wipe(chunk); }
    }
    private static String configText(byte[] bytes) throws VaultException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.indexOf('\0') >= 0) throw failure("SSH_CONFIG_INVALID");
            return value;
        } catch (CharacterCodingException failure) { throw failure("SSH_CONFIG_INVALID"); }
    }
    private static byte[] configBytes(String value) throws VaultException {
        if (value.indexOf('\0') >= 0 || value.length() > VaultManifest.MAX_FILE_BYTES) throw failure("PARAMETERS");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() > VaultManifest.MAX_FILE_BYTES) throw failure("SSH_IMPORT_LIMIT");
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException failure) { throw failure("SSH_CONFIG_INVALID"); }
    }
    private static String managedPath(String path) throws VaultException {
        VaultManifest.path(path);
        if (!path.startsWith(".ssh/") || path.equals(SshConnectionMaterial.MANUAL)) throw failure("SSH_IMPORT_PATH");
        return path;
    }
    private static Map<String, Object> issue(String code, String path, int line) {
        return VaultCollections.map("code", code, "path", path, "line", line, "message", "Review the SSH file and managed references");
    }
    private static String configCode(Throwable error) {
        if (error instanceof SshConfigEvaluator.EvaluationException) {
            int reason = ((SshConfigEvaluator.EvaluationException)error).diagnostic.reason;
            return reason == 3 ? "SSH_CONFIG_LIMIT" : reason == 2 ? "SSH_CONFIG_DEPENDENCY" : "SSH_CONFIG_UNSUPPORTED";
        }
        String code = code(error);
        return code.equals("SSH_IMPORT_FAILED") ? "SSH_CONFIG_INVALID" : code;
    }
    private static String code(Throwable error) {
        for (int depth = 0; depth < 5 && error != null; depth++, error = error.getCause())
            if (error instanceof VaultException) return ((VaultException)error).code;
        return "SSH_IMPORT_FAILED";
    }
    private static String identifier(MethodCall call, String key) throws VaultException {
        String value = text(call, key); VaultManifest.identifier(value); return value;
    }
    private static String text(MethodCall call, String key) throws VaultException {
        Object value = call.argument(key); if (!(value instanceof String)) throw failure("PARAMETERS"); return (String)value;
    }
    private static void exact(MethodCall call, String... keys) throws VaultException {
        if (!(call.arguments instanceof Map) || !((Map<?, ?>)call.arguments).keySet().equals(new HashSet<>(Arrays.asList(keys))))
            throw failure("PARAMETERS");
    }
    private static VaultException failure(String code) { return new VaultException(code, "The SSH library operation could not continue"); }
    private static void error(MethodChannel.Result result, String code) { result.error(code, "The SSH library operation could not continue", null); }
    @Override public void close() {
        if (closed) return; closed = true;
        for (Request request : requests.values()) request.cancel();
        Ticket owned = ticket; if (owned != null) { owned.cancelled = true; worker.execute(owned::close); }
        if (input != null) finish(input, null, "SSH_CANCELLED");
        worker.execute(this::cleanup);
    }
}
