package com.cuentivo.app.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.cuentivo.app.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
     * Google Play requires any app with accounts to offer this from inside the app. Firebase refuses
     * with RECENT_LOGIN_REQUIRED when the session is old; that surfaces as a failure the caller must
     * show rather than swallow, because a delete that silently did nothing is worse than an error.
     */
    fun deleteAccount(onComplete: (Result<Unit>) -> Unit)

    /**
     * Re-authenticates the signed-in session with Google, through the same Credential Manager flow
     * as sign-in.
     *
     * Firebase refuses to delete a user whose session is not recent (`ERROR_REQUIRES_RECENT_LOGIN`).
     * The fix is to prove the person is still there: ask for the Google credential again and
     * `reauthenticate` with it, before deleting anything. A cancellation or failure means nothing is
     * deleted and the caller says so.
     */
    fun reauthenticateWithGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Google sign-in for a signed-out screen, through AndroidX Credential Manager.
     *
     * [GoogleAuthenticationResult] is the only vocabulary the UI ever sees — a silent cancellation,
     * an instruction to use the manual password, or one generic failure. Raw Firebase or Credential
     * Manager exceptions never reach the screen.
     */
    fun signInWithGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Links Google to the signed-in session, preserving its Firebase UID. Never merges two UIDs: a
     * Google account whose email already belongs to another account is surfaced, not adopted.
     */
    fun linkGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Completes a Google sign-in with an idToken already obtained (v45).
     *
     * Exists so confirming a guest account switch does not re-open the Google account picker: the
     * token from the failed link attempt is reused for `signInWithCredential`. The token is never
     * logged or shown.
     */
    fun signInWithGoogleIdToken(idToken: String, onComplete: (GoogleAuthenticationResult) -> Unit)

    /**
     * Forgets the credential the last Google flow left in Credential Manager. Called after every
     * sign-out so the next account picker is not pre-selected for a session that no longer exists.
     */
    fun clearGoogleCredentialState(onComplete: () -> Unit)
}

class FirebaseAuthenticationGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthenticationGateway {
    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val currentUserEmail: String?
        get() = auth.currentUser?.email?.trim()?.takeIf { it.isNotEmpty() }

    override val isAnonymous: Boolean
        get() = auth.currentUser?.isAnonymous == true

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

