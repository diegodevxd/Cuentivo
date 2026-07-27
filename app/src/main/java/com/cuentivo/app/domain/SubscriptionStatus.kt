package com.cuentivo.app.domain

/**
 * What Google Play says about one purchase, reduced to the three facts that decide anything.
 *
 * Deliberately not Play's own `Purchase` class: keeping this a plain data class is what lets the
 * rules below run under testDebugUnitTest instead of needing a device and a real store account.
 */
data class PlayPurchase(
    val productIds: List<String>,
    /** False while Play is still collecting the money — cash payment methods take hours or days. */
    val isPurchased: Boolean,
    /** False until the app confirms back to Play that it delivered what was paid for. */
    val isAcknowledged: Boolean,
)

/**
 * Turns what Play reports into the plan the app should behave as.
 *
 * Pure and closed by default: anything unrecognized, pending or unrelated leaves the person on FREE.
 * The mistake this guards against is the opposite one — unlocking Pro for a purchase that has not
 * actually been paid for yet.
 */
object SubscriptionStatus {

    fun tierFrom(purchases: List<PlayPurchase>): SubscriptionTier =
        if (purchases.any { it.isPurchased && it.grantsPro() }) SubscriptionTier.PRO else SubscriptionTier.FREE

    /**
     * Purchases the app still has to confirm to Google.
     *
     * **Play automatically refunds any purchase left unacknowledged for three days.** So this is not
     * bookkeeping: skipping it means the first subscribers pay, get Pro, and then quietly get their
     * money back while the app goes on treating them as subscribers.
     */
    fun needsAcknowledgement(purchases: List<PlayPurchase>): List<PlayPurchase> =
        purchases.filter { it.isPurchased && it.grantsPro() && !it.isAcknowledged }

    /**
     * Access is granted before acknowledgement, never after.
     *
     * Google's own order is: deliver the entitlement, then acknowledge. Waiting for the
     * acknowledgement round trip would leave someone who just paid staring at the free plan.
     */
    private fun PlayPurchase.grantsPro(): Boolean =
        productIds.contains(SubscriptionTier.PRO_PRODUCT_ID)
}
