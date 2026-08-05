package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import android.content.SharedPreferences;

public class WidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new PassPressRemoteViewsFactory(this.getApplicationContext(), intent);
    }
}

class PassPressRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
    private Context context;
    private SharedPreferences prefs;
    private int slotCount = 0;
    
    public PassPressRemoteViewsFactory(Context context, Intent intent) {
        this.context = context;
        this.prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {}

    @Override
    public void onDataSetChanged() {
        slotCount = prefs.getInt("slot_count", 2);
    }

    @Override
    public void onDestroy() {}

    @Override
    public int getCount() {
        return slotCount;
    }

    @Override
    public RemoteViews getViewAt(int position) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_slot_item);
        
        String label = prefs.getString("label_" + position, "Slot " + (position + 1));
        views.setTextViewText(R.id.widget_item_text, label);
        boolean isLight = prefs.getBoolean("is_light_theme", true);
        if (isLight) {
            views.setInt(R.id.widget_item_container, "setBackgroundResource", R.drawable.widget_item_bg_light);
            views.setTextColor(R.id.widget_item_text, android.graphics.Color.parseColor("#0F172A"));
        } else {
            views.setInt(R.id.widget_item_container, "setBackgroundResource", R.drawable.widget_item_bg);
            views.setTextColor(R.id.widget_item_text, android.graphics.Color.WHITE);
        }
        
        Intent fillInIntent = new Intent();
        fillInIntent.putExtra(PassPressWidgetProvider.EXTRA_SLOT_INDEX, position);
        views.setOnClickFillInIntent(R.id.widget_item_container, fillInIntent);
        
        return views;
    }

    @Override
    public RemoteViews getLoadingView() { return null; }

    @Override
    public int getViewTypeCount() { return 1; }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public boolean hasStableIds() { return true; }
}
