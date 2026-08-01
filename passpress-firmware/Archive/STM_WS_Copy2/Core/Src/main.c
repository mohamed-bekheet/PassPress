/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"
#include "touchsensing.h"
#include "usb_device.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#if ONLINE_CALIBRATION_DEBUG
#include "usbd_cdc_if.h"
#endif
#include "usbd_hid.h"
#include "touch_password.h"
#include <stdio.h>
#include <string.h>

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

typedef struct
{
  const char *name;
  uint32_t channel;
  uint32_t group;
  uint32_t raw;
  uint32_t baseline;
  int32_t delta;
  uint8_t pressed;
} TouchKey_t;

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */

#define TOUCH_KEY_COUNT            6U
#define TOUCH_CALIBRATION_SAMPLES  32U
#define TOUCH_DELTA_THRESHOLD      25
#define TOUCH_SCAN_PERIOD_MS       20U
#define TOUCH_DEBOUNCE_MS          500U
#if ONLINE_CALIBRATION_DEBUG
#define CDC_CMD_MAX_LEN            96U
#else
#define HID_CTRL_CMD_PING          0x01U
#define HID_CTRL_CMD_GET_STATUS    0x02U
#define HID_STATUS_TYPE_PING_ACK   0x81U
#define HID_STATUS_TYPE_SNAPSHOT   0x82U
#endif

/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/
TSC_HandleTypeDef htsc;

/* USER CODE BEGIN PV */

static TouchKey_t touchKeys[TOUCH_KEY_COUNT] =
{
  {"BTN1_PA3", TSC_GROUP1_IO4, TSC_GROUP1_IDX, 0U, 0U, 0, 0U},
  {"BTN2_PA6", TSC_GROUP2_IO3, TSC_GROUP2_IDX, 0U, 0U, 0, 0U},
  {"BTN3_PA2", TSC_GROUP1_IO3, TSC_GROUP1_IDX, 0U, 0U, 0, 0U},
  {"BTN4_PA5", TSC_GROUP2_IO2, TSC_GROUP2_IDX, 0U, 0U, 0, 0U},
  {"BTN5_PA1", TSC_GROUP1_IO2, TSC_GROUP1_IDX, 0U, 0U, 0, 0U},
  {"BTN6_PA4", TSC_GROUP2_IO1, TSC_GROUP2_IDX, 0U, 0U, 0, 0U}
};

static uint32_t touchCalibrationCount = 0U;
static uint8_t touchAnyPressed = 0U;
static uint8_t touchPrevPressed[TOUCH_KEY_COUNT] = {0U};
static uint32_t touchLastAcceptedPressTick[TOUCH_KEY_COUNT] = {0U};
static uint8_t touchThresholds[TOUCH_KEY_COUNT] =
{
  TOUCH_DELTA_THRESHOLD,
  TOUCH_DELTA_THRESHOLD,
  TOUCH_DELTA_THRESHOLD,
  TOUCH_DELTA_THRESHOLD,
  TOUCH_DELTA_THRESHOLD,
  TOUCH_DELTA_THRESHOLD
};
static uint8_t passwordSystemEnabled = 1U;

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_TSC_Init(void);
/* USER CODE BEGIN PFP */

static uint32_t Touch_ReadChannel(uint32_t channelIO, uint32_t groupIdx);
static void Touch_Scan(void);
#if ONLINE_CALIBRATION_DEBUG
static void Touch_PrintPressEvents(void);
static void Touch_PrintSnapshot(void);
static void USB_SendText(const char *text);
static void Touch_ProcessCommands(void);
static void Touch_PrintThresholds(void);
#else
static void HID_Keyboard_Task(void);
static void HID_ProcessControlReport(void);
static void HID_UpdateStatusReport(uint8_t msgType, uint8_t seq);
#endif

/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

static uint32_t Touch_ReadChannel(uint32_t channelIO, uint32_t groupIdx)
{
  TSC_IOConfigTypeDef ioConfig = {0};

  ioConfig.ChannelIOs = channelIO;
  ioConfig.ShieldIOs = 0U;
  ioConfig.SamplingIOs = TSC_GROUP1_IO1 | TSC_GROUP2_IO4;

  if (HAL_TSC_IOConfig(&htsc, &ioConfig) != HAL_OK)
  {
    return 0U;
  }

  if (HAL_TSC_Start(&htsc) != HAL_OK)
  {
    return 0U;
  }

  if (HAL_TSC_PollForAcquisition(&htsc) != HAL_OK)
  {
    return 0U;
  }

  return HAL_TSC_GroupGetValue(&htsc, groupIdx);
}

