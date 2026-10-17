package com.postflow.studio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Actual native Android home screen widget with a month grid and upcoming events. */
public final class FestivalWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){
        String today=LocalDate.now(EventScheduler.ZONE).toString();
        android.content.SharedPreferences prefs=context.getSharedPreferences("postflow_widget",0);
        if(!today.equals(prefs.getString("last_schedule_day",""))){
            EventScheduler.reschedule(context);
            prefs.edit().putString("last_schedule_day",today).apply();
        }
        updateAll(context,manager,ids);
    }
    static void updateAll(Context context,AppWidgetManager manager,int[] ids){
        List<EventScheduler.Event> upcoming=EventScheduler.upcoming(context,3);
        Bitmap month=monthBitmap(context);
        for(int id:ids){
            RemoteViews v=new RemoteViews(context.getPackageName(),R.layout.widget_festival);
            v.setTextViewText(R.id.widgetTitle,"✦  FESTIVAL CALENDAR");
            v.setImageViewBitmap(R.id.widgetCalendar,month);
            v.setTextViewText(R.id.widgetNext,upcoming.size()>0?upcoming.get(0).display():"Add a festival or birthday");
            v.setTextViewText(R.id.widgetSecond,upcoming.size()>1?upcoming.get(1).display():"");
            v.setTextViewText(R.id.widgetThird,upcoming.size()>2?upcoming.get(2).display():"");
            PendingIntent tap=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(R.id.widgetRoot,tap);
            manager.updateAppWidget(id,v);
        }
    }
    private static Bitmap monthBitmap(Context c){
        final int w=480,h=270;
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        LocalDate today=LocalDate.now(EventScheduler.ZONE);
        YearMonth month=YearMonth.from(today);
        paint.setColor(Color.WHITE);paint.setTextSize(25);paint.setFakeBoldText(true);
        String heading=month.getMonth().getDisplayName(TextStyle.FULL,Locale.ENGLISH)+" "+month.getYear();
        canvas.drawText(heading,20,33,paint);
        final String[] week={"M","T","W","T","F","S","S"};
        int cw=w/7;paint.setTextSize(18);paint.setFakeBoldText(false);paint.setColor(0xffa7a9cf);
        for(int i=0;i<7;i++)canvas.drawText(week[i],i*cw+cw/2f-paint.measureText(week[i])/2f,68,paint);
        Set<Integer> occasions=new HashSet<>();
        try{
            JSONObject root=new JSONObject(c.getSharedPreferences("postflow",0).getString("state","{}"));
            JSONArray events=root.optJSONArray("events");
            if(events!=null)for(int i=0;i<events.length();i++){
                JSONObject event=events.optJSONObject(i);if(event==null)continue;
                LocalDate date;
                try{date=LocalDate.parse(event.optString("date"));}catch(Exception ex){continue;}
                if(event.optBoolean("repeat",false)){
                    if(date.getMonthValue()==month.getMonthValue()&&date.getDayOfMonth()<=month.lengthOfMonth())occasions.add(date.getDayOfMonth());
                }else if(date.getYear()==month.getYear()&&date.getMonthValue()==month.getMonthValue())occasions.add(date.getDayOfMonth());
            }
        }catch(Exception ignored){}
        int offset=month.atDay(1).getDayOfWeek().getValue()-1;
        for(int day=1;day<=month.lengthOfMonth();day++){
            int pos=day+offset-1,col=pos%7,row=pos/7;
            float x=col*cw+cw/2f,y=102+row*30;
            if(today.getYear()==month.getYear()&&today.getMonthValue()==month.getMonthValue()&&today.getDayOfMonth()==day){
                paint.setColor(0xff6958c6);canvas.drawCircle(x,y-7,15,paint);
            }
            paint.setColor(Color.WHITE);paint.setTextSize(17);paint.setFakeBoldText(false);
            String str=String.valueOf(day);canvas.drawText(str,x-paint.measureText(str)/2f,y,paint);
            if(occasions.contains(day)){
                paint.setColor(0xff40d5af);canvas.drawCircle(x,y+5,3,paint);
            }
        }
        return bitmap;
    }
}