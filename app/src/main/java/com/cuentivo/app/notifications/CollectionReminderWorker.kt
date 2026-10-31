package com.cuentivo.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cuentivo.app.MainActivity
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.OverdueReminder
import com.cuentivo.app.domain.OverdueReminderPolicy
import com.cuentivo.app.settings.CollectionReminderPreferences
import com.google.firebase.auth.FirebaseAuth
import java.time.LocalDate

/**
 * The daily local collection reminder.
 *
 * Runs entirely off Room and the local clock: no network, no Firebase beyond the session check,
 * no customer contact. Success is the safe failure mode — a hiccup must never block tomorrow's
 * attempt, and retrying forever would burn battery for a notification nobody needs twice.
 */
class CollectionReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!CollectionReminderPreferences(applicationContext).enabled) return Result.success()

        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        return try {
            // WorkManager may run this in a process where the Activity never started; the signed-in
            // session is read the same way SyncWorker reads it, and it still defines whose business
            // may be asked about.
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
            val businessId = database.businessDao().firstOwnedBy(userId)?.id ?: return Result.success()
            val summary = OverdueReminderPolicy.summarize(
                dueSales = database.financialDao().salesWithDueDate(businessId).map { row ->
                    DueSale(
                        saleId = row.saleId,
                        customerId = row.customerId,
                        customerName = row.customerName,
                        dueDateEpochDay = row.dueDateEpochDay,
                        outstandingCents = row.outstandingCents,
                    )
                },
                today = LocalDate.now(),
            ) ?: return Result.success()
            showNotification(summary)
            Result.success()
        } catch (_: Exception) {
            // Never mark the job failed: a failed reminder is not worth a battery-draining retry
            // loop, and the next daily run gets another chance with fresh state.
            Result.success()
        } finally {
            database.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(summary: OverdueReminder) {
        val context = applicationContext
        val manager = NotificationManagerCompat.from(context)
        // The permission guard sits beside the call lint audits: on API 33+ this reflects
        // POST_NOTIFICATIONS, on older Androids the notification settings toggle.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_OVERDUE_CUSTOMERS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(contentText(context, summary))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText(context, summary)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Single debt names the customer and amount; grouped debt shows count and total. */
    private fun contentText(context: Context, summary: OverdueReminder): String = when (summary) {
        is OverdueReminder.Single -> context.getString(
            R.string.reminder_notification_single,
            summary.customerName,
            Money.format(summary.outstandingCents),
        )
        is OverdueReminder.Grouped -> context.getString(
            R.string.reminder_notification_grouped,
            summary.count,
            Money.format(summary.totalCents),
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "collection_reminders"
        const val NOTIFICATION_ID = 1
    }
}