static void Touch_Scan(void)
{
  uint32_t index;
  touchAnyPressed = 0U;

  for (index = 0U; index < TOUCH_KEY_COUNT; index++)
  {
    touchKeys[index].raw = Touch_ReadChannel(touchKeys[index].channel, touchKeys[index].group);

    if (touchCalibrationCount < TOUCH_CALIBRATION_SAMPLES)
    {
      touchKeys[index].baseline = (touchKeys[index].baseline * touchCalibrationCount + touchKeys[index].raw) / (touchCalibrationCount + 1U);
      touchKeys[index].delta = 0;
      touchKeys[index].pressed = 0U;
    }
    else
    {
      int32_t deltaAbs;

      touchKeys[index].delta = (int32_t)touchKeys[index].raw - (int32_t)touchKeys[index].baseline;
      deltaAbs = touchKeys[index].delta;
      if (deltaAbs < 0)
      {
        deltaAbs = -deltaAbs;
      }

      touchKeys[index].pressed = (deltaAbs > touchThresholds[index]) ? 1U : 0U;

      if (touchKeys[index].pressed == 0U)
      {
        touchKeys[index].baseline = ((touchKeys[index].baseline * 31U) + touchKeys[index].raw) / 32U;
      }
    }

    if (touchKeys[index].pressed != 0U)
    {
      touchAnyPressed = 1U;
    }
  }

  if (touchCalibrationCount < TOUCH_CALIBRATION_SAMPLES)
  {
    touchCalibrationCount++;
  }
}

#if ONLINE_CALIBRATION_DEBUG
static void Touch_PrintThresholds(void)
{
  char line[160];
  int length;

  length = snprintf(
      line,
      sizeof(line),
      "th 0=%u 1=%u 2=%u 3=%u 4=%u 5=%u\r\n",
      (unsigned int)touchThresholds[0],
      (unsigned int)touchThresholds[1],
      (unsigned int)touchThresholds[2],
      (unsigned int)touchThresholds[3],
      (unsigned int)touchThresholds[4],
      (unsigned int)touchThresholds[5]);

  if (length > 0)
  {
    USB_SendText(line);
  }
}
#endif
#if ONLINE_CALIBRATION_DEBUG
static void Touch_PrintPressEvents(void)
{
  uint32_t index;
  uint32_t nowTick = HAL_GetTick();
  static uint8_t touchPrevPressedLog[TOUCH_KEY_COUNT] = {0U};
  static uint32_t touchLastAcceptedPressTickLog[TOUCH_KEY_COUNT] = {0U};

  if (touchCalibrationCount < TOUCH_CALIBRATION_SAMPLES)
  {
    return;
  }

  for (index = 0U; index < TOUCH_KEY_COUNT; index++)
  {
    if ((touchKeys[index].pressed != 0U) && (touchPrevPressedLog[index] == 0U)
        && ((nowTick - touchLastAcceptedPressTickLog[index]) >= TOUCH_DEBOUNCE_MS))
    {
      char line[160];
      int length;
      touchLastAcceptedPressTickLog[index] = nowTick;

      length = snprintf(
          line,
          sizeof(line),
          "[%lu ms] PRESS btn=%lu name=%s raw=%lu base=%lu delta=%ld th=%u\r\n",
          nowTick,
          index + 1U,
          touchKeys[index].name,
          touchKeys[index].raw,
          touchKeys[index].baseline,
          touchKeys[index].delta,
          (unsigned int)touchThresholds[index]);

      if (length > 0)
      {
        USB_SendText(line);
      }
    }

    touchPrevPressedLog[index] = touchKeys[index].pressed;
  }
}
#endif

