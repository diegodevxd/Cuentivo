package com.cuentivo.app.ui.customer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The customer's history reads the debt, not the till. These expectations are intentionally the
 * mirror of `DashboardPresentationTest`, where a sale is unsigned and a payment is `+` green.
 */
class CustomerLedgerPresentationTest {
    @Test
    fun a_credit_sale_is_debt_the_customer_owes() {
        assertEquals(
            LedgerVisual("Venta", "−", "Deuda", LedgerTone.DEBT),
            CustomerLedgerPresentation.from("CHARGE", "DEBIT"),
        )
    }

    @Test
    fun a_payment_lowers_the_debt() {
        assertEquals(
            LedgerVisual("Abono a cuenta", "+", "Abono", LedgerTone.CREDIT),
            CustomerLedgerPresentation.from("PAYMENT", "CREDIT"),
        )
    }

    @Test
    fun a_refund_reads_as_a_canceled_abono_not_a_second_charge() {
        assertEquals(
            LedgerVisual("Abono cancelado", "−", "Corrección", LedgerTone.DEBT),
            CustomerLedgerPresentation.from("REFUND", "DEBIT"),
        )
    }

    @Test
    fun a_cancelled_sale_reads_as_a_correction_not_a_payment() {
        assertEquals(
            LedgerVisual("Venta cancelada", "+", "Corrección", LedgerTone.CREDIT),
            CustomerLedgerPresentation.from("CANCELLATION", "CREDIT"),
        )
    }

    @Test
    fun adjustments_are_named_by_their_direction() {
        assertEquals(
            LedgerVisual("Ajuste", "−", "Ajuste de deuda", LedgerTone.DEBT),
            CustomerLedgerPresentation.from("ADJUSTMENT", "DEBIT"),
        )
        assertEquals(
            LedgerVisual("Ajuste", "+", "Ajuste a favor", LedgerTone.CREDIT),
            CustomerLedgerPresentation.from("ADJUSTMENT", "CREDIT"),
        )
    }

    @Test
    fun a_prior_debt_is_not_a_sale_made_in_the_app() {
        // Same arithmetic as a charge — DEBIT raises the debt — but it is a debt the person already
        // had, so the history must not claim a sale with a cart.
        assertEquals(
            LedgerVisual("Deuda anterior", "−", "Deuda anterior", LedgerTone.DEBT, isPriorDebt = true),
            CustomerLedgerPresentation.from("CHARGE", "DEBIT", isPriorDebt = true),
        )
        // The flag is what decides it, not the type: a normal charge stays a sale.
        assertEquals(
            LedgerVisual("Venta", "−", "Deuda", LedgerTone.DEBT),
            CustomerLedgerPresentation.from("CHARGE", "DEBIT", isPriorDebt = false),
        )
    }

    @Test
    fun an_unknown_type_still_gets_the_arithmetic_right() {
        // Wording degrades, sign does not: direction alone decides which way the debt moves.
        assertEquals(
            LedgerVisual("Movimiento", "−", "Deuda", LedgerTone.DEBT),
            CustomerLedgerPresentation.from("SOMETHING_NEW", "DEBIT"),
        )
        assertEquals(
            LedgerVisual("Movimiento", "+", "Abono", LedgerTone.CREDIT),
            CustomerLedgerPresentation.from("SOMETHING_NEW", "CREDIT"),
        )
    }
}
