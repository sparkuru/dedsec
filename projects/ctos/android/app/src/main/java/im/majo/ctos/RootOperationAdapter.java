package im.majo.ctos;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owned byte relay only; Python and authorized document I/O keep the App UID. */
final class RootOperationAdapter implements AutoCloseable {
    static final String CPU_WAKE_LOCK_TAG = "im.majo.ctos:RootHftpRelay";
    private static final long CPU_WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(5);

    static final class CpuLease implements AutoCloseable {
        private final PowerManager.WakeLock lock;
        private final BiConsumer<String, String> log;
        private boolean closed;

        CpuLease(PowerManager.WakeLock lock, BiConsumer<String, String> log) {
            this.lock = lock; this.log = log;
            lock.setReferenceCounted(false);
            try {
                lock.acquire(CPU_WAKE_LOCK_TIMEOUT_MS);
                log.accept("INFO", "Root relay CPU wake lock acquired; maximum five hours");
            } catch (RuntimeException | Error error) {
                if (lock.isHeld()) lock.release();
                throw error;
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (lock.isHeld()) lock.release();
            safeLog(log, "INFO", "Root relay CPU wake lock released");
        }
    }

    static final class WifiTarget {
        final Network network;
        final String address;
        final int prefix;

        WifiTarget(Network network, String address, int prefix) {
            this.network = network; this.address = address; this.prefix = prefix;
        }

        static WifiTarget select(Context context) throws RelayException {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                LinkProperties properties = manager.getLinkProperties(network);
                if (capabilities == null || properties == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()
                            && !link.getAddress().isLinkLocalAddress() && link.getPrefixLength() >= 1 && link.getPrefixLength() <= 30)
                        return new WifiTarget(network, link.getAddress().getHostAddress(), link.getPrefixLength());
                }
            }
            throw new RelayException("Root relay needs an available Wi-Fi IPv4 network");
        }

