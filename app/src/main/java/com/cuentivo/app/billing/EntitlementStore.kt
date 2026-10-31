package com.cuentivo.app.billing

import android.content.Context
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.domain.SyncEntitlement
import com.google.firebase.auth.FirebaseAuth

/**
 * Remembers which account paid, somewhere a background Worker can reach.
 *
 * The plan lives in Compose state while the app is open, but WorkManager can run in a process where
 * MainActivity never started — so an in-memory tier is invisible exactly when sync happens. This
 * writes it to SharedPreferences, which any process of the app can read without touching Room or
 * the network.
 *
 * The entitlement is stored **with the uid it belongs to**. On a shared phone the next person to
 * sign in must not inherit Pro, and comparing the two ids is what stops that; the decision itself
 * lives in [SyncEntitlement], which is pure and tested.
 */
class EntitlementStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Records what Play said about [userId], and **when** it said it.
     *
     * The timestamp is the whole point of storing anything: without it a PRO written months ago
     * would be believed forever by a Worker running while the app is closed, long after the
     * subscription lapsed. Every confirmation refreshes it, so an app that gets opened stays valid.
     */
    fun remember(
        userId: String?,
        tier: SubscriptionTier,
        verifiedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        if (userId.isNullOrBlank()) return
        preferences.edit()
            .putString(USER_ID, userId)
            .putString(TIER, tier.name)
            .putLong(VERIFIED_AT, verifiedAtEpochMillis)
            .apply()
    }

    /** The plan in force for [userId] right now. FREE for anyone else, and for nobody. */
    fun currentTier(userId: String?): SubscriptionTier = SyncEntitlement.tierFor(
        storedUserId = preferences.getString(USER_ID, null),
        storedTier = preferences.getString(TIER, null),
        currentUserId = userId,
    )

    /**
     * The question asked from background code, where no uid has been handed down.
     *
     * Reads the signed-in account straight from Firebase Auth: signed out resolves to null, which
     * [SyncEntitlement] treats as FREE. Auth initialises itself in any process of the app, so this
     * answers correctly from a Worker with no Activity alive.
     */
    fun allowsCloudSync(
        userId: String? = FirebaseAuth.getInstance().currentUser?.uid,
        nowEpochMillis: Long = System.currentTimeMillis(),
        isEmailVerified: Boolean = FirebaseAuth.getInstance().currentUser?.isEmailVerified == true,
    ): Boolean = SyncEntitlement.allowsCloudSync(
        storedUserId = preferences.getString(USER_ID, null),
        storedTier = preferences.getString(TIER, null),
        // Never confirmed reads as epoch 0, which is stale by decades — exactly the answer wanted.
        verifiedAtEpochMillis = preferences.getLong(VERIFIED_AT, 0L),
        currentUserId = userId,
        nowEpochMillis = nowEpochMillis,
        isEmailVerified = isEmailVerified,
    )

    /** Drops the record. Used when the account itself is deleted. */
    fun forget() {
        preferences.edit().remove(USER_ID).remove(TIER).remove(VERIFIED_AT).apply()
    }

    private companion object {
        const val PREFERENCES = "cuentivo.entitlement"

        /** A Firebase uid — opaque, never the email or anything else identifying. */
        const val USER_ID = "entitlementUserId"
        const val TIER = "entitlementTier"

        /** Epoch millis of the last successful confirmation from Play. */
        const val VERIFIED_AT = "entitlementVerifiedAt"
    }
}
