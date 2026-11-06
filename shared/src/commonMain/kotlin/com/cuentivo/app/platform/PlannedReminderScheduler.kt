package com.cuentivo.app.platform

import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.OverdueReminder
import com.cuentivo.app.settings.CollectionReminderPreferences
import com.cuentivo.app.settings.ReminderPlanner
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime

/** El título y el cuerpo de una notificación, ya resueltos en el idioma del teléfono. */
data class ReminderText(val title: String, val body: String)

/** Un recordatorio listo para que el sistema lo programe. */
data class ScheduledReminder(
    val id: String,
    val epochDay: Long,
    val hourOfDay: Int,
    val title: String,
    val body: String,
)

/** El almacén de notificaciones programadas del sistema. */
interface LocalReminderCenter {
    suspend fun replaceAll(reminders: List<ScheduledReminder>)
    fun removeAll()
}

/**
 * El planificador de recordatorios de iOS, con la lógica en común.
 *
 * **`sync` y `cancel` no se pueden pisar.** Una `sync` lenta que termina después de un `cancel` le
 * recordaría al siguiente dueño las deudas del anterior. Por eso cada llamada incrementa una
 * generación y todo pasa por el mismo `Mutex`; la `sync` vuelve a comparar la generación **justo
 * antes** de [LocalReminderCenter.replaceAll] y, si ya no es la última, no escribe. `cancel` siempre
 * llama a `removeAll`.
 *
 * Si `sync` falla, lo programado se queda como estaba (`CancellationException` se relanza): igual que
 * el `Worker` de Android, que nunca reintenta en bucle por una notificación.
 *
 * **[now] es obligatorio a propósito.** `commonMain` no puede nombrar un reloj: Android compila
 * contra `kotlinx-datetime` 0.6.2 e iOS contra 0.7.1, y el `Instant` de cada reloj es otro tipo.
 * Cada plataforma pasa el suyo.
 */
@OptIn(ExperimentalAtomicApi::class)
class PlannedReminderScheduler(
    private val preferences: CollectionReminderPreferences,
    private val center: LocalReminderCenter,
    private val loadDueSales: suspend () -> List<DueSale>?,
    private val texts: suspend (OverdueReminder) -> ReminderText,
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime,
) : ReminderScheduler {

    private val generation = AtomicLong(0L)
    private val mutex = Mutex()

    override fun sync() {
        val myGeneration = generation.addAndFetch(1L)
        // UNDISPATCHED: la llamada arranca ya, así que el orden entre `sync` y `cancel` no depende de
        // cuándo el dispatcher decida correr cada corrutina.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                try {
                    if (!isCurrent(myGeneration)) return@withLock
                    if (!preferences.enabled) {
                        center.removeAll()
                        return@withLock
                    }
                    val dueSales = loadDueSales()
                    if (dueSales == null) {
                        center.removeAll()
                        return@withLock
                    }
                    val reminders = ReminderPlanner.plan(
                        dueSales = dueSales,
                        now = now(),
                        hourOfDay = preferences.hourOfDay,
                    ).map { planned ->
                        val text = texts(planned.summary)
                        ScheduledReminder(
                            id = "cuentivo-overdue-${planned.epochDay}",
                            epochDay = planned.epochDay,
                            hourOfDay = planned.hourOfDay,
                            title = text.title,
                            body = text.body,
                        )
                    }
                    // La comparación va aquí, justo antes de escribir: si un `cancel` entró mientras
                    // se leía la base, esta corrida ya no manda.
                    if (!isCurrent(myGeneration)) return@withLock
                    center.replaceAll(reminders)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Lo programado se queda: un fallo de lectura no borra los avisos de mañana.
                }
            }
        }
    }

    override fun cancel() {
        generation.addAndFetch(1L)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { center.removeAll() }
        }
    }

    private fun isCurrent(value: Long): Boolean = generation.load() == value
}
