package com.cuentivo.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleAuthenticationResultTest {
    @Test
    fun account_exists_with_different_credential_requires_manual_password() {
        assertEquals(
            GoogleAuthenticationResult.ManualAccountNeedsPassword,
            GoogleAuthenticationResult.fromFirebaseErrorCode("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"),
        )
    }

    @Test
    fun cancellation_is_not_an_error_message() {
        assertEquals(
            GoogleAuthenticationResult.Cancelled,
            GoogleAuthenticationResult.fromCredentialFailure(isCancellation = true),
        )
    }

    @Test
    fun any_other_firebase_error_is_a_generic_failure() {
        assertEquals(
            GoogleAuthenticationResult.Failed,
            GoogleAuthenticationResult.fromFirebaseErrorCode("ERROR_INVALID_CREDENTIAL"),
        )
        assertEquals(
            GoogleAuthenticationResult.Failed,
            GoogleAuthenticationResult.fromFirebaseErrorCode(null),
        )
    }

    @Test
    fun a_non_cancellation_credential_failure_is_a_generic_failure() {
        assertEquals(
            GoogleAuthenticationResult.Failed,
            GoogleAuthenticationResult.fromCredentialFailure(isCancellation = false),
        )
    }

    @Test
    fun account_already_exists_collision_returns_account_already_exists() {
        assertEquals(
            GoogleAuthenticationResult.AccountAlreadyExists(null),
            GoogleAuthenticationResult.fromFirebaseErrorCode("ERROR_CREDENTIAL_ALREADY_IN_USE"),
        )
        assertEquals(
            GoogleAuthenticationResult.AccountAlreadyExists("diego@example.com"),
            GoogleAuthenticationResult.fromFirebaseErrorCode("ERROR_EMAIL_ALREADY_IN_USE", "diego@example.com"),
        )
    }
}
