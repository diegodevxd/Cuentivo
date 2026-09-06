package com.cuentivo.app.ai

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedSaleResult
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.domain.SaleOperationIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiSaleExecutionTest {

    private val validLine = AiSaleConfirmationLine.CatalogProduct(
        productId = "prod-1",
        productNameSnapshot = "Jabón",
        quantity = 2,
        unitPriceCents = 5000L,
        lineTotalCents = 10000L,
        sourceProductFingerprint = "prod-1:2000:5000",
    )

    private val validRequest = AiSaleConfirmationRequest(
        confirmationKey = "key-sale-1",
        businessId = "biz-1",
        customerId = "cust-1",
        sourceCustomerFingerprint = "cust-1:1000",
        lines = listOf(validLine),
        totalCents = 10000L,
        initialPaymentCents = 0L,
        promisedPaymentEpochDay = 20600L,
    )

    @Test
    fun `successful sale execution with zero initial payment records sale and charge only`() = runBlocking {
        var recordedSale: SaleEntity? = null
        var recordedItems: List<SaleItemEntity>? = null
        var recordedCharge: AccountMovementEntity? = null
        var recordedPayment: PaymentEntity? = null

        val service = AiSaleExecutionService(
            registerSale = { _, sale, items, charge, _, _, payment, _, _, _, _, _, _, _ ->
                recordedSale = sale
                recordedItems = items
                recordedCharge = charge
                recordedPayment = payment
                ExpectedSaleResult.Recorded(sale.id, payment?.id)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(validRequest)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded
        assertNotNull(recorded.saleId)
        assertNull(recorded.paymentId)
        assertNull(recordedPayment)

        assertEquals("user-1", recordedSale?.sellerUserId)
        assertEquals(10000L, recordedSale?.totalCents)
        assertEquals(20600L, recordedSale?.dueDateEpochDay)
        assertEquals(1, recordedItems?.size)
        assertEquals("CHARGE", recordedCharge?.type)
        assertEquals("DEBIT", recordedCharge?.direction)
        assertEquals(10000L, recordedCharge?.amountCents)
    }

    @Test
    fun `successful sale execution with initial payment records sale and payment`() = runBlocking {
        var recordedPayment: PaymentEntity? = null
        var recordedPaymentMovement: AccountMovementEntity? = null
        var recordedAllocations: List<PaymentAllocationEntity>? = null

        val request = validRequest.copy(initialPaymentCents = 4000L)

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, payment, paymentMovement, allocations, _, _, _, _, _ ->
                recordedPayment = payment
                recordedPaymentMovement = paymentMovement
                recordedAllocations = allocations
                ExpectedSaleResult.Recorded(sale.id, payment?.id)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        val recorded = result as AiSaleExecutionResult.Recorded
        assertNotNull(recorded.saleId)
        assertNotNull(recorded.paymentId)

        assertEquals(4000L, recordedPayment?.amountCents)
        assertEquals("PAYMENT", recordedPaymentMovement?.type)
        assertEquals("CREDIT", recordedPaymentMovement?.direction)
        assertEquals(1, recordedAllocations?.size)
        assertEquals(recorded.saleId, recordedAllocations?.first()?.saleId)
        assertEquals(4000L, recordedAllocations?.first()?.amountCents)
    }

    @Test
    fun `stale customer or product data returns StalePreview`() = runBlocking {
        val service = AiSaleExecutionService(
            registerSale = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
                ExpectedSaleResult.StaleData
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(validRequest)
        assertEquals(AiSaleExecutionResult.StalePreview, result)
    }

    @Test
    fun `invalid request parameters are rejected fail-closed`() = runBlocking {
        val service = AiSaleExecutionService(
            registerSale = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
                ExpectedSaleResult.Recorded("s", null)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(confirmationKey = "")))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(businessId = "")))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(customerId = "")))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = emptyList())))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(totalCents = 0L)))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(totalCents = -100L)))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(initialPaymentCents = -50L)))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(initialPaymentCents = 20000L))) // exceeds total

        // Line with mismatch total
        val badLine = validLine.copy(lineTotalCents = 8000L) // 2 * 5000 != 8000
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(badLine))))

        // Line with blank product name snapshot
        val blankNameLine = validLine.copy(productNameSnapshot = "   ")
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(blankNameLine))))

        // Line with blank product id
        val blankIdLine = validLine.copy(productId = " ")
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(blankIdLine))))

        // Line with invalid quantity or negative price
        val badQtyLine = validLine.copy(quantity = 0, lineTotalCents = 0L)
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(badQtyLine), totalCents = 0L)))
        val negPriceLine = validLine.copy(unitPriceCents = -10L)
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(negPriceLine))))
    }

    @Test
    fun `missing user id returns Unavailable`() = runBlocking {
        val service = AiSaleExecutionService(
            registerSale = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded("s", null) },
            currentUserId = { "" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(validRequest)
        assertEquals(AiSaleExecutionResult.Unavailable, result)
    }

    @Test
    fun `cancellation exception is rethrown`() = runBlocking {
        val service = AiSaleExecutionService(
            registerSale = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> throw CancellationException("test cancel") },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        try {
            service.confirm(validRequest)
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            // Success
        }
    }

    @Test
    fun `deterministic operation IDs generated for same confirmation key`() = runBlocking {
        val key = "key-idem-1"
        val idempotencyKey = "ai-sale-$key"
        val saleId1 = SaleOperationIds.sale(idempotencyKey)
        val saleId2 = SaleOperationIds.sale(idempotencyKey)
        assertEquals(saleId1, saleId2)

        val chargeId1 = SaleOperationIds.chargeMovement(idempotencyKey)
        val chargeId2 = SaleOperationIds.chargeMovement(idempotencyKey)
        assertEquals(chargeId1, chargeId2)

        val paymentId1 = SaleOperationIds.payment(idempotencyKey)
        val paymentId2 = SaleOperationIds.payment(idempotencyKey)
        assertEquals(paymentId1, paymentId2)
    }

    @Test
    fun `confirm on Recorded loads stored bundle and enqueues to sync queue`() = runBlocking {
        var enqueuedBundle: com.cuentivo.app.data.local.StoredSaleBundle? = null
        val sampleSale = SaleEntity(
            id = "sale-1",
            businessId = "biz-1",
            customerId = "cust-1",
            sellerUserId = "user-1",
            saleNumber = "V-1",
            saleDateEpochDay = 20500L,
            dueDateEpochDay = 20600L,
            subtotalCents = 10000L,
            discountCents = 0L,
            totalCents = 10000L,
            isCanceled = false,
            notes = null,
            createdAtEpochMillis = 5000L,
            updatedAtEpochMillis = 5000L,
        )
        val sampleItem = SaleItemEntity(
            id = "item-1",
            saleId = "sale-1",
            productId = "prod-1",
            productNameSnapshot = "Jabón",
            quantity = 2L,
            unitPriceCents = 5000L,
            discountCents = 0L,
            subtotalCents = 10000L,
        )
        val sampleCharge = AccountMovementEntity(
            id = "charge-1",
            businessId = "biz-1",
            customerId = "cust-1",
            saleId = "sale-1",
            paymentId = null,
            type = "CHARGE",
            amountCents = 10000L,
            direction = "DEBIT",
            occurredAtEpochMillis = 5000L,
            createdByUserId = "user-1",
            reversesMovementId = null,
            notes = null,
        )
        val mockBundle = com.cuentivo.app.data.local.StoredSaleBundle(
            sale = sampleSale,
            items = listOf(sampleItem),
            chargeMovement = sampleCharge,
            paymentBundle = null,
        )

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            loadStoredBundle = { businessId, _, _, _ ->
                if (businessId == "biz-1") mockBundle else null
            },
            enqueueStoredBundle = { bundle -> enqueuedBundle = bundle },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(validRequest)
        assertTrue(result is AiSaleExecutionResult.Recorded)
        assertEquals(mockBundle, enqueuedBundle)
    }

    @Test
    fun `confirm on StaleData does not enqueue stored bundle`() = runBlocking {
        var enqueued = false
        val service = AiSaleExecutionService(
            registerSale = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.StaleData },
            loadStoredBundle = { _, _, _, _ -> fail("Should not load bundle on StaleData"); null },
            enqueueStoredBundle = { enqueued = true },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(validRequest)
        assertTrue(result is AiSaleExecutionResult.StalePreview)
        assertEquals(false, enqueued)
    }

    @Test
    fun `successful composite sale passes new entity specs to registerSale and loads persisted entities`() = runBlocking {
        val newCustSpec = AiNewCustomerSpec(
            customerId = "new-cust-1",
            displayName = "Carlos Santana",
            normalizedName = "carlos santana",
            explicitCreateNew = false,
        )
        val newProdSpec = AiNewProductSpec(
            productId = "new-prod-1",
            name = "Aceite de Oliva",
            salePriceCents = 12000L,
            explicitCreateNew = true,
        )
        val compositeLine = AiSaleConfirmationLine(
            productId = "new-prod-1",
            productNameSnapshot = "Aceite de Oliva",
            quantity = 1,
            unitPriceCents = 12000L,
            lineTotalCents = 12000L,
            sourceProductFingerprint = "", // empty fingerprint for new product
        )
        val compositeRequest = AiSaleConfirmationRequest(
            confirmationKey = "key-comp-1",
            businessId = "biz-1",
            customerId = "new-cust-1",
            sourceCustomerFingerprint = "", // empty fingerprint for new customer
            lines = listOf(compositeLine),
            totalCents = 12000L,
            initialPaymentCents = 0L,
            newCustomer = newCustSpec,
            newProducts = listOf(newProdSpec),
        )

        var passedNewCustomer: CustomerEntity? = null
        var passedNewProducts: List<ProductEntity>? = null
        var passedExplicitCustCreateNew = false
        var passedExplicitProdCreateNew = false

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, newCust, expCust, newProds, expProdMap ->
                passedNewCustomer = newCust
                passedNewProducts = newProds
                passedExplicitCustCreateNew = expCust
                passedExplicitProdCreateNew = expProdMap["new-prod-1"] ?: false
                ExpectedSaleResult.Recorded(sale.id, null)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(compositeRequest)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        assertNotNull(passedNewCustomer)
        assertEquals("new-cust-1", passedNewCustomer?.id)
        assertEquals("Carlos Santana", passedNewCustomer?.name)
        assertEquals("carlos santana", passedNewCustomer?.normalizedName)
        assertEquals(false, passedExplicitCustCreateNew)

        assertEquals(1, passedNewProducts?.size)
        assertEquals("new-prod-1", passedNewProducts?.first()?.id)
        assertEquals("Aceite de Oliva", passedNewProducts?.first()?.name)
        assertEquals(12000L, passedNewProducts?.first()?.salePriceCents)
        assertEquals(true, passedExplicitProdCreateNew)
    }

    @Test
    fun `empty customer fingerprint without newCustomer spec is Rejected`() = runBlocking {
        val request = validRequest.copy(sourceCustomerFingerprint = "", newCustomer = null)
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Rejected, result)
    }

    @Test
    fun `non-empty customer fingerprint with newCustomer spec is Rejected`() = runBlocking {
        val request = validRequest.copy(
            sourceCustomerFingerprint = "cust-1:1000",
            newCustomer = AiNewCustomerSpec("cust-1", "María", "maria", false),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Rejected, result)
    }

    @Test
    fun `newCustomer customerId mismatching request customerId is Rejected`() = runBlocking {
        val request = validRequest.copy(
            customerId = "cust-1",
            sourceCustomerFingerprint = "",
            newCustomer = AiNewCustomerSpec("other-cust", "María", "maria", false),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Rejected, result)
    }

    @Test
    fun `blank newCustomer displayName or normalizedName is Rejected`() = runBlocking {
        val request1 = validRequest.copy(
            customerId = "cust-1",
            sourceCustomerFingerprint = "",
            newCustomer = AiNewCustomerSpec("cust-1", "   ", "maria", false),
        )
        val request2 = validRequest.copy(
            customerId = "cust-1",
            sourceCustomerFingerprint = "",
            newCustomer = AiNewCustomerSpec("cust-1", "María", "   ", false),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request1))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request2))
    }

    @Test
    fun `empty product fingerprint without corresponding newProduct spec is Rejected`() = runBlocking {
        val lineWithoutFp = validLine.copy(sourceProductFingerprint = "")
        val request = validRequest.copy(lines = listOf(lineWithoutFp), newProducts = emptyList())
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request))
    }

    @Test
    fun `non-empty product fingerprint with corresponding newProduct spec is Rejected`() = runBlocking {
        val request = validRequest.copy(
            lines = listOf(validLine), // has non-empty fingerprint
            newProducts = listOf(AiNewProductSpec("prod-1", "Jabón", 5000L, false)),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request))
    }

    @Test
    fun `orphan newProduct spec without matching sale line is Rejected`() = runBlocking {
        val lineWithoutFp = validLine.copy(productId = "prod-1", sourceProductFingerprint = "")
        val request = validRequest.copy(
            lines = listOf(lineWithoutFp),
            newProducts = listOf(
                AiNewProductSpec("prod-1", "Jabón", 5000L, false),
                AiNewProductSpec("prod-orphan", "Shampoo", 8000L, false),
            ),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request))
    }

    @Test
    fun `newProduct spec with non-positive salePriceCents is Rejected`() = runBlocking {
        val lineWithoutFp = validLine.copy(productId = "prod-1", sourceProductFingerprint = "", unitPriceCents = 5000L)
        val requestZero = validRequest.copy(
            lines = listOf(lineWithoutFp),
            newProducts = listOf(AiNewProductSpec("prod-1", "Jabón", 0L, false)),
        )
        val requestNegative = validRequest.copy(
            lines = listOf(lineWithoutFp),
            newProducts = listOf(AiNewProductSpec("prod-1", "Jabón", -5000L, false)),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(requestZero))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(requestNegative))
    }

    @Test
    fun `newProduct spec with blank name or productId is Rejected`() = runBlocking {
        val lineWithoutFp = validLine.copy(productId = "prod-1", sourceProductFingerprint = "")
        val requestBlankName = validRequest.copy(
            lines = listOf(lineWithoutFp),
            newProducts = listOf(AiNewProductSpec("prod-1", "   ", 5000L, false)),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(requestBlankName))
    }

    @Test
    fun `duplicate newProduct specs with same productId is Rejected`() = runBlocking {
        val lineWithoutFp = validLine.copy(productId = "prod-1", sourceProductFingerprint = "")
        val request = validRequest.copy(
            lines = listOf(lineWithoutFp),
            newProducts = listOf(
                AiNewProductSpec("prod-1", "Jabón", 5000L, false),
                AiNewProductSpec("prod-1", "Jabón Duplicado", 5000L, true),
            ),
        )
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request))
    }

    @Test
    fun `explicit past notebook saleDateEpochDay is recorded on sale entity`() = runBlocking {
        var recordedSale: SaleEntity? = null
        val request = validRequest.copy(saleDateEpochDay = 20450L)

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ ->
                recordedSale = sale
                ExpectedSaleResult.Recorded(sale.id, null)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        assertEquals(20450L, recordedSale?.saleDateEpochDay)
    }

    @Test
    fun `future saleDateEpochDay is Rejected`() = runBlocking {
        val request = validRequest.copy(saleDateEpochDay = 20501L)

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(request)
        assertEquals(AiSaleExecutionResult.Rejected, result)
    }

    @Test
    fun `zero or negative saleDateEpochDay is Rejected`() = runBlocking {
        val requestZero = validRequest.copy(saleDateEpochDay = 0L)
        val requestNegative = validRequest.copy(saleDateEpochDay = -1L)

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(requestZero))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(requestNegative))
    }

    @Test
    fun `uncataloged charge line without product records null productId and uncataloged item id`() = runBlocking {
        var recordedSale: SaleEntity? = null
        var recordedItems: List<SaleItemEntity>? = null
        var recordedProductFps: Map<String, String>? = null

        val uncatalogedLine = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Deuda libreta",
            unitPriceCents = 30000L,
            lineTotalCents = 30000L,
        )
        val request = validRequest.copy(
            lines = listOf(uncatalogedLine),
            totalCents = 30000L,
            saleDateEpochDay = 20400L,
        )

        val service = AiSaleExecutionService(
            registerSale = { idempotencyKey, sale, items, _, _, expectedProdFps, _, _, _, _, _, _, _, _ ->
                recordedSale = sale
                recordedItems = items
                recordedProductFps = expectedProdFps
                ExpectedSaleResult.Recorded(sale.id, null)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        assertEquals(20400L, recordedSale?.saleDateEpochDay)
        assertEquals(1, recordedItems?.size)
        val item = recordedItems!![0]
        assertNull(item.productId)
        assertEquals("Deuda libreta", item.productNameSnapshot)
        assertEquals(1L, item.quantity)
        assertEquals(30000L, item.unitPriceCents)
        assertEquals(30000L, item.subtotalCents)
        assertEquals(SaleOperationIds.uncatalogedSaleItem("ai-sale-key-sale-1", 0), item.id)
        assertTrue(recordedProductFps?.isEmpty() == true)
    }

    @Test
    fun `uncataloged charge line with blank or overly long name is rejected`() = runBlocking {
        val blankLine = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "   ",
            unitPriceCents = 15000L,
            lineTotalCents = 15000L,
        )
        val longLine = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "A".repeat(161),
            unitPriceCents = 15000L,
            lineTotalCents = 15000L,
        )

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(blankLine), totalCents = 15000L)))
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(longLine), totalCents = 15000L)))
    }

    @Test
    fun `uncataloged charge line rejecting mismatch line total or zero price`() = runBlocking {
        val lineMismatchTotal = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Deuda",
            unitPriceCents = 1000L,
            lineTotalCents = 2000L, // lineTotal != unitPrice
        )
        val validLinePart = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Jabón",
            unitPriceCents = 1000L,
            lineTotalCents = 1000L,
        )
        val lineZeroPrice = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Deuda",
            unitPriceCents = 0L,
            lineTotalCents = 0L,
        )

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        // Mismatch between unitPrice and lineTotal
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(validRequest.copy(lines = listOf(lineMismatchTotal), totalCents = 2000L)))
        // Zero price combined with a valid 1000L line: calculated sum (1000 + 0 = 1000) matches totalCents (1000),
        // proving the rejection is strictly caused by unitPriceCents <= 0L on the uncataloged line.
        assertEquals(
            AiSaleExecutionResult.Rejected,
            service.confirm(validRequest.copy(lines = listOf(validLinePart, lineZeroPrice), totalCents = 1000L)),
        )
    }

    @Test
    fun `confirmLedger with mandatory saleDateEpochDay records exact historical sale date`() = runBlocking {
        var recordedSale: SaleEntity? = null
        val uncatalogedLine = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Saldo libreta",
            unitPriceCents = 25000L,
            lineTotalCents = 25000L,
        )
        val ledgerRequest = AiLedgerSaleConfirmationRequest(
            confirmationKey = "ledger-key-1",
            businessId = "biz-1",
            customerId = "cust-1",
            sourceCustomerFingerprint = "cust-1:1000",
            lines = listOf(uncatalogedLine),
            totalCents = 25000L,
            initialPaymentCents = 5000L,
            saleDateEpochDay = 20410L, // Non-nullable, mandatory on ledger request
            promisedPaymentEpochDay = 20450L,
        )

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, payment, _, _, _, _, _, _, _ ->
                recordedSale = sale
                ExpectedSaleResult.Recorded(sale.id, payment?.id)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirmLedger(ledgerRequest)
        assertTrue(result is AiSaleExecutionResult.Recorded)
        assertEquals(20410L, recordedSale?.saleDateEpochDay)
    }

    @Test
    fun `sale with promised payment date earlier than sale date is rejected`() = runBlocking {
        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, _, _, _, _, _, _, _, _ -> ExpectedSaleResult.Recorded(sale.id, null) },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val request = validRequest.copy(
            saleDateEpochDay = 20400L,
            promisedPaymentEpochDay = 20399L, // earlier than sale date
        )
        assertEquals(AiSaleExecutionResult.Rejected, service.confirm(request))
    }

    @Test
    fun `initial payment on past sale date records payment with historical sale date`() = runBlocking {
        var recordedPayment: PaymentEntity? = null

        val uncatalogedLine = AiSaleConfirmationLine.UncatalogedCharge(
            productNameSnapshot = "Deuda libreta",
            unitPriceCents = 30000L,
            lineTotalCents = 30000L,
        )
        val request = validRequest.copy(
            lines = listOf(uncatalogedLine),
            totalCents = 30000L,
            initialPaymentCents = 10000L,
            saleDateEpochDay = 20400L,
            promisedPaymentEpochDay = 20450L,
        )

        val service = AiSaleExecutionService(
            registerSale = { _, sale, _, _, _, _, payment, _, _, _, _, _, _, _ ->
                recordedPayment = payment
                ExpectedSaleResult.Recorded(sale.id, payment?.id)
            },
            currentUserId = { "user-1" },
            nowMillis = { 5000L },
            todayEpochDay = { 20500L },
        )

        val result = service.confirm(request)
        assertTrue("result must be Recorded", result is AiSaleExecutionResult.Recorded)
        assertNotNull(recordedPayment)
        assertEquals("Initial payment must be dated to effectiveSaleDate", 20400L, recordedPayment?.paymentDateEpochDay)
    }
}

