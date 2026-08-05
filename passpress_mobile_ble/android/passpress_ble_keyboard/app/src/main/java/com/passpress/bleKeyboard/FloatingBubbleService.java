package com.passpress.bleKeyboard;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class FloatingBubbleService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private View expandedMenu;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        windowManager.addView(floatingView, params);

        expandedMenu = floatingView.findViewById(R.id.bubble_expanded_menu);
        View bubbleIcon = floatingView.findViewById(R.id.bubble_icon_container);

        boolean isLight = getSharedPreferences("passpress_prefs", android.content.Context.MODE_PRIVATE).getBoolean("is_light_theme", true);
        if (isLight) {
            expandedMenu.setBackgroundColor(android.graphics.Color.parseColor("#E6FFFFFF"));
            bubbleIcon.setBackgroundResource(R.drawable.bubble_bg_light);
        } else {
            expandedMenu.setBackgroundColor(android.graphics.Color.parseColor("#E60F172A"));
            bubbleIcon.setBackgroundResource(R.drawable.bubble_bg);
        }

        // Slots
        int[] btnIds = {R.id.btn_bubble_s1, R.id.btn_bubble_s2, R.id.btn_bubble_s3, R.id.btn_bubble_s4, R.id.btn_bubble_s5};
        for (int i = 0; i < 5; i++) {
            final int slotIndex = i;
            android.widget.Button btn = floatingView.findViewById(btnIds[i]);
            if (isLight) {
                btn.setBackgroundColor(android.graphics.Color.parseColor("#F8FAFC"));
                btn.setTextColor(android.graphics.Color.parseColor("#0F172A"));
            } else {
                btn.setBackgroundColor(android.graphics.Color.parseColor("#334155"));
                btn.setTextColor(android.graphics.Color.parseColor("#FFFFFF"));
            }
            btn.setOnClickListener(v -> {
                expandedMenu.setVisibility(View.GONE);
                Intent slotIntent = new Intent(this, WidgetProxyActivity.class);
                slotIntent.putExtra("slot_index", slotIndex);
                slotIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(slotIntent);
            });
        }

        // Dragging & Clicking
        bubbleIcon.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging) { // Click
                            if (expandedMenu.getVisibility() == View.VISIBLE) {
                                expandedMenu.setVisibility(View.GONE);
                            } else {
                                expandedMenu.setVisibility(View.VISIBLE);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) {
                            isDragging = true;
                            expandedMenu.setVisibility(View.GONE);
                        }
                        if (isDragging) {
                            params.x = initialX + diffX;
                            params.y = initialY + diffY;
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
