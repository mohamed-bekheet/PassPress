/* Tiny bootloader scaffold with chunked HID update protocol.
 *
 * Protocol expected from GUI (feature report size = 33):
 * report[0] = 0x02 (CTRL OUT report id)
 * report[1] = 0x10 (CMD_BOOT_TRANSFER)
 * report[2] = seq
 * report[3] = flags:
 *   0x00 = data chunk (more)
 *   0x01 = data chunk (last)
 *   0x02 = CRC packet (payload len=4)
 *   0x04 = APP_ADDR packet (payload len=4)
 * report[4..5] = payload length (little endian)
 * report[6..] = payload
 *
 * ACK response (provided by transport layer via Boot_SendAck):
 * report[0] = 0x03 (STATUS IN report id)
 * report[1] = 0x90 (MSG_BOOT_ACK)
 * report[2] = seq
 * report[3] = status code (0 = OK)
 */

#include "stm32f0xx_hal.h"
#include <stdint.h>
#include <string.h>
#include "usb_device.h"
#include "usbd_hid.h"

extern USBD_HandleTypeDef hUsbDeviceFS;

#define HID_REPORT_SIZE                 33U
#define HID_REPORT_ID_CTRL_OUT          0x02U
#define HID_REPORT_ID_STATUS_IN         0x03U

#define CMD_BOOT_TRANSFER               0x10U
#define MSG_BOOT_ACK                    0x90U

#define BOOT_FLAG_MORE                  0x00U
#define BOOT_FLAG_LAST                  0x01U
#define BOOT_FLAG_CRC                   0x02U
#define BOOT_FLAG_APP_ADDR              0x04U

#define ACK_OK                          0x00U
#define ACK_ERR_CMD                     0x01U
#define ACK_ERR_SEQ                     0x02U
#define ACK_ERR_STATE                   0x03U
#define ACK_ERR_ADDR                    0x04U
#define ACK_ERR_FLASH                   0x05U
#define ACK_ERR_CRC                     0x06U
#define ACK_ERR_FORMAT                  0x07U

#define RAM_BOOT_REQUEST_ADDR           0x200017FCUL
#define RAM_BOOT_REQUEST_MAGIC          0xB00710ADUL

#define APP_DEFAULT_START_ADDR          0x08002000UL
#define APP_MAX_END_ADDR                0x08007C00UL
#define FLASH_PAGE_SIZE                 1024UL

typedef struct
{
  uint8_t active;
  uint8_t transferDone;
  uint8_t expectedSeq;
  uint8_t pendingLowByteValid;
  uint8_t pendingLowByte;
  uint32_t appBaseAddr;
  uint32_t currentAddr;
  uint32_t erasedPageAddr;
  uint32_t crc32;
} BootSession_t;

static BootSession_t gBoot = {0};

/* USB HID transport integration using feature reports. */
uint8_t Boot_ReceivePacket(uint8_t *report, uint16_t *length)
{
  if (USBD_HID_GetFeatureOutReport(&hUsbDeviceFS, report, length) != USBD_OK)
  {
    return 0U;
  }
  return 1U;
}

void Boot_SendAck(uint8_t seq, uint8_t status)
{
  uint8_t out[HID_REPORT_SIZE];
  memset(out, 0, sizeof(out));
  out[0] = HID_REPORT_ID_STATUS_IN;
  out[1] = MSG_BOOT_ACK;
  out[2] = seq;
  out[3] = status;
  (void)USBD_HID_SetFeatureInReport(&hUsbDeviceFS, out, HID_REPORT_SIZE);
}

static uint32_t crc32_update(uint32_t crc, const uint8_t *data, uint16_t length)
{
  uint16_t i;
  uint8_t bit;

  for (i = 0U; i < length; i++)
  {
    crc ^= (uint32_t)data[i];
    for (bit = 0U; bit < 8U; bit++)
    {
      uint32_t mask = (uint32_t)(-(int32_t)(crc & 1U));
      crc = (crc >> 1U) ^ (0xEDB88320UL & mask);
    }
  }

  return crc;
}

