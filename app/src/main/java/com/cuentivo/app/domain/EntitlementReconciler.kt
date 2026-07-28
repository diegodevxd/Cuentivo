package com.cuentivo.app.domain

/**
 * What to do with an answer from Google Play, given who is signed in when it arrives.
 *
 * Play and Firebase Auth are two independent clocks. Billing can answer PRO while
 * `FirebaseAuth.currentUser` is still null on a cold start, and an answer can also land *after* the
 * person signed out and somebody else signed in. Both cases used to end with the screen showing Pro
 * while the sync gate — which reads the uid — said FREE; or worse, with one account's purchase
 * attributed to another account's uid.
 *
 * The rule is that an answer only counts for the session that asked for it. Anything else is
 * published as FREE and nothing is written down.
 */
object EntitlementReconciler {

    /**
     * @property publish the tier the UI should show right now.
     * @property persist whether this answer may be written against [userId].
     * @property userId the account the answer belongs to, when it belongs to one.
     */
    data class Decision(
        val publish: SubscriptionTier,
        val persist: Boolean,
        val userId: String? = null,
    )

    /**
     * @param reported what Play said.
     * @param sessionUserId who was signed in when this query was started, if anyone.
     * @param currentUserId who is signed in now, at the moment the answer arrived.
     */
    fun resolve(
        reported: SubscriptionTier,
        sessionUserId: String?,
        currentUserId: String?,
    ): Decision = when {
        // Nobody is signed in. A purchase belongs to an account and there is none to attribute it
        // to, so the app behaves as free until somebody signs in and we ask Play again.
        currentUserId.isNullOrBlank() -> Decision(SubscriptionTier.FREE, persist = false)

        // The answer was asked for by a different session: either it started before sign-in, or the
        // account changed while Play was thinking. Attributing it to whoever happens to be signed in
        // now is exactly how one person's subscription would unlock another person's data.
        sessionUserId != currentUserId -> Decision(SubscriptionTier.FREE, persist = false)

        else -> Decision(reported, persist = true, userId = currentUserId)
    }
}
