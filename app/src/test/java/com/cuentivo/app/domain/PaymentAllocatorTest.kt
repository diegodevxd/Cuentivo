package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentAllocatorTest {

    @Test
    fun `applies a general payment to overdue sales before newer sales`() {
        val allocation = PaymentAllocator.allocate(
            paymentCents = 10_000,
            sales = listOf(
                OutstandingSale(id = "newer", outstandingCents = 5_000, dueDateEpochDay = 20, saleDateEpochDay = 10),
                OutstandingSale(id = "overdue", outstandingCents = 7_500, dueDateEpochDay = 5, saleDateEpochDay = 1),
            ),
        )

        assertEquals(listOf(PaymentAllocation("overdue", 7_500), PaymentAllocation("newer", 2_500)), allocation.allocations)
        assertEquals(0, allocation.creditCents)
    }

    @Test
    fun `keeps an unallocated remainder as customer credit`() {
        val allocation = PaymentAllocator.allocate(
            paymentCents = 2_000,
            sales = listOf(OutstandingSale("sale", 1_250, null, 1)),
        )

        assertEquals(listOf(PaymentAllocation("sale", 1_250)), allocation.allocations)
        assertEquals(750, allocation.creditCents)
    }
}
