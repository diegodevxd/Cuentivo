package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerDetectedPageDate
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerPageTotal
import com.cuentivo.app.ai.ledger.LedgerPageType
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ui.transaction.DueOption

/**
 * What a ledger review under way saves across Activity recreation, and nothing else.
 *
 * The rule this file follows: save what cannot be recalculated — the reading Gemini made of the
 * page plus whatever the person has corrected on top of it — and reload from Room everything that
 * is derivable. The 552 products, the customer candidates, the balances and the already-registered
 * rows are derivable; a Bundle is not a place they would fit, and exceeding the transaction limit
 * kills the app.
 *
 * Never saved: the image, anything base64, or anything that resembles a photo. The capture file
 * path is already `rememberSaveable` in the screen, and that is enough to keep showing crops.
 *
 * Restoring never calls the callable. The extraction result is here, in full, so the review comes
 * back exactly as it was left — including the corrections — without paying for the page twice.
 */
object LedgerReviewSaver {

    /**
     * The part of a review that cannot be recomputed: what the model read, what the person edited,
     * and the session it belongs to. Balances, candidates and registered rows are deliberately
     * absent — they are reloaded from Room when the review is rebuilt.
     *
     * [originalOperations] is the reading as it arrived from the callable, frozen and never
     * edited: the corrections a person made are counted by comparing this against [operations],
     * and without it a recreation would carry only the edited version and count them wrong — or
     * not at all.
     */
    data class SavedLedgerReview(
        val sessionId: String,
        val businessId: String,
        val operations: List<LedgerOperation>,
        val originalOperations: List<LedgerOperation>,
        val discards: List<LedgerDiscardDiagnostic>,
        val unreadTextRemaining: Boolean,
        val pageIncomplete: Boolean,
        val pageDateEpochDay: Long?,
        val todayEpochDay: Long?,
        val detectedPageDate: LedgerDetectedPageDate?,
        val initialDuplicateCount: Int = 0,
        val reviewSchemaVersion: Int? = null,
        val isPageCompletenessConfirmed: Boolean = false,
        val pageType: LedgerPageType? = null,
        val pageTotals: List<LedgerPageTotal> = emptyList(),
        /**
         * El plazo de la página, en días, o `null` para "Sin fecha".
         *
         * Viaja en el Bundle como **texto** (`"15"` / cadena vacía) y no como `Long` ausente, y esa
         * elección es la que distingue dos casos que para `Bundle` son el mismo "no está":
         * un borrador guardado **antes** de que existiera el selector (clave ausente → vuelve con
         * 15, no con `null`) y la elección explícita "Sin fecha" (clave presente, vacía → `null`).
         * Al volver de la app el selector tiene que mostrar exactamente lo que se registrará.
         */
        val dueDateDays: Long? = DueOption.FIFTEEN.days,
    )

    /** Rebuilds the derived half of the review from the saved half. Pure: no Room, no clock. */
    fun rebuildReview(
        saved: SavedLedgerReview,
        existingCustomerBalances: Map<String, Long>,
    ): LedgerReview = LedgerReviewEngine.build(
        operations = saved.operations,
        discards = saved.discards,
        unreadTextRemaining = saved.unreadTextRemaining,
        pageIncomplete = saved.pageIncomplete,
        existingCustomerBalances = existingCustomerBalances,
        pageDateEpochDay = saved.pageDateEpochDay,
        todayEpochDay = saved.todayEpochDay,
        detectedPageDate = saved.detectedPageDate,
    )
}
