package com.cuentivo.app.ai

import com.cuentivo.app.ui.ai.AiDebugTransport

/**
 * Transport backed by the regional callable gateway [FirebaseCallableAiGateway].
 */
class AiDebugFirebaseTransport(
    private val gateway: FirebaseCallableAiGateway = FirebaseCallableAiGateway(),
) : AiDebugTransport {
    override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
        gateway.callContractV2(request)
}
