package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation

data class LedgerImportSummaryResult(
    val detectedCount: Int,
    val readyCount: Int,
    val pendingReviewCount: Int,
    val canConfirm: Boolean,
    val isConfirmButtonEnabled: Boolean,
    val pageIncompleteNoticeRequired: Boolean,
    val unresolvedCount: Int,
    val isPageCompletenessConfirmed: Boolean,
)

/**
 * Pure calculator for ledger import summary counts and button states (Fase 3).
 *
 * Rules:
 * - Detected: review.operations.size (parser discards do NOT count).
 * - Ready: isRegisterableLedgerOperation == true AND no financial diagnostic AND has resolved date.
 * - Pending Review: maxOf(0, detectedCount - readyCount - review.summary.excludedRowsCount).
 * - Button promise: register only when the whole page is resolved.
 * - Incomplete page notice: Shown without numerical guess when pageIncomplete or unreadTextRemaining is true.
 */
object LedgerImportSummaryCalculator {

    fun calculate(
        review: LedgerReview,
        objections: List<LedgerDeterministicObjection>,
        isPageCompletenessConfirmed: Boolean = false,
    ): LedgerImportSummaryResult {
        val detectedCount = review.operations.size
        val pageDateEpochDay = review.pageDateEpochDay
        val todayEpochDay = review.todayEpochDay
        val objectionLineIndices = objections.map { it.lineIndex }.toSet()
        val unresolvedCount = LedgerPageResolution.unresolvedLineIndices(review.operations, objectionLineIndices).size

        val readyOps = review.operations.filter { op ->
            if (!isRegisterableLedgerOperation(op)) return@filter false
            val hasFinancialError = review.summary.financialDiagnostics.any { it.lineIndex == op.lineIndex }
            if (hasFinancialError) return@filter false
            if (op.lineIndex in objectionLineIndices) return@filter false

            val hasValidRowDate = op.date != null
            val hasValidPageDate = pageDateEpochDay != null &&
                    pageDateEpochDay > 0 &&
                    (todayEpochDay == null || pageDateEpochDay <= todayEpochDay)
            hasValidRowDate || hasValidPageDate
        }

        val readyCount = readyOps.size
        val pendingReviewCount = maxOf(0, detectedCount - readyCount - review.summary.excludedRowsCount)
        val canConfirm = review.summary.canConfirm
        val pageIncompleteNoticeRequired = review.pageIncomplete || review.unreadTextRemaining
        val isConfirmButtonEnabled = canConfirm && readyCount > 0 && unresolvedCount == 0 &&
            LedgerPageCompleteness.isRegistrable(pageIncompleteNoticeRequired, isPageCompletenessConfirmed)

        return LedgerImportSummaryResult(
            detectedCount = detectedCount,
            readyCount = readyCount,
            pendingReviewCount = pendingReviewCount,
            canConfirm = canConfirm,
            isConfirmButtonEnabled = isConfirmButtonEnabled,
            pageIncompleteNoticeRequired = pageIncompleteNoticeRequired,
            unresolvedCount = unresolvedCount,
            isPageCompletenessConfirmed = isPageCompletenessConfirmed,
        )
    }
}
