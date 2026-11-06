package com.cuentivo.app.auth

/**
 * Lo que devuelve un proveedor nativo (Google o Apple) a Kotlin.
 *
 * Los dos proveedores viven en Swift porque necesitan su SDK; Kotlin recibe sólo el material de
 * credencial y arma con eso la credencial de Firebase. Nada de esto se imprime ni se registra.
 */
sealed interface IosSignInOutcome {
    /**
     * [rawNonce] y [authorizationCode] sólo tienen sentido para Apple: el nonce en claro se manda a
     * Firebase junto con el `idToken`, y el código es lo que permite revocar el token al borrar la
     * cuenta. Los tres son material de credencial.
     */
    data class Success(
        val idToken: String,
        val accessToken: String? = null,
        val rawNonce: String? = null,
        val authorizationCode: String? = null,
    ) : IosSignInOutcome

    /** La persona cerró el selector: no hay nada que decir. */
    data object Cancelled : IosSignInOutcome

    /** Cualquier otro fallo del proveedor, ya reducido a un mensaje sin tokens. */
    data class Failed(val reason: String) : IosSignInOutcome
}

/** El selector de Google en iOS: `GoogleSignIn-iOS`, presentado desde UIKit. */
interface IosGoogleSignInProvider {
    fun signIn(host: PlatformAuthHost, onResult: (IosSignInOutcome) -> Unit)
}

/** El selector de Apple: `AuthenticationServices`. */
interface IosAppleSignInProvider {
    fun signIn(host: PlatformAuthHost, onResult: (IosSignInOutcome) -> Unit)

    /** Revoca el token de Apple con el `authorizationCode`; App Store lo exige al borrar la cuenta. */
    fun revoke(authorizationCode: String, onResult: (Boolean) -> Unit)
}

/**
 * Registro de los proveedores que implementa Swift.
 *
 * Se llena al arrancar (`AppDelegate`), antes de que nadie pida un inicio de sesión. Los proveedores
 * son opcionales a propósito: un build sin Google configurado tiene que poder compilar y arrancar;
 * el flujo simplemente devuelve fallo.
 */
object IosAuthProviders {
    var google: IosGoogleSignInProvider? = null
    var apple: IosAppleSignInProvider? = null
}
