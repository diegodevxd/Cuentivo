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
        )
        for (err in genericErrors) {
            assertEquals(VoiceUnavailableReason.RECOGNIZER_ERROR, mapSpeechRecognizerError(err))
        }
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
