package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOperationEntity>

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE sync_operations SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun markFailure(id: String, error: String)
}
