# PassPress Hardware (`passpress-hardware`)

Welcome to the **PassPress Hardware** design repository. This directory contains the complete electronic engineering files for the PassPress hardware device, including KiCad schematics, PCB layout files, component libraries, datasheet references, and Bill of Materials (BOM).

---

## Directory Structure

```
passpress-hardware/
├── PassPress_kicad/           # Main base PCB schematic and board layout
├── PassPress_kicad_STM/       # PCB design variant for STM32 microcontroller
├── PassPress_kicad_CH32/      # PCB design variant for CH32 microcontroller
├── PassPress_kicad_CH32_BLE/  # PCB design variant for CH32 BLE microcontroller
├── PassPress_kicad_Combined/  # Combined hardware schematic & PCB layout
├── PassPress_kicad_V2/        # Hardware Revision 2 schematic and layout
├── datasheets/                # Technical IC datasheets & component specifications
├── STM_DOC/                   # STM microcontroller documentation & reference manuals
├── libs_ripos/                # KiCad custom symbol & footprint libraries
├── Components.xlsx            # Master Bill of Materials (BOM) & component list
└── README.md                  # This documentation file
```

---

## Hardware Modules & Schematic Hierarchy

Each KiCad project directory contains structured schematic sheets for specific hardware functional blocks:

- `mcu.kicad_sch` — Main Microcontroller circuit (STM32 / CH32) & pin assignments.
- `power.kicad_sch` — Power management, voltage regulators, and battery/LDO circuit.
- `usb.kicad_sch` — USB Type-C interface, ESD protection, and data line conditioning.
- `antenna.kicad_sch` — Bluetooth LE antenna matching network & RF frontend.
- `INTERFACE.kicad_sch` — User input buttons, touch sensor pads, and display connectors.

---

## Microcontroller Variants

1. **STM32 Variant (`PassPress_kicad_STM`)**:
   - Target MCU: STM32F042 ARM Cortex-M0.
   - Built-in USB 2.0 Full-Speed Crystal-less controller & capacitive touch sensing logic.

2. **CH32 / CH32-BLE Variant (`PassPress_kicad_CH32`, `PassPress_kicad_CH32_BLE`)**:
   - Target MCU: WCH CH32 series microcontroller with integrated BLE transceiver.

---

## Inventory & Component Data

- **Master BOM**: Refer to [Components.xlsx](file:///e:/Personal/Pass_Press/passpress-hardware/Components.xlsx) for part numbers, manufacturer details, and package sizes (e.g. 0603, QFN, QFP).
- **Datasheets**: Refer to the [datasheets/](file:///e:/Personal/Pass_Press/passpress-hardware/datasheets/) directory for hardware datasheets.
