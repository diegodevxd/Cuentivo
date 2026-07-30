package com.cuentivo.app.ui.customer

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale

/** What the directory says about a customer, and how urgently it says it. */
internal data class CustomerStatus(
    val label: String,
    val tone: CustomerTone,
)

internal enum class CustomerTone {
    /** Nothing owed. */
    SETTLED,

    /** Owes, nothing late yet. */
    PENDING,

    /** At least one sale is past its due date. */
    OVERDUE,
}

internal enum class CustomerFilter(val label: String) {
    ALL("Todos"),
    OWING("Con saldo"),
    OVERDUE("En mora"),
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
        if (customer.balanceCents <= 0L) return CustomerStatus("Al día", CustomerTone.SETTLED)

        val mine = dueSales.filter { it.customerId == customer.customer.id }
        val oldestOverdue = mine.filter { it.dueDateEpochDay < todayEpochDay }
            .minOfOrNull { it.dueDateEpochDay }
        if (oldestOverdue != null) {
            val days = todayEpochDay - oldestOverdue
            return CustomerStatus(
                "Vencido ($days ${if (days == 1L) "día" else "días"})",
                CustomerTone.OVERDUE,
            )
        }

        val nextDue = mine.filter { it.dueDateEpochDay >= todayEpochDay }
            .minOfOrNull { it.dueDateEpochDay }
        return when {
            nextDue == null -> CustomerStatus("Saldo pendiente", CustomerTone.PENDING)
            nextDue == todayEpochDay -> CustomerStatus("Vence hoy", CustomerTone.PENDING)
            else -> CustomerStatus("Vence en ${nextDue - todayEpochDay} días", CustomerTone.PENDING)
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
