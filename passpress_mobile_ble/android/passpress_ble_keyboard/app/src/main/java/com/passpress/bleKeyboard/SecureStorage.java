package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.HashSet;
import java.util.Set;

public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String PREFS_FILENAME = "passpress_secure_prefs";

    private SharedPreferences securePrefs;
    private static SecureStorage instance;

    private SecureStorage(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences", e);
            // Fallback to normal prefs if encryption fails (e.g. keystore issue)
            securePrefs = context.getSharedPreferences(PREFS_FILENAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public static synchronized SecureStorage getInstance(Context context) {
        if (instance == null) {
            instance = new SecureStorage(context.getApplicationContext());
        }
        return instance;
    }

    public void savePassword(int slotId, String password) {
        securePrefs.edit().putString("password_" + slotId, password).apply();
    }

    public String getPassword(int slotId) {
        return securePrefs.getString("password_" + slotId, "");
    }

    public void removePassword(int slotId) {
        securePrefs.edit().remove("password_" + slotId).apply();
    }
    
    // --- Trusted Devices per slot ---
    
    public Set<String> getTrustedDevices(int slotId) {
        return securePrefs.getStringSet("trusted_" + slotId, new HashSet<>());
    }
    
    public void addTrustedDevice(int slotId, String macAddress, String deviceName) {
        Set<String> devices = new HashSet<>(getTrustedDevices(slotId));
        // Remove old entry for this MAC if it exists
        devices.removeIf(d -> d.startsWith(macAddress + "|") || d.equals(macAddress));
        
        String safeName = deviceName != null ? deviceName.replace("|", "") : "Unknown Device";
        devices.add(macAddress + "|" + safeName);
        securePrefs.edit().putStringSet("trusted_" + slotId, devices).apply();
    }
    
    public void removeTrustedDevice(int slotId, String macAddress) {
        Set<String> devices = new HashSet<>(getTrustedDevices(slotId));
        devices.removeIf(d -> d.startsWith(macAddress + "|") || d.equals(macAddress));
        securePrefs.edit().putStringSet("trusted_" + slotId, devices).apply();
    }
    
    public void clearTrustedDevices(int slotId) {
        securePrefs.edit().remove("trusted_" + slotId).apply();
    }
    
    public boolean isDeviceTrusted(int slotId, String macAddress) {
        if (macAddress == null) return false;
        Set<String> devices = getTrustedDevices(slotId);
        for (String d : devices) {
            if (d.startsWith(macAddress + "|") || d.equals(macAddress)) return true;
        }
        return false;
    }
    
    public void migrateOldPassword(Context context, int slotId) {
        SharedPreferences oldPrefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        String oldPass = oldPrefs.getString("password_" + slotId, null);
        if (oldPass != null && !oldPass.isEmpty()) {
            // Move to secure storage
            savePassword(slotId, oldPass);
            // Delete from plain text storage
            oldPrefs.edit().remove("password_" + slotId).apply();
            Log.d(TAG, "Migrated password for slot " + slotId + " to secure storage");
        }
    }

    public int getMaxSlotWithData() {
        int max = 2;
        for (int i = 0; i < 50; i++) {
            String p = getPassword(i);
            if (p != null && !p.isEmpty()) {
                if (i + 1 > max) max = i + 1;
            }
        }
        return max;
    }
}
