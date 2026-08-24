package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSalePreviewTest {

    private fun testCustomer(
        id: String = "cust-1",
        businessId: String = "biz-1",
        name: String = "María López",
        normalizedName: String = "maria lopez",
        updatedAt: Long = 1000L,
    ) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = normalizedName,
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = true,
        createdAtEpochMillis = 1000L,
        updatedAtEpochMillis = updatedAt,
        deletedAtEpochMillis = null,
    )

    private fun testProduct(
        id: String = "prod-1",
        businessId: String = "biz-1",
        name: String = "Jabón Corporal",
        salePriceCents: Long = 5000L,
        updatedAt: Long = 2000L,
    ) = ProductEntity(
        id = id,
        businessId = businessId,
        name = name,
        description = null,
        sku = "SKU-JABON",
        category = "Higiene",
        salePriceCents = salePriceCents,
        costPriceCents = 3000L,
        stock = 10L,
        imageUri = null,
        isActive = true,
        createdAtEpochMillis = 2000L,
        updatedAtEpochMillis = updatedAt,
        deletedAtEpochMillis = null,
    )

    @Test
    fun `exact resolution with single product and zero initial payment`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct()
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
            newConfirmationKey = { "key-123" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón Corporal", quantity = 2, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals("key-123", preview.confirmationKey)
        assertEquals(customer.id, preview.customerId)
        assertEquals("María López", preview.customerName)
        assertEquals(10000L, preview.totalCents)
        assertEquals(0L, preview.initialPaymentCents)
        assertEquals(10000L, preview.remainderCents)
        assertEquals(1, preview.lines.size)
        assertEquals(product.id, preview.lines[0].productId)
        assertEquals(2, preview.lines[0].quantity)
        assertEquals(5000L, preview.lines[0].unitPriceCents)
        assertEquals(10000L, preview.lines[0].lineTotalCents)
    }

    @Test
    fun `exact resolution with multiple products and partial initial payment`() = runBlocking {
        val customer = testCustomer()
        val prod1 = testProduct(id = "prod-1", name = "Jabón", salePriceCents = 5000L)
        val prod2 = testProduct(id = "prod-2", name = "Shampoo", salePriceCents = 8000L)
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, query ->
                if (query.contains("Jabón", ignoreCase = true)) listOf(prod1) else listOf(prod2)
            },
            newConfirmationKey = { "key-456" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 2, unitPriceCents = 5000L),
                AiProposalSaleLine("Shampoo", quantity = 1, unitPriceCents = 8000L),
            ),
            declaredInitialPaymentCents = 4000L,
            promisedPaymentCandidate = PromisedPaymentCandidate.DateCandidate(2026, 9, 15),
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals(18000L, preview.totalCents)
        assertEquals(4000L, preview.initialPaymentCents)
        assertEquals(14000L, preview.remainderCents)
        assertEquals(2, preview.lines.size)
        assertEquals(PromisedPaymentCandidate.DateCandidate(2026, 9, 15), preview.promisedPaymentCandidate)
    }

    @Test
    fun `missing customer returns CustomerNotFound`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> listOf(testProduct()) },
        )

        val proposal = CreateSaleProposal(
            customerName = "Desconocido",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.CustomerNotFound, result)
    }

    @Test
    fun `ambiguous customer returns CustomerSelectionRequired with candidates`() = runBlocking {
        val c1 = testCustomer(id = "c1", name = "María Gómez", normalizedName = "maria gomez")
        val c2 = testCustomer(id = "c2", name = "María Torres", normalizedName = "maria torres")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(c1, c2) },
            searchProducts = { _, _ -> listOf(testProduct()) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be CustomerSelectionRequired", result is AiSalePreviewResult.CustomerSelectionRequired)
        val selection = result as AiSalePreviewResult.CustomerSelectionRequired
        assertEquals(2, selection.candidates.size)
        assertEquals("c1", selection.candidates[0].customerId)
        assertEquals("c2", selection.candidates[1].customerId)
    }

    @Test
    fun `missing product returns ProductNotFound with product name`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> emptyList() },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Producto Inexistente", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be ProductNotFound", result is AiSalePreviewResult.ProductNotFound)
        assertEquals("Producto Inexistente", (result as AiSalePreviewResult.ProductNotFound).productName)
    }

    @Test
    fun `ambiguous product returns ProductSelectionRequired with candidate list`() = runBlocking {
        val p1 = testProduct(id = "p1", name = "Jabón Líquido")
        val p2 = testProduct(id = "p2", name = "Jabón en Barra")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> listOf(p1, p2) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be ProductSelectionRequired", result is AiSalePreviewResult.ProductSelectionRequired)
        val selection = result as AiSalePreviewResult.ProductSelectionRequired
        assertEquals("Jabón", selection.productName)
        assertEquals(2, selection.candidates.size)
    }

    @Test
    fun `repeated product lines resolve correctly and accumulate in total`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(id = "prod-1", name = "Jabón", salePriceCents = 5000L)
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
            newConfirmationKey = { "key-repeat" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L),
                AiProposalSaleLine("Jabón", quantity = 3, unitPriceCents = 5000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals(20000L, preview.totalCents)
        assertEquals(2, preview.lines.size)
    }

    @Test
    fun `overpayment blocks with INITIAL_PAYMENT_EXCEEDS_TOTAL`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón", salePriceCents = 5000L)
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 7000L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be Blocked", result is AiSalePreviewResult.Blocked)
        val blocked = result as AiSalePreviewResult.Blocked
        assertEquals(AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL, blocked.reason)
    }

    @Test
    fun `arithmetic overflow fails closed with Blocked ARITHMETIC_OVERFLOW or Unavailable`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón", salePriceCents = Long.MAX_VALUE)
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 2, unitPriceCents = Long.MAX_VALUE / 2 + 1000L),
                AiProposalSaleLine("Jabón", quantity = 2, unitPriceCents = Long.MAX_VALUE / 2 + 1000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("overflow must block or unavailable", result is AiSalePreviewResult.Blocked || result is AiSalePreviewResult.Unavailable)
        if (result is AiSalePreviewResult.Blocked) {
            assertEquals(AiSaleBlockReason.ARITHMETIC_OVERFLOW, result.reason)
        }
    }

    @Test
    fun `wrong-business customer candidates are filtered out`() = runBlocking {
        val wrongCustomer = testCustomer(id = "c-wrong", businessId = "other-biz")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(wrongCustomer) },
            searchProducts = { _, _ -> listOf(testProduct(name = "Jabón")) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.CustomerNotFound, result)
    }

    @Test
    fun `wrong-business product candidates are filtered out`() = runBlocking {
        val customer = testCustomer()
        val wrongProduct = testProduct(id = "p-wrong", businessId = "other-biz", name = "Jabón")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(wrongProduct) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("product must not be found", result is AiSalePreviewResult.ProductNotFound)
    }

    @Test
    fun `resolved preview lines collection is truly unmodifiable`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
            newConfirmationKey = { "key-immutable" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal) as AiSalePreviewResult.Ready
        val lines = result.preview.lines

        var threw = false
        try {
            (lines as MutableList<AiResolvedSaleLine>).clear()
        } catch (_: UnsupportedOperationException) {
            threw = true
        } catch (_: ClassCastException) {
            threw = true
        }
        assertTrue("lines collection must be unmodifiable", threw)
    }
}
