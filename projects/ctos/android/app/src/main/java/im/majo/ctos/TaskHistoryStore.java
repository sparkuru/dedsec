package im.majo.ctos;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Stores a bounded history for the small allowlist of read-only App tasks. */
public final class TaskHistoryStore {
    public static final int MAX_RECORDS = 20;
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> ALLOWED_SCRIPTS = new HashSet<>(Arrays.asList(
            "device.info", "memory.snapshot", "network.interface_diagnose"));

    private final AtomicFile file;

    public TaskHistoryStore(Context context) {
        this(new File(context.getFilesDir(), "task-history.json"));
    }

    TaskHistoryStore(File path) {
        file = new AtomicFile(path);
    }

    public static boolean shouldPersist(String script) {
        return ALLOWED_SCRIPTS.contains(script);
    }

    public synchronized String listJson() throws Exception {
        return document(readRecords()).toString();
    }

    public synchronized void clear() throws IOException {
        file.delete();
    }

    public synchronized void appendResult(JSONObject result, String interfaceName) throws Exception {
        String script = result.optString("script", "");
        if (!shouldPersist(script)) return;
        String taskId = result.optString("taskId", "");
        if (!taskId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("Task result has an invalid task ID");

        JSONObject record = new JSONObject()
                .put("taskId", taskId)
                .put("script", script)
                .put("source", "App")
                .put("state", result.optString("state", "failed"))
                .put("startedAt", result.optLong("startedAt", System.currentTimeMillis()))
                .put("savedAt", System.currentTimeMillis());
        if (result.has("durationMs")) record.put("durationMs", result.get("durationMs"));
        if (result.has("exitCode")) record.put("exitCode", result.get("exitCode"));
        if ("network.interface_diagnose".equals(script) && interfaceName != null
                && interfaceName.matches("[A-Za-z0-9_.:-]{1,64}"))
            record.put("interfaceName", interfaceName);

        String state = record.getString("state");
        if (!Arrays.asList("completed", "failed", "cancelled", "timed_out").contains(state))
            throw new IOException("Task result has an unsupported state");
        if ("completed".equals(state) && result.opt("data") instanceof JSONObject)
            record.put("data", result.getJSONObject("data"));

        JSONArray records = readRecords();
        records.put(record);
        while (records.length() > MAX_RECORDS || encodedSize(document(records)) > MAX_BYTES) {
            if (records.length() <= 1)
                throw new IOException("Task history record exceeds the 5 MiB storage limit");
            records.remove(0);
        }
        write(document(records));
    }

    private JSONArray readRecords() throws IOException {
        byte[] bytes;
        try (InputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IOException("Task history file exceeds 5 MiB");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } catch (FileNotFoundException missing) {
            return new JSONArray();
        }

        try {
            JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.optInt("schemaVersion", -1) != SCHEMA_VERSION)
                throw new IOException("Unsupported task history schema version");
            JSONArray records = parsed.optJSONArray("records");
            if (records == null || records.length() > MAX_RECORDS)
                throw new IOException("Task history records are invalid");
            for (int i = 0; i < records.length(); i++) {
                if (!(records.opt(i) instanceof JSONObject))
                    throw new IOException("Task history contains an invalid record");
            }
            return records;
        } catch (JSONException invalid) {
            throw new IOException("Task history file is corrupt", invalid);
        }
    }

    private JSONObject document(JSONArray records) throws IOException {
        try {
            return new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("records", records);
        } catch (JSONException invalid) {
            throw new IOException("Cannot encode task history", invalid);
        }
    }

    private int encodedSize(JSONObject document) throws JSONException {
        return document.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private void write(JSONObject document) throws IOException, JSONException {
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
    }
}
