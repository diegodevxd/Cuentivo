package com.cuentivo.app.domain

import java.math.BigDecimal

/**
 * One ledger line of a single customer's statement, already scoped to one business.
 * The caller formats the date; this layer stays free of time zones and Android.
 */
data class StatementEntry(
    val dateIso: String,
    val type: String,
    val direction: String,
    val amountCents: Long,
    val notes: String?,
    /**
     * The linked payment, when this row came from `account_movements.paymentId`. Lets
     * `CustomerStatementSummary.canceledPaymentIds` pair a `PAYMENT` row with the `REFUND` that
     * reversed it, entirely from the ledger already loaded — no extra query.
     */
    val paymentId: String? = null,
    /** The sale this movement came from, when it had one. Present on charges and cancellations. */
    val saleId: String? = null,
    /**
     * What was sold, already phrased by [SaleLineSummary]. Kept on the entry instead of looked up
     * per row: the statement is built once and read by the sheet, the CSV and the shared message.
     */
    val detail: String? = null,
    /**
     * True when this charge is a debt the customer already had before the app, recorded as a sale
     * for the ledger's sake. The arithmetic is identical to a sale; only the wording differs, so the
     * history does not claim a sale nobody made. Set by the caller that reads the ledger.
     */
    val isPriorDebt: Boolean = false,
)

/**
 * Builds the CSV export of a customer's account statement (Fase 5).
 *
 * Pure integer arithmetic over cents; the only decimal conversion is the same
 * BigDecimal.valueOf(cents, 2) boundary the UI uses. The running balance follows the ledger's
 * convention: DEBIT increases what the customer owes, CREDIT decreases it.
 *
 * Scope note: entries must belong to ONE customer. A statement never mixes customers — the master
 * prompt forbids exposing other clients' data in an export.
 */
object CustomerStatementCsv {
    private const val HEADER = "Fecha,Tipo,Cargo,Abono,Saldo,Notas"

    private val TYPE_LABELS = mapOf(
        "CHARGE" to "Cargo",
        "PAYMENT" to "Abono",
        "ADJUSTMENT" to "Ajuste",
        "REFUND" to "Devolución",
        "CANCELLATION" to "Cancelación",
    )

    /** Entries must already be in chronological order; the running balance depends on it. */
    fun build(entries: List<StatementEntry>): String {
        val lines = StringBuilder(HEADER)
        var balanceCents = 0L
        entries.forEach { entry ->
            require(entry.amountCents > 0) { "Statement amounts are magnitudes; sign comes from direction." }
            val isDebit = when (entry.direction) {
                "DEBIT" -> true
                "CREDIT" -> false
                else -> throw IllegalArgumentException("Unknown direction: ${entry.direction}")
            }
            balanceCents += if (isDebit) entry.amountCents else -entry.amountCents
            lines.append('\n')
                .append(escape(entry.dateIso)).append(',')
                .append(escape(TYPE_LABELS[entry.type] ?: entry.type)).append(',')
                .append(if (isDebit) pesos(entry.amountCents) else "").append(',')
                .append(if (isDebit) "" else pesos(entry.amountCents)).append(',')
                .append(pesos(balanceCents)).append(',')
                .append(escape(entry.notes.orEmpty()))
        }
        return lines.toString()
    }

    /** "15050" cents → "150.50". Negative balances keep their sign. */
    private fun pesos(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
