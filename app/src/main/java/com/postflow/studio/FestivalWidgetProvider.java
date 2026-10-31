package com.postflow.studio;

import android.app.PendingIntent;
import android.app.AlarmManager;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
    private static final String ACTION_RESET="com.postflow.studio.widget.RESET";
    private static final long IDLE_MS=10*60*1000L;
    private static final String PREF="postflow_calendar_widget";
    private static final DateTimeFormatter LABEL=DateTimeFormatter.ofPattern("dd MMM yyyy",Locale.ENGLISH);

    private static final class Item {
        final String title,kind,status,mediaId,caption,date,account,time,remoteUrl;
        Item(String title,String kind,String status,String mediaId,String caption,String date,String account,String time,String remoteUrl){
            this.title=title;this.kind=kind;this.status=status;this.mediaId=mediaId;this.caption=caption;this.date=date;this.account=account;this.time=time;this.remoteUrl=remoteUrl;
        }
    }

    @Override public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);
        String action=intent.getAction();
        if(ACTION_DATE.equals(action)||ACTION_ITEM.equals(action)||ACTION_TODAY.equals(action)||ACTION_RESET.equals(action)){
            int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);
            if(id<0)return;
            String key="day_"+id;
            LocalDate date;
            try{android.content.SharedPreferences sp=c.getSharedPreferences(PREF,0);date=sp.getBoolean("follow_today_"+id,true)?LocalDate.now(EventScheduler.ZONE):LocalDate.parse(sp.getString(key,LocalDate.now(EventScheduler.ZONE).toString()));}
            catch(Exception e){date=LocalDate.now(EventScheduler.ZONE);}
            if(ACTION_RESET.equals(action)){
                long deadline=c.getSharedPreferences(PREF,0).getLong("idle_until_"+id,0);
                if(deadline==0||System.currentTimeMillis()<deadline)return;
                date=LocalDate.now(EventScheduler.ZONE);
            }
            if(ACTION_TODAY.equals(action))date=LocalDate.now(EventScheduler.ZONE);
            if(ACTION_DATE.equals(action))date=date.plusDays(Math.max(-7,Math.min(7,intent.getIntExtra("delta",0))));
            c.getSharedPreferences(PREF,0).edit().putBoolean("follow_today_"+id,ACTION_TODAY.equals(action)||ACTION_RESET.equals(action)).putString(key,date.toString()).putInt("item_"+id,ACTION_ITEM.equals(action)?Math.max(0,intent.getIntExtra("index",0)):0).apply();
            if(ACTION_DATE.equals(action)||ACTION_ITEM.equals(action))scheduleIdleReset(c,id);
            else cancelIdleReset(c,id);
            updateOne(c,AppWidgetManager.getInstance(c),id);
        }
    }

    private static PendingIntent resetIntent(Context c,int id){
        Intent intent=new Intent(c,FestivalWidgetProvider.class).setAction(ACTION_RESET);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        intent.setData(Uri.parse("postflow-widget-reset://"+id));
        return PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void cancelIdleReset(Context c,int id){
        android.app.AlarmManager alarm=(android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(alarm!=null)alarm.cancel(resetIntent(c,id));
        c.getSharedPreferences(PREF,0).edit().remove("idle_until_"+id).apply();
    }
    private static void scheduleIdleReset(Context c,int id){
        long deadline=System.currentTimeMillis()+IDLE_MS;
        c.getSharedPreferences(PREF,0).edit().putLong("idle_until_"+id,deadline).apply();
        android.app.AlarmManager alarm=(android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(alarm!=null)alarm.set(AlarmManager.RTC,deadline,resetIntent(c,id));
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
        if(width<220)return 3;
        if(width<330)return 5;
        return 7;
    }
    @Override public void onDeleted(Context c,int[] ids){
        android.content.SharedPreferences.Editor ed=c.getSharedPreferences(PREF,0).edit();
        for(int id:ids){ed.remove("day_"+id);ed.remove("item_"+id);ed.remove("follow_today_"+id);ed.remove("idle_until_"+id);cancelIdleReset(c,id);}ed.apply();
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
    /** Only live publishing jobs generate Story/Feed markers or previews. All accounts are visible. */
    private static boolean isActive(String status){
        return "scheduled".equals(status)||"uploading".equals(status)||"processing".equals(status)||"awaiting_verification".equals(status);
    }
    private static String postAccount(JSONObject root,JSONObject post){
        JSONArray picked=post.optJSONArray("wpSelected");
        if(picked!=null&&picked.length()>0){
            String first=picked.optString(0,"");
            int sep=first.lastIndexOf(':');
            if(sep>0){
                String id=first.substring(0,sep);
                JSONArray accounts=root.optJSONArray("wpAccounts");
                if(accounts!=null)for(int i=0;i<accounts.length();i++){
                    JSONObject a=accounts.optJSONObject(i);
                    if(a!=null&&(a.optString("platform")+"_"+a.optString("external_id")).equals(id))
                        return "@"+a.optString("username",a.optString("name","Instagram"));
                }
            }
        }
        JSONArray spaces=post.optJSONArray("workspaces");
        JSONArray accounts=root.optJSONArray("wpAccounts");
        if(spaces!=null&&accounts!=null)for(int i=0;i<spaces.length();i++){
            String key=spaces.optString(i);
            for(int j=0;j<accounts.length();j++){
                JSONObject a=accounts.optJSONObject(j);
                if(a!=null&&(a.optString("platform")+":"+a.optString("external_id")).equals(key))
                    return "@"+a.optString("username",a.optString("name","Instagram"));
            }
        }
        return "Connected account";
    }
    private static JSONObject findPost(JSONObject root,String id){
        JSONArray posts=root.optJSONArray("posts");if(posts==null)return null;
        for(int i=0;i<posts.length();i++){JSONObject p=posts.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p;}
        return null;
    }
    private static String istDay(long unix){
        try{return Instant.ofEpochSecond(unix).atZone(EventScheduler.ZONE).toLocalDate().toString();}
        catch(Exception ignored){return "";}
    }
    private static String istTime(long unix){
        try{return Instant.ofEpochSecond(unix).atZone(EventScheduler.ZONE).format(DateTimeFormatter.ofPattern("hh:mm a",Locale.ENGLISH));}
        catch(Exception ignored){return "";}
    }
    private static List<Item> onDate(Context c,LocalDate day){return onDate(state(c),day);}
    private static List<Item> onDate(JSONObject root,LocalDate day){
        List<Item> out=new ArrayList<>();
        Set<String> handledPosts=new HashSet<>();
        Set<String> handledJobs=new HashSet<>();
        JSONObject pending=root.optJSONObject("wpPendingJobs");
        JSONArray server=root.optJSONArray("wpServerJobs");
        if(server!=null)for(int i=0;i<server.length()&&i<2000;i++){
            JSONObject job=server.optJSONObject(i);
            if(job==null||!isActive(job.optString("status")))continue;
            long unix=job.optLong("scheduled_at",0);
            if(!day.toString().equals(istDay(unix)))continue;
            String jobId=String.valueOf(job.optInt("id",0));
            if(!handledJobs.add(jobId))continue;
            JSONObject local=null;
            if(pending!=null){
                java.util.Iterator<String> keys=pending.keys();
                while(keys.hasNext()){
                    JSONObject tracked=pending.optJSONObject(keys.next());
                    if(tracked!=null&&jobId.equals(String.valueOf(tracked.optInt("serverId",-1)))){
                        local=findPost(root,tracked.optString("postId"));break;
                    }
                }
            }
            String account=job.optString("account_name",job.optString("platform","Instagram"));
            String display=account.startsWith("@")?account:"@"+account;
            String title=local!=null?local.optString("title","Post"):job.optString("title","Scheduled "+job.optString("placement","post"));
            String mediaId=local!=null?local.optString("mediaId",""):"";
            String url=job.optString("media_preview_url","");
            out.add(new Item(title,job.optString("placement","feed").equals("story")?"STORY":"POST",
                    job.optString("status"),mediaId,job.optString("caption",""),day.toString(),display,
                    istTime(unix),url));
            if(local!=null)handledPosts.add(local.optString("id"));
        }
        if(pending!=null){
            java.util.Iterator<String> keys=pending.keys();
            while(keys.hasNext()){
                JSONObject tracked=pending.optJSONObject(keys.next());
                if(tracked==null||!isActive(tracked.optString("status")))continue;
                // A WordPress job's authoritative final status overrides stale local upload state.
                boolean onServer=false;
                if(server!=null)for(int j=0;j<server.length();j++){
                    JSONObject saved=server.optJSONObject(j);
                    if(saved!=null&&saved.optInt("id",-1)==tracked.optInt("serverId",-2)){
                        onServer=true;break;
                    }
                }
                if(onServer)continue;
                String postId=tracked.optString("postId","");
                JSONObject p=findPost(root,postId);
                if(p==null)continue;
                long scheduled=tracked.optLong("scheduled_at",0);
                String date=scheduled>0?istDay(scheduled):p.optString("date");
                if(!day.toString().equals(date))continue;
                String placement=tracked.optString("placement","feed");
                String account=tracked.optString("account",postAccount(root,p));
                String display=account.startsWith("@")?account:"@"+account;
                out.add(new Item(p.optString("title","Post"),"story".equals(placement)?"STORY":"POST",
                        tracked.optString("status"),p.optString("mediaId",""),
                        p.optString("caption",""),date,display,
                        scheduled>0?istTime(scheduled):p.optString("time",""),""));
                handledPosts.add(postId);
            }
        }
        JSONArray posts=root.optJSONArray("posts");
        if(posts!=null)for(int i=0;i<posts.length()&&i<1500;i++){
            JSONObject p=posts.optJSONObject(i);
            if(p==null||handledPosts.contains(p.optString("id"))
                ||!day.toString().equals(p.optString("date"))||!isActive(p.optString("status")))continue;
            // If the associated server job was cancelled/published, never keep a stale local marker.
            boolean hasTracked=false;
            if(pending!=null){java.util.Iterator<String> keys=pending.keys();while(keys.hasNext()){
                JSONObject tracked=pending.optJSONObject(keys.next());
                if(tracked!=null&&p.optString("id").equals(tracked.optString("postId"))){hasTracked=true;break;}
            }}
            if(hasTracked)continue;
            String type=(p.optString("placement","")+" "+p.optString("kind","")+" "+p.optJSONArray("targets")).toLowerCase(Locale.ROOT);
            out.add(new Item(p.optString("title","Post"),type.contains("story")?"STORY":"POST",
                p.optString("status"),p.optString("mediaId",""),p.optString("caption",""),
                day.toString(),postAccount(root,p),p.optString("time",""),""));
        }
        JSONArray events=root.optJSONArray("events");
        if(events!=null)for(int i=0;i<events.length()&&i<500;i++){
            JSONObject e=events.optJSONObject(i);if(e==null||e.optBoolean("cancelled",false))continue;
            try{
                LocalDate ed=LocalDate.parse(e.optString("date"));
                boolean same=ed.equals(day)||(e.optBoolean("repeat",false)&&ed.getMonth()==day.getMonth()&&ed.getDayOfMonth()==day.getDayOfMonth());
                if(same&&!out.stream().anyMatch(p->p.title.equalsIgnoreCase(e.optString("title"))))
                    out.add(new Item(e.optString("title","Festival"),"FESTIVAL","Reminder","","",day.toString(),"Festival","", ""));
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
    private static PendingIntent appAction(Context c,int widgetId,String route,int code){
        Intent launch=new Intent(c,MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra("postflow_widget_action",route);
        launch.setData(Uri.parse("postflow-widget-action://"+widgetId+"/"+route));
        return PendingIntent.getActivity(c,widgetId*100+code,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void updateOne(Context c,AppWidgetManager manager,int id){
        LocalDate today=LocalDate.now(EventScheduler.ZONE),day;
        android.content.SharedPreferences prefs=c.getSharedPreferences(PREF,0);
        try{boolean expired=!prefs.getBoolean("follow_today_"+id,true)&&prefs.getLong("idle_until_"+id,0)>0&&System.currentTimeMillis()>=prefs.getLong("idle_until_"+id,0);if(expired){prefs.edit().putBoolean("follow_today_"+id,true).remove("idle_until_"+id).apply();}day=(expired||prefs.getBoolean("follow_today_"+id,true))?today:LocalDate.parse(prefs.getString("day_"+id,today.toString()));}catch(Exception ex){day=today;}
        List<Item> posts=onDate(c,day);
        int index=Math.max(0,Math.min(prefs.getInt("item_"+id,0),Math.max(0,posts.size()-1)));
        Item current=posts.isEmpty()?null:posts.get(index);
        RemoteViews rv=new RemoteViews(c.getPackageName(),R.layout.widget_festival);
        rv.setTextViewText(R.id.widgetTitle,day.getMonth().getDisplayName(TextStyle.SHORT,Locale.ENGLISH).toUpperCase(Locale.ENGLISH)+" "+day.getYear());
        rv.setTextViewText(R.id.widgetSelectedDay,day.format(LABEL));
        rv.setTextViewText(R.id.widgetPostPosition,posts.isEmpty()?"0/0":(index+1)+"/"+posts.size());
        // As in GraphicaNest's reference, arrows stay visible even without a post:
        // navigate posts when multiple exist, otherwise navigate adjacent dates.
        rv.setViewVisibility(R.id.widgetPrevPost,android.view.View.VISIBLE);
        rv.setViewVisibility(R.id.widgetNextPost,android.view.View.VISIBLE);
        int shown=7;
        rv.setImageViewBitmap(R.id.widgetCalendar,weekBitmap(c,day,shown));
        rv.setOnClickPendingIntent(R.id.widgetPrevWeek,broadcast(c,id,20,ACTION_DATE,-shown,0));
        rv.setOnClickPendingIntent(R.id.widgetNextWeek,broadcast(c,id,21,ACTION_DATE,shown,0));
        int[] dateCells={R.id.widgetDate0,R.id.widgetDate1,R.id.widgetDate2,R.id.widgetDate3,R.id.widgetDate4,R.id.widgetDate5,R.id.widgetDate6};
        for(int i=0;i<dateCells.length;i++){
            rv.setViewVisibility(dateCells[i],i<shown?android.view.View.VISIBLE:android.view.View.GONE);
            if(i<shown)rv.setOnClickPendingIntent(dateCells[i],broadcast(c,id,30+i,ACTION_DATE,i-shown/2,0));
        }
        rv.setTextViewText(R.id.widgetNext,current==null?"No scheduled posts":current.title);
        rv.setTextViewText(R.id.widgetSecond,current==null?"CALENDAR":current.kind+" · "+current.status.replace('_',' ').toUpperCase(Locale.ENGLISH));
        rv.setTextViewText(R.id.widgetThird,current==null?"Create or upload a poster for this date":current.account+" · "+current.time);
        rv.setOnClickPendingIntent(R.id.widgetCreate,appAction(c,id,"compose",40));
        rv.setTextViewText(R.id.widgetUpload,current==null?"Upload":"Share");
        rv.setOnClickPendingIntent(R.id.widgetUpload,appAction(c,id,"openZip",41));
        rv.setOnClickPendingIntent(R.id.widgetToday,broadcast(c,id,3,ACTION_TODAY,0,0));
        if(posts.size()>1){
            rv.setOnClickPendingIntent(R.id.widgetPrevPost,broadcast(c,id,4,ACTION_ITEM,0,(index+posts.size()-1)%posts.size()));
            rv.setOnClickPendingIntent(R.id.widgetNextPost,broadcast(c,id,5,ACTION_ITEM,0,(index+1)%posts.size()));
        }else{
            rv.setOnClickPendingIntent(R.id.widgetPrevPost,broadcast(c,id,4,ACTION_DATE,-1,0));
            rv.setOnClickPendingIntent(R.id.widgetNextPost,broadcast(c,id,5,ACTION_DATE,1,0));
        }
        Intent open=new Intent(c,WidgetPreviewActivity.class);
        if(current!=null){
            open.putExtra("media_id",current.mediaId);
            open.putExtra("caption",current.caption);
            open.putExtra("title",current.title);
            open.putExtra("account",current.account);
            open.putExtra("scheduled_time",current.time);
            open.putExtra("media_url",current.remoteUrl);
        }
        open.setData(Uri.parse("postflow-preview://widget/"+id+"/"+day.toString()+"/"+index));
        rv.setOnClickPendingIntent(R.id.widgetOpen,PendingIntent.getActivity(c,id*100+6,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Bitmap preview=current==null?null:thumbnail(c,current.mediaId);
        if(preview==null&&current!=null&&!current.remoteUrl.isEmpty())
            preview=WidgetImageCache.get(c,current.remoteUrl);
        if(preview!=null){
            rv.setImageViewBitmap(R.id.widgetPoster,preview);
            rv.setViewVisibility(R.id.widgetPoster,android.view.View.VISIBLE);
            rv.setViewVisibility(R.id.widgetBrandMark,android.view.View.GONE);
        }else{
            rv.setViewVisibility(R.id.widgetPoster,android.view.View.GONE);
            rv.setViewVisibility(R.id.widgetBrandMark,android.view.View.VISIBLE);
        }
        boolean shareable=current!=null&&MediaContentProvider.valid(current.mediaId)&&MediaContentProvider.mediaFile(c,current.mediaId).isFile();
        rv.setViewVisibility(R.id.widgetShare,shareable?android.view.View.VISIBLE:android.view.View.GONE);
        if(current!=null&&!shareable)rv.setTextViewText(R.id.widgetUpload,"View");
        rv.setViewVisibility(R.id.widgetOpen,current!=null&&!current.mediaId.isEmpty()||current!=null&&!current.remoteUrl.isEmpty()?android.view.View.VISIBLE:android.view.View.GONE);
        if(shareable){
            Intent share=new Intent(c,WidgetShareActivity.class);
            share.putExtra("media_id",current.mediaId);
            share.putExtra("caption",current.caption);
            share.putExtra("title",current.title);
            share.setData(Uri.parse("postflow-share://widget/"+id+"/"+current.mediaId));
            rv.setOnClickPendingIntent(R.id.widgetUpload,PendingIntent.getActivity(c,id*100+7,share,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
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
    /** The markers and previews use identical, account-independent active-job filtering. */
    private static int[] markers(JSONObject root,LocalDate day){
        boolean story=false,post=false,festival=false;
        for(Item item:onDate(root,day)){
            if("STORY".equals(item.kind))story=true;
            else if("POST".equals(item.kind))post=true;
            else if("FESTIVAL".equals(item.kind))festival=true;
        }
        return new int[]{story?0xff28c987:0,post?0xff3996ff:0,festival?0xffff5267:0};
    }
    /** The original GraphicaNest widget uses short pills and dots below each pill. */
    private static Bitmap weekBitmap(Context c,LocalDate selected,int shown){
        final int slot=52, height=73, scale=2;
        Bitmap b=Bitmap.createBitmap(slot*shown*scale,height*scale,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);
        canvas.scale(scale,scale);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        LocalDate start=selected.minusDays(shown/2);
        JSONObject root=state(c);
        for(int i=0;i<shown;i++){
            LocalDate date=start.plusDays(i);
            float cx=slot*(i+.5f);
            paint.setColor(date.equals(selected)?0xff7952e6:0x92413a54);
            canvas.drawRoundRect(new RectF(cx-23,2,cx+23,53),15,15,paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
            paint.setTextSize(11);
            paint.setColor(0xffe5dfea);
            String weekday=date.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ENGLISH)
                .toUpperCase(Locale.ENGLISH);
            canvas.drawText(weekday,cx-paint.measureText(weekday)/2,19,paint);
            paint.setColor(Color.WHITE);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));
            paint.setTextSize(21);
            String number=String.valueOf(date.getDayOfMonth());
            canvas.drawText(number,cx-paint.measureText(number)/2,43,paint);
            int[] dots=markers(root,date);
            int count=0;
            for(int color:dots)if(color!=0)count++;
            int offset=0;
            for(int color:dots){
                if(color==0)continue;
                paint.setColor(color);
                canvas.drawCircle(cx+(offset-(count-1)/2f)*9,65,3.1f,paint);
                offset++;
            }
        }
        return b;
    }
}
