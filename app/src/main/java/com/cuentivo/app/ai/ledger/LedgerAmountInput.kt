package com.cuentivo.app.ai.ledger

/**
 * The amount field of the review dialog, in the units the notebook is written in.
 *
 * It used to show the raw cents string straight from the model. So a page that said "$999" and was
 * misread as 99.90 arrived at the person as **9990** -- four digits that look almost exactly like
 * the three on the paper, in a dialog whose whole job is comparing the two. It got confirmed. That
 * is not a mistake the person made; it is a question that was asked in the wrong units.
 *
 * Pesos in, pesos out. The cents conversion happens here, once, where it can be tested.
 */
object LedgerAmountInput {

    /** Cents as the model sends them, shown the way the notebook writes them. */
    fun toPesosText(amountCents: String?): String {
        val cents = amountCents?.toLongOrNull() ?: return amountCents.orEmpty()
        if (cents < 0L) return amountCents
        val pesos = cents / 100L
        val remainder = cents % 100L
        return if (remainder == 0L) pesos.toString() else "$pesos." + remainder.toString().padStart(2, '0')
    }

    /**
     * What the person typed, back into canonical cents, or null when it is not an amount.
     *
     * Null is a refusal, never a guess: the caller keeps the save button off rather than writing a
     * number nobody typed.
     */
    fun toCents(pesosText: String): String? {
        val cleaned = normalizeSeparators(pesosText) ?: return null
        if (!AMOUNT_RE.matches(cleaned)) return null

        val whole = cleaned.substringBefore('.')
        val fraction = cleaned.substringAfter('.', "").padEnd(2, '0')
        return try {
            val cents = Math.addExact(
                Math.multiplyExact(whole.toLong(), 100L),
                fraction.toLong(),
            )
            cents.toString()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Strips what people write around a price and settles what a comma means.
     *
     * A comma with exactly two digits behind it and nothing else is decimal ("99,90"); anywhere else
     * it is grouping ("1,250"). Both readings exist in the world, and this is the one that matches
     * what is on screen next to it -- the person can always see the result and correct it.
     */
    private fun normalizeSeparators(raw: String): String? {
        val text = raw.trim().replace("$", "").replace(" ", "").replace("\u00A0", "")
        if (text.isEmpty()) return null
        return when {
            text.contains('.') && text.contains(',') -> text.replace(",", "")
            text.count { it == ',' } == 1 && text.substringAfter(',').length == 2 -> text.replace(',', '.')
            else -> text.replace(",", "")
        }
    }

    private val AMOUNT_RE = Regex("""^[0-9]+(\.[0-9]{1,2})?$""")
}
