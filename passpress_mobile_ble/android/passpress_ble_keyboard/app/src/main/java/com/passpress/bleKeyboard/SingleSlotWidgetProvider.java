package com.passpress.bleKeyboard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

public class SingleSlotWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_CLICK_SINGLE = "com.passpress.bleKeyboard.ACTION_CLICK_SINGLE";
    public static final String EXTRA_APPWIDGET_ID = "com.passpress.bleKeyboard.EXTRA_APPWIDGET_ID";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int appWidgetId : appWidgetIds) {
            editor.remove("single_widget_slot_" + appWidgetId);
        }
        editor.apply();
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        int slotIndex = prefs.getInt("single_widget_slot_" + appWidgetId, -1);
        
        if (slotIndex == -1) return;

        String label = prefs.getString("label_" + slotIndex, "Slot " + (slotIndex + 1));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_single_main);
        boolean isLight = prefs.getBoolean("is_light_theme", true);
        if (isLight) {
            views.setInt(R.id.widget_single_container, "setBackgroundResource", R.drawable.widget_bg_light);
            views.setTextColor(R.id.widget_single_text, android.graphics.Color.parseColor("#0F172A"));
        } else {
            views.setInt(R.id.widget_single_container, "setBackgroundResource", R.drawable.widget_bg);
            views.setTextColor(R.id.widget_single_text, android.graphics.Color.WHITE);
        }
        HidKeyboardService svc = HidKeyboardService.getInstance();
        boolean isConnected = (svc != null && svc.getConnectedDevice() != null);
        String displayStatus = (isConnected ? "🟢 " : "🔴 ") + label;
        views.setTextViewText(R.id.widget_single_text, displayStatus);

        Intent clickIntent = new Intent(context, SingleSlotWidgetProvider.class);
        clickIntent.setAction(ACTION_CLICK_SINGLE);
        clickIntent.putExtra(EXTRA_APPWIDGET_ID, appWidgetId);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
        views.setOnClickPendingIntent(R.id.widget_single_container, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_CLICK_SINGLE.equals(intent.getAction())) {
            int appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, -1);
            if (appWidgetId != -1) {
                SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
                int slotIndex = prefs.getInt("single_widget_slot_" + appWidgetId, -1);
                if (slotIndex != -1) {
                    sendPasswordSeamlessly(context, slotIndex);
                }
            }
        }
    }
    
    private void sendPasswordSeamlessly(Context context, int slotIndex) {
        SecureStorage secureStorage = SecureStorage.getInstance(context);
        String pass = secureStorage.getPassword(slotIndex);
        String suffix = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE)
                .getString("suffix_" + slotIndex, "Enter");
        
        if ((pass == null || pass.isEmpty()) && "None".equals(suffix)) {
            android.widget.Toast.makeText(context, "Slot is empty", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null && svc.getConnectedDevice() != null) {
            String toSend = pass != null ? pass : "";
            if ("Enter".equals(suffix)) toSend += "\n";
            else if ("Tab".equals(suffix)) toSend += "\t";
            
            svc.sendKeySequence(toSend);
            android.widget.Toast.makeText(context, "Password sent!", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(context, "Keyboard not connected!", android.widget.Toast.LENGTH_SHORT).show();
            
            if (svc == null) {
                android.content.Intent serviceIntent = new android.content.Intent(context, HidKeyboardService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                android.widget.Toast.makeText(context, "Starting keyboard service... tap again later", android.widget.Toast.LENGTH_LONG).show();
            } else {
                svc.autoConnect();
                SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
                String pcName = prefs.getString("last_connected_device_name", "PC");
                android.widget.Toast.makeText(context, "Connecting to " + pcName + "... tap again later", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
