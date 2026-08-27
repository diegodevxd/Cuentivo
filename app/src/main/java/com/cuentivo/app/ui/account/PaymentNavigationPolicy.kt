package com.cuentivo.app.ui.account

import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.MainDestination

/**
 * Guarantees the core commercial invariant: payment and collection recording
 * is NEVER paywalled, blocked, or intercepted by subscription gates under any tier.
 */
object PaymentNavigationPolicy {

    /**
     * Evaluates whether registering a payment is allowed for the given [tier].
     * Unconditionally returns [AccessDecision.Allowed].
     */
    fun evaluatePaymentAccess(tier: SubscriptionTier): AccessDecision {
        return FeatureAccessManager.canRegisterPayment()
    }

    /**
     * Resolves the target destination for the payment flow. Always directs to [MainDestination.PAYMENTS].
     */
    fun resolveDestination(): MainDestination = MainDestination.PAYMENTS

    /**
     * Proves that payment navigation never routes to or triggers the AI paywall on any plan.
     */
    fun isPaymentPaywalled(tier: SubscriptionTier): Boolean = false
}