    override fun signInAnonymously(onComplete: (Result<String>) -> Unit) {
        auth.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result?.user?.uid
                if (userId != null) onComplete(Result.success(userId))
                else onComplete(Result.failure(IllegalStateException()))
            } else onComplete(Result.failure(task.exception ?: IllegalStateException()))
        }
    }

    override fun linkEmail(email: String, password: String, onComplete: (Result<String>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(Result.failure(IllegalStateException()))
            return
        }
        val expectedUid = user.uid
        val credential = EmailAuthProvider.getCredential(email.trim(), password)
        user.linkWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val userId = task.result?.user?.uid
                onComplete(AuthenticationFlowPolicy.resolveLinkResult(expectedUid, userId))
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

    // Captured from the last Google flow: clearing the credential state needs a Context, and the
    // gateway is constructed without one so SyncWorker and billing refresh keep using it as-is.
    private var lastGoogleContext: Context? = null

    override fun signInWithGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit) {
        googleIdToken(activity, onComplete = onComplete) { idToken, googleEmail ->
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener { task ->
                    onComplete(
                        if (task.isSuccessful) {
                            val userId = task.result?.user?.uid
                            if (userId != null) GoogleAuthenticationResult.SignedIn(userId)
                            else GoogleAuthenticationResult.Failed
                        } else {
                            GoogleAuthenticationResult.fromFirebaseErrorCode(
                                (task.exception as? FirebaseAuthException)?.errorCode,
                                email = googleEmail,
                            )
                        }
                    )
                }
        }
    }

    override fun linkGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit) {
        googleIdToken(activity, onComplete = onComplete) { idToken, googleEmail ->
            val user = auth.currentUser
            if (user == null) {
                onComplete(GoogleAuthenticationResult.Failed)
                return@googleIdToken
            }
            val expectedUid = user.uid
            user.linkWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener { task ->
                    onComplete(
                        if (task.isSuccessful) {
                            val userId = task.result?.user?.uid
                            AuthenticationFlowPolicy.resolveGoogleLinkResult(expectedUid, userId)
                        } else {
                            GoogleAuthenticationResult.fromFirebaseErrorCode(
                                (task.exception as? FirebaseAuthException)?.errorCode,
                                email = googleEmail,
                                // El token ya está en la mano: se devuelve para que confirmar el
                                // cambio de cuenta no vuelva a abrir el selector (v45).
                                idToken = idToken,
                            )
                        }
                    )
                }
        }
    }

    override fun signInWithGoogleIdToken(idToken: String, onComplete: (GoogleAuthenticationResult) -> Unit) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .addOnCompleteListener { task ->
                onComplete(
                    if (task.isSuccessful) {
                        val userId = task.result?.user?.uid
                        if (userId != null) GoogleAuthenticationResult.SignedIn(userId)
                        else GoogleAuthenticationResult.Failed
                    } else {
                        GoogleAuthenticationResult.fromFirebaseErrorCode(
                            (task.exception as? FirebaseAuthException)?.errorCode,
                        )
                    }
                )
            }
    }

    override fun reauthenticateWithGoogle(activity: Activity, onComplete: (GoogleAuthenticationResult) -> Unit) {
        googleIdToken(activity, onComplete = onComplete) { idToken, googleEmail ->
            val user = auth.currentUser
            if (user == null) {
                onComplete(GoogleAuthenticationResult.Failed)
                return@googleIdToken
            }
            user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener { task ->
                    onComplete(
                        if (task.isSuccessful) {
                            // `reauthenticate` returns Task<Void>: no user in the result. The session
                            // is the same one, so its uid is the confirmation that it is still there.
                            val userId = auth.currentUser?.uid
                            if (userId != null) GoogleAuthenticationResult.SignedIn(userId)
                            else GoogleAuthenticationResult.Failed
                        } else {
                            GoogleAuthenticationResult.fromFirebaseErrorCode(
                                (task.exception as? FirebaseAuthException)?.errorCode,
                                email = googleEmail,
                            )
                        }
                    )
                }
        }
    }

    override fun clearGoogleCredentialState(onComplete: () -> Unit) {
        val context = lastGoogleContext
        if (context == null) {
            onComplete()
            return
        }
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
                // Best effort: the Firebase session is already gone, and a stale picker preference
                // must not turn sign-out into a failure.
            }
            onComplete()
        }
    }

    /**
     * Asks Credential Manager for a Google ID token. Cancellation maps to [GoogleAuthenticationResult.Cancelled]
     * and reaches no error message; every other failure is generic. The client id comes from the
     * generated google-services resource, never copied by hand.
     */
    private fun googleIdToken(
        activity: Activity,
        onComplete: (GoogleAuthenticationResult) -> Unit,
        onIdToken: (idToken: String, email: String?) -> Unit,
    ) {
        lastGoogleContext = activity.applicationContext
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(activity.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .build()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val response = CredentialManager.create(activity).getCredential(activity, request)
                // Parsed rather than cast: GoogleIdTokenCredential.createFrom validates the JSON the
                // selector returned, so a credential of any other type cannot masquerade as a token.
                val parsed = try {
                    GoogleIdTokenCredential.createFrom(response.credential.data)
                } catch (_: Exception) {
                    null
                }
                val idToken = parsed?.idToken
                if (idToken == null) onComplete(GoogleAuthenticationResult.Failed)
                else onIdToken(idToken, parsed?.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: GetCredentialCancellationException) {
                // The person closed the selector: nothing happened, nothing to say.
                onComplete(GoogleAuthenticationResult.Cancelled)
            } catch (_: NoCredentialException) {
                // No saved credential and no selector to show, e.g. a device with no Google account.
                onComplete(GoogleAuthenticationResult.Failed)
            } catch (_: GetCredentialException) {
                onComplete(GoogleAuthenticationResult.Failed)
            }
        }
    }
}
