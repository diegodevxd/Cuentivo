package com.cuentivo.app.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cuentivo.app.settings.CollectionReminderPreferences
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** The one unique daily job this feature owns. The name and interval are pinned here and tested. */
object OverdueReminderWork {
    const val NAME = "cuentivo-overdue-reminder"
    const val INTERVAL_HOURS = 24L
}

/** WorkManager wiring behind an interface, so scheduling and cancellation are JVM-tested. */
interface ReminderWorkEnqueuer {
    fun enqueueDaily(initialDelayMillis: Long)

    fun cancel()
}

/** Real WorkManager enqueuer: one uniquely-named periodic job, replaced on every reschedule. */
class WorkManagerReminderWorkEnqueuer(context: Context) : ReminderWorkEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueDaily(initialDelayMillis: Long) {
        val request = PeriodicWorkRequestBuilder<CollectionReminderWorker>(
            OverdueReminderWork.INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            OverdueReminderWork.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel() {
        workManager.cancelUniqueWork(OverdueReminderWork.NAME)
    }
}

/**
 * Turns the owner's preference into a daily job, or the absence of one.
 *
 * [sync] is the only entry the UI needs: enabled schedules the unique job at the chosen local hour,
 * disabled cancels it. [cancel] is for sign-out and account deletion — a phone must never remind
 * the next owner about the previous owner's debts.
 */
class CollectionReminderScheduler(
    private val preferences: CollectionReminderPreferences,
    private val workEnqueuer: ReminderWorkEnqueuer,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    fun sync() {
        if (preferences.enabled) {
            workEnqueuer.enqueueDaily(nextDelayMillis(preferences.hourOfDay, now()))
        } else {
            workEnqueuer.cancel()
        }
    }

    fun cancel() = workEnqueuer.cancel()

    companion object {
        fun sync(context: Context) {
            CollectionReminderScheduler(
                preferences = CollectionReminderPreferences(context),
                workEnqueuer = WorkManagerReminderWorkEnqueuer(context),
            ).sync()
        }

        fun cancel(context: Context) {
            WorkManagerReminderWorkEnqueuer(context).cancel()
        }

        /**
         * Millis until the chosen local hour: today if it has not passed, tomorrow otherwise.
         *
         * "Has not passed" is compared on the full local time, not just the hour: at exactly 09:00
         * the hour is due now (delay 0), and a millisecond later the next occurrence is tomorrow.
         * Android may run the job later than this (work guarantees are a floor, not a ceiling),
         * never earlier — so this delay is always non-negative.
         */
        fun nextDelayMillis(hourOfDay: Int, now: LocalDateTime): Long {
            val targetTime = LocalTime.of(hourOfDay.coerceIn(0, 23), 0)
            val targetToday = now.toLocalDate().atTime(targetTime)
            val target = if (!now.toLocalTime().isAfter(targetTime)) targetToday else targetToday.plusDays(1)
            return Duration.between(now, target).toMillis()
        }
    }
}
