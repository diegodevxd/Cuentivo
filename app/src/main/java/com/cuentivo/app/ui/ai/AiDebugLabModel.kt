package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiBusinessQueryResult
import com.cuentivo.app.ai.AiDailyMessageKind
import com.cuentivo.app.ai.AiPaymentBlockReason
import com.cuentivo.app.ai.AiPaymentConfirmationRequest
import com.cuentivo.app.ai.AiPaymentCustomerCandidate
import com.cuentivo.app.ai.AiPaymentExecutionResult
import com.cuentivo.app.ai.AiPaymentPreviewResult
import com.cuentivo.app.ai.AiProposal
import com.cuentivo.app.ai.AiProposalValidationResult
import com.cuentivo.app.ai.AiResolvedPaymentPreview
import com.cuentivo.app.ai.AiResolvedSalePreview
import com.cuentivo.app.ai.AiSaleBlockReason
import com.cuentivo.app.ai.AiSaleConfirmationLine
import com.cuentivo.app.ai.AiSaleConfirmationRequest
import com.cuentivo.app.ai.AiSaleCustomerCandidate
import com.cuentivo.app.ai.AiSaleExecutionResult
import com.cuentivo.app.ai.AiSalePreviewResult
import com.cuentivo.app.ai.AiSaleProductCandidate
import com.cuentivo.app.ai.BusinessQueryProposal
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.ClarificationReason
import com.cuentivo.app.ai.CreateSaleProposal
import com.cuentivo.app.ai.PromisedPaymentCandidate
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.RequiresClarificationProposal
import com.cuentivo.app.ai.SafeAiProposalValidator
import com.cuentivo.app.ai.UnresolvedSlot
import com.cuentivo.app.ai.ledger.CustomerReviewGroup
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import java.time.LocalDate
import java.util.Collections

/**
 * Pure, non-executable AI debug lab states. Nothing in here can confirm, save or execute a
 * financial operation; preview shows only candidates/declarations and queries show intent.
 */
sealed interface AiDebugLabState {
    data object TextEditing : AiDebugLabState
    data object Sending : AiDebugLabState
    data object Unavailable : AiDebugLabState
    data class Clarification(val reason: ClarificationReason, val slots: Set<UnresolvedSlot>) : AiDebugLabState
    data class ProposalPreview(
        val proposal: AiProposal,
        val saleChoices: com.cuentivo.app.ai.AiSaleResolutionChoices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
    ) : AiDebugLabState

    /** Non-executable resolved payment preview state; never triggers financial writes. */
    data class PaymentResolution(val result: com.cuentivo.app.ai.AiPaymentPreviewResult) : AiDebugLabState
    data class PaymentConfirmation(val preview: AiResolvedPaymentPreview) : AiDebugLabState
    data class PaymentConfirming(val preview: AiResolvedPaymentPreview) : AiDebugLabState

    /** Non-executable resolved sale preview state; never triggers financial writes. */
    data class SaleResolution(
        val result: AiSalePreviewResult,
        val proposal: CreateSaleProposal? = null,
        val choices: com.cuentivo.app.ai.AiSaleResolutionChoices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
    ) : AiDebugLabState
    data class SaleConfirmation(
        val preview: AiResolvedSalePreview,
        val proposal: CreateSaleProposal? = null,
        val choices: com.cuentivo.app.ai.AiSaleResolutionChoices = com.cuentivo.app.ai.AiSaleResolutionChoices(),
        /**
         * El plazo de pago del borrador, en días, o `null` para "Sin fecha". Arranca en 15 —lo mismo
         * que la venta manual— porque una venta fiada sin fecha no vence, no entra al recordatorio
         * ni cuenta para la calificación (recorrido "Doña Mary", v44). Es elección de la persona, y
         * lo que se registra es lo que este borrador muestra.
         */
        val dueDateDays: Long? = com.cuentivo.app.ui.transaction.DueOption.FIFTEEN.days,
    ) : AiDebugLabState
    data class SaleConfirming(val preview: AiResolvedSalePreview) : AiDebugLabState

    /** Non-executable resolved business query states. */
    data class QueryResolving(val proposal: BusinessQueryProposal) : AiDebugLabState
    data class QueryAnswer(
        val result: AiBusinessQueryResult,
        val proposal: BusinessQueryProposal? = null,
    ) : AiDebugLabState

    /** Pure ledger import states. No Room or network dependencies. */
    data class LedgerExtracting(
        val sessionId: String,
        val businessId: String,
    ) : AiDebugLabState
    data class LedgerReviewing(
        val sessionId: String,
        val businessId: String,
        val review: LedgerReview,
        /**
         * The reading exactly as the callable delivered it, frozen: corrections are counted by
         * comparing this against [review.operations], and it must survive edits and Activity
         * recreations unchanged or the counts lie.
         */
        val originalOperations: List<LedgerOperation> = emptyList(),
        /**
         * Initial duplicate warnings suggested to the user upon page arrival, frozen at creation time.
         * Persisted across Activity recreations so that subsequent edits or process recreations do not alter
         * the initial warning metric.
         */
        val initialDuplicateCount: Int = com.cuentivo.app.domain.LedgerDuplicateDetector.evaluate(
            operations = originalOperations,
            existingRegistered = registeredOperations,
            pageDateEpochDay = review.pageDateEpochDay,
            todayEpochDay = review.todayEpochDay,
        ).size,
        val existingCustomerBalances: Map<String, Long> = emptyMap(),
        /**
         * The customers this business already has, loaded once when the page arrived.
         *
         * Held in the state rather than re-read per edit so the candidates a person is choosing
         * between cannot change underneath them mid-review.
         */
        val customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate> = emptyList(),
        /** The catalogue, for the same reason and with the same lifetime as the customers. */
        val productCandidates: List<com.cuentivo.app.domain.ProductCandidate> = emptyList(),
        /** Existing registered operations in Room within date window for duplicate detection. */
        val registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection> = emptyList(),
        val isLegacySaveWithoutSessionKey: Boolean = false,
        val isPageCompletenessConfirmed: Boolean = false,
        /**
         * El plazo de pago elegido para **toda la página**, en días, o `null` para "Sin fecha".
         *
         * Arranca en 15 —lo mismo que propone la venta manual— porque una venta sin fecha no puede
         * vencerse ni entrar al recordatorio de cobro. Es elección de la persona, no un derivado de
         * los renglones, y viaja al planificador en la confirmación: lo que se registra es lo que
         * esta pantalla muestra.
         */
        val dueDateDays: Long? = com.cuentivo.app.ui.transaction.DueOption.FIFTEEN.days,
    ) : AiDebugLabState {
        /** What the app may offer, recomputed from the rows as they are edited. */
        val customerMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.CustomerCandidate>>
            get() = com.cuentivo.app.domain.LedgerCustomerMatching.matchesFor(review.operations, customerCandidates)

        val productMatches: Map<Int, com.cuentivo.app.domain.EntityMatchResult<com.cuentivo.app.domain.ProductCandidate>>
            get() = com.cuentivo.app.domain.LedgerProductMatching.matchesFor(review.operations, productCandidates)

        /**
         * Live duplicate warnings evaluated against current (edited) review operations for UI display.
         */
        val duplicateMatches: Map<Int, com.cuentivo.app.domain.LedgerDuplicateMatch>
            get() = com.cuentivo.app.domain.LedgerDuplicateDetector.evaluate(
                operations = review.operations,
                existingRegistered = registeredOperations,
                pageDateEpochDay = review.pageDateEpochDay,
                todayEpochDay = review.todayEpochDay,
            )
    }
    data class LedgerConfirming(
        val sessionId: String,
        val businessId: String,
        val review: LedgerReview,
        val originalOperations: List<LedgerOperation> = emptyList(),
        val initialDuplicateCount: Int = 0,
        /** Carried through so a failed write can return to the review with the debts it showed. */
        val existingCustomerBalances: Map<String, Long> = emptyMap(),
        val customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate> = emptyList(),
        val productCandidates: List<com.cuentivo.app.domain.ProductCandidate> = emptyList(),
        val registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection> = emptyList(),
        val isLegacySaveWithoutSessionKey: Boolean = false,
        val isPageCompletenessConfirmed: Boolean = false,
        /** Copiado desde [LedgerReviewing] al confirmar, para que un fallo de escritura devuelva la revisión con el plazo que la persona veía. */
        val dueDateDays: Long? = com.cuentivo.app.ui.transaction.DueOption.FIFTEEN.days,
    ) : AiDebugLabState
}

