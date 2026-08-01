/**
  ******************************************************************************
  * @file           : touch_password.c
  * @brief          : Touch button mode/pin/password output system (HID keyboard)
  ******************************************************************************
  */

#include "touch_password.h"
#include "main.h"
#include "usb_device.h"
#include "usbd_hid.h"
#include <string.h>

extern USBD_HandleTypeDef hUsbDeviceFS;

#define MODE_NONTRUSTED             TOUCH_MODE_NONTRUSTED
#define MODE_TRUSTED                TOUCH_MODE_TRUSTED
#define MODE_ADMIN                  TOUCH_MODE_ADMIN

#define CTRL_MODE_MASK              0x03U
#define CTRL_IS_OUTPUTTING          0x04U
#define CTRL_RELEASE_PENDING        0x08U
#define CTRL_PENDING_IS_ENTER       0x10U
#define CTRL_APPEND_ENTER           0x20U
#define CTRL_ENTER_SENT             0x40U

#define STARTUP_GUARD_MS            3000U
#define PIN_SEQUENCE_LEN            3U
#define OUTPUT_CHAR_PERIOD_MS       40U
#define KEY_RELEASE_GAP_MS          5U
#define TOUCH_CFG_FLASH_PAGE_ADDR   0x08007C00UL
#define TOUCH_CFG_FLASH_PAGE_COUNT  1U
#define BOOT_REQUEST_RAM_ADDR       0x200017FCUL
#define BOOT_REQUEST_MAGIC          0xB00710ADUL

typedef struct
{
  uint32_t bootTick;
  uint32_t lastOutputTick;
  uint8_t control;
  uint8_t pinIndex;
  uint8_t pendingModifier;
  uint8_t pendingKeycode;
  uint8_t outputCharIndex;
  uint8_t configDirty;
  const char *currentText;
  PasswordResult_t lastResult;
} PasswordState_t;

static PasswordState_t pwdState = {0};

static const char *defaultTrustedPasswords[TOUCH_BUTTON_COUNT] =
{
  "*Moustafa_eJ@d2026#!",
  "StungPayingNeedyJavaValue",
  "qxz5fys",
  "741536",
  "test5pass55",
  "secret666xxx"
};

static const char *trustedDescriptions[TOUCH_BUTTON_COUNT] =
{
  "BTN1 - Master unlock code",
  "BTN2 - Secondary access",
  "BTN3 - Administration",
  "BTN4 - Maintenance mode",
  "BTN5 - Test mode 1",
  "BTN6 - Test mode 2"
};

static TouchConfigImage_t cfgImage = {0};
static ButtonPassword_t trustedPasswords[TOUCH_BUTTON_COUNT] = {0};

/* Nontrusted mode dummy outputs */
static const ButtonPassword_t dummyPasswords[6] =
{
  {0, "n1", 0U, "BTN1 - Nontrusted dummy"},
  {1, "n2", 0U, "BTN2 - Nontrusted dummy"},
  {2, "n3", 0U, "BTN3 - Nontrusted dummy"},
  {3, "n4", 0U, "BTN4 - Nontrusted dummy"},
  {4, "n5", 0U, "BTN5 - Nontrusted dummy"},
  {5, "n6", 0U, "BTN6 - Nontrusted dummy"}
};

/* Phase 2 note: PIN is button sequence, not character input */
static const uint8_t trustedModePin[PIN_SEQUENCE_LEN] = {0U, 0U, 5U}; /* BTN1 -> BTN1 -> BTN6 */

static uint16_t Touch_ConfigChecksum(const uint8_t *data, uint16_t length)
{
  uint16_t crc = 0xFFFFU;
  uint16_t idx;

  for (idx = 0U; idx < length; idx++)
  {
    uint8_t bit;
    crc ^= (uint16_t)data[idx] << 8U;
    for (bit = 0U; bit < 8U; bit++)
    {
      if ((crc & 0x8000U) != 0U)
      {
        crc = (uint16_t)((crc << 1U) ^ 0x1021U);
      }
      else
      {
        crc <<= 1U;
      }
    }
  }

  return crc;
}