#if ONLINE_CALIBRATION_DEBUG
static void Touch_ProcessCommands(void)
{
  char cmd[CDC_CMD_MAX_LEN];

  if (CDC_ReadLine(cmd, (uint16_t)sizeof(cmd)) == 0U)
  {
    return;
  }

  if ((strcmp(cmd, "help") == 0) || (strcmp(cmd, "?") == 0))
  {
    USB_SendText("cmd: help | th? | th <idx 0..5> <value> | stat | recal | pwd\r\n");
    USB_SendText("pwd cmds: pwd stat | pwd reset | pwd lock\r\n");
    return;
  }

  if (strcmp(cmd, "th?") == 0)
  {
    Touch_PrintThresholds();
    return;
  }

  if (strcmp(cmd, "stat") == 0)
  {
    Touch_PrintSnapshot();
    return;
  }

  if (strcmp(cmd, "recal") == 0)
  {
    uint32_t index;
    touchCalibrationCount = 0U;
    for (index = 0U; index < TOUCH_KEY_COUNT; index++)
    {
      touchKeys[index].baseline = 0U;
      touchKeys[index].delta = 0;
      touchKeys[index].pressed = 0U;
    }
    USB_SendText("recalibration started\r\n");
    return;
  }

  if (strncmp(cmd, "th ", 3) == 0)
  {
    int index;
    int value;
    char response[80];
    int responseLen;

    if (sscanf(cmd + 3, "%d %d", &index, &value) == 2)
    {
      if ((index >= 0) && (index < (int)TOUCH_KEY_COUNT) && (value > 0) && (value <= 255))
      {
        touchThresholds[index] = (uint8_t)value;
        responseLen = snprintf(response, sizeof(response), "th %d set to %d\r\n", index, value);
        if (responseLen > 0)
        {
          USB_SendText(response);
        }
      }
      else
      {
        USB_SendText("invalid: th <idx 0..5> <value 1..255>\r\n");
      }
    }
    else
    {
      USB_SendText("usage: th <idx 0..5> <value>\r\n");
    }
    return;
  }

  if (strncmp(cmd, "pwd", 3) == 0)
  {
    const char *subcmd = cmd + 3;
    while (*subcmd == ' ')
      subcmd++;

    if ((strcmp(subcmd, "stat") == 0) || (strcmp(subcmd, "") == 0))
    {
      char line[128];
      int len;
      
      if (TouchPassword_IsLockedOut())
      {
        len = snprintf(line, sizeof(line), "pwd: LOCKED OUT - %lu sec remaining\r\n", 
                       TouchPassword_GetLockoutRemainingSec());
      }
      else
      {
        PasswordResult_t result = TouchPassword_GetLastResult();
        if (result.unlocked == 1U)
        {
          len = snprintf(line, sizeof(line), "pwd: LAST UNLOCK success on BTN%lu at t=%lu ms\r\n",
                         result.buttonIdx + 1U, result.unlockTimestamp);
        }
        else
        {
          len = snprintf(line, sizeof(line), "pwd: ready for input\r\n");
        }
      }
      if (len > 0)
        USB_SendText(line);
      return;
    }

    if (strcmp(subcmd, "reset") == 0)
    {
      TouchPassword_ResetBuffer();
      USB_SendText("pwd: buffer reset\r\n");
      return;
    }

    if (strcmp(subcmd, "lock") == 0)
    {
      TouchPassword_ResetBuffer();
      USB_SendText("pwd: manual test lock would require advanced feature\r\n");
      return;
    }

    USB_SendText("pwd: unknown sub-command. try: pwd stat | pwd reset | pwd lock\r\n");
    return;
  }

  USB_SendText("unknown cmd. type help\r\n");
}
#endif

 #if ONLINE_CALIBRATION_DEBUG
static void USB_SendText(const char *text)
{
  uint16_t length = (uint16_t)strlen(text);

  if (length > 0U)
  {
    uint32_t retry;
    for (retry = 0U; retry < 40U; retry++)
    {
      int result = CDC_Transmit_FS((uint8_t *)text, length);
      if (result == USBD_OK)
      {
        break;
      }
      else if (result != USBD_BUSY)
      {
        break;
      }
      HAL_Delay(1);
    }
  }
}


static void Touch_PrintSnapshot(void)
{
  char line[128];
  int length;
  uint32_t index;

  length = snprintf(
      line,
      sizeof(line),
      "--- TOUCH STATUS t=%lu cal=%lu any=%u ---\r\n",
      HAL_GetTick(),
      touchCalibrationCount,
      touchAnyPressed);

  if (length > 0)
  {
    USB_SendText(line);
  }

  for (index = 0U; index < TOUCH_KEY_COUNT; index++)
  {
    length = snprintf(
        line,
        sizeof(line),
      "k%lu %-10s raw=%-4lu base=%-4lu delta=%-5ld th=%-4u p=%u\r\n",
        index,
        touchKeys[index].name,
        touchKeys[index].raw,
        touchKeys[index].baseline,
        touchKeys[index].delta,
      (unsigned int)touchThresholds[index],
        touchKeys[index].pressed);

    if (length > 0)
    {
      USB_SendText(line);
    }
  }
}
#endif

#if !ONLINE_CALIBRATION_DEBUG
extern USBD_HandleTypeDef hUsbDeviceFS;

