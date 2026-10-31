package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixes the rule that makes three plans safe: **every paid plan carries the Pro benefits.**
 *
 * CuentivoAI is not an add-on stacked on top of Pro, it is a higher plan that includes it. The bug
 * this guards against is the quiet one: leaving a `tier == PRO` comparison behind anywhere would
 * take cloud backup, photos or the audit log away from the customer paying the most, while
 * compiling cleanly and passing every test written before the third plan existed.
 */
class PaidTierTest {

    private val testUserId = "user_test_paid_tier"
    private val testUserHash = BillingAccountHasher.obfuscate(testUserId)!!

    private fun purchase(
        productId: String,
        isPurchased: Boolean = true,
        isAcknowledged: Boolean = true,
        obfuscatedAccountId: String? = testUserHash,
    ) = PlayPurchase(
        productIds = listOf(productId),
        isPurchased = isPurchased,
        isAcknowledged = isAcknowledged,
        obfuscatedAccountId = obfuscatedAccountId,
    )

    @Test
    fun `free is the only unpaid plan`() {
        assertFalse(SubscriptionTier.FREE.includesProBenefits)
        assertTrue(SubscriptionTier.PRO.includesProBenefits)
        assertTrue(SubscriptionTier.AI.includesProBenefits)
    }

    @Test
    fun `buying cuentivo ai unlocks the ai plan`() {
        assertEquals(
            SubscriptionTier.AI,
            SubscriptionStatus.tierFrom(listOf(purchase(SubscriptionTier.AI_PRODUCT_ID)), testUserHash),
        )
    }

    @Test
    fun `holding both subscriptions resolves to the higher plan`() {
        // Someone who upgrades from Pro can briefly hold both. Resolving to PRO would take the AI
        // away from a person who is paying for it.
        val both = listOf(
            purchase(SubscriptionTier.PRO_PRODUCT_ID),
            purchase(SubscriptionTier.AI_PRODUCT_ID),
        )
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(both, testUserHash))
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(both.reversed(), testUserHash))
    }

    @Test
    fun `a pending ai purchase does not unlock anything`() {
        assertEquals(
            SubscriptionTier.FREE,
            SubscriptionStatus.tierFrom(listOf(purchase(SubscriptionTier.AI_PRODUCT_ID, isPurchased = false)), testUserHash),
        )
    }

    @Test
    fun `an unacknowledged ai purchase still needs acknowledging`() {
        // Play refunds anything left unacknowledged for three days. Missing the AI product here
        // would refund the first subscribers while the app kept treating them as paid.
        val pending = purchase(SubscriptionTier.AI_PRODUCT_ID, isAcknowledged = false)
        assertEquals(listOf(pending), SubscriptionStatus.needsAcknowledgement(listOf(pending)))
    }

    @Test
    fun `the ai plan keeps the cloud that pro has`() {
        // The regression this exists for: an `== PRO` check would leave the $299 customer without
        // the backup the $199 customer gets.
        assertTrue(SyncEntitlement.allowsCloudSync(SubscriptionTier.AI))
        assertTrue(SyncEntitlement.allowsCloudSync(SubscriptionTier.PRO))
        assertFalse(SyncEntitlement.allowsCloudSync(SubscriptionTier.FREE))
    }

    @Test
    fun `the ai plan has no customer or sale limits`() {
        val many = FeatureAccessManager.FREE_CUSTOMER_LIMIT + 10
        val manySales = FeatureAccessManager.FREE_SALE_LIMIT + 10
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canAddCustomer(SubscriptionTier.AI, many))
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canRegisterSale(SubscriptionTier.AI, manySales))
    }

    @Test
    fun `the ai plan unlocks every paid feature`() {
        PaidFeature.entries.forEach { feature ->
            assertEquals(
                "AI should unlock $feature",
                AccessDecision.Allowed,
                FeatureAccessManager.canUse(SubscriptionTier.AI, feature),
            )
        }
    }

    @Test
    fun `the ai plan may restore from the cloud`() {
        assertEquals(
            CloudRestoreDecision.Attempt,
            CloudRestorePolicy.decide(SubscriptionTier.AI, hasLocalBusiness = false, alreadyAttempted = false),
        )
    }

    @Test
    fun `the commercial ranking is declared, not inherited from declaration order`() {
        // Ranking by `ordinal` would make moving a constant for readability change who gets what.
        // These are the numbers the resolver actually uses.
        assertTrue(SubscriptionTier.AI.rank > SubscriptionTier.PRO.rank)
        assertTrue(SubscriptionTier.PRO.rank > SubscriptionTier.FREE.rank)
        SubscriptionTier.entries.forEach { tier ->
            assertEquals("$tier must rank above nothing but FREE at most", tier == SubscriptionTier.FREE, tier.rank == 0)
        }
    }

    @Test
    fun `every plan states its benefits instead of deriving them from not being free`() {
        // A future TRIAL or INTERNAL tier must start closed: `!= FREE` would have opened it by
        // accident the moment it was added to the enum.
        val paying = SubscriptionTier.entries.filter { it.includesProBenefits }
        assertEquals(listOf(SubscriptionTier.PRO, SubscriptionTier.AI), paying)
    }

    @Test
    fun `an unknown stored plan still degrades to free`() {
        // Closed by default survives the third plan: a name from a newer build must not unlock.
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.fromStoredValue("PLATINUM"))
        assertEquals(SubscriptionTier.AI, SubscriptionTier.fromStoredValue("AI"))
    }
}
