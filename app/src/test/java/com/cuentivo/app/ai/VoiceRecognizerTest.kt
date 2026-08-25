package com.cuentivo.app.ai

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceRecognizerTest {

    @Test
    fun `maps speech timeout and no match to NO_SPEECH`() {
        assertEquals(VoiceUnavailableReason.NO_SPEECH, mapSpeechRecognizerError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceUnavailableReason.NO_SPEECH, mapSpeechRecognizerError(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun `maps insufficient permissions to PERMISSION_DENIED`() {
        assertEquals(VoiceUnavailableReason.PERMISSION_DENIED, mapSpeechRecognizerError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `maps generic recognizer errors to RECOGNIZER_ERROR`() {
        val genericErrors = listOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            ERROR_LANGUAGE_UNAVAILABLE_CODE,
            ERROR_LANGUAGE_NOT_SUPPORTED_CODE,
        )
        for (err in genericErrors) {
            assertEquals(VoiceUnavailableReason.RECOGNIZER_ERROR, mapSpeechRecognizerError(err))
        }
    }

    @Test
    fun `isLanguageUnavailableError identifies error codes 12 and 13`() {
        assertTrue(isLanguageUnavailableError(12))
        assertTrue(isLanguageUnavailableError(13))
        assertFalse(isLanguageUnavailableError(SpeechRecognizer.ERROR_AUDIO))
        assertFalse(isLanguageUnavailableError(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(isLanguageUnavailableError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `shouldRetryWithSystemRecognizer retries error 12 and 13 on on-device recognizer only`() {
        // On-device engine missing language pack -> retry with system recognizer
        assertTrue(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = 12))
        assertTrue(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = 13))

        // System recognizer missing language pack -> DO NOT retry (prevents infinite loop)
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = false, errorCode = 12))
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = false, errorCode = 13))

        // Other errors on on-device engine -> DO NOT retry
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = SpeechRecognizer.ERROR_AUDIO))
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(shouldRetryWithSystemRecognizer(isOnDevice = true, errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `shouldPreferOfflineRecognition is true only when on-device recognizer is available`() {
        assertTrue(shouldPreferOfflineRecognition(isOnDeviceAvailable = true))
        assertFalse(shouldPreferOfflineRecognition(isOnDeviceAvailable = false))
    }

    @Test
    fun `RECORD_AUDIO is never in main manifest and present in debug manifest`() {
        val mainManifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "RECORD_AUDIO must NEVER appear in main manifest (release safety)",
            mainManifest.contains("RECORD_AUDIO"),
        )

        val debugManifest = File("src/debug/AndroidManifest.xml").readText()
        assertTrue(
            "RECORD_AUDIO must be declared in debug manifest",
            debugManifest.contains("RECORD_AUDIO"),
        )
    }
}
