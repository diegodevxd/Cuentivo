package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiBusinessScopeDecision
import com.cuentivo.app.ai.AiBusinessScopePolicy
import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.CallableAiProposalResult

/**
 * Result of evaluating a daily chat turn policy before or through transport.
 */
sealed interface AiDebugLabTurnResult {
    data object OutOfScope : AiDebugLabTurnResult
    data class Remote(val result: CallableAiProposalResult) : AiDebugLabTurnResult
}

/**
 * Enforces business-only daily conversation turn policy. Checks [AiBusinessScopePolicy] first;
 * out-of-scope whole inputs return immediately without calling transport, while business or
 * ambiguous inputs delegate safely to [AiDebugLabSubmitPolicy].
 */
object AiDebugLabTurnPolicy {

    suspend fun run(
        transport: AiDebugTransport,
        request: AiInterpretRequest,
    ): AiDebugLabTurnResult {
        return when (AiBusinessScopePolicy.decide(request.text)) {
            AiBusinessScopeDecision.OutOfScope -> AiDebugLabTurnResult.OutOfScope
            AiBusinessScopeDecision.ContinueToInterpretation -> {
                val result = AiDebugLabSubmitPolicy.run(transport, request)
                AiDebugLabTurnResult.Remote(result)
            }
        }
    }
}
