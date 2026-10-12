package im.majo.ctos;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One bounded no-backup metadata journal; loading never accesses a provider. */
final class VaultExportJournal implements VaultExportTransaction.Journal {
    private final AtomicFile file;
    VaultExportJournal(File directory) { file = new AtomicFile(new File(directory, "vault-export-journal.json")); }
    VaultExportJournal(AtomicFile isolatedFile) { file = isolatedFile; }
    @Override public synchronized void save(VaultExportTransaction.State state) throws Exception {
        JSONObject value = new JSONObject();
        value.put("id", state.id); value.put("tree", state.tree); value.put("location", state.location);
        value.put("name", state.name); value.put("phase", state.phase); value.put("digest", state.digest); value.put("length", state.length);
        value.put("pendingRole", state.pendingRole); value.put("pendingName", state.pendingName);
        value.put("finalVerified", state.finalVerified); value.put("oldRenameStarted", state.oldRenameStarted);
        value.put("stage", encode(state.stage)); value.put("original", encode(state.original));
        value.put("rollback", encode(state.rollback)); value.put("finalFile", encode(state.finalFile));
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32768) throw new java.io.IOException("Export journal exceeds bounds");
        FileOutputStream output = file.startWrite();
        boolean finishCalled = false;
        try {
            output.write(bytes); output.flush(); output.getFD().sync(); finishCalled = true; file.finishWrite(output);
            byte[] stored = readBounded();
            if (!java.util.Arrays.equals(bytes, stored)) throw new java.io.IOException("Export journal persistence was not confirmed");
        }
        catch (Exception error) { if (!finishCalled) file.failWrite(output); throw error; }
    }
    synchronized VaultExportTransaction.State load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            if (new File(file.getBaseFile().getPath() + ".new").exists()) throw new java.io.IOException("Unconfirmed export journal");
            return null;
        }
        if (file.getBaseFile().length() > 32768) throw new java.io.IOException("Invalid export journal");
        byte[] bytes = readBounded();
        if (bytes.length > 32768) throw new java.io.IOException("Invalid export journal");
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        VaultExportTransaction.State state = new VaultExportTransaction.State();
        state.id = text(value, "id", 128); state.tree = text(value, "tree", 4096);
        state.location = text(value, "location", 1024); state.name = text(value, "name", 128);
        state.phase = text(value, "phase", 64); state.digest = text(value, "digest", 64); state.length = value.getInt("length");
        if (state.length < 0 || state.length > VaultEnvelope.MAX_BYTES) throw new java.io.IOException("Invalid export journal");
        if (!state.id.matches("[a-f0-9]{32}") || !state.digest.matches("[a-f0-9]{64}")) throw new java.io.IOException("Invalid export journal");
        VaultExportTransaction.validateName(state.name);
        state.pendingRole = value.has("pendingRole") ? text(value, "pendingRole", 64) : null;
        state.pendingName = value.has("pendingName") ? text(value, "pendingName", 256) : null;
        state.finalVerified = value.getBoolean("finalVerified"); state.oldRenameStarted = value.getBoolean("oldRenameStarted");
        state.stage = decode(value.optJSONObject("stage")); state.original = decode(value.optJSONObject("original"));
        state.rollback = decode(value.optJSONObject("rollback")); state.finalFile = decode(value.optJSONObject("finalFile"));
        if (!java.util.Arrays.asList("preparing", "prepared", "renaming_old", "creating_final", "final_verified", "postcleanup", "compensated").contains(state.phase)
                || (state.pendingRole != null && !java.util.Arrays.asList("stage", "final", "renamed_old").contains(state.pendingRole))
                || (state.pendingRole == null) != (state.pendingName == null) || (state.finalVerified && state.finalFile == null)) throw new java.io.IOException("Invalid export journal roles");
        java.util.Set<String> owned = new java.util.HashSet<>();
        for (VaultExportTransaction.Document doc : new VaultExportTransaction.Document[] {state.stage, state.finalFile}) {
            if (doc != null && (!owned.add(doc.id) || doc.directory
                    || (state.original != null && doc.id.equals(state.original.id)
                        && (doc != state.finalFile || !historicOriginalIdReusedByFinal(state)))
                    || (state.rollback != null && doc.id.equals(state.rollback.id)))) throw new java.io.IOException("Invalid export journal ownership");
        }
        return state;
    }
    private static boolean historicOriginalIdReusedByFinal(VaultExportTransaction.State state) {
        VaultExportTransaction.Document old = state.original, rollback = state.rollback, created = state.finalFile;
        // Path-based providers can reuse the old path's ID after rename. The historic original
        // is not a current role: require a recorded, distinct rollback before admitting that reuse.
        if (!state.oldRenameStarted || old == null || rollback == null || created == null
                || old.directory || rollback.directory || created.directory
                || old.id.equals(rollback.id) || created.id.equals(rollback.id)
                || !old.name.equals(state.name) || !created.name.equals(state.name)
                || !rollback.name.matches("\\.ctos-rollback-[a-f0-9]{32}\\.ctvault")
                || !old.renamable || !old.deletable
                || old.bytes == null || old.bytes < 0 || rollback.bytes == null || !old.bytes.equals(rollback.bytes)
                || old.modified == null || old.modified < 0 || rollback.modified == null || rollback.modified < 0
                || state.pendingRole != null) return false;
        // Compensation can retain a failed-to-delete final plus the still distinct rollback.
        // Once the old file is restored, the engine must retire the deleted final role instead.
        return state.finalVerified
                ? java.util.Arrays.asList("final_verified", "postcleanup").contains(state.phase)
                : java.util.Arrays.asList("creating_final", "compensated").contains(state.phase);
    }
    private byte[] readBounded() throws Exception {
        try (java.io.InputStream input = file.openRead(); java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0 || result.size() + read > 32768) throw new java.io.IOException("Invalid export journal size");
                result.write(buffer, 0, read);
            }
            return result.toByteArray();
        }
    }
    @Override public synchronized void clear() throws Exception {
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()
                || new File(file.getBaseFile().getPath() + ".new").exists()) throw new java.io.IOException("Export journal deletion failed");
    }
    private static JSONObject encode(VaultExportTransaction.Document document) throws Exception {
        if (document == null) return null;
        JSONObject value = new JSONObject(); value.put("id", document.id); value.put("name", document.name); value.put("location", document.location);
        value.put("bytes", document.bytes); value.put("modified", document.modified); value.put("writable", document.writable);
        value.put("deletable", document.deletable); value.put("renamable", document.renamable); value.put("directory", document.directory); return value;
    }
    private static VaultExportTransaction.Document decode(JSONObject value) throws Exception {
        if (value == null) return null;
        return new VaultExportTransaction.Document(text(value, "id", 2048), text(value, "name", 256), text(value, "location", 1536),
                value.has("bytes") ? value.getLong("bytes") : null, value.has("modified") ? value.getLong("modified") : null,
                value.getBoolean("writable"), value.getBoolean("deletable"), value.getBoolean("renamable"), value.getBoolean("directory"));
    }
    private static String text(JSONObject value, String key, int limit) throws Exception {
        String result = value.getString(key);
        if (result.length() > limit) throw new java.io.IOException("Invalid export journal"); return result;
    }
}
