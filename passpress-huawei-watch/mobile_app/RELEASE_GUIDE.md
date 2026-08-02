# 📦 PassPress — Standard Release & Production Guide

This guide walks you through finishing the standard Android production release process to publish **PassPress** on the Google Play Store.

---

## 🏷️ Versioning Strategy

PassPress follows Semantic Versioning (`MAJOR.MINOR.PATCH`):
- **`versionName`**: `"1.0.0"` (Shown in app under Help `ℹ️` dialog)
- **`versionCode`**: `1` (Integer incremented by 1 on every Play Store update)

### How to update version for future releases:
Edit `android/passpress_ble_keyboard/app/build.gradle`:
```groovy
defaultConfig {
    applicationId "com.passpress.bleKeyboard"
    minSdk 28
    targetSdk 34
    versionCode 2        // Increment for next update (e.g., 2, 3, 4)
    versionName "1.1.0"  // User-facing version tag
}
```

---

## 🔑 Step 1: Create a Production Release Keystore

Run the following command in your terminal to create a secure keystore for Google Play:

```bash
keytool -genkey -v -keystore passpress-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias passpress-key
```
> ⚠️ **IMPORTANT:** Keep `passpress-release-key.jks` and your password stored safely. Back it up! You cannot update your app on Google Play without this key.

---

## 🏗️ Step 2: Build the Signed Android App Bundle (.aab)

In **Android Studio**:
1. Go to **Build > Generate Signed Bundle / APK...**
2. Choose **Android App Bundle** and click **Next**.
3. Select your `passpress-release-key.jks` keystore file, key alias, and password.
4. Select **release** build type and click **Create**.

Your release bundle will be generated at:
`android/passpress_ble_keyboard/app/release/app-release.aab`

---

## 🌐 Step 3: Google Play Console Submission

1. Log into your [Google Play Console](https://play.google.com/console).
2. Click **Create app** and set details:
   - **App Name:** `PassPress — Bluetooth Hardware Password Manager`
   - **Default Language:** English
   - **App or Game:** App
   - **Free or Paid:** Free
3. Go to **Production > Create new release**.
4. Upload `app-release.aab`.
5. Fill in store listing using content from `STORE_MARKETING_KIT.md`.
6. Complete **App Content declarations**:
   - **Data Safety:** Select *No data collected or shared*.
   - **Permissions:** Confirm Bluetooth HID keyboard usage.
7. Submit for review! 🚀
