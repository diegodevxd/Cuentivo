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
 * IF THESE METRICS EVER LEAVE THE PHONE, the timestamp is dropped or bucketed AT THE EXPORT
 * BOUNDARY, never at the storage boundary. Same class of warning as the one above: what is safe
 * to keep on a device that already holds the ledger in the clear stops being safe the moment it
 * travels. Today nothing exports this table — it is not in `sync`, and Firestore rules have no
 * collection for it. That is a property to preserve, not an accident.
 *
 * `finishedAtEpochHour` is ROUNDED TO THE HOUR, not an exact instant. Be honest about what that
 * buys, because it is less than it looks: on a single-user phone an hour bucket is often unique,
 * so the row can still be associated with the sales and movements created in that same hour.
 *
 * The real guarantee is narrower, and it is this: THE ROW CARRIES NO PII, and the join grants no
 * access to anything that whoever holds this database does not already have in the clear — the
 * financial tables are right there, with names and amounts. What the join adds is the ability to
 * LABEL METRICS WITH PEOPLE, and that is the harm the rounding reduces without eliminating.
 *
 * The timestamp is kept because it is the AXIS OF THE MEASUREMENT, not for retention — retention
 * could be done by row count. Without knowing when a row was written there is no way to separate
 * the before and after populations, and separating them is the whole reason this table exists.
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
    /** Non-operations dropped/excluded by the parser during initial payload parsing (e.g. malformed or invalid). */
    val parserExcludedCount: Int,
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

/** How an import ended. The values this table can hold. */
object AiLedgerImportStatus {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val REGISTERED = "REGISTERED"
    const val ABANDONED = "ABANDONED"
    /**
     * Marked by sweep when an IN_PROGRESS row was left unclosed by process death, background kill,
     * or crash.
     *
     * IMPORTANT: STALE means "outcome unknown / unclosed", NOT "failed".
     * Baseline metric aggregations must exclude STALE rows when computing success vs abandonment
     * ratios so that unknown outcomes do not distort REGISTERED vs ABANDONED rates.
     */
    const val STALE = "STALE"
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

    /**
     * Sweeps orphaned IN_PROGRESS rows older than cutoff, marking them STALE so unclosed imports
     * from dead processes do not remain IN_PROGRESS forever or contaminate baseline metrics.
     *
     * Uses strict `<` comparison because finishedAtEpochHour is rounded DOWN to the hour.
     * With `< :cutoffEpochHour` where cutoff is `nowRounded - threshold`, an import started at
     * the end of an hour (e.g. 12:59 -> 12:00) will never be swept with less than the nominal
     * threshold of elapsed time (e.g. at 14:01 cutoff is 12:00, 12:00 < 12:00 is false, survives).
     */
    @Query("UPDATE ai_ledger_import_metrics SET status = :targetStatus WHERE status = 'IN_PROGRESS' AND finishedAtEpochHour < :cutoffEpochHour")
    suspend fun markStaleInProgress(cutoffEpochHour: Long, targetStatus: String = AiLedgerImportStatus.STALE): Int
}
