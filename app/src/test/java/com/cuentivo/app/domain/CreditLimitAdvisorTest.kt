package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditLimitAdvisorTest {
    @Test
    fun no_history_gets_no_suggestion() {
        val history = CreditHistory(
            settledSaleCount = 0,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 0L,
        )
        assertNull(CreditLimitAdvisor.suggest(100_000L, history))
    }

    @Test
    fun no_current_limit_gets_no_suggestion_even_with_a_perfect_history() {
        // creditLimitCents is null for every customer today — that means unlimited credit, not "no
        // limit set yet". Suggesting a number here would impose the customer's first-ever ceiling,
        // which is the opposite of rewarding good behavior.
        val history = CreditHistory(
            settledSaleCount = 10,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 1_000_000L,
        )
        assertNull(CreditLimitAdvisor.suggest(null, history))
    }

    @Test
    fun three_settled_sales_with_a_clean_record_earn_a_step_up() {
        val history = CreditHistory(
            settledSaleCount = 3,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 300_000L,
        )
        // max(100_000 * 1.5, 300_000 * 1.2) = 360_000, already a multiple of 10_000.
        assertEquals(360_000L, CreditLimitAdvisor.suggest(100_000L, history))
    }

    @Test
    fun an_existing_limit_is_multiplied_when_it_is_the_larger_floor() {
        val history = CreditHistory(
            settledSaleCount = 5,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 100_000L,
        )
        // max(300_000 * 1.5, 100_000 * 1.2) = 450_000.
        assertEquals(450_000L, CreditLimitAdvisor.suggest(300_000L, history))
    }

    @Test
    fun a_canceled_sale_withholds_the_suggestion_even_with_enough_settled_sales() {
        val history = CreditHistory(
            settledSaleCount = 5,
            hasCanceledSale = true,
            hasLatePayment = false,
            largestSaleTotalCents = 500_000L,
        )
        assertNull(CreditLimitAdvisor.suggest(100_000L, history))
    }

    @Test
    fun a_late_payment_withholds_the_suggestion() {
        val history = CreditHistory(
            settledSaleCount = 10,
            hasCanceledSale = false,
            hasLatePayment = true,
            largestSaleTotalCents = 500_000L,
        )
        assertNull(CreditLimitAdvisor.suggest(100_000L, history))
    }

    @Test
    fun fewer_than_three_settled_sales_is_not_enough_history() {
        val history = CreditHistory(
            settledSaleCount = 2,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 1_000_000L,
        )
        assertNull(CreditLimitAdvisor.suggest(100_000L, history))
    }

    @Test
    fun a_non_round_amount_rounds_up_to_the_nearest_hundred_pesos() {
        val history = CreditHistory(
            settledSaleCount = 3,
            hasCanceledSale = false,
            hasLatePayment = false,
            largestSaleTotalCents = 123_450L,
        )
        // max(50_000 * 1.5, 123_450 * 1.2) = max(75_000, 148_140) = 148_140, rounds up to 150_000.
        assertEquals(150_000L, CreditLimitAdvisor.suggest(50_000L, history))
    }
}
