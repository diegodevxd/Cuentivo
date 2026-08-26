package com.cuentivo.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuthenticationFlowPolicy].
 *
 * Verifies all authentication flow transitions, guest onboarding, UID preservation on linking,
 * collision handling, and sign-out logic without Android or Compose dependencies.
 */
class AuthenticationFlowPolicyTest {

    @Test
    fun initialState_with_existing_user_returns_authenticated() {
        val state = AuthenticationFlowPolicy.initialState(currentUserId = "user_123", isAnonymous = false)
        assertEquals(
            AuthenticationFlowState.Authenticated(userId = "user_123", isAnonymous = false),
            state,
        )
    }

    @Test
    fun initialState_with_existing_anonymous_user_returns_authenticated_anonymous() {
        val state = AuthenticationFlowPolicy.initialState(currentUserId = "guest_123", isAnonymous = true)
        assertEquals(
            AuthenticationFlowState.Authenticated(userId = "guest_123", isAnonymous = true),
            state,
        )
    }

    @Test
    fun initialState_without_user_returns_anonymous_starting() {
        val state = AuthenticationFlowPolicy.initialState(currentUserId = null)
        assertEquals(AuthenticationFlowState.AnonymousStarting, state)
    }

    @Test
    fun onAnonymousSuccess_transitions_to_authenticated_anonymous() {
        val state = AuthenticationFlowPolicy.onAnonymousSuccess("guest_999")
        assertEquals(
            AuthenticationFlowState.Authenticated(userId = "guest_999", isAnonymous = true),
            state,
        )
    }

    @Test
    fun onAnonymousFailure_transitions_to_first_launch_offline() {
        val state = AuthenticationFlowPolicy.onAnonymousFailure()
        assertEquals(AuthenticationFlowState.FirstLaunchOffline, state)
    }

    @Test
    fun onRetryAnonymous_transitions_to_anonymous_starting() {
        val state = AuthenticationFlowPolicy.onRetryAnonymous()
        assertEquals(AuthenticationFlowState.AnonymousStarting, state)
    }

    @Test
    fun onSwitchToManual_transitions_to_manual_authentication() {
        val state = AuthenticationFlowPolicy.onSwitchToManual()
        assertEquals(AuthenticationFlowState.ManualAuthentication(), state)
    }

    @Test
    fun resolveLinkResult_with_matching_uid_returns_success() {
        val result = AuthenticationFlowPolicy.resolveLinkResult(
            expectedUid = "guest_123",
            resultUid = "guest_123",
        )
        assertTrue(result.isSuccess)
        assertEquals("guest_123", result.getOrNull())
    }

    @Test
    fun resolveLinkResult_with_differing_uid_returns_failure() {
        val result = AuthenticationFlowPolicy.resolveLinkResult(
            expectedUid = "guest_123",
            resultUid = "other_user_456",
        )
        assertFalse("Account linking with a differing UID must be rejected", result.isSuccess)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun resolveLinkResult_with_null_uid_returns_failure() {
        val result = AuthenticationFlowPolicy.resolveLinkResult(
            expectedUid = "guest_123",
            resultUid = null,
        )
        assertFalse("Account linking with null UID must be rejected", result.isSuccess)
    }

    @Test
    fun resolveGoogleLinkResult_with_matching_uid_returns_signed_in() {
        val result = AuthenticationFlowPolicy.resolveGoogleLinkResult(
            expectedUid = "guest_google_123",
            resultUid = "guest_google_123",
        )
        assertEquals(GoogleAuthenticationResult.SignedIn("guest_google_123"), result)
    }

    @Test
    fun resolveGoogleLinkResult_with_differing_uid_returns_failed() {
        val result = AuthenticationFlowPolicy.resolveGoogleLinkResult(
            expectedUid = "guest_google_123",
            resultUid = "unexpected_uid",
        )
        assertEquals(GoogleAuthenticationResult.Failed, result)
    }

    @Test
    fun resolveGoogleLinkResult_with_null_uid_returns_failed() {
        val result = AuthenticationFlowPolicy.resolveGoogleLinkResult(
            expectedUid = "guest_google_123",
            resultUid = null,
        )
        assertEquals(GoogleAuthenticationResult.Failed, result)
    }

    @Test
    fun onAccountCollision_with_active_guest_retains_guest_session_without_data_loss() {
        val state = AuthenticationFlowPolicy.onAccountCollision(
            currentGuestUid = "guest_123",
            collisionMessage = "Email already in use",
        )
        assertEquals(
            AuthenticationFlowState.Authenticated(userId = "guest_123", isAnonymous = true),
            state,
        )
    }

    @Test
    fun onAccountCollision_without_guest_shows_collision_message() {
        val state = AuthenticationFlowPolicy.onAccountCollision(
            currentGuestUid = null,
            collisionMessage = "Email already in use",
        )
        assertEquals(
            AuthenticationFlowState.ManualAuthentication(message = "Email already in use"),
            state,
        )
    }

    @Test
    fun onSignOut_transitions_to_manual_authentication() {
        val state = AuthenticationFlowPolicy.onSignOut()
        assertEquals(AuthenticationFlowState.ManualAuthentication(), state)
    }
}
