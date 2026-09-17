package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerReview

/** Decide cuándo una página posiblemente incompleta fue confirmada explícitamente. */
object LedgerPageCompleteness {
    fun modelSaysTextMayBeMissing(review: LedgerReview): Boolean = review.pageIncomplete || review.unreadTextRemaining
    fun isRegistrable(modelSaysTextMayBeMissing: Boolean, isPageCompletenessConfirmed: Boolean): Boolean =
        !modelSaysTextMayBeMissing || isPageCompletenessConfirmed
}
