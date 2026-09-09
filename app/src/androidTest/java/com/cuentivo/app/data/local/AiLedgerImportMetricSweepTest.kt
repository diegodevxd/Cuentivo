package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.domain.LedgerImportMetricRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Instrumented test verifying that stale IN_PROGRESS import metric rows are closed as STALE
 * upon running the sweep against a real Room database, while recent IN_PROGRESS and finished rows
 * remain unmodified.
 */
@RunWith(AndroidJUnit4::class)
class AiLedgerImportMetricSweepTest {

    private lateinit var database: CuentivoDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sweepStaleInProgress_marksOldInProgressAsStale_andKeepsRecentInProgress() = runBlocking {
        val dao = database.aiLedgerImportMetricDao()
        val now = Instant.parse("2026-09-08T14:00:00Z").toEpochMilli()
        val currentHour = LedgerImportMetricRecorder.hourRounded(now)
        val oldHour = currentHour - 4 * 3_600_000L // 4 hours old (> 2 hours threshold)

        val oldImportId = LedgerImportMetricRecorder.importIdFor("session-old-dead-process")
        val recentImportId = LedgerImportMetricRecorder.importIdFor("session-recent-active")
        val registeredImportId = LedgerImportMetricRecorder.importIdFor("session-registered-ok")

        dao.upsert(
            AiLedgerImportMetricEntity(
                importId = oldImportId,
                finishedAtEpochHour = oldHour,
                status = AiLedgerImportStatus.IN_PROGRESS,
                detectedCount = 5,
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
                geminiDurationMillis = 2000L,
                contractVersion = 4,
                highConfidenceFields = 0,
                highConfidenceCorrected = 0,
                midConfidenceFields = 0,
                midConfidenceCorrected = 0,
                lowConfidenceFields = 0,
                lowConfidenceCorrected = 0,
            )
        )

        dao.upsert(
            AiLedgerImportMetricEntity(
                importId = recentImportId,
                finishedAtEpochHour = currentHour,
                status = AiLedgerImportStatus.IN_PROGRESS,
                detectedCount = 3,
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
                geminiDurationMillis = 1500L,
                contractVersion = 4,
                highConfidenceFields = 0,
                highConfidenceCorrected = 0,
                midConfidenceFields = 0,
                midConfidenceCorrected = 0,
                lowConfidenceFields = 0,
                lowConfidenceCorrected = 0,
            )
        )

        dao.upsert(
            AiLedgerImportMetricEntity(
                importId = registeredImportId,
                finishedAtEpochHour = oldHour,
                status = AiLedgerImportStatus.REGISTERED,
                detectedCount = 4,
                readyUntouchedCount = 4,
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
                geminiDurationMillis = 1800L,
                contractVersion = 4,
                highConfidenceFields = 0,
                highConfidenceCorrected = 0,
                midConfidenceFields = 0,
                midConfidenceCorrected = 0,
                lowConfidenceFields = 0,
                lowConfidenceCorrected = 0,
            )
        )

        // Execute sweep via production method
        val sweptCount = LedgerImportMetricRecorder.sweepStaleInProgress(dao, now)
        assertEquals(1, sweptCount)

        val oldEntity = dao.forId(oldImportId)
        assertNotNull(oldEntity)
        assertEquals(AiLedgerImportStatus.STALE, oldEntity?.status)

        val recentEntity = dao.forId(recentImportId)
        assertNotNull(recentEntity)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, recentEntity?.status)

        val registeredEntity = dao.forId(registeredImportId)
        assertNotNull(registeredEntity)
        assertEquals(AiLedgerImportStatus.REGISTERED, registeredEntity?.status)
    }

    @Test
    fun sweepStaleInProgress_boundaryGuaranteesNominalThresholdAgainstRealRoomDatabase() = runBlocking {
        val dao = database.aiLedgerImportMetricDao()
        // An import started at 12:59:00Z gets finishedAtEpochHour = 12:00:00Z
        val importStartedAt = Instant.parse("2026-09-08T12:59:00Z").toEpochMilli()
        val importHour = LedgerImportMetricRecorder.hourRounded(importStartedAt) // 12:00:00Z
        val boundaryImportId = LedgerImportMetricRecorder.importIdFor("session-boundary-room")

        dao.upsert(
            AiLedgerImportMetricEntity(
                importId = boundaryImportId,
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

        // At 14:01:00Z (elapsed: 62 minutes < 120 minutes threshold):
        // hourRounded(14:01) = 14:00, cutoff = 12:00. In SQLite: 12:00 < 12:00 is FALSE -> MUST NOT BE SWEPT!
        val at1401 = Instant.parse("2026-09-08T14:01:00Z").toEpochMilli()
        val sweptAt1401 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1401)
        assertEquals(0, sweptAt1401)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, dao.forId(boundaryImportId)?.status)

        // At 14:59:59Z (elapsed: 119m 59s < 120m threshold):
        // hourRounded(14:59) = 14:00, cutoff = 12:00. In SQLite: 12:00 < 12:00 is FALSE -> MUST NOT BE SWEPT!
        val at1459 = Instant.parse("2026-09-08T14:59:59Z").toEpochMilli()
        val sweptAt1459 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1459)
        assertEquals(0, sweptAt1459)
        assertEquals(AiLedgerImportStatus.IN_PROGRESS, dao.forId(boundaryImportId)?.status)

        // At 15:00:00Z (elapsed: 121m >= 120m threshold):
        // hourRounded(15:00) = 15:00, cutoff = 13:00. In SQLite: 12:00 < 13:00 is TRUE -> SWEPT!
        val at1500 = Instant.parse("2026-09-08T15:00:00Z").toEpochMilli()
        val sweptAt1500 = LedgerImportMetricRecorder.sweepStaleInProgress(dao, at1500)
        assertEquals(1, sweptAt1500)
        assertEquals(AiLedgerImportStatus.STALE, dao.forId(boundaryImportId)?.status)
    }
}
