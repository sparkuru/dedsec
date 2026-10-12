package im.majo.ctos;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compensating export for conforming document providers. Never opens an existing document for write. */
final class VaultExportTransaction {
    static final int MAX_CHILDREN = 1024;
    interface Guard { void check() throws Exception; default void finalVerified() throws Exception { check(); } }
    interface Provider {
        String tree();
        String location();
        boolean canCreate();
        List<Document> children() throws Exception;
        Document create(String name) throws Exception;
        Document rename(Document document, String name) throws Exception;
        void delete(Document document) throws Exception;
        InputStream read(Document document) throws Exception;
        OutputStream write(Document document) throws Exception;
    }
    interface Journal { void save(State state) throws Exception; void clear() throws Exception; }
    static final class Document {
        final String id, name, location;
        final Long bytes, modified;
        final boolean writable, deletable, renamable, directory;
        Document(String id, String name, String location, Long bytes, Long modified,
                boolean writable, boolean deletable, boolean renamable, boolean directory) {
            this.id = id; this.name = name; this.location = location;
            this.bytes = bytes; this.modified = modified; this.writable = writable;
            this.deletable = deletable; this.renamable = renamable; this.directory = directory;
        }
        boolean sameVersion(Document other) {
            return other != null && id.equals(other.id) && name.equals(other.name)
                    && java.util.Objects.equals(bytes, other.bytes) && java.util.Objects.equals(modified, other.modified)
                    && writable == other.writable && deletable == other.deletable && renamable == other.renamable
                    && directory == other.directory;
        }
    }
    static final class State {
        String id = random(), tree, location, name, phase = "preparing", pendingRole, pendingName;
        Document stage, original, rollback, finalFile;
        String digest;
        int length;
        boolean finalVerified, oldRenameStarted;
    }
    final Provider provider;
    final Journal journal;
    final State state;
    private byte[] snapshot;
    private boolean commitRecorded;
    private final boolean recovering;
    private Map<String, Object> terminalReceipt;
    private boolean finalRetirementUnconfirmed;
    VaultExportTransaction(Provider provider, Journal journal, String name, byte[] snapshot) throws Exception {
        validateName(name);
        this.provider = provider; this.journal = journal; this.snapshot = snapshot.clone(); recovering = false;
        state = new State(); state.tree = provider.tree(); state.location = provider.location(); state.name = name;
        state.length = snapshot.length;
        state.digest = androidFreeHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
    }
    VaultExportTransaction(Provider provider, Journal journal, State state) {
        this.provider = provider; this.journal = journal; this.state = state;
        recovering = true;
        commitRecorded = state.finalVerified;
    }
    static void validateName(String name) throws VaultException {
        if (name == null || name.isEmpty() || name.length() > 128 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new VaultException("PARAMETERS", "Enter one bounded backup filename");
        }
    }
    static String random() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    Map<String, Object> prepare(Guard guard) throws Exception {
        guard.check();
        if (!provider.canCreate()) throw failure("VAULT_EXPORT_CAPABILITY");
        List<Document> baseline = children();
        state.original = named(baseline, state.name);
        state.pendingRole = "stage"; state.pendingName = ".ctos-stage-" + random() + ".ctvault";
        journal.save(state);
        state.stage = fresh(provider.create(state.pendingName), baseline);
        state.pendingRole = null; state.pendingName = null; journal.save(state);
        if (!state.stage.name.startsWith(".ctos-stage-")) throw failure("VAULT_CHANGED");
        writeAndVerify(state.stage, guard);
        state.phase = "prepared"; journal.save(state);
        Map<String, Object> value = new HashMap<>();
        value.put("status", "export_preview"); value.put("exportId", state.id); value.put("fileName", state.name);
        value.put("locationLabel", state.location); value.put("collision", state.original != null);
        value.put("overwriteAvailable", state.original == null || overwriteAvailable(state.original));
        if (state.original != null) {
            Map<String, Object> old = new HashMap<>(); old.put("name", state.original.name);
            if (state.original.bytes != null) old.put("bytes", state.original.bytes);
            if (state.original.modified != null) old.put("modified", state.original.modified);
            value.put("existing", old);
        }
        return value;
    }
    Map<String, Object> commit(boolean overwrite, Guard guard) {
        String error = null;
        try {
            guard.check();
            List<Document> current = children();
            Document target = named(current, state.name);
            if (state.original != null) {
                if (!overwrite) throw failure("VAULT_CANCELLED");
                if (!state.original.sameVersion(target)) throw failure("VAULT_CHANGED");
                if (!overwriteAvailable(target)) throw failure("VAULT_EXPORT_CAPABILITY");
                String rollbackName = ".ctos-rollback-" + random() + ".ctvault";
                if (named(current, rollbackName) != null) throw failure("VAULT_CHANGED");
                state.pendingRole = "renamed_old"; state.pendingName = rollbackName;
                state.oldRenameStarted = true; state.phase = "renaming_old"; journal.save(state);
                Document renamed = provider.rename(target, rollbackName);
                if (renamed == null || (byId(current, renamed.id) != null && !renamed.id.equals(target.id))) throw failure("VAULT_EXPORT_UNCERTAIN");
                state.rollback = renamed; state.pendingRole = null; state.pendingName = null; journal.save(state);
                if (!renamed.name.equals(rollbackName)) throw failure("VAULT_EXPORT_UNCERTAIN");
                Document observed = byId(children(), renamed.id);
                if (!renamed.sameVersion(observed)) throw failure("VAULT_CHANGED");
            } else if (target != null) throw failure("VAULT_CHANGED");
            guard.check();
            current = children();
            if (named(current, state.name) != null) throw failure("VAULT_CHANGED");
            state.pendingRole = "final"; state.pendingName = state.name; state.phase = "creating_final"; journal.save(state);
            state.finalFile = fresh(provider.create(state.name), current);
            state.pendingRole = null; state.pendingName = null; journal.save(state);
            if (!state.finalFile.name.equals(state.name)) throw failure("VAULT_CHANGED");
            writeAndVerify(state.finalFile, guard);
            Document unique = named(children(), state.name);
            if (unique == null || !unique.id.equals(state.finalFile.id)) throw failure("VAULT_CHANGED");
            guard.finalVerified();
            // Cancellation checks end here. Postcommit cleanup must preserve this complete final.
            state.finalVerified = true; state.phase = "final_verified"; journal.save(state); commitRecorded = true;
        } catch (Exception failure) { error = code(failure); }
        return finish(error == null ? "exported" : error.equals("VAULT_CANCELLED") ? "cancelled" : "failed", error);
    }
    Map<String, Object> cancel() { return finish("cancelled", null); }
    Map<String, Object> recover() { return finish(state.finalVerified ? "exported" : "failed", null); }
    Map<String, Object> failed(Exception error) { String code = code(error); return finish(code.equals("VAULT_CANCELLED") ? "cancelled" : "failed", code); }
    private Map<String, Object> finish(String result, String error) {
        if (terminalReceipt != null) return terminalReceipt;
        List<Map<String, Object>> remaining = new ArrayList<>();
        boolean finalConfirmed = !state.finalVerified || finalIntact();
        if (state.finalVerified) {
            // Never roll back after old deletion, including delete-then-throw.
            if (commitRecorded && finalConfirmed) cleanup(state.rollback, "renamed_old", remaining);
            else if (state.rollback != null) remaining.add(item("renamed_old", state.rollback.name, state.rollback.location, "unknown"));
            if (!finalConfirmed) remaining.add(item("created_final", state.finalFile == null ? state.name : state.finalFile.name, state.finalFile == null ? state.location : state.finalFile.location, "unknown"));
        } else {
            cleanup(state.finalFile, "created_final", remaining);
            if (finalRetirementUnconfirmed) {
                Document old = state.rollback == null ? state.original : state.rollback;
                if (old != null) remaining.add(item("renamed_old", old.name, old.location, "unknown"));
            } else restoreOld(remaining);
        }
        cleanup(state.stage, "created_stage", remaining);
        if (state.pendingRole != null) {
            remaining.add(item(state.pendingRole, state.pendingName, state.location, "unknown"));
        }
        state.phase = state.finalVerified ? "postcleanup" : "compensated";
        try {
            if (remaining.isEmpty()) journal.clear(); else journal.save(state);
        } catch (Exception failure) {
            remaining.add(item("journal", "导出恢复记录", state.location, "unknown"));
        }
        VaultCodec.wipe(snapshot); snapshot = null;
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("status", state.finalVerified && finalConfirmed ? "exported" : remaining.isEmpty() ? result : "recovery_required");
        receipt.put("phase", state.phase); receipt.put("fileName", state.name); receipt.put("locationLabel", state.location);
        receipt.put("remaining", remaining);
        if (error != null) receipt.put("errorCode", error);
        terminalReceipt = receipt;
        return receipt;
    }
    private void cleanup(Document document, String role, List<Map<String, Object>> remaining) {
        if (document == null) return;
        try {
            Document current = byId(children(), document.id);
            if (current == null) { retire(document, role); return; }
            if (!document.sameVersion(current) || (recovering && !role.equals("renamed_old") && !verify(current, () -> { }))) {
                remaining.add(item(role, current.name, current.location, "present")); return;
            }
            provider.delete(document);
            if (byId(children(), document.id) != null) remaining.add(item(role, document.name, document.location, "present"));
            else retire(document, role);
        } catch (Exception error) {
            try {
                if (byId(children(), document.id) == null) { retire(document, role); return; }
                remaining.add(item(role, document.name, document.location, "present"));
            } catch (Exception unknown) { remaining.add(item(role, document.name, document.location, "unknown")); }
        }
    }
    private void retire(Document document, String role) throws Exception {
        Document stage = state.stage, created = state.finalFile, original = state.original, rollback = state.rollback;
        boolean renamed = state.oldRenameStarted;
        if (role.equals("created_stage") && state.stage == document) state.stage = null;
        else if (role.equals("created_final") && !state.finalVerified && state.finalFile == document) state.finalFile = null;
        else if (role.equals("renamed_old") && state.rollback == document) {
            state.rollback = null; state.original = null; state.oldRenameStarted = false;
        } else return;
        try {
            journal.save(state);
            if (role.equals("created_final")) finalRetirementUnconfirmed = false;
        } catch (Exception failure) {
            state.stage = stage; state.finalFile = created; state.original = original; state.rollback = rollback; state.oldRenameStarted = renamed;
            if (role.equals("created_final")) finalRetirementUnconfirmed = true;
            throw failure;
        }
    }
    private void retireRestoredOld() throws Exception {
        Document original = state.original, restored = state.rollback;
        boolean renamed = state.oldRenameStarted;
        state.original = null; state.rollback = null; state.oldRenameStarted = false;
        try { journal.save(state); }
        catch (Exception failure) {
            // Preserve the observed restored URI/version, never the superseded random rollback URI.
            state.original = original; state.rollback = restored; state.oldRenameStarted = renamed;
            throw failure;
        }
    }
    private void restoreOld(List<Map<String, Object>> remaining) {
        if (state.original == null || !state.oldRenameStarted) return;
        try {
            List<Document> current = children();
            Document old = state.rollback == null ? byId(current, state.original.id) : byId(current, state.rollback.id);
            if (old == null) {
                remaining.add(item("renamed_old", state.pendingName == null ? state.original.name : state.pendingName, state.location, "unknown"));
                return;
            }
            Document expected = state.rollback == null ? state.original : state.rollback;
            if (!expected.sameVersion(old)) {
                remaining.add(item("renamed_old", old.name, old.location, "present")); return;
            }
            if (old.name.equals(state.original.name)) { state.pendingRole = null; state.pendingName = null; retireRestoredOld(); return; }
            if (named(current, state.original.name) != null) {
                remaining.add(item("renamed_old", old.name, old.location, "present")); return;
            }
            Document restored = provider.rename(old, state.original.name);
            if (restored == null || (byId(current, restored.id) != null && !restored.id.equals(old.id))) throw failure("VAULT_EXPORT_UNCERTAIN");
            state.rollback = restored; state.pendingRole = null; state.pendingName = null;
            journal.save(state);
            if (!restored.name.equals(state.original.name) || !restored.sameVersion(byId(children(), restored.id))) throw failure("VAULT_EXPORT_UNCERTAIN");
            retireRestoredOld();
        } catch (Exception error) {
            Document old = state.rollback;
            remaining.add(item("renamed_old", old == null ? state.original.name : old.name, old == null ? state.location : old.location, "unknown"));
        }
    }
    private List<Document> children() throws Exception {
        List<Document> values = provider.children();
        if (values == null || values.size() > MAX_CHILDREN) throw failure("VAULT_EXPORT_DIRECTORY");
        Set<String> ids = new HashSet<>();
        for (Document value : values) {
            if (value == null || value.id == null || value.name == null || !ids.add(value.id)) throw failure("VAULT_EXPORT_DIRECTORY");
        }
        return values;
    }
    private Document fresh(Document created, List<Document> baseline) throws Exception {
        if (created == null || byId(baseline, created.id) != null) throw failure("VAULT_EXPORT_UNCERTAIN");
        List<Document> current = children();
        Document observed = byId(current, created.id);
        if (observed == null || !observed.name.equals(created.name) || observed.directory) throw failure("VAULT_EXPORT_UNCERTAIN");
        return observed;
    }
    private void writeAndVerify(Document document, Guard guard) throws Exception {
        if (!document.writable || document.directory) throw failure("VAULT_EXPORT_CAPABILITY");
        try (OutputStream output = provider.write(document)) {
            if (output == null) throw failure("VAULT_IO");
            for (int offset = 0; offset < snapshot.length; offset += 8192) {
                guard.check(); output.write(snapshot, offset, Math.min(8192, snapshot.length - offset));
            }
            output.flush();
        } finally {
            // Persist the version observed after our handle closes; recovery still verifies ciphertext.
            Document observed = byId(children(), document.id);
            if (observed != null && observed.name.equals(document.name) && !observed.directory) {
                if (state.stage == document) state.stage = observed;
                if (state.finalFile == document) state.finalFile = observed;
                journal.save(state);
            }
        }
        if (!verify(document, guard)) throw failure("VAULT_IO");
    }
    private boolean finalIntact() {
        try { return state.finalFile != null && state.finalFile.sameVersion(byId(children(), state.finalFile.id)) && verify(state.finalFile, () -> { }); }
        catch (Exception error) { return false; }
    }
    private boolean verify(Document document, Guard guard) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0; byte[] buffer = new byte[8192];
        try (InputStream input = provider.read(document)) {
            if (input == null) throw failure("VAULT_IO");
            int read;
            while ((read = input.read(buffer)) != -1) {
                guard.check(); if (read == 0) throw failure("VAULT_IO"); count += read;
                if (count > state.length) throw failure("VAULT_IO");
                digest.update(buffer, 0, read);
            }
        }
        return count == state.length && androidFreeHex(digest.digest()).equals(state.digest);
    }
    private static String androidFreeHex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
    private static boolean overwriteAvailable(Document old) { return !old.directory && old.renamable && old.deletable && old.bytes != null && old.modified != null; }
    private static Document byId(List<Document> values, String id) { for (Document item : values) if (item.id.equals(id)) return item; return null; }
    private static Document named(List<Document> values, String name) throws VaultException {
        Document result = null;
        for (Document item : values) if (item.name.equals(name)) {
            if (result != null) throw failure("VAULT_EXPORT_DIRECTORY"); result = item;
        }
        return result;
    }
    private static Map<String, Object> item(String role, String name, String location, String state) {
        Map<String, Object> value = new HashMap<>(); value.put("role", role); value.put("name", name);
        value.put("location", location); value.put("state", state); return value;
    }
    private static VaultException failure(String code) { return new VaultException(code, "The export could not be verified; inspect the receipt"); }
    private static String code(Exception error) { return error instanceof VaultException ? ((VaultException) error).code : "VAULT_IO"; }
}
