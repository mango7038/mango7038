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
    private NativeGlassBar glassBar;
    private android.view.View glassScrim;
    private android.widget.FrameLayout glassRoot;
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
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                if(url!=null && url.startsWith("file:///android_asset/")){
                    // Make the native bar the sole navigation; prevent the HTML bar showing beneath it.
                    view.evaluateJavascript("document.body.classList.add('native-glass');document.querySelector('#nav')?.setAttribute('aria-hidden','true');",null);
                    String widgetAction=getIntent()!=null?getIntent().getStringExtra("postflow_widget_action"):null;
                    if("compose".equals(widgetAction)||"openZip".equals(widgetAction)){
                        getIntent().removeExtra("postflow_widget_action");
                        view.evaluateJavascript("setTimeout(function(){"+widgetAction+"()},120)",null);
                    }
                }
            }
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
        EventScheduler.reschedule(this);
        showPinGate();
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        String widgetAction=intent!=null?intent.getStringExtra("postflow_widget_action"):null;
        if(web!=null && ("compose".equals(widgetAction)||"openZip".equals(widgetAction))){
            intent.removeExtra("postflow_widget_action");
            web.evaluateJavascript(widgetAction+"()",null);
        }
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
    private void unlockAndLoad(){
        installGlassChrome();
        web.loadUrl("file:///android_asset/index.html");
    }
    private void installGlassChrome(){
        if(glassRoot!=null)return;
        glassRoot=new android.widget.FrameLayout(this);
        if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);
        glassRoot.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));
        // Fade the scrolled page below the fixed bar to keep navigation text legible.
        glassScrim=new android.view.View(this);
        android.graphics.drawable.GradientDrawable fade=new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x000b1020,0xb80b1020,0xf90b1020,0xff0b1020});
        glassScrim.setBackground(fade);
        glassScrim.setClickable(false);
        glassScrim.setFocusable(false);
        android.widget.FrameLayout.LayoutParams scrimParams=new android.widget.FrameLayout.LayoutParams(
            -1,Math.round(152*getResources().getDisplayMetrics().density),android.view.Gravity.BOTTOM);
        glassRoot.addView(glassScrim,scrimParams);
        glassBar=new NativeGlassBar(this,index->{
            String code;
            switch(index){
                case 0:code="route('home')";break;
                case 1:code="route('calendar')";break;
                case 2:code="compose()";break;
                case 3:code="route('library')";break;
                default:code="openMenu()";break;
            }
            web.evaluateJavascript(code,null);
        });
        float den=getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(
            -1,Math.round(66*den),android.view.Gravity.BOTTOM);
        lp.leftMargin=Math.round(12*den);lp.rightMargin=Math.round(12*den);
        lp.bottomMargin=Math.round(12*den);
        glassRoot.addView(glassBar,lp);
        setContentView(glassRoot);
    }
    void setGlassNavigationVisible(boolean visible){
        runOnUiThread(()->{
            int visibility=visible?android.view.View.VISIBLE:android.view.View.GONE;
            if(glassBar!=null)glassBar.setVisibility(visibility);
            if(glassScrim!=null)glassScrim.setVisibility(visibility);
        });
    }
    void setGlassSelectedTab(int index){
        runOnUiThread(()->{if(glassBar!=null)glassBar.select(index);});
    }
    private void showPinGate(){
        if(!PinGate.hasPin(this)){
            requestPin(true, null);
        }else requestPin(false,null);
    }

    private void requestPin(boolean create,String confirm){
        final boolean light=(getResources().getConfiguration().uiMode &
            android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_NO;
        final int bg=light?0xfff5f6fa:0xff0d1120, fg=light?0xff202840:0xfff7f7ff,muted=light?0xff69748a:0xffa9b4d2;
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
        android.widget.LinearLayout root=new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setGravity(android.view.Gravity.CENTER);
        int pad=(int)(26*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        scroll.addView(root);
        android.widget.TextView logo=new android.widget.TextView(this);
        logo.setText("✦");logo.setTextColor(0xffa78bfa);logo.setTextSize(46);logo.setGravity(android.view.Gravity.CENTER);
        root.addView(logo);
        android.widget.TextView brand=new android.widget.TextView(this);
        brand.setText("PostFlow Studio");brand.setTextColor(fg);brand.setTextSize(26);brand.setGravity(android.view.Gravity.CENTER);
        brand.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(brand);
        android.widget.TextView subtitle=new android.widget.TextView(this);
        subtitle.setText(create?(confirm==null?"Create your private 4–8 digit PIN":"Confirm your new PIN"):"Welcome back. Enter your PIN to unlock.");
        subtitle.setTextColor(muted);subtitle.setTextSize(15);subtitle.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams subtitleMargin=new android.widget.LinearLayout.LayoutParams(-1,-2);
        subtitleMargin.topMargin=(int)(12*getResources().getDisplayMetrics().density);
        subtitleMargin.bottomMargin=(int)(22*getResources().getDisplayMetrics().density);
        root.addView(subtitle,subtitleMargin);
        EditText input=new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSingleLine(true);input.setTextColor(fg);input.setHintTextColor(muted);
        input.setHint("••••••");input.setTextSize(23);input.setGravity(android.view.Gravity.CENTER);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        android.graphics.drawable.GradientDrawable inputBg=new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(light?0xffffffff:0xff1c2541);
        inputBg.setCornerRadius(18*getResources().getDisplayMetrics().density);
        inputBg.setStroke((int)(getResources().getDisplayMetrics().density),light?0xffd7ddec:0xff39415d);
        input.setBackground(inputBg);input.setPadding(16,18,16,18);
        root.addView(input,new android.widget.LinearLayout.LayoutParams(-1,-2));
        android.widget.TextView error=new android.widget.TextView(this);
        error.setTextColor(0xffe7677f);error.setTextSize(13);error.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams errorParams=new android.widget.LinearLayout.LayoutParams(-1,-2);
        errorParams.topMargin=(int)(10*getResources().getDisplayMetrics().density);
        root.addView(error,errorParams);
        android.widget.CheckBox reveal=new android.widget.CheckBox(this);
        reveal.setText("Show PIN");reveal.setTextColor(muted);
        reveal.setOnCheckedChangeListener((button,checked)->{
            input.setInputType(InputType.TYPE_CLASS_NUMBER|(checked?InputType.TYPE_NUMBER_VARIATION_NORMAL:InputType.TYPE_NUMBER_VARIATION_PASSWORD));
            input.setSelection(input.length());
        });root.addView(reveal);
        android.widget.Button next=new android.widget.Button(this);
        next.setText(create?"Continue":"Unlock Workspace");
        next.setTextColor(0xffffffff);next.setAllCaps(false);next.setTextSize(16);
        android.graphics.drawable.GradientDrawable purple=new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff8269ed,0xffa187f8});
        purple.setCornerRadius(16*getResources().getDisplayMetrics().density);next.setBackground(purple);
        android.widget.LinearLayout.LayoutParams nextParams=new android.widget.LinearLayout.LayoutParams(-1,(int)(56*getResources().getDisplayMetrics().density));
        nextParams.topMargin=(int)(12*getResources().getDisplayMetrics().density);root.addView(next,nextParams);
        android.widget.TextView exit=new android.widget.TextView(this);exit.setText("Exit app");exit.setTextColor(muted);
        exit.setGravity(android.view.Gravity.CENTER);exit.setPadding(12,23,12,12);root.addView(exit);
        exit.setOnClickListener(v->finish());
        next.setOnClickListener(v->{
            String pin=input.getText().toString();
            if(!PinGate.valid(pin)){error.setText("PIN must contain 4–8 digits.");return;}
            if(create){
                if(confirm==null)requestPin(true,pin);
                else if(!confirm.equals(pin)){error.setText("PINs do not match. Try again.");input.setText("");}
                else if(PinGate.setup(this,pin)){setContentView(web);unlockAndLoad();}
                else error.setText("Could not save PIN securely.");
            }else if(PinGate.verify(this,pin)){setContentView(web);unlockAndLoad();}
            else{error.setText("Incorrect PIN. Please try again.");input.setText("");}
        });
        setContentView(scroll);
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