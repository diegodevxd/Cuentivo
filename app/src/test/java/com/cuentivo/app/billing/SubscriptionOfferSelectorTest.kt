package com.cuentivo.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionOfferSelectorTest {

    private val base = OfferCandidate("base", listOf(OfferPhase(299_000_000, "$299.00", "P1M")))
    private fun trial(token: String, period: String) = OfferCandidate(
        token,
        listOf(OfferPhase(0, "Gratis", period), OfferPhase(299_000_000, "$299.00", "P1M")),
    )

    @Test
    fun trial_offer_wins_whatever_the_order_and_price_is_the_recurring_one() {
        val picked = SubscriptionOfferSelector.select(listOf(base, trial("trial", "P2M")))!!
        assertEquals("trial", picked.offerToken)
        assertEquals("$299.00", picked.recurringPrice)
        assertEquals(2, picked.freeTrialMonths)
    }

    @Test
    fun trial_listed_first_is_also_picked() {
        val picked = SubscriptionOfferSelector.select(listOf(trial("trial", "P3M"), base))!!
        assertEquals("trial", picked.offerToken)
        assertEquals(3, picked.freeTrialMonths)
    }

    @Test
    fun without_trial_eligibility_falls_back_to_base_plan() {
        val picked = SubscriptionOfferSelector.select(listOf(base))!!
        assertEquals("base", picked.offerToken)
        assertEquals(0, picked.freeTrialMonths)
    }

    @Test
    fun trial_not_in_whole_months_is_still_bought_but_not_labelled() {
        val picked = SubscriptionOfferSelector.select(listOf(trial("trial", "P14D")))!!
        assertEquals("trial", picked.offerToken)
        assertEquals(0, picked.freeTrialMonths)
    }

    @Test
    fun no_usable_offer_returns_null() {
        assertNull(SubscriptionOfferSelector.select(emptyList()))
        assertNull(SubscriptionOfferSelector.select(listOf(OfferCandidate("x", emptyList()))))
    }
}
