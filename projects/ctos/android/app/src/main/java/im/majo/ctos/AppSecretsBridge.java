package im.majo.ctos;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.BinaryMessenger;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Typed management bridge. Secret/key enumeration is deliberately absent. */
final class AppSecretsBridge implements AutoCloseable {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AppSecrets vault;
    private final AppAuthentication authentication;
    private final SensitiveWindow window;
    private final java.util.Set<String> sensitiveOwners = new java.util.HashSet<>();
    private volatile int sensitiveOwnerCount;
    private final SharedPreferences preferences;
    private final VaultRequestAdmission admission = new VaultRequestAdmission();
    private final TotpPreviews totpPreviews;
    private final TotpVaultAdapter totpAdapter;
    private final SensitiveClipboard clipboard;
    private EventChannel events;
    private EventChannel.EventSink eventSink;
    private volatile Request active;
    private volatile boolean closed;
    private volatile boolean resumed;
    private boolean cameraVisible;
    private long backgroundVersion;
    private Runnable backgroundLock;
    private static Object exportLeaseOwner;
    private final Object exportLeaseToken = new Object();
    private final VaultExportJournal exportJournal;
    private VaultExportTransaction preparedExport;
    private String completedExportId;
    private Map<String, Object> completedExportReceipt;
    private String preparedRequestId;
    private long preparedEpoch, preparedDeadline;
    private byte[] preparedBinding;

    private static final class Request {
        final String id;
        final MethodCall call;
        final MethodChannel.Result result;
        final long epoch;
        final VaultRequestPhase phase = new VaultRequestPhase();
        private final String previewOwnerId = java.util.UUID.randomUUID().toString();
        boolean completed;
        boolean picker;
        int uiCode = -1;
        Runnable timeout;
        Runnable pendingPickerResult;
        VaultExportTransaction export;
        boolean exporting() { return call.method.startsWith("secrets.export"); }
        final Map<String, char[]> passwords = new java.util.concurrent.ConcurrentHashMap<>();
        char[] sourcePassword;
        char[] targetPassword;
        byte[] output;
        byte[] binding;
        VaultOperation totpRead;
        AppSecrets.TotpReadContext totpReadContext;
        VaultOperation totpCapture;
        AppSecrets.TotpMutationOrigin totpMutationOrigin;
        final long deadline = SystemClock.elapsedRealtime() + 120000;
        TotpCameraActivity.Capture camera;
        TotpImageActivity.Capture image;
        AppSecrets.ImportPreview importPreview;
        String totpPreview;

