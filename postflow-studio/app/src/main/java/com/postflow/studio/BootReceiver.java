package com.postflow.studio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()) || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
            EventScheduler.reschedule(context);
            android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(context);
            FestivalWidgetProvider.updateAll(context,manager,manager.getAppWidgetIds(new android.content.ComponentName(context,FestivalWidgetProvider.class)));
        }
    }
}