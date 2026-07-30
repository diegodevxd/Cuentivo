package com.cuentivo.app.ui.dashboard

/** The visual cash meaning of a persisted ledger activity. */
internal data class DashboardActivityVisual(
    val label: String,
    val sign: String,
    val accent: DashboardActivityAccent,
)

internal enum class DashboardActivityAccent {
    SALE,
    PAYMENT,
    REFUND,
    NEUTRAL,
}

/**
 * Keeps concrete ledger types out of Compose so the activity feed cannot silently relabel data.
 *
 * `direction` is the balance sign, not the cash sign: `FinancialDao` sums movements as
 * `CASE WHEN direction = 'DEBIT' THEN amountCents ELSE -amountCents END`, so DEBIT raises what the
 * customer owes and CREDIT lowers it. The approved reference does not show that balance sign — it
 * shows the cash sign, which is why `Abono recibido` reads `+$250.00` in green even though a
 * payment lowers the receivable, while `Nueva venta` carries no sign at all because a credit sale
 * moves no cash. This object encodes exactly that rule:
 *
 *  - cash in  -> `+` with the payment accent
 *  - cash out -> `-` with the refund accent
 *  - no cash movement (sale, cancellation, adjustment) -> no sign, neutral accent
 *
 * Type alone is enough for the five concrete types because `FinancialDao`, `SaleRepository`, and
 * `PaymentRepository` all `require` a fixed direction per type; ADJUSTMENT is the only type whose
 * direction genuinely varies, so it is the only one that reads `direction`.
 */
internal object DashboardActivityPresentation {
    fun from(type: String, direction: String): DashboardActivityVisual = when (type) {
        // Credit sale: the debt grows, but no cash changes hands, so no sign.
        "CHARGE" -> DashboardActivityVisual("Nueva venta", "", DashboardActivityAccent.SALE)
        "PAYMENT" -> DashboardActivityVisual("Abono recibido", "+", DashboardActivityAccent.PAYMENT)
        "REFUND" -> DashboardActivityVisual("Reembolso enviado", "−", DashboardActivityAccent.REFUND)
        // Compensating entry that voids the unpaid remainder. Nothing is returned to the customer.
        "CANCELLATION" -> DashboardActivityVisual("Venta cancelada", "", DashboardActivityAccent.NEUTRAL)
        "ADJUSTMENT" -> adjustment(direction)
        else -> unknown(direction)
    }

    /** The one type whose direction is not fixed by a `require` in `FinancialDao`. */
    private fun adjustment(direction: String): DashboardActivityVisual =
        if (direction == CREDIT) {
            DashboardActivityVisual("Ajuste a favor del cliente", "", DashboardActivityAccent.NEUTRAL)
        } else {
            DashboardActivityVisual("Ajuste de cargo", "", DashboardActivityAccent.NEUTRAL)
        }

    /**
     * A type this build does not know — for example a newer client's movement arriving through
     * sync. Naming it generically is safer than guessing a cash meaning, so it stays unsigned.
     */
    private fun unknown(direction: String): DashboardActivityVisual =
        if (direction == CREDIT) {
            DashboardActivityVisual("Movimiento a favor", "", DashboardActivityAccent.NEUTRAL)
        } else {
            DashboardActivityVisual("Movimiento registrado", "", DashboardActivityAccent.NEUTRAL)
        }

    private const val CREDIT = "CREDIT"
}
