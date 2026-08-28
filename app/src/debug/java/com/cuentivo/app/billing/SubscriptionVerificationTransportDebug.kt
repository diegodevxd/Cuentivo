package com.cuentivo.app.billing

/**
 * Debug source-set entry point returning the real regional callable transport.
 */
fun subscriptionVerificationGateway(): SubscriptionVerificationGateway =
    FirebaseCallableSubscriptionVerificationGateway()
