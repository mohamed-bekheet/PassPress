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

/* Button password definitions (hard-coded) */
typedef struct
{
  uint32_t buttonIdx;
  const char *password;
  uint8_t appendEnter; /* 1: send Enter after password, 0: do not send Enter */
  const char *description;
} ButtonPassword_t;

/* Initialize password system */
void TouchPassword_Init(void);

/* Process button press - starts outputting the associated password via HID keyboard */
void TouchPassword_OnButtonPress(uint32_t buttonIdx);

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
  uint32_t buttonIdx;
  uint32_t unlockTimestamp;
} PasswordResult_t;

PasswordResult_t TouchPassword_GetLastResult(void);

#ifdef __cplusplus
}
#endif

#endif /* TOUCH_PASSWORD_H */
