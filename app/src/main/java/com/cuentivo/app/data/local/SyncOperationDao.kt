package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations WHERE lastError IS NULL OR lastError NOT LIKE 'PERMANENT:%' ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOperationEntity>

    @Query("DELETE FROM sync_operations WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE sync_operations SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun markFailure(id: String, error: String)

    /**
     * Clears the PERMANENT marker so parked rows become visible to [pending] again.
     * Only meaningful once the condition that caused the rejection is fixed, such as
     * publishing the owner membership the Firestore rules require.
     */
    @Query("UPDATE sync_operations SET lastError = NULL WHERE lastError LIKE 'PERMANENT:%'")
    suspend fun reopenParkedOperations()

    /**
     * Parks rows that have burned through [maxAttempts] retries.
     *
     * Without a cap a row classified as transient retries on every network change for the life of
     * the install. Parking is not deletion: the payload stays in the table for inspection, and
     * [reopenParkedOperations] can put it back in flight once the cause is understood.
     */
    @Query(
        "UPDATE sync_operations SET lastError = 'PERMANENT:gave up after ' || attemptCount || ' attempts (' || COALESCE(lastError, 'unknown') || ')' " +
            "WHERE attemptCount >= :maxAttempts AND (lastError IS NULL OR lastError NOT LIKE 'PERMANENT:%')"
    )
    suspend fun parkExhausted(maxAttempts: Int)
}
