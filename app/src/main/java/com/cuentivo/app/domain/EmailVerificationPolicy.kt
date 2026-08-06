package com.cuentivo.app.domain

/** What a session may do, once the account's email address has been taken into account. */
enum class EmailVerificationAccess {
    /** Verified, or grandfathered and already inside. Nothing to say. */
    ALLOWED,

    /** In, but told to verify, and the cloud stays shut until they do. */
    ALLOWED_WITH_WARNING,

    /** Not in. The verification screen replaces the app. */
    MUST_VERIFY,

    /** Nobody is signed in. */
    SIGNED_OUT,
}

/**
 * Decides whether an unverified email address is allowed past the sign-in screen.
 *
 * Until now an address was never checked, so an account had no provable owner: nobody could be
 * mailed a password reset, and the ledger only exists on that one phone. New accounts therefore
 * have to verify before they get in.
 *
 * Accounts that already existed are deliberately treated differently. Closed testing was already
 * running when this shipped, and locking those people out would strand their customers and sales
 * behind an address some of them typed carelessly — with no backup and no cloud copy to restore
 * from. They keep working and get told; what they lose is the cloud, which they did not have yet.
 *
 * The cutoff is compared against the account's creation timestamp **as Firebase reports it**, not
 * against anything the phone stores, so changing the device clock does not turn a new account into
 * an old one.
 */
object EmailVerificationPolicy {
    /**
     * 2026-08-05T00:00:00Z — the release that introduced verification. Everything registered from
     * this instant on had the verification step in front of it and must have taken it.
     */
    const val LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS = 1_785_888_000_000L

    fun access(
        isSignedIn: Boolean,
        isEmailVerified: Boolean,
        accountCreatedAtEpochMillis: Long?,
        cutoffEpochMillis: Long = LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS,
    ): EmailVerificationAccess = when {
        !isSignedIn -> EmailVerificationAccess.SIGNED_OUT
        isEmailVerified -> EmailVerificationAccess.ALLOWED
        // No creation date means an account this client cannot vouch for. Closed by default: it is
        // the same answer a brand new account gets, which is the safe one.
        accountCreatedAtEpochMillis == null -> EmailVerificationAccess.MUST_VERIFY
        accountCreatedAtEpochMillis < cutoffEpochMillis -> EmailVerificationAccess.ALLOWED_WITH_WARNING
        else -> EmailVerificationAccess.MUST_VERIFY
    }

    /** Whether this device may talk to the cloud at all. An unverified address never may. */
    fun allowsCloudSync(access: EmailVerificationAccess): Boolean =
        access == EmailVerificationAccess.ALLOWED
}
