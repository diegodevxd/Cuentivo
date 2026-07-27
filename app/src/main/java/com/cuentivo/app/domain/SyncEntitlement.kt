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

    /** The single question every sync path asks. */
    fun allowsCloudSync(tier: SubscriptionTier): Boolean = tier == SubscriptionTier.PRO
}
