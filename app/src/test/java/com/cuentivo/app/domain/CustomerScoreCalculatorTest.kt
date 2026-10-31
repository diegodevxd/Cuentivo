package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerScoreCalculatorTest {

    @Test
    fun two_sales_are_not_enough_evidence_to_call_anyone_reliable() {
        val score = CustomerScoreCalculator.score(
            listOf(paid(dueDay = 100, onDay = 100), paid(dueDay = 110, onDay = 110)),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.NO_HISTORY, score.reliability)
        assertEquals(2, score.ratedSaleCount)
    }

    @Test
    fun paying_on_the_agreed_date_is_punctual() {
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 100),
                paid(dueDay = 110, onDay = 110),
                paid(dueDay = 120, onDay = 120),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.PUNCTUAL, score.reliability)
        assertEquals(0, score.averageDaysLate)
    }

    @Test
    fun paying_early_counts_as_on_time_not_as_credit_for_later() {
        // Two weeks early, then eight days late. Were the early payment allowed to go negative the
        // average would land at zero and this customer would read as punctual.
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 86),
                paid(dueDay = 110, onDay = 118),
                paid(dueDay = 120, onDay = 128),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.SLOW, score.reliability)
        assertEquals(5, score.averageDaysLate)
    }

    @Test
    fun paying_weeks_late_every_time_is_delinquent() {
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 130),
                paid(dueDay = 110, onDay = 145),
                paid(dueDay = 120, onDay = 150),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.DELINQUENT, score.reliability)
        // 30, 35 and 30 days late: 95 / 3, truncated like all the money arithmetic here.
        assertEquals(31, score.averageDaysLate)
    }

    @Test
    fun an_open_sale_is_measured_against_today_and_keeps_growing() {
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 100),
                paid(dueDay = 110, onDay = 110),
                open(dueDay = TODAY - 10),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(10, score.worstDaysLate)
        assertEquals(3, score.averageDaysLate)
        assertEquals(PaymentReliability.PUNCTUAL, score.reliability)
    }

    @Test
    fun a_debt_nobody_has_touched_in_two_months_outranks_a_good_average() {
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 100),
                paid(dueDay = 110, onDay = 110),
                paid(dueDay = 120, onDay = 120),
                paid(dueDay = 130, onDay = 130),
                paid(dueDay = 140, onDay = 140),
                open(dueDay = TODAY - 90),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.DELINQUENT, score.reliability)
        assertEquals(90, score.worstDaysLate)
    }

    @Test
    fun an_old_sale_that_was_eventually_paid_late_is_history_not_an_abandoned_debt() {
        // Same 90 days as the case above, but settled. Nothing is standing still, so the average
        // decides — and it must not be forced to DELINQUENT by the abandoned-debt rule.
        val score = CustomerScoreCalculator.score(
            listOf(
                paid(dueDay = 100, onDay = 190),
                paid(dueDay = 110, onDay = 110),
                paid(dueDay = 120, onDay = 120),
                open(dueDay = TODAY + 30),
            ),
            todayEpochDay = TODAY,
        )

        assertEquals(PaymentReliability.DELINQUENT, score.reliability)
        assertEquals(90, score.worstDaysLate)
    }

    @Test
    fun a_sale_with_no_agreed_date_cannot_be_late_so_it_is_not_evidence() {
        val undated = CustomerSaleHistory(
            dueDateEpochDay = null,
            totalCents = 50_000,
            allocatedCents = 50_000,
            lastPaymentDateEpochDay = 100,
        )
        val score = CustomerScoreCalculator.score(
            listOf(paid(dueDay = 100, onDay = 100), paid(dueDay = 110, onDay = 110), undated),
            todayEpochDay = TODAY,
        )

        // Three sales in, but only two carry a date: still not enough to hand out a color.
        assertEquals(PaymentReliability.NO_HISTORY, score.reliability)
        assertEquals(2, score.ratedSaleCount)
    }

    @Test
    fun a_sale_worth_nothing_that_nobody_paid_says_nothing_about_the_customer() {
        val freebie = CustomerSaleHistory(
            dueDateEpochDay = 100,
            totalCents = 0,
            allocatedCents = 0,
            lastPaymentDateEpochDay = null,
        )
        val score = CustomerScoreCalculator.score(
            listOf(paid(dueDay = 100, onDay = 100), paid(dueDay = 110, onDay = 110), freebie),
            todayEpochDay = TODAY,
        )

        assertEquals(2, score.ratedSaleCount)
        assertEquals(PaymentReliability.NO_HISTORY, score.reliability)
    }

    @Test
    fun a_customer_with_no_sales_at_all_has_no_history() {
        val score = CustomerScoreCalculator.score(emptyList(), todayEpochDay = TODAY)

        assertEquals(PaymentReliability.NO_HISTORY, score.reliability)
        assertEquals(0, score.ratedSaleCount)
        assertEquals(0, score.worstDaysLate)
    }

    private fun paid(dueDay: Long, onDay: Long) = CustomerSaleHistory(
        dueDateEpochDay = dueDay,
        totalCents = 50_000,
        allocatedCents = 50_000,
        lastPaymentDateEpochDay = onDay,
    )

    private fun open(dueDay: Long) = CustomerSaleHistory(
        dueDateEpochDay = dueDay,
        totalCents = 50_000,
        allocatedCents = 10_000,
        lastPaymentDateEpochDay = null,
    )

    private companion object {
        const val TODAY = 20_000L
    }
}
