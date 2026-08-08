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
            if (!task.isSuccessful) {
                onComplete(Result.failure(task.exception ?: IllegalStateException()))
                return@addOnCompleteListener
            }
            val refreshed = auth.currentUser
            if (refreshed?.isEmailVerified != true) {
                onComplete(Result.success(false))
                return@addOnCompleteListener
            }
            // reload() is not enough, and believing it was cost a tester the whole app. It refreshes
            // the local user object, so `isEmailVerified` flips to true and the client lets them in
            // — but Firestore does not read that object. It reads `email_verified` out of the signed
            // ID token, which reload() leaves untouched. So the app opened, the very first thing it
            // does is ask the cloud whether this account already owns a business, and the rules
            // denied it: still unverified as far as the token was concerned. A denied lookup is
            // indistinguishable from a broken connection, so it landed on the Blocked screen, whose
            // Retry button reissued the same query with the same stale token forever.
            //
            // Forcing the token is therefore part of verifying, not an optimisation. Only after this
            // succeeds is the session actually able to do anything with the cloud, so a failure here
            // is reported as a failure: the caller asks them to try again, which is exactly right.
            refreshed.getIdToken(true).addOnCompleteListener { tokenTask ->
                if (tokenTask.isSuccessful) onComplete(Result.success(true))
                else onComplete(Result.failure(tokenTask.exception ?: IllegalStateException()))
            }
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
