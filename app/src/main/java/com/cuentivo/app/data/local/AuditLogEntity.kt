package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val userId: String,
    val timestampEpochMillis: Long,
    val summary: String,
)
