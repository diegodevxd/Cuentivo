package com.cuentivo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.LocalDate
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDateComponents
import platform.Foundation.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Las notificaciones locales de iOS, con `UNUserNotificationCenter`.
 *
 * Los identificadores son `cuentivo-overdue-<epochDay>`, así que reprogramar el mismo día reemplaza en
 * vez de duplicar. El disparador es de calendario y **sin repetir**: la repetición la da la propia
 * planificación de siete días.
 *
 * Pendiente de CI: `UNUserNotificationCenter.currentNotificationCenter()` truena sin bundle, así que
 * esta clase no tiene prueba.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalReminderCenter : LocalReminderCenter {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun replaceAll(reminders: List<ScheduledReminder>) {
        center.removeAllPendingNotificationRequests()
        for (reminder in reminders) {
            val components = NSDateComponents()
            val date = LocalDate.fromEpochDays(reminder.epochDay.toInt())
            components.year = date.year.toLong()
            components.month = date.monthNumber.toLong()
            components.day = date.dayOfMonth.toLong()
            components.hour = reminder.hourOfDay.toLong()

            val content = UNMutableNotificationContent()
            // `title`, `body` y `sound` los ve Kotlin como `val` (la clase mutable hereda la
            // declaración de solo lectura de `UNNotificationContent`), así que la asignación va por
            // KVC, igual que `subject` en `IosSharer`.
            content.setValue(reminder.title, forKey = "title")
            content.setValue(reminder.body, forKey = "body")
            content.setValue(UNNotificationSound.defaultSound, forKey = "sound")

            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = components,
                repeats = false,
            )
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = reminder.id,
                content = content,
                trigger = trigger,
            )
            center.addNotificationRequest(request, withCompletionHandler = null)
        }
    }

    override fun removeAll() {
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }

    /** Lo llamará la raíz; si niegan, no se programa nada y la pantalla lo dice. */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }
}