        boolean present(LinkProperties properties) {
            if (properties == null) return false;
            for (LinkAddress link : properties.getLinkAddresses())
                if (address.equals(link.getAddress().getHostAddress()) && prefix == link.getPrefixLength()) return true;
            return false;
        }
    }

    static final class RelayException extends java.io.IOException {
        RelayException(String reason) { super(reason); }
    }

    private final Process process;
    private final WifiTarget target;
    private final int port, backendPort;
    private final BiConsumer<String, String> log;
    private final Consumer<String> failure;
    private final ScheduledExecutorService heartbeat;
    private final CpuLease cpuLease;
    private volatile boolean closed;

    RootOperationAdapter(Context context, ToolExecutionContext execution, WifiTarget target, int port, int backendPort,
                  BiConsumer<String, String> log, Consumer<String> failure) throws Exception {
        if (execution == null) throw new IllegalArgumentException("A Root operation declaration is required");
        execution.requireRootRelay();
        if (target == null || target.network == null || !validIpv4(target.address) || target.prefix < 1 || target.prefix > 30
                || target.network.getNetworkHandle() == 0 || port < 1024 || port > 65535 || backendPort < 1024 || backendPort > 65535)
            throw new RelayException("Invalid Root relay network parameters");
        this.target = target; this.port = port; this.backendPort = backendPort; this.log = log; this.failure = failure;
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libctos_hftp_relay.so");
        if (!executable.isFile() || !executable.canExecute()) throw new RelayException("Bundled Root relay is unavailable");
        String command = "exec " + quote(executable.getAbsolutePath()) + " " + target.address + " " + target.prefix
                + " " + port + " " + backendPort + " " + Long.toUnsignedString(target.network.getNetworkHandle())
                + " " + android.os.Process.myPid();
        PowerManager power = context.getApplicationContext().getSystemService(PowerManager.class);
        if (power == null) throw new RelayException("App CPU wake capability is unavailable");
        cpuLease = new CpuLease(power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_WAKE_LOCK_TAG), log);
        Process started = null;
        ScheduledExecutorService scheduled = null;
        try {
            try { started = new ProcessBuilder("su", "-c", command).start(); }
            catch (java.io.IOException missing) { throw new RelayException("Root network capability is unavailable; check Root authorization"); }
            process = started;
            scheduled = Executors.newSingleThreadScheduledExecutor();
            heartbeat = scheduled;
            Thread errors = new Thread(this::drainErrors, "ctos-hftp-relay-stderr");
            errors.setDaemon(true); errors.start();
            heartbeat.scheduleWithFixedDelay(this::ping, 0, 2, TimeUnit.SECONDS);
        } catch (Exception | Error error) {
            closed = true;
            try {
                try { if (scheduled != null) scheduled.shutdownNow(); }
                finally { if (started != null) stopProcess(started); }
            } finally { cpuLease.close(); }
            throw error;
        }
    }

    void awaitReady() throws Exception {
        try { readReady(); }
        catch (Exception | Error error) { close(); throw error; }
    }

    private void readReady() throws Exception {
        String first = readLine(process.getInputStream());
        if (first == null) throw new RelayException("Root relay did not become ready; check Root authorization");
        JSONObject ready;
        try { ready = new JSONObject(first); }
        catch (org.json.JSONException invalid) { throw new RelayException("Root relay returned an invalid readiness response"); }
        if (!"ready".equals(ready.optString("state"))) throw new RelayException("Root relay startup failed: " + reason(ready));
        if (!target.address.equals(ready.optString("host")) || ready.optInt("port") != port
                || ready.optInt("backendPort") != backendPort || ready.optLong("pid") <= 1 || ready.optInt("uid", -1) != 0)
            throw new RelayException("Root relay readiness did not match the selected network");
        if (closed) throw new RelayException("Root relay stopped during startup");
        log.accept("INFO", "Root Wi-Fi relay ready; " + target.address + ":" + port + " -> App 127.0.0.1:" + backendPort);
        Thread events = new Thread(this::consume, "ctos-hftp-relay-events");
        events.setDaemon(true); events.start();
    }

    private void ping() {
        boolean failed = false;
        synchronized (this) {
            if (closed) return;
            try {
                process.getOutputStream().write("PING\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
            } catch (java.io.IOException error) { failed = true; }
        }
        if (failed && !closed) reportFailure("Root relay control connection closed");
    }

    private void consume() {
        try {
            String line;
            while (!closed && (line = readLine(process.getInputStream())) != null) {
                JSONObject event;
                try { event = new JSONObject(line); }
                catch (org.json.JSONException invalid) { safeLog(log, "WARN", "Unrecognized Root relay output suppressed"); continue; }
                if (!"relay".equals(event.optString("event"))) continue;
                String kind = event.optString("kind");
                if (!kind.matches("accepted|closed|rejected|error|stopped")) continue;
                String client = event.optString("client");
                String peer = client.matches("[0-9.]{7,15}") ? " client=" + client : "";
                String detail = "closed".equals(kind) || "error".equals(kind) || "rejected".equals(kind)
                        || "stopped".equals(kind) ? " reason=" + reason(event) : "";
                safeLog(log, "error".equals(kind) ? "ERROR" : "rejected".equals(kind) ? "WARN" : "INFO", "Root relay " + kind + peer + detail);
            }
            int exit = process.waitFor();
            if (!closed) reportFailure("Root relay process exited (" + exit + ")");
        } catch (Exception error) { if (!closed) reportFailure("Root relay diagnostics failed: " + error.getClass().getSimpleName()); }
    }

    private void reportFailure(String reason) {
        if (closed) return;
        try { failure.accept(reason); }
        catch (RuntimeException error) { safeLog(log, "WARN", "Root relay failure callback rejected"); }
        finally { close(); }
    }

    private static void safeLog(BiConsumer<String, String> logger, String level, String message) {
        try { logger.accept(level, message); }
        catch (RuntimeException ignored) { /* Diagnostics must not interrupt owned cleanup or stream draining. */ }
    }

    private void drainErrors() {
        boolean reported = false;
        try (InputStream input = process.getErrorStream()) {
            byte[] bytes = new byte[2048];
            while (input.read(bytes) != -1) {
                if (!reported && !closed) safeLog(log, "WARN", "Root relay stderr output suppressed");
                reported = true;
            }
        } catch (java.io.IOException ignored) { /* Closing the owned process closes these streams. */ }
    }

    private static String reason(JSONObject value) {
        String label = value.optString("reason");
        if (!label.matches("root_required|invalid_arguments|invalid_address|signal_setup|control_setup|backend_unavailable|listener_socket|network_binding|listener_reuse|listener_bind|ready_output|idle_timeout|invalid_socket|backend_connect|socket_error|socket_io|complete|service_stop|accept_failed|outside_subnet|concurrency_limit|signal|clock_failed|heartbeat_timeout|session_timeout|owner_gone|poll_failed|control_eof|control_failed|requested|invalid_control|listener_failed")) label = "relay_error";
        int error = value.optInt("errno", 0);
        return label + (error > 0 && error <= 4096 ? " (errno " + error + ")" : "");
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static boolean validIpv4(String value) {
        if (value == null || !value.matches("[0-9.]{7,15}")) return false;
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= 2048) throw new RelayException("Root relay response exceeded its bound");
            bytes.write(value);
        }
        return value == -1 && bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8.name());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try { heartbeat.shutdownNow(); }
            finally { stopProcess(process); }
        } finally { cpuLease.close(); }
    }

    private static void stopProcess(Process process) {
        try {
            process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
        } catch (java.io.IOException ignored) { /* The native EOF/watchdog also closes an abandoned relay. */ }
        try { process.getOutputStream().close(); } catch (java.io.IOException ignored) { }
        try {
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
