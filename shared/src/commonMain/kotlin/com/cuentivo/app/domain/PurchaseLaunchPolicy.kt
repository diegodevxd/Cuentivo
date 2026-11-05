package com.cuentivo.app.domain

/**
 * State of Google Play purchases query for a specific account.
 */
sealed interface PurchasesQueryState {
    /** The gateway has not yet received a verified purchases response from Play. */
    data object Pending : PurchasesQueryState

    /** The purchases query failed or the billing connection was unavailable. */
    data object Failed : PurchasesQueryState

    /**
     * A verified, confirmed snapshot of purchases returned by Play for [queriedUserId].
     *
     * @param purchases the active purchases confirmed by Play.
     * @param queriedUserId the user ID on whose behalf the query was executed.
     */
    data class Resolved(
        val purchases: List<PlayPurchase>,
        val queriedUserId: String?,
    ) : PurchasesQueryState
}

/**
 * Reason why a subscription purchase launch was blocked.
 */
enum class PurchaseBlockReason {
    /** Play purchases query is still in flight; cannot verify active subscriptions. */
    QUERY_PENDING,

    /** Play purchases query failed; cannot safely verify active subscriptions. */
    QUERY_FAILED,

    /** The signed-in user changed between the query snapshot and the purchase attempt. */
    USER_MISMATCH,

    /** No user ID is bound; purchases cannot be attributed to an account. */
    MISSING_USER_ID,

    /** The target product is already active on this account. */
    ALREADY_ACTIVE,

    /** Both Pro and AI are active simultaneously (in-flight replacement or inconsistent state). */
    INCONSISTENT_STATE,

    /** The target product is unknown or not a purchasable subscription. */
    UNKNOWN_PRODUCT,

    /** Replacement requested but no valid proration / replacement mode exists. */
    INVALID_REPLACEMENT,

    /** The current session is an anonymous guest; a persistent account is required before purchasing. */
    ANONYMOUS_SESSION,
}

/**
 * Decision determining whether a purchase flow may proceed or must be blocked.
 */
sealed interface PurchaseLaunchDecision {
    /**
     * The purchase flow is safe to launch with the specified parameters.
     *
     * @param targetProductId the product being purchased.
     * @param productToReplace the product ID of an existing subscription to replace, or null for a new subscription.
     * @param replacementMode the proration policy to apply when replacing, or null for a new subscription.
     * @param obfuscatedAccountId the deterministic SHA-256 hash of the user ID for Play Billing.
     */
    data class Proceed(
        val targetProductId: String,
        val productToReplace: String?,
        val replacementMode: ReplacementMode?,
        val obfuscatedAccountId: String,
    ) : PurchaseLaunchDecision

    /**
     * The purchase flow MUST NOT be opened.
     *
     * @param reason the technical safety reason why the purchase was blocked.
     */
    data class Blocked(val reason: PurchaseBlockReason) : PurchaseLaunchDecision
}

/**
 * Pure policy evaluating whether launching a subscription purchase is safe.
 *
 * **Core invariant:** No purchase sheet may ever be launched from stale, pending, or failed
 * query states, and no flow may ever open a second subscription when an active subscription exists.
 */
object PurchaseLaunchPolicy {

    /**
     * Evaluates whether launching a purchase for [targetProductId] on behalf of [currentUserId]
     * is safe given the confirmed [queryState].
     *
     * @param isAnonymous whether the current session is an anonymous guest session. Anonymous
     * sessions MUST NOT purchase subscriptions because backend entitlement cannot be granted.
     */
    fun evaluate(
        queryState: PurchasesQueryState,
        currentUserId: String?,
        targetProductId: String,
        isAnonymous: Boolean = false,
    ): PurchaseLaunchDecision {
        if (isAnonymous) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ANONYMOUS_SESSION)
        }

        if (currentUserId.isNullOrBlank()) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.MISSING_USER_ID)
        }

        if (targetProductId != SubscriptionTier.PRO_PRODUCT_ID && targetProductId != SubscriptionTier.AI_PRODUCT_ID) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.UNKNOWN_PRODUCT)
        }

        val resolved = when (queryState) {
            PurchasesQueryState.Pending -> return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.QUERY_PENDING)
            PurchasesQueryState.Failed -> return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.QUERY_FAILED)
            is PurchasesQueryState.Resolved -> queryState
        }

        if (resolved.queriedUserId != currentUserId) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.USER_MISMATCH)
        }

        val obfuscatedAccountId = BillingAccountHasher.obfuscate(currentUserId)
            ?: return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.MISSING_USER_ID)

        val activePurchases = resolved.purchases.filter { it.isPurchased }

        // If the target product is already active for this account, block double purchase.
        if (SubscriptionReplacementPolicy.isProductAlreadyActive(activePurchases, targetProductId, obfuscatedAccountId)) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ALREADY_ACTIVE)
        }

        val activeTiers = activePurchases
            .mapNotNull { purchase ->
                com.cuentivo.app.domain.SubscriptionStatus.run { purchase.grantedTier(obfuscatedAccountId) }
            }
            .distinct()

        // If both Pro and AI are active simultaneously for this account, treat as inconsistent / in-flight state.
        val hasPro = activeTiers.contains(SubscriptionTier.PRO)
        val hasAi = activeTiers.contains(SubscriptionTier.AI)
        if (hasPro && hasAi) {
            return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.INCONSISTENT_STATE)
        }

        val productToReplace = SubscriptionReplacementPolicy.findProductToReplace(
            activePurchases = activePurchases,
            targetProductId = targetProductId,
            currentUserAccountHash = obfuscatedAccountId,
        )

        if (productToReplace != null) {
            val replacementMode = SubscriptionReplacementPolicy.replacementModeFor(
                currentProductId = productToReplace,
                targetProductId = targetProductId,
            ) ?: return PurchaseLaunchDecision.Blocked(PurchaseBlockReason.INVALID_REPLACEMENT)

            return PurchaseLaunchDecision.Proceed(
                targetProductId = targetProductId,
                productToReplace = productToReplace,
                replacementMode = replacementMode,
                obfuscatedAccountId = obfuscatedAccountId,
            )
        }

        return PurchaseLaunchDecision.Proceed(
            targetProductId = targetProductId,
            productToReplace = null,
            replacementMode = null,
            obfuscatedAccountId = obfuscatedAccountId,
        )
    }
}
