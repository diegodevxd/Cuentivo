package com.cuentivo.app.auth

import com.google.firebase.auth.FirebaseAuth

interface AuthenticationGateway {
    val currentUserId: String?
    val currentUserEmail: String?

    /** Whether the signed-in address has been proven to exist. Stale until [reloadUser] runs. */
    val isEmailVerified: Boolean

    /** When Firebase says the account was created. Decides who is grandfathered past verification. */
    val accountCreatedAtEpochMillis: Long?

    fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit)

    fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit)

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

    override val isEmailVerified: Boolean
        get() = auth.currentUser?.isEmailVerified == true

    override val accountCreatedAtEpochMillis: Long?
        get() = auth.currentUser?.metadata?.creationTimestamp

    override fun signIn(email: String, password: String, onComplete: (Result<String>) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result.user?.uid
                if (userId != null) onComplete(Result.success(userId))
                else onComplete(Result.failure(IllegalStateException()))
            } else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    override fun signUp(email: String, password: String, onComplete: (Result<String>) -> Unit) {
        auth.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result.user?.uid
                if (userId != null) onComplete(Result.success(userId))
                else onComplete(Result.failure(IllegalStateException()))
            } else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    override fun sendEmailVerification(onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(Result.failure(IllegalStateException()))
            return
        }
        user.sendEmailVerification().addOnCompleteListener { task ->
            if (task.isSuccessful) onComplete(Result.success(Unit))
            else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    override fun reloadUser(onComplete: (Result<Boolean>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(Result.failure(IllegalStateException()))
            return
        }
        user.reload().addOnCompleteListener { task ->
            if (task.isSuccessful) onComplete(Result.success(auth.currentUser?.isEmailVerified == true))
            else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    /**
     * Never reports whether the address was registered.
     *
     * The caller shows the same message either way, which is what email enumeration protection
     * expects: telling a stranger "that account does not exist" hands them the list of who does.
     */
    override fun sendPasswordReset(email: String, onComplete: (Result<Unit>) -> Unit) {
        auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener { task ->
            if (task.isSuccessful) onComplete(Result.success(Unit))
            else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    override fun signOut() = auth.signOut()

    override fun deleteAccount(onComplete: (Result<Unit>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(Result.failure(IllegalStateException()))
            return
        }
        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onComplete(Result.success(Unit))
            } else {
                onComplete(
                    Result.failure(
                        task.exception
                            ?: IllegalStateException()
                    )
                )
            }
        }
    }
}
