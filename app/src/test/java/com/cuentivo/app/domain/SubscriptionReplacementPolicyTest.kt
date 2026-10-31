package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionReplacementPolicyTest {

    private val testUserId = "user_test_policy"
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
    fun upgrade_from_pro_to_ai_uses_charge_prorated_price() {
        val mode = SubscriptionReplacementPolicy.replacementModeFor(
            currentProductId = SubscriptionTier.PRO_PRODUCT_ID,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            ReplacementMode.CHARGE_PRORATED,
            mode,
        )
    }

    @Test
    fun downgrade_from_ai_to_pro_uses_deferred() {
        val mode = SubscriptionReplacementPolicy.replacementModeFor(
            currentProductId = SubscriptionTier.AI_PRODUCT_ID,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertEquals(
            ReplacementMode.DEFERRED,
            mode,
        )
    }

    @Test
    fun new_subscription_without_prior_purchase_returns_null_replacement_mode() {
        val mode = SubscriptionReplacementPolicy.replacementModeFor(
            currentProductId = null,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertNull(mode)
    }

    @Test
    fun purchasing_same_active_plan_returns_null_replacement_mode() {
        val mode = SubscriptionReplacementPolicy.replacementModeFor(
            currentProductId = SubscriptionTier.AI_PRODUCT_ID,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertNull(mode)
    }

    @Test
    fun isProductAlreadyActive_checks_presence_in_purchases_for_user() {
        val purchases = listOf(purchase(SubscriptionTier.PRO_PRODUCT_ID))
        assertTrue(SubscriptionReplacementPolicy.isProductAlreadyActive(purchases, SubscriptionTier.PRO_PRODUCT_ID, testUserHash))
        assertFalse(SubscriptionReplacementPolicy.isProductAlreadyActive(purchases, SubscriptionTier.AI_PRODUCT_ID, testUserHash))

        // Different user does not have it active
        assertFalse(SubscriptionReplacementPolicy.isProductAlreadyActive(purchases, SubscriptionTier.PRO_PRODUCT_ID, "other_user_hash"))
    }

    @Test
    fun `isProductAlreadyActive returns false for pro without account identifier`() {
        val unlinkedPro = listOf(purchase(SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = null))
        assertFalse(SubscriptionReplacementPolicy.isProductAlreadyActive(unlinkedPro, SubscriptionTier.PRO_PRODUCT_ID, testUserHash))
        assertFalse(SubscriptionReplacementPolicy.isProductAlreadyActive(unlinkedPro, SubscriptionTier.PRO_PRODUCT_ID, "other_user_hash"))
    }

    @Test
    fun findProductToReplace_identifies_other_active_product() {
        val proPurchase = listOf(purchase(SubscriptionTier.PRO_PRODUCT_ID))
        assertEquals(
            SubscriptionTier.PRO_PRODUCT_ID,
            SubscriptionReplacementPolicy.findProductToReplace(proPurchase, SubscriptionTier.AI_PRODUCT_ID, testUserHash),
        )

        val aiPurchase = listOf(purchase(SubscriptionTier.AI_PRODUCT_ID))
        assertEquals(
            SubscriptionTier.AI_PRODUCT_ID,
            SubscriptionReplacementPolicy.findProductToReplace(aiPurchase, SubscriptionTier.PRO_PRODUCT_ID, testUserHash),
        )
    }

    @Test
    fun findProductToReplace_does_not_replace_other_users_purchase() {
        val otherUserAiPurchase = listOf(purchase(SubscriptionTier.AI_PRODUCT_ID, obfuscatedAccountId = "other_user_hash"))
        assertNull(
            SubscriptionReplacementPolicy.findProductToReplace(otherUserAiPurchase, SubscriptionTier.PRO_PRODUCT_ID, testUserHash),
        )
    }

    @Test
    fun transient_state_with_both_reported_replaces_highest_different_plan() {
        val bothPurchases = listOf(
            purchase(SubscriptionTier.PRO_PRODUCT_ID),
            purchase(SubscriptionTier.AI_PRODUCT_ID),
        )
        // If user targets Pro, replace AI
        assertEquals(
            SubscriptionTier.AI_PRODUCT_ID,
            SubscriptionReplacementPolicy.findProductToReplace(bothPurchases, SubscriptionTier.PRO_PRODUCT_ID, testUserHash),
        )
        // If user targets AI, replace Pro
        assertEquals(
            SubscriptionTier.PRO_PRODUCT_ID,
            SubscriptionReplacementPolicy.findProductToReplace(bothPurchases, SubscriptionTier.AI_PRODUCT_ID, testUserHash),
        )
    }
}
