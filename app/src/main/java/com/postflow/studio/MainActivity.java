package com.postflow.studio;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.content.ClipData;
import android.widget.Toast;
import android.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int FILE_PICKER = 2002;
    private static final int BACKUP_PICKER = 2003;
    private WebView web;
    WebView getWebViewForBridge(){return web;}
    private ValueCallback<Uri[]> fileCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReminderReceiver.createChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
        }
        web = new WebView(this);
        web.setBackgroundColor(android.graphics.Color.rgb(11,16,32));
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        web.addJavascriptInterface(new NativeBridge(this), "PostFlowNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request){
                Uri url=request.getUrl();
                if ("file".equals(url.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); }
                catch (ActivityNotFoundException ignored) {}
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("file:")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
                catch (ActivityNotFoundException ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                                       FileChooserParams fileChooserParams){
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback=callback;
                Intent intent=fileChooserParams.createIntent();
                if(fileChooserParams.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE){intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.setAction(Intent.ACTION_GET_CONTENT);}
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try{ startActivityForResult(intent, FILE_PICKER); }
                catch(Exception ex){fileCallback.onReceiveValue(null); fileCallback=null; return false;}
                return true;
            }
        });
        setContentView(web);
        EventScheduler.reschedule(this);
        showPinGate();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode == BACKUP_PICKER && resultCode == RESULT_OK && data!=null && data.getData()!=null) {
            Uri selected=data.getData();
            new Thread(()->restoreArchive(selected)).start();
        }
        if(requestCode == FILE_PICKER && fileCallback!=null){
            java.util.ArrayList<Uri> picked=new java.util.ArrayList<>();
            if(resultCode==RESULT_OK && data!=null){
                if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++){Uri uri=data.getClipData().getItemAt(i).getUri();if(uri!=null&&!picked.contains(uri))picked.add(uri);}}
                if(data.getData()!=null&&!picked.contains(data.getData()))picked.add(data.getData());
            }
            fileCallback.onReceiveValue(picked.isEmpty()?null:picked.toArray(new Uri[0]));
            fileCallback=null;
        }
    }
    private void unlockAndLoad(){web.loadUrl("file:///android_asset/index.html");}
    private void showPinGate(){
        if(!PinGate.hasPin(this)){
            requestPin(true, null);
        }else requestPin(false,null);
    }
    private void requestPin(boolean create,String confirm){
        EditText input=new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint("4–8 digit PIN");
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(create?(confirm==null?"Create PostFlow owner PIN":"Confirm owner PIN"):"Unlock PostFlow Studio")
            .setMessage(create?"This protects your local clients and post library. Keep the PIN safe.":"Enter your local owner PIN to manage posts.")
            .setView(input).setCancelable(false)
            .setPositiveButton("Continue",(d,w)->{
                String pin=input.getText().toString();
                if(!PinGate.valid(pin)){
                    showError("PIN must be 4–8 digits");requestPin(create,confirm);return;
                }
                if(create){
                    if(confirm==null)requestPin(true,pin);
                    else if(!confirm.equals(pin)){
                        showError("PINs did not match. Try again.");requestPin(true,null);
                    }else if(PinGate.setup(this,pin))unlockAndLoad();
                    else{showError("Could not secure local PIN");requestPin(true,null);}
                }else if(PinGate.verify(this,pin))unlockAndLoad();
                else {showError("Incorrect PIN");requestPin(false,null);}
            }).setNegativeButton("Exit",(d,w)->finish()).create();
        dialog.show();
    }
    public void showError(String message) {runOnUiThread(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
    public void shareBackup(File backup) {
        Uri uri=new Uri.Builder().scheme("content").authority(MediaContentProvider.AUTHORITY).appendPath("backup").build();
        Intent send=new Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(getContentResolver(),"PostFlow Backup",uri));
        startActivity(Intent.createChooser(send,"Save full PostFlow backup"));
    }
    public void launchBackupPicker(){runOnUiThread(()->{
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,BACKUP_PICKER);
    });}
    private void restoreArchive(Uri uri){
        File mediaDir=MediaContentProvider.directory(this);
        java.util.List<File> staged=new java.util.ArrayList<>();
        try {
            JSONObject names=null; String json=null; long total=0;
            try(InputStream source=getContentResolver().openInputStream(uri);
                ZipInputStream input=new ZipInputStream(source)){
                ZipEntry entry;
                while((entry=input.getNextEntry())!=null){
                    if(entry.isDirectory())continue;
                    String name=entry.getName();
                    java.io.ByteArrayOutputStream small=new java.io.ByteArrayOutputStream();
                    if(name.equals("postflow-state.json")||name.equals("media-names.json")){
                        byte[] buf=new byte[16*1024]; int n;
                        while((n=input.read(buf))!=-1){
                            total+=n;
                            if(total>300L*1024*1024||small.size()+n>8_000_000)throw new IllegalArgumentException("Backup too large");
                            small.write(buf,0,n);
                        }
                        if(name.equals("postflow-state.json"))json=small.toString("UTF-8");
                        else names=new JSONObject(small.toString("UTF-8"));
                    }else if(name.startsWith("media/")){
                        String id=name.substring(6);
                        if(!MediaContentProvider.valid(id))throw new IllegalArgumentException("Unsafe backup entry");
                        File staging=new File(getCacheDir(),"restore-"+id);
                        staged.add(staging);
                        try(FileOutputStream out=new FileOutputStream(staging)){
                            byte[] buf=new byte[32*1024];int n;
                            while((n=input.read(buf))!=-1){
                                total+=n;if(total>300L*1024*1024)throw new IllegalArgumentException("Backup too large");
                                out.write(buf,0,n);
                            }
                        }
                    } else throw new IllegalArgumentException("Unexpected ZIP entry");
                    input.closeEntry();
                }
            }
            if(json==null)throw new IllegalArgumentException("Missing state");
            JSONObject restored=new JSONObject(json);
            if(restored.optJSONArray("posts")==null||restored.optJSONArray("events")==null)
                throw new IllegalArgumentException("Invalid state");
            for(File temp:staged){
                String id=temp.getName().substring("restore-".length());
                File output=MediaContentProvider.mediaFile(this,id);
                if(output.exists())output.delete();
                if(!temp.renameTo(output))throw new java.io.IOException("Media restore failed");
            }
            if(names!=null){
                android.content.SharedPreferences.Editor e=getSharedPreferences("postflow_media",0).edit();
                java.util.Iterator<String> keys=names.keys();
                while(keys.hasNext()){String key=keys.next();if(MediaContentProvider.valid(key))e.putString(key,names.optString(key));}
                e.apply();
            }
            new NativeBridge(this).syncState(json);
            EventScheduler.reschedule(this);
            android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(this);
            FestivalWidgetProvider.updateAll(this,manager,manager.getAppWidgetIds(new android.content.ComponentName(this,FestivalWidgetProvider.class)));
            String quoted=JSONObject.quote(json);
            runOnUiThread(()->{
                web.evaluateJavascript("window.applyNativeRestoredState("+quoted+")",null);
                Toast.makeText(this,"PostFlow backup restored",Toast.LENGTH_LONG).show();
            });
        }catch(Exception e){showError("Restore failed: "+e.getMessage());}
        finally{for(File f:staged)if(f.exists())f.delete();}
    }
    @Override public void onBackPressed() {
        if(web!=null)web.evaluateJavascript("if(document.querySelector('#overlay .modal'))closeModal();else if(page!=='home')route('home');else PostFlowNative.exitApp();",null);
        else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(web!=null){web.destroy();web=null;}
        super.onDestroy();
    }
}