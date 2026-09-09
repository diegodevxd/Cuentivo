package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.data.local.AiLedgerImportMetricEntity
import com.cuentivo.app.data.local.AiLedgerImportStatus
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes the local quality/cost metric of one ledger page import, in two stages.
 *
 * WHY TWO STAGES, AND NOT "WRITE IT AT THE END WRAPPED IN runCatching":
 * that protects the registration but not the metric. Between confirming the batch and writing
 * the row there is a window where the process can die; launching the write from Compose's scope
 * and closing the screen right away CANCELS it; and an Activity recreation is not an
 * abandonment, but a retried confirmation could write the metric twice. So:
 *
 * 1. The moment the extraction arrives, an IN_PROGRESS row is written, with an id derived from
 *    the import session — not random per attempt, or a retry creates a second row.
 * 2. When the import ends, that same row is UPDATED to REGISTERED or ABANDONED, idempotently:
 *    writing it twice leaves the same result.
 *
 * An import that dies halfway leaves a trace that it existed — exactly the case worth measuring
 * — and no import is ever counted twice.
 *
 * Every write is wrapped in runCatching, like the outbox enqueue and for the same reason: a
 * metric that fails must never make the registration itself fail. Retention runs inside the same
 * write, so it costs no scheduling and cannot be forgotten.
 */
object LedgerImportMetricRecorder {

    /** Rows older than this are deleted on every write. See the class comment of the entity. */
    const val RETENTION_DAYS = 90L

    /**
     * Stale threshold: an IN_PROGRESS import row older than 2 hours is considered abandoned/unclosed
     * by process death or abnormal termination.
     */
    const val STALE_THRESHOLD_HOURS = 2L

