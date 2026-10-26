package im.majo.ctos;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict public foreground form bridge; the actual terminal owner performs all connection I/O. */
final class SshPasswordConnectBridge implements AutoCloseable {
    interface Attacher {
        java.util.concurrent.CompletableFuture<String> attach(MainActivity.AuthenticatedSshFactory factory,
                String mode, int columns, int rows);
    }
    private final AppSecretsBridge secrets;
    private final AppSecrets vault;
    private final Attacher attacher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService defaultsWorker = Executors.newSingleThreadScheduledExecutor();
    private boolean cleanupQueued;
    private final EventChannel channel;
    private EventChannel.EventSink sink;
    private volatile boolean closed;
    private volatile Defaults idle;
    private Attempt active;
    private final Set<String> requests = new HashSet<>();

    private static final class Defaults {
        final String id;
        final long epoch, revision, deadline;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile boolean loading = true;
        Defaults(String id, AppSecrets vault) {
            this.id = id; epoch = vault.session.epoch(); revision = vault.contentRevision;
            deadline = SystemClock.elapsedRealtime() + 60_000;
        }
    }
    private static final class Attempt {
        final String id;
        final SshPasswordConnector connector;
        final MethodChannel.Result result;
        boolean finished;
        Attempt(String id, SshPasswordConnector connector, MethodChannel.Result result) {
            this.id = id; this.connector = connector; this.result = result;
        }
    }

