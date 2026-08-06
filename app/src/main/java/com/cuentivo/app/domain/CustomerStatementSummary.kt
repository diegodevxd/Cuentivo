package com.cuentivo.app.domain

/**
 * Anchors the customer detail screen with the three numbers testers said were missing: what they
 * were sold, what they've paid, and what's left. Pure integer arithmetic over cents, same
 * `direction` convention `FinancialDao.customerBalanceCents` uses in SQL — `balanceCents` here must
 * always equal that query for the same movements, which is exactly what
 * `CustomerStatementSummaryTest` checks.
 */
object CustomerStatementSummary {
    data class Totals(
        val totalChargedCents: Long,
        val totalPaidCents: Long,
        val balanceCents: Long,
    )

    fun totals(entries: List<StatementEntry>): Totals {
        var chargedCents = 0L
        var paidCents = 0L
        entries.forEach { entry ->
            when (entry.type) {
                // Netted, not gross: a CANCELLATION reverses a CHARGE and a REFUND reverses a
                // PAYMENT, so each has to come out of the total it undoes. Grossing them in as
                // their own DEBIT/CREDIT would make "Le vendiste" bigger than what was ever really
                // sold — exactly the ghost-purchase reading testers reported.
                "CHARGE" -> chargedCents += entry.amountCents
                "CANCELLATION" -> chargedCents -= entry.amountCents
                "PAYMENT" -> paidCents += entry.amountCents
                "REFUND" -> paidCents -= entry.amountCents
                // Unknown movement types (and ADJUSTMENT, which can go either way) fall back to
                // direction, same as the ledger itself — so a type this function doesn't special
                // case still keeps balanceCents in lockstep with FinancialDao.customerBalanceCents.
                else -> if (entry.direction == "DEBIT") {
                    chargedCents += entry.amountCents
                } else {
                    paidCents += entry.amountCents
                }
            }
        }
        return Totals(chargedCents, paidCents, chargedCents - paidCents)
    }

    /**
     * Balance after each entry, in the same order given. Entries must already be chronological —
     * same requirement `CustomerStatementCsv.build` has, and for the same reason.
     */
    fun runningBalances(entries: List<StatementEntry>): List<Long> {
        var balanceCents = 0L
        return entries.map { entry ->
            balanceCents += if (entry.direction == "DEBIT") entry.amountCents else -entry.amountCents
            balanceCents
        }
    }

    /**
     * Payment ids whose abono has since been reversed, derived only from the ledger's own REFUND
     * rows — no extra query needed. A `PAYMENT` row whose id shows up here is the "compra fantasma"
     * testers reported: it still belongs in the history, but the screen should mark it as undone
     * instead of leaving it to read like a second, unrelated charge.
     */
    fun canceledPaymentIds(entries: List<StatementEntry>): Set<String> =
        entries.filter { it.type == "REFUND" }.mapNotNull { it.paymentId }.toSet()
}
