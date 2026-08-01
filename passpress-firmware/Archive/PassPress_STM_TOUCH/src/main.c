#include <stdint.h>

#define RCC_BASE        (0x40021000UL)
#define RCC_AHBENR      (*(volatile uint32_t *)(RCC_BASE + 0x14UL))

#define GPIOB_BASE      (0x48000400UL)
#define GPIOB_MODER     (*(volatile uint32_t *)(GPIOB_BASE + 0x00UL))
#define GPIOB_OTYPER    (*(volatile uint32_t *)(GPIOB_BASE + 0x04UL))
#define GPIOB_OSPEEDR   (*(volatile uint32_t *)(GPIOB_BASE + 0x08UL))
#define GPIOB_PUPDR     (*(volatile uint32_t *)(GPIOB_BASE + 0x0CUL))
#define GPIOB_ODR       (*(volatile uint32_t *)(GPIOB_BASE + 0x14UL))

#define RCC_AHBENR_IOPBEN (1UL << 18)

static void delay(volatile uint32_t count)
{
    while (count--)
    {
        __asm volatile("nop");
    }
}

int main(void)
{
    RCC_AHBENR |= RCC_AHBENR_IOPBEN;

    GPIOB_MODER &= ~((3UL << (0U * 2U)) | (3UL << (1U * 2U)));
    GPIOB_MODER |=  ((1UL << (0U * 2U)) | (1UL << (1U * 2U)));

    GPIOB_OTYPER &= ~((1UL << 0U) | (1UL << 1U));

    GPIOB_OSPEEDR &= ~((3UL << (0U * 2U)) | (3UL << (1U * 2U)));
    GPIOB_OSPEEDR |=  ((1UL << (0U * 2U)) | (1UL << (1U * 2U)));

    GPIOB_PUPDR &= ~((3UL << (0U * 2U)) | (3UL << (1U * 2U)));

    while (1)
    {
        GPIOB_ODR ^= (1UL << 0U) | (1UL << 1U);
        delay(200000U);
    }
}
