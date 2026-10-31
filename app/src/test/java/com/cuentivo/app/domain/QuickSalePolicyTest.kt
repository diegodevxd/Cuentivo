package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSalePolicyTest {
    private fun read(
        name: String = "",
        price: String = "",
        quantity: String = "1",
        photo: String? = null,
        productId: String? = null,
        lineId: String = "line-1",
    ) = QuickSalePolicy.read(lineId, productId, name, price, quantity, photo)

    @Test fun an_untouched_section_saves_an_ordinary_customer() {
        assertEquals(QuickSaleEntry.Empty, read())
    }

    @Test fun a_complete_row_becomes_a_line() {
        val entry = read(name = "Jabón Zote", price = "25.50", quantity = "2")
        assertEquals(QuickSaleEntry.Ready(QuickSaleLine("line-1", null, "Jabón Zote", 2_550, 2, null)), entry)
        assertEquals(5_100, (entry as QuickSaleEntry.Ready).line.totalCents)
    }

    /**
     * The bug this route had: every sale reached the ledger with no agreed date, so no customer
     * created this way could ever be late and the payment score had nothing to judge.
     */
    @Test fun a_quick_sale_cart_carries_an_agreed_date_by_default() {
        assertEquals(15L, QuickSaleCart().dueInDays)
    }

    /** Choosing "Sin fecha" is still allowed — it just has to be a choice, not the default. */
    @Test fun no_agreed_date_is_kept_when_it_was_chosen() {
        assertNull(QuickSaleCart(dueInDays = null).dueInDays)
    }

    /**
     * The important half: someone who typed a product and forgot the price meant to sell something.
     * Saving a bare customer there would silently drop what they were writing down.
     */
    @Test fun a_half_filled_row_is_not_treated_as_untouched() {
        assertEquals(QuickSaleEntry.Incomplete, read(name = "Jabón"))
        assertEquals(QuickSaleEntry.Incomplete, read(price = "25.00"))
        assertEquals(QuickSaleEntry.Incomplete, read(name = "Jabón", price = "0"))
        assertEquals(QuickSaleEntry.Incomplete, read(name = "Jabón", price = "abc"))
        assertEquals(QuickSaleEntry.Incomplete, read(name = "Jabón", price = "25", quantity = "0"))
        assertEquals(QuickSaleEntry.Incomplete, read(name = "Jabón", price = "25", quantity = ""))
    }

    /** A photo was already written to disk, so it counts as started even with nothing typed. */
    @Test fun a_photo_on_its_own_counts_as_started() {
        assertEquals(QuickSaleEntry.Incomplete, read(photo = "product-1.jpg"))
    }

    @Test fun the_photo_travels_with_the_line() {
        val entry = read(name = "Crema", price = "80", photo = "product-1.jpg") as QuickSaleEntry.Ready
        assertEquals("product-1.jpg", entry.line.photoFileName)
        assertEquals(1, entry.line.quantity)
    }

    @Test fun a_row_taken_from_the_catalog_keeps_its_product() {
        val entry = read(name = "Jabón", price = "25", productId = "prod-1") as QuickSaleEntry.Ready
        assertEquals("prod-1", entry.line.productId)
    }

    // --- the cart itself: what made this form able to hold a real order ---

    private fun line(id: String, productId: String?, name: String, price: Long, quantity: Int = 1) =
        QuickSaleLine(id, productId, name, price, quantity, null)

    @Test fun a_cart_adds_up_every_line() {
        val cart = QuickSaleCart()
            .withLine(line("l1", "p1", "Jabón", 2_550, 2))
            .withLine(line("l2", null, "Trapeador", 8_000))
        assertEquals(2, cart.lines.size)
        assertEquals(13_100, cart.totalCents)
    }

    @Test fun the_same_catalog_product_twice_raises_its_quantity() {
        val cart = QuickSaleCart()
            .withLine(line("l1", "p1", "Jabón", 2_500))
            .withLine(line("l2", "p1", "Jabón", 2_500, 2))
        assertEquals(1, cart.lines.size)
        assertEquals(3, cart.lines.single().quantity)
        assertEquals(7_500, cart.totalCents)
    }

    @Test fun two_hand_written_rows_with_the_same_name_stay_separate() {
        val cart = QuickSaleCart()
            .withLine(line("l1", null, "Jabón", 2_500))
            .withLine(line("l2", null, "Jabón", 2_500))
        assertEquals(2, cart.lines.size)
        assertEquals(5_000, cart.totalCents)
    }

    @Test fun removing_a_row_drops_only_that_one() {
        val cart = QuickSaleCart()
            .withLine(line("l1", null, "Jabón", 2_500))
            .withLine(line("l2", null, "Trapeador", 8_000))
        assertEquals(8_000, cart.withoutLine("l1").totalCents)
    }

    @Test fun an_empty_cart_reads_as_empty() {
        assertEquals(true, QuickSaleCart().isEmpty)
        assertEquals(0, QuickSaleCart().totalCents)
    }

    @Test fun the_cart_reports_the_photos_it_is_holding_so_cancelling_can_clean_up() {
        val cart = QuickSaleCart()
            .withLine(QuickSaleLine("l1", null, "Crema", 8_000, 1, "a.jpg"))
            .withLine(QuickSaleLine("l2", null, "Jabón", 2_500, 1, null))
        assertEquals(listOf("a.jpg"), cart.photoFileNames())
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_due_date_of_today_or_earlier_is_refused() {
        QuickSaleCart(dueInDays = 0)
    }
}
