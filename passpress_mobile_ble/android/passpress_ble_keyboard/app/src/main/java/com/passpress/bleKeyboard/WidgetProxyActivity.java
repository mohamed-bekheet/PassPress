package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

public class WidgetProxyActivity extends AppCompatActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // No layout, transparent background in manifest
        
        int slotIndex = getIntent().getIntExtra("slot_index", -1);
        if (slotIndex == -1) {
            finish();
            return;
        }

        boolean requireBiometrics = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE).getBoolean("require_biometrics", true);

        if (!requireBiometrics) {
            sendPasswordAndFinish(slotIndex);
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
                    sendPasswordAndFinish(slotIndex);
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
            // No biometric available, just send
            sendPasswordAndFinish(slotIndex);
        }
    }

    private void sendPasswordAndFinish(int slotIndex) {
        SecureStorage secureStorage = SecureStorage.getInstance(this);
        String pass = secureStorage.getPassword(slotIndex);
        String suffix = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE).getString("suffix_" + slotIndex, "Enter");
        
        if ((pass == null || pass.isEmpty()) && "None".equals(suffix)) {
            Toast.makeText(this, "Slot is empty", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null && svc.getConnectedDevice() != null) {
            String toSend = pass != null ? pass : "";
            if ("Enter".equals(suffix)) toSend += "\n";
            else if ("Tab".equals(suffix)) toSend += "\t";
            
            svc.sendKeySequence(toSend);
            Toast.makeText(this, "Password sent!", Toast.LENGTH_SHORT).show();
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
                android.content.SharedPreferences prefs = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
                String pcName = prefs.getString("last_connected_device_name", "PC");
                Toast.makeText(this, "Connecting to " + pcName + "... tap again later", Toast.LENGTH_LONG).show();
            }
        }
        
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 300);
    }
}
