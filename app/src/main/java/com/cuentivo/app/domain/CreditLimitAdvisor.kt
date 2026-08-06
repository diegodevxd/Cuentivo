package com.cuentivo.app.domain

/**
 * A snapshot of one customer's payment behavior, already scoped to one business. Read-only inputs
 * for [CreditLimitAdvisor.suggest] — nothing here writes anything.
 */
data class CreditHistory(
    val settledSaleCount: Int,
    val hasCanceledSale: Boolean,
    val hasLatePayment: Boolean,
    val largestSaleTotalCents: Long,
)

/**
 * Suggests raising a customer's credit limit after good behavior. It only ever suggests — the
 * owner decides, same as every other correction in this app. Nothing here calls into Room or
 * touches `creditLimitCents`; the caller applies the change only if the owner taps "Subir cupo".
 */
object CreditLimitAdvisor {
    private const val MIN_SETTLED_SALES = 3
    private const val CURRENT_LIMIT_MULTIPLIER = 1.5
    private const val LARGEST_SALE_MULTIPLIER = 1.2
    private const val ROUND_TO_CENTS = 10_000L // 100 pesos

    /**
     * Returns the suggested new limit in cents, or null if this customer hasn't earned one yet.
     *
     * Requires no cancelled sale and no late payment ever seen in [history] — a single strike
     * withholds the suggestion rather than merely lowering it, since a false "trusted" reading is
     * the one mistake that costs the owner real money.
     */
    fun suggest(currentCreditLimitCents: Long?, history: CreditHistory): Long? {
        // No current limit means the customer already has unlimited credit. "Suggesting" a number
        // here would impose the first ceiling they've ever had, not reward them with a higher one —
        // setting an initial limit is the owner's call to make, not this advisor's to propose.
        if (currentCreditLimitCents == null) return null
        if (history.settledSaleCount < MIN_SETTLED_SALES) return null
        if (history.hasCanceledSale || history.hasLatePayment) return null

        val fromCurrent = (currentCreditLimitCents * CURRENT_LIMIT_MULTIPLIER).toLong()
        val fromLargestSale = (history.largestSaleTotalCents * LARGEST_SALE_MULTIPLIER).toLong()
        val raw = maxOf(fromCurrent, fromLargestSale)
        val rounded = roundUpToNearest(raw, ROUND_TO_CENTS)

        // Nothing to suggest if the math lands at or below what the customer already has.
        return rounded.takeIf { it > currentCreditLimitCents }
    }

    private fun roundUpToNearest(cents: Long, step: Long): Long {
        if (cents <= 0) return step
        val remainder = cents % step
        return if (remainder == 0L) cents else cents + (step - remainder)
    }
}