    SshPasswordConnectBridge(AppSecretsBridge secrets, BinaryMessenger messenger, Attacher attacher) {
        this.secrets = secrets; vault = secrets.sshVault(); this.attacher = attacher;
        channel = new EventChannel(messenger, "ctos/ssh");
        channel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override public void onListen(Object arguments, EventChannel.EventSink events) { sink = events; }
            @Override public void onCancel(Object arguments) {
                sink = null; retireIdle();
                if (active != null) active.connector.cancel();
            }
        });
    }

    boolean handle(MethodCall call, MethodChannel.Result result) {
        if (!call.method.startsWith("ssh.")) return false;
        try {
            if (closed) throw failure("SSH_CANCELLED");
            switch (call.method) {
                case "ssh.defaults": defaults(call, result); break;
                case "ssh.passwordConnect": connect(call, result); break;
                case "ssh.cancel": {
                    exact(call, "requestId"); String id = id(call);
                    boolean cancelled = false;
                    if (idle != null && idle.id.equals(id)) { retireIdle(); cancelled = true; }
                    if (active != null && active.id.equals(id)) { active.connector.cancel(); cancelled = true; }
                    result.success(VaultCollections.map("cancelled", cancelled)); break;
                }
                case "ssh.hostKeyDecision": {
                    exact(call, "requestId", "ticketId", "binding", "accept");
                    String id = id(call), ticket = text(call, "ticketId"), binding = text(call, "binding");
                    Object accept = call.argument("accept");
                    if (!ticket.matches("[A-Za-z0-9_-]{1,128}") || !binding.matches("[0-9a-f]{64}")
                            || !(accept instanceof Boolean)) throw parameters();
                    boolean accepted = false;
                    if (active != null && active.id.equals(id)) {
                        secrets.requireSshConsumer();
                        accepted = active.connector.decide(ticket, binding, (Boolean)accept);
                    }
                    result.success(VaultCollections.map("accepted", accepted)); break;
                }
                default: throw parameters();
            }
        } catch (VaultException error) { error(result, error.code); }
        catch (RuntimeException | LinkageError error) { error(result, "SSH_FAILED"); }
        return true;
    }

    private void defaults(MethodCall call, MethodChannel.Result result) throws VaultException {
        exact(call, "requestId"); String id = id(call);
        secrets.requireSshConsumer();
        if (active != null || idle != null && idle.loading) throw failure("BUSY");
        admit(id);
        retireIdle();
        Defaults request = new Defaults(id, vault); idle = request;
        AppSecrets.BundleContext context = vault.newBundleContext(request.deadline, () -> checkIdle(request, true));
        defaultsWorker.execute(() -> {
            SshPasswordProfile profile = null;
            String code = null;
            try {
                profile = SshPasswordManifestValidator.within(request.deadline, () -> {
                    try (SshPasswordProfile.Snapshot snapshot = vault.passwordProfile(context)) { return snapshot.profile; }
                });
            } catch (Throwable failure) { code = code(failure); }
            finally { vault.cancelBundleContext(context); }
            SshPasswordProfile value = profile; String failed = code;
            main.post(() -> {
                request.loading = false;
                if (failed != null) {
                    if (idle == request) retireIdle(); error(result, failed); return;
                }
                try {
                    checkIdle(request, true);
                    result.success(VaultCollections.map("host", value == null ? "" : value.host,
                            "port", value == null ? 22 : value.port, "username", value == null ? "" : value.username));
                    main.postDelayed(() -> monitor(request), 100);
                } catch (VaultException failure) {
                    if (idle == request) retireIdle(); error(result, failure.code);
                }
            });
            finishDefaultsCleanup();
        });
    }

    private void connect(MethodCall call, MethodChannel.Result result) throws VaultException {
        exact(call, "requestId", "host", "port", "username", "password", "columns", "rows");
        String id = id(call);
        secrets.requireSshConsumer();
        if (active != null || idle != null && idle.loading) throw failure("BUSY");
        if (idle != null && idle.id.equals(id)) throw parameters();
        if (sink == null) throw failure("SSH_CANCELLED");
        SshPasswordProfile profile = new SshPasswordProfile(text(call, "host"), integer(call, "port", 1, 65535),
                text(call, "username"));
        int columns = integer(call, "columns", 1, 4096), rows = integer(call, "rows", 1, 4096);
        String supplied = text(call, "password");
        if (supplied.length() == 0 || supplied.length() > 8192) throw parameters();
        for (int index = 0; index < supplied.length(); index++) {
            char value = supplied.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= supplied.length() || !Character.isLowSurrogate(supplied.charAt(index))) throw parameters();
            } else if (Character.isLowSurrogate(value)) throw parameters();
        }
        byte[] password = supplied.getBytes(StandardCharsets.UTF_8);
        SshPasswordConnector connector;
        try {
            admit(id);
            retireIdle();
            connector = new SshPasswordConnector(vault, profile, password, SystemClock.elapsedRealtime() + 60_000,
                    () -> { if (closed) throw failure("SSH_CANCELLED"); secrets.requireSshConsumer(); },
                    new SshPasswordConnector.Events() {
                        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) throws VaultException {
                            if (closed) throw failure("SSH_CANCELLED");
                            main.post(() -> {
                                if (active == null || !active.id.equals(id) || sink == null) return;
                                long remaining = ticket.deadline - SystemClock.elapsedRealtime();
                                if (remaining <= 0) { active.connector.cancel(); return; }
                                state(id, "reviewing", null);
                                emit(VaultCollections.map("type", "hostKey", "requestId", id, "ticketId", ticket.id,
                                        "binding", ticket.binding, "host", ticket.host, "port", ticket.port,
                                        "keyType", ticket.keyType, "fingerprint", ticket.fingerprint,
                                        "remainingMillis", Math.min(30_000L, remaining)));
                            });
                        }
                        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) {
                            main.post(() -> emit(VaultCollections.map("type", "hostKeyDismissed", "requestId", id,
                                    "ticketId", ticket.id)));
                        }
                    });
        } finally { VaultCodec.wipe(password); }
        Attempt attempt = new Attempt(id, connector, result); active = attempt;
        state(id, "connecting", null);
        try {
            attacher.attach(connector, "ssh", columns, rows).whenComplete((mode, failure) -> main.post(() -> {
                if (attempt.finished) return;
                attempt.finished = true;
                if (active == attempt) active = null;
                String completionFailure = failure == null ? null : code(failure);
                if (completionFailure == null) {
                    try {
                        if (closed || !connector.handoffLive()) throw failure("SSH_CANCELLED");
                        secrets.requireSshConsumer();
                    } catch (VaultException invalidated) {
                        connector.cancel(); completionFailure = invalidated.code;
                    }
                }
                if (completionFailure == null) {
                    state(id, "connected", null);
                    result.success(VaultCollections.map("requestId", id, "status", "connected"));
                } else {
                    // Uninvoked factories have no native owner/resources; wipe their source immediately.
                    if (!connector.started()) connector.cancel();
                    String reason = completionFailure;
                    state(id, isCancelled(reason) ? "cancelled" : "failed", reason);
                    error(result, reason);
                }
            }));
        } catch (RuntimeException failure) {
            active = null; connector.cancel(); error(result, "SSH_FAILED"); state(id, "failed", "SSH_FAILED");
        }
    }

    private void monitor(Defaults request) {
        if (idle != request || closed) return;
        try { checkIdle(request, false); main.postDelayed(() -> monitor(request), 100); }
        catch (VaultException failure) {
            retireIdle(); state(request.id, "cancelled", failure.code);
        }
    }
    private void checkIdle(Defaults request, boolean bounded) throws VaultException {
        if (closed || idle != request || request.cancelled.get()) throw failure("SSH_CANCELLED");
        secrets.requireSshConsumer();
        vault.session.assertEpoch(request.epoch);
        if (vault.contentRevision != request.revision) throw failure("VAULT_CHANGED");
        if (bounded && SystemClock.elapsedRealtime() >= request.deadline) throw failure("SSH_TIMED_OUT");
    }
    private void retireIdle() {
        Defaults previous = idle; idle = null;
        if (previous != null) previous.cancelled.set(true);
    }
    private void finishDefaultsCleanup() {
        try {
            vault.retryPasswordValidationCleanup();
            if (closed) defaultsWorker.shutdown();
        } catch (VaultException failure) {
            if (!cleanupQueued) {
                cleanupQueued = true;
                defaultsWorker.schedule(() -> { cleanupQueued = false; finishDefaultsCleanup(); }, 100, TimeUnit.MILLISECONDS);
            }
        }
    }
    private void state(String id, String state, String reason) {
        emit(reason == null ? VaultCollections.map("type", "state", "requestId", id, "state", state)
                : VaultCollections.map("type", "state", "requestId", id, "state", state, "reason", reason));
    }
    private void emit(Map<String, Object> event) { if (!closed && sink != null) sink.success(event); }
    private static String code(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; i < 4 && current != null; i++, current = current.getCause())
            if (current instanceof VaultException) return ((VaultException)current).code;
        return "SSH_FAILED";
    }
    private static boolean isCancelled(String code) { return code.contains("CANCELLED"); }
    private void admit(String id) throws VaultException {
        if (requests.size() >= 4096 || !requests.add(id)) throw parameters();
    }
    private static void error(MethodChannel.Result result, String code) {
        result.error(code, "The SSH connection could not continue", null);
    }
    private static void exact(MethodCall call, String... fields) throws VaultException {
        if (!(call.arguments instanceof Map)) throw parameters();
        Set<String> expected = new HashSet<>(Arrays.asList(fields));
        if (!((Map<?, ?>)call.arguments).keySet().equals(expected)) throw parameters();
    }
    private static String id(MethodCall call) throws VaultException {
        String id = text(call, "requestId");
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw parameters();
        return id;
    }
    private static String text(MethodCall call, String key) throws VaultException {
        Object value = call.argument(key); if (!(value instanceof String)) throw parameters(); return (String)value;
    }
    private static int integer(MethodCall call, String key, int minimum, int maximum) throws VaultException {
        Object value = call.argument(key);
        if (!(value instanceof Integer) || (Integer)value < minimum || (Integer)value > maximum) throw parameters();
        return (Integer)value;
    }
    private static VaultException parameters() { return failure("PARAMETERS"); }
    private static VaultException failure(String code) { return new VaultException(code, "The SSH request is unavailable"); }
    @Override public void close() {
        if (closed) return;
        closed = true; retireIdle();
        if (active != null) active.connector.cancel();
        channel.setStreamHandler(null); sink = null; defaultsWorker.execute(this::finishDefaultsCleanup);
    }
}
