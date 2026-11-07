package com.cuentivo.app.auth

/**
 * La ventana que un flujo de inicio de sesión necesita del sistema.
 *
 * Existe porque el contrato de auth es común y el flujo de Google —en Android vía Credential
 * Manager, en iOS vía el SDK de Google— pide prestada una pantalla para mostrar su selector.
 *
 * En Android es `android.app.Activity`, exactamente el mismo tipo que recibía el gateway antes de
 * moverse a común, así que ninguna firma cambia de forma efectiva.
 */
expect class PlatformAuthHost

interface AuthenticationGateway {
    val currentUserId: String?
    val currentUserEmail: String?
    val isAnonymous: Boolean

    /** Whether the signed-in address has been proven to exist. Stale until [reloadUser] runs. */
    val isEmailVerified: Boolean

    /** When Firebase says the account was created. Decides who is grandfathered past verification. */
    val accountCreatedAtEpochMillis: Long?

    fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit)

    /**
     * Starts an anonymous guest session on first launch, without credentials or email.
     */
    fun signInAnonymously(onComplete: (Result<String>) -> Unit)

    /**
     * Links an email and password to an existing anonymous guest session, preserving its UID.
     */
    fun linkEmail(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun sendEmailVerification(onComplete: (Result<Unit>) -> Unit)

    /**
     * Re-reads the account from Firebase.
     *
     * Not optional: `isEmailVerified` is cached in the local token, so a person who just clicked
     * the link in their inbox still looks unverified until the user object is refreshed.
     */
    fun reloadUser(onComplete: (Result<Boolean>) -> Unit)

    fun sendPasswordReset(email: String, onComplete: (Result<Unit>) -> Unit)

    fun signOut()

    /**
     * Deletes the signed-in account itself, not just the session.
     *
     * Google Play and the App Store both require any app with accounts to offer this from inside the
     * app. Firebase refuses with RECENT_LOGIN_REQUIRED when the session is old; that surfaces as a
     * failure the caller must show rather than swallow, because a delete that silently did nothing
     * is worse than an error.
     */
    fun deleteAccount(onComplete: (Result<Unit>) -> Unit)

    /**
     * Re-authenticates the signed-in session with Google, through the same flow as sign-in.
     *
     * Firebase refuses to delete a user whose session is not recent (`ERROR_REQUIRES_RECENT_LOGIN`).
     * The fix is to prove the person is still there: ask for the Google credential again and
     * `reauthenticate` with it, before deleting anything. A cancellation or failure means nothing is
     * deleted and the caller says so.
     */
    fun reauthenticateWithGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Google sign-in for a signed-out screen.
     *
     * [GoogleAuthenticationResult] is the only vocabulary the UI ever sees — a silent cancellation,
     * an instruction to use the manual password, or one generic failure. Raw Firebase or credential
     * exceptions never reach the screen.
     */
    fun signInWithGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Links Google to the signed-in session, preserving its Firebase UID. Never merges two UIDs: a
     * Google account whose email already belongs to another account is surfaced, not adopted.
     */
    fun linkGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Completes a Google sign-in with an idToken already obtained (v45).
     *
     * Exists so confirming a guest account switch does not re-open the Google account picker: the
     * token from the failed link attempt is reused for `signInWithCredential`. The token is never
     * logged or shown.
     */
    fun signInWithGoogleIdToken(idToken: String, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Forgets the credential the last Google flow left stored. Called after every sign-out so the
     * next account picker is not pre-selected for a session that no longer exists.
     */
    fun clearGoogleCredentialState(onComplete: () -> Unit)

    fun classifyFailure(error: Throwable): AuthFailure
}
