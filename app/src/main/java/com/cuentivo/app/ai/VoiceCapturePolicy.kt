package com.cuentivo.app.ai

/**
 * Reasons why voice capture cannot proceed or failed.
 * Fixed vocabulary with no free-form text. The UI layer resolves localized presentation strings.
 */
enum class VoiceUnavailableReason {
    NO_RECOGNIZER,
    PERMISSION_DENIED,
    NO_SPEECH,
    RECOGNIZER_ERROR,
}

/**
 * Pure, non-executable states for the voice capture workflow.
 * Voice is strictly an input method for the existing text field: it never initiates
 * proposals or confirmations directly.
 */
sealed interface VoiceCaptureState {
    data object Idle : VoiceCaptureState
    data object PermissionRequired : VoiceCaptureState
    data class Listening(val partialText: String = "") : VoiceCaptureState
    data class Transcribed(val text: String) : VoiceCaptureState
    data class Unavailable(val reason: VoiceUnavailableReason) : VoiceCaptureState
}

/**
 * Immutable state container for voice capture.
 * [generation] is the single-flight and cancellation token used to discard stale async callbacks.
 */
data class VoiceCaptureModel(
    val state: VoiceCaptureState = VoiceCaptureState.Idle,
    val generation: Long = 0L,
)

/**
 * Pure deterministic state machine for voice capture transitions.
 * Zero Android dependencies, zero I/O, zero coroutines, zero logging.
 */
object VoiceCaptureMachine {

    fun onMicTapped(
        model: VoiceCaptureModel,
        hasPermission: Boolean,
        isRecognizerAvailable: Boolean = true,
    ): VoiceCaptureModel {
        // Single-flight: cannot restart while already listening.
        if (model.state is VoiceCaptureState.Listening) {
            return model
        }
        val nextGeneration = model.generation + 1
        if (!isRecognizerAvailable) {
            return model.copy(
                state = VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_RECOGNIZER),
                generation = nextGeneration,
            )
        }
        if (!hasPermission) {
            return model.copy(
                state = VoiceCaptureState.PermissionRequired,
                generation = nextGeneration,
            )
        }
        return model.copy(
            state = VoiceCaptureState.Listening(),
            generation = nextGeneration,
        )
    }

    fun onPermissionGranted(
        model: VoiceCaptureModel,
        isRecognizerAvailable: Boolean = true,
    ): VoiceCaptureModel {
        if (model.state != VoiceCaptureState.PermissionRequired) {
            return model
        }
        val nextGeneration = model.generation + 1
        if (!isRecognizerAvailable) {
            return model.copy(
                state = VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_RECOGNIZER),
                generation = nextGeneration,
            )
        }
        return model.copy(
            state = VoiceCaptureState.Listening(),
            generation = nextGeneration,
        )
    }

    fun onPermissionDenied(model: VoiceCaptureModel): VoiceCaptureModel {
        if (model.state != VoiceCaptureState.PermissionRequired) {
            return model
        }
        return model.copy(
            state = VoiceCaptureState.Unavailable(VoiceUnavailableReason.PERMISSION_DENIED),
            generation = model.generation + 1,
        )
    }

    fun onPartialResult(
        model: VoiceCaptureModel,
        partialText: String,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || model.state !is VoiceCaptureState.Listening) {
            return model
        }
        return model.copy(state = VoiceCaptureState.Listening(partialText = partialText))
    }

    fun onFinalResult(
        model: VoiceCaptureModel,
        text: String,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || model.state !is VoiceCaptureState.Listening) {
            return model
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return model.copy(state = VoiceCaptureState.Unavailable(VoiceUnavailableReason.NO_SPEECH))
        }
        return model.copy(state = VoiceCaptureState.Transcribed(text = trimmed))
    }

    fun onError(
        model: VoiceCaptureModel,
        reason: VoiceUnavailableReason,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || model.state !is VoiceCaptureState.Listening) {
            return model
        }
        return model.copy(state = VoiceCaptureState.Unavailable(reason))
    }

    fun onCancel(model: VoiceCaptureModel): VoiceCaptureModel {
        if (model.state == VoiceCaptureState.Idle) {
            return model
        }
        return model.copy(
            state = VoiceCaptureState.Idle,
            generation = model.generation + 1,
        )
    }

    fun onReset(model: VoiceCaptureModel): VoiceCaptureModel =
        model.copy(state = VoiceCaptureState.Idle)
}
