package com.passpress.bleKeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

public class QuickMenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.5f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F172A"));
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("PassPress Quick Actions");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        root.addView(spacer);

        // Connect/Disconnect Button
        Button connBtn = new Button(this);
        HidKeyboardService svc = HidKeyboardService.getInstance();
        boolean connected = svc != null && svc.getConnectedDevice() != null;
        connBtn.setText(connected ? "Disconnect PC" : "Connect PC");
        connBtn.setTextColor(Color.WHITE);
        connBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        connBtn.setOnClickListener(v -> {
            if (svc != null) {
                if (connected) {
                    svc.disconnectDevice();
                } else {
                    SharedPreferences prefs = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
                    String lastDevice = prefs.getString("last_connected_device", null);
                    if (lastDevice != null) {
                        svc.connectToDevice(lastDevice);
                        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No saved device.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            finish();
        });
        root.addView(connBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        root.addView(spacer2);

        // Slots Grid (Horizontal)
        LinearLayout slotsRow = new LinearLayout(this);
        slotsRow.setOrientation(LinearLayout.HORIZONTAL);
        slotsRow.setGravity(Gravity.CENTER);

        for (int i = 0; i < 5; i++) {
            Button slotBtn = new Button(this);
            slotBtn.setText(String.valueOf(i + 1));
            slotBtn.setTextColor(Color.WHITE);
            slotBtn.setBackgroundColor(Color.parseColor("#334155"));
            
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
            p.setMargins(dp(4), dp(4), dp(4), dp(4));
            slotsRow.addView(slotBtn, p);

            final int slotIndex = i;
            slotBtn.setOnClickListener(v -> {
                Intent slotIntent = new Intent(this, WidgetProxyActivity.class);
                slotIntent.putExtra("slot_index", slotIndex);
                startActivity(slotIntent);
                finish();
            });
        }
        root.addView(slotsRow);

        setContentView(root);
    }

    private int dp(int px) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, px, getResources().getDisplayMetrics());
    }
}
