package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiDailyMessageKind
import com.cuentivo.app.ai.AiPaymentBlockReason
import com.cuentivo.app.ai.AiPaymentCustomerCandidate
import com.cuentivo.app.ai.AiPaymentPreviewResult
import com.cuentivo.app.ai.AiProposal
import com.cuentivo.app.ai.AiProposalValidationResult
import com.cuentivo.app.ai.AiResolvedPaymentPreview
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.ClarificationReason
import com.cuentivo.app.ai.RecordPaymentProposal
import com.cuentivo.app.ai.RequiresClarificationProposal
import com.cuentivo.app.ai.SafeAiProposalValidator
import com.cuentivo.app.ai.UnresolvedSlot
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
    data class ProposalPreview(val proposal: AiProposal) : AiDebugLabState
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
}

/**
 * Immutable, temporary operational chat transcript message. Purely presentational: no
 * confirmation, save, execute, callback or domain dependency. Nothing here persists.
 */
sealed interface AiChatMessage {
    data class UserText(val text: String) : AiChatMessage
    data object AssistantGreeting : AiChatMessage
    data object AssistantOutOfScope : AiChatMessage
    data object AssistantPending : AiChatMessage
    data object AssistantUnavailable : AiChatMessage
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
            AiDebugLabState.Sending -> model
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
            AiChatMessage.AssistantUnavailable -> AiDebugLabState.Unavailable
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
            }
        }
        return model.copy(
            state = AiDebugLabState.TextEditing,
            messages = mappedMessages,
        )
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
        return model.copy(messages = messages)
    }

    fun onReset(model: AiDebugLabModel): AiDebugLabModel =
        model.copy(
            state = AiDebugLabState.TextEditing,
            text = "",
            messages = emptyList(),
            generation = model.generation + 1,
        )
}
