package com.cuentivo.app.ui.customer

/** One history row, read from the customer's side of the ledger. */
internal data class LedgerVisual(
    val label: String,
    val sign: String,
    val caption: String,
    val tone: LedgerTone,
)

internal enum class LedgerTone {
    /** Raises what this customer owes. */
    DEBT,

    /** Lowers what this customer owes. */
    CREDIT,
}

/**
 * Translates a movement into the customer's point of view.
 *
 * This is deliberately the opposite framing from the dashboard feed. Inicio answers "did money move
 * through the till today", so a credit sale is unsigned there. A customer's history answers "what
 * does this person owe me and why", so the same sale is a negative, red `Deuda` line. Both readings
 * are correct for their screen, and keeping them in two named policies is what stops one from
 * quietly drifting into the other.
 *
 * The sign therefore follows `direction`, which `FinancialDao` defines as
 * `CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END`: DEBIT raises the debt,
 * CREDIT lowers it.
 */
internal object CustomerLedgerPresentation {
    fun from(type: String, direction: String): LedgerVisual {
        val debit = direction != CREDIT
        return when (type) {
            "CHARGE" -> LedgerVisual("Compra", "−", "Deuda", LedgerTone.DEBT)
            "PAYMENT" -> LedgerVisual("Abono a cuenta", "+", "Abono", LedgerTone.CREDIT)
            "REFUND" -> LedgerVisual("Reembolso", "−", "Deuda", LedgerTone.DEBT)
            "CANCELLATION" -> LedgerVisual("Venta cancelada", "+", "Corrección", LedgerTone.CREDIT)
            "ADJUSTMENT" -> if (debit) {
                LedgerVisual("Ajuste", "−", "Ajuste de deuda", LedgerTone.DEBT)
            } else {
                LedgerVisual("Ajuste", "+", "Ajuste a favor", LedgerTone.CREDIT)
            }
            // A type a newer client introduced. Direction is still trustworthy, so the arithmetic
            // stays right even when the wording has to stay generic.
            else -> if (debit) {
                LedgerVisual("Movimiento", "−", "Deuda", LedgerTone.DEBT)
            } else {
                LedgerVisual("Movimiento", "+", "Abono", LedgerTone.CREDIT)
            }
        }
    }

    private const val CREDIT = "CREDIT"
}
