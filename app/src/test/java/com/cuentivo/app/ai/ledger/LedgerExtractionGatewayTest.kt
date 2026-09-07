package com.cuentivo.app.ai.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire parser is the door a photographed page walks through on its way into the ledger, so it
 * is exercised here directly, with the shapes a server could actually send.
 */
class LedgerExtractionGatewayTest {

    private fun response(
        operations: List<Map<String, Any?>>? = emptyList(),
        status: String = "AI_PROPOSAL",
        requestId: String = REQUEST_ID,
        contractVersion: Any = 3,
        pageIncomplete: Any? = false,
        unreadTextRemaining: Any? = false,
        discards: Any? = emptyList<Any>(),
    ): Map<String, Any?> = mapOf(
        "contractVersion" to contractVersion,
        "requestId" to requestId,
        "status" to status,
        "operations" to operations,
        "discards" to discards,
        "unreadTextRemaining" to unreadTextRemaining,
        "pageIncomplete" to pageIncomplete,
    )

    private fun sale(amount: Any?) = mapOf(
        "lineIndex" to 0,
        "proposedKind" to "SALE",
        "customerName" to "Rosa",
        "amountCents" to amount,
        "uncertainFields" to emptyList<String>(),
        "unreadableFields" to emptyList<String>(),
    )

    @Test fun `a well formed page is parsed`() {
        val result = parseLedgerExtractionResponse(response(listOf(sale("25000"))), REQUEST_ID)

        assertTrue(result is LedgerExtractionCallResult.Extracted)
        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertEquals(1, payload.operations.size)
        assertEquals("25000", payload.operations[0].amountCents)
        assertEquals(ProposedLedgerKind.SALE, payload.operations[0].proposedKind)
    }

    @Test fun `money as a JSON number is refused, not truncated`() {
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(listOf(sale(25000))), REQUEST_ID),
        )
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(listOf(sale(250.75))), REQUEST_ID),
        )
    }

    @Test fun `a response for another request is refused`() {
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(requestId = "someone-else"), REQUEST_ID),
        )
    }

    @Test fun `a v2 shaped response cannot be read as a page`() {
        val v2 = mapOf(
            "contractVersion" to 2,
            "requestId" to REQUEST_ID,
            "status" to "AI_PROPOSAL",
            "proposal" to null,
        )
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(v2, REQUEST_ID),
        )
    }

    @Test fun `a root with an extra key is refused`() {
        val extra = response(listOf(sale("100"))) + ("surprise" to true)
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(extra, REQUEST_ID),
        )
    }

    @Test fun `more than twenty five operations is refused rather than trimmed`() {
        val many = (0..25).map { index -> sale("100") + ("lineIndex" to index) }
        assertEquals(26, many.size)
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(many), REQUEST_ID),
        )
    }

    @Test fun `an incomplete page keeps its warning`() {
        val result = parseLedgerExtractionResponse(
            response(listOf(sale("100")), pageIncomplete = true, unreadTextRemaining = true),
            REQUEST_ID,
        )

        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertTrue(payload.pageIncomplete)
        assertTrue(payload.unreadTextRemaining)
    }

    @Test fun `an unknown kind is refused instead of guessed`() {
        val odd = sale("100") + ("proposedKind" to "REFUND")
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(listOf(odd)), REQUEST_ID),
        )
    }

    @Test fun `denials are reported as themselves so the screen can say why`() {
        val cases = mapOf(
            "AI_QUOTA_EXHAUSTED" to LedgerExtractionCallResult.QuotaExhausted,
            "AI_RATE_LIMITED" to LedgerExtractionCallResult.RateLimited,
            "AI_DISABLED" to LedgerExtractionCallResult.Disabled,
            "AI_ENTITLEMENT_REQUIRED" to LedgerExtractionCallResult.EntitlementRequired,
        )
        for ((status, expected) in cases) {
            assertEquals(
                expected,
                parseLedgerExtractionResponse(response(operations = null, status = status), REQUEST_ID),
            )
        }
    }

    @Test fun `unreadable fields survive the wire so the person can fill them in`() {
        val row = sale(null) + ("unreadableFields" to listOf("AMOUNT"))
        val result = parseLedgerExtractionResponse(response(listOf(row)), REQUEST_ID)

        val op = (result as LedgerExtractionCallResult.Extracted).extraction.operations[0]
        assertEquals(listOf(UnreadableLedgerField.AMOUNT), op.unreadableFields)
        assertEquals(null, op.amountCents)
    }

    @Test fun `the payload carries the exact contract fields`() {
        val payload = buildLedgerCallablePayload(REQUEST_ID, "aGVsbG8=", "image/jpeg")

        assertEquals(
            setOf("contractVersion", "requestId", "action", "imageBase64", "imageMimeType"),
            payload.keys,
        )
        assertEquals(4, payload["contractVersion"])
        assertEquals("extract_ledger_page", payload["action"])
    }

    @Test fun `a v4 page with v4 operation keys and pageDate is parsed`() {
        val v4Sale = sale("25000") + mapOf(
            "confidence" to mapOf("customer" to 0.95),
            "sources" to mapOf("customer" to "EXPLICIT"),
            "rawText" to "Rosa 250",
            "items" to listOf(mapOf("description" to "coca", "subtotalCents" to "25000")),
            "warnings" to emptyList<String>(),
        )
        val v4Response = response(listOf(v4Sale), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 6, "day" to 15), "confidence" to 0.9),
        )

        val result = parseLedgerExtractionResponse(v4Response, REQUEST_ID)
        assertTrue(result is LedgerExtractionCallResult.Extracted)
        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertEquals(1, payload.operations.size)
        assertEquals("25000", payload.operations[0].amountCents)
    }

    @Test fun `a fractional contract version is not version three or four`() {
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(listOf(sale("100")), contractVersion = 3.9), REQUEST_ID),
        )
    }

    @Test fun `a duplicated or out of order line index is refused`() {
        val duplicated = listOf(sale("100"), sale("200"))
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(duplicated), REQUEST_ID),
        )
        val descending = listOf(sale("100") + ("lineIndex" to 5), sale("200") + ("lineIndex" to 2))
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(descending), REQUEST_ID),
        )
    }

    @Test fun `a fractional line index is refused instead of truncated`() {
        val odd = listOf(sale("100") + ("lineIndex" to 1.5))
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(odd), REQUEST_ID),
        )
    }

    @Test fun `an operation with an unexpected key is refused`() {
        val odd = listOf(sale("100") + ("saleId" to "abc"))
        assertEquals(
            LedgerExtractionCallResult.Unavailable,
            parseLedgerExtractionResponse(response(odd), REQUEST_ID),
        )
    }

    private companion object {
        const val REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
