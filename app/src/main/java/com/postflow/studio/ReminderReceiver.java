package com.postflow.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.Manifest;

public final class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL="postflow_reminders";
    static void createChannel(Context context){
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Festival and Post Reminders",NotificationManager.IMPORTANCE_HIGH));
    }
    @Override public void onReceive(Context context,Intent intent){
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
           != PackageManager.PERMISSION_GRANTED) return;
        createChannel(context);
        String title=intent.getStringExtra("title"),body=intent.getStringExtra("body");
        if(title==null)title="PostFlow Reminder";
        if(body==null)body="Open PostFlow to review your calendar";
        Intent open=new Intent(context,MainActivity.class);
        PendingIntent tap=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(context,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body).setAutoCancel(true)
            .setContentIntent(tap).build();
        ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(intent.getIntExtra("id",0),n);
    }
}