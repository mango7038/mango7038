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


    /** Pairing credential is stored in Android Keystore-backed encrypted preferences when available. */
    @JavascriptInterface public void pairWordPress(String key){
        if(key==null || !key.matches("[A-Za-z0-9_-]{32,128}")){sendWordPressResult("{\"error\":\"Invalid pairing key format\"}");return;}
        try{
            javax.crypto.KeyGenerator generator=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
            android.security.keystore.KeyGenParameterSpec spec=new android.security.keystore.KeyGenParameterSpec.Builder("postflow_wp_pair",android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build();
            java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
            if(!store.containsAlias("postflow_wp_pair")){generator.init(spec);generator.generateKey();}
            javax.crypto.SecretKey secret=((java.security.KeyStore.SecretKeyEntry)store.getEntry("postflow_wp_pair",null)).getSecretKey();
            javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,secret);
            byte[] enc=cipher.doFinal(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            activity.getSharedPreferences("postflow_wp",Context.MODE_PRIVATE).edit().putString("key",Base64.encodeToString(enc,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).apply();
            syncWordPressAccounts();
        }catch(Exception e){sendWordPressResult("{\"error\":\"Could not securely save pairing key\"}");}
    }
    private String wordpressKey() throws Exception{
        android.content.SharedPreferences prefs=activity.getSharedPreferences("postflow_wp",Context.MODE_PRIVATE);
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
        javax.crypto.SecretKey secret=((java.security.KeyStore.SecretKeyEntry)store.getEntry("postflow_wp_pair",null)).getSecretKey();
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,secret,new javax.crypto.spec.GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("key",""),Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    @JavascriptInterface public boolean isWordPressPaired(){return activity.getSharedPreferences("postflow_wp",Context.MODE_PRIVATE).contains("key");}
    @JavascriptInterface public void unpairWordPress(){activity.getSharedPreferences("postflow_wp",Context.MODE_PRIVATE).edit().clear().apply();sendWordPressResult("{\"unpaired\":true}");}
    @JavascriptInterface public void syncWordPressAccounts(){
        new Thread(()->{
            java.net.HttpURLConnection c=null;
            try{
                String key=wordpressKey();
                java.net.URL url=new java.net.URL("https://graphicanest.in/wp-json/postflow/v1/accounts");
                c=(java.net.HttpURLConnection)url.openConnection();
                c.setRequestMethod("GET");c.setConnectTimeout(12000);c.setReadTimeout(12000);
                c.setRequestProperty("Authorization","Bearer "+key);
                c.setRequestProperty("Accept","application/json");
                int status=c.getResponseCode();
                if(status!=200){sendWordPressResult("{\"error\":\"WordPress rejected sync (HTTP "+status+")\"}");return;}
                try(java.io.InputStream in=c.getInputStream()){
                    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                    while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>150000)throw new IOException("Response too large");}
                    org.json.JSONObject data=new org.json.JSONObject(bytes.toString("UTF-8"));
                    if(data.optJSONArray("accounts")==null)throw new IOException("Invalid accounts response");
                    sendWordPressResult(data.toString());
                }
            }catch(Exception e){sendWordPressResult("{\"error\":\"Sync failed. Check internet, pairing key and website API.\"}");}
            finally{if(c!=null)c.disconnect();}
        }).start();
    }
    private void sendWordPressResult(String json){
        activity.runOnUiThread(()->activity.getWebViewForBridge().evaluateJavascript("window.onWordPressSync("+org.json.JSONObject.quote(json)+")",null));
    }
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
    /** Read image bytes for the local poster editor. Limit bridge payload to avoid WebView memory exhaustion. */
    @JavascriptInterface public String getMediaBase64(String id){
        if(!MediaContentProvider.valid(id))return "";
        File file=MediaContentProvider.mediaFile(activity,id);
        if(!file.isFile()||file.length()>16L*1024*1024)return "";
        try{
            byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());
            return Base64.encodeToString(bytes,Base64.NO_WRAP);
        }catch(Exception ex){return "";}
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