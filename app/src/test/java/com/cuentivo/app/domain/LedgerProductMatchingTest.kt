package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerProductMatchingTest {

    @Test
    fun `product candidate offered when description matches fuzzy catalog product`() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Maria Perez",
                amountCents = "7990",
                description = "Protect tap",
            )
        )
        val products = listOf(
            ProductCandidate(id = "prod-1", name = "Protector Tapete", salePriceCents = 7990L)
        )

        val matches = LedgerProductMatching.matchesFor(ops, products)

        assertNotNull(matches[0])
        assertEquals("Protector Tapete", matches[0]?.candidate?.name)
    }

    @Test
    fun `accepting product proposal NEVER changes operation amount`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Maria Perez",
            amountCents = "8500", // Notebook says 85.00
            description = "Protect tap",
        )
        val products = listOf(
            ProductCandidate(id = "prod-1", name = "Protector Tapete", salePriceCents = 7990L) // Catalog price 79.90
        )

        val matches = LedgerProductMatching.matchesFor(listOf(op), products)
        val candidate = matches[0]?.candidate
        assertNotNull(candidate)

        // Accepting proposal updates description ONLY
        val acceptedOp = op.copy(description = candidate!!.name)

        assertEquals("Protector Tapete", acceptedOp.description)
        assertEquals("8500", acceptedOp.amountCents) // Amount is untouched!
    }

    @Test
    fun `accepting product proposal does NOT make unreadable customer row registerable`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = null,
            amountCents = "8500",
            description = "Protect tap",
            unreadableFields = listOf(UnreadableLedgerField.CUSTOMER),
        )
        val products = listOf(
            ProductCandidate(id = "prod-1", name = "Protector Tapete", salePriceCents = 8500L)
        )

        val matches = LedgerProductMatching.matchesFor(listOf(op), products)
        val acceptedOp = op.copy(description = matches[0]!!.candidate!!.name)

        // Customer field is still unreadable, row remains unregisterable
        assertFalse(isRegisterableLedgerOperation(acceptedOp))
    }

    @Test
    fun `exact normalized product match is NOT offered again`() {
        val ops = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Maria Perez",
                amountCents = "7990",
                description = "Protector Tapete",
            )
        )
        val products = listOf(
            ProductCandidate(id = "prod-1", name = "Protector Tapete", salePriceCents = 7990L)
        )

        val matches = LedgerProductMatching.matchesFor(ops, products)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `after accepting product proposal batch plan keeps productId null and inventory untouched`() {
        val acceptedOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Maria Perez",
            amountCents = "8500",
            description = "Protector Tapete", // Accepted name
            date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 6, 15),
        )

        val plannerResult = AiLedgerBatchPlanner.plan(
            businessId = "biz-1",
            userId = "user-1",
            importSessionId = "session-test",
            operations = listOf(acceptedOp),
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
            nowEpochMillis = 1700000000000L,
        )

        assertTrue(plannerResult is BatchPlanningResult.Success)
        val plan = (plannerResult as BatchPlanningResult.Success).plan
        assertEquals(1, plan.saleItems.size)
        val item = plan.saleItems.first()
        assertNull(item.productId) // productId remains null!
        assertEquals("Protector Tapete", item.productNameSnapshot)
        assertEquals(8500L, item.unitPriceCents) // Amount intact
        assertEquals(1L, item.quantity)
    }

    @Test
    fun `product candidate matched by SKU in rawText or description`() {
        val opWithSkuInRaw = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Cliente Sintetico",
            amountCents = "4490",
            description = "Asientos",
            rawText = "WC-01 44.90",
        )
        val products = listOf(
            ProductCandidate(id = "p-sku-1", name = "Sani Asientos WC", salePriceCents = 4490L, sku = "WC-01")
        )

        val matches = LedgerProductMatching.matchesFor(listOf(opWithSkuInRaw), products)

        assertNotNull(matches[0])
        assertEquals("p-sku-1", matches[0]?.candidate?.id)
        assertEquals("Sani Asientos WC", matches[0]?.candidate?.name)
    }
}
