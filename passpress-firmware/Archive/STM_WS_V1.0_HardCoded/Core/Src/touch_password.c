/**
  ******************************************************************************
  * @file           : touch_password.c
  * @brief          : Touch button password output system (HID keyboard)
  ******************************************************************************
  */

#include "touch_password.h"
#include "main.h"
#include "usb_device.h"
#include "usbd_hid.h"
#include <string.h>

/* External USB device handle */
extern USBD_HandleTypeDef hUsbDeviceFS;

/**
 * @brief Convert ASCII character to HID keyboard modifier + keycode
 */
static void ASCII_to_HID_Report(char ascii, uint8_t *modifier, uint8_t *keycode)
{
  *modifier = 0x00;
  *keycode = 0x00;

  /* Lowercase a-z */
  if (ascii >= 'a' && ascii <= 'z')
  {
    *keycode = 0x04 + (ascii - 'a');
    return;
  }

  /* Uppercase A-Z (shift) */
  if (ascii >= 'A' && ascii <= 'Z')
  {
    *modifier = 0x02;
    *keycode = 0x04 + (ascii - 'A');
    return;
  }

  /* Numbers 0-9 */
  if (ascii >= '0' && ascii <= '9')
  {
    *keycode = (ascii == '0') ? 0x27 : (0x1E + (ascii - '1'));
    return;
  }

  /* Special characters */
  switch (ascii)
  {
    case ' ':  *keycode = 0x2C; break;
    case '!':  *modifier = 0x02; *keycode = 0x1E; break;
    case '@':  *modifier = 0x02; *keycode = 0x1F; break;
    case '#':  *modifier = 0x02; *keycode = 0x20; break;
    case '$':  *modifier = 0x02; *keycode = 0x21; break;
    case '%':  *modifier = 0x02; *keycode = 0x22; break;
    case '^':  *modifier = 0x02; *keycode = 0x23; break;
    case '&':  *modifier = 0x02; *keycode = 0x24; break;
    case '*':  *modifier = 0x02; *keycode = 0x25; break;
    case '(':  *modifier = 0x02; *keycode = 0x26; break;
    case ')':  *modifier = 0x02; *keycode = 0x27; break;
    case '-':  *keycode = 0x2D; break;
    case '_':  *modifier = 0x02; *keycode = 0x2D; break;
    case '=':  *keycode = 0x2E; break;
    case '+':  *modifier = 0x02; *keycode = 0x2E; break;
    case '[':  *keycode = 0x2F; break;
    case '{':  *modifier = 0x02; *keycode = 0x2F; break;
    case ']':  *keycode = 0x30; break;
    case '}':  *modifier = 0x02; *keycode = 0x30; break;
    case ';':  *keycode = 0x33; break;
    case ':':  *modifier = 0x02; *keycode = 0x33; break;
    case '\'': *keycode = 0x34; break;
    case '\"': *modifier = 0x02; *keycode = 0x34; break;
    case ',':  *keycode = 0x36; break;
    case '<':  *modifier = 0x02; *keycode = 0x36; break;
    case '.':  *keycode = 0x37; break;
    case '>':  *modifier = 0x02; *keycode = 0x37; break;
    case '/':  *keycode = 0x38; break;
    case '?':  *modifier = 0x02; *keycode = 0x38; break;
    case '`':  *keycode = 0x35; break;
    case '~':  *modifier = 0x02; *keycode = 0x35; break;
    default:   break;
  }
}

/**
 * @brief Send HID keyboard key press report
 * Format: [Modifier][Reserved][Key1][Key2][Key3][Key4][Key5][Key6]
 */
static uint8_t HID_Send_KeyReport(uint8_t modifier, uint8_t keycode)
{
  uint8_t report[8] = {0};
  
  report[0] = modifier;      /* Modifier keys */
  report[1] = 0x00;          /* Reserved */
  report[2] = keycode;       /* Key code */
  report[3] = 0x00;
  report[4] = 0x00;
  report[5] = 0x00;
  report[6] = 0x00;
  report[7] = 0x00;
  
  /* Send key press */
  return USBD_HID_SendReport(&hUsbDeviceFS, report, 8);
}

/**
 * @brief Send HID keyboard key release report (all zeros)
 */
static uint8_t HID_Send_KeyRelease(void)
{
  uint8_t report[8] = {0};
  return USBD_HID_SendReport(&hUsbDeviceFS, report, 8);
}

/* Hard-coded button passwords / output strings */
static const ButtonPassword_t buttonPasswords[6] =
{
  {0, "*Moustafa_eJ@d2026#!", 1U, "BTN1 - Master unlock code"},
  {1, "StungPayingNeedyJavaValue", 1U, "BTN2 - Secondary access"},
  {2, "qxz5fys", 1U, "BTN3 - Administration"},
  {3, "741536", 1U, "BTN4 - Maintenance mode"},
  {4, "test5pass55",  1U, "BTN5 - Test mode 1"},
  {5, "secret666xxx", 1U, "BTN6 - Test mode 2"}
};

