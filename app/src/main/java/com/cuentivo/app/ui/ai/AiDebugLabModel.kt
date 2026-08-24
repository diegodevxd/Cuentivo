package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiProposal
import com.cuentivo.app.ai.AiProposalValidationResult
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.ClarificationReason
import com.cuentivo.app.ai.RequiresClarificationProposal
import com.cuentivo.app.ai.SafeAiProposalValidator
import com.cuentivo.app.ai.UnresolvedSlot

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
    data object AssistantPending : AiChatMessage
    data object AssistantUnavailable : AiChatMessage
    data class AssistantClarification(
        val reason: ClarificationReason,
        val slots: Set<UnresolvedSlot>,
    ) : AiChatMessage
    data class AssistantProposal(val proposal: AiProposal) : AiChatMessage
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
        return model.copy(text = (model.text + separator + trimmed).trim())
    }

    fun onCancel(model: AiDebugLabModel): AiDebugLabModel =
        model.copy(state = AiDebugLabState.TextEditing, generation = model.generation + 1)

    fun onReset(model: AiDebugLabModel): AiDebugLabModel =
        model.copy(
            state = AiDebugLabState.TextEditing,
            text = "",
            messages = emptyList(),
            generation = model.generation + 1,
        )
}
