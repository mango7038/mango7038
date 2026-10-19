package com.postflow.studio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native date-selectable PostFlow widget. Widget state stays local to the device. */
public final class FestivalWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_DATE="com.postflow.studio.widget.DATE";
    private static final String ACTION_ITEM="com.postflow.studio.widget.ITEM";
    private static final String ACTION_TODAY="com.postflow.studio.widget.TODAY";
    private static final String PREF="postflow_calendar_widget";
    private static final DateTimeFormatter LABEL=DateTimeFormatter.ofPattern("dd MMM yyyy",Locale.ENGLISH);

    private static final class Item {
        final String title,kind,status,mediaId,caption,date;
        Item(String title,String kind,String status,String mediaId,String caption,String date){
            this.title=title;this.kind=kind;this.status=status;this.mediaId=mediaId;this.caption=caption;this.date=date;
        }
    }

    @Override public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);
        String action=intent.getAction();
        if(ACTION_DATE.equals(action)||ACTION_ITEM.equals(action)||ACTION_TODAY.equals(action)){
            int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);
            if(id<0)return;
            String key="day_"+id;
            LocalDate date;
            try{date=LocalDate.parse(c.getSharedPreferences(PREF,0).getString(key,LocalDate.now(EventScheduler.ZONE).toString()));}
            catch(Exception e){date=LocalDate.now(EventScheduler.ZONE);}
            if(ACTION_TODAY.equals(action))date=LocalDate.now(EventScheduler.ZONE);
            if(ACTION_DATE.equals(action))date=date.plusDays(Math.max(-7,Math.min(7,intent.getIntExtra("delta",0))));
            c.getSharedPreferences(PREF,0).edit().putString(key,date.toString()).putInt("item_"+id,ACTION_ITEM.equals(action)?Math.max(0,intent.getIntExtra("index",0)):0).apply();
            updateOne(c,AppWidgetManager.getInstance(c),id);
        }
    }

    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){updateAll(c,manager,ids);}
    @Override public void onDeleted(Context c,int[] ids){
        android.content.SharedPreferences.Editor ed=c.getSharedPreferences(PREF,0).edit();
        for(int id:ids){ed.remove("day_"+id);ed.remove("item_"+id);}ed.apply();
    }
    static void updateAll(Context c,AppWidgetManager manager,int[] ids){
        for(int id:ids)updateOne(c,manager,id);
    }

    private static JSONObject state(Context c){
        try{
            File file=new File(c.getFilesDir(),"state.json");
            if(file.isFile()&&file.length()<20_000_000)
                return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        }catch(Exception ignored){}
        try{return new JSONObject(c.getSharedPreferences("postflow",0).getString("state","{}"));}
        catch(Exception ignored){return new JSONObject();}
    }
    private static List<Item> onDate(Context c,LocalDate day){
        JSONObject root=state(c);
        List<Item> out=new ArrayList<>();
        JSONArray posts=root.optJSONArray("posts");
        if(posts!=null)for(int i=0;i<posts.length()&&i<1000;i++){
            JSONObject p=posts.optJSONObject(i);
            if(p==null||!day.toString().equals(p.optString("date")))continue;
            String status=p.optString("status","draft");
            if("scheduled".equals(status)||"published".equals(status)||"draft".equals(status)||"processing".equals(status)||"uploading".equals(status)||"awaiting_verification".equals(status)||"attention".equals(status))
                out.add(new Item(p.optString("title","Post"),"POST",status,p.optString("mediaId",""),p.optString("caption",""),p.optString("date","")));
        }
        JSONArray events=root.optJSONArray("events");
        if(events!=null)for(int i=0;i<events.length()&&i<500;i++){
            JSONObject e=events.optJSONObject(i);if(e==null)continue;
            try{
                LocalDate ed=LocalDate.parse(e.optString("date"));
                boolean same=ed.equals(day)||(e.optBoolean("repeat",false)&&ed.getMonth()==day.getMonth()&&ed.getDayOfMonth()==day.getDayOfMonth());
                if(same&&!out.stream().anyMatch(p->p.title.equalsIgnoreCase(e.optString("title"))))
                    out.add(new Item(e.optString("title","Festival"),"FESTIVAL","Poster reminder","","",day.toString()));
            }catch(Exception ignored){}
        }
        return out;
    }
    private static PendingIntent broadcast(Context c,int id,int request,String action,int delta,int index){
        Intent intent=new Intent(c,FestivalWidgetProvider.class).setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("delta",delta).putExtra("index",index);
        intent.setData(Uri.parse("postflow-widget://"+id+"/"+request));
        return PendingIntent.getBroadcast(c,id*100+request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void updateOne(Context c,AppWidgetManager manager,int id){
        LocalDate today=LocalDate.now(EventScheduler.ZONE),day;
        android.content.SharedPreferences prefs=c.getSharedPreferences(PREF,0);
        try{day=LocalDate.parse(prefs.getString("day_"+id,today.toString()));}catch(Exception ex){day=today;}
        List<Item> posts=onDate(c,day);
        int index=Math.max(0,Math.min(prefs.getInt("item_"+id,0),Math.max(0,posts.size()-1)));
        Item current=posts.isEmpty()?null:posts.get(index);
        RemoteViews rv=new RemoteViews(c.getPackageName(),R.layout.widget_festival);
        rv.setTextViewText(R.id.widgetTitle,day.getMonth().getDisplayName(TextStyle.SHORT,Locale.ENGLISH).toUpperCase(Locale.ENGLISH)+" "+day.getYear());
        rv.setTextViewText(R.id.widgetSelectedDay,day.format(LABEL)+" • "+(posts.isEmpty()?"No posts":(index+1)+" / "+posts.size()));
        rv.setImageViewBitmap(R.id.widgetCalendar,weekBitmap(c,day));
        rv.setTextViewText(R.id.widgetNext,current==null?"No scheduled posts":current.title);
        rv.setTextViewText(R.id.widgetSecond,current==null?"Select another date":current.kind+" · "+current.status.replace('_',' '));
        rv.setTextViewText(R.id.widgetThird,current==null?"":"Preview · "+day.format(LABEL));
        rv.setOnClickPendingIntent(R.id.widgetPrevDay,broadcast(c,id,1,ACTION_DATE,-1,0));
        rv.setOnClickPendingIntent(R.id.widgetNextDay,broadcast(c,id,2,ACTION_DATE,1,0));
        rv.setOnClickPendingIntent(R.id.widgetToday,broadcast(c,id,3,ACTION_TODAY,0,0));
        rv.setOnClickPendingIntent(R.id.widgetPrevPost,broadcast(c,id,4,ACTION_ITEM,0,Math.max(0,index-1)));
        rv.setOnClickPendingIntent(R.id.widgetNextPost,broadcast(c,id,5,ACTION_ITEM,0,Math.min(posts.size()-1,index+1)));
        Intent open=new Intent(c,MainActivity.class);
        rv.setOnClickPendingIntent(R.id.widgetOpen,PendingIntent.getActivity(c,id*100+6,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Bitmap preview=current==null?null:thumbnail(c,current.mediaId);
        if(preview!=null){
            rv.setImageViewBitmap(R.id.widgetPoster,preview);
            rv.setViewVisibility(R.id.widgetPoster,android.view.View.VISIBLE);
        }else rv.setViewVisibility(R.id.widgetPoster,android.view.View.GONE);
        boolean shareable=current!=null&&MediaContentProvider.valid(current.mediaId)&&MediaContentProvider.mediaFile(c,current.mediaId).isFile();
        rv.setViewVisibility(R.id.widgetShare,shareable?android.view.View.VISIBLE:android.view.View.GONE);
        if(shareable){
            Intent share=new Intent(c,WidgetShareActivity.class);
            share.putExtra("media_id",current.mediaId);
            share.putExtra("caption",current.caption);
            share.putExtra("title",current.title);
            share.setData(Uri.parse("postflow-share://widget/"+id+"/"+current.mediaId));
            rv.setOnClickPendingIntent(R.id.widgetShare,PendingIntent.getActivity(c,id*100+7,share,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        }
        manager.updateAppWidget(id,rv);
    }
    private static Bitmap thumbnail(Context c,String mediaId){
        if(!MediaContentProvider.valid(mediaId))return null;
        File file=MediaContentProvider.mediaFile(c,mediaId);
        if(!file.isFile())return null;
        String type=c.getSharedPreferences("postflow_media",0).getString(mediaId,"");
        if(type.endsWith(".mp4")||type.endsWith(".mov"))return null;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),opts);
        opts.inSampleSize=Math.max(1,Math.max(opts.outWidth/180,opts.outHeight/180));
        opts.inJustDecodeBounds=false;
        try{return BitmapFactory.decodeFile(file.getAbsolutePath(),opts);}catch(Exception e){return null;}
    }
    private static Bitmap weekBitmap(Context c,LocalDate selected){
        int w=490,h=115;Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        LocalDate start=selected.minusDays(3);
        for(int i=0;i<7;i++){
            LocalDate date=start.plusDays(i);float cx=35+70*i;
            if(date.equals(selected)){paint.setColor(0xff7655db);canvas.drawRoundRect(new RectF(cx-31,3,cx+31,110),20,20,paint);}
            else {paint.setColor(0xff30324c);canvas.drawRoundRect(new RectF(cx-31,3,cx+31,110),20,20,paint);}
            paint.setColor(0xffd4cbee);paint.setTextSize(17);
            String wd=date.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
            wd=wd.substring(0,Math.min(3,wd.length()));
            canvas.drawText(wd,cx-paint.measureText(wd)/2,36,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(29);paint.setFakeBoldText(true);
            String label=String.valueOf(date.getDayOfMonth());
            canvas.drawText(label,cx-paint.measureText(label)/2,80,paint);
            paint.setFakeBoldText(false);
        }
        return b;
    }
}