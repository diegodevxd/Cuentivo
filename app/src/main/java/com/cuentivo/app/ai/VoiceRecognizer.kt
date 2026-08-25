package com.cuentivo.app.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Maps Android framework [SpeechRecognizer] error codes to pure [VoiceUnavailableReason].
 */
fun mapSpeechRecognizerError(errorCode: Int): VoiceUnavailableReason =
    when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceUnavailableReason.NO_SPEECH

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceUnavailableReason.PERMISSION_DENIED

        else -> VoiceUnavailableReason.RECOGNIZER_ERROR
    }

/**
 * Decision helper for recognizer offline preference based on on-device availability.
 * Returns true only when on-device recognition is explicitly available.
 */
fun shouldPreferOfflineRecognition(isOnDeviceAvailable: Boolean): Boolean = isOnDeviceAvailable

/**
 * Narrow seam for speech-to-text recognition.
 * Decouples speech recognition implementation from state machines and test runners.
 */
interface VoiceRecognizer {
    fun isAvailable(): Boolean
    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
    )
    fun cancel()
    fun destroy()
}

/**
 * Android framework [SpeechRecognizer] wrapper.
 * Prefers on-device recognition when available with EXTRA_PREFER_OFFLINE; falls back to
 * system/network recognition without forcing offline mode. Configured for "es-MX".
 * Never stores or uploads audio recordings.
 */
class AndroidVoiceRecognizer(
    private val context: Context,
) : VoiceRecognizer {

    private var recognizer: SpeechRecognizer? = null

    private data class RecognizerSelection(
        val recognizer: SpeechRecognizer,
        val preferOffline: Boolean,
    )

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
    ) {
        destroy()

        if (!isAvailable()) {
            onError(VoiceUnavailableReason.NO_RECOGNIZER)
            return
        }

        val selection = createRecognizer()
        val speechRecognizer = selection.recognizer
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                onError(mapSpeechRecognizerError(error))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                onFinalResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                onPartialResult(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALE_ES_MX)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LOCALE_ES_MX)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, LOCALE_ES_MX)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (shouldPreferOfflineRecognition(selection.preferOffline)) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        speechRecognizer.startListening(intent)
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    private fun createRecognizer(): RecognizerSelection {
        val isOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        return if (isOnDevice) {
            RecognizerSelection(
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context),
                preferOffline = true,
            )
        } else {
            RecognizerSelection(
                recognizer = SpeechRecognizer.createSpeechRecognizer(context),
                preferOffline = false,
            )
        }
    }

    companion object {
        private const val LOCALE_ES_MX = "es-MX"
    }
}
