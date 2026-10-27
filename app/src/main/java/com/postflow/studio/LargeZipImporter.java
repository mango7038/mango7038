package com.postflow.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Streams large user-selected archives directly from Android's Storage Access
 * Framework. JSZip is deliberately NOT used for archives above the small
 * WebView safety limit. Stages media by random IDs; original ZIP is untouched.
 */
final class LargeZipImporter {
    private static final long MAX_MEDIA_BYTES=100L*1024*1024;
    private static final long MAX_EXTRACTED_BYTES=3L*1024*1024*1024;
    private static final int MAX_MEDIA_COUNT=250, MAX_TOTAL_ENTRIES=340;
    private LargeZipImporter(){}
    private static boolean media(String p){return p.matches("(?i).*\\.(png|jpe?g|webp|mp4|mov)$");}
    private static boolean metadata(String p){return p.matches("(?i).*\\.(txt|csv)$");}
    private static String preview(File f,String name){
        if(!name.matches("(?i).*\\.(png|jpe?g|webp)$"))return "";
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(f.getAbsolutePath(),bounds);
            if(bounds.outWidth<1||bounds.outHeight<1)return "";
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=1;
            while(bounds.outWidth/options.inSampleSize>280||bounds.outHeight/options.inSampleSize>280)options.inSampleSize*=2;
            Bitmap bitmap=BitmapFactory.decodeFile(f.getAbsolutePath(),options);
            if(bitmap==null)return "";
            try{
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG,67,out);
                if(out.size()>100_000)return "";
                return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
            }finally{bitmap.recycle();}
        }catch(Exception ignored){return "";}
    }
    static JSONObject extract(Context c,Uri uri)throws Exception{
        File mediaDir=MediaContentProvider.directory(c);
        JSONArray entries=new JSONArray(),metadata=new JSONArray();
        ArrayList<String> staged=new ArrayList<>();
        long total=0;int inspected=0;
        try{
            try(InputStream stream=c.getContentResolver().openInputStream(uri)){
                if(stream==null)throw new IOException("Could not read selected ZIP");
                try(ZipInputStream zip=new ZipInputStream(stream)){
                    ZipEntry item;
                    while((item=zip.getNextEntry())!=null){
                        if(++inspected>MAX_TOTAL_ENTRIES)throw new IOException("ZIP contains too many entries");
                        if(item.isDirectory()){zip.closeEntry();continue;}
                        String path=item.getName().replace('\\','/');
                        if(path.length()>350||path.startsWith("/")||path.contains("../")||path.startsWith("__MACOSX/")){
                            zip.closeEntry();continue;
                        }
                        boolean isMedia=media(path),isMeta=metadata(path);
                        if(!isMedia&&!isMeta){zip.closeEntry();continue;}
                        if(isMedia&&entries.length()>=MAX_MEDIA_COUNT)throw new IOException("ZIP maximum is 250 media files");
                        long max=isMeta?1024*1024:MAX_MEDIA_BYTES;
                        if(item.getSize()>max)throw new IOException("Entry exceeds safety limit: "+path);
                        String id="z"+UUID.randomUUID().toString().replace("-","");
                        File stored=isMedia?MediaContentProvider.mediaFile(c,id):null;
                        ByteArrayOutputStream text=isMeta?new ByteArrayOutputStream():null;
                        CRC32 crc=new CRC32();long size=0;
                        boolean complete=false;
                        try{
                            if(stored!=null){
                                try(FileOutputStream out=new FileOutputStream(stored)){
                                    byte[] buf=new byte[64*1024];int n;
                                    while((n=zip.read(buf))!=-1){
                                        size+=n;total+=n;
                                        if(size>max||total>MAX_EXTRACTED_BYTES)throw new IOException("ZIP media/extraction limit exceeded");
                                        crc.update(buf,0,n);out.write(buf,0,n);
                                    }
                                }
                            }else{
                                byte[] buf=new byte[16*1024];int n;
                                while((n=zip.read(buf))!=-1){
                                    size+=n;total+=n;
                                    if(size>max||total>MAX_EXTRACTED_BYTES)throw new IOException("ZIP metadata/extraction limit exceeded");
                                    crc.update(buf,0,n);text.write(buf,0,n);
                                }
                            }
                            complete=true;
                        }finally{if(stored!=null&&!complete)stored.delete();}
                        if(isMedia){
                            if(size<1){stored.delete();continue;}
                            staged.add(id);
                            c.getSharedPreferences("postflow_media",0).edit().putString(id,new File(path).getName()).apply();
                            JSONObject object=new JSONObject();
                            object.put("path",path);object.put("mediaId",id);object.put("size",size);
                            object.put("crc",crc.getValue());object.put("preview",preview(stored,path));
                            entries.put(object);
                        }else{
                            JSONObject object=new JSONObject();
                            object.put("path",path);object.put("text",text.toString("UTF-8"));
                            metadata.put(object);
                        }
                        zip.closeEntry();
                    }
                }
            }
            if(entries.length()==0)throw new IOException("ZIP contains no supported posters");
            JSONObject result=new JSONObject();
            result.put("ok",true);result.put("entries",entries);result.put("metadata",metadata);
            result.put("extractedBytes",total);
            return result;
        }catch(Exception error){
            for(String id:staged){
                if(MediaContentProvider.valid(id))MediaContentProvider.mediaFile(c,id).delete();
                c.getSharedPreferences("postflow_media",0).edit().remove(id).apply();
            }
            throw error;
        }
    }
}
