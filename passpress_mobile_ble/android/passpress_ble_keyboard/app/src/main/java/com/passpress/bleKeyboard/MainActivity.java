package com.passpress.bleKeyboard;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "passpress_prefs";
    private static final String LAST_DEVICE_KEY = "last_connected_device";
    private static final String SLOT_COUNT_KEY = "slot_count";

    private static final int PERM_REQUEST_STARTUP = 100;
    private static final int PERM_REQUEST_PICKER = 101;

    // ─── Color Palette ───────────────────────────────────────────────────────
    private static final String COLOR_BG = "#0F172A";  // Deep navy background
    private static final String COLOR_SURFACE = "#1E293B";  // Card surface
    private static final String COLOR_SURFACE_ALT = "#334155";  // Elevated surface
    private static final String COLOR_PRIMARY = "#6366F1";  // Indigo
    private static final String COLOR_PRIMARY_DARK = "#4F46E5";  // Darker indigo
    private static final String COLOR_ACCENT = "#22D3EE";  // Cyan accent
    private static final String COLOR_SUCCESS = "#34D399";  // Emerald
    private static final String COLOR_TEXT = "#F1F5F9";  // Light text
    private static final String COLOR_TEXT_DIM = "#94A3B8";  // Dimmed text
    private static final String COLOR_SEND_BTN = "#8B5CF6";  // Violet send
    private static final String COLOR_WARNING = "#F59E0B";  // Amber warning

    private boolean permissionDeniedAtStartup = false;
    private SharedPreferences prefs;
    private SecureStorage secureStorage;
    private TextView connectionStatusText;
    private View statusDot;
    private Button reconnectButton;
    private HidKeyboardService.ConnectionListener connectionListener;
    private final Map<Integer, Boolean> passwordVisible = new HashMap<>();

    private LinearLayout slotsContainer;
    private boolean isAuthenticated = false;
    private long lastPauseTime = 0;
    private String pendingPassword = "";

    // --- Undo State ---
    private static class BackupState {
        int index;
        String label;
        String password;
        String suffix;
        Set<String> trustedDevices;
        boolean wasDeleted; // true if the slot was entirely removed
        
        BackupState(int index, String label, String password, String suffix, Set<String> trustedDevices, boolean wasDeleted) {
            this.index = index;
            this.label = label;
            this.password = password;
            this.suffix = suffix;
            this.trustedDevices = trustedDevices != null ? new java.util.HashSet<>(trustedDevices) : new java.util.HashSet<>();
            this.wasDeleted = wasDeleted;
        }
    }
    
    private void showUndoSnackbar(BackupState backup, String message) {
        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), message, 10000);
        snackbar.setAction("UNDO", v -> {
            if (backup.wasDeleted) {
                int currentCount = prefs.getInt(SLOT_COUNT_KEY, 2);
                
                // Shift passwords UP to make room for the restored slot
                for (int i = currentCount - 1; i >= backup.index; i--) {
                    String prevPass = secureStorage.getPassword(i);
                    String prevLabel = prefs.getString("label_" + i, "Slot " + (i + 1));
                    String prevSuffix = prefs.getString("suffix_" + i, "Enter");
                    Set<String> prevTrusted = secureStorage.getTrustedDevices(i);
                    
                    secureStorage.savePassword(i + 1, prevPass);
                    prefs.edit().putString("label_" + (i + 1), prevLabel).apply();
                    prefs.edit().putString("suffix_" + (i + 1), prevSuffix).apply();
                    
                    secureStorage.clearTrustedDevices(i + 1);
                    for (String device : prevTrusted) {
                        Set<String> devices = secureStorage.getTrustedDevices(i + 1);
                        devices.add(device);
                        getSharedPreferences("passpress_secure_prefs", Context.MODE_PRIVATE)
                            .edit().putStringSet("trusted_" + (i + 1), devices).apply();
                    }
                }
                
                // Restore deleted slot count
                prefs.edit().putInt(SLOT_COUNT_KEY, currentCount + 1).apply();
            }
            
            // Restore data
            prefs.edit().putString("label_" + backup.index, backup.label).apply();
            prefs.edit().putString("suffix_" + backup.index, backup.suffix).apply();
            secureStorage.savePassword(backup.index, backup.password);
            
            // Restore trusted devices
            secureStorage.clearTrustedDevices(backup.index);
            for (String device : backup.trustedDevices) {
                // Manually re-add to shared prefs to preserve exact string (MAC|Name)
                Set<String> devices = secureStorage.getTrustedDevices(backup.index);
                devices.add(device);
                getSharedPreferences("passpress_secure_prefs", Context.MODE_PRIVATE)
                    .edit().putStringSet("trusted_" + backup.index, devices).apply();
                // We use the secure storage's underlying logic by re-fetching and saving
                secureStorage.addTrustedDevice(backup.index, device.split("\\|")[0], device.contains("|") ? device.split("\\|")[1] : "Unknown Device");
            }
            
            refreshPasswordGrid();
            Toast.makeText(this, "Restored!", Toast.LENGTH_SHORT).show();
        });
        snackbar.setActionTextColor(Color.parseColor(COLOR_ACCENT));
        snackbar.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        secureStorage = SecureStorage.getInstance(this);

        // Run migrations for existing passwords
        int numSlots = prefs.getInt(SLOT_COUNT_KEY, 10);
        for (int i = 0; i < numSlots; i++) {
            secureStorage.migrateOldPassword(this, i);
        }

        showLockScreenUI();

        // Status bar color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#060B18"));
            getWindow().setNavigationBarColor(Color.parseColor(COLOR_BG));
        }

        requestRequiredPermissions();
    }

    private void showLockScreenUI() {
        LinearLayout lockLayout = new LinearLayout(this);
        lockLayout.setOrientation(LinearLayout.VERTICAL);
        lockLayout.setBackgroundColor(Color.parseColor(COLOR_BG));
        lockLayout.setGravity(Gravity.CENTER);

        TextView lockIcon = new TextView(this);
        lockIcon.setText("🔒");
        lockIcon.setTextSize(48);
        lockIcon.setGravity(Gravity.CENTER);
        lockLayout.addView(lockIcon);

        setContentView(lockLayout);
    }

    private void showBiometricPrompt() {
        if (isAuthenticated) {
            loadMainUI();
            return;
        }

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt biometricPrompt = new BiometricPrompt(MainActivity.this,
                    executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Toast.makeText(getApplicationContext(), "Authentication error: " + errString, Toast.LENGTH_SHORT).show();
                    finish(); // Exit if auth fails
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    isAuthenticated = true;
                    loadMainUI();
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(getApplicationContext(), "Authentication failed", Toast.LENGTH_SHORT).show();
                }
            });

            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("PassPress Keyboard")
                    .setSubtitle("Authenticate to access your passwords")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();

            biometricPrompt.authenticate(promptInfo);
        } else {
            // No biometric features available, skip auth for now
            isAuthenticated = true;
            loadMainUI();
        }
    }

    private void loadMainUI() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor(COLOR_BG));

        mainLayout.addView(createHeaderLayout());

        TextView subtitleText = new TextView(this);
        subtitleText.setText("Tap Send to type your password via Bluetooth Keyboard");
        subtitleText.setTextSize(13);
        subtitleText.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        subtitleText.setPadding(dp(20), dp(12), dp(20), dp(8));
        mainLayout.addView(subtitleText);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        slotsContainer = new LinearLayout(this);
        slotsContainer.setOrientation(LinearLayout.VERTICAL);
        slotsContainer.setPadding(dp(16), dp(8), dp(16), dp(24));

        refreshPasswordGrid();

        scrollView.addView(slotsContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(mainLayout);
        setupConnectionListener();
        
        // Auto-reconnect on app launch if we have a saved device
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            HidKeyboardService svc = HidKeyboardService.getInstance();
            if (svc != null && svc.getConnectedDevice() == null) {
                reconnectDevice();
            }
        }, 800);
    }

    private void refreshPasswordGrid() {
        if (slotsContainer == null) return;
        slotsContainer.removeAllViews();
        int slotCount = prefs.getInt(SLOT_COUNT_KEY, 2);

        LinearLayout currentRow = null;
        for (int i = 0; i < slotCount; i++) {
            if (i % 2 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                slotsContainer.addView(currentRow);
            }
            View card = createPasswordCard(i);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cardParams.setMargins(dp(4), dp(4), dp(4), dp(8));
            card.setLayoutParams(cardParams);
            
            if (currentRow != null) {
                currentRow.addView(card);
            }
        }
        
        // If odd number of slots, add a dummy view to balance the last row
        if (slotCount % 2 != 0 && currentRow != null) {
            View dummy = new View(this);
            LinearLayout.LayoutParams dummyParams = new LinearLayout.LayoutParams(0, 1, 1f);
            dummy.setLayoutParams(dummyParams);
            currentRow.addView(dummy);
        }

        // Add "Add Slot" button
        Button addSlotBtn = createStyledButton("+ Add Password Slot", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, dp(12));
        addSlotBtn.setLayoutParams(params);
        addSlotBtn.setOnClickListener(v -> {
            prefs.edit().putInt(SLOT_COUNT_KEY, slotCount + 1).apply();
            refreshPasswordGrid();
        });
        slotsContainer.addView(addSlotBtn);
        
        updateWidget();
    }

    private void updateWidget() {
        android.appwidget.AppWidgetManager appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this);
        
        // Update Grid Widget
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new android.content.ComponentName(this, PassPressWidgetProvider.class));
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_grid);
        
        // Update Single Widgets
        int[] singleWidgetIds = appWidgetManager.getAppWidgetIds(new android.content.ComponentName(this, SingleSlotWidgetProvider.class));
        for (int id : singleWidgetIds) {
            SingleSlotWidgetProvider.updateAppWidget(this, appWidgetManager, id);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        lastPauseTime = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        boolean autoLockEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("auto_lock_enabled", true);
        if (autoLockEnabled && lastPauseTime > 0 && (System.currentTimeMillis() - lastPauseTime > 3 * 60 * 1000)) {
            isAuthenticated = false;
            showLockScreenUI();
            showBiometricPrompt();
            return;
        }

        if (isAuthenticated) {
            setupConnectionListener();
            if (slotsContainer != null) {
                refreshPasswordGrid(); // Refresh to update lock icons
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    // ─── Header ──────────────────────────────────────────────────────────────
    private LinearLayout createHeaderLayout() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(16));

        GradientDrawable headerBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.parseColor("#1E1B4B"),
                        Color.parseColor("#0F172A"),
                        Color.parseColor("#164E63")
                }
        );
        header.setBackground(headerBg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconText = new TextView(this);
        iconText.setText("⌨️");
        iconText.setTextSize(22);
        iconText.setPadding(0, 0, dp(10), 0);
        titleRow.addView(iconText);

        TextView appTitle = new TextView(this);
        appTitle.setText("PassPress");
        appTitle.setTextSize(22);
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleRow.addView(appTitle);

        TextView tagText = new TextView(this);
        tagText.setText("  Keyboard");
        tagText.setTextSize(22);
        tagText.setTextColor(Color.parseColor(COLOR_ACCENT));
        tagText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titleRow.addView(tagText);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1.0f));
        titleRow.addView(spacer);

        TextView settingsIcon = new TextView(this);
        settingsIcon.setText("⚙️");
        settingsIcon.setTextSize(22);
        settingsIcon.setPadding(dp(10), 0, dp(10), 0);
        settingsIcon.setOnClickListener(v -> showSettingsDialog());
        titleRow.addView(settingsIcon);

        TextView infoIcon = new TextView(this);
        infoIcon.setText("ℹ️");
        infoIcon.setTextSize(22);
        infoIcon.setPadding(0, 0, 0, 0);
        infoIcon.setOnClickListener(v -> showHelpDialog());
        titleRow.addView(infoIcon);

        header.addView(titleRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(12), 0, dp(12));

        statusDot = new View(this);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(Color.parseColor(COLOR_TEXT_DIM));
        dotShape.setSize(dp(10), dp(10));
        statusDot.setBackground(dotShape);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMargins(0, 0, dp(10), 0);
        statusDot.setLayoutParams(dotParams);
        statusRow.addView(statusDot);

        connectionStatusText = new TextView(this);
        connectionStatusText.setText("Initializing Bluetooth...");
        connectionStatusText.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        connectionStatusText.setTextSize(13);
        connectionStatusText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(connectionStatusText);

        header.addView(statusRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button connectButton = createStyledButton("📱 Connect PC", COLOR_PRIMARY, COLOR_PRIMARY_DARK);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        connectParams.setMargins(0, 0, dp(8), 0);
        connectButton.setLayoutParams(connectParams);
        connectButton.setOnClickListener(v -> showPairedDevicePicker());
        btnRow.addView(connectButton);

        reconnectButton = createStyledButton("⚡ Reconnect", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams reconnectParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        reconnectButton.setLayoutParams(reconnectParams);
        reconnectButton.setOnClickListener(v -> reconnectDevice());
        btnRow.addView(reconnectButton);

        header.addView(btnRow);

        return header;
    }

    // ─── Password Card ───────────────────────────────────────────────────────
    private View createPasswordCard(int index) {
        CardView card = new CardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(Color.parseColor(COLOR_SURFACE));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(10));
        card.setLayoutParams(params);

        // Long press to delete slot
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Delete Slot " + (index + 1) + "?")
                .setMessage("This will remove the password slot entirely.")
                .setPositiveButton("Delete", (d, w) -> {
                    // Capture backup
                    String oldLabel = prefs.getString("label_" + index, "Slot " + (index + 1));
                    String oldPass = secureStorage.getPassword(index);
                    String oldSuffix = prefs.getString("suffix_" + index, "Enter");
                    Set<String> oldTrusted = secureStorage.getTrustedDevices(index);
                    BackupState backup = new BackupState(index, oldLabel, oldPass, oldSuffix, oldTrusted, true);

                    int slotCount = prefs.getInt(SLOT_COUNT_KEY, 2);
                    // Shift passwords down
                    for (int i = index; i < slotCount - 1; i++) {
                        String nextPass = secureStorage.getPassword(i + 1);
                        String nextLabel = prefs.getString("label_" + (i + 1), "Slot " + (i + 2));
                        String nextSuffix = prefs.getString("suffix_" + (i + 1), "Enter");
                        secureStorage.savePassword(i, nextPass);
                        prefs.edit().putString("label_" + i, nextLabel).apply();
                        prefs.edit().putString("suffix_" + i, nextSuffix).apply();
                        // Transfer trusted devices
                        Set<String> nextTrusted = secureStorage.getTrustedDevices(i + 1);
                        prefs.edit().putStringSet("trusted_" + i, nextTrusted).apply();
                    }
                    secureStorage.removePassword(slotCount - 1);
                    prefs.edit().remove("label_" + (slotCount - 1)).apply();
                    prefs.edit().remove("suffix_" + (slotCount - 1)).apply();
                    prefs.edit().remove("trusted_" + (slotCount - 1)).apply();
                    prefs.edit().putInt(SLOT_COUNT_KEY, slotCount - 1).apply();
                    
                    refreshPasswordGrid();
                    showUndoSnackbar(backup, "Slot deleted");
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        });

        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(String.valueOf(index + 1));
        badge.setTextSize(11);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(Color.parseColor(COLOR_PRIMARY));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        badgeParams.setMargins(0, 0, dp(8), 0);
        badge.setLayoutParams(badgeParams);
        topRow.addView(badge);

        String label = prefs.getString("label_" + index, "Slot " + (index + 1));
        TextView labelText = new TextView(this);
        labelText.setText(label);
        labelText.setTextSize(14);
        labelText.setTextColor(Color.parseColor(COLOR_TEXT));
        labelText.setTypeface(null, Typeface.BOLD);
        labelText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(labelText);

        // Trust indicator
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null && svc.getConnectedDevice() != null) {
            boolean isTrusted = secureStorage.isDeviceTrusted(index, svc.getConnectedDevice().getAddress());
            TextView trustIcon = new TextView(this);
            trustIcon.setText(isTrusted ? "🔒" : "⚠️");
            trustIcon.setTextSize(12);
            trustIcon.setPadding(dp(4), 0, 0, 0);
            topRow.addView(trustIcon);
        }

        cardContent.addView(topRow);

        String password = secureStorage.getPassword(index);
        passwordVisible.put(index, false);

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.setGravity(Gravity.CENTER_VERTICAL);
        passRow.setPadding(0, dp(6), 0, dp(10));

        TextView passText = new TextView(this);
        passText.setText(password.isEmpty() ? "Not set" : "••••••••");
        passText.setTextSize(12);
        passText.setTextColor(Color.parseColor(password.isEmpty() ? COLOR_TEXT_DIM : COLOR_ACCENT));
        passText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        passRow.addView(passText);

        if (!password.isEmpty()) {
            TextView eyeBtn = new TextView(this);
            eyeBtn.setText("👁");
            eyeBtn.setTextSize(16);
            eyeBtn.setPadding(dp(8), dp(2), dp(4), dp(2));
            final String savedPass = password;
            eyeBtn.setOnClickListener(v -> {
                Boolean visible = passwordVisible.get(index);
                if (visible == null) visible = false;
                if (visible) {
                    passText.setText("••••••••");
                    passText.setTextColor(Color.parseColor(COLOR_ACCENT));
                    eyeBtn.setText("👁");
                    passwordVisible.put(index, false);
                } else {
                    passText.setText(savedPass);
                    passText.setTextColor(Color.parseColor(COLOR_TEXT));
                    eyeBtn.setText("🙈");
                    passwordVisible.put(index, true);
                }
            });
            passRow.addView(eyeBtn);
        }

        cardContent.addView(passRow);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        Button sendBtn = createStyledButton("Send ➔", COLOR_SEND_BTN, "#7C3AED");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, dp(36), 1f);
        sendParams.setMargins(0, 0, dp(6), 0);
        sendBtn.setLayoutParams(sendParams);
        sendBtn.setTextSize(12);
        sendBtn.setOnClickListener(v -> {
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                    .start();
            attemptSendPassword(index);
        });
        actionsRow.addView(sendBtn);

        Button editBtn = createStyledButton("✏️", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(40), dp(36));
        editBtn.setLayoutParams(editParams);
        editBtn.setPadding(0, 0, 0, 0);
        editBtn.setTextSize(14);
        editBtn.setOnClickListener(v -> editPassword(index));
        actionsRow.addView(editBtn);

        cardContent.addView(actionsRow);
        card.addView(cardContent);

        return card;
    }

    // ─── Styled Button Helper ────────────────────────────────────────────────
    private Button createStyledButton(String text, String bgColor, String pressedColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(bgColor));
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);

        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ─── Permissions ─────────────────────────────────────────────────────────
    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE))
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (needed.isEmpty()) {
            onAllPermissionsGranted();
        } else {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), PERM_REQUEST_STARTUP);
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void onAllPermissionsGranted() {
        startHidKeyboardService();
        new Handler(Looper.getMainLooper()).postDelayed(this::showBiometricPrompt, 600);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
        }

        if (requestCode == PERM_REQUEST_STARTUP) {
            if (allGranted) {
                permissionDeniedAtStartup = false;
                onAllPermissionsGranted();
            } else {
                permissionDeniedAtStartup = true;
                Toast.makeText(this, "Bluetooth permissions needed for Keyboard", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PERM_REQUEST_PICKER) {
            if (allGranted) {
                permissionDeniedAtStartup = false;
                if (HidKeyboardService.getInstance() == null) startHidKeyboardService();
                new Handler(Looper.getMainLooper()).postDelayed(this::showPairedDevicePicker, 400);
            } else {
                permissionDeniedAtStartup = true;
                showPermissionSettingsDialog();
            }
        }
    }

    private void startHidKeyboardService() {
        Intent intent = new Intent(this, HidKeyboardService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ─── Connection Listener ─────────────────────────────────────────────────
    private void setupConnectionListener() {
        connectionListener = (isConnected, deviceName) ->
            runOnUiThread(() -> {
                GradientDrawable dotShape = new GradientDrawable();
                dotShape.setShape(GradientDrawable.OVAL);
                dotShape.setSize(dp(10), dp(10));

                if (isConnected && deviceName != null) {
                    connectionStatusText.setText("Connected: " + deviceName);
                    connectionStatusText.setTextColor(Color.parseColor(COLOR_SUCCESS));
                    dotShape.setColor(Color.parseColor(COLOR_SUCCESS));
                    statusDot.animate().scaleX(1.3f).scaleY(1.3f).setDuration(300)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> statusDot.animate().scaleX(1f).scaleY(1f).setDuration(300).start())
                        .start();
                    if (reconnectButton != null) {
                        reconnectButton.setText("🛑 Disconnect");
                        reconnectButton.setOnClickListener(v -> {
                            HidKeyboardService svc = HidKeyboardService.getInstance();
                            if (svc != null) svc.disconnectDevice();
                        });
                    }
                } else {
                    connectionStatusText.setText("Ready – pair in Windows Bluetooth Settings");
                    connectionStatusText.setTextColor(Color.parseColor(COLOR_ACCENT));
                    dotShape.setColor(Color.parseColor(COLOR_ACCENT));
                    if (reconnectButton != null) {
                        reconnectButton.setText("⚡ Reconnect");
                        reconnectButton.setOnClickListener(v -> reconnectDevice());
                    }
                }
                statusDot.setBackground(dotShape);
                if (slotsContainer != null) refreshPasswordGrid(); // Update trust icons
            });

        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null) svc.setConnectionListener(connectionListener);
    }

    // ─── Device Picker ───────────────────────────────────────────────────────
    private void showPairedDevicePicker() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null || !bt.isEnabled()) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (!permissionDeniedAtStartup) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.BLUETOOTH_SCAN
                        }, PERM_REQUEST_PICKER);
            } else {
                showPermissionSettingsDialog();
            }
            return;
        }

        Set<BluetoothDevice> paired;
        try {
            paired = bt.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission error", Toast.LENGTH_SHORT).show();
            return;
        }

        if (paired == null || paired.isEmpty()) {
            Toast.makeText(this, "No paired host devices found.\nPair in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(24), dp(24), dp(24), dp(16));
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(COLOR_SURFACE));
        bg.setCornerRadius(dp(20));
        dialogLayout.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Select Target PC 💻");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16));
        dialogLayout.addView(title);
        
        ScrollView scroll = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        
        AlertDialog dialog = builder.setView(dialogLayout).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        for (BluetoothDevice d : paired) {
            BluetoothClass btClass = d.getBluetoothClass();
            String icon = "💻";
            if (btClass != null) {
                int major = btClass.getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    major == BluetoothClass.Device.Major.WEARABLE ||
                    major == BluetoothClass.Device.Major.HEALTH) {
                    continue; // Skip these devices
                }
                if (major == BluetoothClass.Device.Major.PHONE) {
                    icon = "📱";
                }
            }

            String name = null;
            try { name = d.getName(); } catch (Exception ignored) {}
            String displayName = icon + "  " + (name != null ? name : "Unknown PC");
            String addr = d.getAddress();
            
            LinearLayout deviceRow = new LinearLayout(this);
            deviceRow.setOrientation(LinearLayout.HORIZONTAL);
            deviceRow.setGravity(Gravity.CENTER_VERTICAL);
            deviceRow.setPadding(dp(16), dp(12), dp(16), dp(12));
            
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(Color.parseColor(COLOR_BG));
            rowBg.setCornerRadius(dp(12));
            deviceRow.setBackground(rowBg);
            
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(8));
            deviceRow.setLayoutParams(rowParams);
            
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            
            TextView deviceNameText = new TextView(this);
            deviceNameText.setText(displayName);
            deviceNameText.setTextColor(Color.parseColor(COLOR_TEXT));
            deviceNameText.setTextSize(16);
            deviceNameText.setTypeface(null, Typeface.BOLD);
            textCol.addView(deviceNameText);
            
            TextView deviceMacText = new TextView(this);
            deviceMacText.setText(addr);
            deviceMacText.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
            deviceMacText.setTextSize(12);
            textCol.addView(deviceMacText);
            
            deviceRow.addView(textCol);
            
            TextView connectIcon = new TextView(this);
            connectIcon.setText("➔");
            connectIcon.setTextSize(20);
            connectIcon.setTextColor(Color.parseColor(COLOR_PRIMARY));
            deviceRow.addView(connectIcon);
            
            deviceRow.setOnClickListener(v -> {
                prefs.edit().putString(LAST_DEVICE_KEY, addr).apply();
                connectionStatusText.setText("Connecting to: " + displayName + "...");
                Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show();
                HidKeyboardService svc = HidKeyboardService.getInstance();
                if (svc != null) svc.connectToDevice(addr);
                dialog.dismiss();
            });
            
            listLayout.addView(deviceRow);
        }
        
        scroll.addView(listLayout);
        dialogLayout.addView(scroll);
        
        Button cancelBtn = createStyledButton("Cancel", COLOR_SURFACE_ALT, COLOR_BG);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.setMargins(0, dp(16), 0, 0);
        cancelBtn.setLayoutParams(cancelParams);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        dialogLayout.addView(cancelBtn);

        dialog.show();
    }

    private void reconnectDevice() {
        String lastDevice = prefs.getString(LAST_DEVICE_KEY, null);
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc == null) {
            startHidKeyboardService();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                HidKeyboardService s = HidKeyboardService.getInstance();
                if (s != null) s.connectToDevice(lastDevice);
            }, 800);
        } else {
            svc.connectToDevice(lastDevice);
        }
        Toast.makeText(this, "Connecting via Bluetooth...", Toast.LENGTH_SHORT).show();
    }

    // ─── Send Password with Trust Check ──────────────────────────────────────
    private void attemptSendPassword(int index) {
        String password = secureStorage.getPassword(index);
        if (password.isEmpty()) {
            Toast.makeText(this, "Slot " + (index + 1) + " is empty – tap ✏️ to edit", Toast.LENGTH_LONG).show();
            return;
        }

        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc == null || svc.getConnectedDevice() == null) {
            Toast.makeText(this, "Keyboard is not connected to any device", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = svc.getConnectedDevice();
        String macAddress = device.getAddress();
        String deviceName = device.getName();
        if (deviceName == null) deviceName = "Unknown Device";

        if (secureStorage.isDeviceTrusted(index, macAddress)) {
            sendPassword(index, password);
        } else {
            promptTrustDevice(index, macAddress, deviceName, password);
        }
    }

    private void promptTrustDevice(int index, String macAddress, String deviceName, String password) {
        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Untrusted Device")
            .setMessage("This password slot is not trusted for:\n" + deviceName + "\n\nWould you like to add it as a trusted device?")
            .setPositiveButton("Trust & Send", (dialog, which) -> {
                BiometricPrompt biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            secureStorage.addTrustedDevice(index, macAddress, deviceName);
                            refreshPasswordGrid();
                            sendPassword(index, password);
                        }
                    });

                BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Authorize Trusted Device")
                        .setSubtitle("Authenticate to trust " + deviceName)
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .build();

                biometricPrompt.authenticate(promptInfo);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void sendPassword(int index, String password) {
        String suffix = prefs.getString("suffix_" + index, "Enter");
        String suffixChar = "";
        String suffixMsg = "";
        
        switch (suffix) {
            case "Enter":
                suffixChar = "\n";
                suffixMsg = " + Enter ⏎";
                break;
            case "Tab":
                suffixChar = "\t";
                suffixMsg = " + Tab ⇥";
                break;
            case "None":
                suffixChar = "";
                suffixMsg = "";
                break;
        }
        
        final String passwordWithSuffix = password + suffixChar;
        final String finalSuffixMsg = suffixMsg;
        
        new Thread(() -> {
            HidKeyboardService svc = HidKeyboardService.getInstance();
            if (svc == null) return;
            if (!svc.isServiceReady()) {
                runOnUiThread(() -> Toast.makeText(this, "Initializing Bluetooth Keyboard...", Toast.LENGTH_SHORT).show());
                if (!svc.waitForReady(6000)) {
                    runOnUiThread(() -> Toast.makeText(this, "Bluetooth Keyboard service not ready", Toast.LENGTH_LONG).show());
                    return;
                }
            }
            svc.sendKeySequence(passwordWithSuffix);
            runOnUiThread(() -> Toast.makeText(this, "✓ Password sent" + finalSuffixMsg, Toast.LENGTH_SHORT).show());
        }).start();
    }

    // ─── Edit Password ───────────────────────────────────────────────────────
    private void editPassword(int index) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        // Custom root layout for the dialog
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(24), dp(24), dp(24), dp(16));
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(COLOR_SURFACE));
        bg.setCornerRadius(dp(20));
        dialogLayout.setBackground(bg);

        // Funny Title
        TextView title = new TextView(this);
        String[] funnyTitles = {
            "Top Secret Vault 🕵️‍♂️", 
            "Spill the beans 🫘", 
            "Your digital keys 🗝️", 
            "The magic words 🪄"
        };
        title.setText(funnyTitles[index % funnyTitles.length] + " (Slot " + (index + 1) + ")");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16));
        dialogLayout.addView(title);

        // Label Input
        EditText labelInput = new EditText(this);
        labelInput.setHint("What is this? (e.g. My secret Netflix account)");
        labelInput.setText(prefs.getString("label_" + index, "Slot " + (index + 1)));
        labelInput.setTextColor(Color.WHITE);
        labelInput.setHintTextColor(Color.parseColor(COLOR_TEXT_DIM));
        labelInput.setTextSize(14);
        labelInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable inputBg1 = new GradientDrawable();
        inputBg1.setColor(Color.parseColor(COLOR_BG));
        inputBg1.setCornerRadius(dp(8));
        labelInput.setBackground(inputBg1);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.setMargins(0, 0, 0, dp(12));
        labelInput.setLayoutParams(lp1);
        dialogLayout.addView(labelInput);

        // Password Input
        EditText passInput = new EditText(this);
        passInput.setHint("The actual password shhh 🤫");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setText(secureStorage.getPassword(index));
        passInput.setTextColor(Color.WHITE);
        passInput.setHintTextColor(Color.parseColor(COLOR_TEXT_DIM));
        passInput.setTextSize(14);
        passInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable inputBg2 = new GradientDrawable();
        inputBg2.setColor(Color.parseColor(COLOR_BG));
        inputBg2.setCornerRadius(dp(8));
        passInput.setBackground(inputBg2);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        passInput.setLayoutParams(lp2);
        dialogLayout.addView(passInput);

        // Eye Toggle
        TextView togglePass = new TextView(this);
        togglePass.setText("👁  Peek at password");
        togglePass.setTextColor(Color.parseColor(COLOR_ACCENT));
        togglePass.setTextSize(13);
        togglePass.setPadding(0, dp(8), 0, dp(16));
        final boolean[] dialogPassVisible = {false};
        togglePass.setOnClickListener(v -> {
            if (dialogPassVisible[0]) {
                passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                togglePass.setText("👁  Peek at password");
                dialogPassVisible[0] = false;
            } else {
                passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                togglePass.setText("🙈  Hide it!");
                dialogPassVisible[0] = true;
            }
            passInput.setSelection(passInput.getText().length());
        });
        dialogLayout.addView(togglePass);

        // End Action (Suffix) Selection
        TextView suffixTitle = new TextView(this);
        suffixTitle.setText("End Action");
        suffixTitle.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        suffixTitle.setTextSize(12);
        suffixTitle.setPadding(0, dp(8), 0, dp(4));
        dialogLayout.addView(suffixTitle);

        LinearLayout suffixRow = new LinearLayout(this);
        suffixRow.setOrientation(LinearLayout.HORIZONTAL);
        suffixRow.setWeightSum(3);
        suffixRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        
        String currentSuffix = prefs.getString("suffix_" + index, "Enter");
        final String[] selectedSuffix = {currentSuffix};
        
        Button enterBtn = createStyledButton("Enter ⏎", "Enter".equals(currentSuffix) ? COLOR_PRIMARY : COLOR_BG, "Enter".equals(currentSuffix) ? COLOR_PRIMARY_DARK : COLOR_SURFACE_ALT);
        Button tabBtn = createStyledButton("Tab ⇥", "Tab".equals(currentSuffix) ? COLOR_PRIMARY : COLOR_BG, "Tab".equals(currentSuffix) ? COLOR_PRIMARY_DARK : COLOR_SURFACE_ALT);
        Button noneBtn = createStyledButton("None", "None".equals(currentSuffix) ? COLOR_PRIMARY : COLOR_BG, "None".equals(currentSuffix) ? COLOR_PRIMARY_DARK : COLOR_SURFACE_ALT);
        
        LinearLayout.LayoutParams suffixBtnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        suffixBtnParams.setMargins(dp(2), 0, dp(2), dp(16));
        
        enterBtn.setLayoutParams(suffixBtnParams);
        tabBtn.setLayoutParams(suffixBtnParams);
        noneBtn.setLayoutParams(suffixBtnParams);
        
        View.OnClickListener suffixClickListener = v -> {
            // Reset all
            enterBtn.setBackgroundColor(Color.parseColor(COLOR_BG));
            tabBtn.setBackgroundColor(Color.parseColor(COLOR_BG));
            noneBtn.setBackgroundColor(Color.parseColor(COLOR_BG));
            
            // Highlight selected
            v.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
            
            if (v == enterBtn) selectedSuffix[0] = "Enter";
            else if (v == tabBtn) selectedSuffix[0] = "Tab";
            else if (v == noneBtn) selectedSuffix[0] = "None";
            
            // Re-apply corner radii because setBackgroundColor overrides the GradientDrawable
            GradientDrawable activeBg = new GradientDrawable();
            activeBg.setColor(Color.parseColor(COLOR_PRIMARY));
            activeBg.setCornerRadius(dp(8));
            v.setBackground(activeBg);
            
            GradientDrawable inactiveBg = new GradientDrawable();
            inactiveBg.setColor(Color.parseColor(COLOR_BG));
            inactiveBg.setCornerRadius(dp(8));
            
            if (v != enterBtn) enterBtn.setBackground(inactiveBg);
            if (v != tabBtn) tabBtn.setBackground(inactiveBg);
            if (v != noneBtn) noneBtn.setBackground(inactiveBg);
        };
        
        // Initial setup for backgrounds
        suffixClickListener.onClick("Enter".equals(currentSuffix) ? enterBtn : ("Tab".equals(currentSuffix) ? tabBtn : noneBtn));
        
        enterBtn.setOnClickListener(suffixClickListener);
        tabBtn.setOnClickListener(suffixClickListener);
        noneBtn.setOnClickListener(suffixClickListener);
        
        suffixRow.addView(enterBtn);
        suffixRow.addView(tabBtn);
        suffixRow.addView(noneBtn);
        dialogLayout.addView(suffixRow);

        // Trusted Devices List
        Set<String> trustedDevices = secureStorage.getTrustedDevices(index);
        if (!trustedDevices.isEmpty()) {
            TextView trustTitle = new TextView(this);
            trustTitle.setText("Authorized Devices 🛡️");
            trustTitle.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
            trustTitle.setTextSize(12);
            trustTitle.setPadding(0, dp(8), 0, dp(8));
            dialogLayout.addView(trustTitle);

            for (String device : trustedDevices) {
                String[] parts = device.split("\\|");
                String mac = parts[0];
                String name = parts.length > 1 ? parts[1] : "Unknown Device";

                LinearLayout deviceRow = new LinearLayout(this);
                deviceRow.setOrientation(LinearLayout.HORIZONTAL);
                deviceRow.setGravity(Gravity.CENTER_VERTICAL);
                deviceRow.setPadding(dp(12), dp(8), dp(12), dp(8));
                
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(Color.parseColor(COLOR_BG));
                rowBg.setCornerRadius(dp(12));
                deviceRow.setBackground(rowBg);
                
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, 0, 0, dp(8));
                deviceRow.setLayoutParams(rowParams);

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView deviceName = new TextView(this);
                deviceName.setText(name);
                deviceName.setTextColor(Color.parseColor(COLOR_TEXT));
                deviceName.setTextSize(14);
                deviceName.setTypeface(null, Typeface.BOLD);
                textCol.addView(deviceName);

                TextView deviceMac = new TextView(this);
                deviceMac.setText(mac);
                deviceMac.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
                deviceMac.setTextSize(11);
                textCol.addView(deviceMac);

                deviceRow.addView(textCol);

                TextView removeDeviceBtn = new TextView(this);
                removeDeviceBtn.setText("🗑️");
                removeDeviceBtn.setTextSize(18);
                removeDeviceBtn.setPadding(dp(12), dp(4), 0, dp(4));
                removeDeviceBtn.setOnClickListener(v -> {
                    secureStorage.removeTrustedDevice(index, mac);
                    deviceRow.setVisibility(View.GONE);
                    Toast.makeText(this, "Booted " + name + " out!", Toast.LENGTH_SHORT).show();
                });
                deviceRow.addView(removeDeviceBtn);

                dialogLayout.addView(deviceRow);
            }
            
            // Add some padding below the list
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(4)));
            dialogLayout.addView(spacer);
        }

        // Buttons Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        Button clearBtn = createStyledButton("🗑 Destroy", COLOR_WARNING, "#D97706");
        clearBtn.setTextColor(Color.WHITE);
        
        Button cancelBtn = createStyledButton("Cancel", COLOR_SURFACE_ALT, COLOR_BG);
        
        Button saveBtn = createStyledButton("💾 Save", COLOR_PRIMARY, COLOR_PRIMARY_DARK);

        AlertDialog dialog = builder.setView(dialogLayout).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        clearBtn.setOnClickListener(v -> {
            String oldLabel = prefs.getString("label_" + index, "Slot " + (index + 1));
            String oldPass = secureStorage.getPassword(index);
            String oldSuffix = prefs.getString("suffix_" + index, "Enter");
            Set<String> oldTrusted = secureStorage.getTrustedDevices(index);
            BackupState backup = new BackupState(index, oldLabel, oldPass, oldSuffix, oldTrusted, false);

            prefs.edit().remove("label_" + index).apply();
            prefs.edit().remove("suffix_" + index).apply();
            secureStorage.removePassword(index);
            secureStorage.clearTrustedDevices(index);
            refreshPasswordGrid();
            
            dialog.dismiss();
            showUndoSnackbar(backup, "Slot cleared");
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String oldLabel = prefs.getString("label_" + index, "Slot " + (index + 1));
            String oldPass = secureStorage.getPassword(index);
            String oldSuffix = prefs.getString("suffix_" + index, "Enter");
            Set<String> oldTrusted = secureStorage.getTrustedDevices(index);
            BackupState backup = new BackupState(index, oldLabel, oldPass, oldSuffix, oldTrusted, false);

            String label = labelInput.getText().toString().trim();
            if (label.isEmpty()) label = "Slot " + (index + 1);
            String password = passInput.getText().toString();
            String suffix = selectedSuffix[0];

            prefs.edit().putString("label_" + index, label).apply();
            prefs.edit().putString("suffix_" + index, suffix).apply();
            secureStorage.savePassword(index, password);

            refreshPasswordGrid();
            dialog.dismiss();
            
            // Only show undo if something actually changed
            if (!label.equals(oldLabel) || !password.equals(oldPass) || !suffix.equals(oldSuffix)) {
                showUndoSnackbar(backup, "Slot saved");
            }
        });

        // Add buttons with margins
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(dp(8), 0, 0, 0);

        btnRow.addView(clearBtn);
        btnRow.addView(cancelBtn, btnParams);
        btnRow.addView(saveBtn, btnParams);
        
        dialogLayout.addView(btnRow);

        dialog.show();
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Bluetooth Permission Required")
                .setMessage("Please grant Bluetooth / Nearby Devices permission in App Settings to proceed.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHelpDialog() {
        String versionName = "1.0.0";
        int versionCode = 1;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pInfo.versionName;
            versionCode = pInfo.versionCode;
        } catch (Exception ignored) {}

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("PassPress  •  v" + versionName + " (" + versionCode + ")");
        builder.setMessage("Welcome to PassPress v" + versionName + "!\n\n" +
                "• Setup: Click 'Connect PC' to pair your computer via Bluetooth. PassPress will act as a standard Bluetooth Keyboard.\n" +
                "• Usage: Tap a password slot to instantly send your saved password to your PC.\n" +
                "• Widgets: Long-press your home screen to add PassPress widgets for one-tap password sending without opening the app.\n" +
                "• Security: Passwords are encrypted on your device. Untrusted PCs require a fingerprint to send passwords.\n" +
                "• Versioning: Current Release v" + versionName + " (Build " + versionCode + ").");
        builder.setPositiveButton("Got it!", null);
        builder.show();
    }

    private void showSettingsDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Settings");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(20), dp(20), dp(20));

        // Typing Delay
        TextView delayLabel = new TextView(this);
        int currentDelay = prefs.getInt("typing_delay", 25);
        delayLabel.setText("Typing Delay (Anti-Paste): " + currentDelay + "ms");
        delayLabel.setTextColor(Color.WHITE);
        layout.addView(delayLabel);

        android.widget.SeekBar delaySeekBar = new android.widget.SeekBar(this);
        delaySeekBar.setMax(100);
        delaySeekBar.setProgress(currentDelay);
        delaySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                delayLabel.setText("Typing Delay (Anti-Paste): " + progress + "ms");
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                prefs.edit().putInt("typing_delay", seekBar.getProgress()).apply();
            }
        });
        layout.addView(delaySeekBar);

        // Auto-Lock Toggle
        android.widget.CheckBox autoLockCheck = new android.widget.CheckBox(this);
        autoLockCheck.setText("Enable 3-Minute Auto-Lock");
        autoLockCheck.setTextColor(Color.WHITE);
        autoLockCheck.setChecked(prefs.getBoolean("auto_lock_enabled", true));
        autoLockCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_lock_enabled", isChecked).apply();
        });
        layout.addView(autoLockCheck);

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
        layout.addView(spacer);

        // Export Button
        android.widget.Button exportBtn = new android.widget.Button(this);
        exportBtn.setText("📤 Export Backup");
        exportBtn.setBackgroundColor(Color.parseColor("#334155"));
        exportBtn.setTextColor(Color.WHITE);
        exportBtn.setOnClickListener(v -> {
            showExportDialog();
        });
        layout.addView(exportBtn);

        // Spacer 2
        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
        layout.addView(spacer2);

        // Import Button
        android.widget.Button importBtn = new android.widget.Button(this);
        importBtn.setText("📥 Import Backup");
        importBtn.setBackgroundColor(Color.parseColor("#334155"));
        importBtn.setTextColor(Color.WHITE);
        importBtn.setOnClickListener(v -> {
            showImportDialog();
        });
        layout.addView(importBtn);

        builder.setView(layout);
        builder.setPositiveButton("Close", null);
        builder.show();
    }
    
    private void showExportDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Master Password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Export Backup")
            .setMessage("Set a master password for this backup. If you lose this password, you cannot restore the backup!")
            .setView(input)
            .setPositiveButton("Export", (d, w) -> {
                String pw = input.getText().toString();
                if (pw.isEmpty()) {
                    android.widget.Toast.makeText(this, "Password cannot be empty", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingPassword = pw;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "PassPress_Backup.txt");
                startActivityForResult(intent, 101);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void showImportDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Master Password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Import Backup")
            .setMessage("Enter the master password used to encrypt the backup.")
            .setView(input)
            .setPositiveButton("Import", (d, w) -> {
                String pw = input.getText().toString();
                if (pw.isEmpty()) {
                    android.widget.Toast.makeText(this, "Password cannot be empty", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingPassword = pw;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 102);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            if (requestCode == 101) { // Export
                try {
                    String encryptedData = BackupUtils.exportData(this, pendingPassword);
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(encryptedData.getBytes("UTF-8"));
                        os.close();
                        android.widget.Toast.makeText(this, "Backup exported successfully!", android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Export failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            } else if (requestCode == 102) { // Import
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        is.close();
                        BackupUtils.importData(this, sb.toString(), pendingPassword);
                        android.widget.Toast.makeText(this, "Backup imported successfully!", android.widget.Toast.LENGTH_SHORT).show();
                        if (slotsContainer != null) {
                            refreshPasswordGrid();
                        }
                    }
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Import failed: Invalid password or file", android.widget.Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
