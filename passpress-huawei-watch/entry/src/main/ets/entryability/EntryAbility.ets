import UIAbility from '@ohos.app.ability.UIAbility';
import window from '@ohos.window';
import hilog from '@ohos.hilog';

export default class EntryAbility extends UIAbility {
  onCreate(want, launchParam) {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onCreate');
  }

  onDestroy() {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage) {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onWindowStageCreate');

    windowStage.loadContent('pages/Index', (err, data) => {
      if (err.code) {
        hilog.error(0x0000, 'PassPressWatch', 'Failed to load content. Cause: %{public}s', JSON.stringify(err) ?? '');
        return;
      }
      hilog.info(0x0000, 'PassPressWatch', 'Succeeded loading content.');
    });
  }

  onWindowStageDestroy() {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground() {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onForeground');
  }

  onBackground() {
    hilog.info(0x0000, 'PassPressWatch', '%{public}s', 'Ability onBackground');
  }
}
