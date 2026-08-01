# PassPress (Hardware Security & BLE Keyboard Project)

**PassPress** is an open hardware and software project featuring a compact hardware password manager and custom Bluetooth Low Energy (BLE) / USB HID keyboard device.

---

## Repository Structure (Monorepo)

This repository is organized as a unified monorepo containing all sub-systems of the project:

```
Pass_Press/
├── passpress-hardware/     # PCB schematics (KiCad), BOM Excel sheet, datasheets, libraries
├── passpress-firmware/     # Embedded C/C++ firmware (STM32F042), USB HID, Bootloader, GUI
├── passpress_mobile_ble/   # Android BLE virtual keyboard app & companion software
├── passpress-huawei-watch/ # Huawei wearable watch companion application
└── README.md               # Root project index (this file)
```

---

## Component Quick Links

1. 🛠️ **[Hardware (`passpress-hardware`)](file:///e:/Personal/Pass_Press/passpress-hardware/README.md)**:
   - KiCad schematics and PCB designs for STM32 and CH32 MCU variants (`kicad-stm`, `kicad-ch32`, `kicad-v2`).
   - Bill of Materials ([Components.xlsx](file:///e:/Personal/Pass_Press/passpress-hardware/Components.xlsx)).
   - IC Datasheets ([datasheets/](file:///e:/Personal/Pass_Press/passpress-hardware/datasheets/)).

2. ⚡ **[Embedded Firmware (`passpress-firmware`)](file:///e:/Personal/Pass_Press/passpress-firmware/README.md)**:
   - STM32F042 C/C++ firmware workspace (`STM_WS`).
   - USB HID driver, Bootloader, Touch sensing (`TOUCHSENSING`), and GUI display engine.

3. 📱 **[Mobile Application (`passpress_mobile_ble`)](file:///e:/Personal/Pass_Press/passpress_mobile_ble/README.md)**:
   - Native Android Virtual BLE Keyboard app (`android/passpress_ble_keyboard`).
   - Setup guide ([ANDROID_SETUP.md](file:///e:/Personal/Pass_Press/passpress_mobile_ble/ANDROID_SETUP.md)).
   - Legacy Web Bluetooth and Python testing tools (`legacy/`).

4. ⌚ **[Huawei Watch App (`passpress-huawei-watch`)](file:///e:/Personal/Pass_Press/passpress-huawei-watch/README.md)**:
   - Huawei smartwatch companion app for HarmonyOS / LiteOS / Huawei Wear Engine.

---

## Project Architecture Summary

```
+------------------------+      BLE / USB      +-------------------------+
|  passpress_mobile_ble  |  ---------------->  |   passpress-hardware    |
|  (Android Companion)   |                     |   (PCB, MCU, Touch, USB)  |
+------------------------+                     +-------------------------+
                                                            |
                                                            v
                                               +-------------------------+
                                               |   passpress-firmware    |
                                               |  (C/C++, USB HID, GUI)  |
                                               +-------------------------+
```
