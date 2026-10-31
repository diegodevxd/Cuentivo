package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCallableAiGatewayTest {

    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    // ---- Payload (no Firebase) ----

    @Test
    fun `payload sent is the exact v2 contract including the original text`() {
        val request = AiInterpretRequest(text = "vende dos perfumes", requestId = requestId)
        assertEquals(
            mapOf(
                "contractVersion" to 2,
                "requestId" to requestId,
                "action" to "interpret_text_proposal",
                "text" to "vende dos perfumes",
            ),
            buildCallablePayload2(request),
        )
    }

    // ---- Parser: valid responses ----

    @Test
    fun `unavailable response with null proposal parses to unavailable`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_UNAVAILABLE",
            "proposal" to null,
        )
        assertEquals(CallableAiProposalResult.Unavailable, parseCallableProposalResponse(response, requestId))
    }

    @Test
    fun `rejection reasons with null proposal parse to their respective typed results`() {
        val cases = listOf(
            "AI_QUOTA_EXHAUSTED" to CallableAiProposalResult.QuotaExhausted,
            "AI_RATE_LIMITED" to CallableAiProposalResult.RateLimited,
            "AI_DISABLED" to CallableAiProposalResult.Disabled,
            "AI_ENTITLEMENT_REQUIRED" to CallableAiProposalResult.EntitlementRequired,
        )
        for ((status, expected) in cases) {
            val response = mapOf(
                "contractVersion" to 2,
                "requestId" to requestId,
                "status" to status,
                "proposal" to null,
            )
            assertEquals(
                "expected $expected for status $status",
                expected,
                parseCallableProposalResponse(response, requestId),
            )
        }
    }

    @Test
    fun `rejection reasons with non null proposal fail closed to unavailable`() {
        val rejectionStatuses = listOf(
            "AI_UNAVAILABLE",
            "AI_QUOTA_EXHAUSTED",
            "AI_RATE_LIMITED",
            "AI_DISABLED",
            "AI_ENTITLEMENT_REQUIRED",
        )
        for (status in rejectionStatuses) {
            val response = mapOf(
                "contractVersion" to 2,
                "requestId" to requestId,
                "status" to status,
                "proposal" to mapOf("kind" to "record_payment", "customerName" to "María", "declaredAmountCents" to "100"),
            )
            assertEquals(
                "expected Unavailable for status $status with non-null proposal",
                CallableAiProposalResult.Unavailable,
                parseCallableProposalResponse(response, requestId),
            )
        }
    }

    @Test
    fun `unknown or future status values fall back to unavailable for backwards compatibility`() {
        val unknownStatuses = listOf(
            "AI_FUTURE_UNKNOWN_STATUS",
            "UNKNOWN",
            "AI_AUTHORIZED",
            "AI_AUTHENTICATION_REQUIRED",
            "SOMETHING_ELSE",
        )
        for (status in unknownStatuses) {
            val response = mapOf(
                "contractVersion" to 2,
                "requestId" to requestId,
                "status" to status,
                "proposal" to null,
            )
            assertEquals(
                "expected Unavailable for unknown status $status",
                CallableAiProposalResult.Unavailable,
                parseCallableProposalResponse(response, requestId),
            )
        }
    }

    @Test
    fun `valid requires_clarification parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf(
                "kind" to "requires_clarification",
                "reason" to "AMBIGUOUS_CUSTOMER",
                "unresolvedSlots" to listOf("CUSTOMER", "AMOUNT"),
            ),
        )
        val result = parseCallableProposalResponse(response, requestId)
        assertTrue("expected proposal, got $result", result is CallableAiProposalResult.Proposal)
        assertEquals(
            RequiresClarificationProposal(
                ClarificationReason.AMBIGUOUS_CUSTOMER,
                setOf(UnresolvedSlot.CUSTOMER, UnresolvedSlot.AMOUNT),
            ),
            (result as CallableAiProposalResult.Proposal).proposal,
        )
    }

    @Test
    fun `valid create_sale with promise parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf(
                "kind" to "create_sale",
                "customerName" to "María",
                "lines" to listOf(
                    mapOf("productName" to "Perfume A", "quantity" to 2, "unitPriceCents" to "30000"),
                    mapOf("productName" to "Cremas", "quantity" to 1, "unitPriceCents" to "15000"),
                ),
                "declaredInitialPaymentCents" to "20000",
                "promisedPaymentDate" to mapOf("year" to 2026, "month" to 8, "day" to 30),
            ),
        )
        val result = parseCallableProposalResponse(response, requestId)
        assertTrue("expected proposal, got $result", result is CallableAiProposalResult.Proposal)
        val proposal = (result as CallableAiProposalResult.Proposal).proposal
        assertTrue(proposal is CreateSaleProposal)
        proposal as CreateSaleProposal
        assertEquals("María", proposal.customerName)
        assertEquals(2, proposal.lines.size)
        assertEquals(30000L, proposal.lines[0].unitPriceCents)
        assertEquals(2, proposal.lines[0].quantity)
        assertEquals(20000L, proposal.declaredInitialPaymentCents)
        assertEquals(PromisedPaymentCandidate.DateCandidate(2026, 8, 30), proposal.promisedPaymentCandidate)
    }

    @Test
    fun `valid record_payment parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf("kind" to "record_payment", "customerName" to "María", "declaredAmountCents" to "15000"),
        )
        val result = parseCallableProposalResponse(response, requestId)
        assertEquals(
            CallableAiProposalResult.Proposal(RecordPaymentProposal("María", 15000L)),
            result,
        )
    }

    @Test
    fun `valid business_query debs parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf("kind" to "business_query", "queryType" to "CUSTOMERS_WITH_DEBT"),
        )
        assertEquals(
            CallableAiProposalResult.Proposal(
                BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT),
            ),
            parseCallableProposalResponse(response, requestId),
        )
    }

    @Test
    fun `valid business_query sales period parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf(
                "kind" to "business_query",
                "queryType" to "SALES_FOR_PERIOD",
                "period" to mapOf(
                    "startYear" to 2026, "startMonth" to 8, "startDay" to 1,
                    "endYear" to 2026, "endMonth" to 8, "endDay" to 31,
                ),
            ),
        )
        val result = parseCallableProposalResponse(response, requestId)
        assertTrue("expected proposal, got $result", result is CallableAiProposalResult.Proposal)
        assertEquals(
            BusinessQueryProposal(
                BusinessQueryType.SALES_FOR_PERIOD,
                period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 31),
            ),
            (result as CallableAiProposalResult.Proposal).proposal,
        )
    }

    @Test
    fun `valid business_query balance parses to proposal`() {
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf("kind" to "business_query", "queryType" to "CUSTOMER_BALANCE", "customerName" to "María"),
        )
        assertEquals(
            CallableAiProposalResult.Proposal(
                BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, customerName = "María"),
            ),
            parseCallableProposalResponse(response, requestId),
        )
    }

    // ---- Parser: unexpected shapes fail closed ----

    @Test
    fun `unexpected shapes never produce a proposal`() {
        val invalidProposals = listOf(
            // wrong root keys
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "0"), "extra" to 1),
            // proposal missing key
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL"),
            // unavailable with non-null proposal
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_UNAVAILABLE", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "0")),
            // wrong status
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_INTERPRETED", "proposal" to null),
            // wrong identifier
            mapOf("contractVersion" to 2, "requestId" to "other-request", "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "0")),
            // money as number
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to 15000)),
            // malformed money strings
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "01")),
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "1000000000000000000")),
            // whitespace padded name beyond raw limit
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "record_payment", "customerName" to " ".repeat(1000) + "A", "declaredAmountCents" to "0")),
            // duplicate slots
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "requires_clarification", "reason" to "AMBIGUOUS_PRODUCT", "unresolvedSlots" to listOf("PRODUCT", "PRODUCT"))),
            // empty slots
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "requires_clarification", "reason" to "MISSING_CUSTOMER", "unresolvedSlots" to emptyList<String>())),
            // query cross-selector: balance with period
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "business_query", "queryType" to "CUSTOMER_BALANCE", "customerName" to "A", "period" to mapOf("startYear" to 2026, "startMonth" to 8, "startDay" to 1, "endYear" to 2026, "endMonth" to 8, "endDay" to 31))),
            // query cross-selector: sales with customer
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "business_query", "queryType" to "SALES_FOR_PERIOD", "customerName" to "A", "period" to mapOf("startYear" to 2026, "startMonth" to 8, "startDay" to 1, "endYear" to 2026, "endMonth" to 8, "endDay" to 31))),
            // invalid date (non-leap Feb 29)
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "create_sale", "customerName" to "A", "lines" to listOf(mapOf("productName" to "P", "quantity" to 1, "unitPriceCents" to "1")), "declaredInitialPaymentCents" to "0", "promisedPaymentDate" to mapOf("year" to 2023, "month" to 2, "day" to 29))),
            // inverted period
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "business_query", "queryType" to "SALES_FOR_PERIOD", "period" to mapOf("startYear" to 2026, "startMonth" to 8, "startDay" to 31, "endYear" to 2026, "endMonth" to 8, "endDay" to 1))),
            // too many lines
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "create_sale", "customerName" to "A", "lines" to (1..9).map { mapOf("productName" to "P$it", "quantity" to 1, "unitPriceCents" to "1") }, "declaredInitialPaymentCents" to "0")),
            // zero quantity
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "create_sale", "customerName" to "A", "lines" to listOf(mapOf("productName" to "P", "quantity" to 0, "unitPriceCents" to "1")), "declaredInitialPaymentCents" to "0")),
            // fractional quantity
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "create_sale", "customerName" to "A", "lines" to listOf(mapOf("productName" to "P", "quantity" to 1.5, "unitPriceCents" to "1")), "declaredInitialPaymentCents" to "0")),
            // unknown kind
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "sale_draft", "customerName" to "A")),
            // line extra key
            mapOf("contractVersion" to 2, "requestId" to requestId, "status" to "AI_PROPOSAL", "proposal" to mapOf("kind" to "create_sale", "customerName" to "A", "lines" to listOf(mapOf("productName" to "P", "quantity" to 1, "unitPriceCents" to "1", "id" to "x")), "declaredInitialPaymentCents" to "0")),
        )
        for (response in invalidProposals) {
            assertEquals(
                "expected Unavailable for: $response",
                CallableAiProposalResult.Unavailable,
                parseCallableProposalResponse(response, requestId),
            )
        }
    }

    @Test
    fun `non map payloads and non matching identifiers fail closed`() {
        for (payload in listOf(null, "AI_UNAVAILABLE", listOf("a"), 42L)) {
            assertEquals(CallableAiProposalResult.Unavailable, parseCallableProposalResponse(payload, requestId))
        }
        val response = mapOf(
            "contractVersion" to 2,
            "requestId" to requestId,
            "status" to "AI_PROPOSAL",
            "proposal" to mapOf("kind" to "record_payment", "customerName" to "A", "declaredAmountCents" to "0"),
        )
        assertEquals(CallableAiProposalResult.Unavailable, parseCallableProposalResponse(response, "different-request-id"))
    }
}
