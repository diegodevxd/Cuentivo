package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.DueSale
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPresentationTest {
    /**
     * The signs come from the approved reference, where a payment reads `+$250.00` in green and a
     * sale reads `$420.00` unsigned: the feed shows cash movement, not the balance direction.
     */
    @Test
    fun ledger_activity_types_keep_their_cash_meaning() {
        assertEquals(
            DashboardActivityVisual("Nueva venta", "", DashboardActivityAccent.SALE),
            DashboardActivityPresentation.from(type = "CHARGE", direction = "DEBIT"),
        )
        assertEquals(
            DashboardActivityVisual("Abono recibido", "+", DashboardActivityAccent.PAYMENT),
            DashboardActivityPresentation.from(type = "PAYMENT", direction = "CREDIT"),
        )
        assertEquals(
            DashboardActivityVisual("Reembolso enviado", "−", DashboardActivityAccent.REFUND),
            DashboardActivityPresentation.from(type = "REFUND", direction = "DEBIT"),
        )
    }

    /** Corrections must never be announced as a generic movement, and never move cash. */
    @Test
    fun cancellations_and_adjustments_are_named_without_a_cash_sign() {
        assertEquals(
            DashboardActivityVisual("Venta cancelada", "", DashboardActivityAccent.NEUTRAL),
            DashboardActivityPresentation.from(type = "CANCELLATION", direction = "CREDIT"),
        )
        assertEquals(
            DashboardActivityVisual("Ajuste a favor del cliente", "", DashboardActivityAccent.NEUTRAL),
            DashboardActivityPresentation.from(type = "ADJUSTMENT", direction = "CREDIT"),
        )
        assertEquals(
            DashboardActivityVisual("Ajuste de cargo", "", DashboardActivityAccent.NEUTRAL),
            DashboardActivityPresentation.from(type = "ADJUSTMENT", direction = "DEBIT"),
        )
    }

    /** A movement type from a newer client must degrade to a neutral label, not to a wrong sign. */
    @Test
    fun unknown_types_degrade_to_a_neutral_unsigned_label() {
        assertEquals(
            DashboardActivityVisual("Movimiento registrado", "", DashboardActivityAccent.NEUTRAL),
            DashboardActivityPresentation.from(type = "SOMETHING_NEW", direction = "DEBIT"),
        )
        assertEquals(
            DashboardActivityVisual("Movimiento a favor", "", DashboardActivityAccent.NEUTRAL),
            DashboardActivityPresentation.from(type = "SOMETHING_NEW", direction = "CREDIT"),
        )
    }

    @Test
    fun overdue_customers_come_before_larger_current_debts() {
        val result = DashboardPresentation.criticalCustomers(
            customers = listOf(balance("large", 50_000), balance("late", 10_000)),
            dueSales = listOf(DueSale("sale", "late", "Late", 99, 4_000)),
            todayEpochDay = 100,
            limit = 3,
        )

        assertEquals(listOf("late", "large"), result.map { it.customer.customer.id })
    }

    private fun balance(id: String, cents: Long) = CustomerBalanceRow(
        customer = customer(id, "business-1", id.replaceFirstChar { it.uppercase() }),
        balanceCents = cents,
    )

    private fun customer(id: String, businessId: String, name: String) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        deletedAtEpochMillis = null,
    )
}
