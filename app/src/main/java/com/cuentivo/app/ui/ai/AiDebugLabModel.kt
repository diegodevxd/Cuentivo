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
    internal val generation: Long = 0L,
) {
    /** Blank text or an in-flight request must not send; the gateway is never called then. */
    val canSubmit: Boolean
        get() = text.isNotBlank() && state == AiDebugLabState.TextEditing
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
        if (model.state == AiDebugLabState.TextEditing) model.copy(text = newText) else model

    fun onSubmit(model: AiDebugLabModel): AiDebugLabSubmitOutcome {
        if (model.text.isBlank()) {
            return AiDebugLabSubmitOutcome.IgnoredBlank
        }
        if (model.state != AiDebugLabState.TextEditing) {
            return AiDebugLabSubmitOutcome.IgnoredBusy
        }
        val next = model.copy(state = AiDebugLabState.Sending, generation = model.generation + 1)
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
        return when (result) {
            CallableAiProposalResult.Unavailable ->
                model.copy(state = AiDebugLabState.Unavailable)

            is CallableAiProposalResult.Proposal ->
                when (val validation = SafeAiProposalValidator.validate(result.proposal)) {
                    is AiProposalValidationResult.Accepted ->
                        when (val proposal = validation.proposal) {
                            is RequiresClarificationProposal ->
                                model.copy(
                                    state = AiDebugLabState.Clarification(
                                        proposal.reason,
                                        proposal.unresolvedSlots,
                                    ),
                                )

                            else -> model.copy(state = AiDebugLabState.ProposalPreview(proposal))
                        }

                    is AiProposalValidationResult.Rejected ->
                        model.copy(state = AiDebugLabState.Unavailable)
                }
        }
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
            generation = model.generation + 1,
        )
}
