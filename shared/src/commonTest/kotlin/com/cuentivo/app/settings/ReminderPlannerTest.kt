package com.cuentivo.app.settings

import com.cuentivo.app.domain.DueSale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * La planificación de los próximos días.
 *
 * La regla del primer día es la misma que la de Android: a las 09:00 exactas el aviso de hoy todavía
 * entra, y un segundo después ya es mañana.
 */
class ReminderPlannerTest {

    private fun dueSale(dueDate: LocalDate = LocalDate(2026, 11, 1)) = DueSale(
        saleId = "venta-1",
        customerId = "cliente-1",
        customerName = "Rosa",
        dueDateEpochDay = dueDate.toEpochDays().toLong(),
        outstandingCents = 12_500,
    )

    @Test
    fun `a las nueve exactas el aviso de hoy entra`() {
        val plan = ReminderPlanner.plan(
            dueSales = listOf(dueSale()),
            now = LocalDateTime(2026, 11, 6, 9, 0),
            hourOfDay = 9,
        )

        assertTrue(plan.isNotEmpty())
        assertEquals(LocalDate(2026, 11, 6).toEpochDays().toLong(), plan.first().epochDay)
        assertEquals(9, plan.first().hourOfDay)
    }

    @Test
    fun `un segundo despues el aviso de hoy ya no entra`() {
        val plan = ReminderPlanner.plan(
            dueSales = listOf(dueSale()),
            now = LocalDateTime(2026, 11, 6, 9, 0, 1),
            hourOfDay = 9,
        )

        assertTrue(plan.isNotEmpty())
        assertEquals(LocalDate(2026, 11, 7).toEpochDays().toLong(), plan.first().epochDay)
    }

    @Test
    fun `sin deudas vencidas no hay recordatorio`() {
        val plan = ReminderPlanner.plan(
            dueSales = emptyList(),
            now = LocalDateTime(2026, 11, 6, 8, 0),
            hourOfDay = 9,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `una venta que todavia no vence no genera aviso`() {
        val plan = ReminderPlanner.plan(
            dueSales = listOf(dueSale(dueDate = LocalDate(2026, 12, 1))),
            now = LocalDateTime(2026, 11, 6, 8, 0),
            hourOfDay = 9,
            days = 7,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `se planifican hasta siete dias y el alto se valida`() {
        val plan = ReminderPlanner.plan(
            dueSales = listOf(dueSale()),
            now = LocalDateTime(2026, 11, 6, 8, 0),
            hourOfDay = 99,
            days = 7,
        )

        assertEquals(7, plan.size)
        assertTrue(plan.all { it.hourOfDay == 23 })
    }
}
