package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.AiInterpretRequest

/**
 * Pure boundary of the debug AI lab transport. Implementations live in the debug source set
 * (Firebase gateway, only for debug builds) and the release source set (always unavailable).
 * Main sources never import Firebase or the debug gateway implementation.
 */
interface AiDebugTransport {
    suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult
}