static uint8_t Touch_ConfigIsValid(const TouchConfigImage_t *image)
{
  uint32_t idx;
  uint16_t computed;

  if (image == NULL)
  {
    return 0U;
  }

  if ((image->header.version != TOUCH_CONFIG_VERSION) ||
      (image->header.valid != TOUCH_CFG_VALID_FLAG) ||
      (image->header.buttonCount != TOUCH_BUTTON_COUNT) ||
      (image->header.maxPasswordLen != TOUCH_PASSWORD_MAX_LEN) ||
      (image->header.payloadSize != (uint16_t)sizeof(image->buttons)))
  {
    return 0U;
  }

  for (idx = 0U; idx < TOUCH_BUTTON_COUNT; idx++)
  {
    if (image->buttons[idx].passwordLen > TOUCH_PASSWORD_MAX_LEN)
    {
      return 0U;
    }
  }

  computed = Touch_ConfigChecksum((const uint8_t *)image->buttons, image->header.payloadSize);
  return (computed == image->header.checksum) ? 1U : 0U;
}

static void Touch_LoadDefaults(void)
{
  uint32_t idx;

  memset(&cfgImage, 0, sizeof(cfgImage));
  cfgImage.header.version = TOUCH_CONFIG_VERSION;
  cfgImage.header.valid = TOUCH_CFG_VALID_FLAG;
  cfgImage.header.buttonCount = TOUCH_BUTTON_COUNT;
  cfgImage.header.maxPasswordLen = TOUCH_PASSWORD_MAX_LEN;
  cfgImage.header.payloadSize = (uint16_t)sizeof(cfgImage.buttons);

  for (idx = 0U; idx < TOUCH_BUTTON_COUNT; idx++)
  {
    uint8_t len = (uint8_t)strlen(defaultTrustedPasswords[idx]);
    if (len > TOUCH_PASSWORD_MAX_LEN)
    {
      len = TOUCH_PASSWORD_MAX_LEN;
    }

    cfgImage.buttons[idx].passwordLen = len;
    cfgImage.buttons[idx].flags = TOUCH_BTN_FLAG_APPEND_ENTER;
    memset(cfgImage.buttons[idx].password, 0, sizeof(cfgImage.buttons[idx].password));
    memcpy(cfgImage.buttons[idx].password, defaultTrustedPasswords[idx], len);
    cfgImage.buttons[idx].password[len] = '\0';
  }

  cfgImage.header.checksum = Touch_ConfigChecksum((const uint8_t *)cfgImage.buttons, cfgImage.header.payloadSize);
}

static void Touch_RefreshRuntimeTrustedTable(void)
{
  uint8_t idx;

  for (idx = 0U; idx < TOUCH_BUTTON_COUNT; idx++)
  {
    uint8_t len = cfgImage.buttons[idx].passwordLen;
    if (len > TOUCH_PASSWORD_MAX_LEN)
    {
      len = TOUCH_PASSWORD_MAX_LEN;
    }
    cfgImage.buttons[idx].password[len] = '\0';

    trustedPasswords[idx].buttonIdx = idx;
    trustedPasswords[idx].password = (const char *)cfgImage.buttons[idx].password;
    trustedPasswords[idx].appendEnter = ((cfgImage.buttons[idx].flags & TOUCH_BTN_FLAG_APPEND_ENTER) != 0U) ? 1U : 0U;
    trustedPasswords[idx].description = trustedDescriptions[idx];
  }
}

static uint8_t Touch_LoadConfigFromFlash(void)
{
  const TouchConfigImage_t *flashImage = (const TouchConfigImage_t *)TOUCH_CFG_FLASH_PAGE_ADDR;

  if (Touch_ConfigIsValid(flashImage) == 0U)
  {
    return 0U;
  }

  memcpy(&cfgImage, flashImage, sizeof(cfgImage));
  return 1U;
}

