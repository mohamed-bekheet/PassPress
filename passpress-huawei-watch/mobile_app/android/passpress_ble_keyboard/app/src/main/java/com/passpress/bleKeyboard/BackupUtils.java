package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class BackupUtils {

    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;

    public static String exportData(Context context, String password) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        SecureStorage secureStorage = SecureStorage.getInstance(context);
        int slotCount = prefs.getInt("slot_count", 2);

        JSONObject root = new JSONObject();
        root.put("slot_count", slotCount);

        JSONArray slotsArray = new JSONArray();
        for (int i = 0; i < slotCount; i++) {
            JSONObject slot = new JSONObject();
            slot.put("label", prefs.getString("label_" + i, "Slot " + (i + 1)));
            slot.put("password", secureStorage.getPassword(i));
            slot.put("suffix", prefs.getString("suffix_" + i, "Enter"));

            JSONArray trustedArray = new JSONArray();
            Set<String> trustedDevices = secureStorage.getTrustedDevices(i);
            for (String device : trustedDevices) {
                trustedArray.put(device);
            }
            slot.put("trusted_devices", trustedArray);
            slotsArray.put(slot);
        }
        root.put("slots", slotsArray);

        String jsonPayload = root.toString();
        return encrypt(jsonPayload, password);
    }

    public static void importData(Context context, String encryptedData, String password) throws Exception {
        String jsonPayload = decrypt(encryptedData, password);
        JSONObject root = new JSONObject(jsonPayload);
        
        int slotCount = root.getInt("slot_count");
        SharedPreferences prefs = context.getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
        SecureStorage secureStorage = SecureStorage.getInstance(context);
        
        prefs.edit().putInt("slot_count", slotCount).apply();
        JSONArray slotsArray = root.getJSONArray("slots");
        
        for (int i = 0; i < slotCount; i++) {
            JSONObject slot = slotsArray.getJSONObject(i);
            prefs.edit().putString("label_" + i, slot.getString("label")).apply();
            secureStorage.savePassword(i, slot.getString("password"));
            prefs.edit().putString("suffix_" + i, slot.getString("suffix")).apply();
            
            secureStorage.clearTrustedDevices(i);
            JSONArray trustedArray = slot.getJSONArray("trusted_devices");
            for (int j = 0; j < trustedArray.length(); j++) {
                String device = trustedArray.getString(j);
                String[] parts = device.split("\\|");
                if (parts.length >= 2) {
                    secureStorage.addTrustedDevice(i, parts[0], parts[1]);
                } else {
                    secureStorage.addTrustedDevice(i, parts[0], "Unknown Device");
                }
            }
        }
    }

    private static String encrypt(String plaintext, String password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret, gcmSpec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // Combine salt + iv + ciphertext
        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);

        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String decrypt(String encrypted, String password) throws Exception {
        byte[] combined = Base64.decode(encrypted, Base64.NO_WRAP);
        if (combined.length < SALT_LENGTH + IV_LENGTH) {
            throw new IllegalArgumentException("Invalid backup file");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - SALT_LENGTH - IV_LENGTH];

        System.arraycopy(combined, 0, salt, 0, salt.length);
        System.arraycopy(combined, salt.length, iv, 0, iv.length);
        System.arraycopy(combined, salt.length + iv.length, ciphertext, 0, ciphertext.length);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret, gcmSpec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, "UTF-8");
    }
}
