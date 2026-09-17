package com.cuentivo.app.ai.ledger

import com.cuentivo.app.domain.FieldHealthColor
import com.cuentivo.app.domain.FieldOrigin
import com.cuentivo.app.domain.LedgerFieldHealth
import com.cuentivo.app.domain.LedgerItemsDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the server says about a row has to survive all the way to the person reviewing it.
 *
 * The certainty, the origin of each field and the products of a line were arriving from the callable
 * and being dropped on the floor by the Kotlin parser. Nothing crashed; the screen simply could not
 * tell a name read clearly from one carried over off a quote mark, which are different things to
 * check and only one of them lands money on the wrong person.
 */
class LedgerV4FidelityTest {

    private fun wire(op: Map<String, Any?>): LedgerOperation {
        val response = mapOf(
            "contractVersion" to 4,
            "requestId" to REQUEST_ID,
            "status" to "AI_PROPOSAL",
            "operations" to listOf(op),
            "discards" to emptyList<Any?>(),
            "unreadTextRemaining" to false,
            "pageIncomplete" to false,
        )
        val result = parseLedgerExtractionResponse(response, REQUEST_ID)
        assertTrue("la respuesta v4 debe entenderse", result is LedgerExtractionCallResult.Extracted)
        return (result as LedgerExtractionCallResult.Extracted).extraction.operations.single()
    }

    private fun baseOp(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "lineIndex" to 0,
        "proposedKind" to "SALE",
        "customerName" to "Rosa",
        "amountCents" to "25000",
        "uncertainFields" to emptyList<String>(),
        "unreadableFields" to emptyList<String>(),
    ) + extra

    @Test
    fun `certainty, origin and raw text survive the trip from the server`() {
        val op = wire(
            baseOp(
                mapOf(
                    "confidence" to mapOf("customer" to 0.93, "amount" to 0.99, "concept" to 0.61),
                    "sources" to mapOf("customer" to "INHERITED_PREVIOUS_ROW", "date" to "PAGE_DATE"),
                    "rawText" to "\" 250 protector",
                ),
            ),
        )

        assertEquals(0.93, op.confidence?.customer)
        assertEquals(0.61, op.confidence?.concept)
        assertEquals(LedgerFieldSource.INHERITED_PREVIOUS_ROW, op.sources?.customer)
        assertEquals(LedgerFieldSource.PAGE_DATE, op.sources?.date)
        assertEquals("\" 250 protector", op.rawText)
    }

    @Test
    fun `a v3 answer still parses, with no certainty and no origin`() {
        // The published v37 speaks v3, and a server rolled back to v3 must leave it working.
        val response = mapOf(
            "contractVersion" to 3,
            "requestId" to REQUEST_ID,
            "status" to "AI_PROPOSAL",
            "operations" to listOf(baseOp()),
            "discards" to emptyList<Any?>(),
            "unreadTextRemaining" to false,
            "pageIncomplete" to false,
        )

        val result = parseLedgerExtractionResponse(response, REQUEST_ID)

        val op = (result as LedgerExtractionCallResult.Extracted).extraction.operations.single()
        assertNull(op.confidence)
        assertNull(op.sources)
        assertNull(op.rawText)
        assertTrue(op.items.isEmpty())
    }

    @Test
    fun `an item subtotal that arrives as a number is refused, like every other amount`() {
        // A JSON number is how fractional money enters a ledger of whole centavos.
        val response = mapOf(
            "contractVersion" to 4,
            "requestId" to REQUEST_ID,
            "status" to "AI_PROPOSAL",
            "operations" to listOf(
                baseOp(mapOf("items" to listOf(mapOf("description" to "Coca", "subtotalCents" to 3800)))),
            ),
            "discards" to emptyList<Any?>(),
            "unreadTextRemaining" to false,
            "pageIncomplete" to false,
        )

        assertTrue(parseLedgerExtractionResponse(response, REQUEST_ID) is LedgerExtractionCallResult.Unavailable)
    }

    @Test
    fun `an inherited name is flagged as inherited even when it was read perfectly`() {
        // Green by every other measure and still worth confirming: the quote mark is an assumption
        // about the row above, so the reviewer has to check a different thing.
        val op = wire(
            baseOp(
                mapOf(
                    "confidence" to mapOf("customer" to 0.99),
                    "sources" to mapOf("customer" to "INHERITED_PREVIOUS_ROW"),
                ),
            ),
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        assertEquals(FieldHealthColor.GREEN, health.customerHealth)
        assertEquals(FieldOrigin.INHERITED_FROM_ROW_ABOVE, health.customerOrigin)
        assertTrue("un valor heredado se revisa", health.needsAttention)
    }

    @Test
    fun `low certainty asks for a second look but never clears a doubt`() {
        val unsure = wire(baseOp(mapOf("confidence" to mapOf("customer" to 0.40))))
        assertEquals(
            FieldHealthColor.YELLOW,
            LedgerFieldHealth.evaluate(
                op = unsure,
                financialDiagnostics = emptyList(),
                pageDateEpochDay = null,
                todayEpochDay = null,
                objections = emptyList(),
            ).customerHealth,
        )

        // And the other direction, which is the one that would cost money: high certainty over a
        // field the model itself declared unreadable does NOT turn it green.
        val declaredUnreadable = wire(
            mapOf(
                "lineIndex" to 0,
                "proposedKind" to "SALE",
                "amountCents" to "25000",
                "uncertainFields" to emptyList<String>(),
                "unreadableFields" to listOf("CUSTOMER"),
                "confidence" to mapOf("customer" to 0.99),
            ),
        )
        assertEquals(
            FieldHealthColor.RED,
            LedgerFieldHealth.evaluate(
                op = declaredUnreadable,
                financialDiagnostics = emptyList(),
                pageDateEpochDay = null,
                todayEpochDay = null,
                objections = emptyList(),
            ).customerHealth,
        )
        assertTrue(
            "la certeza no puede volver registrable un renglon",
            !isRegisterableLedgerOperation(declaredUnreadable),
        )
    }

    @Test
    fun `the products of a line are written into the description instead of being lost`() {
        val summary = LedgerItemsDescription.summarize(
            listOf(
                LedgerLineItem("Coca", quantity = 2, subtotalCents = "3800"),
                LedgerLineItem("Pan", subtotalCents = "2500"),
            ),
        )

        assertEquals("2 Coca ($38.00) + Pan ($25.00)", summary)
    }

    @Test
    fun `a single product is still summarized, because it may be all there is`() {
        // This used to return null on the reasoning that one product is what the description would
        // already say. But the contract lets the model send items with no description at all, and
        // then the product was dropped and the sale became "Venta en libreta". Which of the two the
        // planner prefers is the planner's decision; losing the product is nobody's.
        assertEquals("Coca ($38.00)", LedgerItemsDescription.summarize(listOf(LedgerLineItem("Coca", subtotalCents = "3800"))))
        assertNull(LedgerItemsDescription.summarize(emptyList()))
    }

    @Test
    fun `a long list is cut on a whole product and says it was cut`() {
        val many = (1..12).map { LedgerLineItem("Producto largo numero $it", subtotalCents = "1000") }

        val summary = LedgerItemsDescription.summarize(many)

        assertNotNull(summary)
        assertTrue("no puede pasar del limite del nombre", summary!!.length <= LedgerItemsDescription.MAX_LENGTH)
        assertTrue("tiene que decir que se corto", summary.endsWith("…"))
        assertTrue("no corta a media palabra", !summary.contains("Producto largo numero 1 +…"))
    }

    private companion object {
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
    }
}
