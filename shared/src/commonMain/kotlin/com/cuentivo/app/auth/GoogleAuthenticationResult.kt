package com.cuentivo.app.auth

/**
 * Outcome of a Google sign-in or linking attempt, mapped to what the host can show.
 *
 * The gateway never hands raw Firebase or Credential Manager exceptions to the UI:
 * cancellation is silence, an existing manual account is an instruction, and everything
 * else is one generic failure. No PII, error codes or tokens ever reach the screen.
 *
 * Regla de oro de identidad y datos locales:
 * Ante la duda se conservan los datos locales y se le pregunta al usuario.
 * NUNCA se borra para resolver un conflicto de identidad.
 *
 * When linking an anonymous guest session fails because the Google account already belongs
 * to another user ([AccountAlreadyExists]), the guest session and all its local ledger data
 * remain preserved and untouched.
 */
sealed interface GoogleAuthenticationResult {
    /** The Google account became (or was added to) the signed-in Firebase session. */
    data class SignedIn(val userId: String) : GoogleAuthenticationResult

    /** The person closed the credential selector; nothing changed. */
    data object Cancelled : GoogleAuthenticationResult

    /** The email already has a password account here; sign in with the password first. */
    data object ManualAccountNeedsPassword : GoogleAuthenticationResult

    /** The account already exists; linking is refused while preserving local guest data. */
    data class AccountAlreadyExists(
        val email: String? = null,
        /**
         * El idToken de Google que ya se obtuvo en este intento (v45).
         *
         * Cuando un invitado intenta enlazar una cuenta de Google que ya existe, el enlace falla
         * pero el token ya está en la mano. Antes, confirmar el cambio de cuenta volvía a lanzar el
         * selector de Google —una segunda selección de la misma cuenta—. Con el token aquí, el host
         * puede completar el inicio de sesión sin volver a pedirlo.
         *
         * Nunca se registra ni se muestra: es material de credencial.
         */
        val idToken: String? = null,
    ) : GoogleAuthenticationResult

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
         * Maps a Firebase Auth error code. The only codes the host acts on are those explaining
         * password presence or existing account collisions, preserving the local session.
         */
        fun fromFirebaseErrorCode(errorCode: String?, email: String? = null, idToken: String? = null): GoogleAuthenticationResult =
            when (errorCode) {
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> ManualAccountNeedsPassword
                "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_EMAIL_ALREADY_IN_USE" ->
                    AccountAlreadyExists(email, idToken)
                else -> Failed
            }
    }
}
