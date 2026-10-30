package im.majo.ctos;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local terminal generations and a bounded, non-persistent SSH replay buffer. */
final class SshTerminalState {
    static final int OUTPUT_LIMIT = 256 * 1024;
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    interface Listener { void event(Map<String, Object> event); }

    static int nextGeneration() {
        int generation = GENERATIONS.incrementAndGet();
        if (generation <= 0) throw new IllegalStateException("Terminal generation exhausted");
        return generation;
    }

    private final ArrayDeque<byte[]> output = new ArrayDeque<>();
    private int outputBytes;
    private int generation;
    private String mode;
    private String state;
    private Long exitStatus;
    private String reason;
    private boolean truncated;
    private Listener listener;
    private SshReconnectTarget reconnectTarget;

    synchronized void begin(int value, String selectedMode) {
        if (value <= generation) throw new IllegalStateException("Terminal generation regressed");
        clearOutput();
        generation = value;
        mode = selectedMode;
        state = null;
        exitStatus = null;
        reason = null;
        truncated = false;
        reconnectTarget = null;
    }

    synchronized void reconnectTarget(int expected, SshReconnectTarget target) {
        if (expected == generation) reconnectTarget = target;
    }

    synchronized void subscribe(Listener value) {
        listener = value;
        if (state != null && listener != null) listener.event(snapshot());
    }

    synchronized void unsubscribe(Listener value) {
        if (listener == value) listener = null;
    }

    synchronized void state(int expected, String value, Long exit, String failure) {
        if (expected != generation) return;
        if (state != null && !"running".equals(state) && "running".equals(value)) return;
        state = value;
        exitStatus = exit;
        reason = failure;
        if (!"running".equals(value)) clearOutput();
        publish(stateEvent("state"));
    }

    synchronized void data(int expected, byte[] bytes) {
        if (expected != generation || !"running".equals(state) || bytes.length == 0) return;
        byte[] kept = bytes.length > OUTPUT_LIMIT
                ? Arrays.copyOfRange(bytes, bytes.length - OUTPUT_LIMIT, bytes.length) : bytes.clone();
        if (bytes.length > kept.length) truncated = true;
        while (outputBytes + kept.length > OUTPUT_LIMIT) {
            byte[] dropped = output.removeFirst();
            outputBytes -= dropped.length;
            Arrays.fill(dropped, (byte) 0);
            truncated = true;
        }
        output.addLast(kept);
        outputBytes += kept.length;
        Map<String, Object> event = new HashMap<>();
        event.put("type", "data");
        event.put("generation", generation);
        event.put("bytes", bytes.clone());
        publish(event);
    }

    synchronized void focus(int expected) {
        if (expected != generation || state == null) return;
        Map<String, Object> event = new HashMap<>();
        event.put("type", "focus");
        event.put("generation", generation);
        publish(event);
    }

    synchronized int generation() { return generation; }
    synchronized int bufferedBytes() { return outputBytes; }

    synchronized Map<String, Object> snapshot() {
        if (state == null) return null;
        Map<String, Object> event = stateEvent("snapshot");
        byte[] bytes = new byte[outputBytes];
        int offset = 0;
        for (byte[] chunk : output) {
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        event.put("bytes", bytes);
        event.put("truncated", truncated);
        return event;
    }

    private Map<String, Object> stateEvent(String type) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("generation", generation);
        event.put("state", state);
        event.put("mode", mode);
        event.put("remote", true);
        if (exitStatus != null) event.put("exitStatus", exitStatus);
        if (reason != null) event.put("reason", reason);
        if ("closed".equals(state) && "SSH_SCREEN_LOCKED".equals(reason) && reconnectTarget != null)
            event.put("reconnect", reconnectTarget.wire());
        return event;
    }

    private void publish(Map<String, Object> event) {
        if (listener != null) listener.event(event);
    }

    private void clearOutput() {
        for (byte[] bytes : output) Arrays.fill(bytes, (byte) 0);
        output.clear();
        outputBytes = 0;
    }
}