static uint8_t Boot_IsValidAppAddress(uint32_t addr)
{
  if (addr < APP_DEFAULT_START_ADDR)
  {
    return 0U;
  }
  if (addr >= APP_MAX_END_ADDR)
  {
    return 0U;
  }
  return 1U;
}

static uint8_t Boot_ErasePageForAddress(uint32_t address)
{
  uint32_t pageAddr = address & ~(FLASH_PAGE_SIZE - 1UL);
  FLASH_EraseInitTypeDef erase = {0};
  uint32_t pageError = 0U;

  if (gBoot.erasedPageAddr == pageAddr)
  {
    return 1U;
  }

  erase.TypeErase = FLASH_TYPEERASE_PAGES;
  erase.PageAddress = pageAddr;
  erase.NbPages = 1U;

  if (HAL_FLASHEx_Erase(&erase, &pageError) != HAL_OK)
  {
    return 0U;
  }

  gBoot.erasedPageAddr = pageAddr;
  return 1U;
}

static uint8_t Boot_ProgramHalfWord(uint32_t address, uint16_t value)
{
  if (Boot_IsValidAppAddress(address) == 0U)
  {
    return 0U;
  }
  if ((address + 1U) >= APP_MAX_END_ADDR)
  {
    return 0U;
  }

  if (Boot_ErasePageForAddress(address) == 0U)
  {
    return 0U;
  }

  return (HAL_FLASH_Program(FLASH_TYPEPROGRAM_HALFWORD, address, value) == HAL_OK) ? 1U : 0U;
}

static uint8_t Boot_WriteBytes(const uint8_t *data, uint16_t length, uint8_t isLastChunk)
{
  uint16_t i;

  for (i = 0U; i < length; i++)
  {
    if (gBoot.pendingLowByteValid == 0U)
    {
      gBoot.pendingLowByte = data[i];
      gBoot.pendingLowByteValid = 1U;
    }
    else
    {
      uint16_t halfWord = (uint16_t)gBoot.pendingLowByte | ((uint16_t)data[i] << 8U);
      if (Boot_ProgramHalfWord(gBoot.currentAddr, halfWord) == 0U)
      {
        return 0U;
      }
      gBoot.currentAddr += 2U;
      gBoot.pendingLowByteValid = 0U;
    }
  }

  if ((isLastChunk != 0U) && (gBoot.pendingLowByteValid != 0U))
  {
    uint16_t halfWord = (uint16_t)gBoot.pendingLowByte | 0xFF00U;
    if (Boot_ProgramHalfWord(gBoot.currentAddr, halfWord) == 0U)
    {
      return 0U;
    }
    gBoot.currentAddr += 2U;
    gBoot.pendingLowByteValid = 0U;
  }

  return 1U;
}

static void Boot_ResetSession(void)
{
  memset(&gBoot, 0, sizeof(gBoot));
  gBoot.appBaseAddr = APP_DEFAULT_START_ADDR;
  gBoot.currentAddr = APP_DEFAULT_START_ADDR;
  gBoot.erasedPageAddr = 0xFFFFFFFFUL;
  gBoot.crc32 = 0xFFFFFFFFUL;
}

static void Boot_JumpToApp(void)
{
  uint32_t appBase = gBoot.appBaseAddr;
  uint32_t appStack = *(uint32_t *)appBase;
  uint32_t appReset = *(uint32_t *)(appBase + 4U);

  if ((appStack & 0x2FFE0000UL) == 0x20000000UL)
  {
    typedef void (*pEntry)(void);
    __disable_irq();
    __set_MSP(appStack);
    ((pEntry)appReset)();
  }
}

static uint8_t Boot_CheckAndConsumeRequest(void)
{
  volatile uint32_t *req = (volatile uint32_t *)RAM_BOOT_REQUEST_ADDR;
  if (*req == RAM_BOOT_REQUEST_MAGIC)
  {
    *req = 0UL;
    return 1U;
  }
  return 0U;
}