    /**
     * The stable id of an import: SHA-256 of the session id. The session id is already a random
     * UUID per import, so this is opaque, stable across retries and recreations, and carries no
     * information about the page or the business.
     */
    fun importIdFor(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Stage 1: the extraction arrived, the review is open. Writes the IN_PROGRESS row with the
     * counts that are already final (detected, unreadable, duplicates, contract version, call
     * cost) so an import that never finishes still left its trace.
     */
    suspend fun onExtractionArrived(
        dao: com.cuentivo.app.data.local.AiLedgerImportMetricDao,
        sessionId: String,
        detectedCount: Int,
        unreadableCount: Int,
        duplicateSuggestedCount: Int,
        parserExcludedCount: Int,
        geminiCalls: Int,
        geminiDurationMillis: Long,
        contractVersion: Int,
        nowEpochMillis: Long,
    ) {
        runCatching {
            dao.upsert(
                AiLedgerImportMetricEntity(
                    importId = importIdFor(sessionId),
                    finishedAtEpochHour = hourRounded(nowEpochMillis),
                    status = AiLedgerImportStatus.IN_PROGRESS,
                    detectedCount = detectedCount,
                    readyUntouchedCount = 0,
                    correctedCount = 0,
                    discardedCount = 0,
                    unreadableCount = unreadableCount,
                    duplicateSuggestedCount = duplicateSuggestedCount,
                    parserExcludedCount = parserExcludedCount,
                    customerCorrections = 0,
                    productCorrections = 0,
                    amountCorrections = 0,
                    dateCorrections = 0,
                    kindCorrections = 0,
                    geminiCalls = geminiCalls,
                    geminiDurationMillis = geminiDurationMillis,
                    contractVersion = contractVersion,
                    highConfidenceFields = 0,
                    highConfidenceCorrected = 0,
                    midConfidenceFields = 0,
                    midConfidenceCorrected = 0,
                    lowConfidenceFields = 0,
                    lowConfidenceCorrected = 0,
                ),
            )
            applyRetention(dao, nowEpochMillis)
            sweepStaleInProgress(dao, nowEpochMillis)
        }
    }

    /**
     * Stage 2: the import ended. Updates the same row — same id, so a retried confirmation
     * overwrites rather than duplicates — with the final counts and the outcome.
     *
     * The call-cost fields (calls, duration, contract version) are taken from the IN_PROGRESS
     * row stage 1 wrote: they belong to the extraction, not to the ending, and the ending may
     * happen in a different process life than the extraction.
     *
     * An Activity recreation is NOT an abandonment: the review survives it, and counting one
     * there would measure a defect that no longer exists. Only a person discarding the page or
     * closing the lab ends an import as ABANDONED.
     *
     * [duplicateSuggestedCount] and [parserExcludedCount] are mandatory (no default) to prevent callers
     * from silently defaulting to 0 and losing critical quality/friction signals.
     */
    suspend fun onImportEnded(
        dao: com.cuentivo.app.data.local.AiLedgerImportMetricDao,
        sessionId: String,
        registered: Boolean,
        originalOperations: List<LedgerOperation>,
        finalReview: LedgerReview,
        duplicateSuggestedCount: Int,
        parserExcludedCount: Int,
        nowEpochMillis: Long,
    ) {
        runCatching {
            val existing = dao.forId(importIdFor(sessionId))
            val counting = LedgerCorrectionCounter.count(
                original = originalOperations,
                final = finalReview.operations,
            )
            val histogram = LedgerCorrectionCounter.histogram(
                original = originalOperations,
                final = finalReview.operations,
            )
            val finalOperations = finalReview.operations
            val originalByLineIndex = originalOperations.associateBy { it.lineIndex }
            val deletedRows = originalByLineIndex.keys - finalOperations.map { it.lineIndex }.toSet()

            // Ready-untouched: registrable, no financial diagnostic, and not one the person
            // corrected. A row the person fixed is a win for the review, not for the model.
            val readyUntouched = finalOperations.count { op ->
                isRegisterableLedgerOperation(op) &&
                    op.lineIndex !in deletedRows &&
                    originalByLineIndex[op.lineIndex]?.let { original ->
                        !rowCorrected(original, op)
                    } == true
            }

            // METRIC DEFINITIONS:
            // - detectedCount represents the total candidate operations detected upon page arrival (originalOperations.size).
            // - parserExcludedCount represents non-operations dropped during JSON/payload parsing (finalReview.summary.excludedRowsCount).
            //   Preserving this aggregate ensures baseline metrics can distinguish "few rows in sheet" from "parser dropped rows".
            // - discardedCount represents candidate operations manually deleted by the user during review (counting.rowsDeleted).
            // Note: On screen, the review header initially shows detectedCount ("X detectadas"), but dynamically reflects
            // current remaining operations (review.operations.size = detectedCount - discardedCount) if the user deletes rows.
            val detectedCount = originalOperations.size
            val discardedCount = counting.rowsDeleted

            dao.upsert(
                AiLedgerImportMetricEntity(
                    importId = importIdFor(sessionId),
                    finishedAtEpochHour = hourRounded(nowEpochMillis),
                    status = if (registered) AiLedgerImportStatus.REGISTERED else AiLedgerImportStatus.ABANDONED,
                    detectedCount = detectedCount,
                    readyUntouchedCount = readyUntouched,
                    correctedCount = counting.rowsCorrected,
                    discardedCount = discardedCount,
                    unreadableCount = finalOperations.count { it.unreadableFields.isNotEmpty() },
                    duplicateSuggestedCount = duplicateSuggestedCount,
                    parserExcludedCount = parserExcludedCount,
                    customerCorrections = counting.corrections.customer,
                    productCorrections = counting.corrections.product,
                    amountCorrections = counting.corrections.amount,
                    dateCorrections = counting.corrections.date,
                    kindCorrections = counting.corrections.kind,
                    geminiCalls = existing?.geminiCalls ?: 0,
                    geminiDurationMillis = existing?.geminiDurationMillis ?: 0L,
                    contractVersion = existing?.contractVersion ?: 0,
                    highConfidenceFields = histogram.highFields,
                    highConfidenceCorrected = histogram.highCorrected,
                    midConfidenceFields = histogram.midFields,
                    midConfidenceCorrected = histogram.midCorrected,
                    lowConfidenceFields = histogram.lowFields,
                    lowConfidenceCorrected = histogram.lowCorrected,
                ),
            )
            applyRetention(dao, nowEpochMillis)
            sweepStaleInProgress(dao, nowEpochMillis)
        }
    }

    private fun rowCorrected(original: LedgerOperation, final: LedgerOperation): Boolean {
        val result = LedgerCorrectionCounter.count(listOf(original), listOf(final))
        return result.corrections.total > 0
    }

    /**
     * The hour, rounded down to the hour. An exact timestamp is a join key against the sales
     * created at that instant; the hour is enough to compare mornings against afternoons and
     * useless for joining.
     */
    internal fun hourRounded(nowEpochMillis: Long): Long =
        Math.floorDiv(nowEpochMillis, 3_600_000L) * 3_600_000L

    private suspend fun applyRetention(
        dao: com.cuentivo.app.data.local.AiLedgerImportMetricDao,
        nowEpochMillis: Long,
    ) {
        val cutoff = hourRounded(nowEpochMillis) - RETENTION_DAYS * 24L * 3_600_000L
        dao.deleteOlderThan(cutoff)
    }

    /**
     * Sweeps orphaned IN_PROGRESS rows older than [staleThresholdHours], marking them as STALE.
     *
     * Runs during normal metric writes and app startup so dead processes leave clean metrics.
     *
     * SEMANTICS:
     * STALE means "outcome unknown / unclosed" (process death, background kill), NOT "failed".
     *
     * BOUNDARY GUARANTEE:
     * Because `finishedAtEpochHour` is truncated DOWN to the hour, the DAO query uses strict `< :cutoffEpochHour`
     * with `cutoff = hourRounded(now) - thresholdHours`. This ensures that an import started at the end of
     * an hour (e.g. 12:59 -> 12:00) is NEVER swept when elapsed time is less than the nominal threshold
     * (e.g. at 14:01 cutoff is 12:00, 12:00 < 12:00 is false, so it survives).
     */
    suspend fun sweepStaleInProgress(
        dao: com.cuentivo.app.data.local.AiLedgerImportMetricDao,
        nowEpochMillis: Long,
        staleThresholdHours: Long = STALE_THRESHOLD_HOURS,
    ): Int {
        return runCatching {
            val cutoff = hourRounded(nowEpochMillis) - staleThresholdHours * 3_600_000L
            dao.markStaleInProgress(cutoff, AiLedgerImportStatus.STALE)
        }.getOrDefault(0)
    }
}
