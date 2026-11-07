package com.cuentivo.app.ai

/** Outcome of [AiDraftSafety.validate]. Deliberately carries no execute, confirm, DAO,
 * repository, idempotency or persistence callback. */
sealed interface AiDraftSafetyResult {

    /** Draft passed every check and may be shown to a human for confirmation. */
    data class ReadyForHumanPreview(val draft: AiIntent.Draft) : AiDraftSafetyResult

    /** Clarification, refusal, unavailability or invalid values: never preview-ready. */
    data object NotReady : AiDraftSafetyResult
}

/**
 * Pure, local safety gate for provider output.
 *
 * Only drafts with nonblank names, positive quantities and positive centavo amounts become
 * [AiDraftSafetyResult.ReadyForHumanPreview]. [AiIntent.NeedClarification], [AiIntent.Refuse],
 * [AiProviderResult.Unavailable] and zero/negative or blank values never do. Nothing here
 * writes, executes, persists or touches entities.
 */
object AiDraftSafety {

    fun validate(result: AiProviderResult): AiDraftSafetyResult = when (result) {
        is AiProviderResult.Unavailable -> AiDraftSafetyResult.NotReady
        is AiProviderResult.Interpreted -> when (val intent = result.intent) {
            is AiIntent.Draft.RecordPaymentDraft ->
                if (intent.customerName.isNotBlank() && intent.amountCents > 0L) {
                    AiDraftSafetyResult.ReadyForHumanPreview(intent)
                } else {
                    AiDraftSafetyResult.NotReady
                }

            is AiIntent.Draft.CreateSaleDraft ->
                if (intent.customerName.isNotBlank() &&
                    intent.lines.isNotEmpty() &&
                    intent.lines.all { line ->
                        line.productName.isNotBlank() && line.quantity > 0 && line.unitPriceCents > 0L
                    } &&
                    intent.declaredPaymentCents > 0L
                ) {
                    AiDraftSafetyResult.ReadyForHumanPreview(intent)
                } else {
                    AiDraftSafetyResult.NotReady
                }

            AiIntent.NeedClarification, AiIntent.Refuse -> AiDraftSafetyResult.NotReady
        }
    }
}
