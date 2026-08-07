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
