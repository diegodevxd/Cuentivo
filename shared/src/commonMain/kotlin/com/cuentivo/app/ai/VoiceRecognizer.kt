package com.cuentivo.app.ai

/**
 * Narrow seam for speech-to-text recognition.
 * Decouples speech recognition implementation from state machines and test runners.
 *
 * Vive en común desde F5-C: la máquina de estados es la misma y cada plataforma pone su motor.
 */
interface VoiceRecognizer {
    fun isAvailable(): Boolean
    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
        onRmsChanged: ((Float) -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onListeningStarted: (() -> Unit)? = null,
    )
    fun cancel()
    fun destroy()
}