static uint8_t Touch_SaveConfigToFlash(void)
{
  HAL_StatusTypeDef status;
  FLASH_EraseInitTypeDef erase = {0};
  uint32_t pageError = 0U;
  uint32_t addr;
  uint32_t offset;
  TouchConfigImage_t image;

  memcpy(&image, &cfgImage, sizeof(image));
  image.header.version = TOUCH_CONFIG_VERSION;
  image.header.valid = TOUCH_CFG_VALID_FLAG;
  image.header.buttonCount = TOUCH_BUTTON_COUNT;
  image.header.maxPasswordLen = TOUCH_PASSWORD_MAX_LEN;
  image.header.payloadSize = (uint16_t)sizeof(image.buttons);
  image.header.checksum = Touch_ConfigChecksum((const uint8_t *)image.buttons, image.header.payloadSize);

  HAL_FLASH_Unlock();

  erase.TypeErase = FLASH_TYPEERASE_PAGES;
  erase.PageAddress = TOUCH_CFG_FLASH_PAGE_ADDR;
  erase.NbPages = TOUCH_CFG_FLASH_PAGE_COUNT;

  status = HAL_FLASHEx_Erase(&erase, &pageError);
  if (status != HAL_OK)
  {
    HAL_FLASH_Lock();
    return 0U;
  }

  addr = TOUCH_CFG_FLASH_PAGE_ADDR;
  for (offset = 0U; offset < sizeof(image); offset += 2U)
  {
    uint16_t halfWord;
    memcpy(&halfWord, ((const uint8_t *)&image) + offset, sizeof(halfWord));
    status = HAL_FLASH_Program(FLASH_TYPEPROGRAM_HALFWORD, addr + offset, halfWord);
    if (status != HAL_OK)
    {
      HAL_FLASH_Lock();
      return 0U;
    }
  }

  HAL_FLASH_Lock();
  memcpy(&cfgImage, &image, sizeof(cfgImage));
  return 1U;
}

static uint8_t GetMode(void)
{
  return (uint8_t)(pwdState.control & CTRL_MODE_MASK);
}

static void SetMode(uint8_t mode)
{
  pwdState.control = (uint8_t)((pwdState.control & (uint8_t)(~CTRL_MODE_MASK)) | (mode & CTRL_MODE_MASK));
}

static uint8_t IsFlagSet(uint8_t mask)
{
  return ((pwdState.control & mask) != 0U) ? 1U : 0U;
}

static void SetFlag(uint8_t mask)
{
  pwdState.control |= mask;
}

static void ClearFlag(uint8_t mask)
{
  pwdState.control = (uint8_t)(pwdState.control & (uint8_t)(~mask));
}

static void ASCII_to_HID_Report(char ascii, uint8_t *modifier, uint8_t *keycode)
{
  *modifier = 0x00;
  *keycode = 0x00;

  if (ascii >= 'a' && ascii <= 'z')
  {
    *keycode = (uint8_t)(0x04 + (ascii - 'a'));
    return;
  }

  if (ascii >= 'A' && ascii <= 'Z')
  {
    *modifier = 0x02;
    *keycode = (uint8_t)(0x04 + (ascii - 'A'));
    return;
  }

  if (ascii >= '0' && ascii <= '9')
  {
    *keycode = (ascii == '0') ? 0x27U : (uint8_t)(0x1EU + (ascii - '1'));
    return;
  }

  switch (ascii)
  {
    case ' ':  *keycode = 0x2CU; break;
    case '!':  *modifier = 0x02U; *keycode = 0x1EU; break;
    case '@':  *modifier = 0x02U; *keycode = 0x1FU; break;
    case '#':  *modifier = 0x02U; *keycode = 0x20U; break;
    case '$':  *modifier = 0x02U; *keycode = 0x21U; break;
    case '%':  *modifier = 0x02U; *keycode = 0x22U; break;
    case '^':  *modifier = 0x02U; *keycode = 0x23U; break;
    case '&':  *modifier = 0x02U; *keycode = 0x24U; break;
    case '*':  *modifier = 0x02U; *keycode = 0x25U; break;
    case '(':  *modifier = 0x02U; *keycode = 0x26U; break;
    case ')':  *modifier = 0x02U; *keycode = 0x27U; break;
    case '-':  *keycode = 0x2DU; break;
    case '_':  *modifier = 0x02U; *keycode = 0x2DU; break;
    case '=':  *keycode = 0x2EU; break;
    case '+':  *modifier = 0x02U; *keycode = 0x2EU; break;
    case '[':  *keycode = 0x2FU; break;
    case '{':  *modifier = 0x02U; *keycode = 0x2FU; break;
    case ']':  *keycode = 0x30U; break;
    case '}':  *modifier = 0x02U; *keycode = 0x30U; break;
    case ';':  *keycode = 0x33U; break;
    case ':':  *modifier = 0x02U; *keycode = 0x33U; break;
    case '\'': *keycode = 0x34U; break;
    case '"': *modifier = 0x02U; *keycode = 0x34U; break;
    case ',':  *keycode = 0x36U; break;
    case '<':  *modifier = 0x02U; *keycode = 0x36U; break;
    case '.':  *keycode = 0x37U; break;
    case '>':  *modifier = 0x02U; *keycode = 0x37U; break;
    case '/':  *keycode = 0x38U; break;
    case '?':  *modifier = 0x02U; *keycode = 0x38U; break;
    case '`':  *keycode = 0x35U; break;
    case '~':  *modifier = 0x02U; *keycode = 0x35U; break;
    default:   break;
  }
}

