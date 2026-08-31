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
    /**
     * Obfuscated account identifier (SHA-256 hash of Firebase UID) recorded by Play during purchase,
     * or null for legacy purchases made prior to T2.
     */
    val obfuscatedAccountId: String? = null,
)

/**
 * Turns what Play reports into the plan the app should behave as.
 *
 * Pure and closed by default: anything unrecognized, pending or unrelated leaves the person on FREE.
 * The mistake this guards against is the opposite one — unlocking Pro or AI for a purchase that has not
 * actually been paid for or that belongs to a different account.
 */
object SubscriptionStatus {

    /**
     * The highest plan any completed purchase grants for the specified [currentUserAccountHash].
     *
     * Rules:
     * - AI: strictly requires matching [currentUserAccountHash]. If [currentUserAccountHash] is null,
     *   or [PlayPurchase.obfuscatedAccountId] is null or does not match, grants nothing.
     * - Pro:
     *   - If [PlayPurchase.obfuscatedAccountId] is null (legacy purchase prior to T2), grants PRO.
     *   - If [PlayPurchase.obfuscatedAccountId] is non-null, grants PRO only if it matches [currentUserAccountHash].
     * - In all other cases (unrecognized product, pending purchase, mismatched user), falls back to FREE.
     */
    fun tierFrom(
        purchases: List<PlayPurchase>,
        currentUserAccountHash: String? = null,
    ): SubscriptionTier =
        purchases.filter { it.isPurchased }
            .mapNotNull { it.grantedTier(currentUserAccountHash) }
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
        purchases.filter { it.isPurchased && it.isRecognizedSubscription() && !it.isAcknowledged }

    private fun PlayPurchase.isRecognizedSubscription(): Boolean =
        productIds.contains(SubscriptionTier.AI_PRODUCT_ID) ||
            productIds.contains(SubscriptionTier.PRO_PRODUCT_ID)

    /**
     * Access is granted before acknowledgement, never after.
     */
    fun PlayPurchase.grantedTier(currentUserAccountHash: String? = null): SubscriptionTier? = when {
        productIds.contains(SubscriptionTier.AI_PRODUCT_ID) -> {
            if (!currentUserAccountHash.isNullOrBlank() && obfuscatedAccountId == currentUserAccountHash) {
                SubscriptionTier.AI
            } else {
                null
            }
        }
        productIds.contains(SubscriptionTier.PRO_PRODUCT_ID) -> {
            if (obfuscatedAccountId.isNullOrBlank() || (!currentUserAccountHash.isNullOrBlank() && obfuscatedAccountId == currentUserAccountHash)) {
                SubscriptionTier.PRO
            } else {
                null
            }
        }
        else -> null
    }
}
