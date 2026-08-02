import asyncio
import sys
import re
import pyautogui
from bleak import BleakScanner, BleakClient

SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
TX_CHAR_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

class PasswordReceiver:
    def __init__(self):
        self.client = None
        self.device = None
        self.connected = False

    async def find_device(self):
        print("Scanning for BLE devices...")
        devices = await BleakScanner.discover(timeout=5.0)
        for d in devices:
            if d.name and "Pass