package com.cuentivo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "operations")
data class OperationEntity(
    @PrimaryKey val idempotencyKey: String,
    val type: String,
    val resultId: String,
    val createdAtEpochMillis: Long,
)
