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
    fun `RECORD_AUDIO and RecognitionService queries are declared in manifest as direct children`() {
        val mainManifest = File("src/main/AndroidManifest.xml")
        assertTrue("main manifest must exist", mainManifest.exists())

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(mainManifest)
        val root = doc.documentElement
        assertEquals("root element must be manifest", "manifest", root.nodeName)

        fun org.w3c.dom.Element.directChildren(name: String): List<org.w3c.dom.Element> {
            val list = mutableListOf<org.w3c.dom.Element>()
            val children = this.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.nodeName == name) {
                    list.add(node as org.w3c.dom.Element)
                }
            }
            return list
        }

        val permissions = root.directChildren("uses-permission")
        val hasRecordAudio = permissions.any { it.getAttribute("android:name") == "android.permission.RECORD_AUDIO" }
        assertTrue(
            "RECORD_AUDIO must be explicitly declared as a direct uses-permission child of manifest",
            hasRecordAudio,
        )

        val queriesElements = root.directChildren("queries")
        assertTrue("manifest must contain at least one direct queries child", queriesElements.isNotEmpty())

        val hasRecognitionService = queriesElements.any { queries ->
            queries.directChildren("intent").any { intent ->
                intent.directChildren("action").any { action ->
                    action.getAttribute("android:name") == "android.speech.RecognitionService"
                }
            }
        }
        assertTrue(
            "android.speech.RecognitionService must be strictly declared under manifest > queries > intent > action in main manifest",
            hasRecognitionService,
        )
    }
}
