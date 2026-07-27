package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud sync is a Pro benefit. These are the rules that decide it, and every one of them fails
 * closed: the interesting direction is not "Pro was denied", it is "someone got the cloud who did
 * not pay for it, or under an account that was not theirs".
 */
class SyncEntitlementTest {

    private val ana = "uid-ana"
    private val beto = "uid-beto"

    @Test
    fun `a free account may not sync`() {
        val tier = SyncEntitlement.tierFor(storedUserId = ana, storedTier = "FREE", currentUserId = ana)

        assertEquals(SubscriptionTier.FREE, tier)
        assertFalse(SyncEntitlement.allowsCloudSync(tier))
    }

    @Test
    fun `an active pro account may sync`() {
        val tier = SyncEntitlement.tierFor(storedUserId = ana, storedTier = "PRO", currentUserId = ana)

        assertEquals(SubscriptionTier.PRO, tier)
        assertTrue(SyncEntitlement.allowsCloudSync(tier))
    }

    @Test
    fun `losing pro stops sync immediately`() {
        // The downgrade is just the stored tier changing; nothing else has to be torn down, and the
        // outbox rows are deliberately left untouched so resubscribing drains them.
        val tier = SyncEntitlement.tierFor(storedUserId = ana, storedTier = "FREE", currentUserId = ana)

        assertFalse(SyncEntitlement.allowsCloudSync(tier))
    }

    @Test
    fun `getting pro back allows sync again`() {
        val downgraded = SyncEntitlement.tierFor(ana, "FREE", ana)
        val upgraded = SyncEntitlement.tierFor(ana, "PRO", ana)

        assertFalse(SyncEntitlement.allowsCloudSync(downgraded))
        assertTrue(SyncEntitlement.allowsCloudSync(upgraded))
    }

    @Test
    fun `one account never inherits another account's pro`() {
        // The case that matters on a shared phone: Ana paid, Beto signs in. Beto is not Pro, and
        // more importantly Beto's ledger must never travel under Ana's entitlement.
        val tier = SyncEntitlement.tierFor(storedUserId = ana, storedTier = "PRO", currentUserId = beto)

        assertEquals(SubscriptionTier.FREE, tier)
        assertFalse(SyncEntitlement.allowsCloudSync(tier))
    }

    @Test
    fun `nobody signed in is treated as free`() {
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(ana, "PRO", currentUserId = null))
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(ana, "PRO", currentUserId = ""))
    }

    @Test
    fun `a device that has never stored anything is free`() {
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(null, null, ana))
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor("", "PRO", ana))
    }

    @Test
    fun `an unrecognised plan name degrades to free instead of unlocking`() {
        // A future tier name written by a newer build, or a corrupted preference, must not be read
        // as "something better than FREE".
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(ana, "PLATINUM", ana))
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(ana, "", ana))
        assertEquals(SubscriptionTier.FREE, SyncEntitlement.tierFor(ana, null, ana))
    }

    @Test
    fun `only PRO opens the cloud`() {
        assertTrue(SyncEntitlement.allowsCloudSync(SubscriptionTier.PRO))
        assertFalse(SyncEntitlement.allowsCloudSync(SubscriptionTier.FREE))
    }
}
