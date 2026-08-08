package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessClaimPolicyTest {

    @Test fun a_device_that_already_has_the_business_decides_nothing() {
        assertEquals(
            BusinessClaim.UseLocal,
            BusinessClaimPolicy.decide(hasLocalBusiness = true, remoteBusinessId = null, lookupFailed = false),
        )
    }

    @Test fun an_empty_device_adopts_the_business_the_account_already_owns() {
        assertEquals(
            BusinessClaim.AdoptRemote("b-1"),
            BusinessClaimPolicy.decide(hasLocalBusiness = false, remoteBusinessId = "b-1", lookupFailed = false),
        )
    }

    @Test fun a_genuinely_new_account_may_create_one() {
        assertEquals(
            BusinessClaim.CreateNew,
            BusinessClaimPolicy.decide(hasLocalBusiness = false, remoteBusinessId = null, lookupFailed = false),
        )
    }

    /**
     * The whole point of the class. A failed lookup used to be indistinguishable from "you own
     * nothing", and the app created a second business for an account that already had one — which
     * is how nine of them accumulated under a single uid.
     */
    @Test fun a_failed_lookup_blocks_creation_instead_of_guessing() {
        assertEquals(
            BusinessClaim.Blocked,
            BusinessClaimPolicy.decide(hasLocalBusiness = false, remoteBusinessId = null, lookupFailed = true),
        )
    }

    /** Being unable to ask must never lock out somebody whose data is already on the device. */
    @Test fun a_failed_lookup_does_not_block_a_device_that_has_its_data() {
        assertEquals(
            BusinessClaim.UseLocal,
            BusinessClaimPolicy.decide(hasLocalBusiness = true, remoteBusinessId = null, lookupFailed = true),
        )
    }

    /**
     * The retro that found this: a session the rules will not serve is not a broken connection, and
     * telling somebody to check theirs sends them after a problem they do not have. There is nothing
     * in the cloud to duplicate either — the same rules refused every write this account attempted.
     */
    @Test fun a_session_that_may_not_read_the_cloud_creates_instead_of_blocking() {
        assertEquals(
            BusinessClaim.CreateNew,
            BusinessClaimPolicy.decide(
                hasLocalBusiness = false,
                remoteBusinessId = null,
                lookupFailed = true,
                canQueryCloud = false,
            ),
        )
    }

    /** Not being allowed to ask must not take the data off a device that already has it. */
    @Test fun a_session_that_may_not_read_the_cloud_still_uses_what_is_local() {
        assertEquals(
            BusinessClaim.UseLocal,
            BusinessClaimPolicy.decide(
                hasLocalBusiness = true,
                remoteBusinessId = null,
                lookupFailed = true,
                canQueryCloud = false,
            ),
        )
    }

    /**
     * Offline-first has to survive being offline. Requiring the server for this lookup made the
     * Blocked screen reachable for the first time — including for somebody who just registered and
     * has no signal, who would meet a screen about confirming data they have never had.
     */
    @Test fun an_account_registered_in_this_session_may_create_even_if_the_lookup_failed() {
        assertEquals(
            BusinessClaim.CreateNew,
            BusinessClaimPolicy.decide(
                hasLocalBusiness = false,
                remoteBusinessId = null,
                lookupFailed = true,
                accountIsBrandNew = true,
            ),
        )
    }

    /** The reinstall is the case that strands a ledger, and it is never brand new. Still blocked. */
    @Test fun a_reinstall_with_a_failed_lookup_is_blocked_even_though_the_device_is_empty() {
        assertEquals(
            BusinessClaim.Blocked,
            BusinessClaimPolicy.decide(
                hasLocalBusiness = false,
                remoteBusinessId = null,
                lookupFailed = true,
                accountIsBrandNew = false,
            ),
        )
    }

    /** Being new never overrides a real answer: a business that was found is still adopted. */
    @Test fun a_brand_new_account_still_adopts_a_business_the_lookup_actually_found() {
        assertEquals(
            BusinessClaim.AdoptRemote("b-1"),
            BusinessClaimPolicy.decide(
                hasLocalBusiness = false,
                remoteBusinessId = "b-1",
                lookupFailed = false,
                accountIsBrandNew = true,
            ),
        )
    }

    /** A verified session that genuinely cannot reach the network is still blocked, as before. */
    @Test fun a_verified_session_with_a_failed_lookup_is_still_blocked() {
        assertEquals(
            BusinessClaim.Blocked,
            BusinessClaimPolicy.decide(
                hasLocalBusiness = false,
                remoteBusinessId = null,
                lookupFailed = true,
                canQueryCloud = true,
            ),
        )
    }

    /**
     * The old gateway took the newest, which hands the account its own duplicate and strands the
     * ledger with the real history.
     */
    @Test fun the_oldest_business_wins_when_an_account_owns_several() {
        val candidates = listOf(
            RemoteBusiness("nuevo", 1_786_064_127_186),
            RemoteBusiness("original", 1_786_046_337_570),
            RemoteBusiness("intermedio", 1_786_050_007_270),
        )
        assertEquals(RemoteBusiness("original", 1_786_046_337_570), BusinessClaimPolicy.preferred(candidates))
    }

    /** Two phones resolving a tie differently would adopt different ids and diverge for good. */
    @Test fun a_tie_on_creation_time_resolves_the_same_way_everywhere() {
        val sameMillis = listOf(RemoteBusiness("bbb", 100L), RemoteBusiness("aaa", 100L))
        assertEquals(RemoteBusiness("aaa", 100L), BusinessClaimPolicy.preferred(sameMillis))
        assertEquals(BusinessClaimPolicy.preferred(sameMillis), BusinessClaimPolicy.preferred(sameMillis.reversed()))
    }

    @Test fun nothing_in_the_cloud_has_no_preferred_business() {
        assertEquals(null, BusinessClaimPolicy.preferred(emptyList()))
    }
}
