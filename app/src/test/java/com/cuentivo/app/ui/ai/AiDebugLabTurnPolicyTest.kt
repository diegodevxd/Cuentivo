package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.RecordPaymentProposal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AiDebugLabTurnPolicyTest {

    private class RecordingTransport(
        var response: CallableAiProposalResult = CallableAiProposalResult.Unavailable,
    ) : AiDebugTransport {
        var callCount = 0
        var lastRequest: AiInterpretRequest? = null

        override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult {
            callCount++
            lastRequest = request
            return response
        }
    }

    @Test
    fun `social exact prompt returns out of scope without calling transport`() = runBlocking {
        val transport = RecordingTransport()
        val request = AiInterpretRequest(text = "¿como estas?", requestId = "req-1")

        val result = AiDebugLabTurnPolicy.run(transport, request)

        assertEquals(AiDebugLabTurnResult.OutOfScope, result)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `business and ambiguous text call transport exactly once`() = runBlocking {
        val proposal = RecordPaymentProposal("María", 15000L)
        val transport = RecordingTransport(response = CallableAiProposalResult.Proposal(proposal))
        val request = AiInterpretRequest(text = "María abonó 150", requestId = "req-2")

        val result = AiDebugLabTurnPolicy.run(transport, request)

        assertEquals(AiDebugLabTurnResult.Remote(CallableAiProposalResult.Proposal(proposal)), result)
        assertEquals(1, transport.callCount)
        assertEquals("María abonó 150", transport.lastRequest?.text)
    }

    @Test
    fun `transport exception falls back to remote unavailable without throwing`() = runBlocking {
        val throwingTransport = object : AiDebugTransport {
            override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult {
                throw RuntimeException("Network down")
            }
        }
        val request = AiInterpretRequest(text = "María abonó 150", requestId = "req-3")

        val result = AiDebugLabTurnPolicy.run(throwingTransport, request)

        assertEquals(AiDebugLabTurnResult.Remote(CallableAiProposalResult.Unavailable), result)
    }
}
