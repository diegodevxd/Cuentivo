package com.cuentivo.app.domain

/** What a session may do, once the account's email address has been taken into account. */
enum class EmailVerificationAccess {
    /** Verified, or grandfathered and already inside, or anonymous guest session. Nothing to say. */
    ALLOWED,

    /** In, but told to verify, and the cloud stays shut until they do. */
    ALLOWED_WITH_WARNING,

    /** Not in. The verification screen replaces the app. */
    MUST_VERIFY,

    /** Nobody is signed in. */
    SIGNED_OUT,
}

/**
 * Decides whether an account is allowed past the sign-in screen.
 *
 * Regla de acceso y verificación:
 * La verificación de correo se exige ÚNICAMENTE cuando la cuenta tiene correo registrado
 * Y hay respaldo en la nube en juego (o recuperación de cuenta). No en la puerta de entrada.
 * Una sesión anónima (invitado) no tiene correo, por lo que resuelve siempre [EmailVerificationAccess.ALLOWED],
 * permitiendo trabajar localmente de inmediato sin bloquear al usuario en el primer uso.
 *
 * For accounts with email:
 * Accounts created before [LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS] (grandfathered) get
 * [EmailVerificationAccess.ALLOWED_WITH_WARNING] (they can use local ledger but cloud sync is blocked).
 * New accounts with unverified email get [EmailVerificationAccess.MUST_VERIFY] when cloud backup
 * / verified account gate is evaluated.
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
    ): EmailVerificationAccess = access(
        isSignedIn = isSignedIn,
        isEmailVerified = isEmailVerified,
        accountCreatedAtEpochMillis = accountCreatedAtEpochMillis,
        isAnonymous = false,
        cutoffEpochMillis = cutoffEpochMillis,
    )

    fun access(
        isSignedIn: Boolean,
        isEmailVerified: Boolean,
        accountCreatedAtEpochMillis: Long?,
        isAnonymous: Boolean,
        cutoffEpochMillis: Long = LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS,
    ): EmailVerificationAccess = when {
        !isSignedIn -> EmailVerificationAccess.SIGNED_OUT
        isAnonymous -> EmailVerificationAccess.ALLOWED
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
