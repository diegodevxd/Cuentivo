package com.cuentivo.app.billing

/** One pricing phase of a Play offer, reduced to what the selector needs. */
data class OfferPhase(
    val priceAmountMicros: Long,
    val formattedPrice: String,
    /** ISO-8601 period as Play reports it, e.g. `P1M` or `P2M`. */
    val billingPeriod: String,
)

data class OfferCandidate(
    val offerToken: String,
    val phases: List<OfferPhase>,
)

/**
 * [recurringPrice] is always the price after any trial: the one the user keeps paying.
 * [freeTrialMonths] is 0 when the offer has no free trial (or one not expressed in whole months).
 */
data class SelectedOffer(
    val offerToken: String,
    val recurringPrice: String,
    val freeTrialMonths: Int,
)

/**
 * Chooses which Play offer to buy and which price to show.
 *
 * Free trials live in Play Console as an offer on the base plan, not in the app. Play returns only
 * the offers the user is eligible for, so if a trial offer is present it is the one to buy; when
 * the user already used it, only the base plan comes back and this falls through to it. Taking
 * `firstOrNull()` instead would depend on Play's ordering and would show the trial's `$0.00` as the
 * price.
 */
object SubscriptionOfferSelector {

    fun select(candidates: List<OfferCandidate>): SelectedOffer? {
        val usable = candidates.filter { it.phases.isNotEmpty() }
        val chosen = usable.firstOrNull { isFreeTrial(it) }
            ?: usable.firstOrNull { it.phases.size == 1 }
            ?: usable.firstOrNull()
            ?: return null
        return SelectedOffer(
            offerToken = chosen.offerToken,
            recurringPrice = chosen.phases.last().formattedPrice,
            freeTrialMonths = if (isFreeTrial(chosen)) wholeMonths(chosen.phases.first().billingPeriod) else 0,
        )
    }

    private fun isFreeTrial(offer: OfferCandidate): Boolean =
        offer.phases.size > 1 && offer.phases.first().priceAmountMicros == 0L

    private fun wholeMonths(isoPeriod: String): Int {
        val match = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?$").matchEntire(isoPeriod) ?: return 0
        val years = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        return years * 12 + months
    }
}
