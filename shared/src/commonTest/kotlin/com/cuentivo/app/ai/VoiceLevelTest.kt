package com.cuentivo.app.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las dos funciones puras del nivel de voz del iPhone.
 *
 * Se prueban aquí, en común, porque `SFSpeechRecognizer` no existe en la JVM ni hace falta: lo que
 * decide es el número.
 */
class VoiceLevelTest {

    @Test
    fun `un rms en silencio queda en el fondo de la escala`() {
        assertEquals(ANDROID_RMS_MIN_DB, rmsToAndroidScale(0f))
        assertEquals(ANDROID_RMS_MIN_DB, rmsToAndroidScale(0.0001f))
    }

    @Test
    fun `un rms a fondo de escala da cero decibelios`() {
        assertEquals(0f, rmsToAndroidScale(1f))
    }

    @Test
    fun `el nivel nunca sale de la escala de Android`() {
        val values = listOf(0f, 0.001f, 0.01f, 0.1f, 0.5f, 0.9f, 1f)
        for (value in values) {
            val scaled = rmsToAndroidScale(value)
            assertTrue(scaled >= ANDROID_RMS_MIN_DB && scaled <= ANDROID_RMS_MAX_DB, "fuera de escala: $scaled")
        }
    }

    @Test
    fun `mas senal nunca da menos nivel`() {
        assertTrue(rmsToAndroidScale(0.5f) >= rmsToAndroidScale(0.1f))
        assertTrue(rmsToAndroidScale(0.1f) >= rmsToAndroidScale(0.01f))
    }

    @Test
    fun `los codigos de SFSpeechRecognizer se traducen a las razones comunes`() {
        assertEquals(VoiceUnavailableReason.NO_SPEECH, mapIosSpeechError(IOS_ASSISTANT_ERROR_DOMAIN, 1110L))
        assertEquals(VoiceUnavailableReason.PERMISSION_DENIED, mapIosSpeechError(IOS_ASSISTANT_ERROR_DOMAIN, 1700L))
        assertEquals(VoiceUnavailableReason.RECOGNIZER_ERROR, mapIosSpeechError(IOS_ASSISTANT_ERROR_DOMAIN, 999L))
        assertEquals(VoiceUnavailableReason.RECOGNIZER_ERROR, mapIosSpeechError("otro.dominio", 1110L))
    }
}
