package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OperationDao {
    @Query("SELECT * FROM operations WHERE idempotencyKey = :key")
    suspend fun findByKey(key: String): OperationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(operation: OperationEntity): Long

    @Query("SELECT resultId FROM operations WHERE idempotencyKey = :key")
    suspend fun resultIdFor(key: String): String?
}
