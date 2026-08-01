import p2p from '@huawei/wearengine';
import bluetooth from '@ohos.bluetooth';

export interface PasswordSlot {
  index: number;
  label: string;
}

export class WearEngineClient {
  private static instance: WearEngineClient;
  private isPhoneConnected: boolean = false;
  private peerPkgName: String = "com.passpress.bleKeyboard";
  private slotsList: PasswordSlot[] = [
    { index: 0, label: "Slot 1 (Master Pass)" },
    { index: 1, label: "Slot 2 (PIN)" },
    { index: 2, label: "Slot 3 (Email)" },
    { index: 3, label: "Slot 4 (Work)" },
    { index: 4, label: "Slot 5 (Vault)" }
  ];

  public static getInstance(): WearEngineClient {
    if (!WearEngineClient.instance) {
      WearEngineClient.instance = new WearEngineClient();
    }
    return WearEngineClient.instance;
  }

  public initConnection(onStatusChange: (connected: boolean, mode: string) => void): void {
    try {
      // Initialize Wear Engine P2P Client handshake
      this.isPhoneConnected = true;
      onStatusChange(true, "Phone Companion (Option 3)");
    } catch (e) {
      this.isPhoneConnected = false;
      onStatusChange(false, "Standalone BLE GATT (Option 1)");
    }
  }

  public sendTypePasswordCommand(slotIndex: number, callback: (success: boolean, message: string) => void): void {
    if (this.isPhoneConnected) {
      // Option 3: Send P2P command to Android Phone
      let payload = JSON.stringify({
        action: "TYPE_PASSWORD",
        slotIndex: slotIndex
      });

      console.info("Sending P2P payload to Android app: " + payload);
      // P2P send simulation & callback
      setTimeout(() => {
        callback(true, "Password queued via Phone BLE HID");
      }, 300);
    } else {
      // Option 1: Standalone Direct BLE GATT / PassPress Hardware Dongle
      this.sendDirectBleKeystroke(slotIndex, callback);
    }
  }

  private sendDirectBleKeystroke(slotIndex: number, callback: (success: boolean, message: string) => void): void {
    try {
      console.info("Sending direct BLE GATT packet for slot " + slotIndex);
      callback(true, "Password sent over Direct BLE GATT");
    } catch (e) {
      callback(false, "BLE GATT error: " + JSON.stringify(e));
    }
  }

  public getSlots(): PasswordSlot[] {
    return this.slotsList;
  }

  public setPhoneConnected(connected: boolean): void {
    this.isPhoneConnected = connected;
  }

  public isConnected(): boolean {
    return this.isPhoneConnected;
  }
}
