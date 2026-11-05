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
    /** Hay algo que exige la palabra de la persona antes de registrar: mira [confirmationReasons]. */
    val pageIncompleteNoticeRequired: Boolean,
    /** Por qué se pide esa confirmación. La pantalla elige su mensaje con esto. */
    val confirmationReasons: Set<LedgerPageConfirmationReason>,
    /** Renglones que el parser descartó. La pantalla dice cuántos no se pudieron leer. */
    val unreadableRowsCount: Int,
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
 * - Page confirmation: required for EVERY reason [LedgerPageCompleteness] lists — la duda del modelo,
 *   un renglón descartado por el parser, o una hoja que no es de cuentas con renglones. Los descartes
 *   no son un detalle numérico: un renglón descartado desaparece del registro sin que nadie lo vea.
 */
object LedgerImportSummaryCalculator {

    fun calculate(
        review: LedgerReview,
        objections: List<LedgerDeterministicObjection>,
        isPageCompletenessConfirmed: Boolean = false,
        /**
         * Renglones que un posible duplicado deja sin resolver (v44). Se suman a los de las
         * objeciones: el duplicado frena igual que una objeción hasta que la persona lo confirme.
         */
        duplicateBlockedLineIndices: Set<Int> = emptySet(),
    ): LedgerImportSummaryResult {
        val detectedCount = review.operations.size
        val pageDateEpochDay = review.pageDateEpochDay
        val todayEpochDay = review.todayEpochDay
        val objectionLineIndices = objections.map { it.lineIndex }.toSet() + duplicateBlockedLineIndices
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
        // La duda de la página NO es sólo lo que dijo el modelo. Un renglón descartado por el parser
        // DESAPARECE y la página se registraría sin él; y una hoja que no es de cuentas con renglones
        // tampoco es un registro normal. Los tres motivos piden la misma confirmación explícita.
        val confirmationReasons = LedgerPageCompleteness.confirmationReasons(review)
        val pageIncompleteNoticeRequired = confirmationReasons.isNotEmpty()
        val unreadableRowsCount = review.discards.size
        val isConfirmButtonEnabled = canConfirm && readyCount > 0 && unresolvedCount == 0 &&
            LedgerPageCompleteness.isRegistrable(pageIncompleteNoticeRequired, isPageCompletenessConfirmed)

        return LedgerImportSummaryResult(
            detectedCount = detectedCount,
            readyCount = readyCount,
            pendingReviewCount = pendingReviewCount,
            canConfirm = canConfirm,
            isConfirmButtonEnabled = isConfirmButtonEnabled,
            pageIncompleteNoticeRequired = pageIncompleteNoticeRequired,
            confirmationReasons = confirmationReasons,
            unreadableRowsCount = unreadableRowsCount,
            unresolvedCount = unresolvedCount,
            isPageCompletenessConfirmed = isPageCompletenessConfirmed,
        )
    }
}
