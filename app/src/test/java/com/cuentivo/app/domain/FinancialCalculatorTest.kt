package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialCalculatorTest {

    @Test
    fun `calculates a customer balance from charges and payments in cents`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 12_550),
                AccountMovement(MovementType.CHARGE, 2_000),
                AccountMovement(MovementType.PAYMENT, 4_250),
            ),
        )

        assertEquals(10_300, balance)
    }

    @Test
    fun `keeps an overpayment as a negative customer balance`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 1_000),
                AccountMovement(MovementType.PAYMENT, 1_250),
            ),
        )

        assertEquals(-250, balance)
    }

    @Test
    fun `computes a sale total without floating point arithmetic`() {
        val total = FinancialCalculator.saleTotal(
            items = listOf(
                SaleItem(quantity = 2, unitPriceCents = 1_999),
                SaleItem(quantity = 1, unitPriceCents = 250),
            ),
            discountCents = 149,
        )

        assertEquals(4_099, total)
    }
}
