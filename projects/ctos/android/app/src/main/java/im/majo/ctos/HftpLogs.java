package im.majo.ctos;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Process-local diagnostic lines. Only the current service session may append. */
final class HftpLogs {
    private static final int MAX_LINES = 200, MAX_BYTES = 32768, MAX_LINE = 512;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private Object owner;

    synchronized void begin(Object session) { owner = session; lines.clear(); }
    synchronized boolean owns(Object session) { return owner == session; }
    synchronized void clear() { lines.clear(); }
    synchronized JSONArray snapshot() { return new JSONArray(lines); }

    synchronized void append(Object session, String level, String message) {
        if (owner != session) return;
        String severity = "ERROR".equals(level) ? "ERROR" : "WARN".equals(level) ? "WARN" : "INFO";
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        lines.addLast(clean(timestamp + " [" + severity + "] " + message, MAX_LINE));
        while (lines.size() > MAX_LINES || snapshot().toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            lines.removeFirst();
    }

    synchronized void event(Object session, JSONObject event) {
        if (owner != session || !"log".equals(event.optString("type"))) return;
        String kind = event.optString("event");
        String remote = event.optString("remote");
        if (!remote.matches("[0-9a-fA-F:.]{1,45}")) remote = "unknown";
        String method = event.optString("method");
        if (!method.matches("GET|PUT|HEAD|POST|DELETE|OPTIONS|PATCH")) method = "OTHER";
        String path = event.optString("path", "/").split("[?#]", 2)[0];
        // A protocol field is a relative HTTP path, never an arbitrary process message.
        if (!path.startsWith("/") || path.startsWith("//")) path = "/[invalid]";
        path = clean(path, 240);
        String request = remote + " " + method + " " + path;
        int status = event.optInt("status", 0);
        long bytes = event.optLong("bytes", -1);
        switch (kind) {
            case "connection": append(session, "INFO", "Connection from " + remote); break;
            case "capacity": append(session, "WARN", "Connection rejected: concurrency limit; " + remote); break;
            case "request": append(session, "INFO", "Request " + request); break;
            case "response":
                if (status >= 100 && status <= 599) append(session, status >= 400 ? "WARN" : "INFO", request + " -> " + status);
                break;
            case "upload": case "download": case "mkdir":
                if (bytes >= 0 && bytes <= 1073741824L) append(session, "INFO", kind + " complete: " + request + " (" + bytes + " bytes)");
                break;
            case "error":
                String error = event.optString("error");
                if (!error.matches("[A-Za-z][A-Za-z0-9]{0,47}")) error = "ServiceError";
                append(session, "ERROR", request + " failed: " + error);
                break;
            default: break; // Unknown events and traceback text are never copied into diagnostics.
        }
    }

    private static String clean(String value, int limit) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length() && result.length() < limit;) {
            int character = value.codePointAt(offset);
            offset += Character.charCount(character);
            int category = Character.getType(character);
            if (Character.isISOControl(character) || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR || category == Character.PARAGRAPH_SEPARATOR)
                result.append('?');
            else if (result.length() + Character.charCount(character) <= limit) result.appendCodePoint(character);
        }
        return result.toString();
    }
}
