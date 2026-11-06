package com.cuentivo.app.settings

import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.OverdueReminder
import com.cuentivo.app.domain.OverdueReminderPolicy
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Un recordatorio planificado para un día, con el resumen real que mostraría. */
data class PlannedReminder(
    val epochDay: Long,
    val hourOfDay: Int,
    val summary: OverdueReminder,
)

/**
 * La política de los próximos días, pura y probada.
 *
 * En iOS no hay trabajo diario fiable (`BGTaskScheduler` no garantiza hora), así que se dejan
 * programadas las notificaciones de los próximos días con la política **real**: por cada día se llama
 * a [OverdueReminderPolicy.summarize], no a una copia.
 *
 * La regla del primer día es la misma que `nextDelayMillis` de Android: a las 09:00 exactas el aviso
 * de hoy todavía entra (la hora no ha pasado), y un instante después ya es mañana.
 */
object ReminderPlanner {

    const val DEFAULT_DAYS = 7

    fun plan(
        dueSales: List<DueSale>,
        now: LocalDateTime,
        hourOfDay: Int,
        days: Int = DEFAULT_DAYS,
    ): List<PlannedReminder> {
        if (days <= 0) return emptyList()
        val hour = CollectionReminderPreferences.validatedHour(hourOfDay)
        val target = LocalTime(hour, 0)
        val today = now.date
        return buildList {
            for (offset in 0 until days) {
                val date = today.plus(DatePeriod(days = offset))
                // El de hoy sólo entra si la hora no ha pasado todavía.
                if (offset == 0 && now.time > target) continue
                val summary = OverdueReminderPolicy.summarize(dueSales, date) ?: continue
                add(PlannedReminder(epochDay = date.toEpochDays().toLong(), hourOfDay = hour, summary = summary))
            }
        }
    }
}
