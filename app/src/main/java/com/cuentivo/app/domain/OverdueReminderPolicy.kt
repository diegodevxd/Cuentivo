package com.cuentivo.app.domain

import java.time.LocalDate

/** What the daily collection reminder says, derived purely from Room rows and the local date. */
sealed interface OverdueReminder {
    /** A single overdue sale: name the customer and the amount, so the tap leads somewhere. */
    data class Single(val customerName: String, val outstandingCents: Long) : OverdueReminder

    /** Two or more overdue sales: the count and the total are the news, not a name list. */
    data class Grouped(val count: Int, val totalCents: Long) : OverdueReminder
}

/**
 * The daily collection reminder's content policy — pure, no Android, no context.
 *
 * Only a sale strictly past its due date with a positive outstanding balance counts. Nothing here
 * stores, formats or forwards customer data: the caller decides what to show from the summary.
 */
object OverdueReminderPolicy {
    fun summarize(dueSales: List<DueSale>, today: LocalDate): OverdueReminder? {
        val overdue = dueSales.filter {
            it.dueDateEpochDay < today.toEpochDay() && it.outstandingCents > 0
        }
        return when (overdue.size) {
            0 -> null
            1 -> OverdueReminder.Single(
                customerName = overdue.single().customerName,
                outstandingCents = overdue.single().outstandingCents,
            )
            else -> OverdueReminder.Grouped(
                count = overdue.size,
                totalCents = overdue.sumOf { it.outstandingCents },
            )
        }
    }
}
