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

// ------------------------------------------------------------------
// Increment A / Fase 3a: versioned, strict, pure proposals.
// ------------------------------------------------------------------

/**
 * Versioned proposal produced by interpretation of user input.
 *
 * Pure data: display-name candidates only — no entity IDs, no SQL, no tool calls, no Firestore
 * path, no computed totals, balances or allocations. Money is whole centavos in a [Long];
 * quantities are whole units in an [Int]. Nothing here writes, executes, persists or touches
 * entities; the deterministic domain layer computes money and executes after explicit confirmation.
 */
sealed interface AiProposal {
    val schemaVersion: Int
}

/**
 * One candidate sale line: display name only, whole units and the declared unit price in centavos.
 * Prices are declarations for the human preview; the domain computes totals itself.
 */
data class AiProposalSaleLine(
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
)

/**
 * Structured payment-promise candidate: a calendar date only. Never free text, ids or amounts.
 */
sealed interface PromisedPaymentCandidate {

    /** Deterministic calendar date; validity is checked by the pure validator (no heuristics). */
    data class DateCandidate(
        val year: Int,
        val month: Int,
        val day: Int,
    ) : PromisedPaymentCandidate
}

/**
 * Candidate sale: display name, raw lines and the declared initial payment; NO computed totals.
 *
 * Plain final class (not a data class) so construction and [copy] always freeze [lines]: every
 * construction stores an unmodifiable copy, so neither the original reference nor a `MutableList`
 * cast can mutate a proposal on the JVM.
 */
class CreateSaleProposal private constructor(
    val customerName: String,
    rawLines: List<AiProposalSaleLine>,
    val declaredInitialPaymentCents: Long,
    val promisedPaymentCandidate: PromisedPaymentCandidate?,
) : AiProposal {

    override val schemaVersion: Int = 1

    /** Truly unmodifiable: mutation attempts throw [UnsupportedOperationException]. */
    val lines: List<AiProposalSaleLine> = Collections.unmodifiableList(rawLines.toMutableList())

    companion object {
        operator fun invoke(
            customerName: String,
            lines: List<AiProposalSaleLine>,
            declaredInitialPaymentCents: Long,
            promisedPaymentCandidate: PromisedPaymentCandidate? = null,
        ): CreateSaleProposal = CreateSaleProposal(
            customerName = customerName,
            rawLines = lines,
            declaredInitialPaymentCents = declaredInitialPaymentCents,
            promisedPaymentCandidate = promisedPaymentCandidate,
        )
    }

    /** Also freezes [lines], unlike a generated data class copy. */
    fun copy(
        customerName: String = this.customerName,
        lines: List<AiProposalSaleLine> = this.lines,
        declaredInitialPaymentCents: Long = this.declaredInitialPaymentCents,
        promisedPaymentCandidate: PromisedPaymentCandidate? = this.promisedPaymentCandidate,
    ): CreateSaleProposal = CreateSaleProposal(
        customerName = customerName,
        rawLines = lines,
        declaredInitialPaymentCents = declaredInitialPaymentCents,
        promisedPaymentCandidate = promisedPaymentCandidate,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CreateSaleProposal &&
                customerName == other.customerName &&
                lines == other.lines &&
                declaredInitialPaymentCents == other.declaredInitialPaymentCents &&
                promisedPaymentCandidate == other.promisedPaymentCandidate)

    override fun hashCode(): Int {
        var result = customerName.hashCode()
        result = 31 * result + lines.hashCode()
        result = 31 * result + declaredInitialPaymentCents.hashCode()
        result = 31 * result + (promisedPaymentCandidate?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CreateSaleProposal(customerName=$customerName, lines=$lines, " +
            "declaredInitialPaymentCents=$declaredInitialPaymentCents, " +
            "promisedPaymentCandidate=$promisedPaymentCandidate)"
}

/**
 * Candidate payment: display name only and the declared amount in centavos. Zero is a valid
 * declared payment; negative is never allowed. No balance or total comparison happens here.
 */
data class RecordPaymentProposal(
    val customerName: String,
    val declaredAmountCents: Long,
) : AiProposal {
    override val schemaVersion: Int = 1
}

/** Strict allow-list of what a business query may ask. No arbitrary filters or SQL. */
enum class BusinessQueryType {
    CUSTOMER_BALANCE,
    CUSTOMERS_WITH_DEBT,
    SALES_FOR_PERIOD,
}

/** Deterministic calendar-period candidate; validity checked by the pure validator. */
data class AiPeriodCandidate(
    val startYear: Int,
    val startMonth: Int,
    val startDay: Int,
    val endYear: Int,
    val endMonth: Int,
    val endDay: Int,
)

/**
 * Allow-listed business query. Selectors are only a display-name candidate (for balance) and/or
 * a calendar period (for sales), per [BusinessQueryType]; anything else is an invalid shape.
 */
data class BusinessQueryProposal(
    val queryType: BusinessQueryType,
    val customerName: String? = null,
    val period: AiPeriodCandidate? = null,
) : AiProposal {
    override val schemaVersion: Int = 1
}

/** Machine-readable clarification reasons. No free text, ids or instructed answers. */
enum class ClarificationReason {
    AMBIGUOUS_CUSTOMER,
    AMBIGUOUS_PRODUCT,
    MISSING_CUSTOMER,
    MISSING_PRODUCT,
    MISSING_AMOUNT,
    UNKNOWN_INPUT,
}

/** Allow-listed unresolved slot kinds; no free text. */
enum class UnresolvedSlot {
    CUSTOMER,
    PRODUCT,
    AMOUNT,
    QUANTITY,
    PERIOD,
}

/**
 * Clarification request: a machine-readable [reason] plus unresolved [unresolvedSlots] only.
 *
 * Plain final class so [unresolvedSlots] is always an unmodifiable canonical set (duplicates
 * collapse per `Set` semantics on construction).
 */
class RequiresClarificationProposal private constructor(
    val reason: ClarificationReason,
    rawSlots: Set<UnresolvedSlot>,
) : AiProposal {

    override val schemaVersion: Int = 1

    /** Truly unmodifiable canonical set: mutation attempts throw [UnsupportedOperationException]. */
    val unresolvedSlots: Set<UnresolvedSlot> =
        Collections.unmodifiableSet(LinkedHashSet(rawSlots))

    companion object {
        operator fun invoke(
            reason: ClarificationReason,
            slots: Set<UnresolvedSlot>,
        ): RequiresClarificationProposal = RequiresClarificationProposal(
            reason = reason,
            rawSlots = slots,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RequiresClarificationProposal &&
                reason == other.reason &&
                unresolvedSlots == other.unresolvedSlots)

    override fun hashCode(): Int = 31 * reason.hashCode() + unresolvedSlots.hashCode()

    override fun toString(): String =
        "RequiresClarificationProposal(reason=$reason, unresolvedSlots=$unresolvedSlots)"
}
