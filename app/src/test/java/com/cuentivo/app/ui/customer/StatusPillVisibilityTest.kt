package com.cuentivo.app.ui.customer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail hero must not say "Saldo pendiente" twice: the pill shows only when it adds a date. */
class StatusPillVisibilityTest {

    @Test
    fun pending_without_due_date_repeats_the_balance_kind_so_it_is_hidden() {
        assertFalse(CustomerStatus(CustomerTone.PENDING).addsToBalanceKind())
    }

    @Test
    fun credit_repeats_saldo_a_favor_so_it_is_hidden() {
        assertFalse(CustomerStatus(CustomerTone.CREDIT).addsToBalanceKind())
    }

    @Test
    fun pending_with_a_due_date_says_when_so_it_is_shown() {
        assertTrue(CustomerStatus(CustomerTone.PENDING, daysUntilDue = 0).addsToBalanceKind())
        assertTrue(CustomerStatus(CustomerTone.PENDING, daysUntilDue = 5).addsToBalanceKind())
    }

    @Test
    fun overdue_and_settled_are_always_shown() {
        assertTrue(CustomerStatus(CustomerTone.OVERDUE, overdueDays = 3).addsToBalanceKind())
        assertTrue(CustomerStatus(CustomerTone.SETTLED).addsToBalanceKind())
    }
}
