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

    // ---- El reintento v5 -> v4 (bloque 3, punto 5) ----

    private fun anExtractedPage() = LedgerExtractionCallResult.Extracted(
        LedgerExtractionPayload(
            operations = emptyList(),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
        ),
    )

    @Test fun `con v5 bueno hay una sola llamada`() {
        val versions = mutableListOf<Int>()

        val result = kotlinx.coroutines.runBlocking {
            extractPageWithFallback { version ->
                versions += version
                anExtractedPage()
            }
        }

        org.junit.Assert.assertEquals(listOf(5), versions)
        org.junit.Assert.assertTrue(result is LedgerExtractionCallResult.Extracted)
    }

    @Test fun `si v5 no contesta se reintenta una vez con la 4`() {
        val versions = mutableListOf<Int>()

        val result = kotlinx.coroutines.runBlocking {
            extractPageWithFallback { version ->
                versions += version
                if (version == 5) LedgerExtractionCallResult.Unavailable else anExtractedPage()
            }
        }

        org.junit.Assert.assertEquals("v5 y luego v4, una vez cada una", listOf(5, 4), versions)
        org.junit.Assert.assertTrue(result is LedgerExtractionCallResult.Extracted)
    }

    @Test fun `si v5 lanza tambien se reintenta una vez con la 4`() {
        // Un servidor sin v5 desplegado puede contestar error en vez de una respuesta rara.
        val versions = mutableListOf<Int>()

        val result = kotlinx.coroutines.runBlocking {
            extractPageWithFallback { version ->
                versions += version
                if (version == 5) throw IllegalStateException("boom") else anExtractedPage()
            }
        }

        org.junit.Assert.assertEquals(listOf(5, 4), versions)
        org.junit.Assert.assertTrue(result is LedgerExtractionCallResult.Extracted)
    }

    @Test fun `si fallan las dos no hay una tercera llamada`() {
        // Un solo reintento, nunca un bucle: cada llamada al modelo se paga.
        val versions = mutableListOf<Int>()

        val result = kotlinx.coroutines.runBlocking {
            extractPageWithFallback { version ->
                versions += version
                LedgerExtractionCallResult.Unavailable
            }
        }

        org.junit.Assert.assertEquals("exactamente dos llamadas", listOf(5, 4), versions)
        org.junit.Assert.assertEquals(LedgerExtractionCallResult.Unavailable, result)
    }

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
        assertEquals(5, payload["contractVersion"])
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

    @Test fun `the detected page date arrives parsed from a v4 answer`() {
        val v4Response = response(listOf(sale("25000")), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 7, "day" to 30), "confidence" to 0.95),
        )

        val result = parseLedgerExtractionResponse(v4Response, REQUEST_ID)

        val detected = (result as LedgerExtractionCallResult.Extracted).extraction.detectedPageDate
        assertEquals(SafeDateCandidate(2026, 7, 30), detected?.date)
        assertEquals(0.95, detected?.confidence)
    }

    @Test fun `a v3 answer carries no page date and still parses`() {
        // The published v37 speaks v3, and v3 has no pageDate key. A server rolled back to v3 must
        // leave the app reading pages, not refusing them.
        val result = parseLedgerExtractionResponse(response(listOf(sale("25000"))), REQUEST_ID)

        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertEquals(null, payload.detectedPageDate)
    }

    @Test fun `a v4 answer without pageDate carries no detected date`() {
        val v4Response = response(listOf(sale("25000")), contractVersion = 4)

        val result = parseLedgerExtractionResponse(v4Response, REQUEST_ID)

        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertEquals(null, payload.detectedPageDate)
    }

    @Test fun `a detected page date that is not a real calendar day is not offered`() {
        // June 31st does not exist. A date like that cannot be offered for a tap, because the tap
        // would send ten movements to a day that never happened.
        val v4Response = response(listOf(sale("25000")), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 6, "day" to 31), "confidence" to 0.99),
        )

        val result = parseLedgerExtractionResponse(v4Response, REQUEST_ID)

        val payload = (result as LedgerExtractionCallResult.Extracted).extraction
        assertEquals(null, payload.detectedPageDate)
    }

    @Test fun `a detected page date with a malformed shape is dropped, not fatal`() {
        // The page date is advisory: a malformed one must not cost the twenty readable rows that
        // came with it. The page parses; the offer simply does not exist.
        val wrongShape = response(listOf(sale("25000")), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 7), "confidence" to 0.9),
        )
        val outOfRange = response(listOf(sale("25000")), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 7, "day" to 30), "confidence" to 1.5),
        )
        val extraKey = response(listOf(sale("25000")), contractVersion = 4) + mapOf(
            "pageDate" to mapOf("date" to mapOf("year" to 2026, "month" to 7, "day" to 30), "confidence" to 0.9, "note" to "x"),
        )

        for (response in listOf(wrongShape, outOfRange, extraKey)) {
            val result = parseLedgerExtractionResponse(response, REQUEST_ID)
            assertTrue(result is LedgerExtractionCallResult.Extracted)
            assertEquals(null, (result as LedgerExtractionCallResult.Extracted).extraction.detectedPageDate)
        }
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

    @Test
    fun `an unbounded rawText is refused, because it ends up in a Bundle`() {
        // The server caps this at 200. The client is what decides how much lands in the saved state,
        // and a Bundle past the transaction limit does not degrade -- it kills the app. Refused and
        // not trimmed: a shortened rawText is evidence that no longer matches the paper.
        val response = v4ResponseWith(mapOf("rawText" to "x".repeat(201)))

        assertTrue(parseLedgerExtractionResponse(response, REQUEST_ID) is LedgerExtractionCallResult.Unavailable)
    }

    @Test
    fun `a rawText right at the cap is still accepted`() {
        val response = v4ResponseWith(mapOf("rawText" to "x".repeat(200)))

        assertTrue(parseLedgerExtractionResponse(response, REQUEST_ID) is LedgerExtractionCallResult.Extracted)
    }

    @Test
    fun `an absurd list of items or warnings is refused`() {
        val manyItems = v4ResponseWith(
            mapOf("items" to (1..26).map { mapOf("description" to "Producto $it") }),
        )
        assertTrue(parseLedgerExtractionResponse(manyItems, REQUEST_ID) is LedgerExtractionCallResult.Unavailable)

        val manyWarnings = v4ResponseWith(mapOf("warnings" to (1..17).map { "AMBIGUOUS_KIND" }))
        assertTrue(parseLedgerExtractionResponse(manyWarnings, REQUEST_ID) is LedgerExtractionCallResult.Unavailable)
    }

    private fun v4ResponseWith(extra: Map<String, Any?>): Map<String, Any?> = mapOf(
        "contractVersion" to 4,
        "requestId" to REQUEST_ID,
        "status" to "AI_PROPOSAL",
        "operations" to listOf(
            mapOf(
                "lineIndex" to 0,
                "proposedKind" to "SALE",
                "customerName" to "Rosa",
                "amountCents" to "25000",
                "uncertainFields" to emptyList<String>(),
                "unreadableFields" to emptyList<String>(),
            ) + extra,
        ),
        "discards" to emptyList<Any?>(),
        "unreadTextRemaining" to false,
        "pageIncomplete" to false,
    )
}
