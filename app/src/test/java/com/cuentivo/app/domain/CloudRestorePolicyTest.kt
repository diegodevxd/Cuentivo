package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRestorePolicyTest {

    @Test
    fun `a pro account on an empty device restores`() {
        assertEquals(
            CloudRestoreDecision.Attempt,
            CloudRestorePolicy.decide(SubscriptionTier.PRO, hasLocalBusiness = false, alreadyAttempted = false),
        )
    }

    @Test
    fun `a device that already has the business is left alone`() {
        assertEquals(
            CloudRestoreDecision.AlreadyHasBusiness,
            CloudRestorePolicy.decide(SubscriptionTier.PRO, hasLocalBusiness = true, alreadyAttempted = false),
        )
    }

    @Test
    fun `the local business wins even before Play has confirmed the plan`() {
        // The tier starts at FREE on every sign-in until Play answers. Someone with their data
        // already on the phone must never be told anything about restoring in that window.
        assertEquals(
            CloudRestoreDecision.AlreadyHasBusiness,
            CloudRestorePolicy.decide(SubscriptionTier.FREE, hasLocalBusiness = true, alreadyAttempted = false),
        )
    }

    @Test
    fun `a free account has no cloud copy to come back to`() {
        assertEquals(
            CloudRestoreDecision.NotEntitled,
            CloudRestorePolicy.decide(SubscriptionTier.FREE, hasLocalBusiness = false, alreadyAttempted = false),
        )
    }

    @Test
    fun `one attempt per session`() {
        // Guards the retry path: the effect reruns whenever the tier changes, and without this a
        // failed restore would loop against the network for as long as the screen stayed open.
        assertEquals(
            CloudRestoreDecision.AlreadyAttempted,
            CloudRestorePolicy.decide(SubscriptionTier.PRO, hasLocalBusiness = false, alreadyAttempted = true),
        )
    }
}
