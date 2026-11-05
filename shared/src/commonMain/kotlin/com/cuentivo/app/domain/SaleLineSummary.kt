package com.cuentivo.app.domain

/** One sold line as the statement needs it: what it was called that day, and how many. */
data class SoldLine(
    val productName: String,
    val quantity: Long,
)

/**
 * Turns a sale's lines into one short phrase: `"2 Jabón Zote, Crema Nivea"`.
 *
 * A charge that says only "Venta $400" is unreadable for the person receiving it on WhatsApp —
 * they cannot tell their own debt apart from someone else's. This is what goes under the amount,
 * so it stays deliberately short: the full breakdown lives in the sale, not in a text message.
 *
 * Pure Kotlin, no Android: the same phrase feeds the shared message and the in-app history, and
 * two builders would drift.
 */
object SaleLineSummary {
    private const val MAX_NAMES = 3

    /** Null when there is nothing to say, so callers can skip the line entirely. */
    fun describe(lines: List<SoldLine>): String? {
        val named = lines.filter { it.productName.isNotBlank() && it.quantity > 0 }
        if (named.isEmpty()) return null
        // "1 Crema" reads like a count someone bothered to write down; "Crema" is what a person says.
        val shown = named.take(MAX_NAMES).joinToString(", ") { line ->
            if (line.quantity == 1L) line.productName else "${line.quantity} ${line.productName}"
        }
        val rest = named.size - MAX_NAMES
        return if (rest > 0) "$shown y $rest más" else shown
    }
}
