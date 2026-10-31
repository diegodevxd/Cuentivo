package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * High-water mark for the remote pull, one row per business.
 *
 * [lastPulledAtEpochMillis] is the largest watermark timestamp already merged into Room. The next
 * pull asks Firestore only for documents strictly newer than this, so a device that has been
 * offline for a week does not re-download the entire business.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val businessId: String,
    val lastPulledAtEpochMillis: Long,
)

@Dao
interface SyncStateDao {
    @Query("SELECT lastPulledAtEpochMillis FROM sync_state WHERE businessId = :businessId")
    suspend fun lastPulledAt(businessId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: SyncStateEntity)
}
