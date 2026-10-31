package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * pesosText prefills the edit forms, and whatever it writes goes straight back through
 * centsFromPesos when the person saves. If the round trip ever loses a cent, editing a product
 * would silently reprice it.
 */
class MoneyPesosTextTest {

    @Test
    fun `pesos text keeps both decimals and no currency symbol`() {
        assertEquals("150.50", Money.pesosText(15_050))
        assertEquals("0.05", Money.pesosText(5))
        assertEquals("1234567.89", Money.pesosText(123_456_789))
    }

    @Test
    fun `pesos text round-trips back to the same cents`() {
        listOf(1L, 5L, 99L, 100L, 15_050L, 123_456_789L).forEach { cents ->
            assertEquals(cents, Money.centsFromPesos(Money.pesosText(cents)))
        }
    }
}
