package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiDebugFirebaseTransport

/** Source-set entry point: release builds get the real callable gateway transport. */
fun debugAiTransport(): AiDebugTransport = AiDebugFirebaseTransport()

/** Release builds have no OCR engine; the capture button is hidden. */
fun debugAiOcrEngine(context: android.content.Context): com.cuentivo.app.ai.capture.AiOcrEngine? = null
