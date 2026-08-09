package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningBalancePlannerTest {

    private fun customer(id: String, name: String) = CustomerEntity(
        id, "biz", name, CustomerNameNormalizer.key(name), null, null, null, null, null,
        true, 1_000L, 1_000L, null,
    )

    private fun plan(
        rows: List<OpeningBalanceRow>,
        existing: List<CustomerEntity> = emptyList(),
    ): OpeningBalancePlan {
        var counter = 0
        return OpeningBalancePlanner.plan(
            businessId = "biz",
            ownerUserId = "owner",
            rows = rows,
            existingCustomers = existing,
            todayEpochDay = 20_000,
            nowEpochMillis = 1_700_000_000_000,
            newId = { "id-${counter++}" },
        )
    }

    @Test
    fun `every row becomes a customer and a debt`() {
        val result = plan(listOf(OpeningBalanceRow("Ana", "500"), OpeningBalanceRow("Beto", "250.50")))
        assertEquals(2, result.customers.size)
        assertEquals(2, result.entries.size)
        assertEquals(75_050, result.totalCents)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `a name already in the ledger reuses that customer instead of creating another`() {
        val existing = customer("existing-1", "José Pérez")
        // Different accents and case on purpose: this is the same key the unique index folds to,
        // so planning any other way would just move the failure to the insert.
        val result = plan(listOf(OpeningBalanceRow("jose perez", "300")), existing = listOf(existing))
        assertTrue(result.customers.isEmpty())
        assertEquals("existing-1", result.entries.single().sale.customerId)
    }

    @Test
    fun `the same name twice in one batch is rejected rather than charged twice`() {
        val result = plan(listOf(OpeningBalanceRow("Ana", "500"), OpeningBalanceRow("ANA", "500")))
        assertEquals(1, result.entries.size)
        assertEquals(OpeningBalanceRejection(1, OpeningBalanceRejection.Reason.DUPLICATE_NAME), result.rejected.single())
    }

    @Test
    fun `a blank name is rejected with its row number`() {
        val result = plan(listOf(OpeningBalanceRow("Ana", "500"), OpeningBalanceRow("   ", "100")))
        assertEquals(OpeningBalanceRejection(1, OpeningBalanceRejection.Reason.BLANK_NAME), result.rejected.single())
    }

    @Test
    fun `an unreadable or non-positive amount is rejected`() {
        val result = plan(
            listOf(
                OpeningBalanceRow("Ana", "abc"),
                OpeningBalanceRow("Beto", "0"),
                OpeningBalanceRow("Cata", ""),
            )
        )
        assertTrue(result.isEmpty)
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.all { it.reason == OpeningBalanceRejection.Reason.INVALID_AMOUNT })
    }

    @Test
    fun `a rejected row does not stop the rows around it from planning`() {
        val result = plan(
            listOf(
                OpeningBalanceRow("Ana", "500"),
                OpeningBalanceRow("Beto", "nope"),
                OpeningBalanceRow("Cata", "100"),
            )
        )
        assertEquals(2, result.entries.size)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `every debt is a debit charge matching its sale`() {
        val result = plan(listOf(OpeningBalanceRow("Ana", "500")))
        val entry = result.entries.single()
        assertEquals("CHARGE", entry.charge.type)
        assertEquals("DEBIT", entry.charge.direction)
        assertEquals(entry.sale.totalCents, entry.charge.amountCents)
        assertEquals(entry.sale.id, entry.item.saleId)
    }

    @Test
    fun `planning the same notebook twice plans the same rows`() {
        val rows = listOf(OpeningBalanceRow("Ana", "500"), OpeningBalanceRow("Beto", "250"))
        assertEquals(plan(rows), plan(rows))
    }
}
