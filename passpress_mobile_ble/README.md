# PassPress Mobile Application (`passpress_mobile_ble`)

Welcome to the **PassPress Mobile Application** component. This directory contains Android native applications and companion software for connecting to and configuring the **PassPress** hardware device over Bluetooth Low Energy (BLE).

---

## Directory Structure

```
passpress_mobile_ble/
├── android/
│   ├── passpress_ble_keyboard/    # Main Native Android Virtual BLE Keyboard App
│   └── Kontroller/                # Controller application prototype
├── legacy/                        # Web Bluetooth & Python BLE receiver scripts
│   ├── app.js                     # Web Bluetooth interface script
│   ├── index.html                 # Web UI prototype
│   ├── receiver.py                # Python BLE listener script
│   └── ble_keyboard_receiver.py   # Python BLE keyboard packet receiver
├── ANDROID_SETUP.md               # Detailed Android SDK & JDK build environment guide
└── README.md                      # This documentation file
```

---

## Component Overview

### 1. Android BLE Virtual Keyboard (`android/passpress_ble_keyboard`)
The primary Android companion app allowing your smartphone to communicate with the target PC over Bluetooth LE.
- **Key Features**:
  - **BLE Keyboard Emulation**: Wirelessly type saved passwords into Target PCs (`💻 Connect PC`).
  - **Biometric Security & Encryption**: Encrypted storage using AES-256 GCM + Android Biometric Auth.
  - **Per-Slot Device Trusting**: Secure verification for untrusted PCs.
  - **Encrypted Backup & Restore**: Master-password encrypted export/import.
  - **Clean Process Shutdown**: Dedicated `🛑 Shut Down & Exit App` option in Settings (`⚙️`) to disconnect BLE and terminate background services.
  - **Home Screen Widgets**: Quick-send password widgets and quick-settings tiles.

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
