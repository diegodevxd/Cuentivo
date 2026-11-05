package com.cuentivo.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * El texto de pesos es la frontera entre lo que la persona escribe y los centavos enteros. Antes lo
 * resolvía `BigDecimal`; ahora lo resuelve aritmética entera en común, y estos casos son los que
 * tienen que seguir coincidiendo en Android y en iOS.
 */
class MoneyDecimalTextCommonTest {

    @Test
    fun plain_pesos_round_trip() {
        listOf(0L, 1L, 5L, 99L, 100L, 15_050L, 123_456_789L).forEach { cents ->
            assertEquals(cents, Money.centsFromPesos(Money.pesosText(cents)))
        }
    }

    @Test
    fun text_keeps_two_decimals_and_the_sign() {
        assertEquals("0.05", Money.pesosText(5))
        assertEquals("-0.05", Money.pesosText(-5))
        assertEquals("-150.50", Money.pesosText(-15_050))
        assertEquals("1234567.89", Money.pesosText(123_456_789))
    }

    @Test
    fun trailing_zeros_beyond_cents_are_still_exact() {
        assertEquals(123L, Money.centsFromPesos("1.230"))
        assertEquals(1_000L, Money.centsFromPesos("10"))
        assertEquals(50L, Money.centsFromPesos(".5"))
        assertEquals(1_000L, Money.centsFromPesos("10."))
        assertEquals(-5L, Money.centsFromPesos(" -0.05 "))
    }

    @Test
    fun a_third_decimal_is_refused_instead_of_truncated() {
        assertNull(Money.centsFromPesos("10.005"))
        assertNull(Money.centsFromPesos("0.001"))
    }

    @Test
    fun invalid_text_is_null() {
        listOf("", "   ", "abc", ".", "+.", "1,50", "10 20", "--1").forEach { text ->
            assertNull(Money.centsFromPesos(text), "expected null for '$text'")
        }
    }

    @Test
    fun exponent_notation_behaves_like_big_decimal() {
        assertEquals(100_000L, Money.centsFromPesos("1E3"))
        assertEquals(100_000L, Money.centsFromPesos("1e+3"))
        assertEquals(1L, Money.centsFromPesos("1e-2"))
    }

    @Test
    fun amounts_that_do_not_fit_in_a_long_are_null() {
        assertNull(Money.centsFromPesos("92233720368547758.09"))
    }

    @Test
    fun positive_parser_rejects_zero_and_negatives() {
        assertNull(Money.positiveCentsFromPesos("0"))
        assertNull(Money.positiveCentsFromPesos("-1"))
        assertEquals(100L, Money.positiveCentsFromPesos("1"))
    }
}
