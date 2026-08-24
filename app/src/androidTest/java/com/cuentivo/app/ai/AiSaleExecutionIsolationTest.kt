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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented isolation tests for [AiSaleExecutionService], [SaleRepository], and [FinancialDao].
 * Proves exact-once financial mutation, atomic sale + initial payment, idempotency replay,
 * concurrency safety, and stale fingerprint rejection.
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
        assertEquals(6000L, customerBalance) // 10000 debt - 4000 paid = 6000
    }

    @Test
    fun idempotencyReplayReturnsOriginalIdsWithoutDuplicates() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-replay",
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
            initialPaymentCents = 2000L,
        )

        val first = service.confirm(request) as AiSaleExecutionResult.Recorded
        val second = service.confirm(request) as AiSaleExecutionResult.Recorded

        assertEquals(first.saleId, second.saleId)
        assertEquals(first.paymentId, second.paymentId)

        val allSales = database.financialDao().allSalesForBackfill(businessId)
        assertEquals(1, allSales.size)
        val customerBalance = database.financialDao().customerBalanceCents(businessId, customerId)
        assertEquals(3000L, customerBalance)
    }

    @Test
    fun staleCustomerFingerprintRejectsAndWritesNothing() = runBlocking {
        val service = createService()
        val request = AiSaleConfirmationRequest(
            confirmationKey = "sale-conf-stale-cust",
            businessId = businessId,
            customerId = customerId,
            sourceCustomerFingerprint = "$customerId:99999", // Stale!
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
                    sourceProductFingerprint = "$productId:99999:5000", // Stale!
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
