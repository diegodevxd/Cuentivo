package com.cuentivo.app.ai

import kotlin.math.log10

/** El fondo de la escala de Android: `normalizeRmsDb` mapea -2..10 dB a 0..1. */
const val ANDROID_RMS_MIN_DB = -2f

/** El techo de la escala de Android. */
const val ANDROID_RMS_MAX_DB = 10f

/**
 * Convierte el RMS de una muestra de audio a la escala que espera [normalizeRmsDb] (-2 a 10 dB).
 *
 * iOS entrega las muestras como `Float` en -1..1, así que el nivel se calcula con
 * `20 * log10(rms)`. Ese número es ≤ 0 para cualquier señal que quepa en el rango, y la escala de
 * Android empieza en -2: lo que queda por debajo se recorta, igual que lo que pase de 10. Así la
 * barra de nivel se comporta como en Android aunque el micrófono sea otro.
 */
fun rmsToAndroidScale(rms: Float): Float {
    if (rms <= 0f || rms.isNaN()) return ANDROID_RMS_MIN_DB
    val decibels = 20f * log10(rms.toDouble()).toFloat()
    return decibels.coerceIn(ANDROID_RMS_MIN_DB, ANDROID_RMS_MAX_DB)
}

/** El dominio con el que `SFSpeechRecognizer` reporta los errores de escucha en iOS. */
const val IOS_ASSISTANT_ERROR_DOMAIN = "kAFAssistantErrorDomain"

/** `SFSpeechRecognizer` no reconoció habla. */
const val IOS_ERROR_NO_SPEECH = 1110L

/** El permiso de reconocimiento o de micrófono fue negado. */
const val IOS_ERROR_PERMISSION_DENIED = 1700L

/**
 * Traduce un `NSError` de `SFSpeechRecognizer` a la razón común.
 *
 * Puro a propósito: el dominio y el código son lo único que decide, así que se puede probar sin
 * micrófono ni simulador. Todo lo demás es [VoiceUnavailableReason.RECOGNIZER_ERROR].
 */
fun mapIosSpeechError(domain: String, code: Long): VoiceUnavailableReason = when {
    domain == IOS_ASSISTANT_ERROR_DOMAIN && code == IOS_ERROR_NO_SPEECH -> VoiceUnavailableReason.NO_SPEECH
    domain == IOS_ASSISTANT_ERROR_DOMAIN && code == IOS_ERROR_PERMISSION_DENIED ->
        VoiceUnavailableReason.PERMISSION_DENIED
    else -> VoiceUnavailableReason.RECOGNIZER_ERROR
}
