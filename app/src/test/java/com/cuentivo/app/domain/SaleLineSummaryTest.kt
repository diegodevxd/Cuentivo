package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaleLineSummaryTest {
    @Test
    fun a_single_unit_is_named_without_a_count() {
        assertEquals("Crema Nivea", SaleLineSummary.describe(listOf(SoldLine("Crema Nivea", 1))))
    }

    @Test
    fun several_units_keep_their_quantity() {
        assertEquals(
            "2 Jabón Zote, Crema Nivea",
            SaleLineSummary.describe(listOf(SoldLine("Jabón Zote", 2), SoldLine("Crema Nivea", 1))),
        )
    }

    /** A long sale has to stay one line: this goes into a WhatsApp message, not a receipt. */
    @Test
    fun past_three_products_the_rest_are_counted() {
        val lines = listOf(
            SoldLine("Jabón", 1),
            SoldLine("Crema", 1),
            SoldLine("Shampoo", 1),
            SoldLine("Cepillo", 1),
            SoldLine("Pasta", 3),
        )
        assertEquals("Jabón, Crema, Shampoo y 2 más", SaleLineSummary.describe(lines))
    }

    @Test
    fun nothing_worth_saying_returns_null_so_the_line_is_skipped() {
        assertNull(SaleLineSummary.describe(emptyList()))
        assertNull(SaleLineSummary.describe(listOf(SoldLine("  ", 2), SoldLine("Jabón", 0))))
    }
}
