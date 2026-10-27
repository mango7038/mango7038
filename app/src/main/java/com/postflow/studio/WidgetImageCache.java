package com.postflow.studio;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Asynchronous, allowlisted server-media thumbnail cache for WP-only scheduled jobs. */
final class WidgetImageCache {
    private static final Set<String> IN_FLIGHT=Collections.synchronizedSet(new HashSet<>());
    private WidgetImageCache(){}
    private static String key(String url)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();
        for(int i=0;i<12;i++)s.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]&255));
        return s.toString();
    }
    private static boolean allowed(String url){
        try{
            URI uri=new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                "graphicanest.in".equalsIgnoreCase(uri.getHost()) &&
                uri.getPath()!=null&&uri.getPath().contains("/postflow-media/") &&
                uri.getRawUserInfo()==null && uri.getPort()==-1;
        }catch(Exception ignored){return false;}
    }
    private static File file(Context c,String url)throws Exception{return new File(c.getCacheDir(),"pf-wthumb-"+key(url)+".jpg");}
    static Bitmap get(Context c,String url){
        if(!allowed(url))return null;
        try{
            File stored=file(c,url);
            if(stored.isFile()&&stored.length()>0){
                BitmapFactory.Options o=new BitmapFactory.Options();
                o.inJustDecodeBounds=true;BitmapFactory.decodeFile(stored.getAbsolutePath(),o);
                o.inSampleSize=Math.max(1,Math.max(o.outWidth/520,o.outHeight/520));
                o.inJustDecodeBounds=false;
                Bitmap bmp=BitmapFactory.decodeFile(stored.getAbsolutePath(),o);
                if(bmp!=null)return bmp;
                stored.delete();
            }
            queue(c,url);
        }catch(Exception ignored){}
        return null;
    }
    private static void queue(Context source,String url){
        if(!allowed(url)||!IN_FLIGHT.add(url))return;
        Context c=source.getApplicationContext();
        new Thread(()->{
            File temp=null;HttpURLConnection conn=null;
            try{
                File cached=file(c,url);
                if(cached.isFile()&&cached.length()>0)return;
                URL endpoint=new URL(url);conn=(HttpURLConnection)endpoint.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(12000);conn.setReadTimeout(16000);
                conn.setRequestProperty("Accept","image/jpeg,image/png,image/webp");
                if(conn.getResponseCode()!=200)return;
                String mime=conn.getContentType();
                if(mime==null||!mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))return;
                temp=File.createTempFile("pf-wtmp-", ".jpg",c.getCacheDir());
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(temp)){
                    byte[] buf=new byte[32768];int n;long count=0;
                    while((n=in.read(buf))!=-1){count+=n;if(count>10*1024*1024)throw new java.io.IOException("Thumbnail too large");out.write(buf,0,n);}
                }
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(temp.getAbsolutePath(),o);
                if(o.outWidth<=0||o.outHeight<=0)return;
                BitmapFactory.Options sample=new BitmapFactory.Options();sample.inSampleSize=1;
                while(o.outWidth/sample.inSampleSize>720||o.outHeight/sample.inSampleSize>720)sample.inSampleSize*=2;
                Bitmap bmp=BitmapFactory.decodeFile(temp.getAbsolutePath(),sample);
                if(bmp==null)return;
                try(FileOutputStream out=new FileOutputStream(cached)){
                    bmp.compress(Bitmap.CompressFormat.JPEG,84,out);
                }finally{bmp.recycle();}
                AppWidgetManager manager=AppWidgetManager.getInstance(c);
                int[] ids=manager.getAppWidgetIds(new ComponentName(c,FestivalWidgetProvider.class));
                FestivalWidgetProvider.updateAll(c,manager,ids);
            }catch(Exception ignored){}
            finally{if(temp!=null)temp.delete();if(conn!=null)conn.disconnect();IN_FLIGHT.remove(url);}
        },"postflow-widget-thumbnail").start();
    }
}
