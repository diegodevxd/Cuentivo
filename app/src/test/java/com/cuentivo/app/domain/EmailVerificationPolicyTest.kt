package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailVerificationPolicyTest {
    private val cutoff = 1_000L

    @Test fun a_verified_address_is_simply_let_in() {
        assertEquals(
            EmailVerificationAccess.ALLOWED,
            EmailVerificationPolicy.access(true, true, 5_000, cutoff),
        )
    }

    @Test fun a_new_unverified_account_does_not_get_in() {
        assertEquals(
            EmailVerificationAccess.MUST_VERIFY,
            EmailVerificationPolicy.access(true, false, cutoff, cutoff),
        )
    }

    /** The testers already inside keep their ledger; they are told, not locked out. */
    @Test fun an_account_from_before_the_cutoff_keeps_working_with_a_warning() {
        assertEquals(
            EmailVerificationAccess.ALLOWED_WITH_WARNING,
            EmailVerificationPolicy.access(true, false, cutoff - 1, cutoff),
        )
    }

    @Test fun an_account_with_no_known_creation_date_is_treated_as_new() {
        assertEquals(
            EmailVerificationAccess.MUST_VERIFY,
            EmailVerificationPolicy.access(true, false, null, cutoff),
        )
    }

    @Test fun nobody_signed_in_is_its_own_answer() {
        assertEquals(
            EmailVerificationAccess.SIGNED_OUT,
            EmailVerificationPolicy.access(false, true, 5_000, cutoff),
        )
    }

    /** Being grandfathered in buys time, not the cloud: an unverified address never uploads. */
    @Test fun only_a_verified_address_may_reach_the_cloud() {
        assertTrue(EmailVerificationPolicy.allowsCloudSync(EmailVerificationAccess.ALLOWED))
        assertFalse(EmailVerificationPolicy.allowsCloudSync(EmailVerificationAccess.ALLOWED_WITH_WARNING))
        assertFalse(EmailVerificationPolicy.allowsCloudSync(EmailVerificationAccess.MUST_VERIFY))
        assertFalse(EmailVerificationPolicy.allowsCloudSync(EmailVerificationAccess.SIGNED_OUT))
    }

    @Test fun anonymous_guest_session_is_never_allowed_to_sync_to_cloud() {
        val access = EmailVerificationPolicy.access(
            isSignedIn = true,
            isEmailVerified = false,
            accountCreatedAtEpochMillis = null,
            isAnonymous = true,
            cutoffEpochMillis = cutoff,
        )
        assertEquals(EmailVerificationAccess.ALLOWED, access)
        assertFalse(EmailVerificationPolicy.allowsCloudSync(access, isAnonymous = true))
        // Verified email account allows cloud sync
        val verifiedAccess = EmailVerificationPolicy.access(
            isSignedIn = true,
            isEmailVerified = true,
            accountCreatedAtEpochMillis = 5_000L,
            isAnonymous = false,
            cutoffEpochMillis = cutoff,
        )
        assertTrue(EmailVerificationPolicy.allowsCloudSync(verifiedAccess, isAnonymous = false))
    }

    @Test fun anonymous_guest_session_without_email_is_always_allowed_without_verification() {
        assertEquals(
            EmailVerificationAccess.ALLOWED,
            EmailVerificationPolicy.access(
                isSignedIn = true,
                isEmailVerified = false,
                accountCreatedAtEpochMillis = 5_000L,
                isAnonymous = true,
                cutoffEpochMillis = cutoff,
            ),
        )
        assertEquals(
            EmailVerificationAccess.ALLOWED,
            EmailVerificationPolicy.access(
                isSignedIn = true,
                isEmailVerified = false,
                accountCreatedAtEpochMillis = null,
                isAnonymous = true,
                cutoffEpochMillis = cutoff,
            ),
        )
    }

    @Test fun anonymous_session_signed_out_resolves_signed_out() {
        assertEquals(
            EmailVerificationAccess.SIGNED_OUT,
            EmailVerificationPolicy.access(
                isSignedIn = false,
                isEmailVerified = false,
                accountCreatedAtEpochMillis = null,
                isAnonymous = true,
                cutoffEpochMillis = cutoff,
            ),
        )
    }

    @Test fun unverified_account_with_email_created_after_cutoff_remains_blocked_from_cloud() {
        val access = EmailVerificationPolicy.access(
            isSignedIn = true,
            isEmailVerified = false,
            accountCreatedAtEpochMillis = EmailVerificationPolicy.LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS + 1000L,
            isAnonymous = false,
        )
        assertEquals(EmailVerificationAccess.MUST_VERIFY, access)
        assertFalse(EmailVerificationPolicy.allowsCloudSync(access))
    }

    @Test fun legacy_account_with_email_created_before_cutoff_keeps_local_access_but_blocked_from_cloud() {
        val access = EmailVerificationPolicy.access(
            isSignedIn = true,
            isEmailVerified = false,
            accountCreatedAtEpochMillis = EmailVerificationPolicy.LEGACY_ACCOUNT_CUTOFF_EPOCH_MILLIS - 1000L,
            isAnonymous = false,
        )
        assertEquals(EmailVerificationAccess.ALLOWED_WITH_WARNING, access)
        assertFalse(EmailVerificationPolicy.allowsCloudSync(access))
    }
}
