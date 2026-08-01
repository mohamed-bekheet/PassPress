import asyncio
import sys
import time
import pyautogui
from bleak import BleakScanner, BleakClient

SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
TX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

class Receiver:
    def __init__(self):
        self.client = None
        self.buffer = ""

    async def scan_and_connect(self):
        print("Scanning for BLE devices...")
        devices = await BleakScanner.discover(timeout=5.0)
        for device in devices:
            if device.name and "Pass" in device.name:
                print(f"Found device: {device.name} ({device.address})")
                self.client = BleakClient(device)
                await self.client.connect()
                print("Connected")
                await self.client.start_notify(RX_CHAR_UUID, self.notification_handler)
                print("Listening for data...")
                await asyncio.Event().wait()

    def notification_handler(self, sender, data):
        text = data.decode("utf-8", errors="ignore").strip()
        if not text:
            return
        print(f"Received: {text}")
        pyautogui.write(text, interval=0.01)
        pyautogui.press("enter")


if __name__ == "__main__":
    receiver = Receiver()
    try:
        asyncio.run(receiver.scan_and_connect())
    except KeyboardInterrupt:
        print("Stopped")
