package com.cuentivo.app.auth

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseAuth
import dev.gitlive.firebase.auth.FirebaseAuthException
import dev.gitlive.firebase.auth.FirebaseAuthRecentLoginRequiredException
import dev.gitlive.firebase.auth.FirebaseAuthUserCollisionException
import dev.gitlive.firebase.auth.EmailAuthProvider
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.OAuthProvider
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.auth.code
import dev.gitlive.firebase.auth.ios
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSError

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
@OptIn(ExperimentalForeignApi::class)
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

    /**
     * Cuándo Firebase dice que se creó la cuenta.
     *
     * GitLive entrega `UserMetaData.creationTime` en **segundos desde la fecha de referencia de
     * Apple** (2001-01-01T00:00:00Z, `timeIntervalSinceReferenceDate`), no en milisegundos desde
     * 1970. Android, en cambio, entrega `metadata.creationTimestamp` en milisegundos desde epoch, y
     * este campo se llama `accountCreatedAtEpochMillis` para todos. Por eso se suma la diferencia
     * entre 1970 y 2001 antes de pasar a milisegundos: `EmailVerificationPolicy` compara este valor
     * contra un corte en epoch millis y un número en otra unidad dejaría a todo el mundo del lado
     * equivocado.
     */
    override val accountCreatedAtEpochMillis: Long?
        get() = auth.currentUser?.metaData?.creationTime
            ?.let { seconds -> ((seconds + SECONDS_1970_TO_2001) * MILLIS_PER_SECOND).toLong() }

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

    /**
     * Cierra la sesión de forma síncrona.
     *
     * `signOut()` de GitLive es `suspend` y el contrato común no lo es, así que aquí se usa la API
     * nativa `FIRAuth.signOut(error:)`: la sesión ya está cerrada cuando esta función regresa. No se
     * usa `runBlocking` ni un `launch` sin esperar, porque los dos dejarían la sesión viva un rato
     * después de que el llamador crea que terminó.
     *
     * Android no expone ruta de error aquí (`FirebaseAuth.signOut()` no falla), pero el SDK de iOS sí
     * devuelve un `NSError`, y GitLive lo lanza desde su versión `suspend`. Se conserva esa
     * semántica: un fallo no se traga en silencio.
     */
    override fun signOut() {
        val error: NSError? = memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            auth.ios.signOut(errorVar.ptr)
            errorVar.value
        }
        check(error == null) { "Firebase could not close the session" }
    }

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
                                errorCode = error.iosErrorCode(),
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
                        onComplete(GoogleAuthenticationResult.fromFirebaseErrorCode(error.iosErrorCode()))
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
                                errorCode = error.iosErrorCode(),
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
                        onComplete(GoogleAuthenticationResult.fromFirebaseErrorCode(error.iosErrorCode()))
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
        GoogleAuthenticationResult.fromFirebaseErrorCode(error.iosErrorCode())
    } catch (unexpected: Throwable) {
        GoogleAuthenticationResult.Failed
    }

    private fun dev.gitlive.firebase.auth.AuthResult.uidOrThrow(): String =
        user?.uid ?: throw IllegalStateException()

    private companion object {
        /** Segundos entre 1970-01-01 y la fecha de referencia de Apple (2001-01-01), en UTC. */
        const val SECONDS_1970_TO_2001 = 978_307_200.0
        const val MILLIS_PER_SECOND = 1_000.0
    }

    override fun classifyFailure(error: Throwable): AuthFailure = classifyIosAuthFailure(error)
}

internal fun classifyIosAuthFailure(error: Throwable): AuthFailure = AuthFailure(
    kind = when (error) {
        is FirebaseAuthUserCollisionException -> AuthFailureKind.USER_COLLISION
        is FirebaseAuthRecentLoginRequiredException -> AuthFailureKind.RECENT_LOGIN_REQUIRED
        else -> AuthFailureKind.OTHER
    },
    code = (error as? FirebaseAuthException)?.iosErrorCode(),
)

/**
 * El código de error de Firebase Auth, en el vocabulario que habla [GoogleAuthenticationResult].
 *
 * GitLive publica `FirebaseAuthException.code` en iOS con el número de `AuthErrorCode` (`"17007"`),
 * mientras que Android expone `FirebaseAuthException.errorCode` con su nombre
 * (`"ERROR_EMAIL_ALREADY_IN_USE"`). `fromFirebaseErrorCode` distingue por ese vocabulario, así que sin
 * esta traducción iOS convertiría en `Failed` los casos en los que Android conserva la sesión del
 * invitado y le pide una instrucción a la persona. Los números salen de los códigos de `AuthErrors`
 * que GitLive mismo mapea en `firebase-auth/src/appleMain/.../auth.kt`.
 */
private fun FirebaseAuthException.iosErrorCode(): String? = when (code) {
    "17007" -> "ERROR_EMAIL_ALREADY_IN_USE"
    "17012" -> "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"
    "17025" -> "ERROR_CREDENTIAL_ALREADY_IN_USE"
    else -> code
}
