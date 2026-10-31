package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.AiLedgerImportMetricEntity
import com.cuentivo.app.data.local.AiLedgerImportStatus
import com.cuentivo.app.ui.ai.toImportEndParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.Field
import java.time.Instant

/**
 * The recorder is what stands between "we measure imports" and "we keep a copy of somebody's
 * notebook". The privacy promise Diego approved is checked here against the real row the
 * recorder builds, not against a list of intentions.
 */
class LedgerImportMetricRecorderTest {

    /** An in-memory fake of the DAO, exercising the real recorder against the real entity. */
    private class FakeMetricDao : com.cuentivo.app.data.local.AiLedgerImportMetricDao {
        val rows = LinkedHashMap<String, AiLedgerImportMetricEntity>()
        var failWrites = false

        override suspend fun upsert(metric: AiLedgerImportMetricEntity) {
            if (failWrites) throw IllegalStateException("forced failure")
            rows[metric.importId] = metric
        }

        override suspend fun forId(importId: String): AiLedgerImportMetricEntity? = rows[importId]

        override suspend fun count(): Int = rows.size

        override suspend fun deleteOlderThan(cutoffEpochHour: Long): Int {
            val before = rows.size
            rows.values.removeAll { it.finishedAtEpochHour < cutoffEpochHour }
            return before - rows.size
        }

        override suspend fun countForId(importId: String): Int = if (rows.containsKey(importId)) 1 else 0

        override suspend fun markStaleInProgress(cutoffEpochHour: Long, targetStatus: String): Int {
            var updated = 0
            rows.entries.forEach { (id, entity) ->
                if (entity.status == AiLedgerImportStatus.IN_PROGRESS && entity.finishedAtEpochHour < cutoffEpochHour) {
                    rows[id] = entity.copy(status = targetStatus)
                    updated++
                }
            }
            return updated
        }
    }

