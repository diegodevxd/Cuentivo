package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    // A name is a search aid, never an identity. In particular an archived "Diego" must not
    // prevent creating another customer called Diego. Keep the index for lookups, but do not make
    // it unique: customer.id is the identity used by Room, Firestore and financial records.
    indices = [Index(value = ["businessId", "normalizedName"])],
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
