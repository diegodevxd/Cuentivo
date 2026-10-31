package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AuditLogEntity)

    @Query("SELECT * FROM audit_logs WHERE businessId = :businessId ORDER BY timestampEpochMillis DESC")
    suspend fun forBusiness(businessId: String): List<AuditLogEntity>
}
