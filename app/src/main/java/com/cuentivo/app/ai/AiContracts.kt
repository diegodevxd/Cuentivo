package com.cuentivo.app.ai

import java.util.Collections

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

        /**
         * Candidate sale: display names only, raw lines and the declared payment; no totals.
         * Plain final class (not a data class) so that both construction and [copy] can
         * freeze [lines]: every construction stores an unmodifiable copy, so neither the
         * original reference nor a [MutableList] cast can mutate a draft on the JVM.
         */
        class CreateSaleDraft private constructor(
            val customerName: String,
            rawLines: List<AiSaleLineDraft>,
            val declaredPaymentCents: Long,
        ) : Draft {

            /** Truly unmodifiable: mutation attempts throw [UnsupportedOperationException]. */
            val lines: List<AiSaleLineDraft> = Collections.unmodifiableList(rawLines.toMutableList())

            companion object {
                operator fun invoke(
                    customerName: String,
                    lines: List<AiSaleLineDraft>,
                    declaredPaymentCents: Long,
                ): CreateSaleDraft = CreateSaleDraft(
                    customerName = customerName,
                    rawLines = lines,
                    declaredPaymentCents = declaredPaymentCents,
                )
            }

            /** Also freezes [lines], unlike a generated data class copy. */
            fun copy(
                customerName: String = this.customerName,
                lines: List<AiSaleLineDraft> = this.lines,
                declaredPaymentCents: Long = this.declaredPaymentCents,
            ): CreateSaleDraft = CreateSaleDraft(
                customerName = customerName,
                rawLines = lines,
                declaredPaymentCents = declaredPaymentCents,
            )

            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is CreateSaleDraft &&
                        customerName == other.customerName &&
                        lines == other.lines &&
                        declaredPaymentCents == other.declaredPaymentCents)

            override fun hashCode(): Int {
                var result = customerName.hashCode()
                result = 31 * result + lines.hashCode()
                result = 31 * result + declaredPaymentCents.hashCode()
                return result
            }

            override fun toString(): String =
                "CreateSaleDraft(customerName=$customerName, lines=$lines, declaredPaymentCents=$declaredPaymentCents)"
        }
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
