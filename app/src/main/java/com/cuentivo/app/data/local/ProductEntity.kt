package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val description: String?,
    val sku: String?,
    val category: String?,
    val salePriceCents: Long,
    val costPriceCents: Long?,
    val stock: Long?,
    val imageUri: String?,
    val isActive: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
)
