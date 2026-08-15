package com.cuentivo.app.ai

/**
 * Immutable, versioned contracts for CuentivoAI interpretation.
 *
 * Names are display candidates only: no entity IDs, no SQL, no executable instructions.
 * Money is always whole centavos in a [Long]; quantities are whole units in an [Int].
 * The model never has financial authority — existing deterministic domain logic computes
 * totals, allocations and execution. Drafts carry no computed totals.
 */
data class AiInterpretRequest(
    val text: String,
    val requestId: String,
)

/** Strict allow-list of what a provider may propose. */
sealed interface AiIntent {

    /** Drafts that may be shown to a human for preview after safety validation. */
    sealed interface Draft : AiIntent {

        /** Candidate payment: display name only, positive amount in centavos. */
        data class RecordPaymentDraft(
            val customerName: String,
            val amountCents: Long,
        ) : Draft

        /** Candidate sale: display names only, raw lines and the declared payment; no totals. */
        data class CreateSaleDraft(
            val customerName: String,
            val lines: List<AiSaleLineDraft>,
            val declaredPaymentCents: Long,
        ) : Draft
    }

    /** The input was not interpretable; a human must clarify. Never preview-ready. */
    data object NeedClarification : AiIntent

    /** The provider declined the input. Never preview-ready. */
    data object Refuse : AiIntent
}

/** One candidate line of a sale draft. Prices are centavos, quantities are whole units. */
data class AiSaleLineDraft(
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
)

/** Outcome of one [AiProvider.interpret] call. */
sealed interface AiProviderResult {

    /** The provider produced an intent (which may itself be clarification or refusal). */
    data class Interpreted(val intent: AiIntent) : AiProviderResult

    /** The provider failed to produce any intent; must never become preview-ready. */
    data class Unavailable(val reason: String) : AiProviderResult
}
