package com.cuentivo.app.domain

/**
 * The single conversion between integer cents and something a person reads.
 *
 * Lives in domain rather than UI so the share texts and the CSV builders format money the same way
 * as the screens, and so it can be tested without Android.
 */
object Money {

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
     *
     * The formatting itself is per platform ([formatMoney]): Android keeps the `NumberFormat`
     * es-MX that has always produced these strings, iOS uses the equivalent `NSNumberFormatter`.
     * Cents never become a `Double` on the way.
     */
    fun format(cents: Long, currencyCode: String = "MXN"): String = formatMoney(cents, currencyCode)

    /**
     * Parses pesos typed by a person ("150.50") into cents.
     *
     * Refuses a third decimal instead of truncating it: that is the door through which fractional
     * money would enter an integer-cents system. Returns null on anything invalid.
     *
     * The arithmetic is the one `BigDecimal(text).movePointRight(2).longValueExact()` used to do:
     * a decimal with more decimals than cents allows is rejected unless the extra digits are zeros
     * ("1.230" is still 123 cents), and anything that does not fit in a `Long` is rejected too.
     */
    fun centsFromPesos(text: String): Long? = centsFromDecimalText(text)

    /**
     * Cents as plain editable pesos ("150.50"), for prefilling an amount field.
     *
     * No currency symbol or grouping, unlike [format]: this text goes back through
     * [centsFromPesos] when the person saves, and it must round-trip to the same integer.
     */
    fun pesosText(cents: Long): String = decimalTextFromCents(cents)

    /** Same as [centsFromPesos] but rejects zero and negatives, for amounts that must be charged. */
    fun positiveCentsFromPesos(text: String): Long? = centsFromPesos(text)?.takeIf { it > 0 }
}

/** `$1,234.50` style, per platform. Never receives nor returns a decimal point number. */
internal expect fun formatMoney(cents: Long, currencyCode: String): String

/**
 * `BigDecimal.valueOf(cents, 2).toPlainString()`, with integer arithmetic only.
 *
 * Cents are always two decimals, so this is sign + integer part + fraction; the sign is taken from
 * the value itself, which is why `-5` cents prints `-0.05` and not `0.-5`.
 */
internal fun decimalTextFromCents(cents: Long): String {
    val units = cents / 100
    val rawFraction = cents % 100
    val fraction = if (rawFraction < 0) -rawFraction else rawFraction
    val fractionText = if (fraction < 10) "0$fraction" else fraction.toString()
    return if (cents < 0 && units == 0L) "-0.$fractionText" else "$units.$fractionText"
}

/**
 * The parsing half of [Money.centsFromPesos]: a decimal literal times 100, exactly.
 *
 * Accepts what `BigDecimal` accepted — optional sign, digits with an optional fraction, optional
 * exponent — and returns null where `longValueExact()` threw: a non-zero digit past the second
 * decimal, or a result that does not fit in a `Long`.
 */
internal fun centsFromDecimalText(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    var index = 0
    var negative = false
    when (text[index]) {
        '+' -> index++
        '-' -> {
            negative = true
            index++
        }
    }

    val digits = StringBuilder()
    var integerDigits = 0
    while (index < text.length && text[index].isAsciiDigit()) {
        digits.append(text[index])
        integerDigits++
        index++
    }
    var fractionDigits = 0
    if (index < text.length && text[index] == '.') {
        index++
        while (index < text.length && text[index].isAsciiDigit()) {
            digits.append(text[index])
            fractionDigits++
            index++
        }
    }
    // "." and "+." carry no number at all; "5." and ".5" do.
    if (integerDigits == 0 && fractionDigits == 0) return null

    var exponent = 0
    if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
        index++
        var exponentNegative = false
        when {
            index < text.length && text[index] == '+' -> index++
            index < text.length && text[index] == '-' -> {
                exponentNegative = true
                index++
            }
        }
        if (index >= text.length) return null
        var magnitude = 0
        while (index < text.length && text[index].isAsciiDigit()) {
            magnitude = magnitude * 10 + (text[index] - '0')
            // Anything this large overflows a Long after scaling anyway.
            if (magnitude > 100_000) return null
            index++
        }
        exponent = if (exponentNegative) -magnitude else magnitude
    }
    if (index != text.length) return null

    var value = 0L
    for (character in digits) {
        val digit = character - '0'
        if (value > (Long.MAX_VALUE - digit) / 10) return null
        value = value * 10 + digit
    }
    if (value == 0L) return 0L

    val shift = 2 + exponent - fractionDigits
    if (shift >= 0) {
        repeat(shift) {
            if (value > Long.MAX_VALUE / 10) return null
            value *= 10
        }
    } else {
        var divisor = 1L
        repeat(-shift) {
            // Beyond 18 zeros the divisor itself overflows; only an exact zero survives, and that
            // case already returned above.
            if (divisor > Long.MAX_VALUE / 10) return null
            divisor *= 10
        }
        if (value % divisor != 0L) return null
        value /= divisor
    }
    return if (negative) -value else value
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
