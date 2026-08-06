package com.cuentivo.app.domain

/** One customer as it appears in a shared list: just what is needed to say who owes how much. */
data class Debtor(
    val name: String,
    val balanceCents: Long,
    val isOverdue: Boolean = false,
)

/**
 * Plain-text summaries meant for WhatsApp or any app in the Android Sharesheet.
 *
 * Two separate builders on purpose. [forCustomer] is sent *to* the customer and therefore must never
 * contain another customer's name or balance; [forBusiness] is the owner's own list and never leaves
 * their hands unless they choose to send it.
 */
object DebtorShareText {

    /** The owner's collection list: everyone who owes, largest debt first. */
    fun forBusiness(businessName: String, debtors: List<Debtor>): String {
        val owing = debtors.filter { it.balanceCents > 0 }.sortedByDescending { it.balanceCents }
        if (owing.isEmpty()) return "$businessName\nNadie tiene saldo pendiente."
        val lines = owing.joinToString("\n") { debtor ->
            val overdue = if (debtor.isOverdue) " (vencido)" else ""
            "• ${debtor.name}: ${Money.format(debtor.balanceCents)}$overdue"
        }
        val total = owing.sumOf { it.balanceCents }
        return buildString {
            append(businessName).append('\n')
            append("Clientes por cobrar: ${owing.size}").append('\n').append('\n')
            append(lines).append('\n').append('\n')
            append("Total por cobrar: ${Money.format(total)}")
        }
    }

    /**
     * The message sent to one customer. Deliberately short and neutral in tone, and it carries only
     * this customer's numbers.
     */
    fun forCustomer(businessName: String, customerName: String, balanceCents: Long, entries: List<StatementEntry> = emptyList()): String {
        val header = "Hola $customerName, te comparto el estado de tu cuenta con $businessName."
        // No column alignment: WhatsApp renders this in a proportional font, so padded columns
        // arrive crooked. Separators and an indented second line survive any width.
        val detail = entries.takeIf { it.isNotEmpty() }?.joinToString("\n") { entry ->
            val sign = if (entry.direction == "DEBIT") "+" else "−"
            val line = "${displayDate(entry.dateIso)} · ${movementLabel(entry.type)} · $sign${Money.format(entry.amountCents)}"
            entry.detail?.takeIf { it.isNotBlank() }?.let { "$line\n   $it" } ?: line
        }
        return buildString {
            append(header).append('\n')
            if (detail != null) append('\n').append(detail).append('\n')
            append('\n')
            append(
                if (balanceCents > 0) "Saldo pendiente: ${Money.format(balanceCents)}"
                else "Tu cuenta está al corriente. ¡Gracias!"
            )
        }
    }

    /** "2026-08-03" → "03/08/2026". Nobody outside a database reads ISO dates. */
    private fun displayDate(dateIso: String): String {
        val parts = dateIso.split('-')
        return if (parts.size == 3 && parts[0].length == 4) "${parts[2]}/${parts[1]}/${parts[0]}" else dateIso
    }

    private fun movementLabel(type: String): String = when (type) {
        "CHARGE" -> "Venta"
        "PAYMENT" -> "Abono"
        "ADJUSTMENT" -> "Ajuste"
        "REFUND" -> "Devolución"
        "CANCELLATION" -> "Cancelación"
        else -> type
    }
}
