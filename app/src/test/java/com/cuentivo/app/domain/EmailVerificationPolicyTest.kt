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
}