/* Password system state */
typedef struct
{
  uint32_t lastOutputTick;
  uint8_t isOutputting;
  uint8_t releasePending;
  uint8_t pendingIsEnter;
  uint8_t appendEnter;
  uint8_t enterSent;
  uint8_t pendingModifier;
  uint8_t pendingKeycode;
  uint32_t outputCharIndex;
  const char *currentPassword;
} PasswordState_t;

static PasswordState_t pwdState = {0};

/**
 * @brief Initialize password system
 */
void TouchPassword_Init(void)
{
  memset(&pwdState, 0, sizeof(pwdState));
}

/**
 * @brief Called when a button press is detected
 * Start outputting the associated password string via HID keyboard
 * @param buttonIdx: Button index (0-5)
 */
void TouchPassword_OnButtonPress(uint32_t buttonIdx)
{
  if (buttonIdx >= 6U)
  {
    return;
  }

  /* Start outputting this button's password */
  pwdState.currentPassword = buttonPasswords[buttonIdx].password;
  pwdState.outputCharIndex = 0U;
  pwdState.isOutputting = 1U;
  pwdState.releasePending = 0U;
  pwdState.pendingIsEnter = 0U;
  pwdState.appendEnter = buttonPasswords[buttonIdx].appendEnter;
  pwdState.enterSent = 0U;
  pwdState.pendingModifier = 0U;
  pwdState.pendingKeycode = 0U;
  pwdState.lastOutputTick = HAL_GetTick();
}

/**
 * @brief Main password task - outputs characters via HID keyboard
 * Call periodically from main loop
 */
void TouchPassword_Task(void)
{
  uint32_t nowTick = HAL_GetTick();

  if (!pwdState.isOutputting || !pwdState.currentPassword)
  {
    return;
  }

  if (pwdState.releasePending != 0U)
  {
    if ((nowTick - pwdState.lastOutputTick) < 5U)
    {
      return;
    }

    if (HID_Send_KeyRelease() == USBD_OK)
    {
      pwdState.releasePending = 0U;
      if (pwdState.pendingIsEnter != 0U)
      {
        pwdState.isOutputting = 0U;
        pwdState.currentPassword = NULL;
      }
      else
      {
        pwdState.outputCharIndex++;
      }
      pwdState.pendingIsEnter = 0U;
      pwdState.lastOutputTick = nowTick;
    }
    return;
  }

  /* Send one character every 50ms */
  if ((nowTick - pwdState.lastOutputTick) < 50U)
  {
    return;
  }

  {
    char charToSend = pwdState.currentPassword[pwdState.outputCharIndex];
    uint8_t modifier;
    uint8_t keycode;

    if (charToSend == '\0')
    {
      if ((pwdState.appendEnter != 0U) && (pwdState.enterSent == 0U))
      {
        if (HID_Send_KeyReport(0U, 0x28U) == USBD_OK)
        {
          pwdState.pendingModifier = 0U;
          pwdState.pendingKeycode = 0x28U;
          pwdState.pendingIsEnter = 1U;
          pwdState.enterSent = 1U;
          pwdState.releasePending = 1U;
          pwdState.lastOutputTick = nowTick;
        }
        return;
      }

      /* End of password string */
      pwdState.isOutputting = 0U;
      pwdState.currentPassword = NULL;
      return;
    }

    /* Convert ASCII to HID keyboard report and send */
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
      pwdState.pendingIsEnter = 0U;
      pwdState.releasePending = 1U;
      pwdState.lastOutputTick = nowTick;
    }
  }
}

/**
 * @brief Reset current output
 */
void TouchPassword_ResetBuffer(void)
{
  pwdState.isOutputting = 0U;
  pwdState.releasePending = 0U;
  pwdState.pendingIsEnter = 0U;
  pwdState.appendEnter = 0U;
  pwdState.enterSent = 0U;
  pwdState.pendingModifier = 0U;
  pwdState.pendingKeycode = 0U;
  pwdState.currentPassword = NULL;
  pwdState.outputCharIndex = 0U;
}

/**
 * @brief Check if system is currently outputting
 */
uint8_t TouchPassword_IsLockedOut(void)
{
  return pwdState.isOutputting;
}

/**
 * @brief Get remaining output time (not used in direct output mode)
 */
uint32_t TouchPassword_GetLockoutRemainingSec(void)
{
  if (!pwdState.currentPassword)
  {
    return 0U;
  }

  uint32_t remaining = strlen(pwdState.currentPassword) - pwdState.outputCharIndex;
  return remaining;
}

/**
 * @brief Get last output result (not used in direct output mode)
 */
PasswordResult_t TouchPassword_GetLastResult(void)
{
  PasswordResult_t result = {0};
  return result;
}
