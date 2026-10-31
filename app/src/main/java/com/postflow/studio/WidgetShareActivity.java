package com.postflow.studio;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.File;

/** User-initiated widget sharing. WhatsApp Status publishing always requires user confirmation. */
public final class WidgetShareActivity extends Activity {
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        String mediaId=getIntent().getStringExtra("media_id");
        if(!MediaContentProvider.valid(mediaId)){fail("No poster selected");return;}
        File media=MediaContentProvider.mediaFile(this,mediaId);
        if(!media.isFile()){fail("Poster unavailable. Open PostFlow and reattach it.");return;}
        Uri uri=new Uri.Builder().scheme("content").authority(MediaContentProvider.AUTHORITY)
                .appendPath(mediaId).build();
        String type=getContentResolver().getType(uri);
        if(type==null)type="image/jpeg";
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM,uri);
        String caption=getIntent().getStringExtra("caption");
        if(caption!=null&&!caption.isEmpty())send.putExtra(Intent.EXTRA_TEXT,caption);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(getContentResolver(),"PostFlow media",uri));
        try{startActivity(Intent.createChooser(send,"Share PostFlow poster"));}
        catch(ActivityNotFoundException e){fail("No compatible sharing app installed");return;}
        finish();
    }
    private void fail(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();finish();}
}
