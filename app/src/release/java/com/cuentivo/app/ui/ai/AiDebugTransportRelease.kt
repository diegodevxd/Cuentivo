package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.AiInterpretRequest

/** Release transport: the AI debug lab is never available; every call stays unavailable. */
private object AiDebugUnavailableTransport : AiDebugTransport {
    override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
        CallableAiProposalResult.Unavailable
}

/** Source-set entry point: release builds have no AI lab (null hides the entry entirely). */
fun debugAiTransport(): AiDebugTransport? = null
