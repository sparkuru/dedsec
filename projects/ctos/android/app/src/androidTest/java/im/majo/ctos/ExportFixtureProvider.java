package im.majo.ctos;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Instrumentation-only provider, backed exclusively by this test application's cache. */
public final class ExportFixtureProvider extends DocumentsProvider {
    private final Map<String,String> names=new HashMap<>();
    private File directory;
    @Override public boolean onCreate(){
        directory=new File(getContext().getCacheDir(),"isolated-export-provider");
        // Instrumentation executes under target UID; grant only this test provider's private root.
        getContext().grantUriPermission("im.majo.ctos", DocumentsContract.buildTreeDocumentUri("im.majo.ctos.export.fixture","root"),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return directory.exists()||directory.mkdir();
    }
    private static final String[] DEFAULT={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private void row(MatrixCursor c,String id,String name,boolean root){
        MatrixCursor.RowBuilder r=c.newRow();File f=new File(directory,id);
        for(String column:c.getColumnNames()) {
            switch(column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:r.add(id);break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:r.add(name);break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:r.add(root?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream");break;
                case DocumentsContract.Document.COLUMN_FLAGS:r.add(root?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME);break;
                case DocumentsContract.Document.COLUMN_SIZE:r.add(root?0:f.length());break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:r.add(root?1:f.lastModified());break;
                default:r.add(null);
            }
        }
    }
    @Override public Cursor queryRoots(String[] projection){return new MatrixCursor(projection==null?new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}:projection);}
    @Override public synchronized Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(projection==null?DEFAULT:projection);if(id.equals("root"))row(c,id,"Isolated test directory",true);else {if(!names.containsKey(id))throw new FileNotFoundException();row(c,id,names.get(id),false);}return c;
    }
    @Override public synchronized Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException();MatrixCursor c=new MatrixCursor(projection==null?DEFAULT:projection);for(Map.Entry<String,String> e:names.entrySet())row(c,e.getKey(),e.getValue(),false);return c;
    }
    @Override public synchronized String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException();String id=UUID.randomUUID().toString();try{if(!new File(directory,id).createNewFile())throw new FileNotFoundException();}catch(java.io.IOException e){throw new FileNotFoundException();}names.put(id,name);return id;
    }
    @Override public synchronized String renameDocument(String id,String name)throws FileNotFoundException {if(!names.containsKey(id))throw new FileNotFoundException();names.put(id,name);return id;}
    @Override public synchronized void deleteDocument(String id)throws FileNotFoundException {if(!names.containsKey(id)||!new File(directory,id).delete())throw new FileNotFoundException();names.remove(id);}
    @Override public synchronized ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {if(!names.containsKey(id))throw new FileNotFoundException();return ParcelFileDescriptor.open(new File(directory,id),ParcelFileDescriptor.parseMode(mode));}
    @Override public boolean isChildDocument(String parent,String id){return parent.equals("root")&&(id.equals("root")||names.containsKey(id));}
}
