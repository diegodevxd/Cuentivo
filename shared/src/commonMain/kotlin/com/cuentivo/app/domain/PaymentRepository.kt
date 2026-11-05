package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.ExpectedBalancePaymentResult
import com.cuentivo.app.data.local.FinancialDao
import com.cuentivo.app.data.local.OperationEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.StoredPaymentBundle

class PaymentRepository(private val financialDao: FinancialDao) {
    suspend fun register(idempotencyKey: String, payment: PaymentEntity, allocations: List<PaymentAllocationEntity>, movement: AccountMovementEntity): String {
        require(payment.amountCents > 0) { "Payment must be positive." }
        require(allocations.sumOf { it.amountCents } <= payment.amountCents)
        require(movement.type == "PAYMENT" && movement.direction == "CREDIT")
        return financialDao.registerPaymentIdempotently(
            operation = OperationEntity(idempotencyKey, "PAYMENT", payment.id, payment.createdAtEpochMillis),
            payment = payment,
            allocations = allocations,
            movement = movement,
        )
    }

    suspend fun registerAgainstExpectedBalance(
        idempotencyKey: String,
        payment: PaymentEntity,
        movement: AccountMovementEntity,
        expectedBalanceCents: Long,
    ): ExpectedBalancePaymentResult =
        financialDao.registerPaymentAgainstExpectedBalanceIdempotently(
            OperationEntity(idempotencyKey, "PAYMENT", payment.id, payment.createdAtEpochMillis),
            payment,
            movement,
            expectedBalanceCents,
        )

    suspend fun storedBundle(businessId: String, paymentId: String): StoredPaymentBundle? {
        val payment = financialDao.paymentForId(paymentId) ?: return null
        if (payment.businessId != businessId) return null
        val movement = financialDao.paymentMovement(businessId, paymentId) ?: return null
        return StoredPaymentBundle(payment, financialDao.allocationsForPayment(paymentId), movement)
    }
}