    private fun row(lineIndex: Int = 0) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa Martinez",
        amountCents = "25000",
        description = "coca",
        date = SafeDateCandidate(2026, 7, 30),
    )

    @Test
    fun `the id is stable for the same session and different for different sessions`() = runBlocking {
        assertEquals(
            LedgerImportMetricRecorder.importIdFor("session-1"),
            LedgerImportMetricRecorder.importIdFor("session-1"),
        )
        assertNotEquals(
            LedgerImportMetricRecorder.importIdFor("session-1"),
            LedgerImportMetricRecorder.importIdFor("session-2"),
        )
    }

    @Test
    fun `the stored hour is rounded - two imports in the same minute are not told apart by it`() = runBlocking {
        // The test that protects against joining the metric with the sales of that instant: an
        // exact timestamp is a join key; the hour is not.
        val withinSameHour = Instant.parse("2026-09-08T14:23:17Z").toEpochMilli()
        val laterSameHour = Instant.parse("2026-09-08T14:59:59Z").toEpochMilli()

        assertEquals(
            LedgerImportMetricRecorder.hourRounded(withinSameHour),
            LedgerImportMetricRecorder.hourRounded(laterSameHour),
        )
        // And the rounding is to the hour in point, not to any other bucket.
        assertEquals(
            Instant.parse("2026-09-08T14:00:00Z").toEpochMilli(),
            LedgerImportMetricRecorder.hourRounded(withinSameHour),
        )
    }

    @Test
    fun `stage one writes an in-progress row and stage two updates the same row`() = runBlocking {
        val dao = FakeMetricDao()
        val original = listOf(row(0), row(1))

        LedgerImportMetricRecorder.onExtractionArrived(
            dao = dao,
            sessionId = "session-1",
            detectedCount = 2,
            unreadableCount = 0,
            duplicateSuggestedCount = 0,
            parserExcludedCount = 0,
            geminiCalls = 1,
            geminiDurationMillis = 4200L,
            contractVersion = 4,
            nowEpochMillis = Instant.parse("2026-09-08T14:23:17Z").toEpochMilli(),
        )

        assertEquals(1, dao.rows.size)
        val inProgress = dao.rows.values.single()
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, inProgress.status)
        assertEquals(2, inProgress.detectedCount)
        assertEquals(1, inProgress.geminiCalls)
        assertEquals(4200L, inProgress.geminiDurationMillis)
        assertEquals(4, inProgress.contractVersion)

        val finalReview = LedgerReviewEngine.build(
            operations = listOf(row(0).copy(amountCents = "38000"), row(1)),
            todayEpochDay = 20700L,
        )
        LedgerImportMetricRecorder.onImportEnded(
            dao = dao,
            sessionId = "session-1",
            registered = true,
            originalOperations = original,
            finalReview = finalReview,
            duplicateSuggestedCount = 0,
            parserExcludedCount = 0,
            nowEpochMillis = Instant.parse("2026-09-08T14:55:00Z").toEpochMilli(),
        )

        // Still one row: the update landed on the same id, not beside it.
        assertEquals(1, dao.rows.size)
        val finished = dao.rows.values.single()
        assertEquals(AiLedgerImportStatus.REGISTERED, finished.status)
        // The cost fields survive the ending, taken from the stage-one row.
        assertEquals(1, finished.geminiCalls)
        assertEquals(4200L, finished.geminiDurationMillis)
        assertEquals(4, finished.contractVersion)
        // And the correction the person made is counted.
        assertEquals(1, finished.amountCorrections)
        assertEquals(1, finished.correctedCount)
    }

    @Test
    fun `retrying the ending twice leaves one row with the same result`() = runBlocking {
        val dao = FakeMetricDao()
        LedgerImportMetricRecorder.onExtractionArrived(
            dao = dao,
            sessionId = "session-1",
            detectedCount = 1,
            unreadableCount = 0,
            duplicateSuggestedCount = 0,
            parserExcludedCount = 0,
            geminiCalls = 1,
            geminiDurationMillis = 1000L,
            contractVersion = 4,
            nowEpochMillis = 1000L,
        )
        val review = LedgerReviewEngine.build(operations = listOf(row(0)), todayEpochDay = 20700L)

        LedgerImportMetricRecorder.onImportEnded(
            dao = dao, sessionId = "session-1", registered = true,
            originalOperations = listOf(row(0)), finalReview = review, duplicateSuggestedCount = 0, parserExcludedCount = 0, nowEpochMillis = 2000L,
        )
        LedgerImportMetricRecorder.onImportEnded(
            dao = dao, sessionId = "session-1", registered = true,
            originalOperations = listOf(row(0)), finalReview = review, duplicateSuggestedCount = 0, parserExcludedCount = 0, nowEpochMillis = 3000L,
        )

        assertEquals(1, dao.rows.size)
        assertEquals(AiLedgerImportStatus.REGISTERED, dao.rows.values.single().status)
    }

    @Test
    fun `a failed metric write never throws`() = runBlocking {
        val dao = FakeMetricDao().apply { failWrites = true }

        // Neither stage may propagate: a metric that fails costs the metric, never the import.
        LedgerImportMetricRecorder.onExtractionArrived(
            dao = dao, sessionId = "session-1", detectedCount = 1, unreadableCount = 0,
            duplicateSuggestedCount = 0, parserExcludedCount = 0, geminiCalls = 1, geminiDurationMillis = 1L,
            contractVersion = 4, nowEpochMillis = 1000L,
        )
        LedgerImportMetricRecorder.onImportEnded(
            dao = dao, sessionId = "session-1", registered = true,
            originalOperations = listOf(row(0)),
            finalReview = LedgerReviewEngine.build(operations = listOf(row(0)), todayEpochDay = 20700L),
            duplicateSuggestedCount = 0,
            parserExcludedCount = 0,
            nowEpochMillis = 2000L,
        )

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `retention deletes rows older than the cutoff and keeps the recent ones`() = runBlocking {
        val dao = FakeMetricDao()
        val now = Instant.parse("2026-09-08T14:00:00Z").toEpochMilli()
        val recent = LedgerImportMetricRecorder.hourRounded(now)
        val old = recent - (LedgerImportMetricRecorder.RETENTION_DAYS + 1) * 24L * 3_600_000L

        dao.upsert(AiLedgerImportMetricEntity(importId = "old", finishedAtEpochHour = old, status = AiLedgerImportStatus.REGISTERED, detectedCount = 0, readyUntouchedCount = 0, correctedCount = 0, discardedCount = 0, unreadableCount = 0, duplicateSuggestedCount = 0, parserExcludedCount = 0, customerCorrections = 0, productCorrections = 0, amountCorrections = 0, dateCorrections = 0, kindCorrections = 0, geminiCalls = 1, geminiDurationMillis = 0, contractVersion = 4, highConfidenceFields = 0, highConfidenceCorrected = 0, midConfidenceFields = 0, midConfidenceCorrected = 0, lowConfidenceFields = 0, lowConfidenceCorrected = 0))
        dao.upsert(AiLedgerImportMetricEntity(importId = "recent", finishedAtEpochHour = recent, status = AiLedgerImportStatus.REGISTERED, detectedCount = 0, readyUntouchedCount = 0, correctedCount = 0, discardedCount = 0, unreadableCount = 0, duplicateSuggestedCount = 0, parserExcludedCount = 0, customerCorrections = 0, productCorrections = 0, amountCorrections = 0, dateCorrections = 0, kindCorrections = 0, geminiCalls = 1, geminiDurationMillis = 0, contractVersion = 4, highConfidenceFields = 0, highConfidenceCorrected = 0, midConfidenceFields = 0, midConfidenceCorrected = 0, lowConfidenceFields = 0, lowConfidenceCorrected = 0))

        // Any write applies retention; the extraction arrival is one.
        LedgerImportMetricRecorder.onExtractionArrived(
            dao = dao, sessionId = "session-new", detectedCount = 0, unreadableCount = 0,
            duplicateSuggestedCount = 0, parserExcludedCount = 0, geminiCalls = 1, geminiDurationMillis = 0,
            contractVersion = 4, nowEpochMillis = now,
        )

        assertTrue("the old row is gone", "old" !in dao.rows.keys)
        assertTrue("the recent row survives", "recent" in dao.rows.keys)
        assertTrue("the new row was written", dao.rows.keys.size == 2)
    }

    @Test
    fun `nothing stored contains free text - no name, no amount, no rawText`() = runBlocking {
        // The promise Diego approved, checked against the real row: every field of the entity is
        // a count, a duration, a version or a status. If someone adds a text column, this fails.
        val dao = FakeMetricDao()
        LedgerImportMetricRecorder.onExtractionArrived(
            dao = dao, sessionId = "session-1", detectedCount = 1, unreadableCount = 0,
            duplicateSuggestedCount = 0, parserExcludedCount = 0, geminiCalls = 1, geminiDurationMillis = 1L,
            contractVersion = 4, nowEpochMillis = 1000L,
        )
        LedgerImportMetricRecorder.onImportEnded(
            dao = dao, sessionId = "session-1", registered = true,
            originalOperations = listOf(row(0).copy(rawText = "Rosa Martinez 250 coca")),
            finalReview = LedgerReviewEngine.build(
                operations = listOf(row(0).copy(amountCents = "38000", rawText = "Rosa Martinez 380 coca")),
                todayEpochDay = 20700L,
            ),
            duplicateSuggestedCount = 0,
            parserExcludedCount = 0,
            nowEpochMillis = 2000L,
        )

        val stored = dao.rows.values.single()
        // Every declared field of the entity, by reflection, so a new column cannot slip past.
        for (field: Field in AiLedgerImportMetricEntity::class.java.declaredFields) {
            field.isAccessible = true
            val value = field.get(stored)
            when (value) {
                is String -> {
                    // The only strings allowed are the id (a hex digest) and the status enum.
                    assertTrue(
                        "field ${field.name} holds free text: $value",
                        value == stored.importId || value == stored.status,
                    )
                }
                is Int, is Long -> { /* counts, durations, versions: fine */ }
                else -> throw AssertionError("field ${field.name} is of an unexpected type: ${value?.javaClass}")
            }
        }
    }

    @Test
    fun `duplicates count comes from duplicate matches and discarded count matches user manual deletions`() = runBlocking {
        val dao = FakeMetricDao()
        val original = listOf(row(0), row(1), row(2))

        val parserDiscards = listOf(
            com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic(
                inputIndex = 3,
                lineIndex = 3,
                reason = com.cuentivo.app.ai.ledger.LedgerDiscardReason.MALFORMED,
            )
        )

        // User deletes row 2, leaving rows 0 and 1 in final review
        val finalReview = LedgerReviewEngine.build(
            operations = listOf(row(0), row(1)),
            discards = parserDiscards,
            todayEpochDay = 20700L,
        )

        // 2 duplicates were detected on this page
        val duplicatesDetectedCount = 2

        LedgerImportMetricRecorder.onImportEnded(
            dao = dao,
            sessionId = "session-dup-disc",
            registered = true,
            originalOperations = original,
            finalReview = finalReview,
            duplicateSuggestedCount = duplicatesDetectedCount,
            parserExcludedCount = finalReview.summary.excludedRowsCount,
            nowEpochMillis = 2000L,
        )

        val stored = dao.rows["session-dup-disc".let { LedgerImportMetricRecorder.importIdFor(it) }]
        org.junit.Assert.assertNotNull(stored)
        stored!!

        // duplicateSuggestedCount must be the 2 detected duplicates
        assertEquals(2, stored.duplicateSuggestedCount)

        // discardedCount must be 1 (the 1 operation deleted by user during review)
        assertEquals(1, stored.discardedCount)

        // detectedCount must be 3 (the 3 original candidate operations presented on screen for review)
        assertEquals(3, stored.detectedCount)

        // parserExcludedCount must be 1 (the 1 malformed row excluded by parser)
        assertEquals(1, stored.parserExcludedCount)
    }

    @Test
    fun `toImportEndParams and onImportEnded propagate duplicateSuggestedCount and parserExcludedCount to persisted row`() = runBlocking {
        val dao = FakeMetricDao()
        val op = row(0).copy(
            customerName = "Rosa Martinez",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(2026, 6, 7),
        )
        val registeredOp = com.cuentivo.app.domain.RegisteredOperationProjection(
            id = "reg-1",
            customerId = "c1",
            customerName = "Rosa Martinez",
            amountCents = 25000L,
            effectiveDateEpochDay = 20612L,
            kind = ProposedLedgerKind.SALE,
        )
        val parserDiscards = listOf(
            com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic(
                inputIndex = 1,
                lineIndex = 1,
                reason = com.cuentivo.app.ai.ledger.LedgerDiscardReason.MALFORMED,
            )
        )

        val reviewingState = com.cuentivo.app.ui.ai.AiDebugLabState.LedgerReviewing(
            sessionId = "session-e2e-dup",
            businessId = "biz-1",
            review = LedgerReviewEngine.build(
                operations = listOf(op),
                discards = parserDiscards,
                todayEpochDay = 20612L,
            ),
            originalOperations = listOf(op),
            registeredOperations = listOf(registeredOp),
        )

        // 1. Verify model layer calculates initialDuplicateCount correctly
        assertEquals(1, reviewingState.initialDuplicateCount)
        assertEquals(1, reviewingState.review.summary.excludedRowsCount)

        // 2. Extract params via production helper
        val now = Instant.parse("2026-09-08T14:30:00Z").toEpochMilli()
        val params = reviewingState.toImportEndParams(registered = true, nowEpochMillis = now)

        // 3. Persist via production method
        LedgerImportMetricRecorder.onImportEnded(
            dao = dao,
            sessionId = params.sessionId,
            registered = params.registered,
            originalOperations = params.originalOperations,
            finalReview = params.finalReview,
            duplicateSuggestedCount = params.duplicateSuggestedCount,
            parserExcludedCount = params.parserExcludedCount,
            nowEpochMillis = params.nowEpochMillis,
        )

        // 4. Assert full chain: DAO row contains the exact real calculated duplicate and parser discard counts
        val stored = dao.forId(LedgerImportMetricRecorder.importIdFor("session-e2e-dup"))
        org.junit.Assert.assertNotNull(stored)
        stored!!

        assertEquals(1, stored.duplicateSuggestedCount)
        assertEquals(1, stored.parserExcludedCount)
        assertEquals(1, stored.detectedCount)
        assertEquals(0, stored.discardedCount)
        assertEquals(AiLedgerImportStatus.REGISTERED, stored.status)
    }

    @Test
    fun `sweep marks stale in-progress imports as STALE and leaves recent in-progress intact`() = runBlocking {
        val dao = FakeMetricDao()
        val now = Instant.parse("2026-09-08T14:00:00Z").toEpochMilli()
        val recentTime = LedgerImportMetricRecorder.hourRounded(now)
        val staleTime = recentTime - 3 * 3_600_000L // 3 hours ago (older than 2h threshold)

        val oldId = LedgerImportMetricRecorder.importIdFor("session-old")
        val recentId = LedgerImportMetricRecorder.importIdFor("session-recent")
        val registeredId = LedgerImportMetricRecorder.importIdFor("session-registered")

        dao.upsert(AiLedgerImportMetricEntity(importId = oldId, finishedAtEpochHour = staleTime, status = AiLedgerImportStatus.IN_PROGRESS, detectedCount = 2, readyUntouchedCount = 0, correctedCount = 0, discardedCount = 0, unreadableCount = 0, duplicateSuggestedCount = 0, parserExcludedCount = 0, customerCorrections = 0, productCorrections = 0, amountCorrections = 0, dateCorrections = 0, kindCorrections = 0, geminiCalls = 1, geminiDurationMillis = 0, contractVersion = 4, highConfidenceFields = 0, highConfidenceCorrected = 0, midConfidenceFields = 0, midConfidenceCorrected = 0, lowConfidenceFields = 0, lowConfidenceCorrected = 0))
        dao.upsert(AiLedgerImportMetricEntity(importId = recentId, finishedAtEpochHour = recentTime, status = AiLedgerImportStatus.IN_PROGRESS, detectedCount = 2, readyUntouchedCount = 0, correctedCount = 0, discardedCount = 0, unreadableCount = 0, duplicateSuggestedCount = 0, parserExcludedCount = 0, customerCorrections = 0, productCorrections = 0, amountCorrections = 0, dateCorrections = 0, kindCorrections = 0, geminiCalls = 1, geminiDurationMillis = 0, contractVersion = 4, highConfidenceFields = 0, highConfidenceCorrected = 0, midConfidenceFields = 0, midConfidenceCorrected = 0, lowConfidenceFields = 0, lowConfidenceCorrected = 0))
        dao.upsert(AiLedgerImportMetricEntity(importId = registeredId, finishedAtEpochHour = staleTime, status = AiLedgerImportStatus.REGISTERED, detectedCount = 2, readyUntouchedCount = 2, correctedCount = 0, discardedCount = 0, unreadableCount = 0, duplicateSuggestedCount = 0, parserExcludedCount = 0, customerCorrections = 0, productCorrections = 0, amountCorrections = 0, dateCorrections = 0, kindCorrections = 0, geminiCalls = 1, geminiDurationMillis = 0, contractVersion = 4, highConfidenceFields = 0, highConfidenceCorrected = 0, midConfidenceFields = 0, midConfidenceCorrected = 0, lowConfidenceFields = 0, lowConfidenceCorrected = 0))

        val swept = LedgerImportMetricRecorder.sweepStaleInProgress(dao, now)
        assertEquals(1, swept)

        assertEquals(AiLedgerImportStatus.STALE, dao.rows[oldId]?.status)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, dao.rows[recentId]?.status)
        assertEquals(AiLedgerImportStatus.REGISTERED, dao.rows[registeredId]?.status)
    }

    @Test
    fun `sweepStaleInProgress respects boundary and never marks rows stale before full nominal threshold has elapsed`() = runBlocking {
        val dao = FakeMetricDao()
        // Suppose an import started at 12:59:00 UTC -> finishedAtEpochHour is rounded down to 12:00:00 UTC
        val importStartedAt = Instant.parse("2026-09-08T12:59:00Z").toEpochMilli()
        val importHour = LedgerImportMetricRecorder.hourRounded(importStartedAt) // 12:00:00
        val importId = LedgerImportMetricRecorder.importIdFor("session-boundary-test")

        dao.upsert(
            AiLedgerImportMetricEntity(
                importId = importId,
                finishedAtEpochHour = importHour,
                status = AiLedgerImportStatus.IN_PROGRESS,
                detectedCount = 2,
                readyUntouchedCount = 0,
                correctedCount = 0,
                discardedCount = 0,
                unreadableCount = 0,
                duplicateSuggestedCount = 0,
                parserExcludedCount = 0,
                customerCorrections = 0,
                productCorrections = 0,
                amountCorrections = 0,
                dateCorrections = 0,
                kindCorrections = 0,
                geminiCalls = 1,
                geminiDurationMillis = 0,
                contractVersion = 4,
                highConfidenceFields = 0,
                highConfidenceCorrected = 0,
                midConfidenceFields = 0,
                midConfidenceCorrected = 0,
                lowConfidenceFields = 0,
                lowConfidenceCorrected = 0,
            )
        )

        // At 14:01:00 UTC (elapsed: 62 minutes, nominal threshold: 2 hours = 120 minutes):
        // hourRounded(14:01) = 14:00, cutoff = 14:00 - 2h = 12:00.
        // With strict < cutoff, 12:00 < 12:00 is FALSE -> MUST NOT BE SWEPT!
        val at1401 = Instant.parse("2026-09-08T14:01:00Z").toEpochMilli()
        val sweptAt1401 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1401)
        assertEquals(0, sweptAt1401)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, dao.rows[importId]?.status)

        // At 14:59:59 UTC (elapsed: 119m 59s < 120m):
        // hourRounded(14:59) = 14:00, cutoff = 12:00 -> 12:00 < 12:00 is FALSE -> MUST NOT BE SWEPT!
        val at1459 = Instant.parse("2026-09-08T14:59:59Z").toEpochMilli()
        val sweptAt1459 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1459)
        assertEquals(0, sweptAt1459)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, dao.rows[importId]?.status)

        // At 15:00:00 UTC (elapsed: 121 minutes >= 120 minutes):
        // hourRounded(15:00) = 15:00, cutoff = 15:00 - 2h = 13:00 -> 12:00 < 13:00 is TRUE -> SWEPT!
        val at1500 = Instant.parse("2026-09-08T15:00:00Z").toEpochMilli()
        val sweptAt1500 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1500)
        assertEquals(1, sweptAt1500)
        assertEquals(AiLedgerImportStatus.STALE, dao.rows[importId]?.status)
    }
}
