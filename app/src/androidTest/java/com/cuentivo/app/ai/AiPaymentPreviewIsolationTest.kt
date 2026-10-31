package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SyncOperationEntity
import com.cuentivo.app.domain.CustomerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that [AiPaymentPreviewResolver] reads solely through safe projections and never performs
 * any financial mutations, creates sales, records payments, inserts account movements, enqueues
 * outbox operations, or mutates customer balances in Room.
 */
@RunWith(AndroidJUnit4::class)
class AiPaymentPreviewIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var customerRepository: CustomerRepository
    private lateinit var resolver: AiPaymentPreviewResolver

    private val businessId = "biz-isolation-test"
    private val userId = "user-isolation-test"

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()

        customerRepository = CustomerRepository(database.customerDao())
        resolver = AiPaymentPreviewResolver(
            searchCustomers = customerRepository::search,
            loadBalanceCents = database.financialDao()::customerBalanceCents,
        )

        val business = BusinessEntity(
            id = businessId,
            name = "Abarrotes El Aislado",
            ownerUserId = userId,
            currency = "MXN",
            timezone = "America/Mexico_City",
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
        )
        database.businessDao().insert(business)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createCustomer(id: String, name: String): CustomerEntity = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name.lowercase().trim(),
        phone = "5550001122",
        email = null,
        address = null,
        notes = null,
        creditLimitCents = 100_000L,
        isActive = true,
        createdAtEpochMillis = 10_000L,
        updatedAtEpochMillis = 10_000L,
        deletedAtEpochMillis = null,
    )

    private fun createSale(saleId: String, customerId: String, amountCents: Long): SaleEntity = SaleEntity(
        id = saleId,
        businessId = businessId,
        customerId = customerId,
        sellerUserId = userId,
        saleNumber = "V-$saleId",
        saleDateEpochDay = 20_000,
        dueDateEpochDay = 20_030,
        subtotalCents = amountCents,
        discountCents = 0L,
        totalCents = amountCents,
        isCanceled = false,
        notes = null,
        createdAtEpochMillis = 10_000L,
        updatedAtEpochMillis = 10_000L,
    )

    private fun createDebitMovement(movementId: String, customerId: String, saleId: String, amountCents: Long): AccountMovementEntity =
        AccountMovementEntity(
            id = movementId,
            businessId = businessId,
            customerId = customerId,
            saleId = saleId,
            paymentId = null,
            type = "CHARGE",
            amountCents = amountCents,
            direction = "DEBIT",
            occurredAtEpochMillis = 10_000L,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = "Cargo inicial",
        )

    private data class DatabaseSnapshot(
        val sales: List<SaleEntity>,
        val payments: List<PaymentEntity>,
        val allocations: List<PaymentAllocationEntity>,
        val movements: List<AccountMovementEntity>,
        val outbox: List<SyncOperationEntity>,
        val customers: List<CustomerBalanceRow>,
    )

    private suspend fun captureSnapshot(scopedBusinessId: String = businessId): DatabaseSnapshot = DatabaseSnapshot(
        sales = database.financialDao().allSalesForBackfill(scopedBusinessId),
        payments = database.financialDao().allPaymentsForBackfill(scopedBusinessId),
        allocations = database.financialDao().allAllocationsForBackfill(scopedBusinessId),
        movements = database.financialDao().allMovementsForBackfill(scopedBusinessId),
        outbox = database.syncOperationDao().pending(100),
        customers = database.customerDao().activeWithBalances(scopedBusinessId),
    )

    @Test
    fun resolving_payment_preview_produces_Ready_with_accurate_figures_and_zero_Room_mutations() = runBlocking {
        val customer = createCustomer("cust-1", "Carlos Santana")
        database.customerDao().insert(customer)
        database.financialDao().insertSale(createSale("sale-1", "cust-1", 50_000L))
        database.financialDao().insertMovement(createDebitMovement("mov-1", "cust-1", "sale-1", 50_000L))

        val snapshotBefore = captureSnapshot()
        val balanceBefore = database.financialDao().customerBalanceCents(businessId, "cust-1")
        assertEquals(50_000L, balanceBefore)

        val proposal = RecordPaymentProposal(
            customerName = "Carlos Santana",
            declaredAmountCents = 20_000L,
        )
        val result = resolver.resolve(businessId, proposal)

        assertTrue("Expected Ready but got $result", result is AiPaymentPreviewResult.Ready)
        val ready = result as AiPaymentPreviewResult.Ready
        assertEquals("Carlos Santana", ready.preview.customerName)
        assertEquals(50_000L, ready.preview.currentBalanceCents)
        assertEquals(20_000L, ready.preview.declaredAmountCents)
        assertEquals(30_000L, ready.preview.projectedBalanceCents)

        // Verify zero mutations across all financial tables, outbox, and balances
        val snapshotAfter = captureSnapshot()
        assertEquals("Sales must not be mutated", snapshotBefore.sales, snapshotAfter.sales)
        assertEquals("Payments must not be created", snapshotBefore.payments, snapshotAfter.payments)
        assertEquals("Allocations must not be created", snapshotBefore.allocations, snapshotAfter.allocations)
        assertEquals("Movements must not be created", snapshotBefore.movements, snapshotAfter.movements)
        assertEquals("Outbox must not be modified", snapshotBefore.outbox, snapshotAfter.outbox)
        assertEquals("Customer list and balances must remain identical", snapshotBefore.customers, snapshotAfter.customers)
        assertEquals("Balance in database must remain untouched", balanceBefore, database.financialDao().customerBalanceCents(businessId, "cust-1"))
    }

    @Test
    fun resolving_payment_for_unknown_customer_produces_CustomerNotFound_and_zero_Room_mutations() = runBlocking {
        val emptyBusinessId = "biz-empty-test"
        val emptyBusiness = BusinessEntity(
            id = emptyBusinessId,
            name = "Tienda Vacia",
            ownerUserId = userId,
            currency = "MXN",
            timezone = "America/Mexico_City",
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
        )
        database.businessDao().insert(emptyBusiness)

        val snapshotBefore = captureSnapshot(emptyBusinessId)

        val proposal = RecordPaymentProposal(
            customerName = "Desconocido Lopez",
            declaredAmountCents = 15_000L,
        )
        val result = resolver.resolve(emptyBusinessId, proposal)

        assertEquals(AiPaymentPreviewResult.CustomerNotFound, result)

        val snapshotAfter = captureSnapshot(emptyBusinessId)
        assertEquals(snapshotBefore, snapshotAfter)
    }

    @Test
    fun resolving_overpayment_produces_Blocked_AMOUNT_EXCEEDS_DEBT_and_zero_Room_mutations() = runBlocking {
        val customer = createCustomer("cust-1", "Carlos Santana")
        database.customerDao().insert(customer)
        database.financialDao().insertSale(createSale("sale-1", "cust-1", 50_000L))
        database.financialDao().insertMovement(createDebitMovement("mov-1", "cust-1", "sale-1", 50_000L))

        val snapshotBefore = captureSnapshot()

        val proposal = RecordPaymentProposal(
            customerName = "Carlos Santana",
            declaredAmountCents = 80_000L,
        )
        val result = resolver.resolve(businessId, proposal)

        assertTrue("Expected Blocked but got $result", result is AiPaymentPreviewResult.Blocked)
        val blocked = result as AiPaymentPreviewResult.Blocked
        assertEquals(AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT, blocked.reason)
        assertEquals(50_000L, blocked.preview?.currentBalanceCents)
        assertEquals(80_000L, blocked.preview?.declaredAmountCents)
        assertEquals(-30_000L, blocked.preview?.projectedBalanceCents)

        val snapshotAfter = captureSnapshot()
        assertEquals(snapshotBefore, snapshotAfter)
    }

    @Test
    fun resolving_payment_for_customer_with_no_debt_produces_Blocked_NO_OUTSTANDING_DEBT_and_zero_Room_mutations() = runBlocking {
        val customer = createCustomer("cust-zero", "Maria Sin Deuda")
        database.customerDao().insert(customer)

        val snapshotBefore = captureSnapshot()

        val proposal = RecordPaymentProposal(
            customerName = "Maria Sin Deuda",
            declaredAmountCents = 10_000L,
        )
        val result = resolver.resolve(businessId, proposal)

        assertTrue("Expected Blocked but got $result", result is AiPaymentPreviewResult.Blocked)
        val blocked = result as AiPaymentPreviewResult.Blocked
        assertEquals(AiPaymentBlockReason.NO_OUTSTANDING_DEBT, blocked.reason)
        assertNull("Preview must be null for NO_OUTSTANDING_DEBT", blocked.preview)

        val snapshotAfter = captureSnapshot()
        assertEquals(snapshotBefore, snapshotAfter)
    }

    @Test
    fun resolving_ambiguous_customer_names_produces_CustomerSelectionRequired_and_zero_Room_mutations() = runBlocking {
        val custA = createCustomer("cust-juan-1", "Juan Perez")
        val custB = createCustomer("cust-juan-2", "Juan Perez Norte")
        database.customerDao().insert(custA)
        database.customerDao().insert(custB)

        val snapshotBefore = captureSnapshot()

        val proposal = RecordPaymentProposal(
            customerName = "Juan",
            declaredAmountCents = 10_000L,
        )
        val result = resolver.resolve(businessId, proposal)

        assertTrue("Expected CustomerSelectionRequired but got $result", result is AiPaymentPreviewResult.CustomerSelectionRequired)
        val selection = result as AiPaymentPreviewResult.CustomerSelectionRequired
        assertEquals(2, selection.candidates.size)

        val snapshotAfter = captureSnapshot()
        assertEquals(snapshotBefore, snapshotAfter)
    }
}
