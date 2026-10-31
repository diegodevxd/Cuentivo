package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "payments")
data class PaymentEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val customerId: String,
    val amountCents: Long,
    val paymentMethod: String,
    val paymentDateEpochDay: Long,
    val reference: String?,
    val notes: String?,
    val createdByUserId: String,
    val createdAtEpochMillis: Long,
    val canceledAtEpochMillis: Long?,
)

@Entity(tableName = "payment_allocations")
data class PaymentAllocationEntity(
    @PrimaryKey val id: String,
    val paymentId: String,
    val saleId: String,
    val amountCents: Long,
)

@Entity(tableName = "account_movements")
data class AccountMovementEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val customerId: String,
    val saleId: String?,
    val paymentId: String?,
    val type: String,
    val amountCents: Long,
    val direction: String,
    val occurredAtEpochMillis: Long,
    val createdByUserId: String,
    val reversesMovementId: String?,
    val notes: String?,
)
