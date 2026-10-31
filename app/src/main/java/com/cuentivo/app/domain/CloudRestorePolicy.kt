package com.cuentivo.app.domain

/** Why a restore was or was not attempted. The UI turns this into a message; the policy never does. */
enum class CloudRestoreDecision {
    /** Pro account, empty device, not tried yet: go looking for the business in the cloud. */
    Attempt,

    /** The device already has this owner's business. Nothing was lost, so nothing to restore. */
    AlreadyHasBusiness,

    /** Free accounts have no cloud copy to come back to — that is what the plan buys. */
    NotEntitled,

    /** One attempt per session. Play can confirm Pro late, but it only earns one more try. */
    AlreadyAttempted,
}

/**
 * Decides whether a freshly signed-in device should pull its business down from the cloud.
 *
 * This exists because of a real data-loss report: reinstalling the app emptied Room, the app offered
 * "create a business", and that button minted a brand-new random businessId. Every query filters by
 * businessId, so the customers still sitting in Firestore under the previous id became unreachable —
 * present, paid for, and invisible. Restoring has to happen *before* a new business can be created,
 * which is why the decision is made at sign-in rather than offered as a button.
 *
 * Pure and free of Android, like the rest of this layer: it answers the question and writes nothing.
 */
object CloudRestorePolicy {

    fun decide(
        tier: SubscriptionTier,
        hasLocalBusiness: Boolean,
        alreadyAttempted: Boolean,
    ): CloudRestoreDecision = when {
        // Checked first so a Pro user with data intact is never told anything about restoring, and
        // so the common case costs no network call at all.
        hasLocalBusiness -> CloudRestoreDecision.AlreadyHasBusiness
        !tier.includesProBenefits -> CloudRestoreDecision.NotEntitled
        alreadyAttempted -> CloudRestoreDecision.AlreadyAttempted
        else -> CloudRestoreDecision.Attempt
    }
}