        Request(String id, MethodCall call, MethodChannel.Result result, long epoch) throws VaultException {
            this.id = id;
            Map<String, Object> safe = new HashMap<>();
            for (String name : VaultCollections.list("action", "previewId", "confirmTrust", "reviewBinding", "fileName", "exportId", "overwrite", "recoveryId", "issuer", "account", "entryId", "label", "candidateId")) {
                Object value = call.argument(name);
                if (value != null) safe.put(name, value);
            }
            for (String name : VaultCollections.list("password", "confirmation", "currentPassword", "newPassword", "sourcePassword", "targetPassword", "secret", "uri")) {
                Object value = call.argument(name);
                if (value != null) {
                    int maximum = name.equals("uri") ? TotpInput.MAX_URI : 1024;
                    if (!(value instanceof String) || ((String) value).length() > maximum) throw parameters();
                    passwords.put(name, ((String) value).toCharArray());
                }
            }
            this.call = new MethodCall(call.method, safe);
            this.result = result;
            this.epoch = epoch;
        }
        void clear() {
            for (char[] password : passwords.values()) Arrays.fill(password, '\0');
            passwords.clear();
            if (sourcePassword != null) Arrays.fill(sourcePassword, '\0');
            if (targetPassword != null) Arrays.fill(targetPassword, '\0');
            VaultCodec.wipe(output);
            if (camera != null) { TotpCameraActivity.release(camera); camera = null; }
            if (image != null) { TotpImageActivity.release(image); image = null; }
        }
        void cancel() {
            phase.cancel();
            if (totpReadContext != null) totpReadContext.cancel();
            if (totpMutationOrigin != null) totpMutationOrigin.cancel();
        }
    }

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (closed) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                invalidateVault();
                cancelActive();
                window.background(true);
            }
        }
    };

    AppSecretsBridge(Activity activity) {
        this(activity, AppSecretsOwner.acquire(activity),
                activity.getSharedPreferences("app_secrets_settings", Context.MODE_PRIVATE),
                new VaultExportJournal(activity.getNoBackupFilesDir()));
        authentication.cleanupDisabledKeys();
    }

    /** Scoped host dependencies; lifecycle/request logic is identical to the process-owned bridge. */
    AppSecretsBridge(Activity activity, AppSecrets vault, SharedPreferences preferences, VaultExportJournal exportJournal) {
        this.activity = activity;
        this.vault = java.util.Objects.requireNonNull(vault);
        this.preferences = java.util.Objects.requireNonNull(preferences);
        this.exportJournal = java.util.Objects.requireNonNull(exportJournal);
        try { vault.session.timeout(preferences.getInt("background_seconds", 30)); }
        catch (VaultException ignored) { /* Corrupt settings retain the safe default. */ }
        window = new SensitiveWindow(activity);
        authentication = new AppAuthentication(activity, main, worker, vault);
        totpPreviews = new TotpPreviews(vault, SystemClock::elapsedRealtime);
        totpAdapter = new TotpVaultAdapter(vault);
        clipboard = new SensitiveClipboard(activity);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(screenOff, filter);
    }

    void attachEvents(BinaryMessenger messenger) {
        events = new EventChannel(messenger, "ctos/secrets");
        events.setStreamHandler(new EventChannel.StreamHandler() {
            @Override public void onListen(Object arguments, EventChannel.EventSink sink) { eventSink = sink; emitState(); }
            @Override public void onCancel(Object arguments) { eventSink = null; }
        });
    }
    private void emitState() {
        main.post(() -> {
            if (!closed && eventSink != null) eventSink.success(VaultCollections.map("epoch", vault.session.epoch(),
                    "unlocked", vault.session.unlocked(), "foreground", vault.session.foreground()));
        });
    }

    AppSecrets sshVault() { return vault; }

    /** Actual shared foreground, unlock and native sensitive-window ownership gate. */
    void requireSshConsumer() throws VaultException {
        if (closed || !resumed || !vault.session.foreground())
            throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
        if (!vault.session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock the vault first");
        if (sensitiveOwnerCount == 0)
            throw new VaultException("VAULT_PROTECTION_REQUIRED", "Open the protected SSH connection form");
    }

    boolean handle(MethodCall call, MethodChannel.Result result) {
        if (!call.method.startsWith("secrets.")) return false;
        try {
            if (closed) throw new VaultException("VAULT_CANCELLED", "The app context closed");
            switch (call.method) {
                case "secrets.protect": {
                    String action = text(call, "action");
                    if (action.equals("acquire")) {
                        String owner = window.acquire();
                        sensitiveOwners.add(owner); sensitiveOwnerCount = sensitiveOwners.size();
                        result.success(VaultCollections.map("owner", owner));
                    }
                    else if (action.equals("release")) {
                        String owner = text(call, "owner"); window.release(owner);
                        sensitiveOwners.remove(owner); sensitiveOwnerCount = sensitiveOwners.size(); result.success(null);
                    }
                    else throw parameters();
                    return true;
                }
                case "secrets.cancel": {
                    String id = text(call, "requestId");
                    Request cancelling = active != null && active.id.equals(id) ? active : null;
                    if (cancelling != null) {
                        cancelling.cancel();
                        vault.invalidatePreviewRequest(cancelling.previewOwnerId);
                        worker.execute(() -> vault.cancelRequest(cancelling.previewOwnerId));
                    }
                    totpPreviews.cancelRequest(id);
                    if (cancelling != null && cancelling.call.method.equals("secrets.totpCommit")) {
                        Object preview = cancelling.call.argument("previewId");
                        if (preview instanceof String) totpPreviews.cancel((String) preview);
                    }
                    worker.execute(() -> { if (id.equals(preparedRequestId)) discardPrepared(); });
                    authentication.cancel(id);
                    if (active != null && active.id.equals(id) && !active.exporting()) finish(active, null,
                            new VaultException("VAULT_CANCELLED", "Operation was cancelled"));
                    if (active != null && active.id.equals(id) && active.exporting()) {
                        Request waiting = active;
                        worker.execute(() -> exportFailure(waiting, new VaultException("VAULT_CANCELLED", "Cancelled")));
                    }
                    result.success(null);
                    return true;
                }
                case "secrets.lock":
                    invalidateVault();
                    cancelActive();
                    worker.execute(() -> respondStatus(result));
                    return true;
                case "secrets.importCancel":
                    String previewId = text(call, "previewId");
                    worker.execute(() -> { vault.cancelPreview(previewId); main.post(() -> result.success(null)); });
                    return true;
                case "secrets.totpCancel": {
                    String preview = text(call, "previewId");
                    totpPreviews.cancel(preview);
                    result.success(null);
                    return true;
                }
                case "secrets.status":
                    worker.execute(() -> respondStatus(result));
                    return true;
                case "secrets.settings": {
                    int background = integer(call, "backgroundTimeoutSeconds", 0);
                    worker.execute(() -> {
                        try {
                            if (!preferences.edit().putInt("background_seconds", background).commit()) {
                                throw new VaultException("VAULT_IO", "Unable to save timeout settings");
                            }
                            vault.session.timeout(background);
                            main.post(this::scheduleBackgroundLock);
                            respondStatus(result);
                        } catch (VaultException failure) { main.post(() -> result.error(failure.code, failure.getMessage(), null)); }
                    });
                    return true;
                }
                default: break;
            }
            if (!VaultCollections.set("secrets.create", "secrets.unlock", "secrets.changePassword", "secrets.quickUnlock",
                    "secrets.exportPreview", "secrets.exportCommit", "secrets.exportCancel", "secrets.exportRecover", "secrets.importPreview", "secrets.importCommit",
                    "secrets.totpList", "secrets.totpManualPreview", "secrets.totpImagePreview", "secrets.totpCameraPreview",
                    "secrets.totpCommit", "secrets.totpRename", "secrets.totpDelete", "secrets.totpCopy").contains(call.method)) {
                result.notImplemented();
                return true;
            }
            if (active != null) throw new VaultException("BUSY", "A vault operation is already active");
            String id = text(call, "requestId");
            boolean read = call.method.equals("secrets.totpList");
            admission.admit(id, read);
            Request request = new Request(id, call, result, vault.session.epoch());
            if (read || call.method.equals("secrets.totpCopy")) {
                request.totpReadContext = vault.newTotpReadContext(request.epoch, request.deadline, readGuard(request));
            }
            if (VaultCollections.set("secrets.totpManualPreview", "secrets.totpImagePreview", "secrets.totpCameraPreview",
                    "secrets.totpRename", "secrets.totpDelete").contains(call.method)) {
                request.totpMutationOrigin = vault.newTotpMutationOrigin(request.epoch, request.deadline, readGuard(request));
            }
            active = request;
            request.timeout = () -> {
                if (active == request) {
                    request.cancel();
                    vault.invalidatePreviewRequest(request.previewOwnerId);
                    worker.execute(() -> vault.cancelRequest(request.previewOwnerId));
                    authentication.cancel(request.id);
                    if (request.exporting()) {
                        worker.execute(() -> exportFailure(request, new VaultException("VAULT_TIMED_OUT", "Timed out")));
                    } else finish(request, null, new VaultException("VAULT_TIMED_OUT", "Vault operation timed out"));
                }
            };
            main.postDelayed(request.timeout, 120000);
            worker.execute(() -> execute(request));
        } catch (VaultException error) { result.error(error.code, error.getMessage(), null); }
        return true;
    }

    private void execute(Request request) {
        try {
            // Bind the exact prepared ticket before any interruptible guard: failure must compensate it.
            if (request.call.method.equals("secrets.exportCancel") || request.call.method.equals("secrets.exportCommit")) {
                Object id = request.call.argument("exportId");
                if (preparedExport != null && preparedExport.state.id.equals(id)) request.export = preparedExport;
            }
            if ((request.call.method.equals("secrets.exportCancel") || request.call.method.equals("secrets.exportCommit"))
                    && completedExportReceipt != null && completedExportId.equals(text(request.call, "exportId"))) {
                // No new effect: cancellation must not erase an already terminal cleanup receipt.
                replayExportReceipt(request); return;
            }
            if (request.call.method.equals("secrets.exportCancel") || request.call.method.equals("secrets.exportRecover")) exportNavigationGuard(request);
            else if (request.totpReadContext != null) readGuard(request).check();
            else guard(request);
            switch (request.call.method) {
                case "secrets.totpList": {
                    request.totpRead = vault.beginTotpList(request.totpReadContext);
                    Map<String, Object> entries = vault.totpList(request.totpRead, System.currentTimeMillis() / 1000);
                    main.post(() -> finish(request, entries, null)); return;
                }
                case "secrets.totpManualPreview": {
                    TotpInput input;
                    if (request.passwords.containsKey("uri")) input = TotpInput.uri(new String(request.passwords.get("uri")));
                    else input = TotpInput.manual(text(request.call, "issuer"), text(request.call, "account"), new String(request.passwords.getOrDefault("secret", new char[0])));
                    Map<String, Object> preview = totpPreviews.prepare(request.previewOwnerId, request.id, VaultCollections.list(input), 0, request.totpMutationOrigin);
                    request.totpPreview = (String) preview.get("previewId");
                    expireTotpPreview(request);
                    main.post(() -> finish(request, preview, null)); return;
                }
                case "secrets.totpImagePreview":
                case "secrets.totpCameraPreview":
                    openTotpPicker(request); return;
                case "secrets.totpCommit": {
                    String entry = totpPreviews.commit(text(request.call, "previewId"), text(request.call, "candidateId"), requestGuard(request));
                    main.post(() -> finish(request, VaultCollections.map("entryId", entry), null)); return;
                }
                case "secrets.totpRename": {
                    String id = text(request.call, "entryId");
                    vault.renameTotp(vault.beginTotpRename(id, request.totpMutationOrigin.context()), id, text(request.call, "label"), requestGuard(request)); break;
                }
                case "secrets.totpDelete": {
                    String id = text(request.call, "entryId");
                    totpAdapter.delete(totpAdapter.deleteRequest(id, request.totpMutationOrigin.context()), id, requestGuard(request)); break;
                }
                case "secrets.totpCopy": {
                    String id = text(request.call, "entryId");
                    request.totpRead = totpAdapter.request(id, request.totpReadContext);
                    long seconds = System.currentTimeMillis() / 1000;
                    String code = vault.totpCode(request.totpRead, id, seconds);
                    main.post(() -> {
                        if (request.completed) return;
                        try {
                            vault.publishTotpRead(request.totpRead, () -> clipboard.copyCode(code));
                            finishUnchecked(request, VaultCollections.map("copied", true), null);
                        } catch (VaultException failure) { finish(request, null, failure); }
                        catch (RuntimeException failure) {
                            if (!request.completed) finish(request, null, new VaultException("VAULT_FAILED", "Unable to copy the code"));
                            else throw failure;
                        } catch (Error failure) {
                            if (!request.completed) { request.completed = true; cleanupRequest(request, true); }
                            throw failure;
                        }
                    }); return;
                }
                case "secrets.create":
                    withPasswords(request, "password", "confirmation", (password, confirmation) ->
                            vault.create(password, confirmation, requestGuard(request)));
                    break;
                case "secrets.unlock":
                    withPasswords(request, "password", null, (password, ignored) -> vault.unlock(password, requestGuard(request)));
                    break;
                case "secrets.changePassword": {
                    char[] old = password(request, "currentPassword");
                    char[] next = password(request, "newPassword");
                    char[] confirmation = password(request, "confirmation");
                    try { vault.changePassword(old, next, confirmation, requestGuard(request)); }
                    finally { Arrays.fill(old, '\0'); Arrays.fill(next, '\0'); Arrays.fill(confirmation, '\0'); }
                    authentication.cleanupDisabledKeys();
                    break;
                }
                case "secrets.quickUnlock": {
                    String action = text(request.call, "action");
                    if (action.equals("disable")) {
                        withPasswords(request, "password", null, (password, ignored) -> vault.disableQuick(password, requestGuard(request)));
                        authentication.cleanupDisabledKeys();
                    } else if (action.equals("enable") || action.equals("unlock")) {
                        VaultEnvelope target = vault.current();
                        byte[] wrapper = vault.quickMaterial();
                        byte[] key = null;
                        if (action.equals("enable")) {
                            char[] password = password(request, "password");
                            try {
                                VaultEnvelope envelope = target;
                                if (envelope == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
                                try (AppSecrets.Verified verified = AppSecrets.verify(envelope.encode(), password)) {
                                    key = verified.key.clone();
                                    request.binding = envelope.binding();
                                }
                            } finally { Arrays.fill(password, '\0'); }
                        }
                        byte[] material = key;
                        main.post(() -> {
                            try {
                                guard(request);
                                authentication.start(request.id, action, target, wrapper, material, requestGuard(request), failure -> {
                                    if (failure != null) finish(request, null, failure);
                                    else worker.execute(() -> completeStatus(request));
                                });
                            } catch (VaultException failure) { finish(request, null, failure); }
                            finally { VaultCodec.wipe(material); }
                        });
                        return;
                    } else throw parameters();
                    break;
                }
                case "secrets.exportPreview": {
                    VaultExportTransaction.validateName(text(request.call, "fileName"));
                    acquireExportLease();
                    if (loadExportRecovery() != null) throw new VaultException("VAULT_EXPORT_RECOVERY_REQUIRED", "Review the unfinished export first");
                    char[] password = password(request, "password");
                    try { request.output = vault.export(password, requestGuard(request)); }
                    finally { Arrays.fill(password, '\0'); }
                    for (char[] input : request.passwords.values()) Arrays.fill(input, '\0');
                    request.passwords.clear();
                    request.binding = VaultEnvelope.parse(request.output).binding();
                    openPicker(request, false);
                    return;
                }
                case "secrets.exportCommit": {
                    if (replayExportReceipt(request)) return;
                    VaultExportTransaction transaction = requirePrepared(request);
                    Object overwrite = request.call.argument("overwrite");
                    if (!(overwrite instanceof Boolean)) throw parameters();
                    completeExport(request, transaction.commit((Boolean) overwrite, exportGuard(request)));
                    return;
                }
                case "secrets.exportCancel":
                    if (replayExportReceipt(request)) return;
                    completeExport(request, requirePrepared(request).cancel());
                    return;
                case "secrets.exportRecover": {
                    acquireExportLease();
                    VaultExportTransaction.State recovery = loadExportRecovery();
                    if (recovery == null || !recovery.id.equals(text(request.call, "recoveryId"))) throw parameters();
                    openPicker(request, false);
                    return;
                }
                case "secrets.importPreview":
                    request.sourcePassword = password(request, "sourcePassword");
                    if (request.passwords.containsKey("targetPassword")) request.targetPassword = password(request, "targetPassword");
                    openPicker(request, true);
                    return;
                case "secrets.importCommit":
                    Object trust = request.call.argument("confirmTrust");
                    if (!(trust instanceof Boolean)) throw parameters();
                    vault.importCommit(text(request.call, "previewId"), request.call.argument("reviewBinding") == null ? "" : text(request.call, "reviewBinding"), (Boolean) trust, requestGuard(request));
                    authentication.cleanupDisabledKeys();
                    break;
                default: throw parameters();
            }
            completeStatus(request);
        } catch (VaultException error) {
            if (request.exporting()) exportFailure(request, error); else main.post(() -> finish(request, null, error));
        } catch (Exception error) {
            if (request.exporting()) exportFailure(request, error);
            else main.post(() -> finish(request, null, new VaultException("VAULT_FAILED", "Vault operation failed; previous data retained")));
        }
    }

    private void guard(Request request) throws VaultException {
        if (closed || request.phase.cancelled() || active != request) throw new VaultException("VAULT_CANCELLED", "Operation was cancelled");
        if (SystemClock.elapsedRealtime() >= request.deadline) throw new VaultException("VAULT_TIMED_OUT", "Operation timed out");
        vault.session.assertEpoch(request.epoch);
        if (!resumed || !vault.session.foreground()) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
    }

    private void openTotpPicker(Request request) throws VaultException {
        request.totpCapture = vault.beginTotpSave(java.util.UUID.randomUUID().toString(), request.totpMutationOrigin.context());
        main.post(() -> {
            try {
                guard(request);
                request.picker = true; request.uiCode = VaultUiRequests.allocate();
                Intent intent;
                if (request.call.method.equals("secrets.totpCameraPreview")) {
                    request.camera = new TotpCameraActivity.Capture(request.id, () -> {
                        if (closed || active != request || request.phase.cancelled() || SystemClock.elapsedRealtime() >= request.deadline)
                            throw new VaultException("VAULT_CANCELLED", "Scanner expired");
                        vault.session.assertEpoch(request.epoch);
                        if (!vault.session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock again");
                    }, visible -> onCameraVisibility(request, visible));
                    TotpCameraActivity.install(request.camera);
                    intent = new Intent(activity, TotpCameraActivity.class).putExtra("requestId", request.id);
                } else {
                    request.image = new TotpImageActivity.Capture(request.id, () -> {
                        if (closed || active != request || request.phase.cancelled() || SystemClock.elapsedRealtime() >= request.deadline)
                            throw new VaultException("VAULT_CANCELLED", "Image read expired");
                        vault.session.assertEpoch(request.epoch);
                        if (!vault.session.unlocked()) throw new VaultException("VAULT_LOCKED", "Unlock again");
                    });
                    TotpImageActivity.install(request.image);
                    intent = new Intent(activity, TotpImageActivity.class).putExtra("requestId", request.id);
                }
                activity.startActivityForResult(intent, request.uiCode);
            } catch (Exception failure) {
                finish(request, null, failure instanceof VaultException ? (VaultException) failure
                        : new VaultException("TOTP_IMAGE", "Image picker or camera is unavailable"));
            }
        });
    }

    private void onCameraVisibility(Request request, boolean visible) {
        if (active != request || closed) return;
        cameraVisible = visible && active == request && !closed && !request.phase.cancelled();
        if (cameraVisible || resumed) { vault.session.foreground(true); cancelBackgroundLock(); }
        else { vault.session.foreground(false); scheduleBackgroundLock(); }
        emitState();
    }
    private void expireTotpPreview(Request request) {
        main.postDelayed(() -> { if (!closed) totpPreviews.cancelOwner(request.previewOwnerId); },
                Math.max(0, request.deadline - SystemClock.elapsedRealtime()));
    }

    private void finishTotpPicker(Request request, int resultCode, Intent data) {
        request.picker = false;
        worker.execute(() -> {
            try {
                guard(request); vault.checkTotpPending(request.totpCapture);
                List<TotpInput> inputs;
                int invalid;
                if (request.call.method.equals("secrets.totpCameraPreview")) {
                    TotpCameraActivity.Capture capture = request.camera;
                    if (capture == null) throw new VaultException("VAULT_CANCELLED", "Scanner cancelled");
                    synchronized (capture) {
                        if (capture.failure != null) throw capture.failure;
                        if (resultCode != Activity.RESULT_OK || capture.input == null) throw new VaultException("VAULT_CANCELLED", "Scanner cancelled");
                        inputs = VaultCollections.list(capture.input); capture.input = null;
                    }
                    invalid = 0;
                } else {
                    TotpImageActivity.Capture capture = request.image;
                    if (capture == null) throw new VaultException("VAULT_CANCELLED", "Image selection cancelled");
                    synchronized (capture) {
                        if (capture.failure != null) throw capture.failure;
                        if (resultCode != Activity.RESULT_OK || capture.decoded == null) throw new VaultException("VAULT_CANCELLED", "Image selection cancelled");
                        inputs = capture.decoded.inputs; invalid = capture.decoded.invalid; capture.decoded = null;
                    }
                }
                try {
                    vault.checkTotpPending(request.totpCapture);
                    Map<String, Object> preview = totpPreviews.prepare(request.previewOwnerId, request.id, inputs, invalid, request.totpMutationOrigin);
                    request.totpPreview = (String) preview.get("previewId");
                    expireTotpPreview(request);
                    main.post(() -> finish(request, preview, null));
                } catch (VaultException failure) { for (TotpInput input : inputs) input.close(); throw failure; }
            } catch (VaultException failure) { main.post(() -> finish(request, null, failure)); }
        });
    }

    private AppSecrets.EffectGuard requestGuard(Request request) {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException { guard(request); }
            @Override public void beforeCommit() throws VaultException {
                request.phase.claimCommit(() -> guard(request));
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                request.phase.publish(() -> {
                    synchronized (vault.session) {
                        // Reject background before assertEpoch can perform lazy revocation cleanup.
                        if (!resumed || !vault.session.foreground())
                            throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
                        guard(request);
                        publication.check();
                    }
                });
            }
        };
    }

    private AppSecrets.EffectGuard readGuard(Request request) {
        return new AppSecrets.EffectGuard() {
            @Override public void check() throws VaultException {
                if (closed || request.phase.cancelled() || active != request || request.completed)
                    throw new VaultException("VAULT_CANCELLED", "Operation was cancelled");
                if (!resumed) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
                if (SystemClock.elapsedRealtime() >= request.deadline)
                    throw new VaultException("VAULT_TIMED_OUT", "Operation timed out");
            }
            @Override public void publish(AppSecrets.EffectGuard publication) throws VaultException {
                request.phase.publish(() -> { check(); publication.check(); });
            }
        };
    }

    private void openPicker(Request request, boolean importing) {
        main.post(() -> {
            try {
                if (request.call.method.equals("secrets.exportRecover")) exportNavigationGuard(request); else guard(request);
                request.picker = true;
                request.uiCode = VaultUiRequests.allocate();
                Intent intent = importing
                        ? new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
                        : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                activity.startActivityForResult(intent, request.uiCode);
            } catch (Exception error) {
                if (request.exporting()) {
                    // close already queues this request's owned cleanup before shutting down the executor.
                    if (!closed && !worker.isShutdown()) worker.execute(() -> exportFailure(request, error));
                }
                else finish(request, null, error instanceof VaultException ? (VaultException) error
                    : new VaultException("VAULT_UNAVAILABLE", "System file picker is unavailable"));
            }
        });
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (authentication.onActivityResult(requestCode, resultCode)) return true;
        Request request = active;
        if (request == null || !request.picker || request.uiCode != requestCode) return VaultUiRequests.owns(requestCode);
        if (!resumed) {
            request.pendingPickerResult = () -> finishPickerResult(request, resultCode, data);
            return true;
        }
        finishPickerResult(request, resultCode, data);
        return true;
    }

    private void finishPickerResult(Request request, int resultCode, Intent data) {
        if (active != request || request.phase.cancelled() || request.completed) return;
        if (request.call.method.equals("secrets.totpImagePreview") || request.call.method.equals("secrets.totpCameraPreview")) {
            finishTotpPicker(request, resultCode, data); return;
        }
        request.picker = false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (request.exporting()) worker.execute(() -> exportFailure(request, new VaultException("VAULT_CANCELLED", "Cancelled")));
            else finish(request, VaultCollections.map("status", "cancelled"), null);
            return;
        }
        Uri uri = data.getData();
        worker.execute(() -> {
            try {
                if (request.call.method.equals("secrets.exportRecover")) exportNavigationGuard(request); else guard(request);
                if (request.call.method.equals("secrets.importPreview")) {
                    byte[] source;
                    try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new java.io.IOException();
                        source = readBounded(stream);
                    }
                    AppSecrets.ImportPreview preview = vault.preview(source, request.sourcePassword,
                            request.targetPassword, request.previewOwnerId, request.deadline, requestGuard(request));
                    request.importPreview = preview;
                    Map<String, Object> summary = previewSummary(preview);
                    String previewId = preview.id;
                    main.postDelayed(() -> {
                        if (!closed) worker.execute(() -> vault.expirePreview(previewId));
                    }, 120000);
                    main.post(() -> finish(request, summary, null));
                } else {
                    SafExportProvider provider = new SafExportProvider(activity.getContentResolver(), uri);
                    if (request.call.method.equals("secrets.exportRecover")) {
                        VaultExportTransaction.State recovery = loadExportRecovery();
                        if (recovery == null || !recovery.tree.equals(provider.tree()) || !recovery.id.equals(text(request.call, "recoveryId"))) throw parameters();
                        request.export = new VaultExportTransaction(provider, exportJournal, recovery);
                        completeExport(request, request.export.recover());
                    } else {
                        request.export = new VaultExportTransaction(provider, exportJournal, text(request.call, "fileName"), request.output);
                        Map<String, Object> preview = request.export.prepare(exportGuard(request));
                        preparedExport = request.export; preparedRequestId = request.id;
                        preparedEpoch = request.epoch; preparedBinding = request.binding.clone();
                        preparedDeadline = SystemClock.elapsedRealtime() + 120000;
                        guard(request);
                        main.postDelayed(() -> {
                            if (!worker.isShutdown()) worker.execute(() -> {
                                if (preparedExport != null && SystemClock.elapsedRealtime() >= preparedDeadline) discardPrepared();
                            });
                        }, 120000);
                        main.post(() -> {
                            if (request.phase.cancelled() || closed) {
                                if (!worker.isShutdown()) worker.execute(() -> exportFailure(request, new VaultException("VAULT_CANCELLED", "Cancelled")));
                            }
                            else finish(request, preview, null);
                        });
                    }
                }
            } catch (VaultException error) {
                if (request.exporting()) exportFailure(request, error); else main.post(() -> finish(request, null, error));
            } catch (Exception error) {
                if (request.exporting()) exportFailure(request, error);
                else main.post(() -> finish(request, null, new VaultException("VAULT_IO", "Unable to transfer the encrypted backup")));
            }
        });
    }

    private static synchronized void takeLease(Object owner) throws VaultException {
        if (exportLeaseOwner != null && exportLeaseOwner != owner) throw new VaultException("BUSY", "An export is active");
        exportLeaseOwner = owner;
    }
    private void exportNavigationGuard(Request request) throws VaultException {
        if (closed || request.phase.cancelled() || active != request || !resumed) throw new VaultException("VAULT_CANCELLED", "Export navigation cancelled");
    }
    private void acquireExportLease() throws VaultException { takeLease(exportLeaseToken); }
    private void releaseExportLease() { synchronized (AppSecretsBridge.class) { if (exportLeaseOwner == exportLeaseToken) exportLeaseOwner = null; } }
    private VaultExportTransaction requirePrepared(Request request) throws VaultException {
        if (preparedExport == null || !preparedExport.state.id.equals(text(request.call, "exportId"))) throw parameters();
        request.export = preparedExport;
        return preparedExport;
    }
    private VaultExportTransaction.Guard exportGuard(Request request) {
        return new VaultExportTransaction.Guard() {
            @Override public void check() throws Exception {
                guard(request);
                byte[] expected = request.binding == null ? preparedBinding : request.binding;
                VaultEnvelope current = vault.current();
                if (current == null || expected == null || !MessageDigest.isEqual(expected, current.binding())) throw new VaultException("VAULT_CHANGED", "The snapshot changed");
                if (request.call.method.equals("secrets.exportCommit") && (request.epoch != preparedEpoch || SystemClock.elapsedRealtime() > preparedDeadline)) throw new VaultException("VAULT_EXPIRED", "The export expired");
            }
            @Override public void finalVerified() throws Exception {
                request.phase.claimExport(() -> {
                    try { check(); } catch (VaultException error) { throw error; } catch (Exception error) { throw parameters(); }
                });
            }
        };
    }
    private void discardPrepared() {
        if (preparedExport != null) {
            completedExportId = preparedExport.state.id;
            completedExportReceipt = preparedExport.cancel();
        }
        preparedExport = null; preparedRequestId = null; preparedBinding = null; releaseExportLease();
    }
    private void completeExport(Request request, Map<String, Object> receipt) {
        if (request.export != null) { completedExportId = request.export.state.id; completedExportReceipt = receipt; }
        if (request.export == preparedExport) { preparedExport = null; preparedRequestId = null; preparedBinding = null; }
        releaseExportLease(); main.post(() -> finish(request, receipt, null));
    }
    private boolean replayExportReceipt(Request request) throws VaultException {
        if (completedExportReceipt != null && completedExportId.equals(text(request.call, "exportId"))) {
            Map<String, Object> receipt = completedExportReceipt;
            main.post(() -> finish(request, receipt, null)); return true;
        }
        return false;
    }
    private void exportFailure(Request request, Exception error) {
        if (request.export != null) { completeExport(request, request.export.failed(error)); return; }
        if (preparedExport == null) releaseExportLease();
        String code = error instanceof VaultException ? ((VaultException) error).code : "VAULT_IO";
        if (request.call.method.equals("secrets.exportPreview") && !code.equals("VAULT_CANCELLED") && !code.equals("VAULT_TIMED_OUT")) {
            main.post(() -> finish(request, null, new VaultException(code, "Export preparation failed"))); return;
        }
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", code.equals("VAULT_CANCELLED") ? "cancelled" : "failed");
        receipt.put("phase", "not_prepared"); receipt.put("fileName", request.call.argument("fileName") == null ? "" : request.call.argument("fileName"));
        receipt.put("locationLabel", ""); receipt.put("remaining", VaultCollections.list()); receipt.put("errorCode", code);
        if (request.call.method.equals("secrets.exportRecover")) {
            java.util.List<Map<String, Object>> remaining = new java.util.ArrayList<>();
            try {
                VaultExportTransaction.State pending = exportJournal.load();
                if (pending != null) {
                    receipt.put("status", "recovery_required"); receipt.put("phase", pending.phase);
                    receipt.put("fileName", pending.name); receipt.put("locationLabel", pending.location);
                    for (int i = 0; i < 3; i++) {
                        VaultExportTransaction.Document document = new VaultExportTransaction.Document[] {pending.stage, pending.finalFile, pending.rollback}[i];
                        if (document != null) remaining.add(remainingItem(new String[] {"created_stage", "created_final", "renamed_old"}[i], document.name, document.location));
                    }
                    if (pending.pendingRole != null) remaining.add(remainingItem(pending.pendingRole, pending.pendingName, pending.location));
                }
            } catch (Exception unknown) {
                receipt.put("status", "recovery_required"); remaining.add(remainingItem("journal", "导出恢复记录", "ctOS"));
            }
            receipt.put("remaining", remaining);
        }
        main.post(() -> finish(request, receipt, null));
    }
    private static Map<String, Object> remainingItem(String role, String name, String location) {
        Map<String, Object> value = new HashMap<>(); value.put("role", role); value.put("name", name); value.put("location", location); value.put("state", "unknown"); return value;
    }

    private Map<String, Object> previewSummary(AppSecrets.ImportPreview preview) throws VaultException {
        Map<String, Object> map = new HashMap<>();
        map.put("status", "preview");
        map.put("previewId", preview.id);
        map.put("totpEntries", (int) preview.source.manifest.entries.stream().filter(e -> e.type == VaultManifest.EntryType.TOTP).count());
        map.put("sshEntries", (int) preview.source.manifest.entries.stream().filter(e -> e.type != VaultManifest.EntryType.TOTP).count());
        map.put("files", preview.source.manifest.files.size());
        // SSH semantic validator will supply concrete endpoint/fingerprint/conflict projections before activation.
        Map<String, Object> review = preview.reviewMap();
        map.put("reviewBinding", preview.reviewBinding);
        map.put("restoreReview", review);
        map.put("trustRecords", review == null ? 0 : ((java.util.List<?>) review.get("trustChanges")).size());
        map.put("trustSources", VaultCollections.list());
        map.put("overwrite", preview.targetBinding != null);
        return map;
    }

    private VaultExportTransaction.State loadExportRecovery() throws VaultException {
        try { return exportJournal.load(); }
        catch (Exception error) { throw new VaultException("VAULT_EXPORT_RECOVERY_REQUIRED", "The export recovery record could not be read"); }
    }
    private Map<String, Object> status() throws VaultException {
        VaultEnvelope envelope = vault.current();
        boolean unlocked = vault.session.unlocked();
        Map<String, Object> map = new HashMap<>();
        map.put("state", envelope == null ? "missing" : unlocked ? "unlocked" : "locked");
        map.put("exists", envelope != null);
        map.put("generation", envelope == null ? 0L : envelope.generation);
        map.put("quickUnlockEnabled", vault.quickMaterial() != null);
        map.put("backgroundTimeoutSeconds", vault.session.timeout());
        map.put("sshBackupImportAvailable", false);
        List<Map<String, Object>> recoveries = new ArrayList<>();
        try {
            VaultExportTransaction.State state = exportJournal.load();
            if (state != null && preparedExport == null) {
                Map<String, Object> recovery = new HashMap<>(); recovery.put("recoveryId", state.id);
                recovery.put("fileName", state.name); recovery.put("locationLabel", state.location); recovery.put("phase", state.phase);
                recoveries.add(recovery);
            }
        } catch (Exception error) { map.put("exportRecoveryError", "VAULT_EXPORT_RECOVERY_REQUIRED"); recoveries.clear(); }
        map.put("exportRecovery", recoveries);
        map.putAll(authentication.availability());
        return map;
    }

    private void respondStatus(MethodChannel.Result result) {
        try {
            vault.cleanupInvalidatedContexts();
            Map<String, Object> state = status();
            main.post(() -> result.success(state));
        }
        catch (VaultException error) { main.post(() -> result.error(error.code, error.getMessage(), null)); }
    }

    private void completeStatus(Request request) {
        try { Map<String, Object> state = status(); main.post(() -> finish(request, state, null)); }
        catch (VaultException error) { main.post(() -> finish(request, null, error)); }
    }

    private void finish(Request request, Object value, VaultException error) {
        if (request.completed) return;
        if (error == null && request.importPreview != null) {
            try {
                vault.deliverPreview(request.importPreview, requestGuard(request), () -> finishUnchecked(request, value, null));
            } catch (VaultException invalid) { finishUnchecked(request, null, invalid); }
            return;
        }
        if (error == null && request.totpRead != null) {
            try {
                vault.publishTotpRead(request.totpRead, () -> {
                    request.completed = true;
                    // Flutter Result is a bounded transport callback; it must not re-enter core.
                    request.result.success(value);
                });
            } catch (VaultException invalid) { finishUnchecked(request, null, invalid); }
            finally { if (request.completed && active == request) cleanupRequest(request, false); }
            return;
        }
        if (error == null && request.totpPreview != null) {
            try {
                totpPreviews.publish(request.totpPreview, () -> {
                    request.completed = true; request.result.success(value);
                });
                cleanupRequest(request, false);
            }
            catch (VaultException invalid) { finishUnchecked(request, null, invalid); }
            catch (RuntimeException | Error failure) {
                cleanupRequest(request, true);
                throw failure;
            }
            return;
        }
        finishUnchecked(request, value, error);
    }

    private void finishUnchecked(Request request, Object value, VaultException error) {
        if (error != null && VaultCollections.set("VAULT_CANCELLED", "VAULT_TIMED_OUT", "VAULT_CHANGED",
                "VAULT_FOREGROUND_REQUIRED", "VAULT_EXPIRED", "VAULT_FAILED").contains(error.code)) {
            error = request.phase.interrupted(error);
        }
        if (error != null && error.code.equals("VAULT_COMMIT_UNCERTAIN")) invalidateVault();
        request.completed = true;
        cleanupRequest(request, error != null);
        if (error != null) request.result.error(error.code, error.getMessage(), null);
        else request.result.success(value);
    }

    private void cleanupRequest(Request request, boolean failed) {
        if (request.camera != null) onCameraVisibility(request, false);
        if (failed) {
            AppSecrets.ImportPreview pending = request.importPreview;
            if (pending != null) {
                vault.invalidatePreviewRequest(request.previewOwnerId);
                worker.execute(() -> vault.cancelPreview(pending.id));
            }
            if (request.totpMutationOrigin != null) request.totpMutationOrigin.cancel();
            totpPreviews.cancelOwner(request.previewOwnerId);
            if (request.call.method.equals("secrets.totpCommit")) {
                Object preview = request.call.argument("previewId");
                if (preview instanceof String) totpPreviews.cancel((String) preview);
            }
        }
        vault.cancelOperation(request.totpCapture);
        if (request.totpReadContext != null) request.totpReadContext.cancel();
        if (request.totpMutationOrigin != null && !request.totpMutationOrigin.handedOff) request.totpMutationOrigin.cancel();
        if (request.timeout != null) main.removeCallbacks(request.timeout);
        request.clear();
        if (active == request) active = null;
        worker.execute(totpPreviews::retireInvalid);
        emitState();
    }

    void onResume() {
        if (closed) return;
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) { invalidateVault(); cancelActive(); }
        vault.session.foreground(true);
        resumed = true;
        worker.execute(vault::cleanupInvalidatedContexts);
        worker.execute(totpPreviews::retireInvalid);
        cancelBackgroundLock();
        window.background(false);
        authentication.onResume();
        emitState();
        Request request = active;
        if (request != null && request.pendingPickerResult != null) {
            Runnable callback = request.pendingPickerResult;
            request.pendingPickerResult = null;
            callback.run();
        }
    }

    void onPause() {
        if (closed) return;
        resumed = false;
        authentication.onPause();
        window.background(true);
        // A same-app camera transition hands visibility to onStart/onStop; ordinary pauses
        // and external pickers retain the shared background policy.
        if (!cameraVisible && !(active != null && active.camera != null && active.picker)) vault.session.foreground(false);
        scheduleBackgroundLock();
    }

    void onStop() {
        if (closed) return;
        if (!cameraVisible) vault.session.foreground(false);
        scheduleBackgroundLock();
    }

    private void scheduleBackgroundLock() {
        cancelBackgroundLock();
        if (closed) return;
        boolean unlocked = vault.session.unlocked();
        long currentEpoch = vault.session.epoch();
        Request request = active;
        if (request != null && request.epoch != currentEpoch) cancelActiveRequest();
        // A zero timeout may have already wiped the key. Retire old plaintext/capabilities anyway,
        // while keeping new requests admitted from an already-locked, unchanged context alive.
        worker.execute(() -> {
            vault.cleanupInvalidatedContexts();
            totpPreviews.retireInvalid();
            if (preparedExport != null && preparedEpoch != vault.session.epoch()) discardPrepared();
        });
        long version = backgroundVersion;
        if (vault.session.foreground() || !unlocked) return;
        // Lazy deadline checks remain authoritative when Android pauses the callback.
        backgroundLock = () -> {
            if (closed || version != backgroundVersion) return;
            backgroundLock = null;
            if (!vault.session.unlocked()) { invalidateVault(); cancelActive(); }
        };
        main.postDelayed(backgroundLock, vault.session.remainingBackgroundMillis());
    }

    private void cancelBackgroundLock() {
        backgroundVersion++;
        if (backgroundLock != null) main.removeCallbacks(backgroundLock);
        backgroundLock = null;
    }

    private void cancelActive() {
        cancelActiveRequest();
        worker.execute(this::discardPrepared);
        worker.execute(totpPreviews::close);
    }

    private void cancelActiveRequest() {
        Request request = active;
        if (request != null) {
            request.cancel();
            vault.invalidatePreviewRequest(request.previewOwnerId);
            worker.execute(() -> vault.cancelRequest(request.previewOwnerId));
            authentication.cancel(request.id);
            if (!request.exporting()) finish(request, null, new VaultException("VAULT_CANCELLED", "Operation was cancelled"));
            else worker.execute(() -> exportFailure(request, new VaultException("VAULT_CANCELLED", "Cancelled")));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        sensitiveOwners.clear(); sensitiveOwnerCount = 0;
        resumed = false;
        cancelBackgroundLock();
        vault.session.foreground(false);
        invalidateVault();
        cancelActive();
        authentication.cancel(null);
        activity.unregisterReceiver(screenOff);
        if (events != null) events.setStreamHandler(null);
        eventSink = null;
        worker.execute(this::closeWorkerAfterSshValidationCleanup);
    }

    private void closeWorkerAfterSshValidationCleanup() {
        try { vault.retryPasswordValidationCleanup(); worker.shutdown(); }
        catch (VaultException failure) {
            main.postDelayed(() -> worker.execute(this::closeWorkerAfterSshValidationCleanup), 100);
        }
    }

    private void invalidateVault() {
        // Key/epoch revocation is immediate and never waits for the file/crypto owner's monitor.
        vault.session.lock();
        worker.execute(vault::cleanupInvalidatedContexts);
        worker.execute(totpPreviews::close);
        emitState();
    }

    private interface PasswordOperation { void run(char[] first, char[] second) throws VaultException; }

    private static void withPasswords(Request request, String firstName, String secondName,
            PasswordOperation operation) throws VaultException {
        char[] first = password(request, firstName);
        char[] second = null;
        try { second = secondName == null ? null : password(request, secondName); operation.run(first, second); }
        finally { Arrays.fill(first, '\0'); if (second != null) Arrays.fill(second, '\0'); }
    }

    private static char[] password(Request request, String key) throws VaultException {
        char[] value = request.passwords.get(key);
        if (value == null || value.length == 0 || value.length > 1024) throw parameters();
        return value.clone();
    }

    private static String text(MethodCall call, String key) throws VaultException {
        Object value = call.argument(key);
        if (!(value instanceof String)) throw parameters();
        return (String) value;
    }

    private static int integer(MethodCall call, String key, int minimum) throws VaultException {
        Object value = call.argument(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw parameters();
        long seconds = ((Number) value).longValue();
        if (seconds < minimum || seconds > 86400) throw parameters();
        return (int) seconds;
    }

    private static VaultException parameters() { return new VaultException("PARAMETERS", "Invalid vault parameters"); }

    private static byte[] readBounded(InputStream stream) throws java.io.IOException, VaultException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int count;
        while ((count = stream.read(bytes)) != -1) {
            if (buffer.size() + count > VaultEnvelope.MAX_BYTES) throw new VaultException("VAULT_FORMAT", "Backup exceeds the size limit");
            buffer.write(bytes, 0, count);
        }
        return buffer.toByteArray();
    }
}
