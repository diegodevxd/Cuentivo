package com.cuentivo.app.ui

// Shared presentation models. They live outside the screen files so a screen can be extracted from
// the monolith without dragging the whole file along with it.

/**
 * A sale or payment the screen may offer to correct.
 *
 * Cancelling never deletes anything: it writes a compensating movement. The screen says so out
 * loud before confirming, because "cancelar" reads like "borrar" to the person using the app.
 */
/**
 * A registered sale reopened for correction, already rebuilt from what was stored.
 *
 * Held by the host rather than the form because loading it touches the database, and because the
 * host is what turns the edited draft back into a replacement: the sale itself is never updated.
 */
data class SaleCorrection(
    val saleId: String,
    val saleNumber: String,
    val draft: com.cuentivo.app.domain.SaleDraft,
)

sealed interface CorrectionCandidate {
    val id: String

    data class Sale(
        override val id: String,
        val saleNumber: String,
        val saleDateEpochDay: Long,
        val totalCents: Long,
        val activePaidCents: Long,
        val outstandingCents: Long,
    ) : CorrectionCandidate

    data class Payment(
        override val id: String,
        val amountCents: Long,
        val paymentDateEpochDay: Long,
        val createdAtEpochMillis: Long,
        val paymentMethod: String,
        val reference: String?,
        val ordinal: Int,
        val groupSize: Int,
        val blockedByCanceledSale: Boolean,
    ) : CorrectionCandidate
}
