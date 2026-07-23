package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.FinancialDao
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.OperationEntity

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
}
