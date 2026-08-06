package com.cuentivo.app.ui.customer

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale

/** What the directory says about a customer, and how urgently it says it. */
internal data class CustomerStatus(
    val tone: CustomerTone,
    val overdueDays: Long? = null,
    val daysUntilDue: Long? = null,
)

internal enum class CustomerTone {
    /** Nothing owed. */
    SETTLED,

    /** Owes, nothing late yet. */
    PENDING,

    /** At least one sale is past its due date. */
    OVERDUE,

    /** The customer has paid more than they owe. */
    CREDIT,
}

internal enum class CustomerFilter(val labelRes: Int) {
    ALL(com.cuentivo.app.R.string.customer_filter_all),
    OWING(com.cuentivo.app.R.string.customer_filter_owing),
    OVERDUE(com.cuentivo.app.R.string.customer_filter_overdue),
}

/**
 * Derives the directory's status line from data the screen already has.
 *
 * Kept pure and out of Compose so the wording and the overdue day count can be tested without an
 * emulator. It reads balances and due dates; it never recomputes either. Balances stay `Long`
 * cents and due dates stay epoch days — the two are never mixed with the epoch-millis audit clock.
 */
internal object CustomerDirectoryPresentation {
    fun status(
        customer: CustomerBalanceRow,
        dueSales: List<DueSale>,
        todayEpochDay: Long,
    ): CustomerStatus {
        if (customer.balanceCents < 0L) return CustomerStatus(CustomerTone.CREDIT)
        if (customer.balanceCents == 0L) return CustomerStatus(CustomerTone.SETTLED)

        val mine = dueSales.filter { it.customerId == customer.customer.id }
        val oldestOverdue = mine.filter { it.dueDateEpochDay < todayEpochDay }
            .minOfOrNull { it.dueDateEpochDay }
        if (oldestOverdue != null) {
            val days = todayEpochDay - oldestOverdue
            return CustomerStatus(CustomerTone.OVERDUE, overdueDays = days)
        }

        val nextDue = mine.filter { it.dueDateEpochDay >= todayEpochDay }
            .minOfOrNull { it.dueDateEpochDay }
        return when {
            nextDue == null -> CustomerStatus(CustomerTone.PENDING)
            else -> CustomerStatus(CustomerTone.PENDING, daysUntilDue = nextDue - todayEpochDay)
        }
    }

    /** The reference's three filter chips, applied to the already business-scoped list. */
    fun filter(
        customers: List<CustomerBalanceRow>,
        query: String,
        chip: CustomerFilter,
        dueSales: List<DueSale>,
        todayEpochDay: Long,
    ): List<CustomerBalanceRow> {
        val trimmed = query.trim()
        return customers
            .filter { row ->
                trimmed.isBlank() ||
                    row.customer.name.contains(trimmed, ignoreCase = true) ||
                    row.customer.phone?.contains(trimmed) == true
            }
            .filter { row ->
                when (chip) {
                    CustomerFilter.ALL -> true
                    CustomerFilter.OWING -> row.balanceCents > 0L
                    CustomerFilter.OVERDUE ->
                        status(row, dueSales, todayEpochDay).tone == CustomerTone.OVERDUE
                }
            }
    }

    fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
}
