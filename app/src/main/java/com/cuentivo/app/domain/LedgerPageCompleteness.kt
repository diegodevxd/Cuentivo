package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerPageType

enum class LedgerPageConfirmationReason { MODEL_DOUBT, UNREADABLE_ROWS, NOT_AN_ACCOUNTS_PAGE }

/** Decide cuándo una página tiene evidencia que exige confirmación explícita. */
object LedgerPageCompleteness {
    fun confirmationReasons(review: LedgerReview): Set<LedgerPageConfirmationReason> = buildSet {
        if (review.pageIncomplete || review.unreadTextRemaining) add(LedgerPageConfirmationReason.MODEL_DOUBT)
        if (review.discards.isNotEmpty()) add(LedgerPageConfirmationReason.UNREADABLE_ROWS)
        if (review.pageType != null && review.pageType != LedgerPageType.ACCOUNTS && review.operations.isNotEmpty()) add(LedgerPageConfirmationReason.NOT_AN_ACCOUNTS_PAGE)
    }
    fun requiresPageConfirmation(review: LedgerReview): Boolean = confirmationReasons(review).isNotEmpty()
    fun modelSaysTextMayBeMissing(review: LedgerReview): Boolean = requiresPageConfirmation(review)
    fun isRegistrable(modelSaysTextMayBeMissing: Boolean, isPageCompletenessConfirmed: Boolean): Boolean =
        !modelSaysTextMayBeMissing || isPageCompletenessConfirmed
}
