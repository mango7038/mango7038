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
            try{android.content.SharedPreferences sp=c.getSharedPreferences(PREF,0);date=sp.getBoolean("follow_today_"+id,true)?LocalDate.now(EventScheduler.ZONE):LocalDate.parse(sp.getString(key,LocalDate.now(EventScheduler.ZONE).toString()));}
            catch(Exception e){date=LocalDate.now(EventScheduler.ZONE);}
            if(ACTION_TODAY.equals(action))date=LocalDate.now(EventScheduler.ZONE);
            if(ACTION_DATE.equals(action))date=date.plusDays(Math.max(-7,Math.min(7,intent.getIntExtra("delta",0))));
            c.getSharedPreferences(PREF,0).edit().putBoolean("follow_today_"+id,ACTION_TODAY.equals(action)).putString(key,date.toString()).putInt("item_"+id,ACTION_ITEM.equals(action)?Math.max(0,intent.getIntExtra("index",0)):0).apply();
            updateOne(c,AppWidgetManager.getInstance(c),id);
        }
    }

    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){updateAll(c,manager,ids);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,android.os.Bundle options){
        super.onAppWidgetOptionsChanged(c,manager,id,options);
        updateOne(c,manager,id);
    }
    private static int visibleDays(AppWidgetManager manager,int id){
        android.os.Bundle options=manager.getAppWidgetOptions(id);
        int width=options==null?0:options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,0);
        if(width<=0)return 5;
        if(width<255)return 3;
        if(width<355)return 5;
        return 7;
    }
    @Override public void onDeleted(Context c,int[] ids){
        android.content.SharedPreferences.Editor ed=c.getSharedPreferences(PREF,0).edit();
        for(int id:ids){ed.remove("day_"+id);ed.remove("item_"+id);ed.remove("follow_today_"+id);}ed.apply();
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
        try{day=prefs.getBoolean("follow_today_"+id,true)?today:LocalDate.parse(prefs.getString("day_"+id,today.toString()));}catch(Exception ex){day=today;}
        List<Item> posts=onDate(c,day);
        int index=Math.max(0,Math.min(prefs.getInt("item_"+id,0),Math.max(0,posts.size()-1)));
        Item current=posts.isEmpty()?null:posts.get(index);
        RemoteViews rv=new RemoteViews(c.getPackageName(),R.layout.widget_festival);
        rv.setTextViewText(R.id.widgetTitle,day.getMonth().getDisplayName(TextStyle.SHORT,Locale.ENGLISH).toUpperCase(Locale.ENGLISH)+" "+day.getYear());
        rv.setTextViewText(R.id.widgetSelectedDay,day.format(LABEL)+" • "+(posts.isEmpty()?"No posts":(index+1)+" / "+posts.size()));
        int shown=visibleDays(manager,id);\n        rv.setImageViewBitmap(R.id.widgetCalendar,weekBitmap(c,day,shown));
        rv.setOnClickPendingIntent(R.id.widgetPrevWeek,broadcast(c,id,20,ACTION_DATE,-shown,0));
        rv.setOnClickPendingIntent(R.id.widgetNextWeek,broadcast(c,id,21,ACTION_DATE,shown,0));
        int[] dateCells={R.id.widgetDate0,R.id.widgetDate1,R.id.widgetDate2,R.id.widgetDate3,R.id.widgetDate4,R.id.widgetDate5,R.id.widgetDate6};
        for(int i=0;i<dateCells.length;i++){
            rv.setViewVisibility(dateCells[i],i<shown?android.view.View.VISIBLE:android.view.View.GONE);
            if(i<shown)rv.setOnClickPendingIntent(dateCells[i],broadcast(c,id,30+i,ACTION_DATE,i-shown/2,0));
        }
        rv.setTextViewText(R.id.widgetNext,current==null?"No scheduled posts":current.title);
        rv.setTextViewText(R.id.widgetSecond,current==null?"Select another date":current.kind+" · "+current.status.replace('_',' '));
        rv.setTextViewText(R.id.widgetThird,current==null?"":"Preview · "+day.format(LABEL));
        rv.setOnClickPendingIntent(R.id.widgetPrevDay,broadcast(c,id,1,ACTION_DATE,-1,0));
        rv.setOnClickPendingIntent(R.id.widgetNextDay,broadcast(c,id,2,ACTION_DATE,1,0));
        rv.setOnClickPendingIntent(R.id.widgetToday,broadcast(c,id,3,ACTION_TODAY,0,0));
        rv.setOnClickPendingIntent(R.id.widgetPrevPost,broadcast(c,id,4,ACTION_ITEM,0,Math.max(0,index-1)));
        rv.setOnClickPendingIntent(R.id.widgetNextPost,broadcast(c,id,5,ACTION_ITEM,0,Math.min(posts.size()-1,index+1)));
        Intent open=new Intent(c,WidgetPreviewActivity.class);
        if(current!=null){
            open.putExtra("media_id",current.mediaId);
            open.putExtra("caption",current.caption);
            open.putExtra("title",current.title);
        }
        open.setData(Uri.parse("postflow-preview://widget/"+id+"/"+day.toString()+"/"+index));
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
    /** Status markers for the visible week: Story green, feed Post blue, Festival red. */
    private static int[] markers(JSONObject root,LocalDate day){
        boolean story=false,post=false,festival=false;
        JSONArray posts=root.optJSONArray("posts");
        if(posts!=null)for(int i=0;i<posts.length()&&i<2000;i++){
            JSONObject p=posts.optJSONObject(i);
            if(p==null||!day.toString().equals(p.optString("date")))continue;
            String status=p.optString("status","");
            if(!("scheduled".equals(status)||"published".equals(status)||"processing".equals(status)||"uploading".equals(status)||"awaiting_verification".equals(status)))continue;
            String type=(p.optString("kind","")+" "+p.optString("type","")+" "+p.optString("placement","")).toLowerCase(Locale.ROOT);
            JSONArray targets=p.optJSONArray("targets");
            if(targets!=null)for(int j=0;j<targets.length();j++)type+=" "+targets.optString(j).toLowerCase(Locale.ROOT);
            JSONObject accountTargets=p.optJSONObject("accountTargets");
            if(accountTargets!=null)type+=" "+accountTargets.toString().toLowerCase(Locale.ROOT);
            if(type.contains("story"))story=true;
            if(type.contains("feed")||type.contains("post")||(!type.contains("story")&&!type.contains("reel")))post=true;
        }
        JSONArray events=root.optJSONArray("events");
        if(events!=null)for(int i=0;i<events.length()&&i<500;i++){
            JSONObject e=events.optJSONObject(i);if(e==null)continue;
            try{
                LocalDate eventDate=LocalDate.parse(e.optString("date",""));
                if(eventDate.equals(day)||(e.optBoolean("repeat",false)&&eventDate.getMonthValue()==day.getMonthValue()&&eventDate.getDayOfMonth()==day.getDayOfMonth())){
                    if("festival".equalsIgnoreCase(e.optString("type","festival")))festival=true;
                }
            }catch(Exception ignored){}
        }
        return new int[]{story?0xff28c987:0,post?0xff3996ff:0,festival?0xffff5267:0};
    }
    private static Bitmap weekBitmap(Context c,LocalDate selected,int shown){
        int w=70*shown,h=115;Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        LocalDate start=selected.minusDays(shown/2);
        JSONObject data=state(c);
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
            int[] dots=markers(data,date);int count=0;for(int color:dots)if(color!=0)count++;
            if(count>0){int n=0;for(int color:dots){if(color==0)continue;paint.setColor(color);canvas.drawCircle(cx+(n-(count-1)/2f)*12,98,4.1f,paint);n++;}}
        }
        return b;
    }
}