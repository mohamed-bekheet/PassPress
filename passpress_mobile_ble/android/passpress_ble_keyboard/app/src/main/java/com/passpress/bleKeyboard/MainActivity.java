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
    public static String COLOR_BG;
    public static String COLOR_SURFACE;
    public static String COLOR_SURFACE_ALT;
    public static String COLOR_PRIMARY;
    public static String COLOR_PRIMARY_DARK;
    public static String COLOR_ACCENT;
    public static String COLOR_SUCCESS;
    public static String COLOR_TEXT;
    public static String COLOR_TEXT_DIM;
    public static String COLOR_SEND_BTN;
    public static String COLOR_WARNING;

    private void setupThemeColors() {
        boolean isLight = prefs.getBoolean("is_light_theme", true);
        COLOR_BG = isLight ? "#F8FAFC" : "#0F172A";
        COLOR_SURFACE = isLight ? "#FFFFFF" : "#1E293B";
        COLOR_SURFACE_ALT = isLight ? "#E2E8F0" : "#334155";
        COLOR_PRIMARY = isLight ? "#4F46E5" : "#6366F1";
        COLOR_PRIMARY_DARK = isLight ? "#4338CA" : "#4F46E5";
        COLOR_ACCENT = isLight ? "#0891B2" : "#22D3EE";
        COLOR_SUCCESS = isLight ? "#10B981" : "#34D399";
        COLOR_TEXT = isLight ? "#0F172A" : "#F1F5F9";
        COLOR_TEXT_DIM = isLight ? "#64748B" : "#94A3B8";
        COLOR_SEND_BTN = isLight ? "#7C3AED" : "#8B5CF6";
        COLOR_WARNING = isLight ? "#D97706" : "#F59E0B";
    }

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

    private FrameLayout tabContainer;
    private View passwordsView;
    private View keyboardView;
    private View trackpadView;

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
        View root = findViewById(android.R.id.content);
        if (root == null) return;

        Snackbar snackbar = Snackbar.make(root, message, 8000);
        View snackbarView = snackbar.getView();

        // Background styling
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(Color.parseColor(COLOR_SURFACE));
        shape.setStroke(dp(1), Color.parseColor(COLOR_PRIMARY));
        snackbarView.setBackground(shape);

        // Elevation & floating position above bottom bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            snackbarView.setElevation(dp(8));
        }

        if (snackbarView.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams p = (android.view.ViewGroup.MarginLayoutParams) snackbarView.getLayoutParams();
            p.setMargins(dp(16), 0, dp(16), dp(80));
            snackbarView.setLayoutParams(p);
        }

        TextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        if (textView != null) {
            textView.setTextColor(Color.parseColor(COLOR_TEXT));
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textView.setTextSize(13);
        }

        Button actionBtn = snackbarView.findViewById(com.google.android.material.R.id.snackbar_action);
        if (actionBtn != null) {
            actionBtn.setTextColor(Color.parseColor(COLOR_PRIMARY));
            actionBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            actionBtn.setTextSize(13);
        }

        snackbar.setAction("UNDO ↩️", v -> {
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
                secureStorage.addTrustedDevice(backup.index, device.split("\\|")[0], device.contains("|") ? device.split("\\|")[1] : "Unknown Device");
            }
            
            refreshPasswordGrid();
            Toast.makeText(this, "Restored! ✨", Toast.LENGTH_SHORT).show();
        });
        snackbar.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        secureStorage = SecureStorage.getInstance(this);

        setupThemeColors();

        // Run migrations for existing passwords
        int numSlots = prefs.getInt(SLOT_COUNT_KEY, 10);
        for (int i = 0; i < numSlots; i++) {
            secureStorage.migrateOldPassword(this, i);
        }

        showLockScreenUI();

        // Status bar color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor(COLOR_BG));
            getWindow().setNavigationBarColor(Color.parseColor(COLOR_BG));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && prefs.getBoolean("is_light_theme", true)) {
                int flags = getWindow().getDecorView().getSystemUiVisibility();
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
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
        FrameLayout rootContainer = new FrameLayout(this);
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor(COLOR_BG));

        mainLayout.addView(createHeaderLayout());

        tabContainer = new FrameLayout(this);
        passwordsView = createPasswordsView();
        keyboardView = createKeyboardView();
        trackpadView = createTrackpadView();

        tabContainer.addView(passwordsView);
        tabContainer.addView(keyboardView);
        tabContainer.addView(trackpadView);

        passwordsView.setVisibility(View.VISIBLE);
        keyboardView.setVisibility(View.GONE);
        trackpadView.setVisibility(View.GONE);

        mainLayout.addView(tabContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mainLayout.addView(createBottomNav());

        rootContainer.addView(mainLayout);
        
        if (!prefs.getBoolean("has_seen_onboarding", false)) {
            View onboardingView = createOnboardingView(rootContainer);
            rootContainer.addView(onboardingView);
        }

        setContentView(rootContainer);
        setupConnectionListener();
        
        // Auto-reconnect on app launch if we have a saved device
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean showPicker = getIntent().getBooleanExtra("SHOW_PICKER", false);
            if (showPicker) {
                showPairedDevicePicker();
                getIntent().removeExtra("SHOW_PICKER");
            } else {
                HidKeyboardService svc = HidKeyboardService.getInstance();
                if (svc != null && svc.getConnectedDevice() == null) {
                    if (!getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE).getBoolean("manual_disconnect", false)) {
                        reconnectDevice();
                    }
                }
            }
        }, 500);
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isAuthenticated && intent.getBooleanExtra("SHOW_PICKER", false)) {
            showPairedDevicePicker();
            intent.removeExtra("SHOW_PICKER");
        }
    }
    
    private View createOnboardingView(android.view.ViewGroup parent) {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(Color.parseColor("#E6060B18")); // Dark semi-transparent
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(30), dp(40), dp(30), dp(40));
        overlay.setClickable(true); // Block touches to underlying view
        
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor(COLOR_SURFACE));
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.setPadding(dp(24), dp(32), dp(24), dp(24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        
        TextView iconView = new TextView(this);
        iconView.setTextSize(64);
        iconView.setGravity(Gravity.CENTER);
        
        TextView titleView = new TextView(this);
        titleView.setTextSize(24);
        titleView.setTextColor(Color.parseColor(COLOR_TEXT));
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, dp(16), 0, dp(16));
        
        TextView descView = new TextView(this);
        descView.setTextSize(16);
        descView.setTextColor(Color.parseColor(COLOR_TEXT));
        descView.setGravity(Gravity.CENTER);
        descView.setLineSpacing(dp(4), 1.2f);
        
        String[] icons = {"👋", "💻", "❓"};
        String[] titles = {"Welcome to PassPress", "Connect to PC", "Crucial Step"};
        String[] descriptions = {
            "Your secure, offline hardware password manager. PassPress acts as a virtual Bluetooth keyboard.",
            "To connect, ensure Bluetooth is enabled on your PC/Laptop, then tap the 'Connect PC' option after tapping the target device icon.",
            "If you can't find 'PassPress Keyboard' in the Windows Bluetooth devices list, you must click 'Show all devices', then look for and choose one of the 'Unknown devices'. Wait a moment and the pairing code will appear!"
        };
        
        final int[] currentSlide = {0};
        
        Runnable updateSlide = () -> {
            iconView.setText(icons[currentSlide[0]]);
            titleView.setText(titles[currentSlide[0]]);
            descView.setText(descriptions[currentSlide[0]]);
        };
        updateSlide.run();
        
        card.addView(iconView);
        card.addView(titleView);
        card.addView(descView);
        
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(40)));
        card.addView(spacer);
        
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        
        Button btnSkip = new Button(this);
        btnSkip.setText("Skip");
        btnSkip.setBackgroundColor(Color.TRANSPARENT);
        btnSkip.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        
        View flexSpacer = new View(this);
        flexSpacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        
        Button btnNext = createStyledButton("Next ➔", COLOR_PRIMARY, COLOR_PRIMARY_DARK);
        
        btnRow.addView(btnSkip);
        btnRow.addView(flexSpacer);
        btnRow.addView(btnNext);
        card.addView(btnRow);
        
        overlay.addView(card);
        
        Runnable finishOnboarding = () -> {
            prefs.edit().putBoolean("has_seen_onboarding", true).apply();
            parent.removeView(overlay);
        };
        
        btnSkip.setOnClickListener(v -> finishOnboarding.run());
        
        btnNext.setOnClickListener(v -> {
            if (currentSlide[0] < 2) {
                currentSlide[0]++;
                updateSlide.run();
                if (currentSlide[0] == 2) {
                    btnNext.setText("Finish ✔️");
                }
            } else {
                finishOnboarding.run();
            }
        });
        
        return overlay;
    }

    private View createPasswordsView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView subtitleText = new TextView(this);
        subtitleText.setText("Tap Send to type your password via Bluetooth Keyboard");
        subtitleText.setTextSize(13);
        subtitleText.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        subtitleText.setPadding(dp(20), dp(12), dp(20), dp(8));
        layout.addView(subtitleText);

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

        layout.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private View createKeyboardView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor(COLOR_BG));
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Native Input Area
        EditText inputField = new EditText(this);
        inputField.setTextColor(Color.TRANSPARENT);
        inputField.setCursorVisible(false);
        inputField.setBackgroundColor(Color.TRANSPARENT);
        
        FrameLayout inputContainer = new FrameLayout(this);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor(COLOR_SURFACE));
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
        inputContainer.setBackground(inputBg);
        
        TextView placeholder = new TextView(this);
        placeholder.setText("Tap to open mobile keyboard...");
        placeholder.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        placeholder.setGravity(Gravity.CENTER);
        
        inputContainer.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(40)));
        inputContainer.addView(inputField, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(40)));
                
        // Input logic (non-destructive to preserve IME long-press state)
        String spaces = "                                                                                                    "; // 100 spaces
        inputField.setText(spaces);
        inputField.setSelection(spaces.length());
        
        inputField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                HidKeyboardService svc = HidKeyboardService.getInstance();
                if (svc == null || !svc.isServiceReady()) return;

                if (before > count) { // Text deleted (Backspace)
                    int deletes = before - count;
                    for (int i = 0; i < deletes; i++) {
                        svc.sendKeyDown((byte)0, (byte)0x2A);
                        svc.sendKeyUp();
                    }
                } else if (count > before) { // Text added
                    String typed = s.subSequence(start + before, start + count).toString();
                    svc.sendKeySequence(typed);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // Only reset if we are running out of space to delete, or getting too large.
                // Doing it too often interrupts the soft keyboard's long-press repeating timer.
                if (s.length() < 10 || s.length() > 300) {
                    inputField.post(() -> {
                        inputField.removeTextChangedListener(this);
                        inputField.setText(spaces);
                        inputField.setSelection(spaces.length());
                        inputField.addTextChangedListener(this);
                    });
                }
            }
        });
        
        // Enter key handling
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            HidKeyboardService svc = HidKeyboardService.getInstance();
            if (svc != null && svc.isServiceReady()) {
                svc.sendKeyDown((byte)0, (byte)0x28); // ENTER
                svc.sendKeyUp();
            }
            return true;
        });

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        
        String[] keyNames = {
            "Esc", "F1", "F2", "F3",
            "F4", "F5", "F6", "F7",
            "F8", "F9", "F10", "F11",
            "F12", "Tab", "Del", "Bksp",
            "Copy", "Paste", "Home", "End",
            "Shift", "Ctrl", "⊞", "Alt",
            "🌍", "Fn", "▲", "Enter",
            "", "◀", "▼", "▶"
        };
        
        String[] mediaNames = {
            "Esc", "🔇", "Vol-", "Vol+",
            "⏯", "⏮", "⏭", "Stop",
            "🔅", "🔆", "🔢", "🌐",
            "PrtSc", "Tab", "Del", "Bksp",
            "Copy", "Paste", "Home", "End",
            "Shift", "Ctrl", "⊞", "Alt",
            "🌍", "Fn", "▲", "Enter",
            "", "◀", "▼", "▶"
        };

        // 0=Special, 1=Modifier, 2=Macro, 3=Empty, 4=Fn Toggle
        int[] keyTypes = {
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            2, 2, 0, 0,
            1, 1, 1, 1,
            0, 4, 0, 0,
            3, 0, 0, 0
        };

        byte[] modMasks = new byte[32];
        modMasks[20] = 0x02; // Shift
        modMasks[21] = 0x01; // Ctrl
        modMasks[22] = 0x08; // Win
        modMasks[23] = 0x04; // Alt

        byte[] specialMods = new byte[32];
        specialMods[24] = 0x08; // Lang (uses Win)

        byte[] specialCodes = {
            0x29, 0x3A, 0x3B, 0x3C,
            0x3D, 0x3E, 0x3F, 0x40,
            0x41, 0x42, 0x43, 0x44,
            0x45, 0x2B, 0x4C, 0x2A,
            0, 0, 0x4A, 0x4D,
            0, 0, 0, 0,
            0x2C, 0, 0x52, 0x28,
            0, 0x50, 0x51, 0x4F
        };
        
        // Media bytes for F1-F11 (Consumer Control usage IDs - 16 bit)
        short[] mediaCodes = new short[32];
        mediaCodes[1] = 0x0010; // Mute
        mediaCodes[2] = 0x0020; // Vol Down
        mediaCodes[3] = 0x0040; // Vol Up
        mediaCodes[4] = 0x0008; // Play/Pause
        mediaCodes[5] = 0x0002; // Prev Track
        mediaCodes[6] = 0x0001; // Next Track
        mediaCodes[7] = 0x0004; // Stop
        mediaCodes[8] = 0x0100; // Brightness Dec
        mediaCodes[9] = 0x0080; // Brightness Inc
        mediaCodes[10] = 0x0200; // Calculator
        mediaCodes[11] = 0x0400; // Browser
        
        byte[] fnKeyboardCodes = new byte[32];
        fnKeyboardCodes[12] = 0x46; // Print Screen

        byte[] macroMods = new byte[32];
        macroMods[16] = 0x01; // Copy (Ctrl)
        macroMods[17] = 0x01; // Paste (Ctrl)
        
        byte[] macroCodes = new byte[32];
        macroCodes[16] = 0x06; // C
        macroCodes[17] = 0x19; // V

        int margin = dp(2);
        Button[] buttons = new Button[32];
        final boolean[] fnToggled = {false};

        for (int i = 0; i < keyNames.length; i++) {
            Button btn = createStyledButton(keyNames[i], COLOR_SURFACE_ALT, COLOR_SURFACE);
            btn.setTextSize(12);
            
            // Color code based on groups
            int textColor = Color.parseColor(COLOR_TEXT);
            String name = keyNames[i];
            if (name.startsWith("F") && name.length() > 1 && Character.isDigit(name.charAt(1))) {
                textColor = Color.parseColor("#BB86FC"); // Purple for F-keys
            } else if (name.equals("Copy") || name.equals("Paste")) {
                textColor = Color.parseColor("#FFB300"); // Amber for Macros
            } else if (name.equals("▲") || name.equals("▼") || name.equals("◀") || name.equals("▶")) {
                textColor = Color.parseColor("#03DAC6"); // Teal for Arrows
            } else if (name.equals("Shift") || name.equals("Ctrl") || name.equals("⊞") || name.equals("Alt") || name.equals("Fn") || name.equals("🌍")) {
                textColor = Color.parseColor("#FF5252"); // Red for Modifiers
            }
            btn.setTextColor(textColor);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(46);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(margin, margin, margin, margin);
            btn.setLayoutParams(params);
            buttons[i] = btn;
            
            final int index = i;
            
            if (keyTypes[i] == 3) {
                // Empty space
                btn.setVisibility(View.INVISIBLE);
            } else if (keyTypes[i] == 4) {
                // Fn Toggle logic
                btn.setOnClickListener(v -> {
                    fnToggled[0] = !fnToggled[0];
                    if (fnToggled[0]) {
                        btn.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
                        for(int j=1; j<=12; j++) buttons[j].setText(mediaNames[j]);
                    } else {
                        GradientDrawable bg = new GradientDrawable();
                        bg.setColor(Color.parseColor(COLOR_SURFACE));
                        bg.setCornerRadius(dp(12));
                        bg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
                        btn.setBackground(bg);
                        for(int j=1; j<=12; j++) buttons[j].setText(keyNames[j]);
                    }
                });
            } else if (keyTypes[i] == 1) {
                // Modifier
                final byte mask = modMasks[i];
                final boolean[] isToggled = {false};
                
                btn.setOnClickListener(v -> {
                    isToggled[0] = !isToggled[0];
                    if (isToggled[0]) {
                        btn.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
                    } else {
                        GradientDrawable bg = new GradientDrawable();
                        bg.setColor(Color.parseColor(COLOR_SURFACE));
                        bg.setCornerRadius(dp(12));
                        bg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
                        btn.setBackground(bg);
                    }
                    
                    HidKeyboardService svc = HidKeyboardService.getInstance();
                    if (svc != null && svc.isServiceReady()) {
                        svc.setModifierState(mask, isToggled[0]);
                    }
                });
            } else if (keyTypes[i] == 2) {
                // Macro (Copy/Paste)
                final byte mod = macroMods[i];
                final byte code = macroCodes[i];
                
                btn.setOnTouchListener((v, event) -> {
                    HidKeyboardService svc = HidKeyboardService.getInstance();
                    if (svc == null || !svc.isServiceReady()) return false;
                    
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        btn.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
                        svc.sendKeyDown(mod, code);
                    } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                        GradientDrawable bg = new GradientDrawable();
                        bg.setColor(Color.parseColor(COLOR_SURFACE));
                        bg.setCornerRadius(dp(12));
                        bg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
                        btn.setBackground(bg);
                        svc.sendKeyUp();
                    }
                    return true;
                });
            } else {
                // Special key
                final byte mod = specialMods[i];
                final byte code = specialCodes[i];
                final short mediaCode = mediaCodes[i];
                final byte fnSpecial = fnKeyboardCodes[i];
                
                btn.setOnTouchListener((v, event) -> {
                    HidKeyboardService svc = HidKeyboardService.getInstance();
                    if (svc == null || !svc.isServiceReady()) return false;
                    
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        btn.setBackgroundColor(Color.parseColor(COLOR_PRIMARY));
                        if (fnToggled[0] && mediaCode != 0) {
                            svc.sendConsumerReport(mediaCode);
                        } else if (fnToggled[0] && fnSpecial != 0) {
                            svc.sendKeyDown((byte)0, fnSpecial);
                        } else {
                            svc.sendKeyDown(mod, code);
                        }
                    } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                        GradientDrawable bg = new GradientDrawable();
                        bg.setColor(Color.parseColor(COLOR_SURFACE));
                        bg.setCornerRadius(dp(12));
                        bg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
                        btn.setBackground(bg);
                        if (fnToggled[0] && mediaCode != 0) {
                            svc.sendConsumerReport((short)0);
                        } else {
                            svc.sendKeyUp();
                        }
                    }
                    return true;
                });
            }
            grid.addView(btn);
        }
        
        layout.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Add Native Input at the bottom
        layout.addView(inputContainer);

        ScrollView scroller = new ScrollView(this);
        scroller.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        scroller.addView(layout);

        return scroller;
    }

    private View createTrackpadView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor(COLOR_BG));
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Trackpad area
        FrameLayout trackpad = new FrameLayout(this);
        LinearLayout.LayoutParams tpParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        tpParams.bottomMargin = dp(16);
        trackpad.setLayoutParams(tpParams);
        
        GradientDrawable tpBg = new GradientDrawable();
        tpBg.setColor(Color.parseColor(COLOR_SURFACE));
        tpBg.setCornerRadius(dp(16));
        tpBg.setStroke(dp(1), Color.parseColor(COLOR_SURFACE_ALT));
        trackpad.setBackground(tpBg);
        
        TextView instructions = new TextView(this);
        instructions.setText("TRACKPAD\nDrag to move, tap to click");
        instructions.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        instructions.setGravity(Gravity.CENTER);
        trackpad.addView(instructions, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Visual Scrollbar on the right edge
        View scrollBar = new View(this);
        GradientDrawable scrollBg = new GradientDrawable();
        scrollBg.setColor(Color.parseColor("#1AFFFFFF"));
        scrollBg.setCornerRadii(new float[]{0,0, dp(16),dp(16), dp(16),dp(16), 0,0});
        scrollBar.setBackground(scrollBg);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(dp(45), FrameLayout.LayoutParams.MATCH_PARENT);
        scrollParams.gravity = Gravity.END;
        trackpad.addView(scrollBar, scrollParams);
        
        TextView scrollIcon = new TextView(this);
        scrollIcon.setText("↕");
        scrollIcon.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        scrollIcon.setTextSize(20);
        scrollIcon.setGravity(Gravity.CENTER);
        trackpad.addView(scrollIcon, scrollParams);

        // Mouse buttons
        LinearLayout buttonsLayout = new LinearLayout(this);
        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonsLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));

        Button leftClick = createStyledButton("LEFT CLICK", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        btnParams.rightMargin = dp(8);
        leftClick.setLayoutParams(btnParams);

        Button rightClick = createStyledButton("RIGHT CLICK", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams btnParamsRight = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        btnParamsRight.leftMargin = dp(8);
        rightClick.setLayoutParams(btnParamsRight);

        buttonsLayout.addView(leftClick);
        buttonsLayout.addView(rightClick);

        layout.addView(trackpad);
        layout.addView(buttonsLayout);

        // Touch handling
        trackpad.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            private float fractionScroll = 0;
            private long downTime;
            
            // Multi-touch tracking
            private boolean isThreeFingerSwipe = false;
            private float threeFingerStartX, threeFingerStartY;
            private boolean threeFingerFired = false;
            private int scrollBarWidth = dp(45);

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int pointerCount = event.getPointerCount();
                int action = event.getActionMasked();

                switch (action) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        downTime = System.currentTimeMillis();
                        isThreeFingerSwipe = false;
                        threeFingerFired = false;
                        fractionScroll = 0;
                        return true;

                    case android.view.MotionEvent.ACTION_POINTER_DOWN:
                        if (pointerCount == 2) {
                            lastY = (event.getY(0) + event.getY(1)) / 2;
                            fractionScroll = 0;
                        } else if (pointerCount == 3) {
                            isThreeFingerSwipe = true;
                            threeFingerStartX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3;
                            threeFingerStartY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3;
                            threeFingerFired = false;
                        }
                        return true;

                    case android.view.MotionEvent.ACTION_MOVE:
                        if (isThreeFingerSwipe && !threeFingerFired && pointerCount == 3) {
                            float currX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3;
                            float currY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3;
                            float dx = currX - threeFingerStartX;
                            float dy = currY - threeFingerStartY;
                            
                            // Trigger if moved more than 100px
                            if (Math.abs(dx) > 100 || Math.abs(dy) > 100) {
                                HidKeyboardService svc = HidKeyboardService.getInstance();
                                if (svc != null && svc.isServiceReady()) {
                                    if (Math.abs(dy) > Math.abs(dx)) {
                                        if (dy < 0) {
                                            // Swipe Up -> Win + Tab (Task View)
                                            svc.sendKeyDown((byte)0x08, (byte)0x2B); // Win + Tab
                                            svc.sendKeyUp();
                                        } else {
                                            // Swipe Down -> Win + D (Desktop)
                                            svc.sendKeyDown((byte)0x08, (byte)0x07); // Win + D
                                            svc.sendKeyUp();
                                        }
                                    } else {
                                        if (dx != 0) { // Any horizontal swipe -> Alt + Tab
                                            svc.sendKeyDown((byte)0x04, (byte)0x2B); // Alt + Tab
                                            svc.sendKeyUp();
                                        }
                                    }
                                }
                                threeFingerFired = true; // Prevent multiple fires
                            }
                            return true;
                        }

                        if (pointerCount == 2 && !isThreeFingerSwipe) {
                            // Two-finger scroll
                            float currY = (event.getY(0) + event.getY(1)) / 2;
                            float dy = currY - lastY;
                            float scrollSens = prefs.getFloat("scroll_sensitivity", 0.05f);
                            fractionScroll += (dy * -scrollSens); // Negative for natural scrolling, scale factor
                            
                            int sendScroll = (int) fractionScroll;
                            if (sendScroll != 0) {
                                HidKeyboardService svc = HidKeyboardService.getInstance();
                                if (svc != null && svc.isServiceReady()) {
                                    svc.sendMouseReport((byte)0, (byte)0, (byte)0, (byte)sendScroll);
                                }
                                fractionScroll -= sendScroll;
                            }
                            lastY = currY;
                            return true;
                        }

                        if (pointerCount == 1 && !isThreeFingerSwipe) {
                            float dx = event.getX() - lastX;
                            float dy = event.getY() - lastY;
                            
                            HidKeyboardService svc = HidKeyboardService.getInstance();
                            if (svc != null && svc.isServiceReady()) {
                                if (lastX > v.getWidth() - scrollBarWidth) {
                                    // 1-Finger Scroll Bar
                                    float scrollSens = prefs.getFloat("scroll_sensitivity", 0.05f);
                                    fractionScroll += (dy * -scrollSens);
                                    int sendScroll = (int) fractionScroll;
                                    if (sendScroll != 0) {
                                        svc.sendMouseReport((byte)0, (byte)0, (byte)0, (byte)sendScroll);
                                        fractionScroll -= sendScroll;
                                    }
                                    lastY = event.getY();
                                } else {
                                    // Normal Mouse Move
                                    float mouseSens = prefs.getFloat("mouse_sensitivity", 1.5f);
                                    int sendDx = (int) (dx * mouseSens);
                                    int sendDy = (int) (dy * mouseSens);
                                    if (sendDx != 0 || sendDy != 0) {
                                        sendDx = Math.max(-127, Math.min(127, sendDx));
                                        sendDy = Math.max(-127, Math.min(127, sendDy));
                                        svc.sendMouseReport((byte)0, (byte)sendDx, (byte)sendDy, (byte)0);
                                        lastX = event.getX();
                                        lastY = event.getY();
                                    }
                                }
                            }
                            return true;
                        }
                        return true;

                    case android.view.MotionEvent.ACTION_UP:
                        if (pointerCount == 1 && !isThreeFingerSwipe && System.currentTimeMillis() - downTime < 200) {
                            // Only click if it wasn't a scrollbar touch
                            float startX = event.getX() - (event.getX() - lastX); // Approx startX
                            if (lastX <= v.getWidth() - scrollBarWidth) {
                                HidKeyboardService svc = HidKeyboardService.getInstance();
                                if (svc != null && svc.isServiceReady()) {
                                    svc.sendMouseReport((byte)1, (byte)0, (byte)0, (byte)0);
                                    svc.sendMouseReport((byte)0, (byte)0, (byte)0, (byte)0);
                                }
                            }
                        }
                        isThreeFingerSwipe = false;
                        return true;
                        
                    case android.view.MotionEvent.ACTION_POINTER_UP:
                        if (pointerCount == 3) {
                            // Keep lastY correct for 2 finger scroll if we drop from 3 to 2
                            int remaining1 = event.getActionIndex() == 0 ? 1 : 0;
                            int remaining2 = event.getActionIndex() == 2 ? 1 : 2;
                            lastY = (event.getY(remaining1) + event.getY(remaining2)) / 2;
                            fractionScroll = 0;
                        } else if (pointerCount == 2) {
                            // Keep lastX/lastY correct if we drop from 2 to 1
                            int remainingIndex = event.getActionIndex() == 0 ? 1 : 0;
                            lastX = event.getX(remainingIndex);
                            lastY = event.getY(remainingIndex);
                            fractionScroll = 0;
                        }
                        return true;
                }
                return false;
            }
        });

        View.OnTouchListener btnListener = (v, event) -> {
            byte btnMask = (v == leftClick) ? (byte)1 : (byte)2;
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                HidKeyboardService svc = HidKeyboardService.getInstance();
                if (svc != null && svc.isServiceReady()) {
                    svc.sendMouseReport(btnMask, (byte)0, (byte)0, (byte)0);
                }
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                HidKeyboardService svc = HidKeyboardService.getInstance();
                if (svc != null && svc.isServiceReady()) {
                    svc.sendMouseReport((byte)0, (byte)0, (byte)0, (byte)0);
                }
            }
            return false;
        };

        leftClick.setOnTouchListener(btnListener);
        rightClick.setOnTouchListener(btnListener);

        return layout;
    }

    private View createBottomNav() {
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.parseColor(COLOR_SURFACE));
        navBar.setElevation(dp(16));
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
        
        Button btnPasswords = new Button(this);
        btnPasswords.setText("🔑");
        btnPasswords.setTextSize(28);
        btnPasswords.setBackgroundColor(Color.TRANSPARENT);
        btnPasswords.setTextColor(Color.parseColor(COLOR_PRIMARY));
        
        Button btnKeyboard = new Button(this);
        btnKeyboard.setText("⌨️");
        btnKeyboard.setTextSize(28);
        btnKeyboard.setBackgroundColor(Color.TRANSPARENT);
        btnKeyboard.setTextColor(Color.parseColor(COLOR_TEXT));
        
        Button btnMouse = new Button(this);
        btnMouse.setText("🖱️");
        btnMouse.setTextSize(28);
        btnMouse.setBackgroundColor(Color.TRANSPARENT);
        btnMouse.setTextColor(Color.parseColor(COLOR_TEXT));
        
        navBar.addView(btnPasswords, btnParams);
        navBar.addView(btnKeyboard, btnParams);
        navBar.addView(btnMouse, btnParams);
        
        View.OnClickListener listener = v -> {
            passwordsView.setVisibility(v == btnPasswords ? View.VISIBLE : View.GONE);
            keyboardView.setVisibility(v == btnKeyboard ? View.VISIBLE : View.GONE);
            trackpadView.setVisibility(v == btnMouse ? View.VISIBLE : View.GONE);
            
            btnPasswords.setTextColor(v == btnPasswords ? Color.parseColor(COLOR_PRIMARY) : Color.parseColor(COLOR_TEXT));
            btnKeyboard.setTextColor(v == btnKeyboard ? Color.parseColor(COLOR_PRIMARY) : Color.parseColor(COLOR_TEXT));
            btnMouse.setTextColor(v == btnMouse ? Color.parseColor(COLOR_PRIMARY) : Color.parseColor(COLOR_TEXT));
        };
        
        btnPasswords.setOnClickListener(listener);
        btnKeyboard.setOnClickListener(listener);
        btnMouse.setOnClickListener(listener);
        
        return navBar;
    }



    private void refreshPasswordGrid() {
        if (slotsContainer == null) return;
        slotsContainer.removeAllViews();
        int minSlotCount = secureStorage.getMaxSlotWithData();
        int savedSlotCount = prefs.getInt(SLOT_COUNT_KEY, 2);
        int slotCount = Math.max(savedSlotCount, minSlotCount);
        if (slotCount > savedSlotCount) {
            prefs.edit().putInt(SLOT_COUNT_KEY, slotCount).apply();
        }

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
        updateAllWidgets(this);
    }

    public static void updateAllWidgets(Context context) {
        try {
            android.appwidget.AppWidgetManager appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context);
            
            // Grid widgets
            android.content.ComponentName gridComponent = new android.content.ComponentName(context, PassPressWidgetProvider.class);
            int[] gridIds = appWidgetManager.getAppWidgetIds(gridComponent);
            if (gridIds != null && gridIds.length > 0) {
                for (int id : gridIds) {
                    PassPressWidgetProvider.updateAppWidget(context, appWidgetManager, id);
                }
                appWidgetManager.notifyAppWidgetViewDataChanged(gridIds, R.id.widget_grid);
            }

            // Single slot widgets
            android.content.ComponentName singleComponent = new android.content.ComponentName(context, SingleSlotWidgetProvider.class);
            int[] singleIds = appWidgetManager.getAppWidgetIds(singleComponent);
            if (singleIds != null && singleIds.length > 0) {
                for (int id : singleIds) {
                    SingleSlotWidgetProvider.updateAppWidget(context, appWidgetManager, id);
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating widgets", e);
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
        
        boolean autoLockEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("auto_lock_enabled", false);
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

    private void shutDownApp() {
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null) {
            svc.disconnectDevice();
            stopService(new Intent(this, HidKeyboardService.class));
        }
        finishAffinity();
        System.exit(0);
    }

    // ─── Header ──────────────────────────────────────────────────────────────
    private LinearLayout createHeaderLayout() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(16));

        boolean isLight = prefs.getBoolean("is_light_theme", true);
        GradientDrawable headerBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                isLight ? new int[]{Color.parseColor("#E0E7FF"), Color.parseColor("#CFFAFE")}
                        : new int[]{Color.parseColor("#1E1B4B"), Color.parseColor("#0F172A"), Color.parseColor("#164E63")}
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
        appTitle.setTextColor(Color.parseColor(COLOR_TEXT));
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

        Button connectButton = createStyledButton("💻 Connect PC", COLOR_PRIMARY, COLOR_PRIMARY_DARK);
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
        badge.setTextColor(Color.parseColor(COLOR_TEXT));
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
        btn.setTextColor(Color.parseColor(COLOR_TEXT));
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
        title.setTextColor(Color.parseColor(COLOR_TEXT));
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
                prefs.edit()
                    .putString(LAST_DEVICE_KEY, addr)
                    .putString("last_connected_device_name", displayName)
                    .apply();
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
        String suffix = prefs.getString("suffix_" + index, "Enter");
        if (password.isEmpty() && "None".equals(suffix)) {
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
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16));
        dialogLayout.addView(title);

        // Label Input
        EditText labelInput = new EditText(this);
        labelInput.setHint("What is this? (e.g. My secret Netflix account)");
        labelInput.setText(prefs.getString("label_" + index, "Slot " + (index + 1)));
        labelInput.setTextColor(Color.parseColor(COLOR_TEXT));
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
        passInput.setTextColor(Color.parseColor(COLOR_TEXT));
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
        clearBtn.setTextColor(Color.parseColor(COLOR_TEXT));
        
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

            int currentCount = prefs.getInt(SLOT_COUNT_KEY, 2);
            boolean isDeletedCard = currentCount > 2;

            BackupState backup = new BackupState(index, oldLabel, oldPass, oldSuffix, oldTrusted, isDeletedCard);

            if (isDeletedCard) {
                // Shift subsequent slots UP
                for (int i = index; i < currentCount - 1; i++) {
                    String nextPass = secureStorage.getPassword(i + 1);
                    String nextLabel = prefs.getString("label_" + (i + 1), "Slot " + (i + 2));
                    String nextSuffix = prefs.getString("suffix_" + (i + 1), "Enter");
                    Set<String> nextTrusted = secureStorage.getTrustedDevices(i + 1);

                    secureStorage.savePassword(i, nextPass);
                    prefs.edit().putString("label_" + i, nextLabel).apply();
                    prefs.edit().putString("suffix_" + i, nextSuffix).apply();

                    secureStorage.clearTrustedDevices(i);
                    for (String device : nextTrusted) {
                        secureStorage.addTrustedDevice(i, device.split("\\|")[0], device.contains("|") ? device.split("\\|")[1] : "Unknown Device");
                    }
                }

                // Delete last slot data
                int last = currentCount - 1;
                prefs.edit().remove("label_" + last).apply();
                prefs.edit().remove("suffix_" + last).apply();
                secureStorage.removePassword(last);
                secureStorage.clearTrustedDevices(last);

                // Reduce slot count
                prefs.edit().putInt(SLOT_COUNT_KEY, currentCount - 1).apply();
            } else {
                // Clear single slot content
                prefs.edit().remove("label_" + index).apply();
                prefs.edit().remove("suffix_" + index).apply();
                secureStorage.removePassword(index);
                secureStorage.clearTrustedDevices(index);
            }

            refreshPasswordGrid();
            dialog.dismiss();
            showUndoSnackbar(backup, isDeletedCard ? "Slot deleted" : "Slot cleared");
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

    private void addHelpSection(LinearLayout parent, String titleText, String contentText) {
        // Section Title
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor(COLOR_ACCENT));
        title.setPadding(0, dp(20), 0, dp(6));
        parent.addView(title);
        
        // Section Content
        TextView content = new TextView(this);
        content.setText(contentText);
        content.setTextSize(14);
        content.setTextColor(Color.parseColor(COLOR_TEXT));
        content.setLineSpacing(dp(4), 1.3f); // Better line spacing
        parent.addView(content);
        
        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor(COLOR_SURFACE_ALT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(16), 0, 0);
        divider.setLayoutParams(params);
        parent.addView(divider);
    }

    private void showHelpDialog() {
        String versionName = "1.0.0";
        int versionCode = 1;
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pInfo.versionName;
            versionCode = pInfo.versionCode;
        } catch (Exception ignored) {}

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert);
        
        ScrollView scrollView = new ScrollView(this);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));
        
        // Title
        TextView title = new TextView(this);
        title.setText("PassPress Help");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor(COLOR_TEXT));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        layout.addView(title);
        
        addHelpSection(layout, "✨ Features & Capabilities",
            "• 🔑 Hardware BLE Keyboard: Emulates an offline physical Bluetooth HID keyboard without drivers.\n\n" +
            "• 🔐 Zero-Knowledge Storage: Passwords encrypted on-device via AES-256 GCM in Android Keystore.\n\n" +
            "• ⚡ Quick Access Options: Floating Bubble, Custom Notification Toolbar, and Quick Settings Tile.\n\n" +
            "• 📱 Home Screen Widgets: Instant 1-tap password typing directly from your home screen.\n\n" +
            "• 🛡️ Biometric Security: Untrusted PCs require fingerprint authentication before sending.");

        addHelpSection(layout, "🔌 How to Connect", 
            "1. Turn on Bluetooth on your PC/Mac.\n" +
            "2. Tap 'Connect PC' in PassPress.\n" +
            "3. On your PC, look for 'PassPress Keyboard' and pair it.\n\n" +
            "Note: It's normal if Windows displays a 'Phone' icon. It will still function fully as a keyboard!");
            
        addHelpSection(layout, "🛠️ Troubleshooting",
            "• Can't find the device in Windows?\n" +
            "  If you aren't able to find the \"PassPress Keyboard\" in the Windows Bluetooth search list, press \"Show all devices\", then choose one of the \"Unknown Devices\". Wait a moment, and the pairing code will appear.\n\n" +
            "• Stuck on 'Connecting...'?\n" +
            "  Go to your PC's Bluetooth settings, completely remove/unpair the device, and try connecting again.\n\n" +
            "• Doesn't Type?\n" +
            "  Ensure your text cursor is actively inside a password field on your PC before tapping Send.\n\n" +
            "• Auto-Reconnect Failing?\n" +
            "  The app attempts to reconnect automatically in the background. If it fails, simply tap the '⚡ Reconnect' button.");
            
        addHelpSection(layout, "📱 Usage & Quick Access",
            "• Widgets: Long-press your home screen to add PassPress Widgets for instant one-tap access.\n\n" +
            "• Settings: Enable the Custom Notification Toolbar, Quick Settings Tile, or Floating Bubble from the settings menu for even faster access.");

        addHelpSection(layout, "🔒 Security",
            "• Encryption: All passwords are encrypted directly on your device.\n\n" +
            "• Biometrics: Untrusted PCs will always require your fingerprint authentication before sending a password.");
            
        TextView versionText = new TextView(this);
        versionText.setText("Version " + versionName + " (Build " + versionCode + ")");
        versionText.setTextSize(12);
        versionText.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        versionText.setGravity(Gravity.CENTER);
        versionText.setPadding(0, dp(16), 0, dp(8));
        layout.addView(versionText);

        scrollView.addView(layout);
        builder.setView(scrollView);
        
        // Add Close Button
        builder.setPositiveButton("Close", null);
        
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
        
        // Style the Close button
        Button positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        if (positiveButton != null) {
            positiveButton.setTextColor(Color.parseColor(COLOR_ACCENT));
        }
    }

    private void showSettingsDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Settings");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(20), dp(20), dp(20));

        // Typing Delay
        TextView delayLabel = new TextView(this);
        int currentDelay = prefs.getInt("typing_delay", 25);
        delayLabel.setText("🐢 Typing Delay (Anti-Paste): " + currentDelay + "ms 🐇");
        delayLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        layout.addView(delayLabel);

        android.widget.SeekBar delaySeekBar = new android.widget.SeekBar(this);
        delaySeekBar.setMax(100);
        delaySeekBar.setProgress(currentDelay);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            delaySeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
            delaySeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        delaySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                delayLabel.setText("🐢 Typing Delay (Anti-Paste): " + progress + "ms 🐇");
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                prefs.edit().putInt("typing_delay", seekBar.getProgress()).apply();
            }
        });
        layout.addView(delaySeekBar);

        // Spacer
        View spacerSens = new View(this);
        spacerSens.setLayoutParams(new LinearLayout.LayoutParams(1, dp(16)));
        layout.addView(spacerSens);

        // Mouse Sensitivity
        TextView mouseLabel = new TextView(this);
        float currentMouseSens = prefs.getFloat("mouse_sensitivity", 1.5f);
        mouseLabel.setText("🐢 Mouse DPI / Sensitivity: " + String.format("%.1fx", currentMouseSens) + " 🐇");
        mouseLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        layout.addView(mouseLabel);

        android.widget.SeekBar mouseSeekBar = new android.widget.SeekBar(this);
        mouseSeekBar.setMax(50); // 0.1 to 5.0
        mouseSeekBar.setProgress((int)(currentMouseSens * 10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mouseSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
            mouseSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        mouseSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float val = Math.max(1, progress) / 10f;
                mouseLabel.setText("🐢 Mouse DPI / Sensitivity: " + String.format("%.1fx", val) + " 🐇");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                float val = Math.max(1, seekBar.getProgress()) / 10f;
                prefs.edit().putFloat("mouse_sensitivity", val).apply();
            }
        });
        layout.addView(mouseSeekBar);

        // Scroll Sensitivity
        TextView scrollLabel = new TextView(this);
        float currentScrollSens = prefs.getFloat("scroll_sensitivity", 0.05f);
        scrollLabel.setText("🐢 Scroll Sensitivity: " + String.format("%.2fx", currentScrollSens) + " 🐇");
        scrollLabel.setTextColor(Color.parseColor(COLOR_TEXT));
        layout.addView(scrollLabel);

        android.widget.SeekBar scrollSeekBar = new android.widget.SeekBar(this);
        scrollSeekBar.setMax(50); // 0.01 to 0.50
        scrollSeekBar.setProgress((int)(currentScrollSens * 100));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scrollSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
            scrollSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        scrollSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float val = Math.max(1, progress) / 100f;
                scrollLabel.setText("🐢 Scroll Sensitivity: " + String.format("%.2fx", val) + " 🐇");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                float val = Math.max(1, seekBar.getProgress()) / 100f;
                prefs.edit().putFloat("scroll_sensitivity", val).apply();
            }
        });
        layout.addView(scrollSeekBar);

        // Light Theme Toggle
        android.widget.CheckBox themeCheck = new android.widget.CheckBox(this);
        themeCheck.setText("Enable Light Theme");
        themeCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        themeCheck.setChecked(prefs.getBoolean("is_light_theme", true));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            themeCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        themeCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("is_light_theme", isChecked).apply();
            recreate();
        });
        layout.addView(themeCheck);

        // Biometrics for Quick Access Toggle
        android.widget.CheckBox biometricsCheck = new android.widget.CheckBox(this);
        biometricsCheck.setText("Require Fingerprint for Quick Access");
        biometricsCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        biometricsCheck.setChecked(prefs.getBoolean("require_biometrics", true));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            biometricsCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        biometricsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("require_biometrics", isChecked).apply();
        });
        layout.addView(biometricsCheck);

        // Auto-Lock Toggle
        android.widget.CheckBox autoLockCheck = new android.widget.CheckBox(this);
        autoLockCheck.setText("Enable 3-Minute Auto-Lock");
        autoLockCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        autoLockCheck.setChecked(prefs.getBoolean("auto_lock_enabled", false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            autoLockCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        autoLockCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_lock_enabled", isChecked).apply();
        });
        layout.addView(autoLockCheck);

        // Custom Notification Toggle
        android.widget.CheckBox notifCheck = new android.widget.CheckBox(this);
        notifCheck.setText("Enable Custom Notification Toolbar");
        notifCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        notifCheck.setChecked(prefs.getBoolean("enable_custom_notif", false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notifCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        
        // Slot assignment rows for Notification Toolbar
        LinearLayout slotsConfigLayout = new LinearLayout(this);
        slotsConfigLayout.setOrientation(LinearLayout.VERTICAL);
        slotsConfigLayout.setPadding(dp(20), dp(4), 0, dp(12));
        slotsConfigLayout.setVisibility(notifCheck.isChecked() ? View.VISIBLE : View.GONE);

        TextView slotsLabel = new TextView(this);
        slotsLabel.setText("Notification Button Assignments:");
        slotsLabel.setTextColor(Color.parseColor(COLOR_TEXT_DIM));
        slotsLabel.setTextSize(12);
        slotsLabel.setPadding(0, dp(4), 0, dp(4));
        slotsConfigLayout.addView(slotsLabel);

        int totalSlots = Math.max(prefs.getInt(SLOT_COUNT_KEY, 2), secureStorage.getMaxSlotWithData());

        for (int i = 0; i < 5; i++) {
            final int notifBtnIndex = i;
            int defaultTarget = i < totalSlots ? i : -1;
            int mappedSlot = prefs.getInt("notif_btn_" + notifBtnIndex + "_slot", defaultTarget);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(2), 0, dp(2));

            TextView btnLabelText = new TextView(this);
            btnLabelText.setText("Btn " + (i + 1) + ": ");
            btnLabelText.setTextColor(Color.parseColor(COLOR_TEXT));
            btnLabelText.setTextSize(13);
            row.addView(btnLabelText);

            Button selectBtn = createStyledButton(getNotifBtnLabelText(mappedSlot), COLOR_SURFACE_ALT, COLOR_SURFACE);
            LinearLayout.LayoutParams btnP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
            selectBtn.setLayoutParams(btnP);
            selectBtn.setTextSize(12);

            selectBtn.setOnClickListener(v -> {
                String[] options = new String[totalSlots + 1];
                options[0] = "Disabled 🚫";
                for (int s = 0; s < totalSlots; s++) {
                    String slotName = prefs.getString("label_" + s, "Slot " + (s + 1));
                    options[s + 1] = "Slot " + (s + 1) + ": " + slotName;
                }

                int currentSelected = prefs.getInt("notif_btn_" + notifBtnIndex + "_slot", defaultTarget);
                int checkedItem = (currentSelected >= 0 && currentSelected < totalSlots) ? (currentSelected + 1) : 0;

                new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Assign Notification Button " + (notifBtnIndex + 1))
                    .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                        int selectedSlot = which == 0 ? -1 : (which - 1);
                        prefs.edit().putInt("notif_btn_" + notifBtnIndex + "_slot", selectedSlot).apply();
                        selectBtn.setText(getNotifBtnLabelText(selectedSlot));
                        dialog.dismiss();

                        HidKeyboardService svc = HidKeyboardService.getInstance();
                        if (svc != null) svc.updateNotification(svc.getConnectedDevice() != null ? "Connected to " + svc.getConnectedDevice().getName() : "Disconnected");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });

            row.addView(selectBtn);
            slotsConfigLayout.addView(row);
        }

        notifCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_custom_notif", isChecked).apply();
            slotsConfigLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            HidKeyboardService svc = HidKeyboardService.getInstance();
            if (svc != null) svc.updateNotification(svc.getConnectedDevice() != null ? "Connected to " + svc.getConnectedDevice().getName() : "Disconnected");
        });
        layout.addView(notifCheck);
        layout.addView(slotsConfigLayout);

        // Quick Settings Tile Toggle
        android.widget.CheckBox tileCheck = new android.widget.CheckBox(this);
        tileCheck.setText("Enable Quick Settings Menu Tile");
        tileCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        tileCheck.setChecked(prefs.getBoolean("enable_quick_tile", true));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tileCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        tileCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("enable_quick_tile", isChecked).apply();
            android.content.ComponentName component = new android.content.ComponentName(this, PassPressTileService.class);
            getPackageManager().setComponentEnabledSetting(
                component,
                isChecked ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            );
            if (isChecked) {
                Toast.makeText(this, "Tile Enabled. Add it from your notification drop-down.", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(tileCheck);

        // Floating Bubble Toggle
        android.widget.CheckBox bubbleCheck = new android.widget.CheckBox(this);
        bubbleCheck.setText("Enable Floating Bubble");
        bubbleCheck.setTextColor(Color.parseColor(COLOR_TEXT));
        bubbleCheck.setChecked(prefs.getBoolean("enable_bubble", false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubbleCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY)));
        }
        bubbleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                    bubbleCheck.setChecked(false);
                    Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, 201);
                } else {
                    prefs.edit().putBoolean("enable_bubble", true).apply();
                    startService(new Intent(this, FloatingBubbleService.class));
                }
            } else {
                prefs.edit().putBoolean("enable_bubble", false).apply();
                stopService(new Intent(this, FloatingBubbleService.class));
            }
        });
        layout.addView(bubbleCheck);

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
        layout.addView(spacer);

        // Export Button
        Button exportBtn = createStyledButton("📤 Export Backup", COLOR_SURFACE_ALT, COLOR_SURFACE);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        exportBtn.setLayoutParams(btnParams);
        exportBtn.setOnClickListener(v -> showExportDialog());
        layout.addView(exportBtn);

        // Spacer
        View spacerHelp = new View(this);
        spacerHelp.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        layout.addView(spacerHelp);

        // Help Button
        Button helpBtn = createStyledButton("ℹ️ Help & Troubleshooting", COLOR_PRIMARY, COLOR_PRIMARY_DARK);
        helpBtn.setLayoutParams(btnParams);
        helpBtn.setOnClickListener(v -> showHelpDialog());
        layout.addView(helpBtn);

        // Spacer 2
        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(1, dp(10)));
        layout.addView(spacer2);

        // Import Button
        Button importBtn = createStyledButton("📥 Import Backup", COLOR_SURFACE_ALT, COLOR_SURFACE);
        importBtn.setLayoutParams(btnParams);
        importBtn.setOnClickListener(v -> showImportDialog());
        layout.addView(importBtn);

        // Spacer 3
        View spacer3 = new View(this);
        spacer3.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
        layout.addView(spacer3);


        // Reset Button
        Button resetBtn = createStyledButton("🔄 Reset to Defaults", COLOR_WARNING, "#D97706");
        resetBtn.setLayoutParams(btnParams);
        resetBtn.setTextColor(Color.parseColor(COLOR_TEXT));
        resetBtn.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset to Defaults?")
                .setMessage("This will reset your theme, notification, and widget settings. Your passwords and paired devices will NOT be deleted.")
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.edit()
                        .putBoolean("is_light_theme", true)
                        .putBoolean("require_biometrics", true)
                        .putBoolean("auto_lock_enabled", false)
                        .putBoolean("enable_custom_notif", false)
                        .putBoolean("enable_quick_tile", true)
                        .putBoolean("enable_bubble", false)
                        .putFloat("mouse_sensitivity", 1.5f)
                        .putInt("typing_delay", 25)
                        .putFloat("scroll_sensitivity", 0.05f)
                        .apply();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        layout.addView(resetBtn);
        
        // Spacer 4
        View spacer4 = new View(this);
        spacer4.setLayoutParams(new LinearLayout.LayoutParams(1, dp(20)));
        layout.addView(spacer4);

        // Shut Down & Exit Button
        Button shutDownBtn = createStyledButton("🛑 Shut Down & Exit App", COLOR_WARNING, "#D97706");
        shutDownBtn.setLayoutParams(btnParams);
        shutDownBtn.setTextColor(Color.parseColor(COLOR_TEXT));
        shutDownBtn.setOnClickListener(v -> shutDownApp());
        layout.addView(shutDownBtn);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(layout);
        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private String getNotifBtnLabelText(int slotIndex) {
        if (slotIndex < 0) return "Disabled 🚫";
        String label = prefs.getString("label_" + slotIndex, "Slot " + (slotIndex + 1));
        return "Slot " + (slotIndex + 1) + " (" + label + ")";
    }
    
    private void showExportDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Master Password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        
        new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        
        new android.app.AlertDialog.Builder(this, prefs.getBoolean("is_light_theme", true) ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        if (requestCode == 201) { // Overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                prefs.edit().putBoolean("enable_bubble", true).apply();
                startService(new Intent(this, FloatingBubbleService.class));
            } else {
                Toast.makeText(this, "Permission denied. Bubble disabled.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
