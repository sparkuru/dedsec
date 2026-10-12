package im.majo.ctos;

import static org.junit.Assert.*;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class SafExportProviderTest {
    private static byte[] read(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream result=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while((count=input.read(buffer))!=-1){if(count==0||result.size()+count>8192)throw new java.io.IOException();result.write(buffer,0,count);}return result.toByteArray();
    }
    @Test public void adapterUsesNewDocumentAndKeepsConfirmedOldUntilCommit() throws Exception {
        android.content.Context fixtureContext=InstrumentationRegistry.getInstrumentation().getContext();
        android.content.Context targetContext=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Target UID cannot acquire MANAGE_DOCUMENTS-protected provider before its owner grants access.
        targetContext.startActivity(new android.content.Intent().setClassName(
                fixtureContext.getPackageName(), ExportFixtureGrantActivity.class.getName())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        ContentResolver resolver=targetContext.getContentResolver();
        Uri tree=DocumentsContract.buildTreeDocumentUri("im.majo.ctos.export.fixture","root");
        long deadline=android.os.SystemClock.elapsedRealtime()+5000;
        while (true) {
            try (android.database.Cursor cursor=resolver.query(
                    DocumentsContract.buildDocumentUriUsingTree(tree,"root"),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)) {
                if(cursor==null||!cursor.moveToFirst())throw new java.io.IOException("Fixture bootstrap failed");
                break;
            } catch (SecurityException waiting) {
                if(android.os.SystemClock.elapsedRealtime()>=deadline)throw waiting;
                android.os.SystemClock.sleep(50);
            }
        }
        SafExportProvider p=new SafExportProvider(resolver,tree);String name="fixture-"+UUID.randomUUID()+".ctvault";
        VaultExportTransaction.Document old=null;
        VaultExportTransaction t=null;
        Throwable primary=null;
        try {
            // From the first returned ID onward, setup failures must enter exact-ID cleanup.
            old=p.create(name);
            byte[] original={8,9};try(OutputStream o=p.write(old)){o.write(original);}
            byte[] source={1,2,3,4};VaultExportTransaction.Journal memory=new VaultExportTransaction.Journal(){public void save(VaultExportTransaction.State s){}public void clear(){}};
            t=new VaultExportTransaction(p,memory,name,source);
            assertEquals(true,t.prepare(() -> { }).get("collision"));
            try(java.io.InputStream in=p.read(old)){assertArrayEquals(original,read(in));}
            assertEquals("exported",t.commit(true,() -> { }).get("status"));
            List<VaultExportTransaction.Document> children=p.children();VaultExportTransaction.Document created=null;
            assertNotNull(t.state.finalFile);
            for(VaultExportTransaction.Document d:children)if(d.id.equals(t.state.finalFile.id))created=d;
            assertNotNull(created);assertEquals(name,created.name);assertNotEquals(old.id,created.id);
            try(java.io.InputStream in=p.read(created)){assertArrayEquals(source,read(in));}
        } catch (Exception | Error failure) {
            primary=failure;
            throw failure;
        } finally {
            // Exact IDs from this fixture only; never prefix-scan/delete unrelated documents.
            java.util.Map<String,VaultExportTransaction.Document> owned=new java.util.LinkedHashMap<>();
            remember(owned,old);
            if(t!=null){remember(owned,t.state.original);remember(owned,t.state.stage);remember(owned,t.state.finalFile);remember(owned,t.state.rollback);}
            java.io.IOException cleanupFailure=null;
            for(String id:owned.keySet()) {
                try {
                    VaultExportTransaction.Document observed=null;
                    for(VaultExportTransaction.Document d:p.children())if(d.id.equals(id))observed=d;
                    if(observed!=null) {
                        p.delete(observed);
                        for(VaultExportTransaction.Document d:p.children())if(d.id.equals(id))throw new java.io.IOException("Owned fixture remained after deletion");
                    }
                } catch (Exception | Error failure) {
                    if(cleanupFailure==null)cleanupFailure=new java.io.IOException("Exact fixture cleanup was incomplete");
                    cleanupFailure.addSuppressed(failure);
                }
            }
            if(cleanupFailure!=null) {
                if(primary!=null)primary.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
    private static void remember(java.util.Map<String,VaultExportTransaction.Document> owned,VaultExportTransaction.Document document) {
        if(document!=null)owned.put(document.id,document);
    }
}
