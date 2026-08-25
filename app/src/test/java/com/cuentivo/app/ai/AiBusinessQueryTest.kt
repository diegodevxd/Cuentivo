package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiBusinessQueryTest {

    private val businessId = "biz-123"

    private fun sampleCustomer(
        id: String,
        name: String,
        normalizedName: String = name.lowercase(),
        bizId: String = businessId,
        isActive: Boolean = true,
        deletedAtEpochMillis: Long? = null,
    ) = CustomerEntity(
        id = id,
        businessId = bizId,
        name = name,
        normalizedName = normalizedName,
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = isActive,
        createdAtEpochMillis = 1000L,
        updatedAtEpochMillis = 1000L,
        deletedAtEpochMillis = deletedAtEpochMillis,
    )

    private fun createResolver(
        searchCustomers: suspend (String, String) -> List<CustomerEntity> = { _, _ -> emptyList() },
        customerBalance: suspend (String, String) -> Long = { _, _ -> 0L },
        customersWithBalances: suspend (String) -> List<CustomerBalanceRow> = { _ -> emptyList() },
        salesTotal: suspend (String, Long, Long) -> Long = { _, _, _ -> 0L },
        collectedTotal: suspend (String, Long, Long) -> Long = { _, _, _ -> 0L },
    ) = AiBusinessQueryResolver(
        searchCustomers = searchCustomers,
        customerBalance = customerBalance,
        customersWithBalances = customersWithBalances,
        salesTotal = salesTotal,
        collectedTotal = collectedTotal,
    )

    @Test
    fun `customer balance query with exact match returns CustomerBalance with seam balance`() = runBlocking {
        val customer = sampleCustomer("c1", "Ana Gómez", "ana gomez")
        val resolver = createResolver(
            searchCustomers = { biz, query ->
                assertEquals(businessId, biz)
                assertEquals("Ana Gómez", query)
                listOf(customer)
            },
            customerBalance = { biz, cid ->
                assertEquals(businessId, biz)
                assertEquals("c1", cid)
                75000L
            },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana Gómez",
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue("result must be Answer.CustomerBalance", result is AiBusinessQueryResult.Answer.CustomerBalance)
        val answer = result as AiBusinessQueryResult.Answer.CustomerBalance
        assertEquals("c1", answer.customerId)
        assertEquals("Ana Gómez", answer.customerName)
        assertEquals(75000L, answer.balanceCents)
    }

    @Test
    fun `customer balance query ignores customers from another business, inactive, or deleted`() = runBlocking {
        val foreign = sampleCustomer("c-other", "Ana Gómez", "ana gomez", bizId = "biz-other")
        val inactive = sampleCustomer("c-inact", "Ana Gómez", "ana gomez", isActive = false)
        val deleted = sampleCustomer("c-del", "Ana Gómez", "ana gomez", deletedAtEpochMillis = 5000L)
        val resolver = createResolver(
            searchCustomers = { _, _ -> listOf(foreign, inactive, deleted) },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana Gómez",
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue("Mismatched or inactive customers must be filtered, leading to CustomerNotFound", result is AiBusinessQueryResult.CustomerNotFound)
        assertEquals("Ana Gómez", (result as AiBusinessQueryResult.CustomerNotFound).queriedName)
    }

    @Test
    fun `customer balance query with ambiguous match returns CustomerSelectionRequired with unmodifiable candidates`() = runBlocking {
        val c1 = sampleCustomer("c1", "Ana Gómez", "ana gomez")
        val c2 = sampleCustomer("c2", "Ana Sofía Gómez", "ana sofia gomez")
        val foreign = sampleCustomer("c-other", "Ana Gómez", "ana gomez", bizId = "biz-other")
        val resolver = createResolver(
            searchCustomers = { _, _ -> listOf(c1, c2, foreign) },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana",
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.CustomerSelectionRequired)
        val selection = result as AiBusinessQueryResult.CustomerSelectionRequired
        assertEquals(2, selection.candidates.size)
        assertEquals("c1", selection.candidates[0].customerId)
        assertEquals("Ana Gómez", selection.candidates[0].displayName)
        assertEquals("c2", selection.candidates[1].customerId)
        assertEquals("Ana Sofía Gómez", selection.candidates[1].displayName)

        try {
            (selection.candidates as MutableList).add(AiQueryCustomerCandidate("c3", "Injected"))
            fail("Candidate list must be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
    }

    @Test
    fun `customer balance query with explicit UseExisting choice returns selected customer balance`() = runBlocking {
        val c1 = sampleCustomer("c1", "Ana Gómez", "ana gomez")
        val c2 = sampleCustomer("c2", "Ana Sofía Gómez", "ana sofia gomez")
        val resolver = createResolver(
            searchCustomers = { _, _ -> listOf(c1, c2) },
            customerBalance = { _, cid ->
                if (cid == "c2") 32000L else 10000L
            },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana",
        )

        val result = resolver.resolve(businessId, proposal, choice = AiEntityChoice.UseExisting("c2"))
        assertTrue(result is AiBusinessQueryResult.Answer.CustomerBalance)
        val answer = result as AiBusinessQueryResult.Answer.CustomerBalance
        assertEquals("c2", answer.customerId)
        assertEquals("Ana Sofía Gómez", answer.customerName)
        assertEquals(32000L, answer.balanceCents)
    }

    @Test
    fun `customer balance query with UseExisting choice for foreign or non-candidate returns Unavailable`() = runBlocking {
        val c1 = sampleCustomer("c1", "Ana Gómez", "ana gomez")
        val foreign = sampleCustomer("c-other", "Ana Gómez", "ana gomez", bizId = "biz-other")
        val resolver = createResolver(
            searchCustomers = { _, _ -> listOf(c1, foreign) },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana",
        )

        // Attempting to select foreign customer ID
        val foreignResult = resolver.resolve(businessId, proposal, choice = AiEntityChoice.UseExisting("c-other"))
        assertEquals(AiBusinessQueryResult.Unavailable, foreignResult)

        // Attempting to select non-existent customer ID
        val missingResult = resolver.resolve(businessId, proposal, choice = AiEntityChoice.UseExisting("c-alien"))
        assertEquals(AiBusinessQueryResult.Unavailable, missingResult)
    }

    @Test
    fun `customer balance query with CreateNew choice returns Unavailable because queries cannot create`() = runBlocking {
        val c1 = sampleCustomer("c1", "Ana Gómez", "ana gomez")
        val resolver = createResolver(
            searchCustomers = { _, _ -> listOf(c1) },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana",
        )

        val result = resolver.resolve(businessId, proposal, choice = AiEntityChoice.CreateNew)
        assertEquals(AiBusinessQueryResult.Unavailable, result)
    }

    @Test
    fun `customer balance query with unknown customer returns CustomerNotFound with queried name`() = runBlocking {
        val resolver = createResolver(
            searchCustomers = { _, _ -> emptyList() },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Desconocido",
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.CustomerNotFound)
        assertEquals("Desconocido", (result as AiBusinessQueryResult.CustomerNotFound).queriedName)
    }

    @Test
    fun `customer balance query with blank customerName returns Unavailable`() = runBlocking {
        val resolver = createResolver()
        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "   ",
        )

        assertEquals(AiBusinessQueryResult.Unavailable, resolver.resolve(businessId, proposal))
        assertEquals(AiBusinessQueryResult.Unavailable, resolver.resolve(businessId, proposal.copy(customerName = null)))
    }

    @Test
    fun `customer balance query with blank businessId returns Unavailable`() = runBlocking {
        val resolver = createResolver()
        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMER_BALANCE,
            customerName = "Ana",
        )

        assertEquals(AiBusinessQueryResult.Unavailable, resolver.resolve("   ", proposal))
        assertEquals(AiBusinessQueryResult.Unavailable, resolver.resolve("", proposal))
    }

    @Test
    fun `customers with debt filters out other business, inactive, deleted, and non-positive balances, and caps at 20 rows`() = runBlocking {
        val validRows = (1..25).map { i ->
            CustomerBalanceRow(
                customer = sampleCustomer("c$i", "Cliente $i"),
                balanceCents = when (i) {
                    1 -> 0L // Zero balance -> filtered out
                    2 -> -5000L // Credit balance -> filtered out
                    else -> i * 1000L // Positive debt
                },
            )
        }
        val foreignRow = CustomerBalanceRow(
            customer = sampleCustomer("c-other", "Foreign", bizId = "biz-other"),
            balanceCents = 999_000L,
        )
        val inactiveRow = CustomerBalanceRow(
            customer = sampleCustomer("c-inact", "Inactive", isActive = false),
            balanceCents = 999_000L,
        )
        val deletedRow = CustomerBalanceRow(
            customer = sampleCustomer("c-del", "Deleted", deletedAtEpochMillis = 9000L),
            balanceCents = 999_000L,
        )

        val resolver = createResolver(
            customersWithBalances = { biz ->
                assertEquals(businessId, biz)
                listOf(foreignRow, inactiveRow, deletedRow) + validRows
            },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMERS_WITH_DEBT,
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.Answer.CustomersWithDebt)
        val answer = result as AiBusinessQueryResult.Answer.CustomersWithDebt

        // There were 23 positive debtors in validRows, foreign/inactive/deleted excluded, capped at 20
        assertEquals(20, answer.rows.size)
        assertEquals("c3", answer.rows.first().customerId)
        assertEquals(3000L, answer.rows.first().balanceCents)
        assertEquals("c22", answer.rows.last().customerId)

        // Total cents is the sum of all positive balances for the business (3..25 -> sum of 3000..25000 = 322000L)
        val expectedTotal = (3..25).sumOf { it * 1000L }
        assertEquals(expectedTotal, answer.totalCents)

        try {
            (answer.rows as MutableList).add(AiDebtorRow("c99", "Injected", 1000L))
            fail("Rows list must be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
    }

    @Test
    fun `customers with debt with zero debtors returns empty list and 0 cents as valid answer`() = runBlocking {
        val rows = listOf(
            CustomerBalanceRow(customer = sampleCustomer("c1", "Cliente 1"), balanceCents = 0L),
            CustomerBalanceRow(customer = sampleCustomer("c2", "Cliente 2"), balanceCents = -10000L),
            CustomerBalanceRow(customer = sampleCustomer("c3", "Foreign", bizId = "biz-other"), balanceCents = 50000L),
        )

        val resolver = createResolver(
            customersWithBalances = { _ -> rows },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.CUSTOMERS_WITH_DEBT,
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.Answer.CustomersWithDebt)
        val answer = result as AiBusinessQueryResult.Answer.CustomersWithDebt
        assertEquals(0, answer.rows.size)
        assertEquals(0L, answer.totalCents)
    }

    @Test
    fun `sales for period returns sold and collected totals from seams`() = runBlocking {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 7)

        val resolver = createResolver(
            salesTotal = { biz, s, e ->
                assertEquals(businessId, biz)
                assertEquals(start.toEpochDay(), s)
                assertEquals(end.toEpochDay(), e)
                150_000L
            },
            collectedTotal = { biz, s, e ->
                assertEquals(businessId, biz)
                assertEquals(start.toEpochDay(), s)
                assertEquals(end.toEpochDay(), e)
                80_000L
            },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 7),
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.Answer.SalesForPeriod)
        val answer = result as AiBusinessQueryResult.Answer.SalesForPeriod
        assertEquals(start.toEpochDay(), answer.startEpochDay)
        assertEquals(end.toEpochDay(), answer.endEpochDay)
        assertEquals(150_000L, answer.soldCents)
        assertEquals(80_000L, answer.collectedCents)
    }

    @Test
    fun `sales for period with zero sales and zero collected returns valid Answer with 0 cents`() = runBlocking {
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 1)

        val resolver = createResolver(
            salesTotal = { _, _, _ -> 0L },
            collectedTotal = { _, _, _ -> 0L },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 1),
        )

        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.Answer.SalesForPeriod)
        val answer = result as AiBusinessQueryResult.Answer.SalesForPeriod
        assertEquals(0L, answer.soldCents)
        assertEquals(0L, answer.collectedCents)
    }

    @Test
    fun `sales for period with invalid calendar date returns Blocked INVALID_DATE`() = runBlocking {
        val resolver = createResolver()

        // Feb 30th does not exist
        val proposal1 = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2026, 2, 30, 2026, 3, 5),
        )
        val result1 = resolver.resolve(businessId, proposal1)
        assertEquals(AiBusinessQueryResult.Blocked(AiQueryBlockReason.INVALID_DATE), result1)

        // Month 13 does not exist
        val proposal2 = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2026, 1, 1, 2026, 13, 1),
        )
        val result2 = resolver.resolve(businessId, proposal2)
        assertEquals(AiBusinessQueryResult.Blocked(AiQueryBlockReason.INVALID_DATE), result2)
    }

    @Test
    fun `sales for period with start date after end date returns Blocked START_AFTER_END`() = runBlocking {
        val resolver = createResolver()

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2026, 8, 10, 2026, 8, 5),
        )
        val result = resolver.resolve(businessId, proposal)
        assertEquals(AiBusinessQueryResult.Blocked(AiQueryBlockReason.START_AFTER_END), result)
    }

    @Test
    fun `sales for period with range exceeding 366 days returns Blocked PERIOD_TOO_LONG`() = runBlocking {
        val resolver = createResolver()

        // 367 days (e.g. Jan 1 2025 to Jan 3 2026 = 368 days)
        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2025, 1, 1, 2026, 1, 3),
        )
        val result = resolver.resolve(businessId, proposal)
        assertEquals(AiBusinessQueryResult.Blocked(AiQueryBlockReason.PERIOD_TOO_LONG), result)
    }

    @Test
    fun `sales for period with exactly 366 days (leap year) is accepted`() = runBlocking {
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 12, 31) // 366 days in leap year 2024

        val resolver = createResolver(
            salesTotal = { _, _, _ -> 50000L },
            collectedTotal = { _, _, _ -> 30000L },
        )

        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = AiPeriodCandidate(2024, 1, 1, 2024, 12, 31),
        )
        val result = resolver.resolve(businessId, proposal)
        assertTrue(result is AiBusinessQueryResult.Answer.SalesForPeriod)
    }

    @Test
    fun `sales for period with missing period returns Unavailable`() = runBlocking {
        val resolver = createResolver()
        val proposal = BusinessQueryProposal(
            queryType = BusinessQueryType.SALES_FOR_PERIOD,
            period = null,
        )
        assertEquals(AiBusinessQueryResult.Unavailable, resolver.resolve(businessId, proposal))
    }

    @Test
    fun `resolver rethrows CancellationException from any seam`() = runBlocking {
        val resolverCustomer = createResolver(
            searchCustomers = { _, _ -> throw CancellationException("test cancel search") },
        )
        try {
            resolverCustomer.resolve(businessId, BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, "Ana"))
            fail("Must rethrow CancellationException")
        } catch (_: CancellationException) {}

        val resolverBalance = createResolver(
            searchCustomers = { _, _ -> listOf(sampleCustomer("c1", "Ana", "ana")) },
            customerBalance = { _, _ -> throw CancellationException("test cancel balance") },
        )
        try {
            resolverBalance.resolve(businessId, BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, "Ana"))
            fail("Must rethrow CancellationException")
        } catch (_: CancellationException) {}

        val resolverDebtors = createResolver(
            customersWithBalances = { _ -> throw CancellationException("test cancel debtors") },
        )
        try {
            resolverDebtors.resolve(businessId, BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT))
            fail("Must rethrow CancellationException")
        } catch (_: CancellationException) {}

        val resolverSales = createResolver(
            salesTotal = { _, _, _ -> throw CancellationException("test cancel sales") },
        )
        try {
            resolverSales.resolve(businessId, BusinessQueryProposal(BusinessQueryType.SALES_FOR_PERIOD, period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 2)))
            fail("Must rethrow CancellationException")
        } catch (_: CancellationException) {}
    }

    @Test
    fun `resolver maps seam Exception to Unavailable`() = runBlocking {
        val resolverCustomer = createResolver(
            searchCustomers = { _, _ -> throw RuntimeException("DB error") },
        )
        assertEquals(
            AiBusinessQueryResult.Unavailable,
            resolverCustomer.resolve(businessId, BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, "Ana")),
        )

        val resolverDebtors = createResolver(
            customersWithBalances = { _ -> throw IllegalStateException("IO failure") },
        )
        assertEquals(
            AiBusinessQueryResult.Unavailable,
            resolverDebtors.resolve(businessId, BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT)),
        )

        val resolverSales = createResolver(
            salesTotal = { _, _, _ -> throw RuntimeException("SQL failure") },
        )
        assertEquals(
            AiBusinessQueryResult.Unavailable,
            resolverSales.resolve(businessId, BusinessQueryProposal(BusinessQueryType.SALES_FOR_PERIOD, period = AiPeriodCandidate(2026, 8, 1, 2026, 8, 2))),
        )
    }
}
