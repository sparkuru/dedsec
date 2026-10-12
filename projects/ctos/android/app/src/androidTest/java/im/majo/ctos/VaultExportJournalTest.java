package im.majo.ctos;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Private per-test fixture; never reads the application's real vault or export journal. */
public final class VaultExportJournalTest {
    private File directory;
    @Before public void setup() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory=new File(context.getCacheDir(),"export-journal-test-"+UUID.randomUUID()); assertTrue(directory.mkdir());
    }
    @After public void cleanup() throws Exception { for(File f:directory.listFiles()) Files.delete(f.toPath()); Files.delete(directory.toPath()); }
    private VaultExportTransaction.State state() {
        VaultExportTransaction.State s=new VaultExportTransaction.State(); s.tree="content://fixture/tree/root";s.location="fixture";s.name="backup.ctvault";
        s.digest="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";s.length=5;s.phase="prepared";
        s.stage=new VaultExportTransaction.Document("new-id",".ctos-stage-fixture","fixture/stage",5L,1L,true,true,true,false);return s;
    }
    @Test public void durableMetadataRoundTripExcludesSecretAndClearRemovesFiles() throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);VaultExportTransaction.State s=state();j.save(s);
        VaultExportTransaction.State read=new VaultExportJournal(directory).load(); assertEquals(s.id,read.id);assertEquals(s.stage.id,read.stage.id);assertEquals(s.digest,read.digest);
        String json=new String(Files.readAllBytes(new File(directory,"vault-export-journal.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);assertFalse(json.contains("password"));assertFalse(json.contains("snapshot"));
        j.clear();assertNull(j.load());assertEquals(0,directory.listFiles().length);
    }
    @Test public void oversizedBackupRejectedBeforeUnboundedAllocation() throws Exception {
        File backup=new File(directory,"vault-export-journal.json.bak");Files.write(backup.toPath(),new byte[32769]);
        try{new VaultExportJournal(directory).load();fail();}catch(java.io.IOException expected){}
    }
    @Test public void aliasedRolesRejectedWithoutAnyProviderAccess() throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);VaultExportTransaction.State s=state();s.finalFile=s.stage;j.save(s);
        try{j.load();fail();}catch(java.io.IOException expected){}
    }
    @Test public void silentFinishCannotAuthorizeDeletionAndPreservesPriorRecord() throws Exception {
        File base=new File(directory,"vault-export-journal.json");VaultExportJournal normal=new VaultExportJournal(directory);
        VaultExportTransaction.State prior=state();normal.save(prior);
        android.util.AtomicFile silent=new android.util.AtomicFile(base){@Override public void finishWrite(java.io.FileOutputStream stream){try{stream.close();}catch(java.io.IOException ignored){}}};
        VaultExportTransaction.State next=state();next.phase="final_verified";next.finalFile=new VaultExportTransaction.Document("final-id","backup.ctvault","fixture/final",5L,1L,true,true,true,false);next.finalVerified=true;
        try{new VaultExportJournal(silent).save(next);fail();}catch(java.io.IOException expected){}
        assertEquals(prior.id,normal.load().id);
    }
    @Test public void readbackFailureAfterFinishNeverCallsRollbackOrDeletesCommittedRecord() throws Exception {
        File base=new File(directory,"vault-export-journal.json");boolean[] failRead={false},rolledBack={false};
        android.util.AtomicFile atomic=new android.util.AtomicFile(base){
            @Override public void finishWrite(java.io.FileOutputStream stream){super.finishWrite(stream);failRead[0]=true;}
            @Override public java.io.FileInputStream openRead() throws java.io.FileNotFoundException {if(failRead[0])throw new java.io.FileNotFoundException();return super.openRead();}
            @Override public void failWrite(java.io.FileOutputStream stream){rolledBack[0]=true;super.failWrite(stream);}
        };
        VaultExportTransaction.State value=state();try{new VaultExportJournal(atomic).save(value);fail();}catch(java.io.FileNotFoundException expected){}
        assertFalse(rolledBack[0]);failRead[0]=false;assertEquals(value.id,new VaultExportJournal(atomic).load().id);
    }

    private static final byte[] SOURCE={1,2,3,4,5}, OLD={9,8,7};
    private static VaultExportTransaction.Document document(String id,String name,long size,long time) {
        return new VaultExportTransaction.Document(id,name,"fixture / "+name,size,time,true,true,true,false);
    }
    private VaultExportTransaction.State pathReuse(boolean verified,String phase)throws Exception {
        VaultExportTransaction.State s=state();s.phase=phase;s.oldRenameStarted=true;s.finalVerified=verified;
        String rollbackName=".ctos-rollback-0123456789abcdef0123456789abcdef.ctvault";
        s.original=document("root/backup.ctvault",s.name,OLD.length,1);
        s.rollback=document("root/"+rollbackName,rollbackName,OLD.length,2);
        s.finalFile=document(s.original.id,s.name,SOURCE.length,3);
        s.stage=document("root/stage",".ctos-stage-fixture",SOURCE.length,4);
        StringBuilder digest=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(SOURCE))digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));s.digest=digest.toString();
        return s;
    }
    private static final class RecoveryProvider implements VaultExportTransaction.Provider {
        final java.util.Map<String,VaultExportTransaction.Document> documents=new java.util.HashMap<>();
        final java.util.Map<String,byte[]> data=new java.util.HashMap<>();
        final java.util.List<String> actions=new java.util.ArrayList<>();boolean failStageDelete;
        RecoveryProvider(VaultExportTransaction.State s){put(s.stage,SOURCE);put(s.finalFile,SOURCE);put(s.rollback,OLD);}
        void put(VaultExportTransaction.Document doc,byte[] bytes){if(doc!=null){documents.put(doc.id,doc);data.put(doc.id,bytes.clone());}}
        public String tree(){return "content://fixture/tree/root";}public String location(){return "fixture";}public boolean canCreate(){return true;}
        public java.util.List<VaultExportTransaction.Document> children(){return new java.util.ArrayList<>(documents.values());}
        public VaultExportTransaction.Document create(String name){throw new AssertionError("Recovery must not create a file");}
        public VaultExportTransaction.Document rename(VaultExportTransaction.Document d,String name){
            actions.add("rename:"+d.id);byte[] bytes=data.remove(d.id);documents.remove(d.id);
            VaultExportTransaction.Document restored=document("root/"+name,name,d.bytes,d.modified+1);put(restored,bytes);return restored;
        }
        public void delete(VaultExportTransaction.Document d)throws Exception{actions.add("delete:"+d.id);if(failStageDelete&&d.name.startsWith(".ctos-stage"))throw new java.io.IOException();documents.remove(d.id);data.remove(d.id);}
        public java.io.InputStream read(VaultExportTransaction.Document d){return new java.io.ByteArrayInputStream(data.get(d.id));}
        public java.io.OutputStream write(VaultExportTransaction.Document d){throw new AssertionError("Recovery must not write a file");}
    }
    @Test public void historicPathIdReuseRoundTripsOnlyAfterDistinctRollbackObservation()throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);
        for(String phase:new String[]{"creating_final","compensated","final_verified","postcleanup"}) {
            VaultExportTransaction.State s=pathReuse(phase.equals("final_verified")||phase.equals("postcleanup"),phase);j.save(s);
            VaultExportTransaction.State loaded=j.load();assertEquals(loaded.original.id,loaded.finalFile.id);assertNotEquals(loaded.rollback.id,loaded.finalFile.id);
            // A conforming rename may change modified metadata; current rollback version is recorded separately.
            assertNotEquals(loaded.original.modified,loaded.rollback.modified);
        }
    }
    @Test public void historicReuseWithoutEvidenceOrWithCurrentRoleAliasesIsRejected()throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);
        for(int variant=0;variant<14;variant++) {
            VaultExportTransaction.State s=pathReuse(false,"creating_final");
            switch(variant){
                case 0:s.rollback=null;break;case 1:s.oldRenameStarted=false;break;
                case 2:s.rollback=document(s.original.id,s.rollback.name,OLD.length,2);break;
                case 3:s.stage=document(s.original.id,s.stage.name,SOURCE.length,4);break;
                case 4:s.stage=s.finalFile;break;case 5:s.stage=s.rollback;break;
                case 6:s.finalFile=s.rollback;break;
                case 7:s.original=document(s.original.id,"wrong",OLD.length,1);break;
                case 8:s.finalFile=document(s.finalFile.id,"wrong",SOURCE.length,3);break;
                case 9:s.rollback=document(s.rollback.id,"unrelated",OLD.length,2);break;
                case 10:s.rollback=document(s.rollback.id,s.rollback.name,99,2);break;
                case 11:s.phase="prepared";break;case 12:s.phase="final_verified";break;
                case 13:s.pendingRole="final";s.pendingName=s.name;break;
            }
            j.save(s);try{j.load();fail("variant "+variant);}catch(java.io.IOException expected){}
        }
    }
    @Test public void precommitPathReuseRecoveryRetiresFinalBeforeRestoringOldPath()throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);VaultExportTransaction.State s=pathReuse(false,"compensated");j.save(s);
        RecoveryProvider p=new RecoveryProvider(s);VaultExportTransaction recovered=new VaultExportTransaction(p,j,j.load());
        assertEquals("failed",recovered.recover().get("status"));
        assertArrayEquals(OLD,p.data.get(s.original.id));assertEquals(1,p.documents.size());assertNull(j.load());
        assertNull(recovered.state.finalFile);assertNull(recovered.state.rollback);assertNull(recovered.state.original);
        assertTrue(p.actions.indexOf("delete:"+s.finalFile.id)<p.actions.indexOf("rename:"+s.rollback.id));
    }
    @Test public void verifiedPathReuseWithStageResidueLoadsAndPreservesFinalOnNextRecovery()throws Exception {
        VaultExportJournal j=new VaultExportJournal(directory);VaultExportTransaction.State s=pathReuse(true,"final_verified");j.save(s);
        String finalId=s.finalFile.id;RecoveryProvider p=new RecoveryProvider(s);p.failStageDelete=true;
        assertEquals("exported",new VaultExportTransaction(p,j,j.load()).recover().get("status"));
        VaultExportTransaction.State residual=j.load();assertNotNull(residual.stage);assertNotNull(residual.finalFile);assertNull(residual.original);assertNull(residual.rollback);assertFalse(residual.oldRenameStarted);
        assertArrayEquals(SOURCE,p.data.get(finalId));p.failStageDelete=false;
        assertEquals("exported",new VaultExportTransaction(p,j,residual).recover().get("status"));assertArrayEquals(SOURCE,p.data.get(finalId));assertEquals(1,p.documents.size());assertNull(j.load());
    }

}
