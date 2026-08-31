package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseLaunchPolicyTest {

    private val testUserId = "user_12345_firebase_uid"
    private val expectedObfuscatedId = BillingAccountHasher.obfuscate(testUserId)!!

    private fun purchase(
        productId: String,
        isPurchased: Boolean = true,
        isAcknowledged: Boolean = true,
        obfuscatedAccountId: String? = expectedObfuscatedId,
    ) = PlayPurchase(
        productIds = listOf(productId),
        isPurchased = isPurchased,
        isAcknowledged = isAcknowledged,
        obfuscatedAccountId = obfuscatedAccountId,
    )

    @Test
    fun query_pending_blocks_purchase() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Pending,
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.QUERY_PENDING),
            decision,
        )
    }

    @Test
    fun query_failed_blocks_purchase() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Failed,
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.QUERY_FAILED),
            decision,
        )
    }

    @Test
    fun resolved_and_genuinely_empty_proceeds_with_clean_purchase() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
                productToReplace = null,
                replacementMode = null,
                obfuscatedAccountId = expectedObfuscatedId,
            ),
            decision,
        )
    }

    @Test
    fun active_pro_buying_ai_replaces_pro_with_prorated_charge() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(purchase(SubscriptionTier.PRO_PRODUCT_ID)),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.AI_PRODUCT_ID,
                productToReplace = SubscriptionTier.PRO_PRODUCT_ID,
                replacementMode = ReplacementMode.CHARGE_PRORATED,
                obfuscatedAccountId = expectedObfuscatedId,
            ),
            decision,
        )
    }

    @Test
    fun unlinked_pro_buying_ai_treats_as_fresh_purchase() {
        val unlinkedPro = purchase(SubscriptionTier.PRO_PRODUCT_ID, obfuscatedAccountId = null)
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(unlinkedPro),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.AI_PRODUCT_ID,
                productToReplace = null,
                replacementMode = null,
                obfuscatedAccountId = expectedObfuscatedId,
            ),
            decision,
        )
    }

    @Test
    fun active_ai_buying_pro_replaces_ai_with_deferred_mode() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(purchase(SubscriptionTier.AI_PRODUCT_ID)),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
                productToReplace = SubscriptionTier.AI_PRODUCT_ID,
                replacementMode = ReplacementMode.DEFERRED,
                obfuscatedAccountId = expectedObfuscatedId,
            ),
            decision,
        )
    }

    @Test
    fun active_ai_buying_ai_blocks_as_already_active() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(purchase(SubscriptionTier.AI_PRODUCT_ID)),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ALREADY_ACTIVE),
            decision,
        )
    }

    @Test
    fun user_b_can_purchase_ai_when_device_has_user_a_ai_purchase() {
        // Crucial commercial fix: User B on the same device is not blocked from purchasing AI
        // when User A's AI purchase is present on the device.
        val userAId = "user_a_uid"
        val userAHash = BillingAccountHasher.obfuscate(userAId)!!
        val userBId = "user_b_uid"
        val userBHash = BillingAccountHasher.obfuscate(userBId)!!

        val userAPurchase = purchase(
            productId = SubscriptionTier.AI_PRODUCT_ID,
            obfuscatedAccountId = userAHash,
        )

        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(userAPurchase),
                queriedUserId = userBId,
            ),
            currentUserId = userBId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.AI_PRODUCT_ID,
                productToReplace = null,
                replacementMode = null,
                obfuscatedAccountId = userBHash,
            ),
            decision,
        )
    }

    @Test
    fun active_pro_buying_pro_blocks_as_already_active() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = listOf(purchase(SubscriptionTier.PRO_PRODUCT_ID)),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ALREADY_ACTIVE),
            decision,
        )
    }

    @Test
    fun both_pro_and_ai_active_blocks_as_inconsistent_state() {
        val bothPurchases = listOf(
            purchase(SubscriptionTier.PRO_PRODUCT_ID),
            purchase(SubscriptionTier.AI_PRODUCT_ID),
        )
        val decisionForAi = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = bothPurchases,
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertTrue(
            decisionForAi is PurchaseLaunchDecision.Blocked &&
                (decisionForAi.reason == PurchaseBlockReason.ALREADY_ACTIVE || decisionForAi.reason == PurchaseBlockReason.INCONSISTENT_STATE)
        )

        val decisionForOther = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = bothPurchases,
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
        )
        assertTrue(
            decisionForOther is PurchaseLaunchDecision.Blocked &&
                (decisionForOther.reason == PurchaseBlockReason.ALREADY_ACTIVE || decisionForOther.reason == PurchaseBlockReason.INCONSISTENT_STATE)
        )
    }

    @Test
    fun user_mismatch_between_query_snapshot_and_purchase_blocks() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = "user_ana",
            ),
            currentUserId = "user_beto",
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.USER_MISMATCH),
            decision,
        )
    }

    @Test
    fun missing_or_blank_user_id_blocks_purchase() {
        val decisionNull = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = null,
            ),
            currentUserId = null,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.MISSING_USER_ID),
            decisionNull,
        )

        val decisionBlank = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = "  ",
            ),
            currentUserId = "  ",
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.MISSING_USER_ID),
            decisionBlank,
        )
    }

    @Test
    fun unknown_target_product_blocks_purchase() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = "producto_desconocido",
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.UNKNOWN_PRODUCT),
            decision,
        )
    }

    @Test
    fun anonymous_session_with_valid_product_blocks_purchase_with_anonymous_session_reason() {
        val anonymousUserId = "anon_uid_98765"
        val decisionAi = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = anonymousUserId,
            ),
            currentUserId = anonymousUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
            isAnonymous = true,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ANONYMOUS_SESSION),
            decisionAi,
        )

        val decisionPro = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = anonymousUserId,
            ),
            currentUserId = anonymousUserId,
            targetProductId = SubscriptionTier.PRO_PRODUCT_ID,
            isAnonymous = true,
        )
        assertEquals(
            PurchaseLaunchDecision.Blocked(PurchaseBlockReason.ANONYMOUS_SESSION),
            decisionPro,
        )
    }

    @Test
    fun authenticated_real_account_does_not_block_for_anonymous_session() {
        val decision = PurchaseLaunchPolicy.evaluate(
            queryState = PurchasesQueryState.Resolved(
                purchases = emptyList(),
                queriedUserId = testUserId,
            ),
            currentUserId = testUserId,
            targetProductId = SubscriptionTier.AI_PRODUCT_ID,
            isAnonymous = false,
        )
        assertEquals(
            PurchaseLaunchDecision.Proceed(
                targetProductId = SubscriptionTier.AI_PRODUCT_ID,
                productToReplace = null,
                replacementMode = null,
                obfuscatedAccountId = expectedObfuscatedId,
            ),
            decision,
        )
    }
}
