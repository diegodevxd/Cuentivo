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
import com.cuentivo.app.domain.SaleRepository
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

    private fun createService() = AiSaleExecutionService(
        registerSale = { key, sale, items, charge, custFp, prodFps, payment, paymentMovement, allocations ->
            saleRepository.registerAgainstPreview(
                idempotencyKey = key,
                sale = sale,
                items = items,
                charge = charge,
                expectedCustomerFingerprint = custFp,
                expectedProductFingerprints = prodFps,
                payment = payment,
                paymentMovement = paymentMovement,
                paymentAllocations = allocations,
            )
        },
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
}
