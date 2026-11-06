package com.cuentivo.app.ai

import kotlin.math.sqrt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * El reconocedor de voz de iOS: `SFSpeechRecognizer` en `es-MX` con `AVAudioEngine`.
 *
 * **Nunca se guarda ni se sube audio**: los buffers van del motor al reconocedor y se descartan.
 *
 * El reintento de voz de Android **no se copia**: en iOS `supportsOnDeviceRecognition` lo dice de
 * antemano, así que se pide on-device sólo si está soportado.
 *
 * Pendiente de CI: no hay micrófono ni reconocedor en el simulador de pruebas.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceRecognizer(
    private val recognizer: SFSpeechRecognizer? = SFSpeechRecognizer(
        locale = NSLocale(localeIdentifier = LOCALE_ES_MX),
    ),
) : VoiceRecognizer {

    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    /** Contador de sesión, igual que en Android: un callback viejo se ignora. */
    private var sessionId: Long = 0L

    /** Invalida el temporizador anterior: sólo cuenta el último que se armó. */
    private var timerToken: Long = 0L

    override fun isAvailable(): Boolean = recognizer?.isAvailable() ?: false

    override fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
        onRmsChanged: ((Float) -> Unit)?,
        onEndOfSpeech: (() -> Unit)?,
        onListeningStarted: (() -> Unit)?,
    ) {
        releaseEngine()
        val session = ++sessionId
        val speech = recognizer
        if (speech == null || !speech.isAvailable()) {
            onError(VoiceUnavailableReason.NO_RECOGNIZER)
            return
        }

        SFSpeechRecognizer.requestAuthorization { status ->
            if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                onMain { if (session == sessionId) onError(VoiceUnavailableReason.PERMISSION_DENIED) }
                return@requestAuthorization
            }
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                onMain {
                    if (session != sessionId) return@onMain
                    if (!granted) {
                        onError(VoiceUnavailableReason.PERMISSION_DENIED)
                    } else {
                        begin(
                            session = session,
                            speech = speech,
                            onPartialResult = onPartialResult,
                            onFinalResult = onFinalResult,
                            onError = onError,
                            onRmsChanged = onRmsChanged,
                            onEndOfSpeech = onEndOfSpeech,
                            onListeningStarted = onListeningStarted,
                        )
                    }
                }
            }
        }
    }

    private fun begin(
        session: Long,
        speech: SFSpeechRecognizer,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (VoiceUnavailableReason) -> Unit,
        onRmsChanged: ((Float) -> Unit)?,
        onEndOfSpeech: (() -> Unit)?,
        onListeningStarted: (() -> Unit)?,
    ) {
        val audioSession = AVAudioSession.sharedInstance()
        audioSession.setCategory(
            category = AVAudioSessionCategoryRecord,
            mode = AVAudioSessionModeMeasurement,
            options = AVAudioSessionCategoryOptionDuckOthers,
            error = null,
        )
        audioSession.setActive(true, error = null)

        val audioEngine = AVAudioEngine()
        val inputNode = audioEngine.inputNode
        val format = inputNode.outputFormatForBus(0u)
        // `installTapOnBus` con `sampleRate == 0` lanza un NSException que mata el proceso: pasa en
        // el simulador, que no tiene micrófono. Se comprueba antes de instalar.
        if (format.sampleRate <= 0.0 || format.channelCount == 0u) {
            audioSession.setActive(false, error = null)
            onMain { if (session == sessionId) onError(VoiceUnavailableReason.NO_RECOGNIZER) }
            return
        }

        val recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        recognitionRequest.shouldReportPartialResults = true
        // Sin copiar el reintento de Android: se pide on-device sólo si está soportado.
        recognitionRequest.requiresOnDeviceRecognition = speech.supportsOnDeviceRecognition

        inputNode.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            val audioBuffer = buffer ?: return@installTapOnBus
            recognitionRequest.appendAudioPCMBuffer(audioBuffer)
            if (onRmsChanged != null && session == sessionId) {
                val level = rmsToAndroidScale(rmsOf(audioBuffer))
                onMain { if (session == sessionId) onRmsChanged(level) }
            }
        }

        engine = audioEngine
        request = recognitionRequest
        audioEngine.prepare()
        // Si el motor no arranca no llegaría ningún audio y la pantalla se quedaría escuchando.
        if (!audioEngine.startAndReturnError(null)) {
            sessionId++
            releaseEngine()
            onMain { onError(VoiceUnavailableReason.RECOGNIZER_ERROR) }
            return
        }

        val finishAfter: (Long) -> Unit = { delayMillis ->
            armTimer(session, delayMillis) {
                // Mismo orden que Android: primero "se acabó el habla", después el resultado final.
                onEndOfSpeech?.invoke()
                finishAudio()
                armTimer(session, FINAL_RESULT_GUARD_MILLIS) {
                    sessionId++
                    releaseEngine()
                    onError(VoiceUnavailableReason.RECOGNIZER_ERROR)
                }
            }
        }

        task = speech.recognitionTaskWithRequest(recognitionRequest) { result, error ->
            onMain {
                if (session != sessionId) return@onMain
                val isFinal = result?.isFinal() == true
                if (result != null) {
                    val text = result.bestTranscription.formattedString
                    if (isFinal) {
                        onFinalResult(text)
                    } else {
                        onPartialResult(text)
                        finishAfter(SILENCE_AFTER_SPEECH_MILLIS)
                    }
                }
                if (error != null || isFinal) {
                    // Cancelar la tarea puede traer un error tardío: esta sesión ya terminó.
                    sessionId++
                    if (error != null && !isFinal) {
                        onError(mapIosSpeechError(domain = error.domain, code = error.code))
                    }
                    releaseEngine()
                }
            }
        }

        // Sin ninguna palabra también hay que cerrar: `endAudio` hace que llegue NO_SPEECH.
        finishAfter(NO_SPEECH_MILLIS)
        onMain { if (session == sessionId) onListeningStarted?.invoke() }
    }

    /**
     * Android corta solo cuando detecta silencio; `SFSpeechRecognizer` no: sigue escuchando hasta
     * que le cierran el audio. Sin esto, el resultado final nunca llega. Corre en el hilo principal.
     */
    private fun armTimer(session: Long, delayMillis: Long, action: () -> Unit) {
        val token = ++timerToken
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delayMillis * NANOS_PER_MILLI),
            dispatch_get_main_queue(),
        ) {
            if (session == sessionId && token == timerToken) action()
        }
    }

    /** Cierra el micrófono sin cancelar la tarea: el resultado final todavía tiene que llegar. */
    private fun finishAudio() {
        engine?.let { audioEngine ->
            runCatching {
                if (audioEngine.running) audioEngine.stop()
                audioEngine.inputNode.removeTapOnBus(0u)
            }
        }
        engine = null
        request?.endAudio()
        request = null
    }

    override fun cancel() {
        sessionId++
        releaseEngine()
    }

    override fun destroy() {
        sessionId++
        releaseEngine()
    }

    /** Idempotente: quitar el tap de un motor parado no es error, y volver a pararlo tampoco. */
    private fun releaseEngine() {
        timerToken++
        finishAudio()
        task?.cancel()
        task = null
        runCatching { AVAudioSession.sharedInstance().setActive(false, error = null) }
    }

    private fun rmsOf(buffer: AVAudioPCMBuffer): Float {
        val channels = buffer.floatChannelData ?: return 0f
        val channel = channels[0] ?: return 0f
        val frames = buffer.frameLength.toInt()
        if (frames <= 0) return 0f
        var sum = 0.0
        for (index in 0 until frames) {
            val sample = channel[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / frames).toFloat()
    }

    private companion object {
        const val LOCALE_ES_MX = "es-MX"
        const val SILENCE_AFTER_SPEECH_MILLIS = 1_500L
        const val NO_SPEECH_MILLIS = 6_000L
        const val FINAL_RESULT_GUARD_MILLIS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Los callbacks llegan en otras colas; la UI y el scope de Compose son del hilo principal. */
@OptIn(ExperimentalForeignApi::class)
private fun onMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}
