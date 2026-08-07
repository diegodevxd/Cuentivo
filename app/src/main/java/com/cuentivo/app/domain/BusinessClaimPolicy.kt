package com.cuentivo.app.domain

/**
 * What a device with no business should do about it.
 *
 * The counterpart to [CloudRestorePolicy], and deliberately a separate decision. That one answers
 * "may I download this ledger?", which is a paid feature. This one answers "does this account
 * already own a business?", which is not: it is data integrity, and getting it wrong mints a
 * duplicate that strands everything filed under the previous id.
 */
sealed interface BusinessClaim {
    /** The device already holds this owner's business. Nothing to decide. */
    data object UseLocal : BusinessClaim

    /**
     * The account already owns [businessId] in the cloud. Adopt that id instead of minting one.
     *
     * Adopting is not restoring. On the free plan only the business row is taken, so the account
     * keeps writing under the id it already owns; the ledger itself stays in the cloud until the
     * plan allows it down. On Pro the restore runs as it always has.
     */
    data class AdoptRemote(val businessId: String) : BusinessClaim

    /** The cloud answered, and this account owns nothing. The only path that may mint an id. */
    data object CreateNew : BusinessClaim

    /**
     * The lookup failed, so it is unknown whether a business exists. Do not create.
     *
     * This is the case the app used to get wrong, and the expensive one. A failed lookup left the
     * "create a business" button reachable, and taking it minted a second id for an account that
     * already had one — no free plan required, just a bad connection at the wrong moment. Treating
     * "I do not know" as "there is none" is exactly how one account ended up owning nine.
     */
    data object Blocked : BusinessClaim
}

/** Pure and free of Android, like the rest of this layer: it answers the question and writes nothing. */
object BusinessClaimPolicy {

    /**
     * @param hasLocalBusiness whether Room already holds a business for the signed-in owner.
     * @param remoteBusinessId the id the cloud reported, or null when it reported none.
     * @param lookupFailed whether the lookup could not be completed at all. Checked before
     *   [remoteBusinessId] precisely because a failure and an empty answer are indistinguishable
     *   once both have collapsed into "null".
     */
    fun decide(
        hasLocalBusiness: Boolean,
        remoteBusinessId: String?,
        lookupFailed: Boolean,
    ): BusinessClaim = when {
        // First, so a device with its data intact never spends a network call — and so a lookup
        // failure cannot lock out somebody who was not going to create anything anyway.
        hasLocalBusiness -> BusinessClaim.UseLocal
        lookupFailed -> BusinessClaim.Blocked
        remoteBusinessId != null -> BusinessClaim.AdoptRemote(remoteBusinessId)
        else -> BusinessClaim.CreateNew
    }

    /**
     * Which business wins when an account somehow owns several: **the oldest**.
     *
     * The lookup used to take the newest, which is backwards. A duplicate is by definition the one
     * created later, so "newest" hands the account its own mistake and silently strands the ledger
     * that has the real history. The oldest is the original.
     *
     * Kept here rather than in the gateway so the tie-break is testable without Firestore, and so
     * there is one answer rather than one per call site.
     */
    fun preferred(candidates: List<RemoteBusiness>): RemoteBusiness? =
        candidates.minWithOrNull(
            // createdAt first, then id: two businesses minted in the same millisecond must still
            // resolve the same way on every device, or two phones adopt different ids and diverge.
            compareBy<RemoteBusiness> { it.createdAtEpochMillis }.thenBy { it.businessId }
        )
}

/** The little a claim needs to know about a business that lives in the cloud. */
data class RemoteBusiness(val businessId: String, val createdAtEpochMillis: Long)
