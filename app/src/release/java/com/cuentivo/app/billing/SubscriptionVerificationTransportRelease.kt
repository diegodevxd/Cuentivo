package com.cuentivo.app.billing

/**
 * Release source-set entry point returning the real regional callable transport.
 */
fun subscriptionVerificationGateway(): SubscriptionVerificationGateway =
    FirebaseCallableSubscriptionVerificationGateway()
