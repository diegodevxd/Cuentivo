package com.cuentivo.app.domain

/**
 * One question asked of Google Play, bound to the account it was asked for.
 *
 * Play answers out of order and whenever it likes. Holding the asking account in a field that the
 * next request overwrites means a slow reply gets read against whoever is current by then — start a
 * query for Ana, start one for Beto, let Ana's answer land, and Ana's subscription is handed to
 * Beto. That is not hypothetical: it was the shape of this code before.
 *
 * So each request is its own immutable value, captured when the query starts and carried into the
 * callback by the closure. There is no shared variable left to read.
 */
data class BillingRequest(val userId: String?) {

    /**
     * Resolves this request's answer against whoever is signed in *now*.
     *
     * [EntitlementReconciler] does the deciding; this type's whole job is making sure the uid handed
     * to it is the one that asked, not the one that happens to be current when the reply lands.
     */
    fun reply(
        reported: SubscriptionTier,
        currentUserId: String?,
    ): EntitlementReconciler.Decision = EntitlementReconciler.resolve(
        reported = reported,
        sessionUserId = userId,
        currentUserId = currentUserId,
    )
}
