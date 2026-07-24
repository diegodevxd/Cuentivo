package com.cuentivo.app.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * The single conversion between integer cents and something a person reads.
 *
 * Lives in domain rather than UI so the share texts and the CSV builders format money the same way
 * as the screens, and so it can be tested without Android.
 */
object Money {
    private val MEXICO = Locale("es", "MX")

    fun format(cents: Long): String = NumberFormat.getCurrencyInstance(MEXICO)
        .format(BigDecimal.valueOf(cents, 2))

    /**
     * Parses pesos typed by a person ("150.50") into cents.
     *
     * longValueExact() refuses a third decimal instead of truncating it: that is the door through
     * which fractional money would enter an integer-cents system. Returns null on anything invalid.
     */
    fun centsFromPesos(text: String): Long? = try {
        BigDecimal(text.trim()).movePointRight(2).longValueExact()
    } catch (error: Exception) {
        null
    }

    /** Same as [centsFromPesos] but rejects zero and negatives, for amounts that must be charged. */
    fun positiveCentsFromPesos(text: String): Long? = centsFromPesos(text)?.takeIf { it > 0 }
}
