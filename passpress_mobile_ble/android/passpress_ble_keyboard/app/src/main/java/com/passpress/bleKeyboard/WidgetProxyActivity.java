package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class WidgetProxyActivity extends AppCompatActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        int slotIndex = intent.getIntExtra("SLOT_INDEX", intent.getIntExtra("slot_index", -1));
        boolean isMacro = intent.getBooleanExtra("IS_MACRO", intent.getBooleanExtra("is_macro", false));
        int macroIndex = intent.getIntExtra("MACRO_INDEX", intent.getIntExtra("macro_index", -1));

        if (slotIndex == -1 && macroIndex == -1) {
            finish();
            return;
        }

        boolean requireBiometrics = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE).getBoolean("require_biometrics", true);

        if (!requireBiometrics || isMacro) {
            sendPasswordAndFinish(slotIndex, isMacro, macroIndex);
            return;
        }

        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    Toast.makeText(getApplicationContext(), "Authentication error", Toast.LENGTH_SHORT).show();
                    finish();
                }

                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    sendPasswordAndFinish(slotIndex, isMacro, macroIndex);
                }

                @Override
                public void onAuthenticationFailed() {
                    Toast.makeText(getApplicationContext(), "Authentication failed", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("PassPress")
                    .setSubtitle("Authenticate to send password")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            biometricPrompt.authenticate(promptInfo);
        } else {
            sendPasswordAndFinish(slotIndex, isMacro, macroIndex);
        }
    }

    private void sendPasswordAndFinish(int slotIndex, boolean isMacro, int macroIndex) {
        SharedPreferences prefs = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        HidKeyboardService svc = HidKeyboardService.getInstance();

        if (svc != null && svc.getConnectedDevice() != null) {
            if (isMacro && macroIndex >= 0) {
                byte modifiers = (byte) prefs.getInt("macro_mod_" + macroIndex, 0);
                byte keycode = (byte) prefs.getInt("macro_key_" + macroIndex, 0);
                svc.sendQueue.add("[MACRO]:" + modifiers + "," + keycode);
                Toast.makeText(this, "Shortcut sent!", Toast.LENGTH_SHORT).show();
            } else {
                SecureStorage secureStorage = SecureStorage.getInstance(this);
                String pass = secureStorage.getPassword(slotIndex);
                String suffix = prefs.getString("suffix_" + slotIndex, "Enter");
                
                if ((pass == null || pass.isEmpty()) && "None".equals(suffix)) {
                    Toast.makeText(this, "Slot is empty", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                String toSend = pass != null ? pass : "";
                if ("Enter".equals(suffix)) toSend += "\n";
                else if ("Tab".equals(suffix)) toSend += "\t";
                
                svc.sendKeySequence(toSend);
                Toast.makeText(this, "Password sent!", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Keyboard not connected!", Toast.LENGTH_SHORT).show();
            
            if (svc == null) {
                Intent serviceIntent = new Intent(this, HidKeyboardService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Starting keyboard service... tap again later", Toast.LENGTH_LONG).show();
            } else {
                svc.autoConnect();
                String pcName = prefs.getString("last_connected_device_name", "PC");
                Toast.makeText(this, "Connecting to " + pcName + "... tap again later", Toast.LENGTH_LONG).show();
            }
        }
        
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 300);
    }
}
