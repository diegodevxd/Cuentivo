package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [Index(value = ["businessId", "normalizedName"], unique = true)],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val name: String,
    val normalizedName: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    val creditLimitCents: Long?,
    val isActive: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
)
