package com.cuentivo.app.ui

// Shared presentation models. They live outside the screen files so a screen can be extracted from
// the monolith without dragging the whole file along with it.

/**
 * A sale or payment the screen may offer to correct.
 *
 * Cancelling never deletes anything: it writes a compensating movement. The screen says so out
 * loud before confirming, because "cancelar" reads like "borrar" to the person using the app.
 */
data class CancelableItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val amountCents: Long,
) {
    enum class Kind { SALE, PAYMENT }
}
