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

    var exponent = 0L
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
        // El exponente necesita al menos un dígito DESPUÉS del signo opcional: `BigDecimal("1e+")`
        // y `BigDecimal("1e-")` lanzan, así que aquí también se rechazan.
        if (index >= text.length || !text[index].isAsciiDigit()) return null
        var magnitude = 0L
        while (index < text.length && text[index].isAsciiDigit()) {
            // Se deja de acumular en cuanto pasa del máximo aceptable: más dígitos ya no cambian
            // la respuesta y un Long no llega a desbordarse.
            if (magnitude <= MAX_EXPONENT_MAGNITUDE) {
                magnitude = magnitude * 10 + (text[index] - '0')
            }
            index++
        }
        // `BigDecimal` lanza Exponent overflow pasado este límite, y es asimétrico: acepta
        // 2147483648 con signo + pero sólo 2147483647 con signo -.
        val maxExponent = if (exponentNegative) 2_147_483_647L else MAX_EXPONENT_MAGNITUDE
        if (magnitude > maxExponent) return null
        exponent = if (exponentNegative) -magnitude else magnitude
    }
    if (index != text.length) return null

    // La magnitud se acumula en ULong porque el caso exacto de `Long.MIN_VALUE` no cabe en el
    // positivo de un Long: `-92233720368547758.08` pesos son -9223372036854775808 centavos y
    // `BigDecimal.longValueExact()` los acepta. Con Long se rechazarían de más.
    var magnitude = 0uL
    for (character in digits) {
        val digit = (character - '0').toULong()
        if (magnitude > (ULong.MAX_VALUE - digit) / 10uL) return null
        magnitude = magnitude * 10uL + digit
    }
    val limit = if (negative) LONG_MIN_MAGNITUDE else Long.MAX_VALUE.toULong()
    if (magnitude > limit) return null
    // Cero es cero con cualquier exponente: `BigDecimal("0e801669")` da 0, no lanza.
    if (magnitude == 0uL) return 0L

    val shift = 2L + exponent - fractionDigits
    if (shift >= 0) {
        // 10^19 ya supera cualquier magnitud válida; el tope evita un bucle de dos mil millones
        // de vueltas cuando el exponente es enorme. Con magnitudes no nulas el resultado es null.
        if (shift > 19) return null
        repeat(shift.toInt()) {
            if (magnitude > ULong.MAX_VALUE / 10uL) return null
            magnitude *= 10uL
        }
        if (magnitude > limit) return null
    } else {
        // El divisor más grande que puede dividir a una magnitud válida es 10^19.
        if (-shift > 19) return null
        var divisor = 1uL
        repeat((-shift).toInt()) {
            if (divisor > ULong.MAX_VALUE / 10uL) return null
            divisor *= 10uL
        }
        if (magnitude % divisor != 0uL) return null
        magnitude /= divisor
    }
    // La negación de `Long.MIN_VALUE` vuelve a dar `Long.MIN_VALUE` (complemento a dos), que es
    // justo el valor que corresponde a la magnitud 2^63. Cualquier otra magnitud cabe en su signo.
    val signed = magnitude.toLong()
    return if (negative) -signed else signed
}

/** `9223372036854775808`, el valor absoluto de `Long.MIN_VALUE`. */
private const val LONG_MIN_MAGNITUDE: ULong = 9223372036854775808uL

/** El exponente positivo más grande que acepta `BigDecimal` (`Integer.MAX_VALUE + 1`). */
private const val MAX_EXPONENT_MAGNITUDE: Long = 2_147_483_648L

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
