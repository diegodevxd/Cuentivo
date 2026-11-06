package com.cuentivo.app.ui.customer

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.PaymentReliability
// Los accesos de Res.string son propiedades de extensión generadas una por clave.
import com.cuentivo.shared.resources.*
import org.jetbrains.compose.resources.StringResource

/** What the directory says about a customer, and how urgently it says it. */
data class CustomerStatus(
    val tone: CustomerTone,
    val overdueDays: Long? = null,
    val daysUntilDue: Long? = null,
)

enum class CustomerTone {
    /** Nothing owed. */
    SETTLED,

    /** Owes, nothing late yet. */
    PENDING,

    /** At least one sale is past its due date. */
    OVERDUE,

    /** The customer has paid more than they owe. */
    CREDIT,
}

/**
 * The wording for a payment-reliability dot. Null for [PaymentReliability.NO_HISTORY]: a customer
 * without enough history gets no dot at all, rather than a grey one that reads as a verdict.
 *
 * Lives here, not in the domain, because `domain/` is Kotlin without a `Context` — the calculator
 * returns the enum and the UI owns the words, which is what keeps the scoring rules testable on the
 * JVM.
 */
fun PaymentReliability.labelRes(): StringResource? = when (this) {
    PaymentReliability.PUNCTUAL -> Res.string.customer_score_punctual
    PaymentReliability.SLOW -> Res.string.customer_score_slow
    PaymentReliability.DELINQUENT -> Res.string.customer_score_delinquent
    PaymentReliability.NO_HISTORY -> null
}

/**
 * Whether the detail hero should draw the status pill under the balance.
 *
 * The hero already names the balance kind ("Saldo pendiente", "Saldo a favor"), and the pill for a
 * pending customer with no due date, or one in credit, says exactly the same words again. The pill
 * only earns its place when it adds a date: due today, due in N days, or overdue. A settled
 * customer keeps it, because "Al día" says more than "Saldo en cero".
 */
fun CustomerStatus.addsToBalanceKind(): Boolean = when (tone) {
    CustomerTone.PENDING -> daysUntilDue != null
    CustomerTone.CREDIT -> false
    CustomerTone.OVERDUE, CustomerTone.SETTLED -> true
}

enum class CustomerFilter(val labelRes: StringResource) {
    ALL(Res.string.customer_filter_all),
    OWING(Res.string.customer_filter_owing),
    OVERDUE(Res.string.customer_filter_overdue),
}

/**
 * Derives the directory's status line from data the screen already has.
 *
 * Kept pure and out of Compose so the wording and the overdue day count can be tested without an
 * emulator. It reads balances and due dates; it never recomputes either. Balances stay `Long`
 * cents and due dates stay epoch days — the two are never mixed with the epoch-millis audit clock.
 */
object CustomerDirectoryPresentation {
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
