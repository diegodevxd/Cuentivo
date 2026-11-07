package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.CallableAiProposalResult

/**
 * Pure submit policy for the debug lab: any exception from the transport (including a transport
 * implementation that throws despite the gateway contract) resolves to the fixed
 * [CallableAiProposalResult.Unavailable] and never crashes or logs.
 */
object AiDebugLabSubmitPolicy {

    suspend fun run(
        transport: AiDebugTransport,
        request: AiInterpretRequest,
    ): CallableAiProposalResult =
        try {
            transport.submit(request)
        } catch (_: Exception) {
            CallableAiProposalResult.Unavailable
        }
}
