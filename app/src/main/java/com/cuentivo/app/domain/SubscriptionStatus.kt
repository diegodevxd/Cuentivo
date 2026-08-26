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

    /**
     * The highest plan any completed purchase grants.
     *
     * Highest, not first: upgrading from Pro to CuentivoAI leaves both subscriptions reported for a
     * while, and answering PRO there would take the assistant away from someone already paying for
     * it.
     *
     * Ranked by [SubscriptionTier.rank], never by declaration order: what someone is entitled to is
     * a commercial rule, and it must not change because a constant was moved for readability.
     */
    fun tierFrom(purchases: List<PlayPurchase>): SubscriptionTier =
        purchases.filter { it.isPurchased }
            .mapNotNull { it.grantedTier() }
            .maxByOrNull { it.rank }
            ?: SubscriptionTier.FREE

    /**
     * Purchases the app still has to confirm to Google.
     *
     * **Play automatically refunds any purchase left unacknowledged for three days.** So this is not
     * bookkeeping: skipping it means the first subscribers pay, get Pro, and then quietly get their
     * money back while the app goes on treating them as subscribers.
     */
    fun needsAcknowledgement(purchases: List<PlayPurchase>): List<PlayPurchase> =
        purchases.filter { it.isPurchased && it.grantedTier() != null && !it.isAcknowledged }

    /**
     * Access is granted before acknowledgement, never after.
     *
     * Google's own order is: deliver the entitlement, then acknowledge. Waiting for the
     * acknowledgement round trip would leave someone who just paid staring at the free plan.
     */
    private fun PlayPurchase.grantedTier(): SubscriptionTier? = when {
        productIds.contains(SubscriptionTier.AI_PRODUCT_ID) -> SubscriptionTier.AI
        productIds.contains(SubscriptionTier.PRO_PRODUCT_ID) -> SubscriptionTier.PRO
        // Anything else Play reports is unrelated to a plan and grants nothing.
        else -> null
    }
}
