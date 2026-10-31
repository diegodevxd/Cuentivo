package com.cuentivo.app.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Standard Android SpeechRecognizer error codes for language pack availability.
 */
const val ERROR_LANGUAGE_UNAVAILABLE_CODE = 12
const val ERROR_LANGUAGE_NOT_SUPPORTED_CODE = 13

/**
 * Returns true if the error code indicates a missing or unsupported language pack.
 */
fun isLanguageUnavailableError(errorCode: Int): Boolean =
    errorCode == ERROR_LANGUAGE_UNAVAILABLE_CODE || errorCode == ERROR_LANGUAGE_NOT_SUPPORTED_CODE

/**
 * Returns true if a speech recognition failure on the on-device engine
 * was caused by missing or unsupported language packs and should trigger
 * a one-time fallback to the system/network recognizer.
 */
fun shouldRetryWithSystemRecognizer(isOnDevice: Boolean, errorCode: Int): Boolean =
    isOnDevice && isLanguageUnavailableError(errorCode)

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
        onRmsChanged: ((Float) -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onListeningStarted: (() -> Unit)? = null,
    )
    fun cancel()
    fun destroy()
}

/**
 * Android framework [SpeechRecognizer] wrapper.
 * Prefers on-device recognition when available with EXTRA_PREFER_OFFLINE; falls back to
 * system/network recognition without forcing offline mode if the on-device engine lacks
 * the requested language pack. Configured for "es-MX".
 * Never stores or uploads audio recordings.
 */
class AndroidVoiceRecognizer(
    private val context: Context,
) : VoiceRecognizer {

    private var recognizer: SpeechRecognizer? = null
    private var activeSessionId: Long = 0L

    private data class RecognizerSelection(
        val recognizer: SpeechRecognizer,
        val isOnDevice: Boolean,
        val preferOffline: Boolean,
    )

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
        onRmsChanged: ((Float) -> Unit)?,
        onEndOfSpeech: (() -> Unit)?,
        onListeningStarted: (() -> Unit)?,
    ) {
        destroy()

        if (!isAvailable()) {
            onError(VoiceUnavailableReason.NO_RECOGNIZER)
            return
        }

        val sessionId = ++activeSessionId
        startListeningInternal(
            sessionId = sessionId,
            allowOnDevice = true,
            onPartialResult = onPartialResult,
            onFinalResult = onFinalResult,
            onError = onError,
            onRmsChanged = onRmsChanged,
            onEndOfSpeech = onEndOfSpeech,
            onListeningStarted = onListeningStarted,
        )
    }

    private fun startListeningInternal(
        sessionId: Long,
        allowOnDevice: Boolean,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
        onRmsChanged: ((Float) -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onListeningStarted: (() -> Unit)? = null,
    ) {
        // Destroy existing recognizer before creating a new one without invalidating the active session
        recognizer?.let {
            it.setRecognitionListener(null)
            it.cancel()
            it.destroy()
        }
        recognizer = null

        if (sessionId != activeSessionId) {
            return
        }

        val selection = if (allowOnDevice) {
            createRecognizer()
        } else {
            RecognizerSelection(
                recognizer = SpeechRecognizer.createSpeechRecognizer(context),
                isOnDevice = false,
                preferOffline = false,
            )
        }
        val speechRecognizer = selection.recognizer
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId == activeSessionId && recognizer === speechRecognizer) {
                    onListeningStarted?.invoke()
                }
            }
            override fun onBeginningOfSpeech() {
                if (sessionId == activeSessionId && recognizer === speechRecognizer) {
                    onListeningStarted?.invoke()
                }
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (sessionId == activeSessionId && recognizer === speechRecognizer) {
                    onRmsChanged?.invoke(rmsdB)
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (sessionId == activeSessionId && recognizer === speechRecognizer) {
                    onEndOfSpeech?.invoke()
                }
            }

            override fun onError(error: Int) {
                if (sessionId != activeSessionId || recognizer !== speechRecognizer) {
                    return
                }
                if (shouldRetryWithSystemRecognizer(selection.isOnDevice, error)) {
                    // Retry EXACTLY ONCE with the system/network recognizer within the same active session
                    startListeningInternal(
                        sessionId = sessionId,
                        allowOnDevice = false,
                        onPartialResult = onPartialResult,
                        onFinalResult = onFinalResult,
                        onError = onError,
                        onRmsChanged = onRmsChanged,
                        onEndOfSpeech = onEndOfSpeech,
                        onListeningStarted = onListeningStarted,
                    )
                } else {
                    onError(mapSpeechRecognizerError(error))
                }
            }

            override fun onResults(results: Bundle?) {
                if (sessionId != activeSessionId || recognizer !== speechRecognizer) {
                    return
                }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                onFinalResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != activeSessionId || recognizer !== speechRecognizer) {
                    return
                }
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
        activeSessionId++
        recognizer?.let {
            it.setRecognitionListener(null)
            it.cancel()
        }
    }

    override fun destroy() {
        activeSessionId++
        recognizer?.let {
            it.setRecognitionListener(null)
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
                isOnDevice = true,
                preferOffline = true,
            )
        } else {
            RecognizerSelection(
                recognizer = SpeechRecognizer.createSpeechRecognizer(context),
                isOnDevice = false,
                preferOffline = false,
            )
        }
    }

    companion object {
        private const val LOCALE_ES_MX = "es-MX"
    }
}
