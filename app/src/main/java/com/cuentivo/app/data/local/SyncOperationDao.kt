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
     * Puts back in flight the rows parked on PERMISSION_DENIED that name a document nobody may
     * rewrite -- a sale, an item, a payment, an allocation, a movement, or the owner membership.
     *
     * They were parked by an outbox that could not tell "already published" apart from "rejected":
     * uploading with set() over a document that already exists is an update, and every one of those
     * collections is create-only, so re-sending a sale Firestore already holds is denied by design.
     * The gateway now resolves that case by asking whether the document is there, but a phone that
     * accumulated the rows under the old behaviour has them excluded from [pending] forever, and
     * nothing would ever look at them again.
     *
     * Bounded on purpose by [maxAttempts], the same budget [parkExhausted] enforces: a row that is
     * genuinely denied burns one attempt per pass and parks itself again for good. This cannot turn
     * into the retry loop it exists to end.
     */
    @Query(
        "UPDATE sync_operations SET lastError = NULL " +
            "WHERE lastError LIKE 'PERMANENT:PERMISSION_DENIED%' AND attemptCount < :maxAttempts " +
            "AND (entityType IN ('SALE', 'SALE_ITEM', 'PAYMENT', 'PAYMENT_ALLOCATION', 'MOVEMENT') " +
            "OR (entityType = 'MEMBER' AND payload LIKE '%\"role\":\"OWNER\"%'))"
    )
    suspend fun reopenSettledPermissionDenials(maxAttempts: Int)

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
