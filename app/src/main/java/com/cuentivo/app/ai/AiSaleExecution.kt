package com.cuentivo.app.ai

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedSaleResult
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.StoredSaleBundle
import com.cuentivo.app.domain.SaleOperationIds
import kotlinx.coroutines.CancellationException

data class AiNewCustomerSpec(
    val customerId: String,
    val displayName: String,
    val normalizedName: String,
    val explicitCreateNew: Boolean,
)

data class AiNewProductSpec(
    val productId: String,
    val name: String,
    val salePriceCents: Long,
    val explicitCreateNew: Boolean,
)

data class AiSaleConfirmationLine(
    val productId: String,
    val productNameSnapshot: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    val sourceProductFingerprint: String,
)

data class AiSaleConfirmationRequest(
    val confirmationKey: String,
    val businessId: String,
    val customerId: String,
    val sourceCustomerFingerprint: String,
    val lines: List<AiSaleConfirmationLine>,
    val totalCents: Long,
    val initialPaymentCents: Long,
    val promisedPaymentEpochDay: Long? = null,
    val newCustomer: AiNewCustomerSpec? = null,
    val newProducts: List<AiNewProductSpec> = emptyList(),
)

sealed interface AiSaleExecutionResult {
    data class Recorded(val saleId: String, val paymentId: String?) : AiSaleExecutionResult
    data object StalePreview : AiSaleExecutionResult
    data object Rejected : AiSaleExecutionResult
    data object Unavailable : AiSaleExecutionResult
}

