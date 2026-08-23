package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.AiInterpretRequest
import com.cuentivo.app.ai.CallableAiProposalResult
import com.cuentivo.app.ai.RecordPaymentProposal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDebugLabSubmitPolicyTest {

    private val request = AiInterpretRequest(text = "venta", requestId = "req-1")

    @Test
    fun `a transport that throws resolves to unavailable without crashing`() = runBlocking {
        val throwing = object : AiDebugTransport {
            override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult {
                throw IllegalStateException("gateway down")
            }
        }

        val result = AiDebugLabSubmitPolicy.run(throwing, request)

        assertEquals(CallableAiProposalResult.Unavailable, result)
    }

    @Test
    fun `a transport result is passed through untouched`() = runBlocking {
        val ok = object : AiDebugTransport {
            override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
                CallableAiProposalResult.Proposal(RecordPaymentProposal("Maria", 100L))
        }

        val result = AiDebugLabSubmitPolicy.run(ok, request)

        assertTrue(result is CallableAiProposalResult.Proposal)
        assertEquals(100L, (result as CallableAiProposalResult.Proposal).proposal.run {
            (this as RecordPaymentProposal).declaredAmountCents
        })
    }
}
