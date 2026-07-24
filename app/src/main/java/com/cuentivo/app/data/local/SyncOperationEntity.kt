package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_operations")
data class SyncOperationEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val createdAtEpochMillis: Long,
    val attemptCount: Int,
    val lastError: String?,
)
