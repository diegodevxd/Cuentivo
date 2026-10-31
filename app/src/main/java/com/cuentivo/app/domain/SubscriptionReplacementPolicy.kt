package com.cuentivo.app.domain

/**
 * Pure policy governing subscription plan changes (upgrades, downgrades, and new purchases).
 *
 * **Core rule:** No flow in the app may ever leave a customer with two active subscriptions
 * charging simultaneously (e.g. Pro $199 + AI $299). Any change of subscription plan MUST
 * replace the previous subscription.
 *
 * **Proration / Replacement Policy:**
 * - **Upgrades (Pro -> AI):** [ReplacementMode.CHARGE_PRORATED]
 *   *Rationale:* Charging the prorated price difference immediately grants instant access to AI
 *   capabilities without waiting for the next billing cycle or paying double for overlapping days.
 * - **Downgrades (AI -> Pro):** [ReplacementMode.DEFERRED]
 *   *Rationale:* The customer has already paid for the current AI cycle. Deferring the downgrade
 *   allows them to use AI until the current period expires, switching to Pro at the next renewal.
 */
object SubscriptionReplacementPolicy {

    /**
     * Resolves the replacement mode when transitioning from [currentProductId] to [targetProductId].
     *
     * @param currentProductId the product ID of the currently active subscription (or null if none).
     * @param targetProductId the product ID of the subscription being purchased.
     * @return the [ReplacementMode], or null if this is a fresh purchase rather than a replacement.
     */
    fun replacementModeFor(currentProductId: String?, targetProductId: String): ReplacementMode? {
        if (currentProductId == null || currentProductId == targetProductId) return null
        return when {
            currentProductId == SubscriptionTier.PRO_PRODUCT_ID &&
                targetProductId == SubscriptionTier.AI_PRODUCT_ID -> ReplacementMode.CHARGE_PRORATED
            currentProductId == SubscriptionTier.AI_PRODUCT_ID &&
                targetProductId == SubscriptionTier.PRO_PRODUCT_ID -> ReplacementMode.DEFERRED
            else -> ReplacementMode.CHARGE_PRORATED
        }
    }

    /**
     * Determines whether [targetProductId] is already active for [currentUserAccountHash] in [activePurchases].
     */
    fun isProductAlreadyActive(
        activePurchases: List<PlayPurchase>,
        targetProductId: String,
        currentUserAccountHash: String?,
    ): Boolean {
        val targetTier = when (targetProductId) {
            SubscriptionTier.AI_PRODUCT_ID -> SubscriptionTier.AI
            SubscriptionTier.PRO_PRODUCT_ID -> SubscriptionTier.PRO
            else -> null
        } ?: return false
        return activePurchases.any { purchase ->
            purchase.isPurchased &&
                com.cuentivo.app.domain.SubscriptionStatus.run { purchase.grantedTier(currentUserAccountHash) } == targetTier
        }
    }

    /**
     * Finds the primary active subscription product ID that should be replaced when buying [targetProductId]
     * for [currentUserAccountHash].
     *
     * In transient states where Play reports multiple active purchases (e.g. during an in-flight replacement),
     * this selects the highest-ranking active subscription that differs from [targetProductId].
     */
    fun findProductToReplace(
        activePurchases: List<PlayPurchase>,
        targetProductId: String,
        currentUserAccountHash: String?,
    ): String? {
        val activeTiers = activePurchases
            .filter { it.isPurchased }
            .mapNotNull { purchase ->
                com.cuentivo.app.domain.SubscriptionStatus.run { purchase.grantedTier(currentUserAccountHash) }
            }

        return when {
            targetProductId == SubscriptionTier.AI_PRODUCT_ID && activeTiers.contains(SubscriptionTier.PRO) ->
                SubscriptionTier.PRO_PRODUCT_ID
            targetProductId == SubscriptionTier.PRO_PRODUCT_ID && activeTiers.contains(SubscriptionTier.AI) ->
                SubscriptionTier.AI_PRODUCT_ID
            else -> null
        }
    }
}

/**
 * Proration / replacement mode for transitioning between subscription tiers.
 */
enum class ReplacementMode {
    /** Charge prorated price difference immediately for instant upgrade. */
    CHARGE_PRORATED,

    /** Defer replacement until the next billing renewal for downgrade. */
    DEFERRED,
}
