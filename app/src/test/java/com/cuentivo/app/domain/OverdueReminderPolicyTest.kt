package com.cuentivo.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverdueReminderPolicyTest {
    private val today = LocalDate.of(2026, 8, 12)

    private fun dueSale(
        name: String,
        dueEpochDay: Long,
        outstandingCents: Long,
        saleId: String = "sale-$name",
    ) = DueSale(
        saleId = saleId,
        customerId = "customer-$name",
        customerName = name,
        dueDateEpochDay = dueEpochDay,
        outstandingCents = outstandingCents,
    )

    @Test
    fun `future and settled rows never produce a reminder`() {
        val future = dueSale("Ana", today.plusDays(1).toEpochDay(), 85_000)
        val settled = dueSale("Luis", today.minusDays(3).toEpochDay(), 0)
        assertNull(OverdueReminderPolicy.summarize(listOf(future, settled), today))
    }

    @Test
    fun `a sale due today is not overdue yet`() {
        val dueToday = dueSale("Ana", today.toEpochDay(), 85_000)
        assertNull(OverdueReminderPolicy.summarize(listOf(dueToday), today))
    }

    @Test
    fun `one overdue sale names the customer and amount`() {
        val overdueAna = dueSale("Ana", today.minusDays(2).toEpochDay(), 85_000)
        assertEquals(
            OverdueReminder.Single("Ana", 85_000),
            OverdueReminderPolicy.summarize(listOf(overdueAna), today),
        )
    }

    @Test
    fun `many overdue sales are grouped with total and count`() {
        val overdueAna = dueSale("Ana", today.minusDays(2).toEpochDay(), 85_000)
        val overdueLuis = dueSale("Luis", today.minusDays(5).toEpochDay(), 40_000)
        assertEquals(
            OverdueReminder.Grouped(2, 125_000),
            OverdueReminderPolicy.summarize(listOf(overdueAna, overdueLuis), today),
        )
    }

    @Test
    fun `grouping ignores rows that are not overdue`() {
        val overdueAna = dueSale("Ana", today.minusDays(2).toEpochDay(), 85_000)
        val future = dueSale("Luis", today.plusDays(1).toEpochDay(), 40_000)
        val settled = dueSale("María", today.minusDays(5).toEpochDay(), 0)
        assertEquals(
            OverdueReminder.Single("Ana", 85_000),
            OverdueReminderPolicy.summarize(listOf(overdueAna, future, settled), today),
        )
    }

    @Test
    fun `empty list produces no reminder`() {
        assertNull(OverdueReminderPolicy.summarize(emptyList(), today))
    }
}
