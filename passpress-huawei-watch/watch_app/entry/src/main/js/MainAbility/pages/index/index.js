export default {
    data: {
        slots: [
            { name: "Pass 1", command: "SLOT1" },
            { name: "Pass 2", command: "SLOT2" },
            { name: "Pass 3", command: "SLOT3" },
            { name: "Pass 4", command: "SLOT4" },
            { name: "Pass 5", command: "SLOT5" },
        ]
    },
    onInit() {
    },
    sendBleCommand(command) {
        console.info("PassPress: Sending BLE Command: " + command);
        // We will implement the actual BLE dispatching later
    }
}
