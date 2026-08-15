package com.cuentivo.app.auth

/**
 * Outcome of a Google sign-in or linking attempt, mapped to what the host can show.
 *
 * The gateway never hands raw Firebase or Credential Manager exceptions to the UI:
 * cancellation is silence, an existing manual account is an instruction, and everything
 * else is one generic failure. No PII, error codes or tokens ever reach the screen.
 */
sealed interface GoogleAuthenticationResult {
    /** The Google account became (or was added to) the signed-in Firebase session. */
    data class SignedIn(val userId: String) : GoogleAuthenticationResult

    /** The person closed the credential selector; nothing changed. */
    data object Cancelled : GoogleAuthenticationResult

    /** The email already has a password account here; sign in with the password first. */
    data object ManualAccountNeedsPassword : GoogleAuthenticationResult

    /** Anything else — shown as one localized generic message. */
    data object Failed : GoogleAuthenticationResult

    companion object {
        /**
         * Maps a Credential Manager failure. Only a genuine user cancellation is silent; the
         * selector refusing, Play Services being unavailable, an unknown credential — all failure.
         */
        fun fromCredentialFailure(isCancellation: Boolean): GoogleAuthenticationResult =
            if (isCancellation) Cancelled else Failed

        /**
         * Maps a Firebase Auth error code. The only code the host can act on is the one that says
         * the email already has a manual account: the fix is to sign in with the password and link
         * from there, never to merge two UIDs silently.
         */
        fun fromFirebaseErrorCode(errorCode: String?): GoogleAuthenticationResult =
            when (errorCode) {
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> ManualAccountNeedsPassword
                else -> Failed
            }
    }
}
