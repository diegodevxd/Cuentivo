package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionStatusTest {

    private val userA = "user_diego_firebase_uid"
    private val userAHash = BillingAccountHasher.obfuscate(userA)!!
    private val userB = "user_other_firebase_uid"
    private val userBHash = BillingAccountHasher.obfuscate(userB)!!

    private fun purchase(
        productId: String = SubscriptionTier.PRO_PRODUCT_ID,
        isPurchased: Boolean = true,
        isAcknowledged: Boolean = true,
        obfuscatedAccountId: String? = null,
    ) = PlayPurchase(
        productIds = listOf(productId),
        isPurchased = isPurchased,
        isAcknowledged = isAcknowledged,
        obfuscatedAccountId = obfuscatedAccountId,
    )

    @Test
    fun `no purchases means free`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(emptyList(), userAHash))
    }

    @Test
    fun `a completed purchase of pro product without account identifier returns free`() {
        // Strict ownership check: zero historical Pro subscribers exist in production, so there are no
        // paying customers to protect by tolerating an empty account identifier.
        // A purchase with no obfuscatedAccountId strictly returns FREE for Pro (symmetrical with AI).
        val legacyPro = purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = null)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyPro), userAHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyPro), userBHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyPro), currentUserAccountHash = null))

        // Play returns an empty string "" instead of null when no account was attached.
        val legacyProEmptyString = purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = "")
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyProEmptyString), userAHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyProEmptyString), userBHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(legacyProEmptyString), currentUserAccountHash = null))
    }

    @Test
    fun `a completed purchase of pro product with matching account identifier unlocks pro`() {
        val proForUserA = purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.PRO, SubscriptionStatus.tierFrom(listOf(proForUserA), userAHash))
    }

    @Test
    fun `a completed purchase of pro product with different account identifier returns free`() {
        val proForUserA = purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(proForUserA), userBHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(proForUserA), currentUserAccountHash = null))
    }

    @Test
    fun `a completed purchase of the ai product with matching account identifier unlocks ai`() {
        val aiForUserA = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(listOf(aiForUserA), userAHash))
    }

    @Test
    fun `a completed purchase of the ai product with different account identifier returns free`() {
        // The exact bug found by Diego: 2nd user signing in on the device must NOT get AI entitlement.
        val aiForUserA = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(aiForUserA), userBHash))
    }

    @Test
    fun `a completed purchase of the ai product with no account identifier returns free`() {
        // Since cuentivo_ai_mensual was introduced in T2, every AI purchase must have an account identifier.
        val aiWithoutId = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = null)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(aiWithoutId), userAHash))

        // Empty string must also strictly yield FREE for AI
        val aiWithEmptyString = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = "")
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(aiWithEmptyString), userAHash))
    }

    @Test
    fun `a completed purchase of the ai product with unauthenticated user returns free`() {
        val aiForUserA = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(aiForUserA), currentUserAccountHash = null))
    }

    @Test
    fun `when both pro and ai are reported for the same user the higher rank ai wins`() {
        val purchases = listOf(
            purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = userAHash),
            purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = userAHash),
        )
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(purchases, userAHash))
    }

    @Test
    fun `when pro for user b and ai for user a are reported user b gets pro while ai is rejected`() {
        val purchases = listOf(
            purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = userBHash),
            purchase(productId = SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = userAHash),
        )
        assertEquals(SubscriptionTier.PRO, SubscriptionStatus.tierFrom(purchases, userBHash))
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(purchases, userAHash))
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(purchases, currentUserAccountHash = null))
    }

    @Test
    fun `a pending purchase does not unlock pro`() {
        // Cash payment methods leave a purchase pending for hours. Granting Pro here would hand the
        // paid plan to anyone willing to start a payment and never finish it.
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(purchase(isPurchased = false)), userAHash))
    }

    @Test
    fun `a pending ai purchase does not unlock ai`() {
        val pendingAi = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, isPurchased = false, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(pendingAi), userAHash))
    }

    @Test
    fun `a purchase of some other product does not unlock pro`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionStatus.tierFrom(listOf(purchase(productId = "otra_cosa")), userAHash))
    }

    @Test
    fun `access is granted before the purchase is acknowledged`() {
        // Google's order is deliver first, acknowledge second; waiting would show the free plan to
        // someone who just paid.
        val unackPro = purchase(isAcknowledged = false, obfuscatedAccountId = userAHash)
        val unackAi = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, isAcknowledged = false, obfuscatedAccountId = userAHash)
        assertEquals(SubscriptionTier.PRO, SubscriptionStatus.tierFrom(listOf(unackPro), userAHash))
        assertEquals(SubscriptionTier.AI, SubscriptionStatus.tierFrom(listOf(unackAi), userAHash))
    }

    @Test
    fun `only unacknowledged completed purchases of known products need acknowledging`() {
        val needsPro = purchase(productId = SubscriptionTier.PRO_PRODUCT_ID, isAcknowledged = false)
        val needsAi = purchase(productId = SubscriptionTier.AI_PRODUCT_ID, isAcknowledged = false)
        val purchases = listOf(
            needsPro,
            needsAi,
            purchase(),
            purchase(productId = SubscriptionTier.AI_PRODUCT_ID, isAcknowledged = true),
            purchase(isPurchased = false, isAcknowledged = false),
            purchase(productId = "otra_cosa", isAcknowledged = false),
        )

        assertEquals(listOf(needsPro, needsAi), SubscriptionStatus.needsAcknowledgement(purchases))
    }

    @Test
    fun `the product ids match the ones configured in play console`() {
        // A typo here is invisible until a real purchase silently fails to unlock anything.
        assertEquals("cuentivo_pro_mensual", SubscriptionTier.PRO_PRODUCT_ID)
        assertEquals("cuentivo_ai_mensual", SubscriptionTier.AI_PRODUCT_ID)
    }
}
