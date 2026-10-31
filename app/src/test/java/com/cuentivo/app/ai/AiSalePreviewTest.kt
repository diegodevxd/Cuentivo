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
    fun `missing customer proposes NEW customer entity`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> listOf(testProduct()) },
            newConfirmationKey = { "key-new-cust" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "Desconocido",
            lines = listOf(AiProposalSaleLine("Jabón Corporal", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals("customer-desconocido", preview.customer.customerId)
        assertEquals("Desconocido", preview.customer.displayName)
        assertEquals(AiEntityResolutionMode.NEW, preview.customer.mode)
        assertEquals(null, preview.customer.sourceFingerprint)
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
    fun `missing product proposes NEW product entity`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-new-prod" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Producto Inexistente", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        val line = preview.lines[0]
        assertEquals("product-producto inexistente", line.productId)
        assertEquals("Producto Inexistente", line.productName)
        assertEquals(AiEntityResolutionMode.NEW, line.resolutionMode)
        assertEquals(null, line.sourceFingerprint)
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
    fun `declared unit price zero remains zero and does not substitute catalog price`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Muestra Gratis", salePriceCents = 8000L)
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
            newConfirmationKey = { "key-free" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Muestra Gratis", quantity = 3, unitPriceCents = 0L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals(0L, preview.totalCents)
        assertEquals(0L, preview.remainderCents)
        assertEquals(1, preview.lines.size)
        assertEquals(0L, preview.lines[0].unitPriceCents)
        assertEquals(0L, preview.lines[0].lineTotalCents)
    }

    @Test
    fun `quantity less than or equal to zero returns Blocked INVALID_LINE_QUANTITY`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 0, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_QUANTITY, null),
            result,
        )
    }

    @Test
    fun `negative unitPriceCents returns Blocked INVALID_LINE_PRICE`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = -500L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null),
            result,
        )
    }

    @Test
    fun `arithmetic overflow fails closed with exact Blocked ARITHMETIC_OVERFLOW`() = runBlocking {
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
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null),
            result,
        )
    }

    @Test
    fun `empty or blank confirmation key fails closed to Unavailable`() = runBlocking {
        val customer = testCustomer()
        val product = testProduct(name = "Jabón")
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
            newConfirmationKey = { "   " },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.Unavailable, result)
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
        assertTrue("result must be Ready with new customer", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals(AiEntityResolutionMode.NEW, preview.customer.mode)
        assertFalse("must not use wrong customer ID", preview.customer.customerId == "c-wrong")
        assertEquals(null, preview.customer.sourceFingerprint)
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
        assertTrue("result must be Ready with new product", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        val line = preview.lines[0]
        assertEquals(AiEntityResolutionMode.NEW, line.resolutionMode)
        assertFalse("must not use wrong product ID", line.productId == "p-wrong")
        assertEquals(null, line.sourceFingerprint)
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

    @Test
    fun `no candidates proposes new customer and new product with generated ID and null fingerprint`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "confirm-1" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "  Nuevo Cliente  ",
            lines = listOf(AiProposalSaleLine("  Producto Nuevo  ", quantity = 3, unitPriceCents = 4500L)),
            declaredInitialPaymentCents = 1000L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview

        assertEquals("confirm-1", preview.confirmationKey)
        assertEquals("customer-nuevo cliente", preview.customer.customerId)
        assertEquals("Nuevo Cliente", preview.customer.displayName)
        assertEquals("nuevo cliente", preview.customer.normalizedName)
        assertEquals(AiEntityResolutionMode.NEW, preview.customer.mode)
        assertEquals(null, preview.customer.sourceFingerprint)
        assertEquals("customer-nuevo cliente", preview.customerId)
        assertEquals("Nuevo Cliente", preview.customerName)

        assertEquals(1, preview.lines.size)
        val line = preview.lines[0]
        assertEquals("product-producto nuevo", line.productId)
        assertEquals("Producto Nuevo", line.productName)
        assertEquals(3, line.quantity)
        assertEquals(4500L, line.unitPriceCents)
        assertEquals(13500L, line.lineTotalCents)
        assertEquals(4500L, line.sourceDeclaredPriceCents)
        assertEquals(null, line.sourceFingerprint)
        assertEquals(AiEntityResolutionMode.NEW, line.resolutionMode)
        assertEquals(13500L, preview.totalCents)
        assertEquals(1000L, preview.initialPaymentCents)
        assertEquals(12500L, preview.remainderCents)
    }

    @Test
    fun `mixed existing and new products resolve with appropriate modes`() = runBlocking {
        val existingCustomer = testCustomer(id = "cust-1", name = "María López", normalizedName = "maria lopez", updatedAt = 5000L)
        val existingProduct = testProduct(id = "prod-1", name = "Jabón", salePriceCents = 5000L, updatedAt = 6000L)

        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(existingCustomer) },
            searchProducts = { _, query ->
                if (query.contains("Jabón", ignoreCase = true)) listOf(existingProduct) else emptyList()
            },
            newConfirmationKey = { "confirm-mixed" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L),
                AiProposalSaleLine("Esponja", quantity = 2, unitPriceCents = 1500L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview

        assertEquals(AiEntityResolutionMode.EXISTING, preview.customer.mode)
        assertEquals("cust-1", preview.customer.customerId)
        assertEquals("cust-1:5000", preview.customer.sourceFingerprint)

        assertEquals(2, preview.lines.size)
        val line1 = preview.lines[0]
        assertEquals("prod-1", line1.productId)
        assertEquals(AiEntityResolutionMode.EXISTING, line1.resolutionMode)
        assertEquals("prod-1:6000:5000", line1.sourceFingerprint)

        val line2 = preview.lines[1]
        assertEquals("product-esponja", line2.productId)
        assertEquals(AiEntityResolutionMode.NEW, line2.resolutionMode)
        assertEquals(null, line2.sourceFingerprint)
        assertEquals(1500L, line2.unitPriceCents)
        assertEquals(3000L, line2.lineTotalCents)
    }

    @Test
    fun `similar candidates never auto-create without explicit choice`() = runBlocking {
        val candidate1 = testCustomer(id = "cust-1", name = "María López Gómez", normalizedName = "maria lopez gomez")
        val candidate2 = testCustomer(id = "cust-2", name = "María López Pérez", normalizedName = "maria lopez perez")

        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(candidate1, candidate2) },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "confirm-1" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must require customer selection", result is AiSalePreviewResult.CustomerSelectionRequired)
        val required = result as AiSalePreviewResult.CustomerSelectionRequired
        assertEquals(2, required.candidates.size)
    }

    @Test
    fun `explicit USE_EXISTING choice uses real candidate and fingerprint`() = runBlocking {
        val candidate1 = testCustomer(id = "cust-1", name = "María López Gómez", normalizedName = "maria lopez gomez", updatedAt = 7000L)
        val candidate2 = testCustomer(id = "cust-2", name = "María López Pérez", normalizedName = "maria lopez perez", updatedAt = 8000L)
        val prodCandidate1 = testProduct(id = "prod-1", name = "Jabón Neutro", salePriceCents = 5000L, updatedAt = 9000L)
        val prodCandidate2 = testProduct(id = "prod-2", name = "Jabón Líquido", salePriceCents = 6000L, updatedAt = 9500L)

        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(candidate1, candidate2) },
            searchProducts = { _, _ -> listOf(prodCandidate1, prodCandidate2) },
            newConfirmationKey = { "confirm-use-existing" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 2, unitPriceCents = 6000L)),
            declaredInitialPaymentCents = 0L,
        )

        val choices = AiSaleResolutionChoices(
            customer = AiEntityChoice.UseExisting("cust-2"),
            productsByNormalizedName = mapOf("jabon" to AiEntityChoice.UseExisting("prod-2")),
        )

        val result = resolver.resolve("biz-1", proposal, choices)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview

        assertEquals("cust-2", preview.customer.customerId)
        assertEquals("María López Pérez", preview.customer.displayName)
        assertEquals(AiEntityResolutionMode.EXISTING, preview.customer.mode)
        assertEquals("cust-2:8000", preview.customer.sourceFingerprint)

        val line = preview.lines[0]
        assertEquals("prod-2", line.productId)
        assertEquals("Jabón Líquido", line.productName)
        assertEquals(AiEntityResolutionMode.EXISTING, line.resolutionMode)
        assertEquals("prod-2:9500:6000", line.sourceFingerprint)
    }

    @Test
    fun `explicit CREATE_NEW choice forces new entity even when candidates exist`() = runBlocking {
        val existingCustomer = testCustomer(id = "cust-1", name = "María López", normalizedName = "maria lopez", updatedAt = 7000L)
        val existingProduct = testProduct(id = "prod-1", name = "Jabón", salePriceCents = 5000L, updatedAt = 9000L)

        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(existingCustomer) },
            searchProducts = { _, _ -> listOf(existingProduct) },
            newConfirmationKey = { "confirm-create-new" },
            newEntityId = { kind, name -> "$kind-created-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val choices = AiSaleResolutionChoices(
            customer = AiEntityChoice.CreateNew,
            productsByNormalizedName = mapOf("jabon" to AiEntityChoice.CreateNew),
        )

        val result = resolver.resolve("biz-1", proposal, choices)
        assertTrue("result must be Ready", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview

        assertEquals("customer-created-maria lopez", preview.customer.customerId)
        assertEquals(AiEntityResolutionMode.NEW, preview.customer.mode)
        assertEquals(null, preview.customer.sourceFingerprint)

        val line = preview.lines[0]
        assertEquals("product-created-jabon", line.productId)
        assertEquals(AiEntityResolutionMode.NEW, line.resolutionMode)
        assertEquals(null, line.sourceFingerprint)
    }

    @Test
    fun `explicit USE_EXISTING with non-existent or wrong-business ID returns Unavailable`() = runBlocking {
        val customer = testCustomer(id = "cust-1", businessId = "biz-1", name = "María López")
        val product = testProduct(id = "prod-1", businessId = "biz-1", name = "Jabón")

        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(customer) },
            searchProducts = { _, _ -> listOf(product) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val badCustomerChoices = AiSaleResolutionChoices(customer = AiEntityChoice.UseExisting("cust-nonexistent"))
        assertEquals(AiSalePreviewResult.Unavailable, resolver.resolve("biz-1", proposal, badCustomerChoices))

        val badProductChoices = AiSaleResolutionChoices(
            productsByNormalizedName = mapOf("jabon" to AiEntityChoice.UseExisting("prod-nonexistent")),
        )
        assertEquals(AiSalePreviewResult.Unavailable, resolver.resolve("biz-1", proposal, badProductChoices))
    }

    @Test
    fun `blank generated entity ID fails closed with Unavailable`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newEntityId = { _, _ -> "   " },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.Unavailable, result)
    }

    @Test
    fun `AiSaleResolutionChoices defensive copy protects against mutation of input map`() {
        val mutableProducts = mutableMapOf<String, AiEntityChoice>("jabon" to AiEntityChoice.CreateNew)
        val choices = AiSaleResolutionChoices(
            customer = AiEntityChoice.CreateNew,
            productsByNormalizedName = mutableProducts,
        )
        mutableProducts["shampoo"] = AiEntityChoice.CreateNew
        mutableProducts["jabon"] = AiEntityChoice.UseExisting("prod-mutated")

        assertEquals(1, choices.productsByNormalizedName.size)
        assertEquals(AiEntityChoice.CreateNew, choices.productsByNormalizedName["jabon"])
        assertFalse(choices.productsByNormalizedName.containsKey("shampoo"))
    }

    @Test
    fun `customerName normalizing to empty string fails closed with Unavailable`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
        )

        val proposal = CreateSaleProposal(
            customerName = "   \u0300\u0301   ", // non-blank before normalization, but normalizes to empty string
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.Unavailable, result)
    }

    @Test
    fun `productName normalizing to empty string fails closed with Unavailable`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("   \u0300\u0301   ", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.Unavailable, result)
    }

    @Test
    fun `inconsistent declared prices for same new product returns Blocked INCONSISTENT_NEW_PRODUCT_PRICE`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón Artesanal", quantity = 2, unitPriceCents = 5000L),
                AiProposalSaleLine("jabón artesanal", quantity = 1, unitPriceCents = 6000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.INCONSISTENT_NEW_PRODUCT_PRICE, null),
            result,
        )
    }

    @Test
    fun `consistent declared prices for same new product reuses product ID and succeeds`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
            newEntityId = { kind, name -> "$kind-${com.cuentivo.app.domain.CustomerNameNormalizer.key(name)}" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón Artesanal", quantity = 2, unitPriceCents = 5000L),
                AiProposalSaleLine("jabón artesanal", quantity = 1, unitPriceCents = 5000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue(result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals(2, preview.lines.size)
        assertEquals(preview.lines[0].productId, preview.lines[1].productId)
        assertEquals("product-jabon artesanal", preview.lines[0].productId)
        assertEquals(15000L, preview.totalCents)
    }

    @Test
    fun `colliding generated entity IDs within preview fails closed with Unavailable`() = runBlocking {
        var productGenerationCount = 0
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
            newEntityId = { kind, _ ->
                if (kind == "customer") {
                    "new-customer-id"
                } else {
                    productGenerationCount++
                    "colliding-product-id"
                }
            },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L),
                AiProposalSaleLine("Shampoo", quantity = 1, unitPriceCents = 8000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(AiSalePreviewResult.Unavailable, result)
        assertEquals(
            "must have generated product ID twice and failed on the second product collision",
            2,
            productGenerationCount,
        )
    }

    @Test
    fun `same generated ID for customer and product is permitted across distinct namespaces`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> emptyList() },
            searchProducts = { _, _ -> emptyList() },
            newConfirmationKey = { "key-1" },
            newEntityId = { _, _ -> "shared-id-123" },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(
                AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 5000L),
            ),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertTrue("must be Ready even when customer and product share the same ID", result is AiSalePreviewResult.Ready)
        val preview = (result as AiSalePreviewResult.Ready).preview
        assertEquals("shared-id-123", preview.customer.customerId)
        assertEquals("shared-id-123", preview.lines[0].productId)
    }

    @Test
    fun `CancellationException in searchCustomers propagates without catching`() {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> throw kotlinx.coroutines.CancellationException("test cancel customers") },
            searchProducts = { _, _ -> emptyList() },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        var caughtCancellation = false
        try {
            runBlocking {
                resolver.resolve("biz-1", proposal)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("test cancel customers", e.message)
            caughtCancellation = true
        }
        assertTrue("CancellationException from searchCustomers must propagate", caughtCancellation)
    }

    @Test
    fun `CancellationException in searchProducts propagates without catching`() {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> throw kotlinx.coroutines.CancellationException("test cancel products") },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 1000L)),
            declaredInitialPaymentCents = 0L,
        )

        var caughtCancellation = false
        try {
            runBlocking {
                resolver.resolve("biz-1", proposal)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("test cancel products", e.message)
            caughtCancellation = true
        }
        assertTrue("CancellationException from searchProducts must propagate", caughtCancellation)
    }

    @Test
    fun `new product via NotFound with unitPriceCents zero returns Blocked INVALID_LINE_PRICE`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> emptyList() },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Producto Nuevo", quantity = 1, unitPriceCents = 0L)),
            declaredInitialPaymentCents = 0L,
        )

        val result = resolver.resolve("biz-1", proposal)
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null),
            result,
        )
    }

    @Test
    fun `new product via CreateNew with unitPriceCents zero returns Blocked INVALID_LINE_PRICE`() = runBlocking {
        val resolver = AiSalePreviewResolver(
            searchCustomers = { _, _ -> listOf(testCustomer()) },
            searchProducts = { _, _ -> listOf(testProduct(name = "Jabón")) },
        )

        val proposal = CreateSaleProposal(
            customerName = "María López",
            lines = listOf(AiProposalSaleLine("Jabón", quantity = 1, unitPriceCents = 0L)),
            declaredInitialPaymentCents = 0L,
        )

        val choices = AiSaleResolutionChoices(
            productsByNormalizedName = mapOf("jabon" to AiEntityChoice.CreateNew),
        )

        val result = resolver.resolve("biz-1", proposal, choices)
        assertEquals(
            AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null),
            result,
        )
    }
}
