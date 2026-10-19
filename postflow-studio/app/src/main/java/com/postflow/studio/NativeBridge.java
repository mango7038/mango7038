package com.postflow.studio;

import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileInputStream;
import android.os.Environment;

public final class NativeBridge {
    private final MainActivity activity;
    private final Map<String,FileOutputStream> streams=new HashMap<>();
    NativeBridge(MainActivity activity){this.activity=activity;}

    @JavascriptInterface public String getState(){
        java.io.File file=new java.io.File(activity.getFilesDir(),"state.json");
        try {
            if(file.isFile())return new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception ignored){}
        return activity.getSharedPreferences("postflow",Context.MODE_PRIVATE).getString("state","{}");
    }
    @JavascriptInterface public synchronized void syncState(String json){
        if(json==null || json.length()>20_000_000)return;
        try {
            java.io.File file=new java.io.File(activity.getFilesDir(),"state.json");
            java.io.File temp=new java.io.File(activity.getFilesDir(),"state.json.tmp");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
            }
            if(!temp.renameTo(file)){if(file.exists())file.delete();temp.renameTo(file);}
            org.json.JSONObject mirror=new org.json.JSONObject(json);
            org.json.JSONArray posts=mirror.optJSONArray("posts");
            if(posts!=null)for(int i=0;i<posts.length();i++){
                org.json.JSONObject post=posts.optJSONObject(i);
                if(post!=null){post.remove("thumb");post.remove("caption");post.remove("origin");}
            }
            mirror.remove("imports");mirror.remove("accounts");
            activity.getSharedPreferences("postflow",Context.MODE_PRIVATE).edit().putString("state",mirror.toString()).apply();
        }catch(Exception e){return;}
        EventScheduler.reschedule(activity);
        AppWidgetManager awm=AppWidgetManager.getInstance(activity);
        int[] ids=awm.getAppWidgetIds(new ComponentName(activity,FestivalWidgetProvider.class));
        FestivalWidgetProvider.updateAll(activity,awm,ids);
    }

    /** Streaming writes avoid giant base64 payloads/limited localStorage quotas. */
    @JavascriptInterface public synchronized boolean beginMedia(String id,String fileName){
        if(!MediaContentProvider.valid(id)||streams.containsKey(id))return false;
        try {
            String safe=fileName==null?"media":fileName.replaceAll("[^a-zA-Z0-9._ -]","_");
            streams.put(id,new FileOutputStream(MediaContentProvider.mediaFile(activity,id),false));
            activity.getSharedPreferences("postflow_media",0).edit().putString(id,safe).apply();
            return true;
        }catch(Exception ex){return false;}
    }
    @JavascriptInterface public synchronized boolean appendMedia(String id,String base64){
        FileOutputStream out=streams.get(id);
        if(out==null||base64==null||base64.length()>950_000)return false;
        try {
            byte[] data=Base64.decode(base64,Base64.NO_WRAP);
            if(out.getChannel().position()+data.length>140L*1024*1024)return false;
            out.write(data);return true;
        }catch(Exception ex){return false;}
    }
    @JavascriptInterface public synchronized void finishMedia(String id,boolean success){
        FileOutputStream out=streams.remove(id);
        if(out!=null)try{out.close();}catch(IOException ignored){}
        if(!success)MediaContentProvider.mediaFile(activity,id).delete();
    }
    @JavascriptInterface public void deleteMedia(String id){
        if(!MediaContentProvider.valid(id))return;
        synchronized(this){FileOutputStream opened=streams.remove(id);
            if(opened!=null)try{opened.close();}catch(Exception ignored){}
            MediaContentProvider.mediaFile(activity,id).delete();
            activity.getSharedPreferences("postflow_media",0).edit().remove(id).apply();
        }
    }
    @JavascriptInterface public boolean hasMedia(String id){
        return MediaContentProvider.valid(id)&&MediaContentProvider.mediaFile(activity,id).isFile();
    }
    @JavascriptInterface public void shareMedia(String id,String text){
        if(!hasMedia(id))return;
        Uri uri=new Uri.Builder().scheme("content").authority(MediaContentProvider.AUTHORITY).appendPath(id).build();
        String type=activity.getContentResolver().getType(uri);
        activity.runOnUiThread(()->{
            Intent send=new Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TEXT,text==null?"":text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newUri(activity.getContentResolver(),"PostFlow media",uri));
            activity.startActivity(Intent.createChooser(send,"Share post / WhatsApp"));
        });
    }
    @JavascriptInterface public void exitApp(){activity.runOnUiThread(activity::finish);}
    @JavascriptInterface public void shareText(String text){
        if(text==null)return;
        activity.runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text);
            activity.startActivity(Intent.createChooser(i,"Share with"));
        });
    }
    @JavascriptInterface public void importBackup(){activity.launchBackupPicker();}
    @JavascriptInterface public void exportBackup(){
        new Thread(()->{
            try {
                File file=new File(activity.getCacheDir(),"postflow-full-backup.zip");
                try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
                    String json=getState();
                    out.putNextEntry(new ZipEntry("postflow-state.json"));
                    out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
                    File[] media=MediaContentProvider.directory(activity).listFiles();
                    if(media!=null)for(File m:media){
                        if(!m.isFile()||!MediaContentProvider.valid(m.getName()))continue;
                        out.putNextEntry(new ZipEntry("media/"+m.getName()));
                        try(FileInputStream stream=new FileInputStream(m)){
                            byte[] buf=new byte[32*1024];int read;
                            while((read=stream.read(buf))!=-1)out.write(buf,0,read);
                        }
                        out.closeEntry();
                    }
                    java.util.Map<String,?> names=activity.getSharedPreferences("postflow_media",0).getAll();
                    org.json.JSONObject manifest=new org.json.JSONObject(names);
                    out.putNextEntry(new ZipEntry("media-names.json"));
                    out.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
                }
                activity.runOnUiThread(()->activity.shareBackup(file));
            }catch(Exception e){activity.showError("Backup export failed: "+e.getMessage());}
        },"postflow-backup-export").start();
    }
}