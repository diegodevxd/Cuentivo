package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSalePolicyTest {
    private fun read(name: String = "", price: String = "", quantity: String = "1", photo: String? = null) =
        QuickSalePolicy.read(name, price, quantity, photo)

    @Test fun an_untouched_section_saves_an_ordinary_customer() {
        assertEquals(QuickSaleEntry.Empty, read())
    }

    @Test fun a_complete_section_becomes_a_sale() {
        val entry = read(name = "Jabón Zote", price = "25.50", quantity = "2")
        assertEquals(QuickSaleEntry.Ready(QuickSale("Jabón Zote", 2_550, 2, null)), entry)
        assertEquals(5_100, (entry as QuickSaleEntry.Ready).sale.totalCents)
    }

    /**
     * The important half: someone who typed a product and forgot the price meant to sell something.
     * Saving a bare customer there would silently drop what they were writing down.
     */
    @Test fun a_half_filled_section_is_not_treated_as_untouched() {
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

    @Test fun the_photo_travels_with_the_sale() {
        val entry = read(name = "Crema", price = "80", photo = "product-1.jpg") as QuickSaleEntry.Ready
        assertEquals("product-1.jpg", entry.sale.photoFileName)
        assertEquals(1, entry.sale.quantity)
    }
}
