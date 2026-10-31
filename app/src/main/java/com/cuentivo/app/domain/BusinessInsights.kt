package com.cuentivo.app.domain

/** What a business did in one stretch of time. Money in cents, like everywhere else. */
data class PeriodTotals(
    val soldCents: Long,
    val collectedCents: Long,
    val overdueCents: Long,
)

/** One sentence about how things are going, and whether it is good news. */
data class Insight(val text: String, val tone: Tone) {
    enum class Tone { GOOD, BAD, NEUTRAL }
}

/**
 * Turns two periods of real money into a sentence a shop owner can act on.
 *
 * Every number here comes from the ledger. Nothing is invented, and nothing is rounded into
 * flattery: when collection drops, this says so. A cheerful percentage that does not match what the
 * owner counted in their own hand is the fastest way to lose their trust in the whole app.
 *
 * Pure and integer-only — no Double ever touches money, same as the rest of the domain.
 */
object BusinessInsights {

    /**
     * Movements smaller than this are noise, not a trend.
     *
     * Telling someone they "improved 2%" because they sold one extra jar of cream is the kind of
     * meaningless praise that teaches people to ignore the card.
     */
    private const val MEANINGFUL_PERCENT = 5

    fun compare(current: PeriodTotals, previous: PeriodTotals): List<Insight> =
        listOfNotNull(collection(current, previous), overdue(current))

    private fun collection(current: PeriodTotals, previous: PeriodTotals): Insight? = when {
        // Nothing happened either month. Silence beats a card announcing "0% change".
        previous.collectedCents == 0L && current.collectedCents == 0L -> null

        // First month with real activity: there is genuinely nothing to compare against, so say that
        // instead of dividing by zero or pretending the increase is infinite.
        previous.collectedCents == 0L -> Insight(
            "Llevas ${Money.format(current.collectedCents)} cobrados este mes. " +
                "El mes que entra podrás compararte con este.",
            Insight.Tone.NEUTRAL,
        )

        else -> {
            val change = percentChange(current.collectedCents, previous.collectedCents)
            when {
                change >= MEANINGFUL_PERCENT -> Insight(
                    "Tu cobranza subió $change% respecto al mes pasado. ¡Sigue así!",
                    Insight.Tone.GOOD,
                )
                change <= -MEANINGFUL_PERCENT -> Insight(
                    "Tu cobranza bajó ${-change}% respecto al mes pasado. " +
                        "Revisa quién te quedó debiendo.",
                    Insight.Tone.BAD,
                )
                else -> Insight("Vas parejo con el mes pasado en cobranza.", Insight.Tone.NEUTRAL)
            }
        }
    }

    private fun overdue(current: PeriodTotals): Insight? =
        if (current.overdueCents <= 0L) null
        else Insight(
            "Tienes ${Money.format(current.overdueCents)} vencidos. Ese dinero ya se pasó de fecha.",
            Insight.Tone.BAD,
        )

    /**
     * Integer percentage change, truncated toward zero.
     *
     * [previous] is guaranteed non-zero by the callers above. Multiplying before dividing keeps the
     * precision that integer division would otherwise throw away.
     */
    private fun percentChange(current: Long, previous: Long): Int =
        ((current - previous) * 100 / previous).toInt()
}
