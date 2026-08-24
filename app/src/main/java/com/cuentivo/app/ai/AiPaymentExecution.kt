package com.cuentivo.app.ai

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.ExpectedBalancePaymentResult
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.StoredPaymentBundle
import com.cuentivo.app.domain.PaymentOperationIds
import kotlinx.coroutines.CancellationException

data class AiPaymentConfirmationRequest(
    val confirmationKey: String,
    val businessId: String,
    val customerId: String,
    val expectedBalanceCents: Long,
    val amountCents: Long,
)

sealed interface AiPaymentExecutionResult {
    data class Recorded(val paymentId: String) : AiPaymentExecutionResult
    data object StalePreview : AiPaymentExecutionResult
    data object Rejected : AiPaymentExecutionResult
    data object Unavailable : AiPaymentExecutionResult
}

class AiPaymentExecutionService(
    private val registerPayment: suspend (
        idempotencyKey: String,
        payment: PaymentEntity,
        movement: AccountMovementEntity,
        expectedBalanceCents: Long,
    ) -> ExpectedBalancePaymentResult,
    private val loadStoredBundle: suspend (businessId: String, paymentId: String) -> StoredPaymentBundle?,
    private val enqueueStoredBundle: suspend (StoredPaymentBundle) -> Unit,
    private val currentUserId: () -> String,
    private val nowMillis: () -> Long,
    private val todayEpochDay: () -> Long,
) {
    suspend fun confirm(request: AiPaymentConfirmationRequest): AiPaymentExecutionResult {
        if (request.confirmationKey.isBlank() ||
            request.businessId.isBlank() ||
            request.customerId.isBlank() ||
            request.expectedBalanceCents <= 0L ||
            request.amountCents <= 0L ||
            request.amountCents > request.expectedBalanceCents
        ) {
            return AiPaymentExecutionResult.Rejected
        }

        val userId = currentUserId().trim()
        if (userId.isBlank()) {
            return AiPaymentExecutionResult.Unavailable
        }

        return try {
            val key = request.confirmationKey.trim()
            val now = nowMillis()
            val today = todayEpochDay()
            val paymentId = PaymentOperationIds.payment(key)
            val movementId = PaymentOperationIds.movement(key)
            val idempotencyKey = "ai-payment-$key"

            val payment = PaymentEntity(
                id = paymentId,
                businessId = request.businessId,
                customerId = request.customerId,
                amountCents = request.amountCents,
                paymentMethod = "CASH",
                paymentDateEpochDay = today,
                reference = null,
                notes = null,
                createdByUserId = userId,
                createdAtEpochMillis = now,
                canceledAtEpochMillis = null,
            )

            val movement = AccountMovementEntity(
                id = movementId,
                businessId = request.businessId,
                customerId = request.customerId,
                saleId = null,
                paymentId = paymentId,
                type = "PAYMENT",
                amountCents = request.amountCents,
                direction = "CREDIT",
                occurredAtEpochMillis = now,
                createdByUserId = userId,
                reversesMovementId = null,
                notes = null,
            )

            val result = registerPayment(
                idempotencyKey,
                payment,
                movement,
                request.expectedBalanceCents,
            )

            when (result) {
                is ExpectedBalancePaymentResult.StaleBalance -> AiPaymentExecutionResult.StalePreview
                is ExpectedBalancePaymentResult.Recorded -> {
                    val bundle = loadStoredBundle(request.businessId, result.paymentId)
                    if (bundle != null) {
                        enqueueStoredBundle(bundle)
                    }
                    AiPaymentExecutionResult.Recorded(result.paymentId)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            AiPaymentExecutionResult.Unavailable
        }
    }
}
