package com.postflow.studio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** On-device reminder scheduler; never claims to publish to external platforms. */
public final class EventScheduler {
    static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    public static final class Event {
        final String title; final LocalDate date;
        Event(String title,LocalDate date){this.title=title;this.date=date;}
        String display(){return date.format(DateTimeFormatter.ofPattern("dd MMM"))+"  •  "+title;}
    }
    private static JSONObject state(Context c){
        try{return new JSONObject(c.getSharedPreferences("postflow",0).getString("state","{}"));}
        catch(Exception e){return new JSONObject();}
    }
    private static LocalDate nextYearly(LocalDate original,LocalDate now){
        int year=now.getYear();
        LocalDate result;
        try{result=original.withYear(year);}catch(Exception ex){result=LocalDate.of(year,2,28);}
        if(result.isBefore(now)){
            try{result=original.withYear(year+1);}catch(Exception ex){result=LocalDate.of(year+1,2,28);}
        }
        return result;
    }
    static List<Event> upcoming(Context c,int limit){
        List<Event> out=new ArrayList<>();
        JSONArray events=state(c).optJSONArray("events");
        if(events==null)return out;
        LocalDate now=LocalDate.now(ZONE);
        for(int i=0;i<events.length();i++){
            JSONObject e=events.optJSONObject(i);if(e==null)continue;
            try{
                LocalDate d=LocalDate.parse(e.optString("date",""));
                if(e.optBoolean("repeat",false))d=nextYearly(d,now);
                if(!d.isBefore(now))out.add(new Event(e.optString("title","Event"),d));
            }catch(Exception ignored){}
        }
        out.sort(Comparator.comparing(a->a.date));
        return out.subList(0,Math.min(limit,out.size()));
    }
    static void reschedule(Context c){
        AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(alarms==null)return;
        SharedPreferences prefs=c.getSharedPreferences("postflow_alarm_ids",0);
        String old=prefs.getString("items","");
        for(String id:old.split("\\n"))if(!id.isEmpty()){
            PendingIntent prior=pending(c,id,"","",PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            if(prior!=null){alarms.cancel(prior);prior.cancel();}
        }
        JSONObject root=state(c);
        JSONObject settings=root.optJSONObject("settings");
        if(settings!=null&&!settings.optBoolean("notifications",true)){
            prefs.edit().putString("items","").apply();return;
        }
        List<String> ids=new ArrayList<>();
        LocalDate today=LocalDate.now(ZONE);
        LocalDate cutoff=today.plusMonths(12);
        JSONArray events=root.optJSONArray("events");
        int[] reminderDays={7,1,0};
        if(settings!=null && settings.optJSONArray("reminderDays")!=null){
            JSONArray data=settings.optJSONArray("reminderDays");
            List<Integer> nums=new ArrayList<>();
            for(int i=0;i<data.length();i++){
                int n=data.optInt(i,-1); if(n>=0&&n<=60&&!nums.contains(n))nums.add(n);
            }
            if(!nums.isEmpty()){reminderDays=new int[nums.size()];for(int i=0;i<nums.size();i++)reminderDays[i]=nums.get(i);}
        }
        if(events!=null)for(int i=0;i<events.length()&&i<200;i++){
            JSONObject ev=events.optJSONObject(i);if(ev==null)continue;
            try{
                LocalDate date=LocalDate.parse(ev.optString("date",""));
                boolean repeat=ev.optBoolean("repeat",false);
                if(repeat)date=nextYearly(date,today);
                String title=ev.optString("title","Event");
                String type=ev.optString("type","festival");
                String prefix="birthday".equals(type)?"Birthday":"Festival / Event";
                for(int n:reminderDays){
                    LocalDate reminderDate=date.minusDays(n);
                    if(reminderDate.isBefore(today)||reminderDate.isAfter(cutoff))continue;
                    String label=n==0?"Today":n==1?"Tomorrow":"In "+n+" days";
                    String body="birthday".equals(type)?"Prepare a personal wish; never shared automatically":"Check posters and scheduled content";
                    schedule(c,alarms,ids,"event-"+ev.optString("id",String.valueOf(i))+"-"+n,
                        reminderDate.atTime(9,0).atZone(ZONE),prefix+" • "+title+" • "+label,body);
                }
            }catch(Exception ignored){}
        }
        JSONArray posts=root.optJSONArray("posts");
        if(posts!=null)for(int i=0;i<posts.length()&&i<500;i++){
            JSONObject post=posts.optJSONObject(i);
            if(post==null||!"scheduled".equals(post.optString("status")))continue;
            try{
                LocalDate date=LocalDate.parse(post.optString("date"));
                LocalTime time=LocalTime.parse(post.optString("time","09:00"));
                JSONArray targets=post.optJSONArray("targets");boolean whatsapp=false;
                if(targets!=null)for(int j=0;j<targets.length();j++){
                    if("wa_reminder".equals(targets.optString(j))){whatsapp=true;break;}
                }
                String title=post.optString("title","Untitled");
                schedule(c,alarms,ids,"post-"+post.optString("id",String.valueOf(i)),
                    date.atTime(time).atZone(ZONE),whatsapp?"WhatsApp share ready: "+title:"Post due: "+title,
                    whatsapp?"Tap to manually share media and caption":"Review post; API auto-posting not connected");
            }catch(Exception ignored){}
        }
        prefs.edit().putString("items",String.join("\n",ids)).apply();
    }
    private static PendingIntent pending(Context c,String id,String title,String body,int flags){
        Intent intent=new Intent(c,ReminderReceiver.class).setAction("com.postflow.studio.REMINDER."+id);
        if(title!=null)intent.putExtra("title",title);
        if(body!=null)intent.putExtra("body",body);
        intent.putExtra("id",id.hashCode()&0x7fffffff);
        return PendingIntent.getBroadcast(c,id.hashCode()&0x7fffffff,intent,flags);
    }
    private static void schedule(Context c,AlarmManager alarms,List<String> ids,String id,ZonedDateTime due,String title,String body){
        long ms=due.toInstant().toEpochMilli();
        if(ms<=System.currentTimeMillis()||ids.size()>800)return;
        PendingIntent pi=pending(c,id,title,body,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        if(pi==null)return;
        try{alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,ms,pi);ids.add(id);}
        catch(Exception ignored){}
    }
    private EventScheduler(){}
}