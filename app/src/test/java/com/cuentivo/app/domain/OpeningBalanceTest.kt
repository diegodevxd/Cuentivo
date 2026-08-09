package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningBalanceTest {

    private fun entry(amountCents: Long = 50_000, dueInDays: Long? = null) = OpeningBalance.entry(
        saleId = "sale-1",
        itemId = "item-1",
        chargeId = "charge-1",
        businessId = "biz",
        customerId = "cust",
        ownerUserId = "owner",
        amountCents = amountCents,
        todayEpochDay = 20_000,
        nowEpochMillis = 1_700_000_000_000,
        dueInDays = dueInDays,
    )

    @Test
    fun `the sale satisfies the arithmetic the firestore rules check`() {
        val sale = entry().sale
        assertEquals(50_000, sale.subtotalCents)
        assertEquals(0, sale.discountCents)
        assertEquals(sale.subtotalCents - sale.discountCents, sale.totalCents)
    }

    @Test
    fun `the line carries no catalog product and satisfies the item arithmetic`() {
        val item = entry().item
        assertNull(item.productId)
        assertEquals(OpeningBalance.LINE_NAME, item.productNameSnapshot)
        assertEquals(1L, item.quantity)
        assertEquals(item.quantity * item.unitPriceCents - item.discountCents, item.subtotalCents)
    }

    @Test
    fun `the movement is the debit the sale dao demands`() {
        val result = entry()
        assertEquals("CHARGE", result.charge.type)
        assertEquals("DEBIT", result.charge.direction)
        // createSaleIdempotently requires exactly this, and refuses the write otherwise.
        assertEquals(result.sale.totalCents, result.charge.amountCents)
        assertEquals(result.sale.id, result.charge.saleId)
    }

    @Test
    fun `with no agreed date the sale can never be late`() {
        assertNull(entry().sale.dueDateEpochDay)
    }

    @Test
    fun `an agreed date is resolved against the day the caller supplied`() {
        assertEquals(20_015L, entry(dueInDays = 15).sale.dueDateEpochDay)
    }

    @Test
    fun `the same inputs always produce the same rows`() {
        assertEquals(entry(), entry())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a balance of zero is refused`() {
        entry(amountCents = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative balance is refused`() {
        entry(amountCents = -1)
    }
}
