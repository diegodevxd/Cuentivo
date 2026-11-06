package com.cuentivo.app.auth

/**
 * Pure state machine and decisions for authentication, guest onboarding, and account linking.
 *
 * Golden rule of identity and local data:
 * In case of doubt, preserve local data and ask the user.
 * NEVER delete to resolve an identity conflict.
 */
/**
 * Fixed vocabulary for why anonymous session startup on cold start failed.
 */
enum class AnonymousStartupFailureCause {
    /** Device is offline or request timed out. Retrying when online will succeed. */
    NETWORK_UNAVAILABLE,

    /** Anonymous sign-in provider is disabled in Firebase Console (OPERATION_NOT_ALLOWED). Retrying will not help. */
    PROVIDER_DISABLED,

    /** Internal service or configuration error. */
    SERVICE_ERROR,
}

sealed interface AuthenticationFlowState {
    /** Starting an anonymous guest session in the background on cold start. */
    data object AnonymousStarting : AuthenticationFlowState

    /** First launch failed to create anonymous guest session due to network. */
    data object FirstLaunchOffline : AuthenticationFlowState

    /** First launch failed with a specific diagnosed cause. */
    data class FirstLaunchFailure(
        val cause: AnonymousStartupFailureCause,
    ) : AuthenticationFlowState

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
     * Maps an error code or exception from anonymous sign-in into a fixed failure cause.
     *
     * Pure mapper without Android dependencies, safe for unit testing.
     */
    fun classifyAnonymousErrorCode(errorCode: String?): AnonymousStartupFailureCause {
        if (errorCode == null) return AnonymousStartupFailureCause.SERVICE_ERROR
        val normalized = errorCode.trim().uppercase()
        return when {
            normalized == "ERROR_OPERATION_NOT_ALLOWED" ||
            normalized == "OPERATION_NOT_ALLOWED" ||
            normalized.contains("OPERATION_NOT_ALLOWED") ->
                AnonymousStartupFailureCause.PROVIDER_DISABLED

            normalized == "ERROR_NETWORK_REQUEST_FAILED" ||
            normalized == "NETWORK_ERROR" ||
            normalized.contains("NETWORK") ||
            normalized.contains("TIMEOUT") ||
            normalized.contains("UNREACHABLE") ->
                AnonymousStartupFailureCause.NETWORK_UNAVAILABLE

            else -> AnonymousStartupFailureCause.SERVICE_ERROR
        }
    }

    /**
     * Maps a Throwable into an AnonymousStartupFailureCause.
     */
    fun classifyAnonymousFailure(error: Throwable?): AnonymousStartupFailureCause {
        if (error == null) return AnonymousStartupFailureCause.NETWORK_UNAVAILABLE
        val className = error.javaClass.simpleName
        if (className.contains("Network", ignoreCase = true) ||
            className.contains("Timeout", ignoreCase = true) ||
            className.contains("UnknownHost", ignoreCase = true)
        ) {
            return AnonymousStartupFailureCause.NETWORK_UNAVAILABLE
        }
        val message = error.message ?: ""
        return classifyAnonymousErrorCode(message)
    }

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
     * Transition when anonymous session creation fails with a specific cause.
     */
    fun onAnonymousFailure(cause: AnonymousStartupFailureCause): AuthenticationFlowState {
        return when (cause) {
            AnonymousStartupFailureCause.NETWORK_UNAVAILABLE -> AuthenticationFlowState.FirstLaunchOffline
            else -> AuthenticationFlowState.FirstLaunchFailure(cause)
        }
    }

    /**
     * Transition when anonymous session creation fails (defaults to offline).
     */
    fun onAnonymousFailure(): AuthenticationFlowState {
        return onAnonymousFailure(AnonymousStartupFailureCause.NETWORK_UNAVAILABLE)
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
