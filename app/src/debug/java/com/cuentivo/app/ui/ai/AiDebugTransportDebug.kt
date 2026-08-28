package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiDebugFirebaseTransport

/** Source-set entry point: debug builds get the real gateway. */
fun debugAiTransport(): AiDebugTransport = AiDebugFirebaseTransport()

/** Debug builds get the real on-device ML Kit OCR engine. */
fun debugAiOcrEngine(context: android.content.Context): com.cuentivo.app.ai.capture.AiOcrEngine =
    com.cuentivo.app.ai.capture.MlKitOcrEngine(context)
