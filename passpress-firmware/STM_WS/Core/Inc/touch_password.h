/**
  ******************************************************************************
  * @file           : touch_password.h
  * @brief          : Touch button password output system (HID keyboard)
  ******************************************************************************
  */

#ifndef TOUCH_PASSWORD_H
#define TOUCH_PASSWORD_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

#define TOUCH_MODE_NONTRUSTED 0x00U
#define TOUCH_MODE_TRUSTED    0x01U
#define TOUCH_MODE_ADMIN      0x02U

#define TOUCH_BUTTON_COUNT              6U
#define TOUCH_PASSWORD_MAX_LEN          27U
#define TOUCH_CONFIG_VERSION            1U

#define TOUCH_CFG_VALID_FLAG            0xA5U
#define TOUCH_BTN_FLAG_APPEND_ENTER     0x01U

/* Button password definitions (hard-coded) */
typedef struct
{
  uint8_t buttonIdx;
  const char *password;
  uint8_t appendEnter; /* 1: send Enter after password, 0: do not send Enter */
  const char *description;
} ButtonPassword_t;

typedef struct
{
  uint8_t passwordLen;
  uint8_t flags;
  uint8_t password[TOUCH_PASSWORD_MAX_LEN + 1U];
} TouchButtonConfig_t;

typedef struct
{
  uint8_t version;
  uint8_t valid;
  uint8_t buttonCount;
  uint8_t maxPasswordLen;
  uint16_t payloadSize;
  uint16_t checksum;
} TouchConfigHeader_t;

typedef struct
{
  TouchConfigHeader_t header;
  TouchButtonConfig_t buttons[TOUCH_BUTTON_COUNT];
  uint32_t bootloader_magic;
} TouchConfigImage_t;

/* Initialize password system */
void TouchPassword_Init(void);

/* Process button press - starts outputting the associated password via HID keyboard */
void TouchPassword_OnButtonPress(uint8_t buttonIdx);

/* Main task - call periodically from main loop to output characters */
void TouchPassword_Task(void);

/* Reset current output */
void TouchPassword_ResetBuffer(void);

/* Check if currently outputting */
uint8_t TouchPassword_IsLockedOut(void);

/* Get remaining characters to output */
uint32_t TouchPassword_GetLockoutRemainingSec(void);

/* Get successful unlock info (for logging/CDC output) */
typedef struct
{
  uint8_t unlocked;
  uint8_t buttonIdx;
  uint32_t unlockTimestamp;
} PasswordResult_t;

PasswordResult_t TouchPassword_GetLastResult(void);

uint8_t TouchPassword_GetMode(void);
uint8_t TouchPassword_SetMode(uint8_t mode);

const ButtonPassword_t *TouchPassword_GetActiveTable(void);

uint8_t TouchPassword_GetButtonConfig(uint8_t buttonIdx,
                                      uint8_t *password,
                                      uint8_t *passwordLen,
                                      uint8_t *appendEnter,
                                      uint8_t passwordBufSize);

uint8_t TouchPassword_SetButtonConfig(uint8_t buttonIdx,
                                      const uint8_t *password,
                                      uint8_t passwordLen,
                                      uint8_t appendEnter);

uint8_t TouchPassword_SaveConfig(void);

/* Request the device enter the bootloader on next reset (sets magic in config and persists)
  Returns 1 on success, 0 on failure */
uint8_t TouchPassword_EnterBootloader(void);

#ifdef __cplusplus
}
#endif

#endif /* TOUCH_PASSWORD_H */
