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

    // ── Staleness ────────────────────────────────────────────────────────────
    // A subscription can lapse while the app sits closed for weeks. Time is passed in rather than
    // read from a clock, so these run instantly and deterministically — no sleeps.

    /** An arbitrary fixed instant; only the differences from it matter. */
    private val now = 1_785_200_000_000L
    private val day = SyncEntitlement.MAX_CONFIRMATION_AGE_MILLIS

    /**
     * An address nobody proved exists cannot be trusted with the cloud, whatever Play says: the
     * server refuses those writes anyway, and spending sync attempts on them only parks the queue.
     */
    @Test
    fun `pro with an unverified email address may not sync`() {
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", now, ana, now, false))
    }

    @Test
    fun `pro confirmed within the last day may sync`() {
        val verifiedAt = now - (23L * 60 * 60 * 1000)

        assertTrue(SyncEntitlement.allowsCloudSync(ana, "PRO", verifiedAt, ana, now, true))
    }

    @Test
    fun `pro confirmed just now may sync`() {
        assertTrue(SyncEntitlement.allowsCloudSync(ana, "PRO", now, ana, now, true))
    }

    @Test
    fun `pro whose confirmation expired may not sync`() {
        // The Worker case: the app has been closed for two days, the subscription may well be gone,
        // and nothing has re-confirmed it. Fail closed until Play says otherwise.
        val verifiedAt = now - (2 * day)

        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", verifiedAt, ana, now, true))
    }

    @Test
    fun `the boundary itself is still trusted, one millisecond past it is not`() {
        assertTrue(SyncEntitlement.allowsCloudSync(ana, "PRO", now - day, ana, now, true))
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", now - day - 1, ana, now, true))
    }

    @Test
    fun `a confirmation dated in the future is refused`() {
        // Otherwise winding the clock back would be a way to keep Pro forever. Being strict costs
        // little: opening the app re-confirms.
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", now + 60_000, ana, now, true))
    }

    @Test
    fun `a device that never stored a confirmation is stale by default`() {
        // Epoch 0 is decades old, which is exactly the answer wanted for "never confirmed".
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", 0L, ana, now, true))
    }

    @Test
    fun `freshness never rescues a free plan or the wrong account`() {
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "FREE", now, ana, now, true))
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", now, beto, now, true))
        assertFalse(SyncEntitlement.allowsCloudSync(ana, "PRO", now, null, now, true))
    }
}
