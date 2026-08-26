package com.cuentivo.app.domain

/**
 * Decides whether this device, right now, is allowed to talk to the cloud.
 *
 * Cloud backup and sync are a Pro benefit. That is a product decision, so the rule enforcing it is
 * kept here as pure logic: the storage that feeds it and the two places that ask it (scheduling and
 * the Worker itself) are thin Android glue, and this is the part that gets tested.
 *
 * **Closed by default in every direction.** No session, nothing stored, an unrecognised plan name,
 * or an entitlement remembered for a *different* account all resolve to FREE. The failure this
 * guards against is not "a paying customer waits a little for their sync" — it is one person's
 * ledger being uploaded under somebody else's entitlement.
 */
object SyncEntitlement {

    /**
     * @param storedUserId the account the entitlement was remembered for, if any.
     * @param storedTier the plan name as it was persisted; anything unknown degrades to FREE.
     * @param currentUserId who is signed in at this moment, or null when nobody is.
     */
    fun tierFor(storedUserId: String?, storedTier: String?, currentUserId: String?): SubscriptionTier = when {
        // Signed out. Whatever was bought before is not in force for nobody.
        currentUserId.isNullOrBlank() -> SubscriptionTier.FREE
        // Nothing has ever been remembered on this device.
        storedUserId.isNullOrBlank() -> SubscriptionTier.FREE
        // Someone else's entitlement. A shared phone must never lend Pro to the next account.
        storedUserId != currentUserId -> SubscriptionTier.FREE
        else -> SubscriptionTier.fromStoredValue(storedTier)
    }

    /** Whether a plan, on its own, includes the cloud. Used by the UI, which has no staleness. */
    fun allowsCloudSync(tier: SubscriptionTier): Boolean = tier.includesProBenefits

    /**
     * How long a confirmation from Play is trusted before the app insists on hearing it again.
     *
     * A subscription can lapse while the app sits closed for weeks. Without an expiry the stored
     * PRO would be believed forever and a background Worker would keep syncing for someone who
     * stopped paying. A day bounds that, and is long enough that a paying customer who opens the
     * app even occasionally never notices — every launch re-confirms.
     */
    const val MAX_CONFIRMATION_AGE_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Whether Play's answer is recent enough to act on.
     *
     * A confirmation dated in the future is rejected rather than trusted: it means the clock is
     * inconsistent, and accepting it would make winding the clock back a way to keep Pro forever.
     * Being strict costs little, because opening the app re-confirms.
     */
    fun isConfirmationFresh(
        verifiedAtEpochMillis: Long,
        nowEpochMillis: Long,
        maxAgeMillis: Long = MAX_CONFIRMATION_AGE_MILLIS,
    ): Boolean {
        val age = nowEpochMillis - verifiedAtEpochMillis
        return age in 0..maxAgeMillis
    }

    /**
     * The full question the sync paths ask: right account, **proven address**, paid plan, and a
     * recent confirmation.
     *
     * All four must hold. Failing any of them means no upload and no download — but never deleting
     * the outbox, so everything drains once the address is verified and Play confirms again.
     *
     * The email check is here rather than only in `firestore.rules` so an unverified account never
     * even spends attempts on writes the server is going to refuse. The server still refuses them:
     * this is the polite half, not the enforcing one.
     */
    fun allowsCloudSync(
        storedUserId: String?,
        storedTier: String?,
        verifiedAtEpochMillis: Long,
        currentUserId: String?,
        nowEpochMillis: Long,
        isEmailVerified: Boolean,
        maxAgeMillis: Long = MAX_CONFIRMATION_AGE_MILLIS,
    ): Boolean = isEmailVerified &&
        allowsCloudSync(tierFor(storedUserId, storedTier, currentUserId)) &&
        isConfirmationFresh(verifiedAtEpochMillis, nowEpochMillis, maxAgeMillis)
}
