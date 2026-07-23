package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerUserId: String,
    val currency: String,
    val timezone: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "business_members")
data class BusinessMemberEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val userId: String,
    val role: String,
    val status: String,
    val invitedAtEpochMillis: Long?,
    val acceptedAtEpochMillis: Long?,
)
