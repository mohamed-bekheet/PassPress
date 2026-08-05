package com.passpress.bleKeyboard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

public class PassPressWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_CLICK_SLOT = "com.passpress.bleKeyboard.ACTION_CLICK_SLOT";
    public static final String EXTRA_SLOT_INDEX = "com.passpress.bleKeyboard.EXTRA_SLOT_INDEX";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        Intent serviceIntent = new Intent(context, WidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_main);
        boolean isLight = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE).getBoolean("is_light_theme", true);
        if (isLight) {
            views.setInt(R.id.widget_main_root, "setBackgroundResource", R.drawable.widget_bg_light);
            views.setTextColor(R.id.widget_main_title, android.graphics.Color.parseColor("#0F172A"));
        } else {
            views.setInt(R.id.widget_main_root, "setBackgroundResource", R.drawable.widget_bg);
            views.setTextColor(R.id.widget_main_title, android.graphics.Color.WHITE);
        }
        views.setRemoteAdapter(R.id.widget_grid, serviceIntent);
        views.setEmptyView(R.id.widget_grid, android.R.id.empty); // Not used

        Intent clickIntent = new Intent(context, PassPressWidgetProvider.class);
        clickIntent.setAction(ACTION_CLICK_SLOT);
        PendingIntent clickPendingIntent = PendingIntent.getBroadcast(
                context, 0, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        views.setPendingIntentTemplate(R.id.widget_grid, clickPendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_CLICK_SLOT.equals(intent.getAction())) {
            int slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1);
            if (slotIndex != -1) {
                // Option A: Seamlessly send the password in the background
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
                        android.widget.Toast.makeText(context, "Connecting... tap again later", android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    }
}
