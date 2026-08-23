package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.FirebaseCallableAiGateway

/** Debug-only transport backed by the regional callable gateway; present only in debug builds. */
private class AiDebugFirebaseTransport(
    private val gateway: FirebaseCallableAiGateway = FirebaseCallableAiGateway(),
) : AiDebugTransport {
    override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
        gateway.callContractV2(request)
}

/** Source-set entry point: debug builds get the real gateway. */
fun debugAiTransport(): AiDebugTransport = AiDebugFirebaseTransport()

/** Debug builds get the real on-device ML Kit OCR engine. */
fun debugAiOcrEngine(context: android.content.Context): com.cuentivo.app.ai.capture.AiOcrEngine =
    com.cuentivo.app.ai.capture.MlKitOcrEngine(context)