static void Boot_HandleTransferPacket(const uint8_t *report, uint16_t length)
{
  uint8_t seq;
  uint8_t flags;
  uint16_t payloadLen;
  const uint8_t *payload;
  uint8_t expectedNextSeq;

  if ((length < 6U) || (report[0] != HID_REPORT_ID_CTRL_OUT) || (report[1] != CMD_BOOT_TRANSFER))
  {
    Boot_SendAck(0U, ACK_ERR_FORMAT);
    return;
  }

  seq = report[2];
  flags = report[3];
  payloadLen = (uint16_t)report[4] | ((uint16_t)report[5] << 8U);

  if ((6U + payloadLen) > length)
  {
    Boot_SendAck(seq, ACK_ERR_FORMAT);
    return;
  }

  payload = &report[6];
  expectedNextSeq = (uint8_t)((gBoot.expectedSeq + 1U) & 0xFFU);
  if (seq != expectedNextSeq)
  {
    Boot_SendAck(seq, ACK_ERR_SEQ);
    return;
  }
  gBoot.expectedSeq = seq;

  if (flags == BOOT_FLAG_APP_ADDR)
  {
    uint32_t appAddr;

    if (payloadLen != 4U)
    {
      Boot_SendAck(seq, ACK_ERR_FORMAT);
      return;
    }

    appAddr = (uint32_t)payload[0] |
              ((uint32_t)payload[1] << 8U) |
              ((uint32_t)payload[2] << 16U) |
              ((uint32_t)payload[3] << 24U);

    if ((Boot_IsValidAppAddress(appAddr) == 0U) || ((appAddr & 1U) != 0U))
    {
      Boot_SendAck(seq, ACK_ERR_ADDR);
      return;
    }

    Boot_ResetSession();
    gBoot.active = 1U;
    gBoot.appBaseAddr = appAddr;
    gBoot.currentAddr = appAddr;
    Boot_SendAck(seq, ACK_OK);
    return;
  }

  if (flags == BOOT_FLAG_CRC)
  {
    uint32_t hostCrc;
    uint32_t calcCrc;

    if ((gBoot.active == 0U) || (gBoot.transferDone == 0U) || (payloadLen != 4U))
    {
      Boot_SendAck(seq, ACK_ERR_STATE);
      return;
    }

    hostCrc = (uint32_t)payload[0] |
              ((uint32_t)payload[1] << 8U) |
              ((uint32_t)payload[2] << 16U) |
              ((uint32_t)payload[3] << 24U);
    calcCrc = gBoot.crc32 ^ 0xFFFFFFFFUL;

    if (hostCrc != calcCrc)
    {
      Boot_SendAck(seq, ACK_ERR_CRC);
      return;
    }

    Boot_SendAck(seq, ACK_OK);
    HAL_FLASH_Lock();
    HAL_Delay(20U);
    Boot_JumpToApp();
    return;
  }

  if ((flags == BOOT_FLAG_MORE) || (flags == BOOT_FLAG_LAST))
  {
    if (gBoot.active == 0U)
    {
      Boot_SendAck(seq, ACK_ERR_STATE);
      return;
    }

    if ((payloadLen == 0U) || (payloadLen > 27U))
    {
      Boot_SendAck(seq, ACK_ERR_FORMAT);
      return;
    }

    gBoot.crc32 = crc32_update(gBoot.crc32, payload, payloadLen);
    if (Boot_WriteBytes(payload, payloadLen, (flags == BOOT_FLAG_LAST) ? 1U : 0U) == 0U)
    {
      Boot_SendAck(seq, ACK_ERR_FLASH);
      return;
    }

    if (flags == BOOT_FLAG_LAST)
    {
      gBoot.transferDone = 1U;
    }

    Boot_SendAck(seq, ACK_OK);
    return;
  }

  Boot_SendAck(seq, ACK_ERR_CMD);
}

int main(void)
{
  uint8_t report[HID_REPORT_SIZE] = {0U};
  uint16_t length = 0U;

  HAL_Init();

  if (Boot_CheckAndConsumeRequest() == 0U)
  {
    Boot_JumpToApp();
  }

  Boot_ResetSession();
  HAL_FLASH_Unlock();

  while (1)
  {
    if (Boot_ReceivePacket(report, &length) != 0U)
    {
      Boot_HandleTransferPacket(report, length);
    }
  }
}
