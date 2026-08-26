package com.cuentivo.app.auth

/**
 * Pure state machine and decisions for authentication, guest onboarding, and account linking.
 *
 * Golden rule of identity and local data:
 * In case of doubt, preserve local data and ask the user.
 * NEVER delete to resolve an identity conflict.
 */
sealed interface AuthenticationFlowState {
    /** Starting an anonymous guest session in the background on cold start. */
    data object AnonymousStarting : AuthenticationFlowState

    /** First launch failed to create anonymous guest session (typically offline). */
    data object FirstLaunchOffline : AuthenticationFlowState

    /** Showing manual email / password / Google authentication screen. */
    data class ManualAuthentication(
        val message: String? = null,
        val isWorking: Boolean = false,
    ) : AuthenticationFlowState

    /** Session active (either anonymous guest or linked/authenticated account). */
    data class Authenticated(
        val userId: String,
        val isAnonymous: Boolean,
    ) : AuthenticationFlowState
}

object AuthenticationFlowPolicy {
    /**
     * Determines initial state given whether an existing session is present.
     */
    fun initialState(currentUserId: String?, isAnonymous: Boolean = false): AuthenticationFlowState {
        return if (currentUserId != null) {
            AuthenticationFlowState.Authenticated(userId = currentUserId, isAnonymous = isAnonymous)
        } else {
            AuthenticationFlowState.AnonymousStarting
        }
    }

    /**
     * Transition when anonymous session creation succeeds.
     */
    fun onAnonymousSuccess(userId: String): AuthenticationFlowState {
        return AuthenticationFlowState.Authenticated(userId = userId, isAnonymous = true)
    }

    /**
     * Transition when anonymous session creation fails (e.g. offline on first launch).
     */
    fun onAnonymousFailure(): AuthenticationFlowState {
        return AuthenticationFlowState.FirstLaunchOffline
    }

    /**
     * Transition when user taps Retry on first launch offline screen.
     */
    fun onRetryAnonymous(): AuthenticationFlowState {
        return AuthenticationFlowState.AnonymousStarting
    }

    /**
     * Transition when user chooses "I already have an account" from first launch offline screen.
     */
    fun onSwitchToManual(): AuthenticationFlowState {
        return AuthenticationFlowState.ManualAuthentication()
    }

    /**
     * Enforces the runtime invariant that linking an account must NEVER change the UID.
     *
     * If the linked result returns a different or null UID, it is rejected to protect local data
     * from being orphaned or wiped by LocalSessionGuard.
     */
    fun resolveLinkResult(expectedUid: String, resultUid: String?): Result<String> {
        return if (resultUid != null && resultUid == expectedUid) {
            Result.success(resultUid)
        } else {
            Result.failure(
                IllegalStateException("Account linking changed UID from $expectedUid to $resultUid")
            )
        }
    }

    /**
     * Enforces the runtime invariant for Google account linking.
     */
    fun resolveGoogleLinkResult(expectedUid: String, resultUid: String?): GoogleAuthenticationResult {
        return if (resultUid != null && resultUid == expectedUid) {
            GoogleAuthenticationResult.SignedIn(resultUid)
        } else {
            GoogleAuthenticationResult.Failed
        }
    }

    /**
     * Transition on account collision (email or Google already registered).
     *
     * In accordance with the Golden Rule, local guest data is preserved and the guest session is retained.
     */
    fun onAccountCollision(currentGuestUid: String?, collisionMessage: String): AuthenticationFlowState {
        return if (currentGuestUid != null) {
            // Retain guest session, do not wipe or sign out
            AuthenticationFlowState.Authenticated(userId = currentGuestUid, isAnonymous = true)
        } else {
            AuthenticationFlowState.ManualAuthentication(message = collisionMessage)
        }
    }

    /**
     * Transition on sign out.
     */
    fun onSignOut(): AuthenticationFlowState {
        return AuthenticationFlowState.ManualAuthentication()
    }
}
