package com.passpress.bleKeyboard;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;

public class SingleSlotWidgetConfigActivity extends Activity {

    int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_widget_config);

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        int slotCount = prefs.getInt("slot_count", 2);
        
        List<String> slotNames = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            slotNames.add("🔑 " + prefs.getString("label_" + i, "Slot " + (i + 1)));
        }
        int macroCount = prefs.getInt("macro_count", 0);
        for (int i = 0; i < macroCount; i++) {
            slotNames.add("⚡ " + prefs.getString("macro_name_" + i, "Macro " + (i + 1)));
        }

        ListView listView = findViewById(R.id.config_list_view);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_white_text, slotNames);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            prefs.edit().putInt("single_widget_slot_" + appWidgetId, position).apply();

            SingleSlotWidgetProvider.updateAppWidget(this, AppWidgetManager.getInstance(this), appWidgetId);

            Intent resultValue = new Intent();
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            setResult(RESULT_OK, resultValue);
            finish();
        });
    }
}
