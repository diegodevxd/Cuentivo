package com.cuentivo.app.ui.customer

/** One history row, read from the customer's side of the ledger. */
internal data class LedgerVisual(
    val label: String,
    val sign: String,
    val caption: String,
    val tone: LedgerTone,
    /**
     * True when this charge is a debt carried over from before the app, not a sale made in it.
     * Same ledger arithmetic, different story, and the row has to say which one it is.
     */
    val isPriorDebt: Boolean = false,
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
    fun from(type: String, direction: String, isPriorDebt: Boolean = false): LedgerVisual {
        val debit = direction != CREDIT
        return when (type) {
            // A debt that already existed when the person opened the app is not a sale made in it.
            // It moved the same way in the ledger, but calling it "Venta" with a cart told the
            // owner they had sold something they never sold (v44).
            "CHARGE" -> if (isPriorDebt) {
                LedgerVisual("Deuda anterior", "−", "Deuda anterior", LedgerTone.DEBT, isPriorDebt = true)
            } else {
                // "Venta", not "Compra": it is the same word the owner uses everywhere else in the
                // app and in the message their customer receives, and one vocabulary is easier to
                // trust.
                LedgerVisual("Venta", "−", "Deuda", LedgerTone.DEBT)
            }
            "PAYMENT" -> LedgerVisual("Abono a cuenta", "+", "Abono", LedgerTone.CREDIT)
            // Testers read "Reembolso" as a second, unrelated charge. It is really the cancellation
            // of an earlier abono, so it says that instead — the paired PAYMENT row gets struck
            // through by the UI using CustomerStatementSummary.canceledPaymentIds.
            "REFUND" -> LedgerVisual("Abono cancelado", "−", "Corrección", LedgerTone.DEBT)
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