static uint8_t HID_Send_KeyReport(uint8_t modifier, uint8_t keycode)
{
  uint8_t report[9] = {0};

  report[0] = HID_REPORT_ID_KEYBOARD_INPUT;
  report[1] = modifier;
  report[3] = keycode;

  return USBD_HID_SendReport(&hUsbDeviceFS, report, 9U);
}

static uint8_t HID_Send_KeyRelease(void)
{
  uint8_t report[9] = {0};
  report[0] = HID_REPORT_ID_KEYBOARD_INPUT;
  return USBD_HID_SendReport(&hUsbDeviceFS, report, 9U);
}

static void StartOutput(const ButtonPassword_t *record, uint32_t nowTick)
{
  pwdState.currentText = record->password;
  pwdState.outputCharIndex = 0U;
  SetFlag(CTRL_IS_OUTPUTTING);
  ClearFlag(CTRL_RELEASE_PENDING);
  ClearFlag(CTRL_PENDING_IS_ENTER);
  ClearFlag(CTRL_ENTER_SENT);

  if (record->appendEnter != 0U)
  {
    SetFlag(CTRL_APPEND_ENTER);
  }
  else
  {
    ClearFlag(CTRL_APPEND_ENTER);
  }

  pwdState.pendingModifier = 0U;
  pwdState.pendingKeycode = 0U;
  pwdState.lastOutputTick = nowTick;
}

void TouchPassword_Init(void)
{
  memset(&pwdState, 0, sizeof(pwdState));

  Touch_LoadDefaults();
  if (Touch_LoadConfigFromFlash() == 0U)
  {
    (void)Touch_SaveConfigToFlash();
  }
  Touch_RefreshRuntimeTrustedTable();

  SetMode(MODE_NONTRUSTED);
  pwdState.bootTick = HAL_GetTick();
  pwdState.lastResult.unlocked = 0U;
  pwdState.lastResult.buttonIdx = 0xFFU;
  pwdState.lastResult.unlockTimestamp = 0UL;
  pwdState.configDirty = 0U;
}

