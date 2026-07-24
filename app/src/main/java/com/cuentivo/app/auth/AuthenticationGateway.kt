package com.cuentivo.app.auth

import com.google.firebase.auth.FirebaseAuth

interface AuthenticationGateway {
    val currentUserId: String?

    fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signOut()
}

class FirebaseAuthenticationGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthenticationGateway {
    override val currentUserId: String?
        get() = auth.currentUser?.uid

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
}
