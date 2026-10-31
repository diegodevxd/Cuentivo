package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.AuditLogEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.StoredPaymentBundle
import com.cuentivo.app.data.local.SyncOperationEntity
import com.cuentivo.app.domain.PaymentRepository
import com.cuentivo.app.sync.SyncPayloads
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
 * Instrumented isolation tests for [AiPaymentExecutionService], [PaymentRepository], and [FinancialDao].
 * Proves exact-once financial mutation, idempotency replay, concurrency safety, stale balance rejection,
 * cross-business/archived customer rejection, and post-commit enqueue crash recovery.
 */
@RunWith(AndroidJUnit4::class)
class AiPaymentExecutionIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var paymentRepository: PaymentRepository

    private val businessId = "biz-exec-test"
    private val userId = "user-exec-test"
    private val customerId = "cust-exec-1"

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()

        paymentRepository = PaymentRepository(database.financialDao())

        val business = BusinessEntity(
            id = businessId,
            name = "Abarrotes Concurrente",
            ownerUserId = userId,
            currency = "MXN",
            timezone = "America/Mexico_City",
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
        )
        database.businessDao().insert(business)

        val customer = CustomerEntity(
            id = customerId,
            businessId = businessId,
            name = "Roberto Carlos",
            normalizedName = "roberto carlos",
            phone = "5551112233",
            email = null,
            address = null,
            notes = null,
            creditLimitCents = 100_000L,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )
        database.customerDao().insert(customer)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedSaleAndDebt(amountCents: Long, saleId: String = "sale-1") {
        val sale = SaleEntity(
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
        database.financialDao().insertSale(sale)

        val charge = AccountMovementEntity(
            id = "mov-charge-$saleId",
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
        database.financialDao().insertMovement(charge)
    }

    private fun createService(
        onEnqueue: suspend (StoredPaymentBundle) -> Unit = {},
    ): AiPaymentExecutionService = AiPaymentExecutionService(
        registerPayment = paymentRepository::registerAgainstExpectedBalance,
        loadStoredBundle = paymentRepository::storedBundle,
        enqueueStoredBundle = onEnqueue,
        currentUserId = { userId },
        nowMillis = { 15_000L },
        todayEpochDay = { 20_005L },
    )

    private data class ExecutionSnapshot(
        val payments: List<PaymentEntity>,
        val allocations: List<PaymentAllocationEntity>,
        val movements: List<AccountMovementEntity>,
        val auditLogs: List<AuditLogEntity>,
        val outbox: List<SyncOperationEntity>,
        val balanceCents: Long,
    )

    private suspend fun captureSnapshot(): ExecutionSnapshot = ExecutionSnapshot(
        payments = database.financialDao().allPaymentsForBackfill(businessId),
        allocations = database.financialDao().allAllocationsForBackfill(businessId),
        movements = database.financialDao().allMovementsForBackfill(businessId),
        auditLogs = database.auditDao().forBusiness(businessId),
        outbox = database.syncOperationDao().pending(100),
        balanceCents = database.financialDao().customerBalanceCents(businessId, customerId),
    )

    private fun createSyncOp(id: String, entityType: String, entityId: String, revision: Long, fields: Map<String, Any?>) =
        SyncOperationEntity(
            id = id,
            businessId = businessId,
            entityType = entityType,
            entityId = entityId,
            operation = "UPSERT",
            payload = org.json.JSONObject(fields).toString(),
            createdAtEpochMillis = revision,
            attemptCount = 0,
            lastError = null,
        )

    @Test
    fun confirm_payment_performs_exact_financial_mutation_and_enqueues_outbox() = runBlocking {
        seedSaleAndDebt(50_000L) // $500.00 debt
        val initialBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(50_000L, initialBalance)

        val enqueuedEntities = mutableListOf<String>()
        val service = createService(
            onEnqueue = { bundle ->
                val now = 15_000L
                database.syncOperationDao().enqueue(
                    createSyncOp("sync-pay-${bundle.payment.id}", "PAYMENT", bundle.payment.id, now, SyncPayloads.payment(bundle.payment)),
                )
                enqueuedEntities.add("PAYMENT")
                for (alloc in bundle.allocations) {
                    database.syncOperationDao().enqueue(
                        createSyncOp("sync-alloc-${alloc.id}", "PAYMENT_ALLOCATION", alloc.id, now, SyncPayloads.allocation(alloc)),
                    )
                    enqueuedEntities.add("PAYMENT_ALLOCATION")
                }
                database.syncOperationDao().enqueue(
                    createSyncOp("sync-mov-${bundle.movement.id}", "MOVEMENT", bundle.movement.id, now, SyncPayloads.movement(bundle.movement)),
                )
                enqueuedEntities.add("MOVEMENT")
            },
        )

        val request = AiPaymentConfirmationRequest(
            confirmationKey = "conf-key-1",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L, // $150.00
        )

        val result = service.confirm(request)
        assertTrue("Expected Recorded result but got $result", result is AiPaymentExecutionResult.Recorded)
        val recorded = result as AiPaymentExecutionResult.Recorded

        // 1. One payment created with exact amount
        val payments = database.financialDao().allPaymentsForBackfill(businessId)
        assertEquals(1, payments.size)
        assertEquals(recorded.paymentId, payments.first().id)
        assertEquals(15_000L, payments.first().amountCents)
        assertEquals("CASH", payments.first().paymentMethod)

        // 2. Allocations totaling $150.00
        val allocations = database.financialDao().allocationsForPayment(recorded.paymentId)
        assertEquals(1, allocations.size)
        assertEquals(15_000L, allocations.sumOf { it.amountCents })
        assertEquals("sale-1", allocations.first().saleId)

        // 3. One PAYMENT/CREDIT movement
        val paymentMov = database.financialDao().paymentMovement(businessId, recorded.paymentId)
        assertEquals(15_000L, paymentMov?.amountCents)
        assertEquals("PAYMENT", paymentMov?.type)
        assertEquals("CREDIT", paymentMov?.direction)

        // 4. One PAYMENT_REGISTERED audit log
        val auditLogs = database.auditDao().forBusiness(businessId)
        assertEquals(1, auditLogs.size)
        assertEquals("PAYMENT_REGISTERED", auditLogs.first().action)
        assertEquals(recorded.paymentId, auditLogs.first().entityId)

        // 5. Customer balance updated to $350.00
        val newBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(35_000L, newBalance)

        // 6. Outbox contains PAYMENT, PAYMENT_ALLOCATION, MOVEMENT
        assertEquals(listOf("PAYMENT", "PAYMENT_ALLOCATION", "MOVEMENT"), enqueuedEntities)
        val outbox = database.syncOperationDao().pending(100)
        assertEquals(3, outbox.size)
        assertEquals(setOf("PAYMENT", "PAYMENT_ALLOCATION", "MOVEMENT"), outbox.map { it.entityType }.toSet())
    }

    @Test
    fun duplicate_confirmation_returns_same_payment_id_with_unchanged_counts() = runBlocking {
        seedSaleAndDebt(50_000L)
        val service = createService()

        val request = AiPaymentConfirmationRequest(
            confirmationKey = "conf-replay-key",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )

        val firstResult = service.confirm(request)
        assertTrue(firstResult is AiPaymentExecutionResult.Recorded)
        val firstPaymentId = (firstResult as AiPaymentExecutionResult.Recorded).paymentId

        val snapshotAfterFirst = captureSnapshot()

        // Second confirmation with same key
        val secondResult = service.confirm(request)
        assertTrue(secondResult is AiPaymentExecutionResult.Recorded)
        val secondPaymentId = (secondResult as AiPaymentExecutionResult.Recorded).paymentId

        assertEquals(firstPaymentId, secondPaymentId)
        val snapshotAfterSecond = captureSnapshot()
        assertEquals(snapshotAfterFirst, snapshotAfterSecond)
    }

    @Test
    fun concurrent_confirmations_with_same_key_result_in_exactly_one_mutation() = runBlocking {
        seedSaleAndDebt(50_000L)
        val service = createService()

        val request = AiPaymentConfirmationRequest(
            confirmationKey = "conf-concurrent-key",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )

        val deferred1 = async { service.confirm(request) }
        val deferred2 = async { service.confirm(request) }
        val results = awaitAll(deferred1, deferred2)

        assertTrue(results[0] is AiPaymentExecutionResult.Recorded)
        assertTrue(results[1] is AiPaymentExecutionResult.Recorded)
        val id1 = (results[0] as AiPaymentExecutionResult.Recorded).paymentId
        val id2 = (results[1] as AiPaymentExecutionResult.Recorded).paymentId
        assertEquals(id1, id2)

        // Exactly one payment, one movement, balance reduced by exactly 15_000L
        val payments = database.financialDao().allPaymentsForBackfill(businessId)
        assertEquals(1, payments.size)
        val balance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(35_000L, balance)
    }

    @Test
    fun stale_balance_rejects_and_prevents_mutation() = runBlocking {
        seedSaleAndDebt(50_000L) // $500.00
        val service = createService()

        // Intervening payment reduces balance to $450.00
        val intermediatePayment = PaymentEntity(
            id = "pay-manual-1",
            businessId = businessId,
            customerId = customerId,
            amountCents = 5_000L,
            paymentMethod = "CASH",
            paymentDateEpochDay = 20_000L,
            reference = null,
            notes = null,
            createdByUserId = userId,
            createdAtEpochMillis = 11_000L,
            canceledAtEpochMillis = null,
        )
        val intermediateMovement = AccountMovementEntity(
            id = "mov-manual-1",
            businessId = businessId,
            customerId = customerId,
            saleId = null,
            paymentId = "pay-manual-1",
            type = "PAYMENT",
            amountCents = 5_000L,
            direction = "CREDIT",
            occurredAtEpochMillis = 11_000L,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )
        paymentRepository.register(
            idempotencyKey = "manual-key-1",
            payment = intermediatePayment,
            allocations = listOf(PaymentAllocationEntity("alloc-man-1", "pay-manual-1", "sale-1", 5_000L)),
            movement = intermediateMovement,
        )

        assertEquals(45_000L, database.financialDao().customerBalanceCents(businessId, customerId))
        val snapshotBefore = captureSnapshot()

        // Attempt confirm with stale expected balance 50_000L
        val request = AiPaymentConfirmationRequest(
            confirmationKey = "conf-stale-key",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )

        val result = service.confirm(request)
        assertEquals(AiPaymentExecutionResult.StalePreview, result)

        val snapshotAfter = captureSnapshot()
        assertEquals(snapshotBefore, snapshotAfter)
        assertEquals(45_000L, database.financialDao().customerBalanceCents(businessId, customerId))
    }

    @Test
    fun archived_deleted_or_cross_business_customer_is_rejected_as_stale() = runBlocking {
        seedSaleAndDebt(50_000L)
        val service = createService()

        // 1. Cross-business customer: request references other-biz
        val crossBizRequest = AiPaymentConfirmationRequest(
            confirmationKey = "conf-cross-biz",
            businessId = "biz-other",
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )
        val crossBizResult = service.confirm(crossBizRequest)
        assertEquals(AiPaymentExecutionResult.StalePreview, crossBizResult)

        // 2. Archived customer
        database.customerDao().archive(customerId, 12_000L)
        val archivedRequest = AiPaymentConfirmationRequest(
            confirmationKey = "conf-archived",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )
        val archivedResult = service.confirm(archivedRequest)
        assertEquals(AiPaymentExecutionResult.StalePreview, archivedResult)

        // Verify zero AI mutation
        val payments = database.financialDao().allPaymentsForBackfill(businessId)
        assertEquals(0, payments.size)
    }

    @Test
    fun enqueue_failure_after_commit_recovers_on_retry_without_duplicate_rows() = runBlocking {
        seedSaleAndDebt(50_000L)

        var enqueueAttempt = 0
        val enqueuedBundles = mutableListOf<StoredPaymentBundle>()

        val service = createService(
            onEnqueue = { bundle ->
                enqueueAttempt++
                if (enqueueAttempt == 1) {
                    throw RuntimeException("Network/disk failed during outbox enqueue")
                }
                enqueuedBundles.add(bundle)
                database.syncOperationDao().enqueue(
                    createSyncOp("sync-retry-${bundle.payment.id}", "PAYMENT", bundle.payment.id, 15_000L, SyncPayloads.payment(bundle.payment)),
                )
            },
        )

        val request = AiPaymentConfirmationRequest(
            confirmationKey = "conf-enqueue-fail-key",
            businessId = businessId,
            customerId = customerId,
            expectedBalanceCents = 50_000L,
            amountCents = 15_000L,
        )

        // First attempt: Room transaction commits, enqueue crashes -> Unavailable
        val firstResult = service.confirm(request)
        assertEquals(AiPaymentExecutionResult.Unavailable, firstResult)

        // Verify Room transaction was committed
        val paymentsAfterFirst = database.financialDao().allPaymentsForBackfill(businessId)
        assertEquals(1, paymentsAfterFirst.size)
        val committedPaymentId = paymentsAfterFirst.first().id

        // Retry confirmation with same request: recognizes already recorded, reloads bundle, enqueues successfully
        val retryResult = service.confirm(request)
        assertTrue(retryResult is AiPaymentExecutionResult.Recorded)
        assertEquals(committedPaymentId, (retryResult as AiPaymentExecutionResult.Recorded).paymentId)

        // Room rows remain single
        assertEquals(1, database.financialDao().allPaymentsForBackfill(businessId).size)
        assertEquals(1, database.financialDao().allocationsForPayment(committedPaymentId).size)
        assertEquals(35_000L, database.financialDao().customerBalanceCents(businessId, customerId))

        // Outbox successfully populated on retry
        assertEquals(1, enqueuedBundles.size)
        assertEquals(committedPaymentId, enqueuedBundles.first().payment.id)
        val outbox = database.syncOperationDao().pending(100)
        assertEquals(1, outbox.size)
        assertEquals(committedPaymentId, outbox.first().entityId)
    }
}
