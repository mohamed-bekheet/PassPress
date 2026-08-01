import { WearEngineClient, PasswordSlot } from '../services/WearEngineClient';

@Entry
@Component
struct Index {
  @State isPhoneConnected: boolean = true;
  @State statusText: string = "Phone Companion (Option 3)";
  @State feedbackMessage: string = "";
  @State activeSlotIndex: number = -1;
  @State slots: PasswordSlot[] = [];

  private client: WearEngineClient = WearEngineClient.getInstance();

  aboutToAppear() {
    this.slots = this.client.getSlots();
    this.client.initConnection((connected: boolean, mode: string) => {
      this.isPhoneConnected = connected;
      this.statusText = mode;
    });
  }

  build() {
    Column() {
      // Header Section
      Row() {
        Text("🔑 PassPress")
          .fontSize(16)
          .fontWeight(FontWeight.Bold)
          .fontColor('#F1F5F9')
        
        Blank()

        // Mode Status Badge
        Row() {
          Circle({ width: 8, height: 8 })
            .fill(this.isPhoneConnected ? '#34D399' : '#F59E0B')
            .margin({ right: 4 })
          
          Text(this.isPhoneConnected ? "PHONE" : "STANDALONE")
            .fontSize(10)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.isPhoneConnected ? '#34D399' : '#F59E0B')
        }
        .backgroundColor('#1E293B')
        .padding({ left: 8, right: 8, top: 4, bottom: 4 })
        .borderRadius(12)
        .onClick(() => {
          // Toggle Mode for Option 3 vs Option 1 testing
          this.isPhoneConnected = !this.isPhoneConnected;
          this.client.setPhoneConnected(this.isPhoneConnected);
          this.statusText = this.isPhoneConnected ? "Phone Companion (Option 3)" : "Standalone BLE GATT (Option 1)";
        })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 10, bottom: 6 })

      // Mode Description Subtitle
      Text(this.statusText)
        .fontSize(11)
        .fontColor('#94A3B8')
        .margin({ bottom: 8 })

      // Feedback Toast Box
      if (this.feedbackMessage !== "") {
        Text(this.feedbackMessage)
          .fontSize(12)
          .fontWeight(FontWeight.Medium)
          .fontColor('#22D3EE')
          .backgroundColor('#0F2942')
          .padding({ left: 10, right: 10, top: 4, bottom: 4 })
          .borderRadius(8)
          .margin({ bottom: 6 })
      }

      // Credential Slots List
      List({ space: 8 }) {
        ForEach(this.slots, (slot: PasswordSlot) => {
          ListItem() {
            Row() {
              Column() {
                Text(slot.label)
                  .fontSize(14)
                  .fontWeight(FontWeight.SemiBold)
                  .fontColor('#F1F5F9')
                Text("Tap to type via BLE")
                  .fontSize(10)
                  .fontColor('#64748B')
                  .margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)

              Blank()

              Button("TYPE")
                .type(ButtonType.Normal)
                .backgroundColor(this.activeSlotIndex === slot.index ? '#4F46E5' : '#6366F1')
                .fontColor('#FFFFFF')
                .fontSize(12)
                .fontWeight(FontWeight.Bold)
                .borderRadius(16)
                .height(32)
                .width(64)
                .onClick(() => {
                  this.activeSlotIndex = slot.index;
                  this.feedbackMessage = "Sending...";
                  
                  this.client.sendTypePasswordCommand(slot.index, (success: boolean, msg: string) => {
                    this.feedbackMessage = msg;
                    setTimeout(() => {
                      this.activeSlotIndex = -1;
                    }, 1000);
                  });
                })
            }
            .width('100%')
            .backgroundColor('#1E293B')
            .borderRadius(16)
            .padding({ left: 14, right: 10, top: 10, bottom: 10 })
          }
        }, (slot: PasswordSlot) => slot.index.toString())
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: 10, right: 10 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#0F172A')
  }
}