void TouchPassword_OnButtonPress(uint8_t buttonIdx)
{
  uint32_t nowTick = HAL_GetTick();

  if (buttonIdx >= 6U)
  {
    return;
  }

  if ((nowTick - pwdState.bootTick) < STARTUP_GUARD_MS)
  {
    return;
  }

  if (IsFlagSet(CTRL_IS_OUTPUTTING) != 0U)
  {
    return;
  }

  if (GetMode() == MODE_NONTRUSTED)
  {
    if (buttonIdx == trustedModePin[pwdState.pinIndex])
    {
      pwdState.pinIndex++;
      if (pwdState.pinIndex >= PIN_SEQUENCE_LEN)
      {
        SetMode(MODE_TRUSTED);
        pwdState.pinIndex = 0U;
        pwdState.lastResult.unlocked = 1U;
        pwdState.lastResult.buttonIdx = 0xFEU;
        pwdState.lastResult.unlockTimestamp = nowTick;
        return;
      }
    }
    else
    {
      pwdState.pinIndex = (buttonIdx == trustedModePin[0]) ? 1U : 0U;
    }

    StartOutput(&dummyPasswords[buttonIdx], nowTick);
    return;
  }

  if (GetMode() == MODE_TRUSTED)
  {
    StartOutput(&trustedPasswords[buttonIdx], nowTick);
    return;
  }

  StartOutput(&trustedPasswords[buttonIdx], nowTick);
}

void TouchPassword_Task(void)
{
  uint32_t nowTick = HAL_GetTick();

  if ((IsFlagSet(CTRL_IS_OUTPUTTING) == 0U) || (pwdState.currentText == NULL))
  {
    return;
  }

  if (IsFlagSet(CTRL_RELEASE_PENDING) != 0U)
  {
    if ((nowTick - pwdState.lastOutputTick) < KEY_RELEASE_GAP_MS)
    {
      return;
    }

    if (HID_Send_KeyRelease() == USBD_OK)
    {
      ClearFlag(CTRL_RELEASE_PENDING);

      if (IsFlagSet(CTRL_PENDING_IS_ENTER) != 0U)
      {
        ClearFlag(CTRL_IS_OUTPUTTING);
        pwdState.currentText = NULL;
      }
      else
      {
        pwdState.outputCharIndex++;
      }

      ClearFlag(CTRL_PENDING_IS_ENTER);
      pwdState.lastOutputTick = nowTick;
    }
    return;
  }

  if ((nowTick - pwdState.lastOutputTick) < OUTPUT_CHAR_PERIOD_MS)
  {
    return;
  }

  {
    char charToSend = pwdState.currentText[pwdState.outputCharIndex];
    uint8_t modifier;
    uint8_t keycode;

    if (charToSend == '\0')
    {
      if ((IsFlagSet(CTRL_APPEND_ENTER) != 0U) && (IsFlagSet(CTRL_ENTER_SENT) == 0U))
      {
        if (HID_Send_KeyReport(0U, 0x28U) == USBD_OK)
        {
          pwdState.pendingModifier = 0U;
          pwdState.pendingKeycode = 0x28U;
          SetFlag(CTRL_PENDING_IS_ENTER);
          SetFlag(CTRL_ENTER_SENT);
          SetFlag(CTRL_RELEASE_PENDING);
          pwdState.lastOutputTick = nowTick;
        }
        return;
      }

      ClearFlag(CTRL_IS_OUTPUTTING);
      pwdState.currentText = NULL;
      return;
    }

    ASCII_to_HID_Report(charToSend, &modifier, &keycode);

    if (keycode == 0U)
    {
      pwdState.outputCharIndex++;
      pwdState.lastOutputTick = nowTick;
      return;
    }

    if (HID_Send_KeyReport(modifier, keycode) == USBD_OK)
    {
      pwdState.pendingModifier = modifier;
      pwdState.pendingKeycode = keycode;
      ClearFlag(CTRL_PENDING_IS_ENTER);
      SetFlag(CTRL_RELEASE_PENDING);
      pwdState.lastOutputTick = nowTick;
    }
  }
}

void TouchPassword_ResetBuffer(void)
{
  ClearFlag(CTRL_IS_OUTPUTTING);
  ClearFlag(CTRL_RELEASE_PENDING);
  ClearFlag(CTRL_PENDING_IS_ENTER);
  ClearFlag(CTRL_APPEND_ENTER);
  ClearFlag(CTRL_ENTER_SENT);
  pwdState.pendingModifier = 0U;
  pwdState.pendingKeycode = 0U;
  pwdState.currentText = NULL;
  pwdState.outputCharIndex = 0U;
}

