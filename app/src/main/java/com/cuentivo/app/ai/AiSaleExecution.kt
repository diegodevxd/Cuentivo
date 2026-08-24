package com.cuentivo.app.ai

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.ExpectedSaleResult
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.domain.SaleOperationIds
import kotlinx.coroutines.CancellationException

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
    ) -> ExpectedSaleResult,
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
            )

            when (result) {
                is ExpectedSaleResult.StaleData -> AiSaleExecutionResult.StalePreview
                is ExpectedSaleResult.Recorded -> AiSaleExecutionResult.Recorded(result.saleId, result.paymentId)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            AiSaleExecutionResult.Unavailable
        }
    }
}
