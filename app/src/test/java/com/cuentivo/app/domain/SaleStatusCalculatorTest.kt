package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SaleStatusCalculatorTest {
    @Test
    fun `marks an unpaid past due sale as overdue`() {
        assertEquals(
            SalePaymentStatus.OVERDUE,
            SaleStatusCalculator.status(totalCents = 1_000, paidCents = 0, dueDateEpochDay = 10, todayEpochDay = 11),
        )
    }

    @Test
    fun `marks a partly paid sale as partially paid`() {
        assertEquals(
            SalePaymentStatus.PARTIALLY_PAID,
            SaleStatusCalculator.status(totalCents = 1_000, paidCents = 250, dueDateEpochDay = 20, todayEpochDay = 10),
        )
    }

    @Test fun `marks a cancelled sale as cancelled`() {
        assertEquals(SalePaymentStatus.CANCELED, SaleStatusCalculator.status(totalCents = 1_000, paidCents = 0, dueDateEpochDay = 10, todayEpochDay = 11, isCanceled = true))
    }
}
