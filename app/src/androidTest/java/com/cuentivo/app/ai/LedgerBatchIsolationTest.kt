package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import com.cuentivo.app.domain.AiLedgerBatchPlanner
import com.cuentivo.app.domain.BatchPlanningResult
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.SaleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page of a paper notebook becoming debt in the ledger, against real Room.
 *
 * These are the cases the plan says the batch exists to solve, and none of them can be shown with a
 * fake repository: two payments from the same customer, one customer spread over several lines, and
 * a confirmation that arrives twice. The balance is always read back through the same query
 * production uses, never recomputed by the test.
 */
@RunWith(AndroidJUnit4::class)
class LedgerBatchIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var saleRepository: SaleRepository

    private val businessId = "biz-ledger-batch"
    private val userId = "user-ledger-batch"
    // 2026-06-01, comfortably after the notebook dates the tests use: a date in the future is
    // not a notebook date and the planner refuses it, as it should.
    private val todayEpochDay = 20_605L
    private val nowEpochMillis = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
        saleRepository = SaleRepository(database.financialDao())

        database.businessDao().insert(
            BusinessEntity(
                id = businessId,
                name = "Abarrotes Libreta",
                ownerUserId = userId,
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 10_000L,
                updatedAtEpochMillis = 10_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sale(lineIndex: Int, name: String, cents: String, day: Int = 10) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        date = SafeDateCandidate(2026, 3, day),
    )

    private fun payment(lineIndex: Int, name: String, cents: String, day: Int = 12) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.PAYMENT,
        customerName = name,
        amountCents = cents,
        date = SafeDateCandidate(2026, 3, day),
    )

    private suspend fun existingCustomers(): Map<String, CustomerEntity> =
        database.customerDao().activeWithBalances(businessId).associate { it.customer.normalizedName to it.customer }

    private suspend fun outstandingByCustomer(): Map<String, List<OutstandingSale>> =
        database.customerDao().activeWithBalances(businessId).associate { row ->
            row.customer.id to database.financialDao()
                .outstandingSalesForCustomer(businessId, row.customer.id)
                .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
        }

    private suspend fun planFor(operations: List<LedgerOperation>): BatchPlanningResult =
        AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            operations = operations,
            existingCustomerMap = existingCustomers(),
            existingOutstandingSalesByCustomerId = outstandingByCustomer(),
            pageDateEpochDay = todayEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

    /**
     * Goes through the very coordination the app uses, so removing the lookup that production
     * depends on turns these tests red instead of leaving them green.
     */
    private suspend fun confirm(operations: List<LedgerOperation>): Int? =
        com.cuentivo.app.domain.LedgerBatchCoordinator.confirm(
            businessId = businessId,
            userId = userId,
            operations = operations,
            pageDateEpochDay = todayEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            alreadyRecorded = { key -> database.financialDao().operationResultId(key) != null },
            loadCustomersByNormalizedName = { existingCustomers() },
            loadOutstandingByCustomerId = { outstandingByCustomer() },
            write = { plan -> saleRepository.registerLedgerBatch(plan) },
        )

    private suspend fun write(operations: List<LedgerOperation>): ExpectedLedgerBatchResult {
        val recorded = confirm(operations)
        assertTrue("The page must be recorded: " + operations, recorded != null)
        return ExpectedLedgerBatchResult.Recorded("checked-by-coordinator", recorded!!)
    }

    private suspend fun balanceOf(name: String): Long {
        val customer = database.customerDao().activeWithBalances(businessId)
            .first { it.customer.normalizedName == name }
        return database.financialDao().customerBalanceCents(businessId, customer.customer.id)
    }

    @Test
    fun a_page_of_sales_becomes_debt_for_each_customer() = runBlocking {
        val result = write(listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000")))

        assertTrue(result is ExpectedLedgerBatchResult.Recorded)
        assertEquals(25_000L, balanceOf("rosa"))
        assertEquals(30_000L, balanceOf("juan"))
    }

    @Test
    fun one_customer_across_three_lines_is_created_once() = runBlocking {
        write(listOf(sale(0, "Rosa", "10000"), sale(1, "Rosa", "20000"), sale(2, "Rosa", "5000")))

        val rosas = database.customerDao().activeWithBalances(businessId)
            .filter { it.customer.normalizedName == "rosa" }
        assertEquals("Rosa must exist once, not once per line", 1, rosas.size)
        assertEquals(35_000L, balanceOf("rosa"))
    }

    @Test
    fun two_payments_from_the_same_customer_are_both_applied() = runBlocking {
        // The reason this batch is atomic: applied one at a time, the second payment would be
        // rejected for disagreeing with the balance the first one just changed.
        write(listOf(sale(0, "Rosa", "50000")))

        val result = write(listOf(payment(1, "Rosa", "20000"), payment(2, "Rosa", "15000")))

        assertTrue(result is ExpectedLedgerBatchResult.Recorded)
        assertEquals(15_000L, balanceOf("rosa"))
    }

    @Test
    fun confirming_the_same_page_twice_records_it_once() = runBlocking {
        val operations = listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000"))

        write(operations)
        val balanceAfterFirst = balanceOf("rosa")
        val salesAfterFirst = database.financialDao().registeredSaleCount(businessId)
        val auditAfterFirst = database.auditDao().forBusiness(businessId).size

        write(operations)

        assertEquals(balanceAfterFirst, balanceOf("rosa"))
        assertEquals(salesAfterFirst, database.financialDao().registeredSaleCount(businessId))
        assertEquals(auditAfterFirst, database.auditDao().forBusiness(businessId).size)
    }

    @Test
    fun a_line_that_is_not_registerable_is_left_out_and_the_rest_is_written() = runBlocking {
        val unreadable: LedgerOperation = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(com.cuentivo.app.ai.ledger.UnreadableLedgerField.AMOUNT),
        )

        val result = write(listOf(sale(0, "Rosa", "25000"), unreadable))

        assertTrue(result is ExpectedLedgerBatchResult.Recorded)
        assertEquals(25_000L, balanceOf("rosa"))
        assertTrue(
            "A row nobody could read must not create its customer",
            database.customerDao().activeWithBalances(businessId).none { it.customer.normalizedName == "juan" },
        )
    }

    @Test
    fun a_failure_after_inserting_leaves_absolutely_nothing() = runBlocking {
        // A batch is all or nothing. This forces the rejection to be discovered while rows are
        // already being written, which is the moment where a half written page would survive.
        write(listOf(sale(0, "Rosa", "25000")))
        val salesBefore = database.financialDao().registeredSaleCount(businessId)
        val auditBefore = database.auditDao().forBusiness(businessId).size
        val balanceBefore = balanceOf("rosa")

        val planned = planFor(listOf(sale(1, "Nuevo Cliente", "40000"), sale(2, "Otro Cliente", "10000")))
        val plan = (planned as BatchPlanningResult.Success).plan
        // The failure has to be discovered while rows are already going in, not during the checks
        // that run before the first insert. A duplicated sale id passes every validation and blows
        // up on the second INSERT, which is exactly the moment a half written page would survive.
        val broken = plan.copy(
            sales = plan.sales + plan.sales.first().copy(saleNumber = plan.sales.first().saleNumber + "-dup"),
        )

        var threw = false
        try {
            saleRepository.registerLedgerBatch(broken)
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("A batch that cannot be written whole must be refused", threw)
        assertEquals(
            "Nothing of the refused batch may remain, movements included",
            0,
            database.financialDao().allMovementsForBackfill(businessId).count { movement ->
                plan.chargeMovements.any { it.id == movement.id }
            },
        )
        assertEquals(salesBefore, database.financialDao().registeredSaleCount(businessId))
        assertEquals(auditBefore, database.auditDao().forBusiness(businessId).size)
        assertEquals(balanceBefore, balanceOf("rosa"))
        assertTrue(
            "No customer from the refused batch may survive",
            database.customerDao().activeWithBalances(businessId)
                .none { it.customer.normalizedName == "nuevo cliente" || it.customer.normalizedName == "otro cliente" },
        )
        // And the key must be free, so correcting the page and confirming again still works.
        val retry = write(listOf(sale(1, "Nuevo Cliente", "40000"), sale(2, "Otro Cliente", "10000")))
        assertTrue(retry is ExpectedLedgerBatchResult.Recorded)
        assertEquals(40_000L, balanceOf("nuevo cliente"))
    }

    @Test
    fun two_confirmations_at_once_write_the_page_once() = runBlocking {
        val operations = listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000"))
        val planned = (planFor(operations) as BatchPlanningResult.Success).plan

        val results = listOf(
            async { runCatching { saleRepository.registerLedgerBatch(planned) } },
            async { runCatching { saleRepository.registerLedgerBatch(planned) } },
        ).awaitAll()

        assertTrue("Both confirmations must be answered", results.all { it.isSuccess })
        assertEquals(25_000L, balanceOf("rosa"))
        assertEquals(2, database.financialDao().registeredSaleCount(businessId))
    }

    @Test
    fun reconfirming_a_page_of_payments_reports_the_same_thing() = runBlocking {
        // A payment that settles the debt completely: planning it a second time would find nothing
        // left to pay and reject it, so passing proves the batch is recognised by its key.
        write(listOf(sale(0, "Rosa", "50000")))
        val payments = listOf(payment(1, "Rosa", "50000"))

        val first = write(payments)
        val balanceAfterFirst = balanceOf("rosa")
        val second = write(payments)

        assertTrue(first is ExpectedLedgerBatchResult.Recorded)
        assertTrue("Re-confirming must report the first result, not fail", second is ExpectedLedgerBatchResult.Recorded)
        assertEquals(balanceAfterFirst, balanceOf("rosa"))
    }

    @Test
    fun the_audit_log_names_each_written_row() = runBlocking {
        write(listOf(sale(0, "Rosa", "25000"), sale(1, "Juan", "30000")))

        val audit = database.auditDao().forBusiness(businessId)
        val saleEvents = audit.filter { it.entityType == "SALE" }
        assertEquals("One audit row per sale, not one for the whole page", 2, saleEvents.size)
        assertEquals(2, saleEvents.map { it.entityId }.distinct().size)
    }
}
