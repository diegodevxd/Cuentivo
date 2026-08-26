package com.cuentivo.app.billing

/**
 * Pure interface for submitting an opaque purchase token to the server-side
 * `verifyAiSubscription` callable for verification against Google Play Developer API.
 *
 * **Project Rule 4:** Purchase tokens are NEVER logged, serialized into crash reports,
 * or exposed in analytics.
 */
interface SubscriptionVerificationGateway {
    /**
     * Submits the purchase token for server-side verification.
     * Fail-closed: failures never grant entitlement locally.
     */
    fun verifySubscription(
        purchaseToken: String,
        productId: String,
        onComplete: (Result<SubscriptionVerificationResult>) -> Unit = {},
    )
}

data class SubscriptionVerificationResult(
    val status: String,
    val enabled: Boolean,
    val expiresAtEpochMillis: Long,
)
