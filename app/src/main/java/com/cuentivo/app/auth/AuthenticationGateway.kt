package com.cuentivo.app.auth

import com.google.firebase.auth.FirebaseAuth

interface AuthenticationGateway {
    val currentUserId: String?
    val currentUserEmail: String?

    fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signOut()

    /**
     * Deletes the signed-in account itself, not just the session.
     *
     * Google Play requires any app with accounts to offer this from inside the app. Firebase refuses
     * with RECENT_LOGIN_REQUIRED when the session is old; that surfaces as a failure the caller must
     * show rather than swallow, because a delete that silently did nothing is worse than an error.
     */
    fun deleteAccount(onComplete: (Result<Unit>) -> Unit)
}

class FirebaseAuthenticationGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthenticationGateway {
    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val currentUserEmail: String?
        get() = auth.currentUser?.email?.trim()?.takeIf { it.isNotEmpty() }

    override fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result.user?.uid
                if (userId != null) onComplete(Result.success(userId))
                else onComplete(Result.failure(IllegalStateException("No fue posible iniciar sesión")))
            } else onComplete(Result.failure(task.exception ?: IllegalStateException("No fue posible iniciar sesión")))
        }
    }

    override fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit) {
        auth.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result.user?.uid
                if (userId != null) onComplete(Result.success(userId))
                else onComplete(Result.failure(IllegalStateException("No fue posible crear la cuenta")))
            } else onComplete(Result.failure(task.exception ?: IllegalStateException("No fue posible crear la cuenta")))
        }
    }

    override fun signOut() = auth.signOut()

    override fun deleteAccount(onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(Result.failure(IllegalStateException("No hay una sesión activa")))
            return
        }
        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onComplete(Result.success(Unit))
            } else {
                onComplete(
                    Result.failure(
                        task.exception
                            ?: IllegalStateException("No fue posible eliminar la cuenta. Vuelve a iniciar sesión e inténtalo de nuevo.")
                    )
                )
            }
        }
    }
}
