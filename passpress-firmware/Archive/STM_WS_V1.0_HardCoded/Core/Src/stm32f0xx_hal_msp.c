/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file         stm32f0xx_hal_msp.c
  * @brief        This file provides code for the MSP Initialization
  *               and de-Initialization codes.
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
/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN TD */

/* USER CODE END TD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN Define */

/* USER CODE END Define */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN Macro */

/* USER CODE END Macro */

/* Private variables ---------------------------------------------------------*/
/* USER CODE BEGIN PV */

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
/* USER CODE BEGIN PFP */

/* USER CODE END PFP */

/* External functions --------------------------------------------------------*/
/* USER CODE BEGIN ExternalFunctions */

/* USER CODE END ExternalFunctions */

/* USER CODE BEGIN 0 */

/* USER CODE END 0 */
/**
  * Initializes the Global MSP.
  */
void HAL_MspInit(void)
{

  /* USER CODE BEGIN MspInit 0 */

  /* USER CODE END MspInit 0 */

  __HAL_RCC_SYSCFG_CLK_ENABLE();
  __HAL_RCC_PWR_CLK_ENABLE();

  /* System interrupt init*/

  __HAL_REMAP_PIN_ENABLE(HAL_REMAP_PA11_PA12);

  /* USER CODE BEGIN MspInit 1 */

  /* USER CODE END MspInit 1 */
}

/**
  * @brief TSC MSP Initialization
  * This function configures the hardware resources used in this example
  * @param htsc: TSC handle pointer
  * @retval None
  */
void HAL_TSC_MspInit(TSC_HandleTypeDef* htsc)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  if(htsc->Instance==TSC)
  {
    /* USER CODE BEGIN TSC_MspInit 0 */

    /* USER CODE END TSC_MspInit 0 */
    /* Peripheral clock enable */
    __HAL_RCC_TSC_CLK_ENABLE();

    __HAL_RCC_GPIOA_CLK_ENABLE();
    /**TSC GPIO Configuration
    PA0     ------> TSC_G1_IO1
    PA1     ------> TSC_G1_IO2
    PA2     ------> TSC_G1_IO3
    PA3     ------> TSC_G1_IO4
    PA4     ------> TSC_G2_IO1
    PA5     ------> TSC_G2_IO2
    PA6     ------> TSC_G2_IO3
    PA7     ------> TSC_G2_IO4
    */
    GPIO_InitStruct.Pin = GPIO_PIN_0|GPIO_PIN_7;
    GPIO_InitStruct.Mode = GPIO_MODE_AF_OD;
    GPIO_InitStruct.Pull = GPIO_NOPULL;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
    GPIO_InitStruct.Alternate = GPIO_AF3_TSC;
    HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

    GPIO_InitStruct.Pin = GPIO_PIN_1|GPIO_PIN_2|GPIO_PIN_3|GPIO_PIN_4
                          |GPIO_PIN_5|GPIO_PIN_6;
    GPIO_InitStruct.Mode = GPIO_MODE_AF_PP;
    GPIO_InitStruct.Pull = GPIO_NOPULL;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
    GPIO_InitStruct.Alternate = GPIO_AF3_TSC;
    HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

    /* USER CODE BEGIN TSC_MspInit 1 */

    /* USER CODE END TSC_MspInit 1 */

  }

}

/**
  * @brief TSC MSP De-Initialization
  * This function freeze the hardware resources used in this example
  * @param htsc: TSC handle pointer
  * @retval None
  */
void HAL_TSC_MspDeInit(TSC_HandleTypeDef* htsc)
{
  if(htsc->Instance==TSC)
  {
    /* USER CODE BEGIN TSC_MspDeInit 0 */

    /* USER CODE END TSC_MspDeInit 0 */
    /* Peripheral clock disable */
    __HAL_RCC_TSC_CLK_DISABLE();

    /**TSC GPIO Configuration
    PA0     ------> TSC_G1_IO1
    PA1     ------> TSC_G1_IO2
    PA2     ------> TSC_G1_IO3
    PA3     ------> TSC_G1_IO4
    PA4     ------> TSC_G2_IO1
    PA5     ------> TSC_G2_IO2
    PA6     ------> TSC_G2_IO3
    PA7     ------> TSC_G2_IO4
    */
    HAL_GPIO_DeInit(GPIOA, GPIO_PIN_0|GPIO_PIN_1|GPIO_PIN_2|GPIO_PIN_3
                          |GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7);

    /* USER CODE BEGIN TSC_MspDeInit 1 */

    /* USER CODE END TSC_MspDeInit 1 */
  }

}

/* USER CODE BEGIN 1 */

/* USER CODE END 1 */
