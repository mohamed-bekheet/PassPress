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

#define MODE_NONTRUSTED             0x00U
#define MODE_TRUSTED                0x01U
#define MODE_ADMIN                  0x02U

#define CTRL_MODE_MASK              0x03U
#define CTRL_IS_OUTPUTTING          0x04U
#define CTRL_RELEASE_PENDING        0x08U
#define CTRL_PENDING_IS_ENTER       0x10U
#define CTRL_APPEND_ENTER           0x20U
#define CTRL_ENTER_SENT             0x40U

#define STARTUP_GUARD_MS            3000U
#define PIN_SEQUENCE_LEN            3U
#define OUTPUT_CHAR_PERIOD_MS       50U
#define KEY_RELEASE_GAP_MS          5U

typedef struct
{
  uint32_t bootTick;
  uint32_t lastOutputTick;
  uint8_t control;
  uint8_t pinIndex;
  uint8_t pendingModifier;
  uint8_t pendingKeycode;
  uint8_t outputCharIndex;
  const char *currentText;
  PasswordResult_t lastResult;
} PasswordState_t;

static PasswordState_t pwdState = {0};

/* Trusted mode passwords */
static const ButtonPassword_t buttonPasswords[6] =
{
  {0, "*Moustafa_eJ@d2026#!", 1U, "BTN1 - Master unlock code"},
  {1, "StungPayingNeedyJavaValue", 1U, "BTN2 - Secondary access"},
  {2, "qxz5fys", 1U, "BTN3 - Administration"},
  {3, "741536", 1U, "BTN4 - Maintenance mode"},
  {4, "test5pass55", 1U, "BTN5 - Test mode 1"},
  {5, "secret666xxx", 1U, "BTN6 - Test mode 2"}
};

/* Nontrusted mode dummy outputs */
static const ButtonPassword_t dummyPasswords[6] =
{
  {0, "NonTrusted_btn1", 1U, "BTN1 - Nontrusted dummy"},
  {1, "NonTrusted_btn2", 1U, "BTN2 - Nontrusted dummy"},
  {2, "NonTrusted_btn3", 1U, "BTN3 - Nontrusted dummy"},
  {3, "NonTrusted_btn4", 1U, "BTN4 - Nontrusted dummy"},
  {4, "NonTrusted_btn5", 1U, "BTN5 - Nontrusted dummy"},
  {5, "NonTrusted_btn6", 1U, "BTN6 - Nontrusted dummy"}
};

/* Phase 2 note: PIN is button sequence, not character input */
static const uint8_t trustedModePin[PIN_SEQUENCE_LEN] = {0U, 0U, 5U}; /* BTN1 -> BTN1 -> BTN6 */

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
  uint8_t report[8] = {0};

  report[0] = modifier;
  report[2] = keycode;

  return USBD_HID_SendReport(&hUsbDeviceFS, report, 8U);
}

static uint8_t HID_Send_KeyRelease(void)
{
  uint8_t report[8] = {0};
  return USBD_HID_SendReport(&hUsbDeviceFS, report, 8U);
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
  SetMode(MODE_NONTRUSTED);
  pwdState.bootTick = HAL_GetTick();
  pwdState.lastResult.unlocked = 0U;
  pwdState.lastResult.buttonIdx = 0xFFFFFFFFUL;
  pwdState.lastResult.unlockTimestamp = 0UL;
}

void TouchPassword_OnButtonPress(uint32_t buttonIdx)
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
        pwdState.lastResult.buttonIdx = 0xFFFFFFFEUL;
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
    StartOutput(&buttonPasswords[buttonIdx], nowTick);
    return;
  }

  StartOutput(&buttonPasswords[buttonIdx], nowTick);
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
