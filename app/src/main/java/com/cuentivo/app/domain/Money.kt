package com.cuentivo.app.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * The single conversion between integer cents and something a person reads.
 *
 * Lives in domain rather than UI so the share texts and the CSV builders format money the same way
 * as the screens, and so it can be tested without Android.
 */
object Money {
    private val MEXICO = Locale("es", "MX")

    /**
     * Money is formatted in es-MX regardless of the UI language, on purpose.
     *
     * Switching the app to English must not change what an amount looks like: the business still
     * charges pesos, and `$3,000.00` is what the owner and their customers recognise. Formatting
     * under the UI locale instead would print `MX$3,000.00` in English — and only in the few places
     * that could reach a Compose locale, since 30-odd call sites live in `domain/` (the CSV, the
     * WhatsApp texts, the alerts) where there is no Compose context to read. The result would be
     * two different-looking amounts on the same screen.
     *
     * [currencyCode] stays a parameter because `BusinessEntity.currency` exists and a business
     * outside Mexico is a real future case; the locale deliberately does not.
     */
    fun format(cents: Long, currencyCode: String = "MXN"): String {
        val format = NumberFormat.getCurrencyInstance(MEXICO)
        format.currency = Currency.getInstance(currencyCode)
        return format.format(BigDecimal.valueOf(cents, 2))
    }

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

    /**
     * Cents as plain editable pesos ("150.50"), for prefilling an amount field.
     *
     * No currency symbol or grouping, unlike [format]: this text goes back through
     * [centsFromPesos] when the person saves, and it must round-trip to the same integer.
     */
    fun pesosText(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** Same as [centsFromPesos] but rejects zero and negatives, for amounts that must be charged. */
    fun positiveCentsFromPesos(text: String): Long? = centsFromPesos(text)?.takeIf { it > 0 }
}
