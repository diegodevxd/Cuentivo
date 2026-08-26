package com.cuentivo.app.billing

/**
 * Release implementation of [SubscriptionVerificationGateway].
 */
private object NoOpSubscriptionVerificationGateway : SubscriptionVerificationGateway {
    override fun verifySubscription(
        purchaseToken: String,
        productId: String,
        onComplete: (Result<SubscriptionVerificationResult>) -> Unit,
    ) {
        onComplete(Result.failure(IllegalStateException("Subscription verification callable not configured in release build yet")))
    }
}

/**
 * Release source-set entry point.
 */
fun subscriptionVerificationGateway(): SubscriptionVerificationGateway =
    NoOpSubscriptionVerificationGateway
