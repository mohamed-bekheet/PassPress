# Android SDK Setup for PassPress BLE Keyboard

## What to Download

You need **two things** to build the Android app:

### 1. Java Development Kit (JDK)
- **Download:** [Eclipse Temurin JDK 11](https://adoptium.net/temurin/releases/?version=11)
  - Choose: Windows x64
  - Install to: `C:\Program Files\OpenJDK\jdk-11`

### 2. Android SDK Command-Line Tools
- **Download:** [Android SDK Platform-Tools](https://developer.android.com/studio/releases/platform-tools)
  - Choose: Windows
  - Extract to: `C:\Android\platform-tools`

### 3. Android SDK Build Tools and Platforms
After installing JDK, run these commands:

```powershell
# Set ANDROID_HOME
$env:ANDROID_HOME = "C:\Android"
$env:JAVA_HOME = "C:\Program Files\OpenJDK\jdk-11"

# Add to PATH
$env:Path += ";C:\Android\cmdline-tools\latest\bin;C:\Android\platform-tools"

# Install required components
sdkmanager --sdk_root=C:\Android "platforms;android-33"
sdkmanager --sdk_root=C:\Android "build-tools;33.0.0"
sdkmanager --sdk_root=C:\Android "platform-tools"
```

## Build the App

```powershell
cd e:\Personal\Pass_Press\mobile_app_ble\android\passpress_ble_keyboard
$env:ANDROID_HOME = "C:\Android"
$env:JAVA_HOME = "C:\Program Files\OpenJDK\jdk-11"

# Build APK
.\gradlew.bat build

# Or build directly for APK
.\gradlew.bat assembleDebug
```

The APK will be at:
```
app\build\outputs\apk\debug\app-debug.apk
```

## Install on Phone

```powershell
# Connect phone via USB
adb devices  # Verify phone is listed

# Install APK
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

**Quick Summary:**
1. Download JDK 11 → Install to C:\Program Files\OpenJDK\jdk-11
2. Download Android SDK Platform-Tools → Extract to C:\Android\platform-tools
3. Run gradlew.bat build
4. Connect phone, run adb install

Questions before you start downloading?
