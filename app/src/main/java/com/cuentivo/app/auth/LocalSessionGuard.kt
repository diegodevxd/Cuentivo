package com.cuentivo.app.auth

import android.content.Context
import com.cuentivo.app.data.local.CuentivoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes sure the local database belongs to whoever is signed in right now.
 *
 * Golden rule of identity and local data:
 * In case of doubt, preserve local data and ask the user.
 * NEVER delete to resolve an identity conflict.
 *
 * Room is the source of truth and it lives on the device, not in the account. Signing out only
 * dropped the uid held in memory, so the next person to sign in on the same phone opened a database
 * still full of the previous owner's business, customers and sales — which is exactly what was
 * reported: two accounts showing the same numbers.
 *
 * Scoping every read by owner (see `BusinessDao.firstOwnedBy`) fixes what is *shown*. This fixes
 * what is *stored*: another person's ledger has no business sitting on this phone at all.
 *
 * Linking an anonymous guest session preserves the same Firebase UID (`previousUserId == userId`),
 * so NO wipe occurs and all guest rows remain intact. A wipe only occurs when a genuinely different
 * owner signs in (`previousUserId != userId`).
 */
class LocalSessionGuard(
    context: Context,
    private val database: CuentivoDatabase,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Claims the device for [userId], wiping it first if it held someone else's data.
     *
     * Must finish before any screen queries Room, otherwise the dashboard loads rows that are about
     * to be deleted. Returns true when a wipe happened, so the caller can say so out loud.
     */
    suspend fun adopt(userId: String): Boolean {
        val previousUserId = preferences.getString(LAST_USER_ID, null)
        val belongsToSomeoneElse = when {
            previousUserId != null -> previousUserId != userId
            // Installed before this guard existed: the marker is missing, so the business row itself
            // says who owns the data. This is what repairs a phone where a second account already
            // inherited the first one's business.
            else -> database.businessDao().first()?.let { it.ownerUserId != userId } ?: false
        }

        if (belongsToSomeoneElse) {
            withContext(Dispatchers.IO) { database.clearAllTables() }
        }
        preferences.edit().putString(LAST_USER_ID, userId).apply()
        return belongsToSomeoneElse
    }

    /**
     * Drops the marker after the account and its data are deleted.
     *
     * Without this the next person to sign up on this phone would be told their predecessor's data
     * was wiped, when there was nothing left to wipe.
     */
    fun forget() {
        preferences.edit().remove(LAST_USER_ID).apply()
    }

    private companion object {
        const val PREFERENCES = "cuentivo.session"

        /** A Firebase uid — an opaque id, never the email or anything else identifying. */
        const val LAST_USER_ID = "lastUserId"
    }
}
