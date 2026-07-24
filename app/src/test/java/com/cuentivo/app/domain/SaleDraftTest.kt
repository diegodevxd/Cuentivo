package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleDraftTest {

    private fun line(id: String, quantity: Int, unitPriceCents: Long, discountCents: Long = 0) =
        SaleDraftLine(id, "Producto $id", quantity, unitPriceCents, discountCents)

    @Test
    fun `line subtotal multiplies quantity and subtracts its discount`() {
        assertEquals(29_700, line("a", 3, 10_000, 300).subtotalCents)
    }

    @Test
    fun `sale with several products sums every line`() {
        val draft = SaleDraft().withLine(line("a", 2, 15_050)).withLine(line("b", 1, 9_900))
        assertEquals(40_000, draft.subtotalCents)
        assertEquals(40_000, draft.totalCents)
    }

    @Test
    fun `whole-sale discount applies on top of the subtotal`() {
        val draft = SaleDraft(discountCents = 5_000).withLine(line("a", 1, 30_000))
        assertEquals(30_000, draft.subtotalCents)
        assertEquals(25_000, draft.totalCents)
    }

    @Test
    fun `adding the same product again raises its quantity instead of duplicating the row`() {
        val draft = SaleDraft().withLine(line("a", 1, 10_000)).withLine(line("a", 2, 10_000))
        assertEquals(1, draft.lines.size)
        assertEquals(3, draft.lines.single().quantity)
        assertEquals(30_000, draft.totalCents)
    }

    @Test
    fun `removing a product drops only that line`() {
        val draft = SaleDraft().withLine(line("a", 1, 10_000)).withLine(line("b", 1, 5_000))
        assertEquals(5_000, draft.withoutProduct("a").totalCents)
    }

    @Test
    fun `an empty cart cannot be saved`() {
        assertEquals("Agrega al menos un producto", SaleDraft().validationError())
    }

    @Test
    fun `a discount larger than the sale is rejected`() {
        val draft = SaleDraft(discountCents = 40_000).withLine(line("a", 1, 30_000))
        assertEquals("El descuento no puede ser mayor que la venta", draft.validationError())
    }

    @Test
    fun `a valid cart reports no error`() {
        assertNull(SaleDraft().withLine(line("a", 1, 30_000)).validationError())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `quantity zero is refused at construction`() {
        line("a", 0, 10_000)
    }

    @Test
    fun `totals stay exact cents with awkward prices`() {
        // 3 x $33.33 is the classic place a Double would drift.
        val draft = SaleDraft().withLine(line("a", 3, 3_333))
        assertEquals(9_999, draft.totalCents)
        assertTrue(draft.totalCents % 1 == 0L)
    }
}