/**
 * Parameters extracted from [AiDebugLabState.LedgerReviewing] when ending an import session.
 * Encapsulates the extraction of [duplicateSuggestedCount] and [parserExcludedCount].
 */
data class LedgerImportEndParams(
    val sessionId: String,
    val registered: Boolean,
    val originalOperations: List<LedgerOperation>,
    val finalReview: LedgerReview,
    val duplicateSuggestedCount: Int,
    val parserExcludedCount: Int,
    val nowEpochMillis: Long,
)

fun AiDebugLabState.LedgerReviewing.toImportEndParams(
    registered: Boolean,
    nowEpochMillis: Long = System.currentTimeMillis(),
): LedgerImportEndParams = LedgerImportEndParams(
    sessionId = sessionId,
    registered = registered,
    originalOperations = originalOperations,
    finalReview = review,
    duplicateSuggestedCount = initialDuplicateCount,
    parserExcludedCount = review.summary.excludedRowsCount,
    nowEpochMillis = nowEpochMillis,
)


sealed interface AiSaleChoiceOutcome {
    data class Selected(
        val model: AiDebugLabModel,
        val proposal: CreateSaleProposal,
        val choices: com.cuentivo.app.ai.AiSaleResolutionChoices,
        val generation: Long,
    ) : AiSaleChoiceOutcome

    data object Ignored : AiSaleChoiceOutcome
}

sealed interface AiQueryChoiceOutcome {
    data class Selected(
        val model: AiDebugLabModel,
        val proposal: BusinessQueryProposal,
        val choice: com.cuentivo.app.ai.AiEntityChoice,
        val generation: Long,
    ) : AiQueryChoiceOutcome

    data object Ignored : AiQueryChoiceOutcome
}

sealed interface AiLedgerConfirmOutcome {
    data object Ignored : AiLedgerConfirmOutcome
    data class Started(
        val model: AiDebugLabModel,
        val sessionId: String,
        val businessId: String,
        val review: LedgerReview,
        val generation: Long,
    ) : AiLedgerConfirmOutcome
}

/** Immutable lab view state; [generation] is the single-flight/cancellation token. */
data class AiDebugLabModel(
    val state: AiDebugLabState = AiDebugLabState.TextEditing,
    val text: String = "",
    val messages: List<AiChatMessage> = emptyList(),
    internal val generation: Long = 0L,
) {
    /** Blank text or an in-flight request must not send; the gateway is never called then. */
    val canSubmit: Boolean
        get() = text.isNotBlank() && state == AiDebugLabState.TextEditing

    /** Voice is strictly disabled during extraction, review, confirmation, and sending. */
    val isVoiceDisabled: Boolean
        get() = when (state) {
            AiDebugLabState.Sending,
            is AiDebugLabState.PaymentConfirming,
            is AiDebugLabState.SaleConfirming,
            is AiDebugLabState.QueryResolving,
            is AiDebugLabState.LedgerExtracting,
            is AiDebugLabState.LedgerReviewing,
            is AiDebugLabState.LedgerConfirming -> true
            else -> false
        }
}

/**
 * Immutable, temporary operational chat transcript message. Purely presentational: no
 * confirmation, save, execute, callback or domain dependency. Nothing here persists.
 */
sealed interface AiChatMessage {
    data class UserText(val text: String) : AiChatMessage
    data object AssistantGreeting : AiChatMessage
    /** How a page import ended, so the chat says what happened instead of just closing. */
    data class AssistantLedgerRecorded(val operationsCount: Int) : AiChatMessage
    data class AssistantLedgerAlreadyRecorded(val operationsCount: Int) : AiChatMessage
    data object AssistantLedgerFailed : AiChatMessage
    data object AssistantOutOfScope : AiChatMessage
    data object AssistantPending : AiChatMessage
    data object AssistantUnavailable : AiChatMessage
    data object AssistantQuotaExhausted : AiChatMessage
    data object AssistantRateLimited : AiChatMessage
    data object AssistantDisabled : AiChatMessage
    data object AssistantEntitlementRequired : AiChatMessage
    data class AssistantClarification(
        val reason: ClarificationReason,
        val slots: Set<UnresolvedSlot>,
    ) : AiChatMessage
    data class AssistantProposal(val proposal: AiProposal) : AiChatMessage

    data class AssistantPaymentPreview(val preview: AiResolvedPaymentPreview) : AiChatMessage
    data object AssistantPaymentCustomerNotFound : AiChatMessage

