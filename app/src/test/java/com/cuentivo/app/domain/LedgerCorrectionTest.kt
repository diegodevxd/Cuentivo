package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerCorrectionTest {

    @Test
    fun `cancellation compensates a charge without deleting its history`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 3_000),
                AccountMovement(MovementType.CANCELLATION, 3_000),
            ),
        )

        assertEquals(0, balance)
    }

    @Test
    fun `refund restores debt after a payment is reversed`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 3_000),
                AccountMovement(MovementType.PAYMENT, 3_000),
                AccountMovement(MovementType.REFUND, 3_000),
            ),
        )

        assertEquals(3_000, balance)
    }

    @Test
    fun `a debit adjustment increases a customer balance`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 1_000),
                AccountMovement(MovementType.ADJUSTMENT_DEBIT, 250),
            ),
        )

        assertEquals(1_250, balance)
    }

    @Test
    fun `a credit adjustment reduces a customer balance`() {
        val balance = FinancialCalculator.customerBalance(
            movements = listOf(
                AccountMovement(MovementType.CHARGE, 1_000),
                AccountMovement(MovementType.ADJUSTMENT_CREDIT, 250),
            ),
        )

        assertEquals(750, balance)
    }
}