class AiSaleExecutionService(
    private val registerSale: suspend (
        idempotencyKey: String,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        expectedCustomerFingerprint: String,
        expectedProductFingerprints: Map<String, String>,
        payment: PaymentEntity?,
        paymentMovement: AccountMovementEntity?,
        paymentAllocations: List<PaymentAllocationEntity>,
        expectedInitialPaymentId: String,
        newCustomer: CustomerEntity?,
        explicitCustomerCreateNew: Boolean,
        newProducts: List<ProductEntity>,
        explicitProductCreateNewByProductId: Map<String, Boolean>,
    ) -> ExpectedSaleResult,
    private val loadStoredBundle: (suspend (businessId: String, saleId: String, createdCustomerId: String?, createdProductIds: List<String>) -> StoredSaleBundle?)? = null,
    private val enqueueStoredBundle: (suspend (StoredSaleBundle) -> Unit)? = null,
    private val currentUserId: () -> String,
    private val nowMillis: () -> Long,
    private val todayEpochDay: () -> Long,
) {
    suspend fun confirm(request: AiSaleConfirmationRequest): AiSaleExecutionResult {
        if (request.confirmationKey.isBlank() ||
            request.businessId.isBlank() ||
            request.customerId.isBlank() ||
            request.lines.isEmpty() ||
            request.totalCents <= 0L ||
            request.initialPaymentCents < 0L ||
            request.initialPaymentCents > request.totalCents
        ) {
            return AiSaleExecutionResult.Rejected
        }

        // D3 cross-validation: Customer spec vs fingerprint
        if (request.newCustomer != null) {
            val spec = request.newCustomer
            if (spec.customerId != request.customerId ||
                request.sourceCustomerFingerprint.isNotEmpty() ||
                spec.customerId.isBlank() ||
                spec.displayName.isBlank() ||
                spec.normalizedName.isBlank()
            ) {
                return AiSaleExecutionResult.Rejected
            }
        } else {
            if (request.sourceCustomerFingerprint.isEmpty()) {
                return AiSaleExecutionResult.Rejected
            }
        }

        // D3 cross-validation: Product specs vs lines
        val newProductMap = HashMap<String, AiNewProductSpec>(request.newProducts.size)
        for (spec in request.newProducts) {
            if (spec.productId.isBlank() || spec.name.isBlank() || spec.salePriceCents <= 0L) {
                return AiSaleExecutionResult.Rejected
            }
            if (newProductMap.put(spec.productId, spec) != null) {
                return AiSaleExecutionResult.Rejected // duplicate spec
            }
        }

        val lineProductIds = HashSet<String>(request.lines.size)
        var calculatedTotal = 0L
        for (line in request.lines) {
            if (line.productId.isBlank() ||
                line.productNameSnapshot.isBlank() ||
                line.quantity <= 0 ||
                line.unitPriceCents < 0L ||
                line.lineTotalCents < 0L
            ) {
                return AiSaleExecutionResult.Rejected
            }
            lineProductIds.add(line.productId)

            if (line.sourceProductFingerprint.isEmpty()) {
                if (!newProductMap.containsKey(line.productId)) {
                    return AiSaleExecutionResult.Rejected
                }
            } else {
                if (newProductMap.containsKey(line.productId)) {
                    return AiSaleExecutionResult.Rejected
                }
            }

            val expectedLineTotal = try {
                Math.multiplyExact(line.quantity.toLong(), line.unitPriceCents)
            } catch (_: ArithmeticException) {
                return AiSaleExecutionResult.Rejected
            }
            if (line.lineTotalCents != expectedLineTotal) {
                return AiSaleExecutionResult.Rejected
            }
            calculatedTotal = try {
                Math.addExact(calculatedTotal, line.lineTotalCents)
            } catch (_: ArithmeticException) {
                return AiSaleExecutionResult.Rejected
            }
        }

        // Check for orphan product specs
        for (spec in request.newProducts) {
            if (!lineProductIds.contains(spec.productId)) {
                return AiSaleExecutionResult.Rejected
            }
        }

        if (calculatedTotal != request.totalCents) {
            return AiSaleExecutionResult.Rejected
        }

        val userId = currentUserId().trim()
        if (userId.isBlank()) {
            return AiSaleExecutionResult.Unavailable
        }

        return try {
            val key = request.confirmationKey.trim()
            val now = nowMillis()
            val today = todayEpochDay()
            val idempotencyKey = "ai-sale-$key"
            val saleId = SaleOperationIds.sale(idempotencyKey)
            val chargeId = SaleOperationIds.chargeMovement(idempotencyKey)

            val sale = SaleEntity(
                id = saleId,
                businessId = request.businessId,
                customerId = request.customerId,
                sellerUserId = userId,
                saleNumber = "V-$now",
                saleDateEpochDay = today,
                dueDateEpochDay = request.promisedPaymentEpochDay,
                subtotalCents = request.totalCents,
                discountCents = 0L,
                totalCents = request.totalCents,
                isCanceled = false,
                notes = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )

            val items = request.lines.mapIndexed { index, line ->
                SaleItemEntity(
                    id = SaleOperationIds.saleItem(idempotencyKey, line.productId, index),
                    saleId = saleId,
                    productId = line.productId,
                    productNameSnapshot = line.productNameSnapshot,
                    quantity = line.quantity.toLong(),
                    unitPriceCents = line.unitPriceCents,
                    discountCents = 0L,
                    subtotalCents = line.lineTotalCents,
                )
            }

            val charge = AccountMovementEntity(
                id = chargeId,
                businessId = request.businessId,
                customerId = request.customerId,
                saleId = saleId,
                paymentId = null,
                type = "CHARGE",
                amountCents = request.totalCents,
                direction = "DEBIT",
                occurredAtEpochMillis = now,
                createdByUserId = userId,
                reversesMovementId = null,
                notes = null,
            )

            val initialPaymentId = SaleOperationIds.payment(idempotencyKey)

            val payment: PaymentEntity?
            val paymentMovement: AccountMovementEntity?
            val paymentAllocations: List<PaymentAllocationEntity>

            if (request.initialPaymentCents > 0L) {
                val paymentMovementId = SaleOperationIds.paymentMovement(idempotencyKey)

                payment = PaymentEntity(
                    id = initialPaymentId,
                    businessId = request.businessId,
                    customerId = request.customerId,
                    amountCents = request.initialPaymentCents,
                    paymentMethod = "CASH",
                    paymentDateEpochDay = today,
                    reference = null,
                    notes = null,
                    createdByUserId = userId,
                    createdAtEpochMillis = now,
                    canceledAtEpochMillis = null,
                )

                paymentMovement = AccountMovementEntity(
                    id = paymentMovementId,
                    businessId = request.businessId,
                    customerId = request.customerId,
                    saleId = null,
                    paymentId = initialPaymentId,
                    type = "PAYMENT",
                    amountCents = request.initialPaymentCents,
                    direction = "CREDIT",
                    occurredAtEpochMillis = now,
                    createdByUserId = userId,
                    reversesMovementId = null,
                    notes = null,
                )

                paymentAllocations = listOf(
                    PaymentAllocationEntity(
                        id = SaleOperationIds.allocation(idempotencyKey, saleId),
                        paymentId = initialPaymentId,
                        saleId = saleId,
                        amountCents = request.initialPaymentCents,
                    ),
                )
            } else {
                payment = null
                paymentMovement = null
                paymentAllocations = emptyList()
            }

            val newCustomerEntity = request.newCustomer?.let { spec ->
                CustomerEntity(
                    id = spec.customerId,
                    businessId = request.businessId,
                    name = spec.displayName,
                    normalizedName = spec.normalizedName,
                    phone = null,
                    email = null,
                    address = null,
                    notes = null,
                    creditLimitCents = null,
                    isActive = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = null,
                )
            }

            val newProductEntities = request.newProducts.map { spec ->
                ProductEntity(
                    id = spec.productId,
                    businessId = request.businessId,
                    name = spec.name,
                    description = null,
                    sku = null,
                    category = null,
                    salePriceCents = spec.salePriceCents,
                    costPriceCents = null,
                    stock = null,
                    imageUri = null,
                    isActive = true,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    deletedAtEpochMillis = null,
                )
            }

            val explicitProductCreateNewMap = request.newProducts.associate { it.productId to it.explicitCreateNew }

            val result = registerSale(
                idempotencyKey,
                sale,
                items,
                charge,
                request.sourceCustomerFingerprint,
                request.lines.associate { it.productId to it.sourceProductFingerprint },
                payment,
                paymentMovement,
                paymentAllocations,
                initialPaymentId,
                newCustomerEntity,
                request.newCustomer?.explicitCreateNew ?: false,
                newProductEntities,
                explicitProductCreateNewMap,
            )

            when (result) {
                is ExpectedSaleResult.StaleData -> AiSaleExecutionResult.StalePreview
                is ExpectedSaleResult.Recorded -> {
                    val createdCustId = request.newCustomer?.customerId
                    val createdProdIds = request.newProducts.map { it.productId }
                    val bundle = loadStoredBundle?.invoke(request.businessId, result.saleId, createdCustId, createdProdIds)
                    if (bundle != null) {
                        enqueueStoredBundle?.invoke(bundle)
                    }
                    AiSaleExecutionResult.Recorded(result.saleId, result.paymentId)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            AiSaleExecutionResult.Unavailable
        }
    }
}
