package com.cuentivo.app.auth

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.EmailAuthProvider
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.OAuthProvider
import dev.gitlive.firebase.auth.auth
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `AuthenticationGateway` en iOS: la traducción de `FirebaseAuthenticationGateway`.
 *
 * La semántica es la misma que en Android —incluidos los casos de invitado, vinculación y fallo— y
 * los resultados se construyen con las **mismas** piezas comunes (`AuthenticationFlowPolicy`,
 * `GoogleAuthenticationResult.fromFirebaseErrorCode`). Lo único distinto es de dónde sale el
 * material de credencial: en Android lo entrega Credential Manager; aquí, un proveedor de Swift.
 *
 * Los proveedores nativos presentan UI, así que el gateway trabaja en `Dispatchers.Main`: las
 * llamadas de GitLive son `suspend` y las de Swift tienen que volver al hilo principal.
 *
 * Apple no está en `AuthenticationGateway` (la interfaz se escribió para Android, donde Apple no
 * existe): sus tres flujos son métodos propios de esta clase.
 */
class IosAuthenticationGateway(
    private val auth: FirebaseAuth = Firebase.auth,
    private val providers: () -> Pair<IosGoogleSignInProvider?, IosAppleSignInProvider?> =
        { IosAuthProviders.google to IosAuthProviders.apple },
) : AuthenticationGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val currentUserEmail: String?
        get() = auth.currentUser?.email?.trim()?.takeIf { it.isNotEmpty() }

    override val isAnonymous: Boolean
        get() = auth.currentUser?.isAnonymous == true

    override val isEmailVerified: Boolean
        get() = auth.currentUser?.isEmailVerified == true

    override val accountCreatedAtEpochMillis: Long?
        get() = auth.currentUser?.metaData?.creationTimestamp

    // ---------------------------------------------------------------- correo y contraseña

    override fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit) =
        result(onComplete) { auth.signInWithEmailAndPassword(email.trim(), password).uidOrThrow() }

    override fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit) =
        result(onComplete) { auth.createUserWithEmailAndPassword(email.trim(), password).uidOrThrow() }

    override fun signInAnonymously(onComplete: (Result<String>) -> Unit) =
        result(onComplete) { auth.signInAnonymously().uidOrThrow() }

    override fun linkEmail(email: String, password: String, onComplete: (Result<String>) -> Unit) =
        result(onComplete) {
            val user = auth.currentUser ?: throw IllegalStateException()
            val expectedUid = user.uid
            val linked = user.linkWithCredential(EmailAuthProvider.credential(email.trim(), password))
            AuthenticationFlowPolicy.resolveLinkResult(expectedUid, linked.user?.uid)
                .getOrThrow()
        }

    override fun sendEmailVerification(onComplete: (Result<Unit>) -> Unit) =
        result(onComplete) { (auth.currentUser ?: throw IllegalStateException()).sendEmailVerification() }

    /**
     * Igual que Android: `reload()` refresca el objeto local, pero Firestore lee `email_verified` del
     * token, así que también hay que forzarlo. Sin eso, la app dejaba entrar y la primera consulta a
     * la nube se denegaba.
     */
    override fun reloadUser(onComplete: (Result<Boolean>) -> Unit) = result(onComplete) {
        val user = auth.currentUser ?: throw IllegalStateException()
        user.reload()
        val refreshed = auth.currentUser ?: throw IllegalStateException()
        if (!refreshed.isEmailVerified) return@result false
        refreshed.getIdToken(forceRefresh = true)
        true
    }

    override fun sendPasswordReset(email: String, onComplete: (Result<Unit>) -> Unit) =
        result(onComplete) { auth.sendPasswordResetEmail(email.trim()) }

    override fun signOut() = auth.signOut()

    override fun deleteAccount(onComplete: (Result<Unit>) -> Unit) = result(onComplete) {
        (auth.currentUser ?: throw IllegalStateException()).delete()
    }

    // ---------------------------------------------------------------- Google

    override fun signInWithGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit) {
        val provider = providers().first
        if (provider == null) {
            onComplete(GoogleAuthenticationResult.Failed)
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(GoogleAuthenticationResult.Cancelled)
                is IosSignInOutcome.Failed -> onComplete(GoogleAuthenticationResult.Failed)
                is IosSignInOutcome.Success -> scope.launch {
                    val credential = GoogleAuthProvider.credential(outcome.idToken, outcome.accessToken)
                    onComplete(authOutcome { auth.signInWithCredential(credential).uidOrThrow() })
                }
            }
        }
    }

    override fun linkGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit) {
        val provider = providers().first
        if (provider == null) {
            onComplete(GoogleAuthenticationResult.Failed)
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(GoogleAuthenticationResult.Cancelled)
                is IosSignInOutcome.Failed -> onComplete(GoogleAuthenticationResult.Failed)
                is IosSignInOutcome.Success -> scope.launch {
                    val user = auth.currentUser
                    if (user == null) {
                        onComplete(GoogleAuthenticationResult.Failed)
                        return@launch
                    }
                    val expectedUid = user.uid
                    try {
                        val credential = GoogleAuthProvider.credential(outcome.idToken, outcome.accessToken)
                        val linked = user.linkWithCredential(credential)
                        onComplete(AuthenticationFlowPolicy.resolveGoogleLinkResult(expectedUid, linked.user?.uid))
                    } catch (error: FirebaseAuthException) {
                        // El token ya está en la mano: se devuelve para que confirmar el cambio de
                        // cuenta no vuelva a abrir el selector.
                        onComplete(
                            GoogleAuthenticationResult.fromFirebaseErrorCode(
                                errorCode = error.code,
                                email = null,
                                idToken = outcome.idToken,
                            ),
                        )
                    } catch (unexpected: Throwable) {
                        onComplete(GoogleAuthenticationResult.Failed)
                    }
                }
            }
        }
    }

    override fun signInWithGoogleIdToken(idToken: String, onComplete: (GoogleAuthenticationResult) -> Unit) {
        scope.launch {
            val credential = GoogleAuthProvider.credential(idToken, null)
            onComplete(authOutcome { auth.signInWithCredential(credential).uidOrThrow() })
        }
    }

    override fun reauthenticateWithGoogle(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit) {
        val provider = providers().first
        if (provider == null) {
            onComplete(GoogleAuthenticationResult.Failed)
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(GoogleAuthenticationResult.Cancelled)
                is IosSignInOutcome.Failed -> onComplete(GoogleAuthenticationResult.Failed)
                is IosSignInOutcome.Success -> scope.launch {
                    val user = auth.currentUser
                    if (user == null) {
                        onComplete(GoogleAuthenticationResult.Failed)
                        return@launch
                    }
                    try {
                        user.reauthenticate(GoogleAuthProvider.credential(outcome.idToken, outcome.accessToken))
                        // `reauthenticate` no devuelve usuario: la sesión es la misma y su uid es
                        // la confirmación de que sigue ahí.
                        val uid = auth.currentUser?.uid
                        onComplete(
                            if (uid != null) GoogleAuthenticationResult.SignedIn(uid)
                            else GoogleAuthenticationResult.Failed,
                        )
                    } catch (error: FirebaseAuthException) {
                        onComplete(GoogleAuthenticationResult.fromFirebaseErrorCode(error.code))
                    } catch (unexpected: Throwable) {
                        onComplete(GoogleAuthenticationResult.Failed)
                    }
                }
            }
        }
    }

    /** En iOS no hay Credential Manager; el SDK de Google guarda su propia sesión. */
    override fun clearGoogleCredentialState(onComplete: () -> Unit) = onComplete()

    // ---------------------------------------------------------------- Apple

    /** Inicia sesión con Apple. Mismo vocabulario de resultado que Google. */
    fun signInWithApple(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit) {
        appleSignIn(host, onComplete) { credential ->
            auth.signInWithCredential(credential).uidOrThrow()
        }
    }

    /** Vincula Apple a la sesión actual, conservando el UID (el invitado no pierde sus datos). */
    fun linkApple(host: PlatformAuthHost, onComplete: (GoogleAuthenticationResult) -> Unit) {
        val provider = providers().second
        if (provider == null) {
            onComplete(GoogleAuthenticationResult.Failed)
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(GoogleAuthenticationResult.Cancelled)
                is IosSignInOutcome.Failed -> onComplete(GoogleAuthenticationResult.Failed)
                is IosSignInOutcome.Success -> scope.launch {
                    val user = auth.currentUser
                    if (user == null) {
                        onComplete(GoogleAuthenticationResult.Failed)
                        return@launch
                    }
                    val expectedUid = user.uid
                    try {
                        val linked = user.linkWithCredential(appleCredential(outcome))
                        onComplete(AuthenticationFlowPolicy.resolveGoogleLinkResult(expectedUid, linked.user?.uid))
                    } catch (error: FirebaseAuthException) {
                        onComplete(
                            GoogleAuthenticationResult.fromFirebaseErrorCode(
                                errorCode = error.code,
                                email = null,
                                idToken = outcome.idToken,
                            ),
                        )
                    } catch (unexpected: Throwable) {
                        onComplete(GoogleAuthenticationResult.Failed)
                    }
                }
            }
        }
    }

    /**
     * Borra la cuenta cuando se entró con Apple.
     *
     * App Store exige revocar el token: sin `revokeToken(withAuthorizationCode:)` la cuenta de Apple
     * sigue autorizada aunque el usuario de Firebase desaparezca. Se reautentica primero porque
     * Firebase rechaza borrar una sesión vieja, y se revoca antes de borrar porque el código de
     * autorización sólo sirve mientras la sesión existe.
     */
    fun deleteAccountWithApple(host: PlatformAuthHost, onComplete: (Result<Unit>) -> Unit) {
        val provider = providers().second
        if (provider == null) {
            onComplete(Result.failure(IllegalStateException()))
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(Result.failure(IllegalStateException("cancelled")))
                is IosSignInOutcome.Failed -> onComplete(Result.failure(IllegalStateException(outcome.reason)))
                is IosSignInOutcome.Success -> scope.launch {
                    try {
                        val user = auth.currentUser ?: throw IllegalStateException()
                        user.reauthenticate(appleCredential(outcome))
                        val code = outcome.authorizationCode
                        if (code != null) {
                            // Se espera: revocar exige sesión viva. Si falla, se borra igual: lo que no puede
                            // quedarse a medias es la cuenta de Firebase.
                            suspendCoroutine<Unit> { continuation ->
                                provider.revoke(code) { continuation.resume(Unit) }
                            }
                        }
                        user.delete()
                        onComplete(Result.success(Unit))
                    } catch (error: Throwable) {
                        onComplete(Result.failure(error))
                    }
                }
            }
        }
    }

    private fun appleSignIn(
        host: PlatformAuthHost,
        onComplete: (GoogleAuthenticationResult) -> Unit,
        signIn: suspend (credential: dev.gitlive.firebase.auth.AuthCredential) -> String,
    ) {
        val provider = providers().second
        if (provider == null) {
            onComplete(GoogleAuthenticationResult.Failed)
            return
        }
        provider.signIn(host) { outcome ->
            when (outcome) {
                is IosSignInOutcome.Cancelled -> onComplete(GoogleAuthenticationResult.Cancelled)
                is IosSignInOutcome.Failed -> onComplete(GoogleAuthenticationResult.Failed)
                is IosSignInOutcome.Success -> scope.launch {
                    try {
                        onComplete(GoogleAuthenticationResult.SignedIn(signIn(appleCredential(outcome))))
                    } catch (error: FirebaseAuthException) {
                        onComplete(GoogleAuthenticationResult.fromFirebaseErrorCode(error.code))
                    } catch (unexpected: Throwable) {
                        onComplete(GoogleAuthenticationResult.Failed)
                    }
                }
            }
        }
    }

    private fun appleCredential(outcome: IosSignInOutcome.Success): dev.gitlive.firebase.auth.AuthCredential =
        OAuthProvider.credential(
            providerId = "apple.com",
            accessToken = outcome.accessToken,
            idToken = outcome.idToken,
            rawNonce = outcome.rawNonce,
        )

    // ---------------------------------------------------------------- utilidades

    private fun <T> result(onComplete: (Result<T>) -> Unit, block: suspend () -> T) {
        scope.launch {
            onComplete(
                try {
                    Result.success(block())
                } catch (error: Throwable) {
                    Result.failure(error)
                },
            )
        }
    }

    private suspend fun authOutcome(signIn: suspend () -> String): GoogleAuthenticationResult = try {
        GoogleAuthenticationResult.SignedIn(signIn())
    } catch (error: FirebaseAuthException) {
        GoogleAuthenticationResult.fromFirebaseErrorCode(error.code)
    } catch (unexpected: Throwable) {
        GoogleAuthenticationResult.Failed
    }

    private fun dev.gitlive.firebase.auth.AuthResult.uidOrThrow(): String =
        user?.uid ?: throw IllegalStateException()
}
