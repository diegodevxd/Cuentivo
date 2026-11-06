package com.cuentivo.app.platform

import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.settings.CollectionReminderPreferences
import com.cuentivo.app.settings.ReminderPreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * El planificador, con un centro falso y `runTest`.
 *
 * Lo importante que se prueba aquí es la carrera: una `sync` que termina después de un `cancel` no
 * puede dejarle al siguiente dueño las deudas del anterior.
 */
class PlannedReminderSchedulerTest {

    private class FakeStore : ReminderPreferenceStore {
        override var enabled: Boolean = ReminderPreferenceStore.DEFAULT_ENABLED
        override var hourOfDay: Int = ReminderPreferenceStore.DEFAULT_HOUR
    }

    private class FakeCenter : LocalReminderCenter {
        var reminders: List<ScheduledReminder> = emptyList()
        var replaceCount = 0
        var removeAllCount = 0
        var failOnReplace = false

        override suspend fun replaceAll(newReminders: List<ScheduledReminder>) {
            if (failOnReplace) throw IllegalStateException("el centro falló")
            reminders = newReminders
            replaceCount++
        }

        override fun removeAll() {
            reminders = emptyList()
            removeAllCount++
        }
    }

    private fun dueSale() = DueSale(
        saleId = "venta-1",
        customerId = "cliente-1",
        customerName = "Rosa",
        dueDateEpochDay = LocalDate(2026, 11, 1).toEpochDays().toLong(),
        outstandingCents = 12_500,
    )

    private fun preferences(enabled: Boolean) = CollectionReminderPreferences(FakeStore()).apply {
        update(enabled = enabled, hourOfDay = 9)
    }

    private fun texts() = ReminderText(title = "Aviso", body = "Rosa debe")

    private val morning = LocalDateTime(2026, 11, 6, 8, 0)

    @Test
    fun `desactivado no programa nada`() = runTest {
        val center = FakeCenter()
        val scheduler = PlannedReminderScheduler(
            preferences = preferences(enabled = false),
            center = center,
            loadDueSales = { listOf(dueSale()) },
            texts = { texts() },
            scope = this,
            now = { morning },
        )

        scheduler.sync()
        advanceUntilIdle()

        assertEquals(0, center.replaceCount)
        assertTrue(center.reminders.isEmpty())
        assertTrue(center.removeAllCount >= 1)
    }

    @Test
    fun `sin sesion no programa nada`() = runTest {
        val center = FakeCenter()
        val scheduler = PlannedReminderScheduler(
            preferences = preferences(enabled = true),
            center = center,
            loadDueSales = { null },
            texts = { texts() },
            scope = this,
            now = { morning },
        )

        scheduler.sync()
        advanceUntilIdle()

        assertEquals(0, center.replaceCount)
        assertTrue(center.reminders.isEmpty())
    }

    @Test
    fun `con deudas programa el reemplazo`() = runTest {
        val center = FakeCenter()
        val scheduler = PlannedReminderScheduler(
            preferences = preferences(enabled = true),
            center = center,
            loadDueSales = { listOf(dueSale()) },
            texts = { texts() },
            scope = this,
            now = { morning },
        )

        scheduler.sync()
        advanceUntilIdle()

        assertEquals(1, center.replaceCount)
        assertTrue(center.reminders.isNotEmpty())
        val first = center.reminders.first()
        assertEquals("cuentivo-overdue-${first.epochDay}", first.id)
        assertEquals(9, first.hourOfDay)
        assertEquals("Aviso", first.title)
    }

    @Test
    fun `cancel durante una sync suspendida no deja nada programado`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val center = FakeCenter()
        val scheduler = PlannedReminderScheduler(
            preferences = preferences(enabled = true),
            center = center,
            loadDueSales = {
                gate.await()
                listOf(dueSale())
            },
            texts = { texts() },
            scope = this,
            now = { morning },
        )

        scheduler.sync()
        // La sync está suspendida esperando la base; el cancel entra en medio.
        scheduler.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, center.replaceCount)
        assertTrue(center.reminders.isEmpty())
    }

    @Test
    fun `un error no borra lo programado`() = runTest {
        val center = FakeCenter()
        val scheduler = PlannedReminderScheduler(
            preferences = preferences(enabled = true),
            center = center,
            loadDueSales = { throw IllegalStateException("la base no contestó") },
            texts = { texts() },
            scope = this,
            now = { morning },
        )

        scheduler.sync()
        advanceUntilIdle()

        assertEquals(0, center.replaceCount)
        assertEquals(0, center.removeAllCount)
    }
}