uint8_t TouchPassword_IsLockedOut(void)
{
  return IsFlagSet(CTRL_IS_OUTPUTTING);
}

uint32_t TouchPassword_GetLockoutRemainingSec(void)
{
  if (pwdState.currentText == NULL)
  {
    return 0U;
  }

  {
    uint32_t length = (uint32_t)strlen(pwdState.currentText);
    if (length > (uint32_t)pwdState.outputCharIndex)
    {
      return length - (uint32_t)pwdState.outputCharIndex;
    }
  }

  return 0U;
}

PasswordResult_t TouchPassword_GetLastResult(void)
{
  return pwdState.lastResult;
}

uint8_t TouchPassword_GetMode(void)
{
  return GetMode();
}

uint8_t TouchPassword_SetMode(uint8_t mode)
{
  if ((mode != MODE_NONTRUSTED) && (mode != MODE_TRUSTED) && (mode != MODE_ADMIN))
  {
    return 0U;
  }

  TouchPassword_ResetBuffer();
  pwdState.pinIndex = 0U;
  SetMode(mode);
  return 1U;
}

const ButtonPassword_t *TouchPassword_GetActiveTable(void)
{
  return (GetMode() == MODE_NONTRUSTED) ? dummyPasswords : trustedPasswords;
}

uint8_t TouchPassword_GetButtonConfig(uint8_t buttonIdx,
                                      uint8_t *password,
                                      uint8_t *passwordLen,
                                      uint8_t *appendEnter,
                                      uint8_t passwordBufSize)
{
  const TouchButtonConfig_t *cfg;
  uint8_t len;

  if ((buttonIdx >= TOUCH_BUTTON_COUNT) || (password == NULL) || (passwordLen == NULL) || (appendEnter == NULL))
  {
    return 0U;
  }

  cfg = &cfgImage.buttons[buttonIdx];
  len = cfg->passwordLen;
  if ((len > TOUCH_PASSWORD_MAX_LEN) || (len > passwordBufSize))
  {
    return 0U;
  }

  memcpy(password, cfg->password, len);
  *passwordLen = len;
  *appendEnter = ((cfg->flags & TOUCH_BTN_FLAG_APPEND_ENTER) != 0U) ? 1U : 0U;
  return 1U;
}

uint8_t TouchPassword_SetButtonConfig(uint8_t buttonIdx,
                                      const uint8_t *password,
                                      uint8_t passwordLen,
                                      uint8_t appendEnter)
{
  TouchButtonConfig_t *cfg;

  if ((buttonIdx >= TOUCH_BUTTON_COUNT) || (password == NULL) || (passwordLen > TOUCH_PASSWORD_MAX_LEN))
  {
    return 0U;
  }

  cfg = &cfgImage.buttons[buttonIdx];
  memset(cfg->password, 0, sizeof(cfg->password));
  memcpy(cfg->password, password, passwordLen);
  cfg->password[passwordLen] = '\0';
  cfg->passwordLen = passwordLen;
  cfg->flags = (appendEnter != 0U) ? TOUCH_BTN_FLAG_APPEND_ENTER : 0U;

  cfgImage.header.checksum = Touch_ConfigChecksum((const uint8_t *)cfgImage.buttons, cfgImage.header.payloadSize);
  pwdState.configDirty = 1U;
  Touch_RefreshRuntimeTrustedTable();
  return 1U;
}

uint8_t TouchPassword_SaveConfig(void)
{
  if (pwdState.configDirty == 0U)
  {
    return 1U;
  }

  if (Touch_SaveConfigToFlash() == 0U)
  {
    return 0U;
  }

  pwdState.configDirty = 0U;
  return 1U;
}

uint8_t TouchPassword_EnterBootloader(void)
{
  volatile uint32_t *bootReq = (volatile uint32_t *)BOOT_REQUEST_RAM_ADDR;

  /* Use RAM magic request so bootloader can clear it cheaply after reset. */
  *bootReq = BOOT_REQUEST_MAGIC;
  HAL_Delay(50);
  NVIC_SystemReset();
  return 1U;
}
