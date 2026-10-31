package com.cuentivo.app.ui

import com.cuentivo.app.data.local.CancelablePaymentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentRefundFactoryTest {
    @Test
    fun `creates refund from payment row without mixing business and customer`() {
        val row = CancelablePaymentRow(
            id = "payment-1",
            businessId = "business-1",
            customerId = "customer-2",
            amountCents = 1250L,
            paymentMethod = "CASH",
            paymentDateEpochDay = 20_000L,
            reference = "ref",
            createdAtEpochMillis = 100L,
            blockedByCanceledSale = false,
        )

        val refund = PaymentRefundFactory.create(
            row = row,
            movementId = "movement-1",
            occurredAtEpochMillis = 200L,
            createdByUserId = "user-1",
        )

        assertEquals("movement-1", refund.id)
        assertEquals("business-1", refund.businessId)
        assertEquals("customer-2", refund.customerId)
        assertNull(refund.saleId)
        assertEquals("payment-1", refund.paymentId)
        assertEquals("REFUND", refund.type)
        assertEquals(1250L, refund.amountCents)
        assertEquals("DEBIT", refund.direction)
        assertEquals(200L, refund.occurredAtEpochMillis)
        assertEquals("user-1", refund.createdByUserId)
        assertNull(refund.reversesMovementId)
        assertEquals("Abono cancelado desde la app", refund.notes)
    }
}
