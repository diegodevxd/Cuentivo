package com.cuentivo.app.notifications

import com.cuentivo.app.settings.ReminderPreferenceStore
import com.cuentivo.app.settings.CollectionReminderPreferences
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory preference store sharing the same defaults contract as SharedPreferences. */
private class FakeReminderPreferenceStore : ReminderPreferenceStore {
    override var enabled: Boolean = ReminderPreferenceStore.DEFAULT_ENABLED
    override var hourOfDay: Int = ReminderPreferenceStore.DEFAULT_HOUR
}

/** Records what the scheduler asked the worker layer to do, without touching WorkManager. */
private class RecordingReminderWorkEnqueuer : ReminderWorkEnqueuer {
    val actions = mutableListOf<String>()
    var lastInitialDelayMillis: Long = -1L

    override fun enqueueDaily(initialDelayMillis: Long) {
        actions += "enqueue"
        lastInitialDelayMillis = initialDelayMillis
    }

    override fun cancel() {
        actions += "cancel"
    }
}

class CollectionReminderSchedulerTest {
    @Test
    fun `disabled preference cancels the unique reminder work`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        val enqueuer = RecordingReminderWorkEnqueuer()
        val scheduler = CollectionReminderScheduler(
            preferences = preferences,
            workEnqueuer = enqueuer,
            now = { LocalDateTime.of(2026, 8, 12, 8, 0) },
        )

        scheduler.sync()

        assertEquals(listOf("cancel"), enqueuer.actions)
    }

    @Test
    fun `enabled preference schedules one unique daily job`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore()).apply {
            update(enabled = true, hourOfDay = 9)
        }
        val enqueuer = RecordingReminderWorkEnqueuer()
        val scheduler = CollectionReminderScheduler(
            preferences = preferences,
            workEnqueuer = enqueuer,
            now = { LocalDateTime.of(2026, 8, 12, 8, 0) },
        )

        scheduler.sync()

        assertEquals(listOf("enqueue"), enqueuer.actions)
        assertEquals(3_600_000L, enqueuer.lastInitialDelayMillis)
        // The one daily job, uniquely named and repeated daily, is the contract the UI depends on.
        assertEquals("cuentivo-overdue-reminder", OverdueReminderWork.NAME)
        assertEquals(24L, OverdueReminderWork.INTERVAL_HOURS)
    }

    @Test
    fun `rescheduling after the hour updates the delay to tomorrow`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore()).apply {
            update(enabled = true, hourOfDay = 17)
        }
        val enqueuer = RecordingReminderWorkEnqueuer()
        val scheduler = CollectionReminderScheduler(
            preferences = preferences,
            workEnqueuer = enqueuer,
            now = { LocalDateTime.of(2026, 8, 12, 19, 30) },
        )

        scheduler.sync()

        assertEquals(listOf("enqueue"), enqueuer.actions)
        // 19:30 → tomorrow 17:00 is 21 hours and 30 minutes.
        assertEquals(21L * 3_600_000L + 30L * 60_000L, enqueuer.lastInitialDelayMillis)
    }

    @Test
    fun `cancel stops the unique work regardless of preference`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore()).apply {
            update(enabled = true, hourOfDay = 9)
        }
        val enqueuer = RecordingReminderWorkEnqueuer()
        val scheduler = CollectionReminderScheduler(preferences, enqueuer)

        scheduler.cancel()

        assertEquals(listOf("cancel"), enqueuer.actions)
    }

    @Test
    fun `initial delay never precedes the configured local hour`() {
        val cases = listOf(
            Triple(LocalDateTime.of(2026, 8, 12, 8, 0), 9, 3_600_000L),
            Triple(LocalDateTime.of(2026, 8, 12, 10, 0), 9, 23L * 3_600_000L),
            // Exactly at the configured hour: the hour has not passed yet, so today is valid — the
            // job is due now, not tomorrow. (A millisecond later it is tomorrow.)
            Triple(LocalDateTime.of(2026, 8, 12, 9, 0), 9, 0L),
            Triple(LocalDateTime.of(2026, 8, 12, 9, 0, 0, 1_000_000), 9, 24L * 3_600_000L - 1L),
            Triple(LocalDateTime.of(2026, 8, 12, 9, 0, 30), 9, 24L * 3_600_000L - 30_000L),
            Triple(LocalDateTime.of(2026, 8, 12, 23, 0), 0, 3_600_000L),
            Triple(LocalDateTime.of(2026, 8, 12, 0, 30), 9, 8L * 3_600_000L + 30L * 60_000L),
        )
        cases.forEach { (now, hour, expected) ->
            val delay = CollectionReminderScheduler.nextDelayMillis(hour, now)
            assertTrue("delay $delay for hour $hour at $now must never be negative", delay >= 0)
            assertEquals(expected, delay)
        }
    }
}
