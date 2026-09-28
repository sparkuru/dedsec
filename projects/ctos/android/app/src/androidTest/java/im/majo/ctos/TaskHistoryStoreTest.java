package im.majo.ctos;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class TaskHistoryStoreTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private File path(String name) {
        return new File(context().getCacheDir(), name + "-" + android.os.SystemClock.elapsedRealtime() + ".json");
    }

    private JSONObject result(String taskId, String state, JSONObject data) throws Exception {
        return new JSONObject().put("taskId", taskId).put("script", "device.info")
                .put("environment", "App").put("state", state).put("startedAt", 1000)
                .put("durationMs", 12).put("exitCode", "completed".equals(state) ? 0 : 1)
                .put("data", data == null ? JSONObject.NULL : data)
                .put("stdout", "not retained").put("stderr", "not retained");
    }

    @Test public void reloadClearAndKeepOnlyAllowlistedResultFields() throws Exception {
        File path = path("ctos-history-reload");
        TaskHistoryStore store = new TaskHistoryStore(path);
        try {
            assertTrue(TaskHistoryStore.shouldPersist("device.info"));
            assertTrue(TaskHistoryStore.shouldPersist("memory.snapshot"));
            assertTrue(TaskHistoryStore.shouldPersist("network.interface_diagnose"));
            assertFalse(TaskHistoryStore.shouldPersist("tools.password"));
            JSONObject data = new JSONObject().put("source", "Android API").put("capturedAt", 900)
                    .put("data", new JSONObject().put("model", "test"));
            store.appendResult(result("task-a", "completed", data), null);

            JSONArray records = new JSONObject(store.listJson()).getJSONArray("records");
            assertEquals(1, records.length());
            JSONObject saved = records.getJSONObject(0);
            assertEquals("completed", saved.getString("state"));
            assertEquals("App", saved.getString("source"));
            assertEquals("Android API", saved.getJSONObject("data").getString("source"));
            assertFalse(saved.has("stdout"));
            assertFalse(saved.has("stderr"));

            new TaskHistoryStore(path).clear();
            assertEquals(0, new JSONObject(store.listJson()).getJSONArray("records").length());
        } finally {
            store.clear();
        }
    }

    @Test public void evictsOldestAtRecordAndByteLimits() throws Exception {
        File path = path("ctos-history-cap");
        TaskHistoryStore store = new TaskHistoryStore(path);
        try {
            for (int i = 0; i < 21; i++)
                store.appendResult(result("count-" + i, "completed", new JSONObject().put("value", i)), null);
            JSONArray countLimited = new JSONObject(store.listJson()).getJSONArray("records");
            assertEquals(TaskHistoryStore.MAX_RECORDS, countLimited.length());
            assertEquals("count-1", countLimited.getJSONObject(0).getString("taskId"));

            store.clear();
            String payload = new String(new char[1_300_000]).replace('\0', 'x');
            for (int i = 0; i < 5; i++)
                store.appendResult(result("bytes-" + i, "completed", new JSONObject().put("value", payload)), null);
            JSONArray byteLimited = new JSONObject(store.listJson()).getJSONArray("records");
            assertEquals(4, byteLimited.length());
            assertEquals("bytes-1", byteLimited.getJSONObject(0).getString("taskId"));
            assertTrue(path.length() <= TaskHistoryStore.MAX_BYTES);
        } finally {
            store.clear();
        }
    }

    @Test public void preservesFailureStateAndReportsCorruptHistory() throws Exception {
        File path = path("ctos-history-error");
        TaskHistoryStore store = new TaskHistoryStore(path);
        try {
            store.appendResult(result("cancelled", "cancelled", null), null);
            store.appendResult(result("timed-out", "timed_out", null), null);
            JSONArray records = new JSONObject(store.listJson()).getJSONArray("records");
            assertEquals("cancelled", records.getJSONObject(0).getString("state"));
            assertFalse(records.getJSONObject(0).has("data"));
            assertEquals("timed_out", records.getJSONObject(1).getString("state"));

            try (FileOutputStream output = new FileOutputStream(path)) {
                output.write("{corrupt".getBytes(StandardCharsets.UTF_8));
            }
            try {
                store.listJson();
                fail("Corrupt task history must be reported");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("corrupt"));
            }
        } finally {
            store.clear();
        }
    }
}
