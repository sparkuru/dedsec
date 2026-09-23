package im.majo.ctos;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** One explicitly authorized shell for fixed collector commands, never auto-reopened. */
final class RootSession implements AutoCloseable {
    private final Process process;
    private final BufferedReader output;
    private final OutputStreamWriter input;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    RootSession() throws Exception {
        process = new ProcessBuilder("su").redirectErrorStream(true).start();
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    synchronized JSONObject command(String command, int timeoutSeconds) throws Exception {
        if (closed || !process.isAlive()) throw new IllegalStateException("Root session closed; authorize Root again");
        String marker = "CTOS_" + UUID.randomUUID().toString().replace("-", "") + ":";
        // A subshell isolates exit/status and shell state from the reusable session.
        input.write("( " + command + "\n); printf '\\n" + marker + "%s\\n' \"$?\"\n");
        input.flush();
        Future<JSONObject> result = reader.submit(() -> {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = output.readLine()) != null) {
                if (line.startsWith(marker)) return new JSONObject().put("output", text.toString())
                        .put("exit", Integer.parseInt(line.substring(marker.length())))
                        .put("timeout", false);
                int remaining = 1024 * 1024 - text.length();
                if (remaining > 0) text.append((line + "\n"), 0, Math.min(remaining, line.length() + 1));
            }
            throw new IllegalStateException("Root shell exited; authorize Root again");
        });
        try { return result.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (Exception error) { close(); throw error; }
    }

    @Override public void close() {
        closed = true;
        process.destroyForcibly();
        reader.shutdownNow();
    }
}
