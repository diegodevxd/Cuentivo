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
    data class Listening(
        val partialText: String = "",
        val level: Float = 0f,
    ) : VoiceCaptureState
    data class Processing(
        val partialText: String = "",
    ) : VoiceCaptureState
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
        // Single-flight: cannot restart while already listening or processing.
        if (model.state is VoiceCaptureState.Listening || model.state is VoiceCaptureState.Processing) {
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
        return model.copy(state = model.state.copy(partialText = partialText))
    }

    fun onEndOfSpeech(
        model: VoiceCaptureModel,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || model.state !is VoiceCaptureState.Listening) {
            return model
        }
        return model.copy(
            state = VoiceCaptureState.Processing(partialText = model.state.partialText),
        )
    }

    // DEUDA TÉCNICA / HUECO CONOCIDO:
    // La conexión real entre los callbacks del reconocedor (VoiceRecognizer) y la pantalla (AiDebugLabScreen)
    // NO está cubierta por pruebas unitarias de la JVM. Cubrirla de forma fidedigna y sin objetos paralelos
    // ficticios exige extraer esa coordinación a un objeto orquestador que la pantalla realmente instancie y use.
    fun onListeningResumed(
        model: VoiceCaptureModel,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation) {
            return model
        }
        val partial = when (val s = model.state) {
            is VoiceCaptureState.Listening -> s.partialText
            is VoiceCaptureState.Processing -> s.partialText
            else -> return model
        }
        return model.copy(
            state = VoiceCaptureState.Listening(partialText = partial, level = 0f),
        )
    }

    fun onFinalResult(
        model: VoiceCaptureModel,
        text: String,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || (model.state !is VoiceCaptureState.Listening && model.state !is VoiceCaptureState.Processing)) {
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
        if (generation != model.generation || (model.state !is VoiceCaptureState.Listening && model.state !is VoiceCaptureState.Processing)) {
            return model
        }
        return model.copy(state = VoiceCaptureState.Unavailable(reason))
    }

    fun onAmplitude(
        model: VoiceCaptureModel,
        level: Float,
        generation: Long,
    ): VoiceCaptureModel {
        if (generation != model.generation || model.state !is VoiceCaptureState.Listening) {
            return model
        }
        val currentListening = model.state
        return model.copy(
            state = currentListening.copy(level = level.coerceIn(0f, 1f)),
        )
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
}

/**
 * Pure function mapping Android framework rmsdB (typically -2dB to 10dB)
 * to a normalized 0f..1f range bounded by coerceIn(0f, 1f).
 */
fun normalizeRmsDb(rmsdB: Float): Float {
    val minDb = -2f
    val maxDb = 10f
    return ((rmsdB - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
}
