package com.postflow.studio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/** Widget View opens the selected poster, not the PostFlow home screen. */
public final class WidgetPreviewActivity extends Activity {
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private TextView button(String text){
        TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.WHITE);
        v.setTextSize(16);v.setGravity(Gravity.CENTER);v.setPadding(dp(16),dp(12),dp(16),dp(12));
        return v;
    }
    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff101322);
        getWindow().setNavigationBarColor(0xff101322);
        final String mediaId=getIntent().getStringExtra("media_id");
        final String caption=getIntent().getStringExtra("caption");
        final String title=getIntent().getStringExtra("title");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(15),dp(12),dp(15),dp(18));root.setBackgroundColor(0xff101322);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView close=button("✕");close.setOnClickListener(v->finish());
        header.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView heading=new TextView(this);heading.setText(title==null?"Poster Preview":title);
        heading.setTextColor(Color.WHITE);heading.setTextSize(18);heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        ImageView poster=new ImageView(this);poster.setAdjustViewBounds(true);poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(0xff20263b);
        Bitmap bitmap=null;
        if(MediaContentProvider.valid(mediaId)){
            File file=MediaContentProvider.mediaFile(this,mediaId);
            if(file.isFile()){
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
                if(bounds.outWidth>0 && bounds.outHeight>0){
                    BitmapFactory.Options sample=new BitmapFactory.Options();
                    sample.inSampleSize=Math.max(1,Math.max(bounds.outWidth/2048,bounds.outHeight/2048));
                    try{bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),sample);}catch(Exception ignored){}
                }
            }
        }
        if(bitmap!=null){poster.setImageBitmap(bitmap);content.addView(poster,new LinearLayout.LayoutParams(-1,-2));}
        else {
            TextView message=new TextView(this);
            message.setText("Poster image unavailable on this device. Open PostFlow and restore or reattach the media.");
            message.setTextColor(0xffd1cae7);message.setTextSize(15);message.setGravity(Gravity.CENTER);
            content.addView(message,new LinearLayout.LayoutParams(-1,dp(260)));
        }
        if(caption!=null&&!caption.isEmpty()){
            TextView text=new TextView(this);text.setText(caption);text.setTextColor(0xffd4cee6);
            text.setTextSize(13);text.setPadding(0,dp(16),0,dp(8));
            content.addView(text);
        }
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        TextView done=button("Close");actions.addView(done,new LinearLayout.LayoutParams(0,dp(52),1));
        done.setOnClickListener(v->finish());
        if(bitmap!=null){
            TextView share=button("Share");share.setBackgroundColor(0xff7253d7);
            actions.addView(share,new LinearLayout.LayoutParams(0,dp(52),1));
            share.setOnClickListener(v->{
                Intent i=new Intent(this,WidgetShareActivity.class);
                i.putExtra("media_id",mediaId);i.putExtra("caption",caption);i.putExtra("title",title);
                startActivity(i);
            });
        }
        root.addView(actions);setContentView(root);
    }
}
