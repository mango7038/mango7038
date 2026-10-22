package com.postflow.studio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider for sharing app-private media with user-selected Android apps. */
public final class MediaContentProvider extends ContentProvider {
    public static final String AUTHORITY=BuildConfig.APPLICATION_ID+".media";
    static File directory(android.content.Context c) {
        File dir=new File(c.getFilesDir(),"media");
        if(!dir.isDirectory()) dir.mkdirs();
        return dir;
    }
    static boolean valid(String id){return id!=null && id.matches("[A-Za-z0-9_-]{1,80}");}
    static File mediaFile(android.content.Context c,String id){return new File(directory(c),id);}
    @Override public boolean onCreate(){return true;}
    private File requested(Uri uri) throws FileNotFoundException {
        String id=uri.getLastPathSegment();
        if(!valid(id)) throw new FileNotFoundException("Invalid ID");
        File f="backup".equals(id)?new File(getContext().getCacheDir(),"postflow-full-backup.zip"):mediaFile(getContext(),id);
        if(!f.isFile()) throw new FileNotFoundException("Media not found");
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(requested(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){
        if("backup".equals(uri.getLastPathSegment()))return "application/zip";
        String name=getContext().getSharedPreferences("postflow_media",0).getString(uri.getLastPathSegment(),"image.jpg");
        String lower=name.toLowerCase();
        if(lower.endsWith(".mp4"))return "video/mp4";
        if(lower.endsWith(".mov"))return "video/quicktime";
        if(lower.endsWith(".png"))return "image/png";
        if(lower.endsWith(".webp"))return "image/webp";
        return "image/jpeg";
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try {
            File f=requested(uri);
            String[] keys=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(keys);
            Object[] row=new Object[keys.length];
            for(int i=0;i<keys.length;i++) {
                if(OpenableColumns.DISPLAY_NAME.equals(keys[i]))row[i]="backup".equals(uri.getLastPathSegment())?"postflow-full-backup.zip":getContext().getSharedPreferences("postflow_media",0).getString(uri.getLastPathSegment(),"postflow-media");
                else if(OpenableColumns.SIZE.equals(keys[i]))row[i]=f.length();
            }
            cursor.addRow(row);return cursor;
        }catch(Exception ignored){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}