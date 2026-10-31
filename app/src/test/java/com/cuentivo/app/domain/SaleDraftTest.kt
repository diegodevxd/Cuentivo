package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleDraftTest {

    private var nextLineId = 0

    /** A catalog line. Every call gets its own line id, so merging can only happen by product. */
    private fun line(id: String, quantity: Int, unitPriceCents: Long, discountCents: Long = 0) =
        SaleDraftLine("l${nextLineId++}", id, "Producto $id", quantity, unitPriceCents, discountCents)

    /** A line the seller typed by hand: no catalog product behind it. */
    private fun manual(name: String, quantity: Int, unitPriceCents: Long) =
        SaleDraftLine("l${nextLineId++}", null, name, quantity, unitPriceCents)

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
        val removed = draft.lines.first { it.productId == "a" }
        assertEquals(5_000, draft.withoutLine(removed.lineId).totalCents)
    }

    @Test
    fun `a hand-written line needs no catalog product`() {
        val draft = SaleDraft().withLine(manual("Trapeador", 3, 8_000))
        assertNull(draft.lines.single().productId)
        assertEquals(24_000, draft.totalCents)
        assertNull(draft.validationError())
    }

    @Test
    fun `two hand-written lines with the same name stay separate rows`() {
        // Tapping the same catalog product twice is a slip; typing the same thing twice is not.
        val draft = SaleDraft().withLine(manual("Trapeador", 1, 8_000)).withLine(manual("Trapeador", 1, 8_000))
        assertEquals(2, draft.lines.size)
        assertEquals(16_000, draft.totalCents)
    }

    @Test
    fun `removing one hand-written line leaves its twin alone`() {
        val draft = SaleDraft().withLine(manual("Trapeador", 1, 8_000)).withLine(manual("Trapeador", 1, 8_000))
        val remaining = draft.withoutLine(draft.lines.first().lineId)
        assertEquals(1, remaining.lines.size)
        assertEquals(8_000, remaining.totalCents)
    }

    @Test
    fun `catalog and hand-written lines add up together`() {
        val draft = SaleDraft().withLine(line("a", 2, 10_000)).withLine(manual("Trapeador", 1, 8_000))
        assertEquals(28_000, draft.totalCents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a line with a blank name is refused at construction`() {
        manual("  ", 1, 8_000)
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