static uint32_t hidStatusCounter = 0U;
static uint8_t hidLastActiveButtonOneBased = 0U;

static void HID_UpdateStatusReport(uint8_t msgType, uint8_t seq)
{
  uint8_t report[HID_FEATURE_REPORT_SIZE] = {0U};
  uint32_t idx;
  uint32_t uptimeMs = HAL_GetTick();
  const ButtonPassword_t *table = TouchPassword_GetActiveTable();

  report[0] = HID_REPORT_ID_STATUS_IN;
  report[1] = msgType;
  report[2] = seq;
  report[3] = TouchPassword_GetMode();
  report[4] = TouchPassword_IsLockedOut();
  report[5] = 1U; /* Reserved for transport-level connected flag */
  report[6] = hidLastActiveButtonOneBased;
  report[7] = touchAnyPressed;

  report[8] = (uint8_t)(uptimeMs & 0xFFU);
  report[9] = (uint8_t)((uptimeMs >> 8) & 0xFFU);
  report[10] = (uint8_t)((uptimeMs >> 16) & 0xFFU);
  report[11] = (uint8_t)((uptimeMs >> 24) & 0xFFU);

  hidStatusCounter++;
  report[12] = (uint8_t)(hidStatusCounter & 0xFFU);
  report[13] = (uint8_t)((hidStatusCounter >> 8) & 0xFFU);

  for (idx = 0U; idx < TOUCH_KEY_COUNT; idx++)
  {
    report[14U + idx] = table[idx].appendEnter;
    report[20U + idx] = (uint8_t)strlen(table[idx].password);
  }

  (void)USBD_HID_SetFeatureInReport(&hUsbDeviceFS, report, HID_FEATURE_REPORT_SIZE);
}

static void HID_ProcessControlReport(void)
{
  uint8_t report[HID_FEATURE_REPORT_SIZE] = {0U};
  uint16_t length = 0U;

  if (USBD_HID_GetFeatureOutReport(&hUsbDeviceFS, report, &length) != USBD_OK)
  {
    return;
  }

  if ((length < 3U) || (report[0] != HID_REPORT_ID_CTRL_OUT))
  {
    return;
  }

  switch (report[1])
  {
    case HID_CTRL_CMD_PING:
      HID_UpdateStatusReport(HID_STATUS_TYPE_PING_ACK, report[2]);
      break;

    case HID_CTRL_CMD_GET_STATUS:
      HID_UpdateStatusReport(HID_STATUS_TYPE_SNAPSHOT, report[2]);
      break;

    default:
      HID_UpdateStatusReport(0xFFU, report[2]);
      break;
  }
}

static void HID_Keyboard_Task(void)
{
  uint32_t index;
  static uint8_t hidPrevPressed[TOUCH_KEY_COUNT] = {0U};

  for (index = 0U; index < TOUCH_KEY_COUNT; index++)
  {
    if ((touchKeys[index].pressed != 0U) && (hidPrevPressed[index] == 0U))
    {
      HAL_GPIO_TogglePin(GPIOB, GPIO_PIN_0);
      /* HID mode section:
         Map index -> keyboard key and send HID report here once HID class
         is generated in CubeMX (USB_DEVICE class set to HID keyboard). */
    }
    hidPrevPressed[index] = touchKeys[index].pressed;
  }
}
#endif

