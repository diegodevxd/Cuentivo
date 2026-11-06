package com.cuentivo.app.platform

import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.OverdueReminder
import com.cuentivo.shared.resources.Res
import com.cuentivo.shared.resources.*
import org.jetbrains.compose.resources.getString

/**
 * Los textos de la notificación de iOS.
 *
 * Usa las mismas claves que el `Worker` de Android (`reminder_notification_*`) y el mismo formateador
 * de dinero, así que un aviso dice lo mismo en las dos plataformas.
 */
fun iosReminderTexts(): suspend (OverdueReminder) -> ReminderText = { summary ->
    val title = getString(Res.string.reminder_notification_title)
    val body = when (summary) {
        is OverdueReminder.Single -> getString(
            Res.string.reminder_notification_single,
            summary.customerName,
            Money.format(summary.outstandingCents),
        )
        is OverdueReminder.Grouped -> getString(
            Res.string.reminder_notification_grouped,
            summary.count,
            Money.format(summary.totalCents),
        )
    }
    ReminderText(title = title, body = body)
}
