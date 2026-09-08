package com.cuentivo.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per ledger page import. Local quality and cost metrics only.
 *
 * WHAT IS NOT HERE, ON PURPOSE, AND MUST NEVER BE ADDED:
 * no photo, no base64, no rawText, no customer or product names, no amounts, no customerId, no
 * businessId — that one alone identifies a person — and no before/after text of any correction.
 * A row-per-line with its corrected field is the notebook, reconstructible; this table is counts.
 *
 * DO NOT add "original amount vs corrected amount" to calibrate better. It sounds reasonable and
 * it is exactly the notebook. If that precision is ever needed, decide again with this comment
 * in front of you.
 *
 * `finishedAtEpochHour` is ROUNDED TO THE HOUR, not an exact instant: an exact timestamp is a
 * join key against the sales and movements created at that same moment — same database — and
 * from there the person and the amount are recoverable. Rounding to the hour is enough to tell
 * whether afternoon imports come out worse than morning ones, and useless for joining.
 *
 * Confidence is stored as an AGGREGATED histogram, never as per-row pairs: a pair per row, in
 * order, is a fingerprint of the page even without a single name on it.
 */
@Entity(tableName = "ai_ledger_import_metrics")
data class AiLedgerImportMetricEntity(
    @PrimaryKey val importId: String,
    /** Hour-rounded finish time. See the class comment for why it is not an exact instant. */
    val finishedAtEpochHour: Long,
    /** IN_PROGRESS while the review is open; REGISTERED or ABANDONED once it ends. */
    val status: String,
    val detectedCount: Int,
    val readyUntouchedCount: Int,
    val correctedCount: Int,
    val discardedCount: Int,
    val unreadableCount: Int,
    val duplicateSuggestedCount: Int,
    val customerCorrections: Int,
    val productCorrections: Int,
    val amountCorrections: Int,
    val dateCorrections: Int,
    val kindCorrections: Int,
    val geminiCalls: Int,
    val geminiDurationMillis: Long,
    val contractVersion: Int,
    /** Confidence bucket >= 0.90: how many fields fell here, and how many of those were corrected. */
    val highConfidenceFields: Int,
    val highConfidenceCorrected: Int,
    /** Confidence bucket 0.70-0.89. */
    val midConfidenceFields: Int,
    val midConfidenceCorrected: Int,
    /** Confidence bucket < 0.70. */
    val lowConfidenceFields: Int,
    val lowConfidenceCorrected: Int,
)

/** How an import ended. The three values this table can hold. */
object AiLedgerImportStatus {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val REGISTERED = "REGISTERED"
    const val ABANDONED = "ABANDONED"
}

@Dao
interface AiLedgerImportMetricDao {

    /**
     * Idempotent by primary key: the in-progress row and its final update both use the same
     * stable id, so a retried confirmation cannot create a second row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metric: AiLedgerImportMetricEntity)

    @Query("SELECT * FROM ai_ledger_import_metrics WHERE importId = :importId")
    suspend fun forId(importId: String): AiLedgerImportMetricEntity?

    @Query("SELECT COUNT(*) FROM ai_ledger_import_metrics")
    suspend fun count(): Int

    /**
     * Retention: drops rows older than the cutoff. Called from the same write path that records
     * the metric, so it costs no extra scheduling and cannot be forgotten by a caller.
     */
    @Query("DELETE FROM ai_ledger_import_metrics WHERE finishedAtEpochHour < :cutoffEpochHour")
    suspend fun deleteOlderThan(cutoffEpochHour: Long): Int

    @Query("SELECT COUNT(*) FROM ai_ledger_import_metrics WHERE importId = :importId")
    suspend fun countForId(importId: String): Int
}
