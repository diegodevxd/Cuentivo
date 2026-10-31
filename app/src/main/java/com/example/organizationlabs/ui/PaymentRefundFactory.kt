package com.cuentivo.app.ui

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CancelablePaymentRow

object PaymentRefundFactory {
    fun create(
        row: CancelablePaymentRow,
        movementId: String,
        occurredAtEpochMillis: Long,
        createdByUserId: String,
    ): AccountMovementEntity = AccountMovementEntity(
        id = movementId,
        businessId = row.businessId,
        customerId = row.customerId,
        saleId = null,
        paymentId = row.id,
        type = "REFUND",
        amountCents = row.amountCents,
        direction = "DEBIT",
        occurredAtEpochMillis = occurredAtEpochMillis,
        createdByUserId = createdByUserId,
        reversesMovementId = null,
        notes = "Abono cancelado desde la app",
    )
}
