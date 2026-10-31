package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.AuditLogEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.OperationEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.StoredSaleBundle
import com.cuentivo.app.data.local.SyncOperationEntity
import com.cuentivo.app.domain.AuditAction
import com.cuentivo.app.domain.CustomerRepository
import com.cuentivo.app.domain.ProductRepository
import com.cuentivo.app.domain.SaleRepository
import com.cuentivo.app.sync.SyncOperationId
import com.cuentivo.app.sync.SyncPayloads
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented isolation tests for [AiSaleExecutionService], [SaleRepository], and [FinancialDao].
 * Proves exact-once financial mutation, atomic sale + initial payment, idempotency replay without
 * deriving payment from replay parameters, wrong-business rejections, full rollback on payment failure,
 * and concurrency safety.
 */
@RunWith(AndroidJUnit4::class)
class AiSaleExecutionIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var saleRepository: SaleRepository

    private val businessId = "biz-sale-test"
    private val userId = "user-sale-test"
    private val customerId = "cust-sale-1"
    private val productId = "prod-sale-1"

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()

        saleRepository = SaleRepository(database.financialDao())

        val business = BusinessEntity(
            id = businessId,
            name = "Abarrotes Venta AI",
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
            name = "María Gómez",
            normalizedName = "maria gomez",
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

        val product = ProductEntity(
            id = productId,
            businessId = businessId,
            name = "Jabón Corporal",
            description = null,
            sku = "JAB-01",
            category = "Higiene",
            salePriceCents = 5000L,
            costPriceCents = 3000L,
            stock = 20L,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )
        database.productDao().insert(product)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createService(
        onEnqueue: (suspend (StoredSaleBundle) -> Unit)? = null,
    ) = AiSaleExecutionService(
        registerSale = { key, sale, items, charge, custFp, prodFps, payment, paymentMovement, allocations, expectedInitialPaymentId, newCust, expCust, newProds, expProdMap ->
            saleRepository.registerCompositeAgainstPreview(
                idempotencyKey = key,
                sale = sale,
                items = items,
                charge = charge,
                expectedCustomerFingerprint = custFp,
                expectedProductFingerprints = prodFps,
                payment = payment,
                paymentMovement = paymentMovement,
                paymentAllocations = allocations,
                expectedInitialPaymentId = expectedInitialPaymentId,
                newCustomer = newCust,
                explicitCustomerCreateNew = expCust,
                newProducts = newProds,
                explicitProductCreateNewByProductId = expProdMap,
            )
        },
        loadStoredBundle = saleRepository::storedBundle,
        enqueueStoredBundle = onEnqueue,
        currentUserId = { userId },
        nowMillis = { 20_000L },
        todayEpochDay = { 20_500L },
    )

    @Test
    fun exactExecutionWithoutInitialPaymentRecordsSaleItemsChargeAndAudit() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-1",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 3,
                    unitPriceCents = 5000L,
                    lineTotalCents = 15000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 15000L,
            initialPaymentCents = 0L,
            promisedPaymentEpochDay = 20600L,
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded
        assertNotNull(recorded.saleId)
        assertNull(recorded.paymentId)

        val storedSale = database.financialDao().saleForId(recorded.saleId)
        assertNotNull(storedSale)
        assertEquals(15000L, storedSale?.totalCents)
        assertEquals(20600L, storedSale?.dueDateEpochDay)

        val items = database.financialDao().itemsForSale(recorded.saleId)
        assertEquals(1, items.size)
        assertEquals(3L, items[0].quantity)
        assertEquals(5000L, items[0].unitPriceCents)
        assertEquals(15000L, items[0].subtotalCents)

        val charge = database.financialDao().chargeMovementForSale(recorded.saleId)
        assertNotNull(charge)
        assertEquals(15000L, charge?.amountCents)
        assertEquals("DEBIT", charge?.direction)

        val bundle = saleRepository.storedBundle(businessId, recorded.saleId)
        assertNotNull(bundle)
        assertNull(bundle?.paymentBundle)

        val customerBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(15000L, customerBalance)
    }

    @Test
    fun exactExecutionWithInitialPaymentRecordsSalePaymentAllocationAndCredit() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-2",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 2,
                    unitPriceCents = 5000L,
                    lineTotalCents = 10000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 10000L,
            initialPaymentCents = 4000L,
            promisedPaymentEpochDay = null,
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded
        assertNotNull(recorded.saleId)
        assertNotNull(recorded.paymentId)

        val storedSale = database.financialDao().saleForId(recorded.saleId)
        assertNotNull(storedSale)

        val storedPayment = database.financialDao().paymentForId(recorded.paymentId!!)
        assertNotNull(storedPayment)
        assertEquals(4000L, storedPayment?.amountCents)

        val allocations = database.financialDao().allAllocationsForSale(recorded.saleId)
        assertEquals(1, allocations.size)
        assertEquals(recorded.paymentId, allocations[0].paymentId)
        assertEquals(recorded.saleId, allocations[0].saleId)
        assertEquals(4000L, allocations[0].amountCents)

        val bundle = saleRepository.storedBundle(businessId, recorded.saleId)
        assertNotNull(bundle)
        assertNotNull(bundle?.paymentBundle)
        assertEquals(4000L, bundle?.paymentBundle?.payment?.amountCents)

        val customerBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(6000L, customerBalance)
    }

    @Test
    fun idempotencyReplayNeverDerivesPaymentIdFromReplayParameters() = runBlocking {
        val service = createService()

        // 1. Initial execution has zero initial payment
        val zeroPaymentRequest = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-replay-zero",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val firstRecorded = service.confirm(zeroPaymentRequest) as AiSaleExecutionResult.Recorded
        assertNull(firstRecorded.paymentId)

        // Replay with positive initial payment parameter must NOT create payment and must return paymentId = null
        val positiveReplayRequest = zeroPaymentRequest.copy(initialPaymentCents = 2000L)
        val replayResult = service.confirm(positiveReplayRequest) as AiSaleExecutionResult.Recorded
        assertEquals(firstRecorded.saleId, replayResult.saleId)
        assertNull("Replay must return stored paymentId (null), not request parameter", replayResult.paymentId)

        val allocations = database.financialDao().allAllocationsForSale(firstRecorded.saleId)
        assertEquals(0, allocations.size)

        // 2. Inverse case: Initial execution has positive initial payment
        val withPaymentRequest = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-replay-paid",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 3000L,
        )

        val paidRecorded = service.confirm(withPaymentRequest) as AiSaleExecutionResult.Recorded
        assertNotNull(paidRecorded.paymentId)

        // Replay with zero initial payment parameter must return the actual stored paymentId
        val zeroReplayRequest = withPaymentRequest.copy(initialPaymentCents = 0L)
        val paidReplayResult = service.confirm(zeroReplayRequest) as AiSaleExecutionResult.Recorded
        assertEquals(paidRecorded.saleId, paidReplayResult.saleId)
        assertEquals(paidRecorded.paymentId, paidReplayResult.paymentId)
    }

    @Test
    fun replayWithZeroInitialPaymentAfterLaterManualPaymentStillReturnsNullPaymentId() = runBlocking {
        val service = createService()

        // 1. Initial AI sale with zero initial payment
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-manual-later",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val recorded = service.confirm(request) as AiSaleExecutionResult.Recorded
        assertNull(recorded.paymentId)

        // 2. Later, a manual payment is inserted and allocated to that sale
        val manualPaymentId = "manual-pay-1"
        val manualPayment = PaymentEntity(
            id = manualPaymentId,
            businessId = businessId,
            customerId = customerId,
            amountCents = 2500L,
            paymentMethod = "CASH",
            paymentDateEpochDay = 20501L,
            reference = null,
            notes = null,
            createdByUserId = userId,
            createdAtEpochMillis = 25000L,
            canceledAtEpochMillis = null,
        )
        val manualAllocations = listOf(
            PaymentAllocationEntity(
                id = "manual-alloc-1",
                paymentId = manualPaymentId,
                saleId = recorded.saleId,
                amountCents = 2500L,
            ),
        )
        val manualMovement = AccountMovementEntity(
            id = "manual-mov-1",
            businessId = businessId,
            customerId = customerId,
            saleId = null,
            paymentId = manualPaymentId,
            type = "PAYMENT",
            amountCents = 2500L,
            direction = "CREDIT",
            occurredAtEpochMillis = 25000L,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )
        database.financialDao().insertPaymentWithMovements(manualPayment, manualAllocations, manualMovement)

        // Verify the manual allocation now exists on the sale
        val allAllocations = database.financialDao().allAllocationsForSale(recorded.saleId)
        assertEquals(1, allAllocations.size)
        assertEquals(manualPaymentId, allAllocations[0].paymentId)

        // 3. Replay the AI confirmation request
        val replayResult = service.confirm(request) as AiSaleExecutionResult.Recorded
        assertEquals(recorded.saleId, replayResult.saleId)
        assertNull(
            "Replay must return null paymentId because the expected initial payment does not exist for this key",
            replayResult.paymentId,
        )
    }

    @Test
    fun wrongBusinessCustomerRejectionWritesZeroFinancialRows() = runBlocking {
        val otherBusinessCustomer = CustomerEntity(
            id = "cust-other-biz",
            businessId = "other-biz-id",
            name = "Cliente Otro Negocio",
            normalizedName = "cliente otro negocio",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )
        database.customerDao().insert(otherBusinessCustomer)

        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-wrong-cust",
            businessId = businessId,
            customerId = otherBusinessCustomer.id,
            sourceCustomerFingerprint = "${otherBusinessCustomer.id}:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        assertEquals(0, database.financialDao().allSalesForBackfill(businessId).size)
    }

    @Test
    fun wrongBusinessProductRejectionWritesZeroFinancialRows() = runBlocking {
        val otherBusinessProduct = ProductEntity(
            id = "prod-other-biz",
            businessId = "other-biz-id",
            name = "Producto Otro Negocio",
            description = null,
            sku = null,
            category = null,
            salePriceCents = 5000L,
            costPriceCents = null,
            stock = null,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )
        database.productDao().insert(otherBusinessProduct)

        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-wrong-prod",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = otherBusinessProduct.id,
                    productNameSnapshot = "Producto Otro Negocio",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "${otherBusinessProduct.id}:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        assertEquals(0, database.financialDao().allSalesForBackfill(businessId).size)
    }

    @Test
    fun fullRollbackWhenPaymentLegFailsAfterSaleLegStarts() = runBlocking {
        // We directly invoke registerSaleAgainstPreviewIdempotently with an invalid payment bundle
        // (payment is non-null but allocations sum does not match payment amount)
        val now = 20_000L
        val saleId = "sale-rollback-1"
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            customerId = customerId,
            sellerUserId = userId,
            saleNumber = "V-$saleId",
            saleDateEpochDay = 20500L,
            dueDateEpochDay = null,
            subtotalCents = 5000L,
            discountCents = 0L,
            totalCents = 5000L,
            isCanceled = false,
            notes = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val items = listOf(
            SaleItemEntity(
                id = "item-rollback-1",
                saleId = saleId,
                productId = productId,
                productNameSnapshot = "Jabón Corporal",
                quantity = 1L,
                unitPriceCents = 5000L,
                discountCents = 0L,
                subtotalCents = 5000L,
            ),
        )
        val charge = AccountMovementEntity(
            id = "charge-rollback-1",
            businessId = businessId,
            customerId = customerId,
            saleId = saleId,
            paymentId = null,
            type = "CHARGE",
            amountCents = 5000L,
            direction = "DEBIT",
            occurredAtEpochMillis = now,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )
        val invalidPayment = PaymentEntity(
            id = "pay-rollback-1",
            businessId = businessId,
            customerId = customerId,
            amountCents = 2000L,
            paymentMethod = "CASH",
            paymentDateEpochDay = 20500L,
            reference = null,
            notes = null,
            createdByUserId = userId,
            createdAtEpochMillis = now,
            canceledAtEpochMillis = null,
        )
        val paymentMovement = AccountMovementEntity(
            id = "pm-rollback-1",
            businessId = businessId,
            customerId = customerId,
            saleId = null,
            paymentId = invalidPayment.id,
            type = "PAYMENT",
            amountCents = 2000L,
            direction = "CREDIT",
            occurredAtEpochMillis = now,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )
        // Invalid allocations: sum 1000 != payment amount 2000
        val invalidAllocations = listOf(
            PaymentAllocationEntity(
                id = "alloc-rollback-1",
                paymentId = invalidPayment.id,
                saleId = saleId,
                amountCents = 1000L,
            ),
        )

        try {
            database.financialDao().registerSaleAgainstPreviewIdempotently(
                operation = OperationEntity("op-rollback-test", "SALE", saleId, now),
                sale = sale,
                items = items,
                charge = charge,
                expectedCustomerFingerprint = "$customerId:10000",
                expectedProductFingerprints = mapOf(productId to "$productId:10000:5000"),
                payment = invalidPayment,
                paymentMovement = paymentMovement,
                paymentAllocations = invalidAllocations,
                expectedInitialPaymentId = invalidPayment.id,
            )
            fail("Expected IllegalArgumentException on invalid payment allocation")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // Verify entire Room transaction rolled back: 0 rows in all financial tables
        assertNull("Sale must not exist", database.financialDao().saleForId(saleId))
        assertEquals("Sale items must be empty", 0, database.financialDao().itemsForSale(saleId).size)
        assertNull("Charge must not exist", database.financialDao().chargeMovementForSale(saleId))
        assertNull("Payment must not exist", database.financialDao().paymentForId(invalidPayment.id))
        assertEquals("Allocations must be empty", 0, database.financialDao().allAllocationsForSale(saleId).size)
        assertNull("Operation must not be reserved", database.financialDao().operationResultId("op-rollback-test"))
        assertEquals("Audit log must be empty", 0, database.auditDao().forBusiness(businessId).size)
    }

    @Test
    fun staleCustomerFingerprintRejectsAndWritesNothing() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-stale-cust",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:99999",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        val allSales = database.financialDao().allSalesForBackfill(businessId)
        assertEquals(0, allSales.size)
    }

    @Test
    fun staleProductFingerprintRejectsAndWritesNothing() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-stale-prod",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:99999:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        val allSales = database.financialDao().allSalesForBackfill(businessId)
        assertEquals(0, allSales.size)
    }

    @Test
    fun concurrentExecutionProducesSingleSale() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-concurrent",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 1000L,
        )

        val deferreds = (1..5).map {
            async { service.confirm(request) }
        }
        val results = deferreds.awaitAll()

        results.forEach {
            assertTrue("all results must be Recorded", it is AiSaleExecutionResult.Recorded)
            val recorded = it as AiSaleExecutionResult.Recorded
            assertEquals((results[0] as AiSaleExecutionResult.Recorded).saleId, recorded.saleId)
        }

        val allSales = database.financialDao().allSalesForBackfill(businessId)
        assertEquals(1, allSales.size)
    }

    @Test
    fun previewAloneWritesNothing() = runBlocking {
        val customerRepo = CustomerRepository(database.customerDao())
        val productRepo = ProductRepository(database.productDao())
        val resolver = AiSalePreviewResolver(
            searchCustomers = customerRepo::search,
            searchProducts = productRepo::search,
        )

        val proposal = CreateSaleProposal(
            customerName = "María Gómez",
            lines = listOf(
                AiProposalSaleLine(productName = "Jabón Corporal", quantity = 2, unitPriceCents = 5000L),
            ),
            declaredInitialPaymentCents = 1000L,
            promisedPaymentCandidate = null,
        )

        val previewResult = resolver.resolve(businessId, proposal)
        assertTrue("preview must be Ready", previewResult is AiSalePreviewResult.Ready)

        // Verify zero database mutations across all financial tables
        assertEquals(0, database.financialDao().allSalesForBackfill(businessId).size)
        assertEquals(0, database.financialDao().allPaymentsForBackfill(businessId).size)
        assertEquals(0, database.financialDao().customerMovements(businessId, customerId).size)
        assertEquals(0, database.financialDao().itemsForSale("any").size)
        assertEquals(0, database.auditDao().forBusiness(businessId).size)
    }

    @Test
    fun recordedSaleEnqueuesExactOutboxAndReplayDoesNotDuplicateOutbox() = runBlocking {
        val enqueuedOps = mutableListOf<SyncOperationEntity>()
        val service = createService(
            onEnqueue = { bundle ->
                val sale = bundle.sale
                val now = sale.createdAtEpochMillis
                val saleOp = SyncOperationEntity(
                    id = SyncOperationId.create(sale.businessId, "SALE", sale.id, now),
                    businessId = sale.businessId,
                    entityType = "SALE",
                    entityId = sale.id,
                    operation = "UPSERT",
                    payload = org.json.JSONObject(SyncPayloads.sale(sale)).toString(),
                    createdAtEpochMillis = now,
                    attemptCount = 0,
                    lastError = null,
                )
                database.syncOperationDao().enqueue(saleOp)
                enqueuedOps.add(saleOp)

                bundle.items.forEach { item ->
                    val itemOp = SyncOperationEntity(
                        id = SyncOperationId.create(sale.businessId, "SALE_ITEM", item.id, now),
                        businessId = sale.businessId,
                        entityType = "SALE_ITEM",
                        entityId = item.id,
                        operation = "UPSERT",
                        payload = org.json.JSONObject(SyncPayloads.saleItem(item)).toString(),
                        createdAtEpochMillis = now,
                        attemptCount = 0,
                        lastError = null,
                    )
                    database.syncOperationDao().enqueue(itemOp)
                    enqueuedOps.add(itemOp)
                }

                val chargeOp = SyncOperationEntity(
                    id = SyncOperationId.create(sale.businessId, "MOVEMENT", bundle.chargeMovement.id, now),
                    businessId = sale.businessId,
                    entityType = "MOVEMENT",
                    entityId = bundle.chargeMovement.id,
                    operation = "UPSERT",
                    payload = org.json.JSONObject(SyncPayloads.movement(bundle.chargeMovement)).toString(),
                    createdAtEpochMillis = now,
                    attemptCount = 0,
                    lastError = null,
                )
                database.syncOperationDao().enqueue(chargeOp)
                enqueuedOps.add(chargeOp)

                bundle.paymentBundle?.let { pBundle ->
                    val payment = pBundle.payment
                    val payOp = SyncOperationEntity(
                        id = SyncOperationId.create(payment.businessId, "PAYMENT", payment.id, now),
                        businessId = payment.businessId,
                        entityType = "PAYMENT",
                        entityId = payment.id,
                        operation = "UPSERT",
                        payload = org.json.JSONObject(SyncPayloads.payment(payment)).toString(),
                        createdAtEpochMillis = now,
                        attemptCount = 0,
                        lastError = null,
                    )
                    database.syncOperationDao().enqueue(payOp)
                    enqueuedOps.add(payOp)

                    pBundle.allocations.forEach { alloc ->
                        val allocOp = SyncOperationEntity(
                            id = SyncOperationId.create(payment.businessId, "PAYMENT_ALLOCATION", alloc.id, now),
                            businessId = payment.businessId,
                            entityType = "PAYMENT_ALLOCATION",
                            entityId = alloc.id,
                            operation = "UPSERT",
                            payload = org.json.JSONObject(SyncPayloads.allocation(alloc)).toString(),
                            createdAtEpochMillis = now,
                            attemptCount = 0,
                            lastError = null,
                        )
                        database.syncOperationDao().enqueue(allocOp)
                        enqueuedOps.add(allocOp)
                    }

                    val movOp = SyncOperationEntity(
                        id = SyncOperationId.create(payment.businessId, "MOVEMENT", pBundle.movement.id, now),
                        businessId = payment.businessId,
                        entityType = "MOVEMENT",
                        entityId = pBundle.movement.id,
                        operation = "UPSERT",
                        payload = org.json.JSONObject(SyncPayloads.movement(pBundle.movement)).toString(),
                        createdAtEpochMillis = now,
                        attemptCount = 0,
                        lastError = null,
                    )
                    database.syncOperationDao().enqueue(movOp)
                    enqueuedOps.add(movOp)
                }
            },
        )

        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-outbox-1",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 2,
                    unitPriceCents = 5000L,
                    lineTotalCents = 10000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 10000L,
            initialPaymentCents = 3000L,
        )

        val result1 = service.confirm(request)
        assertTrue(result1 is AiSaleExecutionResult.Recorded)

        // 1 SALE, 1 SALE_ITEM, 1 CHARGE, 1 PAYMENT, 1 PAYMENT_ALLOCATION, 1 PAYMENT MOVEMENT = 6 ops
        val pending1 = database.syncOperationDao().pending(100)
        assertEquals(6, pending1.size)

        // Replaying same key should re-enqueue exact same operation IDs, leaving outbox count at 6 without duplicates
        val result2 = service.confirm(request)
        assertTrue(result2 is AiSaleExecutionResult.Recorded)
        assertEquals((result1 as AiSaleExecutionResult.Recorded).saleId, (result2 as AiSaleExecutionResult.Recorded).saleId)
        assertEquals(result1.paymentId, result2.paymentId)

        val pending2 = database.syncOperationDao().pending(100)
        assertEquals(6, pending2.size)
    }

    @Test
    fun enqueueFailureAfterCommitRecoversOnRetryWithoutDuplicateRows() = runBlocking {
        var enqueueAttempts = 0
        val service = createService(
            onEnqueue = { _ ->
                enqueueAttempts++
                if (enqueueAttempts == 1) {
                    throw RuntimeException("Simulated crash during outbox enqueue")
                }
            },
        )

        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-crash-retry",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        // First attempt commits Room transaction but crashes on enqueue -> Unavailable
        val firstResult = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Unavailable, firstResult)

        // Room has exactly 1 committed sale
        val salesAfterFirst = database.financialDao().allSalesForBackfill(businessId)
        assertEquals(1, salesAfterFirst.size)
        val committedSaleId = salesAfterFirst.first().id

        // Retry with same confirmation key succeeds and recognizes already-recorded sale
        val retryResult = service.confirm(request)
        assertTrue(retryResult is AiSaleExecutionResult.Recorded)
        assertEquals(committedSaleId, (retryResult as AiSaleExecutionResult.Recorded).saleId)

        // Database still has exactly 1 sale, 1 item, 1 charge movement
        assertEquals(1, database.financialDao().allSalesForBackfill(businessId).size)
        assertEquals(1, database.financialDao().itemsForSale(committedSaleId).size)
        assertNotNull(database.financialDao().chargeMovementForSale(committedSaleId))
    }

    private data class TableCounts(
        val customers: Int,
        val products: Int,
        val sales: Int,
        val saleItems: Int,
        val movements: Int,
        val payments: Int,
        val allocations: Int,
        val auditLogs: Int,
    )

    private suspend fun countAllTables(): TableCounts = TableCounts(
        customers = database.customerDao().allForBackfill(businessId).size,
        products = database.productDao().allForBackfill(businessId).size,
        sales = database.financialDao().allSalesForBackfill(businessId).size,
        saleItems = database.financialDao().allSaleItemsForBackfill(businessId).size,
        movements = database.financialDao().allMovementsForBackfill(businessId).size,
        payments = database.financialDao().allPaymentsForBackfill(businessId).size,
        allocations = database.financialDao().allAllocationsForBackfill(businessId).size,
        auditLogs = database.auditDao().forBusiness(businessId).size,
    )

    @Test
    fun compositeExecutionCreatesNewCustomerNewProductSaleAndPaymentInOneTransaction() = runBlocking {
        val service = createService()
        val newCustId = "cust-new-atomic"
        val newProdId = "prod-new-atomic"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-1",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Aceite de Oliva Extra",
                    quantity = 2,
                    unitPriceCents = 15000L,
                    lineTotalCents = 30000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 30000L,
            initialPaymentCents = 10000L,
            promisedPaymentEpochDay = 20600L,
            newCustomer = AiNewCustomerSpec(
                customerId = newCustId,
                displayName = "Don Alberto",
                normalizedName = "don alberto",
                explicitCreateNew = false,
            ),
            newProducts = listOf(
                AiNewProductSpec(
                    productId = newProdId,
                    name = "Aceite de Oliva Extra",
                    salePriceCents = 15000L,
                    explicitCreateNew = false,
                ),
            ),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded
        assertNotNull(recorded.saleId)
        assertNotNull(recorded.paymentId)

        // Customer was created in Room
        val createdCust = database.customerDao().byId(newCustId)
        assertNotNull(createdCust)
        assertEquals("Don Alberto", createdCust?.name)
        assertEquals("don alberto", createdCust?.normalizedName)
        assertEquals(true, createdCust?.isActive)

        // Product was created in Room
        val createdProd = database.productDao().byId(newProdId)
        assertNotNull(createdProd)
        assertEquals("Aceite de Oliva Extra", createdProd?.name)
        assertEquals(15000L, createdProd?.salePriceCents)
        assertEquals(true, createdProd?.isActive)

        // Sale and payment records exist
        val sale = database.financialDao().saleForId(recorded.saleId)
        assertNotNull(sale)
        assertEquals(30000L, sale?.totalCents)
        val payment = database.financialDao().paymentForId(recorded.paymentId!!)
        assertNotNull(payment)
        assertEquals(10000L, payment?.amountCents)
    }

    @Test
    fun compositeExecutionMixedExistingAndNewProductsSucceeds() = runBlocking {
        val service = createService()
        val newProdId = "prod-new-mixed"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-mixed",
            businessId = businessId,
            customerId = customerId, // existing customer
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId, // existing product
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
                AiSaleConfirmationLine(
                    productId = newProdId, // new product
                    productNameSnapshot = "Shampoo Anticaspa",
                    quantity = 2,
                    unitPriceCents = 8000L,
                    lineTotalCents = 16000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 21000L,
            initialPaymentCents = 0L,
            newCustomer = null,
            newProducts = listOf(
                AiNewProductSpec(
                    productId = newProdId,
                    name = "Shampoo Anticaspa",
                    salePriceCents = 8000L,
                    explicitCreateNew = false,
                ),
            ),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded

        val items = database.financialDao().itemsForSale(recorded.saleId)
        assertEquals(2, items.size)

        val newProductInDb = database.productDao().byId(newProdId)
        assertNotNull(newProductInDb)
        assertEquals(8000L, newProductInDb?.salePriceCents)
    }

    @Test
    fun spokenPriceIsPersistedAsSalePriceCentsAndNoOptionalFieldsInvented() = runBlocking {
        val service = createService()
        val newCustId = "cust-no-pii"
        val newProdId = "prod-no-pii"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-pii",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Miel Pura",
                    quantity = 1,
                    unitPriceCents = 7500L,
                    lineTotalCents = 7500L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 7500L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(
                customerId = newCustId,
                displayName = "Pedro Picapiedra",
                normalizedName = "pedro picapiedra",
                explicitCreateNew = false,
            ),
            newProducts = listOf(
                AiNewProductSpec(
                    productId = newProdId,
                    name = "Miel Pura",
                    salePriceCents = 7500L,
                    explicitCreateNew = false,
                ),
            ),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)

        val cust = database.customerDao().byId(newCustId)!!
        assertNull("phone must be null", cust.phone)
        assertNull("email must be null", cust.email)
        assertNull("address must be null", cust.address)
        assertNull("notes must be null", cust.notes)
        assertNull("creditLimitCents must be null", cust.creditLimitCents)
        assertNull("deletedAtEpochMillis must be null", cust.deletedAtEpochMillis)

        val prod = database.productDao().byId(newProdId)!!
        assertEquals(7500L, prod.salePriceCents)
        assertNull("description must be null", prod.description)
        assertNull("sku must be null", prod.sku)
        assertNull("category must be null", prod.category)
        assertNull("costPriceCents must be null", prod.costPriceCents)
        assertNull("stock must be null", prod.stock)
        assertNull("imageUri must be null", prod.imageUri)
        assertNull("deletedAtEpochMillis must be null", prod.deletedAtEpochMillis)
    }

    @Test
    fun doubleConfirmationWithSameKeyReplaysAtomicallyWithoutDuplicateRows() = runBlocking {
        val service = createService()
        val newCustId = "cust-double-tap"
        val newProdId = "prod-double-tap"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-replay",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Galletas Avena",
                    quantity = 1,
                    unitPriceCents = 3500L,
                    lineTotalCents = 3500L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 3500L,
            initialPaymentCents = 1000L,
            newCustomer = AiNewCustomerSpec(newCustId, "Laura Gomez", "laura gomez", false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Galletas Avena", 3500L, false)),
        )

        val firstResult = service.confirm(request) as AiSaleExecutionResult.Recorded
        val countsAfterFirst = countAllTables()

        val secondResult = service.confirm(request) as AiSaleExecutionResult.Recorded
        val countsAfterSecond = countAllTables()

        assertEquals(firstResult.saleId, secondResult.saleId)
        assertEquals(firstResult.paymentId, secondResult.paymentId)
        assertEquals("No rows should be duplicated on replay", countsAfterFirst, countsAfterSecond)
    }

    @Test
    fun normalizedNameRaceWhenExplicitCreateNewIsFalseReturnsStaleDataAndZeroWrites() = runBlocking {
        val service = createService()
        val newCustId = "cust-race-name"
        val newProdId = "prod-race-name"

        // Concurrently insert a customer with same normalized name
        val competingCustomer = CustomerEntity(
            id = "cust-competing-1",
            businessId = businessId,
            name = "Ernesto Zedillo",
            normalizedName = "ernesto zedillo",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 15_000L,
            updatedAtEpochMillis = 15_000L,
            deletedAtEpochMillis = null,
        )
        database.customerDao().insert(competingCustomer)

        val countsBefore = countAllTables()

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-race-fail",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Té Verde",
                    quantity = 1,
                    unitPriceCents = 4000L,
                    lineTotalCents = 4000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 4000L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(newCustId, "Ernesto Zedillo", "ernesto zedillo", explicitCreateNew = false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Té Verde", 4000L, false)),
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        val countsAfter = countAllTables()
        assertEquals("Must write zero rows on normalized name race", countsBefore, countsAfter)
    }

    @Test
    fun normalizedNameDuplicateWhenExplicitCreateNewIsTrueSucceeds() = runBlocking {
        val service = createService()
        val newCustId = "cust-explicit-dup"
        val newProdId = "prod-explicit-dup"

        // Existing customer with same normalized name
        val existingCustomer = CustomerEntity(
            id = "cust-existing-same-name",
            businessId = businessId,
            name = "Ernesto Zedillo",
            normalizedName = "ernesto zedillo",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 15_000L,
            updatedAtEpochMillis = 15_000L,
            deletedAtEpochMillis = null,
        )
        database.customerDao().insert(existingCustomer)

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-explicit-dup-ok",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Té Verde",
                    quantity = 1,
                    unitPriceCents = 4000L,
                    lineTotalCents = 4000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 4000L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(newCustId, "Ernesto Zedillo", "ernesto zedillo", explicitCreateNew = true),
            newProducts = listOf(AiNewProductSpec(newProdId, "Té Verde", 4000L, false)),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)

        val newCustomer = database.customerDao().byId(newCustId)
        assertNotNull(newCustomer)
        assertEquals("Ernesto Zedillo", newCustomer?.name)
    }

    @Test
    fun idCollisionAgainstExistingEntityReturnsStaleDataAndZeroWrites() = runBlocking {
        val service = createService()
        val countsBefore = countAllTables()

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-id-col",
            businessId = businessId,
            customerId = customerId, // Already exists in database!
            sourceCustomerFingerprint = "", // but request claims it is new
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = "prod-new-id-col",
                    productNameSnapshot = "Café Molido",
                    quantity = 1,
                    unitPriceCents = 6000L,
                    lineTotalCents = 6000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 6000L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(customerId, "María Gómez", "maria gomez", false),
            newProducts = listOf(AiNewProductSpec("prod-new-id-col", "Café Molido", 6000L, false)),
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        val countsAfter = countAllTables()
        assertEquals("Must write zero rows on ID collision", countsBefore, countsAfter)
    }

    @Test
    fun rollbackOnPaymentFailureLeavesZeroCatalogZeroSaleZeroMovementsZeroAudit() = runBlocking {
        val newCustId = "cust-rollback-all"
        val newProdId = "prod-rollback-all"
        val countsBefore = countAllTables()

        // Service with invalid payment allocation sum causing require() to fail in Room transaction
        val service = AiSaleExecutionService(
            registerSale = { key, sale, items, charge, custFp, prodFps, payment, paymentMovement, _, expectedInitialPaymentId, newCust, expCust, newProds, expProdMap ->
                val invalidAllocations = listOf(
                    PaymentAllocationEntity(
                        id = "alloc-invalid",
                        paymentId = payment!!.id,
                        saleId = sale.id,
                        amountCents = 999999L, // Mismatches payment.amountCents -> throws IllegalArgumentException
                    ),
                )
                saleRepository.registerCompositeAgainstPreview(
                    idempotencyKey = key,
                    sale = sale,
                    items = items,
                    charge = charge,
                    expectedCustomerFingerprint = custFp,
                    expectedProductFingerprints = prodFps,
                    payment = payment,
                    paymentMovement = paymentMovement,
                    paymentAllocations = invalidAllocations,
                    expectedInitialPaymentId = expectedInitialPaymentId,
                    newCustomer = newCust,
                    explicitCustomerCreateNew = expCust,
                    newProducts = newProds,
                    explicitProductCreateNewByProductId = expProdMap,
                )
            },
            currentUserId = { userId },
            nowMillis = { 20_000L },
            todayEpochDay = { 20_500L },
        )

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-rollback",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Vino Tinto",
                    quantity = 1,
                    unitPriceCents = 25000L,
                    lineTotalCents = 25000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 25000L,
            initialPaymentCents = 5000L,
            newCustomer = AiNewCustomerSpec(newCustId, "Gonzalo", "gonzalo", false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Vino Tinto", 25000L, false)),
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Unavailable, result)

        val countsAfter = countAllTables()
        assertEquals("Full rollback must leave zero writes across all tables", countsBefore, countsAfter)
        assertNull(database.customerDao().byId(newCustId))
        assertNull(database.productDao().byId(newProdId))
    }

    @Test
    fun staleExistingProductFingerprintReturnsStaleDataAndZeroWrites() = runBlocking {
        val service = createService()
        val countsBefore = countAllTables()

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-stale-prod",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:99999:5000", // Stale fingerprint!
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.StalePreview, result)

        val countsAfter = countAllTables()
        assertEquals("Must write zero rows on stale product fingerprint", countsBefore, countsAfter)
    }

    @Test
    fun wrongBusinessAndBlankUserAreRejected() = runBlocking {
        val service = createService()

        // Wrong business
        val requestWrongBiz = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-wrong-biz",
            businessId = "wrong-biz-999",
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = productId,
                    productNameSnapshot = "Jabón Corporal",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "$productId:10000:5000",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
        )
        val resultWrongBiz = service.confirm(requestWrongBiz)
        assertEquals(AiSaleExecutionResult.StalePreview, resultWrongBiz)

        // Blank user
        val serviceBlankUser = AiSaleExecutionService(
            registerSale = { key, sale, items, charge, custFp, prodFps, payment, paymentMovement, allocations, expectedInitialPaymentId, newCust, expCust, newProds, expProdMap ->
                saleRepository.registerCompositeAgainstPreview(key, sale, items, charge, custFp, prodFps, payment, paymentMovement, allocations, expectedInitialPaymentId, newCust, expCust, newProds, expProdMap)
            },
            currentUserId = { "   " },
            nowMillis = { 20_000L },
            todayEpochDay = { 20_500L },
        )
        val resultBlankUser = serviceBlankUser.confirm(requestWrongBiz.copy(businessId = businessId))
        assertEquals(AiSaleExecutionResult.Unavailable, resultBlankUser)
    }

    @Test
    fun outboxPayloadContainsPersistedCreatedEntities() = runBlocking {
        var capturedBundle: StoredSaleBundle? = null
        val service = createService(
            onEnqueue = { bundle -> capturedBundle = bundle },
        )
        val newCustId = "cust-outbox-1"
        val newProdId = "prod-outbox-1"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "comp-conf-outbox-test",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Pan Integral",
                    quantity = 1,
                    unitPriceCents = 4500L,
                    lineTotalCents = 4500L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 4500L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(newCustId, "Beatriz", "beatriz", false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Pan Integral", 4500L, false)),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)
        assertNotNull(capturedBundle)
        assertEquals(newCustId, capturedBundle?.createdCustomer?.id)
        assertEquals("Beatriz", capturedBundle?.createdCustomer?.name)
        assertEquals(1, capturedBundle?.createdProducts?.size)
        assertEquals(newProdId, capturedBundle?.createdProducts?.first()?.id)
        assertEquals("Pan Integral", capturedBundle?.createdProducts?.first()?.name)
    }

    @Test
    fun newCustomerWithAlienBusinessIdReturnsStaleDataAndZeroWrites() = runBlocking {
        val countsBefore = countAllTables()

        val alienCustomer = CustomerEntity(
            id = "cust-alien-biz",
            businessId = "alien-business-999", // Does NOT match sale businessId
            name = "Alien Customer",
            normalizedName = "alien customer",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )

        val saleId = com.cuentivo.app.domain.SaleOperationIds.sale("ai-sale-alien-test")
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            customerId = "cust-alien-biz",
            sellerUserId = userId,
            saleNumber = "V-alien-1",
            saleDateEpochDay = 20500L,
            dueDateEpochDay = null,
            subtotalCents = 5000L,
            discountCents = 0L,
            totalCents = 5000L,
            isCanceled = false,
            notes = null,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
        )
        val item = SaleItemEntity(
            id = "item-alien-1",
            saleId = saleId,
            productId = productId,
            productNameSnapshot = "Jabón",
            quantity = 1L,
            unitPriceCents = 5000L,
            discountCents = 0L,
            subtotalCents = 5000L,
        )
        val charge = AccountMovementEntity(
            id = "charge-alien-1",
            businessId = businessId,
            customerId = "cust-alien-biz",
            saleId = saleId,
            paymentId = null,
            type = "CHARGE",
            amountCents = 5000L,
            direction = "DEBIT",
            occurredAtEpochMillis = 10_000L,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )

        val result = saleRepository.registerCompositeAgainstPreview(
            idempotencyKey = "ai-sale-alien-test",
            sale = sale,
            items = listOf(item),
            charge = charge,
            expectedCustomerFingerprint = "",
            expectedProductFingerprints = mapOf(productId to "$productId:10000:5000"),
            payment = null,
            paymentMovement = null,
            paymentAllocations = emptyList(),
            newCustomer = alienCustomer,
            explicitCustomerCreateNew = false,
            newProducts = emptyList(),
        )

        assertEquals(com.cuentivo.app.data.local.ExpectedSaleResult.StaleData, result)

        val countsAfter = countAllTables()
        assertEquals("Must write zero rows when new customer has alien businessId", countsBefore, countsAfter)
    }

    @Test
    fun twoNewProductsWithSameNormalizedNameWithoutExplicitCreateNewReturnsStaleDataAndZeroWrites() = runBlocking {
        val countsBefore = countAllTables()

        val newProd1 = ProductEntity(
            id = "prod-dup-name-1",
            businessId = businessId,
            name = "Jabón De Miel",
            description = null,
            sku = null,
            category = null,
            salePriceCents = 6000L,
            costPriceCents = null,
            stock = null,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )
        val newProd2 = ProductEntity(
            id = "prod-dup-name-2", // Different ID, but same normalized name "jabon de miel"
            businessId = businessId,
            name = "jabón de miel",
            description = null,
            sku = null,
            category = null,
            salePriceCents = 6000L,
            costPriceCents = null,
            stock = null,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
            deletedAtEpochMillis = null,
        )

        val saleId = com.cuentivo.app.domain.SaleOperationIds.sale("ai-sale-dup-prod-test")
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            customerId = customerId,
            sellerUserId = userId,
            saleNumber = "V-dup-prod-1",
            saleDateEpochDay = 20500L,
            dueDateEpochDay = null,
            subtotalCents = 12000L,
            discountCents = 0L,
            totalCents = 12000L,
            isCanceled = false,
            notes = null,
            createdAtEpochMillis = 10_000L,
            updatedAtEpochMillis = 10_000L,
        )
        val items = listOf(
            SaleItemEntity("item-dup-1", saleId, "prod-dup-name-1", "Jabón De Miel", 1L, 6000L, 0L, 6000L),
            SaleItemEntity("item-dup-2", saleId, "prod-dup-name-2", "jabón de miel", 1L, 6000L, 0L, 6000L),
        )
        val charge = AccountMovementEntity(
            id = "charge-dup-1",
            businessId = businessId,
            customerId = customerId,
            saleId = saleId,
            paymentId = null,
            type = "CHARGE",
            amountCents = 12000L,
            direction = "DEBIT",
            occurredAtEpochMillis = 10_000L,
            createdByUserId = userId,
            reversesMovementId = null,
            notes = null,
        )

        val result = saleRepository.registerCompositeAgainstPreview(
            idempotencyKey = "ai-sale-dup-prod-test",
            sale = sale,
            items = items,
            charge = charge,
            expectedCustomerFingerprint = "$customerId:10000",
            expectedProductFingerprints = emptyMap(),
            payment = null,
            paymentMovement = null,
            paymentAllocations = emptyList(),
            newCustomer = null,
            explicitCustomerCreateNew = false,
            newProducts = listOf(newProd1, newProd2),
            explicitProductCreateNewByProductId = mapOf("prod-dup-name-1" to false, "prod-dup-name-2" to false),
        )

        assertEquals(com.cuentivo.app.data.local.ExpectedSaleResult.StaleData, result)

        val countsAfter = countAllTables()
        assertEquals("Must write zero rows when two new products share normalized name without explicit createNew", countsBefore, countsAfter)
    }

    @Test
    fun outboxPayloadEnqueuesCustomerAndProductsBeforeSaleInCorrectOrder() = runBlocking {
        val enqueuedRecords = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        val mockEnqueue: suspend (StoredSaleBundle) -> Unit = { bundle ->
            val sale = bundle.sale
            val now = sale.createdAtEpochMillis
            bundle.createdCustomer?.let {
                enqueuedRecords.add(Triple("CUSTOMER", it.id, SyncPayloads.customer(it)))
            }
            bundle.createdProducts.forEach {
                enqueuedRecords.add(Triple("PRODUCT", it.id, SyncPayloads.product(it)))
            }
            enqueuedRecords.add(Triple("SALE", sale.id, SyncPayloads.sale(sale)))
            bundle.items.forEach { item ->
                enqueuedRecords.add(Triple("SALE_ITEM", item.id, SyncPayloads.saleItem(item)))
            }
            enqueuedRecords.add(Triple("MOVEMENT", bundle.chargeMovement.id, SyncPayloads.movement(bundle.chargeMovement)))
            bundle.paymentBundle?.let { pBundle ->
                val payment = pBundle.payment
                enqueuedRecords.add(Triple("PAYMENT", payment.id, SyncPayloads.payment(payment)))
                pBundle.allocations.forEach { allocation ->
                    enqueuedRecords.add(Triple("PAYMENT_ALLOCATION", allocation.id, SyncPayloads.allocation(allocation)))
                }
                enqueuedRecords.add(Triple("MOVEMENT", pBundle.movement.id, SyncPayloads.movement(pBundle.movement)))
            }
        }

        val service = createService(onEnqueue = mockEnqueue)
        val newCustId = "cust-order-test"
        val newProdId = "prod-order-test"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "conf-order-test",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Jabón Neutro",
                    quantity = 2,
                    unitPriceCents = 3000L,
                    lineTotalCents = 6000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 6000L,
            initialPaymentCents = 2000L,
            newCustomer = AiNewCustomerSpec(newCustId, "Carlos Mendoza", "carlos mendoza", false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Jabón Neutro", 3000L, false)),
        )

        val result = service.confirm(request)
        assertTrue(result is AiSaleExecutionResult.Recorded)

        // Verify ordering: CUSTOMER, PRODUCT, SALE, SALE_ITEM, MOVEMENT (charge), PAYMENT, PAYMENT_ALLOCATION, MOVEMENT (payment)
        val types = enqueuedRecords.map { it.first }
        assertEquals(
            listOf("CUSTOMER", "PRODUCT", "SALE", "SALE_ITEM", "MOVEMENT", "PAYMENT", "PAYMENT_ALLOCATION", "MOVEMENT"),
            types,
        )

        // Verify CUSTOMER payload
        val custRecord = enqueuedRecords[0]
        assertEquals("CUSTOMER", custRecord.first)
        assertEquals(newCustId, custRecord.second)
        assertEquals("Carlos Mendoza", custRecord.third["name"])
        assertEquals("carlos mendoza", custRecord.third["normalizedName"])

        // Verify PRODUCT payload
        val prodRecord = enqueuedRecords[1]
        assertEquals("PRODUCT", prodRecord.first)
        assertEquals(newProdId, prodRecord.second)
        assertEquals("Jabón Neutro", prodRecord.third["name"])
        assertEquals(3000L, prodRecord.third["salePriceCents"])
    }

    @Test
    fun recoveryAfterPartialEnqueueWithSameConfirmationKeyIsIdempotent() = runBlocking {
        var enqueueCount = 0
        val service = createService(
            onEnqueue = { enqueueCount++ },
        )
        val newCustId = "cust-retry-test"
        val newProdId = "prod-retry-test"

        val request = AiSaleConfirmationRequest(
            confirmationKey = "conf-retry-idem-test",
            businessId = businessId,
            customerId = newCustId,
            sourceCustomerFingerprint = "",
            lines = listOf(
                AiSaleConfirmationLine(
                    productId = newProdId,
                    productNameSnapshot = "Aceite",
                    quantity = 1,
                    unitPriceCents = 5000L,
                    lineTotalCents = 5000L,
                    sourceProductFingerprint = "",
                ),
            ),
            totalCents = 5000L,
            initialPaymentCents = 0L,
            newCustomer = AiNewCustomerSpec(newCustId, "David", "david", false),
            newProducts = listOf(AiNewProductSpec(newProdId, "Aceite", 5000L, false)),
        )

        val result1 = service.confirm(request)
        assertTrue(result1 is AiSaleExecutionResult.Recorded)
        assertEquals(1, enqueueCount)

        val countsAfterFirst = countAllTables()

        // Re-execute with identical request (retry / recovery)
        val result2 = service.confirm(request)
        assertTrue(result2 is AiSaleExecutionResult.Recorded)
        assertEquals((result1 as AiSaleExecutionResult.Recorded).saleId, (result2 as AiSaleExecutionResult.Recorded).saleId)
        assertEquals(2, enqueueCount)

        val countsAfterSecond = countAllTables()
        assertEquals("Retry must not duplicate DB rows", countsAfterFirst, countsAfterSecond)
    }

    @Test
    fun uncatalogedChargeExecutionRecordsSaleAndChargeUpdatesBalanceAndReplaysIdempotently() = runBlocking {
        val service = createService()
        val initialBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        val countsBefore = countAllTables()

        val request = AiSaleConfirmationRequest(
            confirmationKey = "conf-uncataloged-charge-1",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:10000",
            lines = listOf(
                AiSaleConfirmationLine.UncatalogedCharge(
                    productNameSnapshot = "Deuda anterior libreta",
                    unitPriceCents = 35000L,
                    lineTotalCents = 35000L,
                ),
            ),
            totalCents = 35000L,
            initialPaymentCents = 10000L,
            saleDateEpochDay = 20400L,
            promisedPaymentEpochDay = 20450L,
        )

        // First execution
        val result1 = service.confirm(request)
        assertTrue("result1 must be Recorded", result1 is AiSaleExecutionResult.Recorded)
        val recorded1 = result1 as AiSaleExecutionResult.Recorded
        assertNotNull(recorded1.saleId)
        assertNotNull(recorded1.paymentId)

        // Verify balance actually increased by net charge: total 35000 - initialPayment 10000 = +25000
        val balanceAfterFirst = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(initialBalance + 25000L, balanceAfterFirst)

        // Verify item entity created has null productId, quantity 1, and trimmed description
        val items = database.financialDao().itemsForSale(recorded1.saleId)
        assertEquals(1, items.size)
        assertNull(items[0].productId)
        assertEquals(1L, items[0].quantity)
        assertEquals("Deuda anterior libreta", items[0].productNameSnapshot)
        assertEquals(35000L, items[0].unitPriceCents)
        assertEquals(35000L, items[0].subtotalCents)

        // Verify sale dates and payment date
        val sale = database.financialDao().saleForId(recorded1.saleId)
        assertNotNull(sale)
        assertEquals(20400L, sale?.saleDateEpochDay)
        assertEquals(20450L, sale?.dueDateEpochDay)

        val payment = database.financialDao().paymentForId(recorded1.paymentId!!)
        assertNotNull(payment)
        assertEquals(20400L, payment?.paymentDateEpochDay)
        assertEquals(10000L, payment?.amountCents)

        // Verify audit log has been written with specific SALE_CREATED and PAYMENT_REGISTERED events
        val auditLogs = database.auditDao().forBusiness(businessId)
        assertTrue(
            "Must record SALE_CREATED audit event for saleId",
            auditLogs.any { it.action == AuditAction.SALE_CREATED.code && it.entityId == recorded1.saleId },
        )
        assertTrue(
            "Must record PAYMENT_REGISTERED audit event for paymentId",
            auditLogs.any { it.action == AuditAction.PAYMENT_REGISTERED.code && it.entityId == recorded1.paymentId },
        )
        val countsAfterFirst = countAllTables()

        // Re-execute with same confirmationKey (idempotent replay)
        val result2 = service.confirm(request)
        assertTrue("result2 must be Recorded", result2 is AiSaleExecutionResult.Recorded)
        val recorded2 = result2 as AiSaleExecutionResult.Recorded
        assertEquals(recorded1.saleId, recorded2.saleId)
        assertEquals(recorded1.paymentId, recorded2.paymentId)

        // Verify customer balance did not change again
        val balanceAfterSecond = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(balanceAfterFirst, balanceAfterSecond)

        // Verify database counts are strictly unchanged
        val countsAfterSecond = countAllTables()
        assertEquals("Replay must not create duplicate DB rows", countsAfterFirst, countsAfterSecond)
    }
}