    /**
     * More than one payment candidate matches and a human must choose.
     *
     * Plain final class so construction always freezes [candidates]: every construction stores an
     * unmodifiable copy, so neither the original reference nor a `MutableList` cast can mutate the
     * result on the JVM.
     */
    class AssistantPaymentCustomerSelection private constructor(
        rawCandidates: List<AiPaymentCustomerCandidate>,
    ) : AiChatMessage {
        /** Truly unmodifiable: mutation attempts throw [UnsupportedOperationException]. */
        val candidates: List<AiPaymentCustomerCandidate> =
            Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(
                candidates: List<AiPaymentCustomerCandidate>,
            ): AssistantPaymentCustomerSelection = AssistantPaymentCustomerSelection(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AssistantPaymentCustomerSelection && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "AssistantPaymentCustomerSelection(candidates=$candidates)"
    }

    data class AssistantPaymentBlocked(
        val reason: AiPaymentBlockReason,
        val preview: AiResolvedPaymentPreview?,
    ) : AiChatMessage

    data class AssistantPaymentRecorded(val paymentId: String) : AiChatMessage
    data object AssistantPaymentStale : AiChatMessage
    data object AssistantPaymentRejected : AiChatMessage

    data class AssistantSalePreview(val preview: AiResolvedSalePreview) : AiChatMessage
    class AssistantSaleCustomerSelection private constructor(
        rawCandidates: List<com.cuentivo.app.ai.AiSaleCustomerCandidate>,
    ) : AiChatMessage {
        val candidates: List<com.cuentivo.app.ai.AiSaleCustomerCandidate> =
            Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(
                candidates: List<com.cuentivo.app.ai.AiSaleCustomerCandidate>,
            ): AssistantSaleCustomerSelection = AssistantSaleCustomerSelection(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AssistantSaleCustomerSelection && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "AssistantSaleCustomerSelection(candidates=$candidates)"
    }
    class AssistantSaleProductSelection private constructor(
        val requestedName: String,
        rawCandidates: List<com.cuentivo.app.ai.AiSaleProductCandidate>,
    ) : AiChatMessage {
        val candidates: List<com.cuentivo.app.ai.AiSaleProductCandidate> =
            Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(
                requestedName: String,
                candidates: List<com.cuentivo.app.ai.AiSaleProductCandidate>,
            ): AssistantSaleProductSelection = AssistantSaleProductSelection(
                requestedName = requestedName,
                rawCandidates = candidates,
            )
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AssistantSaleProductSelection &&
                    requestedName == other.requestedName &&
                    candidates == other.candidates)

        override fun hashCode(): Int = 31 * requestedName.hashCode() + candidates.hashCode()

        override fun toString(): String = "AssistantSaleProductSelection(requestedName=$requestedName, candidates=$candidates)"
    }
    data class AssistantSaleBlocked(
        val reason: AiSaleBlockReason,
        val preview: AiResolvedSalePreview?,
    ) : AiChatMessage
    data class AssistantSaleRecorded(
        val saleId: String,
        val paymentId: String?,
        val customerName: String? = null,
        val balanceAfterCents: Long? = null,
    ) : AiChatMessage
    data object AssistantSaleStale : AiChatMessage
    data object AssistantSaleRejected : AiChatMessage
    data object AssistantSaleLimitReached : AiChatMessage
    data class AssistantQueryAnswer(val result: AiBusinessQueryResult) : AiChatMessage
}

sealed interface AiPaymentConfirmOutcome {
    data object Ignored : AiPaymentConfirmOutcome
    data class Started(
        val model: AiDebugLabModel,
        val request: AiPaymentConfirmationRequest,
        val generation: Long,
    ) : AiPaymentConfirmOutcome
}

sealed interface AiSaleConfirmOutcome {
    data object Ignored : AiSaleConfirmOutcome
    data class Started(
        val model: AiDebugLabModel,
        val request: AiSaleConfirmationRequest,
        val generation: Long,
    ) : AiSaleConfirmOutcome
}

/** Result of a submit decision: [Ignored*] means the gateway is never invoked. */
sealed interface AiDebugLabSubmitOutcome {
    data object IgnoredBlank : AiDebugLabSubmitOutcome
    data object IgnoredBusy : AiDebugLabSubmitOutcome
    data class Submitted(val model: AiDebugLabModel, val generation: Long) : AiDebugLabSubmitOutcome
}

/**
 * Deterministic state machine for the debug text lab. Pure values only: no coroutines, no I/O,
 * no Firebase, no Room, no logs, no persistence. A proposal is shown only after
 * [SafeAiProposalValidator] accepts it; any rejection, unavailable result or unexpected shape
 * falls back to the generic safe [AiDebugLabState.Unavailable].
 */
object AiDebugLabMachine {

    fun onTextChanged(model: AiDebugLabModel, newText: String): AiDebugLabModel =
        when (model.state) {
            // Single-flight: editing is ignored while a request is in flight.
            AiDebugLabState.Sending,
            is AiDebugLabState.PaymentConfirming,
            is AiDebugLabState.SaleConfirming,
            is AiDebugLabState.QueryResolving,
            is AiDebugLabState.LedgerExtracting,
            is AiDebugLabState.LedgerReviewing,
            is AiDebugLabState.LedgerConfirming -> model
            AiDebugLabState.TextEditing -> model.copy(text = newText)
            // From a result state the next turn starts: accept text and return to editing.
            else -> model.copy(state = AiDebugLabState.TextEditing, text = newText)
        }

    fun onSubmit(model: AiDebugLabModel): AiDebugLabSubmitOutcome {
        if (model.text.isBlank()) {
            return AiDebugLabSubmitOutcome.IgnoredBlank
        }
        if (model.state != AiDebugLabState.TextEditing) {
            return AiDebugLabSubmitOutcome.IgnoredBusy
        }
        val next = model.copy(
            state = AiDebugLabState.Sending,
            generation = model.generation + 1,
            messages = model.messages + AiChatMessage.UserText(model.text.trim()) + AiChatMessage.AssistantPending,
        )
        return AiDebugLabSubmitOutcome.Submitted(next, next.generation)
    }