/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{

  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_USB_DEVICE_Init();
  MX_TSC_Init();
  MX_TOUCHSENSING_Init();
  /* USER CODE BEGIN 2 */

  /* Initialize password/unlock system */
  TouchPassword_Init();

#if ONLINE_CALIBRATION_DEBUG
  USB_SendText("Touch debug start\r\n");
  USB_SendText("type help then enter\r\n");
  USB_SendText("event log: [time] PRESS btn=<1..6> name=<pin> raw=<r> base=<b> delta=<d> th=<t>\r\n");
  USB_SendText("Password system enabled (hard-coded 6 button passwords)\r\n");
#endif

  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1)
  {
    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
    static uint32_t aliveCounter = 0U;
    static uint32_t lastAliveTick = 0U;
    static uint32_t lastScanTick = 0U;
    uint32_t nowTick = HAL_GetTick();

#if ONLINE_CALIBRATION_DEBUG
    if ((nowTick - lastAliveTick) >= 1000U)
    {
      char line[48];
      int length;

      lastAliveTick = nowTick;
      aliveCounter++;
      length = snprintf(line, sizeof(line), "alive=%lu t=%lu ms\r\n", aliveCounter, nowTick);

      if (length > 0)
      {
        USB_SendText(line);
      }
    }

    Touch_ProcessCommands();
#else
    (void)aliveCounter;
    (void)lastAliveTick;

  HID_ProcessControlReport();
#endif

    if ((nowTick - lastScanTick) >= TOUCH_SCAN_PERIOD_MS)
    {
      lastScanTick = nowTick;
      Touch_Scan();

      /* Check for password system input (button press events) */
      if (passwordSystemEnabled)
      {
        uint32_t idx;
        for (idx = 0U; idx < TOUCH_KEY_COUNT; idx++)
        {
          if ((touchKeys[idx].pressed != 0U) && (touchPrevPressed[idx] == 0U)
              && ((nowTick - touchLastAcceptedPressTick[idx]) >= TOUCH_DEBOUNCE_MS))
          {
            touchLastAcceptedPressTick[idx] = nowTick;
            hidLastActiveButtonOneBased = (uint8_t)(idx + 1U);
            TouchPassword_OnButtonPress(idx);
          }
          /* Update state for next scan */
          touchPrevPressed[idx] = touchKeys[idx].pressed;
        }
      }

#if ONLINE_CALIBRATION_DEBUG
      Touch_PrintPressEvents();
#else
  HID_UpdateStatusReport(HID_STATUS_TYPE_SNAPSHOT, 0U);
  HID_Keyboard_Task();
#endif
    }

    /* Run password entry timeout check and unlock logic */
    TouchPassword_Task();
  
  }
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};
  RCC_PeriphCLKInitTypeDef PeriphClkInit = {0};

  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSI48;
  RCC_OscInitStruct.HSI48State = RCC_HSI48_ON;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_NONE;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_HSI48;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_1) != HAL_OK)
  {
    Error_Handler();
  }
  PeriphClkInit.PeriphClockSelection = RCC_PERIPHCLK_USB;
  PeriphClkInit.UsbClockSelection = RCC_USBCLKSOURCE_HSI48;

  if (HAL_RCCEx_PeriphCLKConfig(&PeriphClkInit) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief TSC Initialization Function
  * @param None
  * @retval None
  */
static void MX_TSC_Init(void)
{

  /* USER CODE BEGIN TSC_Init 0 */

  /* USER CODE END TSC_Init 0 */

  /* USER CODE BEGIN TSC_Init 1 */

  /* USER CODE END TSC_Init 1 */

  /** Configure the TSC peripheral
  */
  htsc.Instance = TSC;
  htsc.Init.CTPulseHighLength = TSC_CTPH_2CYCLES;
  htsc.Init.CTPulseLowLength = TSC_CTPL_2CYCLES;
  htsc.Init.SpreadSpectrum = DISABLE;
  htsc.Init.SpreadSpectrumDeviation = 1;
  htsc.Init.SpreadSpectrumPrescaler = TSC_SS_PRESC_DIV1;
  htsc.Init.PulseGeneratorPrescaler = TSC_PG_PRESC_DIV4;
  htsc.Init.MaxCountValue = TSC_MCV_8191;
  htsc.Init.IODefaultMode = TSC_IODEF_OUT_PP_LOW;
  htsc.Init.SynchroPinPolarity = TSC_SYNC_POLARITY_FALLING;
  htsc.Init.AcquisitionMode = TSC_ACQ_MODE_NORMAL;
  htsc.Init.MaxCountInterrupt = DISABLE;
  htsc.Init.ChannelIOs = TSC_GROUP1_IO2|TSC_GROUP1_IO3|TSC_GROUP1_IO4|TSC_GROUP2_IO1
                    |TSC_GROUP2_IO2|TSC_GROUP2_IO3;
  htsc.Init.ShieldIOs = 0;
  htsc.Init.SamplingIOs = TSC_GROUP1_IO1|TSC_GROUP2_IO4;
  if (HAL_TSC_Init(&htsc) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN TSC_Init 2 */

  /* USER CODE END TSC_Init 2 */

}

/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  /* USER CODE BEGIN MX_GPIO_Init_1 */

  /* USER CODE END MX_GPIO_Init_1 */

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOB, GPIO_PIN_0|GPIO_PIN_1, GPIO_PIN_SET);

  /*Configure GPIO pins : PB0 PB1 */
  GPIO_InitStruct.Pin = GPIO_PIN_0|GPIO_PIN_1;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

  /* USER CODE BEGIN MX_GPIO_Init_2 */

  /* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}
#ifdef USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */

