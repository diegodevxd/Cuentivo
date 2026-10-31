package com.cuentivo.app.ui.customer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BalancePresentationTest {
    @Test
    fun positive_balance_is_money_owed() {
        assertEquals(BalancePresentation(BalanceKind.OWED, 250_00), BalancePresenter.from(250_00))
    }

    @Test
    fun zero_balance_is_settled() {
        assertEquals(BalancePresentation(BalanceKind.SETTLED, 0), BalancePresenter.from(0))
    }

    @Test
    fun negative_balance_is_customer_credit_with_absolute_amount() {
        assertEquals(
            BalancePresentation(BalanceKind.CUSTOMER_CREDIT, 250_00),
            BalancePresenter.from(-250_00),
        )
    }

    @Test
    fun archive_fallback_uses_positive_credit_magnitude() {
        val presentation = BalancePresenter.from(-20_000)

        assertEquals(BalanceKind.CUSTOMER_CREDIT, presentation.kind)
        assertEquals(20_000L, presentation.amountCents)
    }

    @Test
    fun minimum_long_balance_fails_explicitly_instead_of_returning_negative_magnitude() {
        assertThrows(IllegalArgumentException::class.java) {
            BalancePresenter.from(Long.MIN_VALUE)
        }
    }
}