    fun onResult(
        model: AiDebugLabModel,
        result: CallableAiProposalResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation || model.state != AiDebugLabState.Sending) {
            return model // cancelled or stale result: never applied
        }
        val assistantMessage = when (result) {
            CallableAiProposalResult.Unavailable -> AiChatMessage.AssistantUnavailable
            CallableAiProposalResult.QuotaExhausted -> AiChatMessage.AssistantQuotaExhausted
            CallableAiProposalResult.RateLimited -> AiChatMessage.AssistantRateLimited
            CallableAiProposalResult.Disabled -> AiChatMessage.AssistantDisabled
            CallableAiProposalResult.EntitlementRequired -> AiChatMessage.AssistantEntitlementRequired

            is CallableAiProposalResult.Proposal ->
                when (val validation = SafeAiProposalValidator.validate(result.proposal)) {
                    is AiProposalValidationResult.Accepted ->
                        when (val proposal = validation.proposal) {
                            is RequiresClarificationProposal ->
                                AiChatMessage.AssistantClarification(
                                    proposal.reason,
                                    proposal.unresolvedSlots,
                                )

                            else -> AiChatMessage.AssistantProposal(proposal)
                        }

                    is AiProposalValidationResult.Rejected -> AiChatMessage.AssistantUnavailable
                }
        }
        val state = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable,
            AiChatMessage.AssistantQuotaExhausted,
            AiChatMessage.AssistantRateLimited,
            AiChatMessage.AssistantDisabled,
            AiChatMessage.AssistantEntitlementRequired -> AiDebugLabState.Unavailable
            is AiChatMessage.AssistantClarification ->
                AiDebugLabState.Clarification(assistantMessage.reason, assistantMessage.slots)
            is AiChatMessage.AssistantProposal -> AiDebugLabState.ProposalPreview(assistantMessage.proposal)
            else -> AiDebugLabState.TextEditing
        }
        // Replace only the final pending message with the assistant outcome, and clear the draft
        // text so the same message cannot be accidentally re-sent.
        val messages = if (model.messages.isNotEmpty() &&
            model.messages.last() == AiChatMessage.AssistantPending
        ) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages + assistantMessage
        }
        return model.copy(state = state, messages = messages, text = "")
    }

    /**
     * Applies an on-device OCR result to the editor text. A null/blank result preserves the
     * existing text unchanged; a valid result appends the recognized text after a newline.
     * The generation token prevents stale OCR results from overwriting a cancelled session.
     */
    fun onOcrResult(
        model: AiDebugLabModel,
        recognizedText: String?,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) {
            return model // cancelled or stale OCR result
        }
        val trimmed = recognizedText?.trim()
        if (trimmed.isNullOrBlank()) {
            return model // preserve existing editor text on empty/failed OCR
        }
        val separator = if (model.text.isNotBlank()) "\n" else ""
        // Valid OCR returns to TextEditing so the inserted text can be submitted even after a
        // previous assistant result state.
        return model.copy(
            state = AiDebugLabState.TextEditing,
            text = (model.text + separator + trimmed).trim(),
        )
    }

    /**
     * Hydrates the transcript from persisted daily chat history. Maps only the allow-listed
     * stored kinds and never manufactures Pending, Proposal, or Clarification states.
     *
     * A ledger flow in progress is left standing: hydration runs on every composition of the
     * screen, including the one right after an Activity recreation restored a review. Paving
     * over it with TextEditing here would throw away the restored review the moment the chat
     * history finished loading — the transcript is loaded, the review is not chat history.
     */
    fun onHistoryLoaded(
        model: AiDebugLabModel,
        history: List<com.cuentivo.app.data.local.AiDailyStoredMessage>,
    ): AiDebugLabModel {
        val mappedMessages = history.map { stored ->
            when (stored.kind) {
                AiDailyMessageKind.USER_TEXT -> AiChatMessage.UserText(stored.textContent.orEmpty())
                AiDailyMessageKind.ASSISTANT_GREETING -> AiChatMessage.AssistantGreeting
                AiDailyMessageKind.ASSISTANT_OUT_OF_SCOPE -> AiChatMessage.AssistantOutOfScope
                AiDailyMessageKind.ASSISTANT_UNAVAILABLE -> AiChatMessage.AssistantUnavailable
                AiDailyMessageKind.ASSISTANT_QUOTA_EXHAUSTED -> AiChatMessage.AssistantQuotaExhausted
                AiDailyMessageKind.ASSISTANT_RATE_LIMITED -> AiChatMessage.AssistantRateLimited
                AiDailyMessageKind.ASSISTANT_DISABLED -> AiChatMessage.AssistantDisabled
                AiDailyMessageKind.ASSISTANT_ENTITLEMENT_REQUIRED -> AiChatMessage.AssistantEntitlementRequired
            }
        }
        return when (model.state) {
            is AiDebugLabState.LedgerExtracting,
            is AiDebugLabState.LedgerReviewing,
            is AiDebugLabState.LedgerConfirming ->
                model.copy(messages = mappedMessages)
            else ->
                model.copy(
                    state = AiDebugLabState.TextEditing,
                    messages = mappedMessages,
                )
        }
    }

    /**
     * Handles a turn classified as out-of-scope. Only allowed from [AiDebugLabState.TextEditing]
     * with non-blank text. Trims current text, appends [AiChatMessage.UserText] and
     * [AiChatMessage.AssistantOutOfScope], clears draft text and remains in [AiDebugLabState.TextEditing].
     */
    fun onOutOfScope(model: AiDebugLabModel): AiDebugLabModel {
        if (model.text.isBlank() || model.state != AiDebugLabState.TextEditing) {
            return model
        }
        val userText = model.text.trim()
        val newMessages = model.messages +
            AiChatMessage.UserText(userText) +
            AiChatMessage.AssistantOutOfScope
        return model.copy(
            state = AiDebugLabState.TextEditing,
            text = "",
            messages = newMessages,
        )
    }

    /**
     * Completes an in-flight out-of-scope turn that was submitted. Replaces the trailing
     * [AiChatMessage.AssistantPending] with [AiChatMessage.AssistantOutOfScope], clears draft text
     * and returns to [AiDebugLabState.TextEditing]. If the generation token is stale or the state
     * is not [AiDebugLabState.Sending], returns [model] unchanged.
     */
    fun onOutOfScopeResult(model: AiDebugLabModel, generation: Long): AiDebugLabModel {
        if (model.state != AiDebugLabState.Sending || generation != model.generation) {
            return model
        }
        val messages = if (model.messages.isNotEmpty() &&
            model.messages.last() == AiChatMessage.AssistantPending
        ) {
            model.messages.dropLast(1) + AiChatMessage.AssistantOutOfScope
        } else {
            model.messages + AiChatMessage.AssistantOutOfScope
        }
        return model.copy(
            state = AiDebugLabState.TextEditing,
            messages = messages,
            text = "",
        )
    }

    fun onCancel(model: AiDebugLabModel): AiDebugLabModel =
        model.copy(state = AiDebugLabState.TextEditing, generation = model.generation + 1)

    /**
     * Generation-safe transition from a raw payment proposal to its resolved preview.
     *
     * Accepts only the current generation and only when the state is ProposalPreview whose
     * proposal is a RecordPaymentProposal AND the last message is the matching AssistantProposal.
     * Replaces that last message with the resolved result; never appends duplicate UserText,
     * persists anything or exposes confirmation. Fail-closed: any mismatch returns [model] unchanged.
     */
    fun onPaymentPreviewResult(
        model: AiDebugLabModel,
        result: AiPaymentPreviewResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val currentState = model.state
        if (currentState !is AiDebugLabState.ProposalPreview) return model
        val currentProposal = currentState.proposal as? RecordPaymentProposal ?: return model
        val lastMessage = model.messages.lastOrNull() ?: return model
        if (lastMessage !is AiChatMessage.AssistantProposal) return model
        val lastAsPayment = lastMessage.proposal as? RecordPaymentProposal ?: return model
        if (lastAsPayment != currentProposal) return model

        val assistantMessage: AiChatMessage = when (result) {
            is AiPaymentPreviewResult.CustomerNotFound -> AiChatMessage.AssistantPaymentCustomerNotFound
            is AiPaymentPreviewResult.CustomerSelectionRequired ->
                AiChatMessage.AssistantPaymentCustomerSelection(result.candidates)
            is AiPaymentPreviewResult.Ready ->
                AiChatMessage.AssistantPaymentPreview(result.preview)
            is AiPaymentPreviewResult.Blocked ->
                AiChatMessage.AssistantPaymentBlocked(result.reason, result.preview)
            is AiPaymentPreviewResult.Unavailable -> AiChatMessage.AssistantUnavailable
        }

        val messages = if (model.messages.isNotEmpty() &&
            model.messages.last() is AiChatMessage.AssistantProposal
        ) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages
        }
        val newState: AiDebugLabState = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
            is AiChatMessage.AssistantPaymentPreview -> AiDebugLabState.PaymentConfirmation(assistantMessage.preview)
            else -> AiDebugLabState.PaymentResolution(result)
        }
        return model.copy(state = newState, messages = messages, text = "")
    }

    /**
     * Generation-safe confirmation start for a resolved payment preview.
     *
     * Accepts only when state is [AiDebugLabState.PaymentConfirmation], the last message matches
     * the active preview, and the business ID is non-blank. Transitions to [AiDebugLabState.PaymentConfirming]
     * with an incremented generation token. Fail-closed: any mismatch returns [AiPaymentConfirmOutcome.Ignored].
     */
    fun onConfirmPayment(model: AiDebugLabModel, businessId: String): AiPaymentConfirmOutcome {
        if (businessId.isBlank()) return AiPaymentConfirmOutcome.Ignored
        val state = model.state as? AiDebugLabState.PaymentConfirmation ?: return AiPaymentConfirmOutcome.Ignored
        val preview = state.preview
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantPaymentPreview ?: return AiPaymentConfirmOutcome.Ignored
        if (lastMessage.preview != preview) return AiPaymentConfirmOutcome.Ignored

        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.PaymentConfirming(preview),
            generation = nextGeneration,
        )
        val request = AiPaymentConfirmationRequest(
            confirmationKey = preview.confirmationKey,
            businessId = businessId,
            customerId = preview.customerId,
            expectedBalanceCents = preview.currentBalanceCents,
            amountCents = preview.declaredAmountCents,
        )
        return AiPaymentConfirmOutcome.Started(
            model = nextModel,
            request = request,
            generation = nextGeneration,
        )
    }

    /**
     * Applies the result of a confirmed payment execution.
     *
     * Accepts only matching [generation] and [AiDebugLabState.PaymentConfirming] where the last
     * message is the corresponding [AiChatMessage.AssistantPaymentPreview]. Replaces the preview
     * message with the outcome; never leaves confirmation active or duplicate draft cards.
     */
    fun onPaymentExecutionResult(
        model: AiDebugLabModel,
        result: AiPaymentExecutionResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val state = model.state as? AiDebugLabState.PaymentConfirming ?: return model
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantPaymentPreview ?: return model
        if (lastMessage.preview != state.preview) return model

        val assistantMessage: AiChatMessage = when (result) {
            is AiPaymentExecutionResult.Recorded -> AiChatMessage.AssistantPaymentRecorded(result.paymentId)
            is AiPaymentExecutionResult.StalePreview -> AiChatMessage.AssistantPaymentStale
            is AiPaymentExecutionResult.Rejected -> AiChatMessage.AssistantPaymentRejected
            is AiPaymentExecutionResult.Unavailable -> AiChatMessage.AssistantUnavailable
        }

        val messages = if (model.messages.isNotEmpty() && model.messages.last() is AiChatMessage.AssistantPaymentPreview) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages + assistantMessage
        }
        val newState = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
            else -> AiDebugLabState.TextEditing
        }
        return model.copy(
            state = newState,
            messages = messages,
            text = "",
        )
    }

    /**
     * Generation-safe transition from a raw sale proposal to its resolved preview.
     *
     * Accepts only the current generation and only when the state is ProposalPreview whose
     * proposal is a CreateSaleProposal AND the last message is matching.
     * Replaces that last message with the resolved result; never appends duplicate UserText,
     * persists anything or exposes confirmation. Fail-closed: any mismatch returns [model] unchanged.
     */
    fun onSalePreviewResult(
        model: AiDebugLabModel,
        result: AiSalePreviewResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val currentState = model.state
        if (currentState !is AiDebugLabState.ProposalPreview) return model
        val currentProposal = currentState.proposal as? CreateSaleProposal ?: return model
        val currentChoices = currentState.saleChoices
        val lastMessage = model.messages.lastOrNull() ?: return model
        val isMatchingLastMessage = when (lastMessage) {
            is AiChatMessage.AssistantProposal -> (lastMessage.proposal as? CreateSaleProposal) == currentProposal
            is AiChatMessage.AssistantSaleCustomerSelection -> true
            is AiChatMessage.AssistantSaleProductSelection -> true
            is AiChatMessage.AssistantSaleBlocked -> true
            is AiChatMessage.AssistantSalePreview -> true
            else -> false
        }
        if (!isMatchingLastMessage) return model

        val assistantMessage: AiChatMessage = when (result) {
            is AiSalePreviewResult.CustomerSelectionRequired ->
                AiChatMessage.AssistantSaleCustomerSelection(result.candidates)
            is AiSalePreviewResult.ProductSelectionRequired ->
                AiChatMessage.AssistantSaleProductSelection(result.productName, result.candidates)
            is AiSalePreviewResult.Ready ->
                AiChatMessage.AssistantSalePreview(result.preview)
            is AiSalePreviewResult.Blocked ->
                AiChatMessage.AssistantSaleBlocked(result.reason, result.preview)
            is AiSalePreviewResult.Unavailable -> AiChatMessage.AssistantUnavailable
        }

        val messages = if (model.messages.isNotEmpty()) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages
        }
        val newState: AiDebugLabState = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
            is AiChatMessage.AssistantSalePreview ->
                AiDebugLabState.SaleConfirmation(assistantMessage.preview, currentProposal, currentChoices)
            else ->
                AiDebugLabState.SaleResolution(result, currentProposal, currentChoices)
        }
        return model.copy(state = newState, messages = messages, text = "")
    }

    /**
     * Applies a customer choice on a required customer selection clarification.
     */
    fun onChooseCustomer(
        model: AiDebugLabModel,
        choice: com.cuentivo.app.ai.AiEntityChoice,
    ): AiSaleChoiceOutcome {
        val state = model.state as? AiDebugLabState.SaleResolution ?: return AiSaleChoiceOutcome.Ignored
        val proposal = state.proposal ?: return AiSaleChoiceOutcome.Ignored
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantSaleCustomerSelection ?: return AiSaleChoiceOutcome.Ignored
        if (state.result !is AiSalePreviewResult.CustomerSelectionRequired) return AiSaleChoiceOutcome.Ignored
        if (choice is com.cuentivo.app.ai.AiEntityChoice.UseExisting) {
            if (lastMessage.candidates.none { it.customerId == choice.entityId }) {
                return AiSaleChoiceOutcome.Ignored
            }
        }
        val newChoices = com.cuentivo.app.ai.AiSaleResolutionChoices(
            customer = choice,
            productsByNormalizedName = state.choices.productsByNormalizedName,
        )
        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.ProposalPreview(proposal, newChoices),
            generation = nextGeneration,
        )
        return AiSaleChoiceOutcome.Selected(
            model = nextModel,
            proposal = proposal,
            choices = newChoices,
            generation = nextGeneration,
        )
    }

    /**
     * Applies a product choice on a required product selection clarification.
     */
    fun onChooseProduct(
        model: AiDebugLabModel,
        productName: String,
        choice: com.cuentivo.app.ai.AiEntityChoice,
    ): AiSaleChoiceOutcome {
        val state = model.state as? AiDebugLabState.SaleResolution ?: return AiSaleChoiceOutcome.Ignored
        val proposal = state.proposal ?: return AiSaleChoiceOutcome.Ignored
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantSaleProductSelection ?: return AiSaleChoiceOutcome.Ignored
        if (state.result !is AiSalePreviewResult.ProductSelectionRequired || lastMessage.requestedName != productName) {
            return AiSaleChoiceOutcome.Ignored
        }
        if (choice is com.cuentivo.app.ai.AiEntityChoice.UseExisting) {
            if (lastMessage.candidates.none { it.productId == choice.entityId }) {
                return AiSaleChoiceOutcome.Ignored
            }
        }
        val normalized = com.cuentivo.app.domain.CustomerNameNormalizer.key(productName)
        val updatedMap = state.choices.productsByNormalizedName + (normalized to choice)
        val newChoices = com.cuentivo.app.ai.AiSaleResolutionChoices(
            customer = state.choices.customer,
            productsByNormalizedName = updatedMap,
        )
        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.ProposalPreview(proposal, newChoices),
            generation = nextGeneration,
        )
        return AiSaleChoiceOutcome.Selected(
            model = nextModel,
            proposal = proposal,
            choices = newChoices,
            generation = nextGeneration,
        )
    }

    /**
     * Generation-safe confirmation start for a resolved sale preview.
     *
     * Accepts only when state is [AiDebugLabState.SaleConfirmation], the last message matches
     * the active preview, and the business ID is non-blank. Transitions to [AiDebugLabState.SaleConfirming]
     * with an incremented generation token. Fail-closed: any mismatch returns [AiSaleConfirmOutcome.Ignored].
     */
    fun onConfirmSale(
        model: AiDebugLabModel,
        businessId: String,
        /**
         * Hoy, en epoch day, para resolver el plazo elegido en el borrador (15 días por defecto).
         * Opcional para no romper a quien confirme sin reloj: sin él, un borrador sin fecha pactada
         * se registra sin fecha, como antes.
         */
        todayEpochDay: Long? = null,
    ): AiSaleConfirmOutcome {
        if (businessId.isBlank()) return AiSaleConfirmOutcome.Ignored
        val state = model.state as? AiDebugLabState.SaleConfirmation ?: return AiSaleConfirmOutcome.Ignored
        val preview = state.preview
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantSalePreview ?: return AiSaleConfirmOutcome.Ignored
        if (lastMessage.preview != preview) return AiSaleConfirmOutcome.Ignored

        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.SaleConfirming(preview),
            generation = nextGeneration,
        )
        val promisedDay: Long? = when (val promise = preview.promisedPaymentCandidate) {
            // La fecha que la persona dijo en el texto manda. Si no dijo ninguna, vale el plazo del
            // borrador; y si el plazo es "Sin fecha", la venta nace sin fecha límite, como se pidió.
            null -> todayEpochDay?.let { today ->
                state.dueDateDays?.let { days -> today + days }
            }
            is PromisedPaymentCandidate.DateCandidate -> {
                try {
                    LocalDate.of(promise.year, promise.month, promise.day).toEpochDay()
                } catch (_: Exception) {
                    return AiSaleConfirmOutcome.Ignored
                }
            }
        }
        val request = AiSaleConfirmationRequest(
            confirmationKey = preview.confirmationKey,
            businessId = businessId,
            customerId = preview.customerId,
            sourceCustomerFingerprint = preview.sourceCustomerFingerprint,
            lines = preview.lines.map { line ->
                AiSaleConfirmationLine.CatalogProduct(
                    productId = line.productId,
                    productNameSnapshot = line.productName,
                    quantity = line.quantity,
                    unitPriceCents = line.unitPriceCents,
                    lineTotalCents = line.lineTotalCents,
                    sourceProductFingerprint = line.sourceProductFingerprint,
                )
            },
            totalCents = preview.totalCents,
            initialPaymentCents = preview.initialPaymentCents,
            promisedPaymentEpochDay = promisedDay,
            newCustomer = preview.newCustomer,
            newProducts = preview.newProducts,
        )
        return AiSaleConfirmOutcome.Started(
            model = nextModel,
            request = request,
            generation = nextGeneration,
        )
    }

    /**
     * Cambia el plazo del borrador de venta. Sólo tiene sentido en [AiDebugLabState.SaleConfirmation]:
     * una vez confirmando, el plazo ya viajó en la petición y no puede cambiar por debajo.
     */
    fun onUpdateSaleDueDateDays(model: AiDebugLabModel, dueDateDays: Long?): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.SaleConfirmation ?: return model
        if (state.dueDateDays == dueDateDays) return model
        return model.copy(state = state.copy(dueDateDays = dueDateDays))
    }

    /**
     * Applies the result of a confirmed sale execution.
     *
     * Accepts only matching [generation] and [AiDebugLabState.SaleConfirming] where the last
     * message is the corresponding [AiChatMessage.AssistantSalePreview]. Replaces the preview
     * message with the outcome; never leaves confirmation active or duplicate draft cards.
     */
    fun onSaleExecutionResult(
        model: AiDebugLabModel,
        result: AiSaleExecutionResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val state = model.state as? AiDebugLabState.SaleConfirming ?: return model
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantSalePreview ?: return model
        if (lastMessage.preview != state.preview) return model

        val assistantMessage: AiChatMessage = when (result) {
            is AiSaleExecutionResult.Recorded -> AiChatMessage.AssistantSaleRecorded(
                saleId = result.saleId,
                paymentId = result.paymentId,
                customerName = result.customerName,
                balanceAfterCents = result.balanceAfterCents,
            )
            is AiSaleExecutionResult.StalePreview -> AiChatMessage.AssistantSaleStale
            is AiSaleExecutionResult.Rejected -> AiChatMessage.AssistantSaleRejected
            is AiSaleExecutionResult.Unavailable -> AiChatMessage.AssistantUnavailable
            is AiSaleExecutionResult.LimitReached -> AiChatMessage.AssistantSaleLimitReached
        }

        val messages = if (model.messages.isNotEmpty() && model.messages.last() is AiChatMessage.AssistantSalePreview) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages + assistantMessage
        }
        val newState = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
            else -> AiDebugLabState.TextEditing
        }
        return model.copy(
            state = newState,
            messages = messages,
            text = "",
        )
    }

    /**
     * Generation-safe transition from a raw query proposal or clarification choice to its resolved answer.
     *
     * Accepts only matching [generation] and only when the current state is [AiDebugLabState.ProposalPreview]
     * with a [BusinessQueryProposal] (initial resolution) or [AiDebugLabState.QueryResolving] (subsequent
     * choice resolution). Any already finalized state such as [AiDebugLabState.QueryAnswer] is ignored.
     * Replaces the pending/clarification message with the resolved answer; never writes to Room.
     */
    fun onQueryResult(
        model: AiDebugLabModel,
        result: AiBusinessQueryResult,
        generation: Long,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val currentState = model.state
        val currentProposal = when (currentState) {
            is AiDebugLabState.ProposalPreview -> currentState.proposal as? BusinessQueryProposal
            is AiDebugLabState.QueryResolving -> currentState.proposal
            else -> null
        } ?: return model

        val lastMessage = model.messages.lastOrNull() ?: return model
        val isMatchingLastMessage = when (currentState) {
            is AiDebugLabState.ProposalPreview ->
                lastMessage is AiChatMessage.AssistantProposal &&
                    lastMessage.proposal == currentProposal
            is AiDebugLabState.QueryResolving ->
                lastMessage is AiChatMessage.AssistantQueryAnswer
            else -> false
        }
        if (!isMatchingLastMessage) return model

        val assistantMessage: AiChatMessage = when (result) {
            AiBusinessQueryResult.Unavailable -> AiChatMessage.AssistantUnavailable
            else -> AiChatMessage.AssistantQueryAnswer(result)
        }

        val messages = if (model.messages.isNotEmpty()) {
            model.messages.dropLast(1) + assistantMessage
        } else {
            model.messages
        }
        val newState = when (assistantMessage) {
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
            is AiChatMessage.AssistantQueryAnswer -> AiDebugLabState.QueryAnswer(result, currentProposal)
            else -> AiDebugLabState.TextEditing
        }
        return model.copy(
            state = newState,
            messages = messages,
            text = "",
        )
    }

    /**
     * Applies a customer choice on a required query customer selection clarification.
     *
     * Validates that the customer ID exists in the active candidate list; arbitrary IDs are rejected.
     * Transitions to [AiDebugLabState.QueryResolving] with an incremented generation token.
     */
    fun onChooseQueryCustomer(
        model: AiDebugLabModel,
        customerId: String,
    ): AiQueryChoiceOutcome {
        val state = model.state as? AiDebugLabState.QueryAnswer ?: return AiQueryChoiceOutcome.Ignored
        val proposal = state.proposal ?: return AiQueryChoiceOutcome.Ignored
        val selectionResult = state.result as? AiBusinessQueryResult.CustomerSelectionRequired ?: return AiQueryChoiceOutcome.Ignored
        val lastMessage = model.messages.lastOrNull() as? AiChatMessage.AssistantQueryAnswer ?: return AiQueryChoiceOutcome.Ignored
        val lastSelection = lastMessage.result as? AiBusinessQueryResult.CustomerSelectionRequired ?: return AiQueryChoiceOutcome.Ignored
        if (selectionResult.candidates.none { it.customerId == customerId } || lastSelection.candidates.none { it.customerId == customerId }) {
            return AiQueryChoiceOutcome.Ignored
        }

        val choice = com.cuentivo.app.ai.AiEntityChoice.UseExisting(customerId)
        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.QueryResolving(proposal),
            generation = nextGeneration,
        )
        return AiQueryChoiceOutcome.Selected(
            model = nextModel,
            proposal = proposal,
            choice = choice,
            generation = nextGeneration,
        )
    }

    /**
     * Starts ledger extraction. Validates non-blank sessionId and businessId.
     * Bumps generation token and transitions to LedgerExtracting.
     */
    fun onStartLedgerExtraction(
        model: AiDebugLabModel,
        sessionId: String,
        businessId: String,
    ): AiDebugLabModel {
        if (sessionId.isBlank() || businessId.isBlank()) return model
        val nextGeneration = model.generation + 1
        return model.copy(
            state = AiDebugLabState.LedgerExtracting(
                sessionId = sessionId,
                businessId = businessId,
            ),
            generation = nextGeneration,
        )
    }

    /**
     * Delivers ledger extraction results. Only applies if matching active session, business,
     * generation, and state is LedgerExtracting. Builds deterministic LedgerReview.
     */
    fun onLedgerExtractionResult(
        model: AiDebugLabModel,
        sessionId: String,
        businessId: String,
        operations: List<LedgerOperation>,
        discards: List<LedgerDiscardDiagnostic> = emptyList(),
        unreadTextRemaining: Boolean = false,
        pageIncomplete: Boolean = false,
        existingCustomerBalances: Map<String, Long> = emptyMap(),
        customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate> = emptyList(),
        productCandidates: List<com.cuentivo.app.domain.ProductCandidate> = emptyList(),
        registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection> = emptyList(),
        generation: Long,
        todayEpochDay: Long = java.time.LocalDate.now().toEpochDay(),
        detectedPageDate: com.cuentivo.app.ai.ledger.LedgerDetectedPageDate? = null,
        pageType: com.cuentivo.app.ai.ledger.LedgerPageType? = null,
        pageTotals: List<com.cuentivo.app.ai.ledger.LedgerPageTotal> = emptyList(),
        /**
         * The cross-check against the Ledger de MexaLM, when the server sent one.
         *
         * It travels into the review as a comment: it says where the two readings disagree and
         * nothing else. No transition below reads it to decide what gets registered.
         */
        mexalmCrosscheck: com.cuentivo.app.ai.ledger.MexalmCrosscheckPayload? = null,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val state = model.state as? AiDebugLabState.LedgerExtracting ?: return model
        if (state.sessionId != sessionId || state.businessId != businessId) return model

        val frozenBalances: Map<String, Long> = Collections.unmodifiableMap(
            LinkedHashMap(existingCustomerBalances)
        )

        val review = LedgerReviewEngine.build(
            operations = operations,
            discards = discards,
            unreadTextRemaining = unreadTextRemaining,
            pageIncomplete = pageIncomplete,
            existingCustomerBalances = frozenBalances,
            todayEpochDay = todayEpochDay,
            detectedPageDate = detectedPageDate,
            pageType = pageType,
            pageTotals = pageTotals,
            mexalmCrosscheck = mexalmCrosscheck,
        )

        return model.copy(
            state = AiDebugLabState.LedgerReviewing(
                sessionId = sessionId,
                businessId = businessId,
                review = review,
                originalOperations = java.util.Collections.unmodifiableList(ArrayList(operations)),
                existingCustomerBalances = frozenBalances,
                customerCandidates = java.util.Collections.unmodifiableList(ArrayList(customerCandidates)),
                productCandidates = java.util.Collections.unmodifiableList(ArrayList(productCandidates)),
                registeredOperations = java.util.Collections.unmodifiableList(ArrayList(registeredOperations)),
            ),
        )
    }

    /**
     * Restores a review that survived Activity recreation.
     *
     * Only valid when there is no ledger flow already live: a restore landing on top of a live
     * review would overwrite fresher corrections with the snapshot taken before them. The saved
     * operations are the person's work and are rebuilt verbatim; the balances, candidates and
     * registered rows arrive reloaded from Room, because they are derivable and were never saved.
     * Nothing here calls the callable — the extraction result is inside [saved].
     */
    internal fun onRestoreLedgerReview(
        model: AiDebugLabModel,
        sessionId: String,
        businessId: String,
        saved: com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview,
        existingCustomerBalances: Map<String, Long>,
        customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate>,
        productCandidates: List<com.cuentivo.app.domain.ProductCandidate>,
        registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection>,
    ): AiDebugLabModel {
        if (sessionId.isBlank() || businessId.isBlank()) return model
        if (model.state is AiDebugLabState.LedgerExtracting ||
            model.state is AiDebugLabState.LedgerReviewing ||
            model.state is AiDebugLabState.LedgerConfirming
        ) {
            return model
        }
        if (saved.sessionId != sessionId || saved.businessId != businessId) return model

        val frozenBalances: Map<String, Long> = Collections.unmodifiableMap(
            LinkedHashMap(existingCustomerBalances)
        )
        val review = LedgerReviewEngine.build(
            operations = saved.operations,
            discards = saved.discards,
            unreadTextRemaining = saved.unreadTextRemaining,
            pageIncomplete = saved.pageIncomplete,
            existingCustomerBalances = frozenBalances,
            pageDateEpochDay = saved.pageDateEpochDay,
            todayEpochDay = saved.todayEpochDay,
            detectedPageDate = saved.detectedPageDate,
            pageType = saved.pageType,
            pageTotals = saved.pageTotals,
        )
        return model.copy(
            state = AiDebugLabState.LedgerReviewing(
                sessionId = sessionId,
                businessId = businessId,
                review = review,
                originalOperations = java.util.Collections.unmodifiableList(ArrayList(saved.originalOperations)),
                initialDuplicateCount = saved.initialDuplicateCount,
                existingCustomerBalances = frozenBalances,
                customerCandidates = Collections.unmodifiableList(ArrayList(customerCandidates)),
                productCandidates = Collections.unmodifiableList(ArrayList(productCandidates)),
                registeredOperations = Collections.unmodifiableList(ArrayList(registeredOperations)),
                isLegacySaveWithoutSessionKey = !com.cuentivo.app.domain.LedgerReviewSchema.canRegisterRestoredReview(saved.reviewSchemaVersion),
                dueDateDays = saved.dueDateDays,
            ),
            // A restored review is a new generation: any in-flight result from before the
            // recreation must not land on it.
            generation = model.generation + 1,
        )
    }

    /**
     * Updates the page date of the active review in LedgerReviewing.
     */    fun onUpdateLedgerPageDate(
        model: AiDebugLabModel,
        sessionId: String,
        pageDateEpochDay: Long?,
        todayEpochDay: Long? = null,
    ): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return model
        if (state.sessionId != sessionId) return model

        val effectiveTodayEpochDay = todayEpochDay ?: state.review.todayEpochDay ?: java.time.LocalDate.now().toEpochDay()

        val updatedReview = LedgerReviewEngine.build(
            operations = state.review.operations,
            discards = state.review.discards,
            unreadTextRemaining = state.review.unreadTextRemaining,
            pageIncomplete = state.review.pageIncomplete,
            existingCustomerBalances = state.existingCustomerBalances,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = effectiveTodayEpochDay,
            detectedPageDate = state.review.detectedPageDate,
            mexalmCrosscheck = state.review.mexalmCrosscheck,
        )

        return model.copy(
            state = state.copy(
                review = updatedReview,
            ),
        )
    }

    /**
     * Reemplaza las operaciones ya registradas contra las que se comparan los duplicados (v44).
     *
     * La consulta a Room se hizo una vez al extraer/restaurar, con la ventana de fechas que había
     * entonces. Si durante la revisión cambia la fecha de un renglón o la de la página, esa ventana
     * deja de cubrir lo que el detector compara, así que hay que volver a consultar. Sólo válido en
     * LedgerReviewing con el sessionId que coincide.
     */
    fun onUpdateLedgerRegisteredOperations(
        model: AiDebugLabModel,
        sessionId: String,
        registeredOperations: List<com.cuentivo.app.domain.RegisteredOperationProjection>,
    ): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return model
        if (state.sessionId != sessionId) return model
        return model.copy(
            state = state.copy(
                registeredOperations = java.util.Collections.unmodifiableList(ArrayList(registeredOperations)),
            ),
        )
    }

    /**
     * Pure update to the active reviews operations. Only valid while in LedgerReviewing with matching sessionId.
     * Retains the existing customer balances context without requiring callers to re-inject it.
     */
    fun onUpdateLedgerOperations(
        model: AiDebugLabModel,
        sessionId: String,
        existingCustomerBalances: Map<String, Long>? = null,
        todayEpochDay: Long? = null,
        transform: (List<LedgerOperation>) -> List<LedgerOperation>,
    ): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return model
        if (state.sessionId != sessionId) return model

        val balancesToUse = Collections.unmodifiableMap(
            LinkedHashMap(existingCustomerBalances ?: state.existingCustomerBalances)
        )
        val updatedOps = transform(state.review.operations)
        val effectiveTodayEpochDay = todayEpochDay ?: state.review.todayEpochDay ?: java.time.LocalDate.now().toEpochDay()
        val updatedReview = LedgerReviewEngine.build(
            operations = updatedOps,
            discards = state.review.discards,
            unreadTextRemaining = state.review.unreadTextRemaining,
            pageIncomplete = state.review.pageIncomplete,
            existingCustomerBalances = balancesToUse,
            pageDateEpochDay = state.review.pageDateEpochDay,
            todayEpochDay = effectiveTodayEpochDay,
            detectedPageDate = state.review.detectedPageDate,
            mexalmCrosscheck = state.review.mexalmCrosscheck,
        )

        return model.copy(
            state = state.copy(
                review = updatedReview,
                existingCustomerBalances = balancesToUse,
            ),
        )
    }

    /**
     * Transitions from LedgerReviewing to LedgerConfirming with incremented generation.
     *
     * In-flight extraction or older confirmations are invalidated by the generation step.
     */
    fun onConfirmLedger(
        model: AiDebugLabModel,
        sessionId: String,
        businessId: String,
    ): AiLedgerConfirmOutcome {
        if (sessionId.isBlank() || businessId.isBlank()) return AiLedgerConfirmOutcome.Ignored
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return AiLedgerConfirmOutcome.Ignored
        if (state.sessionId != sessionId || state.businessId != businessId) return AiLedgerConfirmOutcome.Ignored
        if (state.isLegacySaveWithoutSessionKey) return AiLedgerConfirmOutcome.Ignored

        // El modelo dijo que puede faltar texto: registrar necesita la palabra de la persona a
        // nivel de página. Sin ella sería un registro parcial de la hoja, en silencio.
        if (!com.cuentivo.app.domain.LedgerPageCompleteness.isRegistrable(
                com.cuentivo.app.domain.LedgerPageCompleteness.modelSaysTextMayBeMissing(state.review),
                state.isPageCompletenessConfirmed,
            )
        ) {
            return AiLedgerConfirmOutcome.Ignored
        }

        // Financial & date gate: must be confirmable
        if (!state.review.summary.canConfirm) {
            return AiLedgerConfirmOutcome.Ignored
        }

        val nextGeneration = model.generation + 1
        val nextModel = model.copy(
            state = AiDebugLabState.LedgerConfirming(
                sessionId = sessionId,
                businessId = businessId,
                review = state.review,
                originalOperations = state.originalOperations,
                initialDuplicateCount = state.initialDuplicateCount,
                existingCustomerBalances = state.existingCustomerBalances,
                customerCandidates = state.customerCandidates,
                productCandidates = state.productCandidates,
                registeredOperations = state.registeredOperations,
                isLegacySaveWithoutSessionKey = state.isLegacySaveWithoutSessionKey,
                isPageCompletenessConfirmed = state.isPageCompletenessConfirmed,
                dueDateDays = state.dueDateDays,
            ),
            generation = nextGeneration,
        )

        return AiLedgerConfirmOutcome.Started(
            model = nextModel,
            sessionId = sessionId,
            businessId = businessId,
            review = state.review,
            generation = nextGeneration,
        )
    }

    /** Confirma la integridad de la hoja sin modificar ninguno de sus renglones. */
    fun onConfirmLedgerPageCompleteness(model: AiDebugLabModel, sessionId: String, businessId: String): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return model
        if (state.sessionId != sessionId || state.businessId != businessId) return model
        return model.copy(state = state.copy(isPageCompletenessConfirmed = true))
    }

    /**
     * El plazo que la persona eligió, para la página entera.
     *
     * Sólo el valor viaja: el cálculo de la fecha límite y del aviso de vencidas vive en
     * `domain/LedgerDueDatePolicy`, no aquí ni en el Composable.
     */
    fun onUpdateLedgerDueDateDays(
        model: AiDebugLabModel,
        sessionId: String,
        dueDateDays: Long?,
    ): AiDebugLabModel {
        val state = model.state as? AiDebugLabState.LedgerReviewing ?: return model
        if (state.sessionId != sessionId) return model
        return model.copy(state = state.copy(dueDateDays = dueDateDays))
    }

    /**
     * Cancels any active ledger flow (extraction, review, confirmation), invalidates in-flight
     * responses via generation increment, and cleanly resets state to TextEditing.
     */
    /**
     * Ends a confirmation. Guarded by session, business and generation like every other ledger
     * transition: a write that answers late must never land on a page the person already left.
     *
     * Failure returns to the review with the operations intact rather than to the chat, because
     * losing a reviewed page to a transient error would mean photographing and correcting it again.
     */
    fun onLedgerBatchResult(
        model: AiDebugLabModel,
        sessionId: String,
        businessId: String,
        generation: Long,
        result: com.cuentivo.app.domain.LedgerBatchConfirmation?,
    ): AiDebugLabModel {
        if (generation != model.generation) return model
        val state = model.state as? AiDebugLabState.LedgerConfirming ?: return model
        if (state.sessionId != sessionId || state.businessId != businessId) return model
        return when (result) {
            is com.cuentivo.app.domain.LedgerBatchConfirmation.Recorded -> model.copy(
                state = AiDebugLabState.TextEditing,
                messages = model.messages + AiChatMessage.AssistantLedgerRecorded(result.operationsCount),
                generation = model.generation + 1,
            )
            is com.cuentivo.app.domain.LedgerBatchConfirmation.AlreadyRecorded -> model.copy(
                state = AiDebugLabState.TextEditing,
                messages = model.messages + AiChatMessage.AssistantLedgerAlreadyRecorded(result.operationsCount),
                generation = model.generation + 1,
            )
            null, com.cuentivo.app.domain.LedgerBatchConfirmation.Failed -> {
            model.copy(
                state = AiDebugLabState.LedgerReviewing(
                    sessionId = state.sessionId,
                    businessId = state.businessId,
                    review = state.review,
                    originalOperations = state.originalOperations,
                    initialDuplicateCount = state.initialDuplicateCount,
                    existingCustomerBalances = state.existingCustomerBalances,
                    customerCandidates = state.customerCandidates,
                    productCandidates = state.productCandidates,
                    registeredOperations = state.registeredOperations,
                    isLegacySaveWithoutSessionKey = state.isLegacySaveWithoutSessionKey,
                    dueDateDays = state.dueDateDays,
                ),
                messages = model.messages + AiChatMessage.AssistantLedgerFailed,
                generation = model.generation + 1,
            )
            }
        }
    }

    fun onCancelLedger(model: AiDebugLabModel): AiDebugLabModel {
        return when (model.state) {
            is AiDebugLabState.LedgerExtracting,
            is AiDebugLabState.LedgerReviewing,
            is AiDebugLabState.LedgerConfirming -> model.copy(
                state = AiDebugLabState.TextEditing,
                generation = model.generation + 1,
            )
            else -> model
        }
    }

    fun onReset(model: AiDebugLabModel): AiDebugLabModel =
        model.copy(
            state = AiDebugLabState.TextEditing,
            text = "",
            messages = emptyList(),
            generation = model.generation + 1,
        )
}
