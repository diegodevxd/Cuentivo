package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sales")
data class SaleEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val customerId: String,
    val sellerUserId: String,
    val saleNumber: String,
    val saleDateEpochDay: Long,
    val dueDateEpochDay: Long?,
    val subtotalCents: Long,
    val discountCents: Long,
    val totalCents: Long,
    val isCanceled: Boolean,
    val notes: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "sale_items")
data class SaleItemEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: String?,
    val productNameSnapshot: String,
    val quantity: Long,
    val unitPriceCents: Long,
    val discountCents: Long,
    val subtotalCents: Long,
)
