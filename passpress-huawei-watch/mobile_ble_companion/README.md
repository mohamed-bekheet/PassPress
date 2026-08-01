# PassPress Watch Companion Application (`PassPress Watch Test`)

Welcome to the **PassPress Watch Companion Application**. This is the isolated Android companion app specifically designed for testing and integrating with the **Huawei Smartwatch App** (`com.passpress.watchtest`).

---

## Component Overview

### Android BLE Virtual Keyboard (`android/passpress_ble_keyboard`)
- **App Name**: `PassPress Watch Test`
- **Application ID**: `com.passpress.watchtest`
- **Output APK**: `PassPress_WATCH.apk`
- **Version**: `1.0.1-dev`
- **Key Features**:
  - **Isolated Test App**: Installed side-by-side with original PassPress BLE app.
  - **Custom Watch Icon**: Modern flat dark vector icon with keyboard and security lock visuals.
  - **BLE Keyboard Emulation**: Wirelessly type saved passwords into Target PCs (`💻 Connect PC`).
  - **Biometric Security & Encryption**: Encrypted storage using AES-256 GCM + Android Biometric Auth.
  - **Clean Process Shutdown**: Dedicated `🛑 Shut Down & Exit App` option in Settings (`⚙️`) to disconnect BLE and terminate background services.

### 2. Android Controller App (`android/Kontroller`)
Auxiliary mobile UI layout and controller module for hardware interactions.

### 3. Legacy Web & Python Prototypes (`legacy/`)
- Web Bluetooth HTML/JS interface (`index.html`, `app.js`) for browser-based BLE debugging.
- Python scripts (`receiver.py`, `ble_keyboard_receiver.py`) for host-side testing of BLE packets on Windows/Linux.

---

## Setup & Build Instructions

Detailed prerequisites and build commands can be found in [ANDROID_SETUP.md](file:///e:/Personal/Pass_Press/passpress_mobile_ble/ANDROID_SETUP.md).

### Prerequisites
1. **JDK 11** installed (e.g. Eclipse Temurin JDK 11 at `C:\Program Files\OpenJDK\jdk-11`).
2. **Android SDK Command-Line Tools & Platform-Tools** (`android-33`, `build-tools;33.0.0`).

### Building the Android App (PowerShell)
```powershell
cd e:\Personal\Pass_Press\passpress_mobile_ble\android\passpress_ble_keyboard
$env:ANDROID_HOME = "C:\Android"
$env:JAVA_HOME = "C:\Program Files\OpenJDK\jdk-11"

# Build debug APK
.\gradlew.bat assembleDebug
```
The resulting APK file will be at:
`app\build\outputs\apk\debug\app-debug.apk`

### Installing on Device via ADB
```powershell
adb devices
adb install app\build\outputs\apk\debug\app-debug.apk
```
