package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact reproduction from the tester report: a $5,000 debt, a $2,000 abono, then that abono
 * gets cancelled. The three lines used to read like three unrelated events; these checks pin down
 * the numbers the fixed screen must show.
 */
class CustomerStatementSummaryTest {
    private fun entry(
        type: String,
        direction: String,
        amountCents: Long,
        paymentId: String? = null,
        dateIso: String = "2026-08-01",
    ) = StatementEntry(dateIso, type, direction, amountCents, notes = null, paymentId = paymentId)

    @Test
    fun totals_match_the_five_thousand_debt_report() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 500_000),
            entry("PAYMENT", "CREDIT", 200_000, paymentId = "p1"),
            entry("REFUND", "DEBIT", 200_000, paymentId = "p1"),
        )
        val totals = CustomerStatementSummary.totals(entries)
        // Netted, not gross: the cancelled abono must not inflate "Le vendiste". The customer was
        // sold $5,000 and, net of the cancellation, has paid nothing — which is exactly what still
        // leaves them owing the full $5,000.
        assertEquals(500_000L, totals.totalChargedCents)
        assertEquals(0L, totals.totalPaidCents)
        assertEquals(500_000L, totals.balanceCents)
    }

    @Test
    fun a_canceled_sale_nets_out_of_both_totals() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 500_000),
            entry("CANCELLATION", "CREDIT", 500_000),
        )
        val totals = CustomerStatementSummary.totals(entries)
        assertEquals(0L, totals.totalChargedCents)
        assertEquals(0L, totals.totalPaidCents)
        assertEquals(0L, totals.balanceCents)
    }

    @Test
    fun totals_balance_always_matches_the_ledger_debit_minus_credit_formula() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 800_000),
            entry("PAYMENT", "CREDIT", 300_000, paymentId = "p1"),
            entry("REFUND", "DEBIT", 300_000, paymentId = "p1"),
            entry("CANCELLATION", "CREDIT", 200_000),
            entry("ADJUSTMENT", "DEBIT", 10_000),
            entry("ADJUSTMENT", "CREDIT", 5_000),
        )
        val expectedBalance = entries.sumOf { if (it.direction == "DEBIT") it.amountCents else -it.amountCents }
        val totals = CustomerStatementSummary.totals(entries)
        assertEquals(expectedBalance, totals.balanceCents)
    }

    @Test
    fun running_balance_walks_the_five_thousand_debt_report_line_by_line() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 500_000),
            entry("PAYMENT", "CREDIT", 200_000, paymentId = "p1"),
            entry("REFUND", "DEBIT", 200_000, paymentId = "p1"),
        )
        assertEquals(
            listOf(500_000L, 300_000L, 500_000L),
            CustomerStatementSummary.runningBalances(entries),
        )
    }

    @Test
    fun canceled_payment_ids_come_only_from_refund_rows() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 500_000),
            entry("PAYMENT", "CREDIT", 200_000, paymentId = "p1"),
            entry("PAYMENT", "CREDIT", 100_000, paymentId = "p2"),
            entry("REFUND", "DEBIT", 200_000, paymentId = "p1"),
        )
        assertEquals(setOf("p1"), CustomerStatementSummary.canceledPaymentIds(entries))
    }

    @Test
    fun totals_with_no_cancellation_have_no_canceled_ids() {
        val entries = listOf(
            entry("CHARGE", "DEBIT", 150_000),
            entry("PAYMENT", "CREDIT", 50_000, paymentId = "p1"),
        )
        val totals = CustomerStatementSummary.totals(entries)
        assertEquals(150_000L, totals.totalChargedCents)
        assertEquals(50_000L, totals.totalPaidCents)
        assertEquals(100_000L, totals.balanceCents)
        assertEquals(emptySet<String>(), CustomerStatementSummary.canceledPaymentIds(entries))
    }
}
