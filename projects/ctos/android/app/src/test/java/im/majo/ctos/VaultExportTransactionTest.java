package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class VaultExportTransactionTest {
    private static final byte[] SOURCE = new byte[] {1, 2, 3, 4, 5};
    private static final byte[] OLD = new byte[] {9, 8, 7};
    private static final class Entry {
        String id, name; byte[] data = new byte[0]; long modified = 1;
        Entry(String id, String name) { this.id = id; this.name = name; }
        VaultExportTransaction.Document doc() { return new VaultExportTransaction.Document(id, name, "fixture / " + name, (long) data.length, modified, true, true, true, false); }
    }
    private static final class Provider implements VaultExportTransaction.Provider {
        final Map<String, Entry> files = new HashMap<>(); final List<String> writes = new ArrayList<>(), deletes = new ArrayList<>();
        int ids; boolean pathIds, failStageDelete; Runnable deleted = () -> { }; boolean reuseOld, autoName, failFinalClose, corruptStage, failDelete, deleteThenThrow, changeId;
        Runnable renamed = () -> { }, finalClosed = () -> { }, oldDeleting = () -> { };
        String renameResponseId;
        Entry add(String name, byte[] data) { Entry entry = new Entry(pathIds ? name : "id-" + ++ids, name); entry.data = data.clone(); files.put(entry.id, entry); return entry; }
        @Override public String tree() { return "fixture-tree"; }
        @Override public String location() { return "fixture"; }
        @Override public boolean canCreate() { return true; }
        @Override public List<VaultExportTransaction.Document> children() { List<VaultExportTransaction.Document> result = new ArrayList<>(); for (Entry e : files.values()) result.add(e.doc()); return result; }
        @Override public VaultExportTransaction.Document create(String name) {
            if (reuseOld) return files.values().iterator().next().doc();
            return add(autoName && name.equals("backup.ctvault") ? "backup (1).ctvault" : name, new byte[0]).doc();
        }
        @Override public VaultExportTransaction.Document rename(VaultExportTransaction.Document doc, String name) {
            Entry e = files.get(doc.id); e.name = name;
            if (changeId || pathIds) { files.remove(e.id); e.id = pathIds ? name : "id-" + ++ids; files.put(e.id, e); }
            VaultExportTransaction.Document result = e.doc(); renamed.run(); return renameResponseId == null ? result : files.get(renameResponseId).doc();
        }
        @Override public void delete(VaultExportTransaction.Document doc) throws Exception {
            deletes.add(doc.id); if (doc.name.startsWith(".ctos-rollback")) oldDeleting.run();
            if (failDelete || (failStageDelete && doc.name.startsWith(".ctos-stage"))) throw new IOException();
            files.remove(doc.id); deleted.run(); if (deleteThenThrow) throw new IOException();
        }
        @Override public InputStream read(VaultExportTransaction.Document doc) { byte[] data = files.get(doc.id).data; return new ByteArrayInputStream(corruptStage && doc.name.startsWith(".ctos-stage") ? OLD : data); }
        @Override public OutputStream write(VaultExportTransaction.Document doc) {
            writes.add(doc.id);
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    files.get(doc.id).data = toByteArray();
                    if (doc.name.equals("backup.ctvault")) { finalClosed.run(); if (failFinalClose) throw new IOException(); }
                }
            };
        }
        Entry named(String name) { for (Entry e : files.values()) if (e.name.equals(name)) return e; return null; }
    }
    private static final class Journal implements VaultExportTransaction.Journal {
        VaultExportTransaction.State saved; String failPhase;
        @Override public void save(VaultExportTransaction.State state) throws Exception {
            if (state.phase.equals(failPhase)) throw new IOException();
            saved = copy(state);
        }
        static VaultExportTransaction.State copy(VaultExportTransaction.State s) {
            VaultExportTransaction.State c = new VaultExportTransaction.State();
            c.id=s.id; c.tree=s.tree; c.location=s.location; c.name=s.name; c.phase=s.phase;
            c.pendingRole=s.pendingRole; c.pendingName=s.pendingName; c.digest=s.digest; c.length=s.length;
            c.stage=s.stage; c.original=s.original; c.rollback=s.rollback; c.finalFile=s.finalFile;
            c.finalVerified=s.finalVerified; c.oldRenameStarted=s.oldRenameStarted; return c;
        }
        @Override public void clear() { saved = null; }
    }
    private VaultExportTransaction transaction(Provider provider, Journal journal) throws Exception { return new VaultExportTransaction(provider, journal, "backup.ctvault", SOURCE); }
    @Test public void newExportIsReadBackAndOnlyOwnedStageDeleted() throws Exception {
        Provider p = new Provider(); Entry unrelated = p.add("other", OLD); Journal j = new Journal();
        VaultExportTransaction t = transaction(p, j); assertEquals(false, t.prepare(() -> { }).get("collision"));
        assertEquals("exported", t.commit(false, () -> { }).get("status"));
        assertArrayEquals(SOURCE, p.named("backup.ctvault").data); assertArrayEquals(OLD, p.files.get(unrelated.id).data); assertNull(j.saved);
        assertFalse(p.writes.contains(unrelated.id)); assertFalse(p.deletes.contains(unrelated.id));
    }
    @Test public void collisionRefusalNeverRenamesOrWritesOld() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); Journal j = new Journal(); VaultExportTransaction t = transaction(p, j);
        assertEquals(true, t.prepare(() -> { }).get("collision")); assertEquals("cancelled", t.commit(false, () -> { }).get("status"));
        assertArrayEquals(OLD, p.files.get(old.id).data); assertFalse(p.writes.contains(old.id)); assertFalse(p.deletes.contains(old.id));
    }
    @Test public void overwriteUsesReturnedRenameIdentityAndFreshFinal() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); String oldId = old.id; p.changeId = true;
        VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        assertEquals("exported", t.commit(true, () -> { }).get("status")); assertArrayEquals(SOURCE, p.named("backup.ctvault").data);
        assertFalse(p.writes.contains(oldId)); assertEquals(1, p.files.size());
    }
    @Test public void finalCloseFailureRestoresOldAndDeletesOnlyCreatedFiles() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); p.failFinalClose = true;
        VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        assertEquals("failed", t.commit(true, () -> { }).get("status")); assertArrayEquals(OLD, p.named("backup.ctvault").data);
        assertFalse(p.deletes.contains(old.id)); assertEquals(1, p.files.size());
    }
    @Test public void reusedOldIdNeverOpenedOrDeleted() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); p.reuseOld = true;
        VaultExportTransaction t = transaction(p, new Journal()); try { t.prepare(() -> { }); fail(); } catch (Exception expected) { }
        assertEquals("recovery_required", t.cancel().get("status")); assertTrue(p.writes.isEmpty()); assertTrue(p.deletes.isEmpty()); assertArrayEquals(OLD, p.files.get(old.id).data);
    }
    @Test public void automaticNameChangeAbortsAndRestoresOld() throws Exception {
        Provider p = new Provider(); p.add("backup.ctvault", OLD); p.autoName = true; VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        assertEquals("failed", t.commit(true, () -> { }).get("status")); assertArrayEquals(OLD, p.named("backup.ctvault").data); assertNull(p.named("backup (1).ctvault"));
    }
    @Test public void targetMetadataChangeInvalidatesConfirmation() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { }); old.modified++;
        assertEquals("VAULT_CHANGED", t.commit(true, () -> { }).get("errorCode")); assertArrayEquals(OLD, old.data); assertFalse(p.writes.contains(old.id));
    }
    @Test public void competitorAfterOldRenameIsPreservedAndRollbackLocationReported() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); p.renamed = () -> { if (p.named("backup.ctvault") == null) p.add("backup.ctvault", SOURCE); };
        VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        Map<String, Object> receipt = t.commit(true, () -> { }); assertEquals("recovery_required", receipt.get("status"));
        assertArrayEquals(OLD, old.data); assertArrayEquals(SOURCE, p.named("backup.ctvault").data); assertFalse(p.deletes.contains(old.id)); assertFalse(((List<?>) receipt.get("remaining")).isEmpty());
    }
    @Test public void cancelledAfterRenameCompensatesWithoutDeletingOld() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); boolean[] cancelled = {false}; p.renamed = () -> cancelled[0] = true;
        VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        assertEquals("cancelled", t.commit(true, () -> { if (cancelled[0]) throw new VaultException("VAULT_CANCELLED", "cancel"); }).get("status"));
        assertArrayEquals(OLD, p.named("backup.ctvault").data); assertFalse(p.deletes.contains(old.id));
    }
    @Test public void oldDeleteThenThrowKeepsVerifiedNew() throws Exception {
        Provider p = new Provider(); p.add("backup.ctvault", OLD); p.deleteThenThrow = true;
        VaultExportTransaction t = transaction(p, new Journal()); t.prepare(() -> { });
        assertEquals("exported", t.commit(true, () -> { }).get("status")); assertArrayEquals(SOURCE, p.named("backup.ctvault").data); assertEquals(1, p.files.size());
    }
    @Test public void postCommitCleanupFailureKeepsNewAndJournal() throws Exception {
        Provider p = new Provider(); p.add("backup.ctvault", OLD); p.failDelete = true; Journal j = new Journal(); VaultExportTransaction t = transaction(p, j); t.prepare(() -> { });
        Map<String, Object> receipt = t.commit(true, () -> { }); assertEquals("exported", receipt.get("status")); assertArrayEquals(SOURCE, p.named("backup.ctvault").data); assertNotNull(j.saved); assertFalse(((List<?>) receipt.get("remaining")).isEmpty());
    }
    @Test public void badStageReadbackLeavesExistingUntouched() throws Exception {
        Provider p = new Provider(); Entry old = p.add("backup.ctvault", OLD); p.corruptStage = true; VaultExportTransaction t = transaction(p, new Journal());
        try { t.prepare(() -> { }); fail(); } catch (Exception expected) { }
        t.cancel(); assertArrayEquals(OLD, old.data); assertFalse(p.deletes.contains(old.id)); assertEquals(1, p.files.size());
    }
    @Test public void unsafeFilenamesRejectedBeforeProviderCalls() throws Exception {
        for (String name : new String[] {"", ".", "..", "a/b", "a\\b", "a\n", "x".repeat(129)}) {
            try { new VaultExportTransaction(new Provider(), new Journal(), name, SOURCE); fail(name); } catch (VaultException expected) { assertEquals("PARAMETERS", expected.code); }
        }
    }
    @Test public void renameReturningUnrelatedIdCannotDeleteOrRenameThatFile() throws Exception {
        Provider p = new Provider(); Entry old=p.add("backup.ctvault",OLD), other=p.add("other",SOURCE); p.renameResponseId=other.id;
        VaultExportTransaction t=transaction(p,new Journal()); t.prepare(() -> { });
        assertEquals("recovery_required",t.commit(true,() -> { }).get("status"));
        assertArrayEquals(SOURCE,other.data); assertEquals("other",other.name); assertFalse(p.deletes.contains(other.id)); assertFalse(p.writes.contains(old.id));
    }
    @Test public void failedFinalJournalConfirmationPreservesOldAndVerifiedFinal() throws Exception {
        Provider p=new Provider(); Entry old=p.add("backup.ctvault",OLD); Journal j=new Journal();
        VaultExportTransaction t=transaction(p,j); t.prepare(() -> { }); j.failPhase="final_verified";
        Map<String,Object> receipt=t.commit(true,() -> { });
        assertEquals("exported",receipt.get("status")); assertFalse(p.deletes.contains(old.id)); assertArrayEquals(SOURCE,p.named("backup.ctvault").data);
        assertFalse(((List<?>)receipt.get("remaining")).isEmpty());
    }
    @Test public void recoveryNeverDeletesSameIdReplacedStage() throws Exception {
        Provider p=new Provider(); Journal j=new Journal(); VaultExportTransaction t=transaction(p,j); t.prepare(() -> { });
        VaultExportTransaction.State persisted=Journal.copy(j.saved); Entry replacement=p.files.get(persisted.stage.id); replacement.data=OLD; replacement.modified++;
        Map<String,Object> receipt=new VaultExportTransaction(p,j,persisted).recover();
        assertEquals("recovery_required",receipt.get("status")); assertArrayEquals(OLD,replacement.data); assertFalse(p.deletes.contains(replacement.id));
    }
    @Test public void recoveryNeverDeletesSameMetadataWithWrongCiphertext() throws Exception {
        Provider p=new Provider(); Journal j=new Journal(); VaultExportTransaction t=transaction(p,j); t.prepare(() -> { });
        VaultExportTransaction.State persisted=Journal.copy(j.saved); Entry replacement=p.files.get(persisted.stage.id); replacement.data=new byte[]{8,8,8,8,8};
        assertEquals("recovery_required",new VaultExportTransaction(p,j,persisted).recover().get("status")); assertFalse(p.deletes.contains(replacement.id));
    }
    @Test public void recoveryPreparedVerifiedStageCanBeCleanedWithoutSnapshot() throws Exception {
        Provider p=new Provider(); Journal j=new Journal(); VaultExportTransaction t=transaction(p,j); t.prepare(() -> { });
        assertEquals("failed",new VaultExportTransaction(p,j,Journal.copy(j.saved)).recover().get("status")); assertTrue(p.files.isEmpty()); assertNull(j.saved);
    }
    @Test public void recoveryUnknownCreateDoesNotGuessOwnershipFromName() throws Exception {
        Provider p=new Provider(); Journal j=new Journal(); VaultExportTransaction t=transaction(p,j); t.prepare(() -> { });
        VaultExportTransaction.State state=Journal.copy(j.saved); state.pendingRole="final";state.pendingName="backup.ctvault";Entry other=p.add(state.pendingName,OLD);
        assertEquals("recovery_required",new VaultExportTransaction(p,j,state).recover().get("status")); assertFalse(p.deletes.contains(other.id));
    }
    @Test public void changedRollbackCannotBeRenamedOrDeletedByCompensation() throws Exception {
        Provider p=new Provider();Entry old=p.add("backup.ctvault",OLD);p.renamed=() -> old.modified++;
        VaultExportTransaction t=transaction(p,new Journal());t.prepare(() -> { });
        assertEquals("recovery_required",t.commit(true,() -> { }).get("status")); assertTrue(old.name.startsWith(".ctos-rollback"));assertFalse(p.deletes.contains(old.id));
    }
    @Test public void completeFinalLosingReadbackIsReportedUnknownAndOldKept() throws Exception {
        Provider p=new Provider();Entry old=p.add("backup.ctvault",OLD); Journal j=new Journal();VaultExportTransaction t=transaction(p,j);t.prepare(() -> { });
        Map<String,Object> receipt=t.commit(true,new VaultExportTransaction.Guard(){public void check(){} public void finalVerified(){p.named("backup.ctvault").data=OLD;}});
        assertEquals("recovery_required",receipt.get("status"));assertFalse(p.deletes.contains(old.id));assertNotNull(j.saved);assertFalse(((List<?>)receipt.get("remaining")).isEmpty());
    }
    @Test public void cancelledBeforeFinalClaimRestoresOriginal() throws Exception {
        Provider p=new Provider();p.add("backup.ctvault",OLD);VaultExportTransaction t=transaction(p,new Journal());t.prepare(() -> { });
        assertEquals("cancelled",t.commit(true,new VaultExportTransaction.Guard(){public void check(){}public void finalVerified() throws Exception{throw new VaultException("VAULT_CANCELLED","cancel");}}).get("status")); assertArrayEquals(OLD,p.named("backup.ctvault").data);
    }


    @Test public void firstCommitGuardFailuresAlwaysCompensateOrReportActualResidue() throws Exception {
        for(String code:new String[]{"VAULT_EXPIRED","VAULT_CANCELLED","VAULT_TIMED_OUT","VAULT_CHANGED","VAULT_FOREGROUND_REQUIRED","VAULT_FAILED"})for(boolean deleteFails:new boolean[]{false,true}) {
            Provider p=new Provider();Entry original=p.add("backup.ctvault",OLD);Journal j=new Journal();VaultExportTransaction t=transaction(p,j);t.prepare(() -> { });p.failDelete=deleteFails;
            String stage=t.state.stage.id;
            Map<String,Object> receipt=t.commit(true,() -> {throw new VaultException(code,"admission changed");});
            assertEquals(deleteFails?"recovery_required":code.equals("VAULT_CANCELLED")?"cancelled":"failed",receipt.get("status"));assertEquals(code,receipt.get("errorCode"));
            assertEquals(deleteFails,p.files.containsKey(stage));assertEquals(deleteFails,!((List<?>)receipt.get("remaining")).isEmpty());
            assertArrayEquals(OLD,original.data);assertFalse(p.writes.contains(original.id));assertEquals(deleteFails,j.saved!=null);
        }
    }

    @Test public void pathIdReuseFailedFinalIsRetiredBeforeOldRestoresAndStageResiduePersists() throws Exception {
        Provider p=new Provider();p.pathIds=true;p.failStageDelete=true;Entry original=p.add("backup.ctvault",OLD);p.failFinalClose=true;Journal j=new Journal();VaultExportTransaction t=transaction(p,j);t.prepare(() -> { });
        assertEquals("recovery_required",t.commit(true,() -> { }).get("status"));
        assertArrayEquals(OLD,p.named("backup.ctvault").data);assertNull(j.saved.finalFile);assertNull(j.saved.original);assertNull(j.saved.rollback);assertNotNull(j.saved.stage);
        p.failStageDelete=false;assertEquals("failed",new VaultExportTransaction(p,j,Journal.copy(j.saved)).recover().get("status"));assertArrayEquals(OLD,p.named("backup.ctvault").data);assertEquals(1,p.files.size());assertNull(j.saved);
    }
    @Test public void pathIdReuseVerifiedFinalSurvivesStageCleanupFailureAndRestart() throws Exception {
        Provider p=new Provider();p.pathIds=true;p.failStageDelete=true;p.add("backup.ctvault",OLD);Journal j=new Journal();VaultExportTransaction t=transaction(p,j);t.prepare(() -> { });
        assertEquals("exported",t.commit(true,() -> { }).get("status"));assertArrayEquals(SOURCE,p.named("backup.ctvault").data);
        assertNull(j.saved.original);assertNull(j.saved.rollback);assertFalse(j.saved.oldRenameStarted);assertNotNull(j.saved.finalFile);assertNotNull(j.saved.stage);
        p.failStageDelete=false;assertEquals("exported",new VaultExportTransaction(p,j,Journal.copy(j.saved)).recover().get("status"));assertArrayEquals(SOURCE,p.named("backup.ctvault").data);assertEquals(1,p.files.size());assertNull(j.saved);
    }
    @Test public void unconfirmedRetirementCannotRestoreOldOntoStaleFinalPathEvenIfCipherMatches() throws Exception {
        Provider p=new Provider();p.pathIds=true;Entry original=p.add("backup.ctvault",SOURCE);p.failFinalClose=true;Journal j=new Journal();VaultExportTransaction t=transaction(p,j);t.prepare(() -> { });
        p.deleted=() -> {if(!p.files.containsKey("backup.ctvault"))j.failPhase="creating_final";};
        Map<String,Object> receipt=t.commit(true,() -> { });assertEquals("recovery_required",receipt.get("status"));
        assertNull(p.named("backup.ctvault"));assertTrue(original.name.startsWith(".ctos-rollback"));assertNotNull(j.saved.finalFile);assertNotNull(j.saved.rollback);
        p.deleted=() -> { };j.failPhase=null;assertEquals("failed",new VaultExportTransaction(p,j,Journal.copy(j.saved)).recover().get("status"));
        assertArrayEquals(SOURCE,p.named("backup.ctvault").data);assertEquals(1,p.files.size());assertNull(j.saved);
    }

}
