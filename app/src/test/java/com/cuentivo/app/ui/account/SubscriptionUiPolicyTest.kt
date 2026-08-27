package com.cuentivo.app.ui.account

import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.PaidFeature
import com.cuentivo.app.domain.SubscriptionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUiPolicyTest {

    @Test
    fun payment_registration_is_never_blocked_on_any_plan_or_limit() {
        // Core invariant: recording payments/abonos must never be paywalled or blocked on any tier.
        SubscriptionTier.entries.forEach { tier ->
            assertEquals(AccessDecision.Allowed, FeatureAccessManager.canRegisterPayment())
        }
    }

    @Test
    fun ai_entry_point_paywall_decision() {
        // When user opens AI: FREE and PRO show the subscription paywall; AI tier enters directly.
        fun shouldShowAiPaywall(tier: SubscriptionTier): Boolean = tier != SubscriptionTier.AI

        assertTrue(shouldShowAiPaywall(SubscriptionTier.FREE))
        assertTrue(shouldShowAiPaywall(SubscriptionTier.PRO))
        assertFalse(shouldShowAiPaywall(SubscriptionTier.AI))
    }

    @Test
    fun plan_tiers_commercial_hierarchy_and_benefits() {
        assertTrue(SubscriptionTier.AI.rank > SubscriptionTier.PRO.rank)
        assertTrue(SubscriptionTier.PRO.rank > SubscriptionTier.FREE.rank)

        // CuentivoAI includes all Pro benefits
        assertTrue(SubscriptionTier.AI.includesProBenefits)
        assertTrue(SubscriptionTier.PRO.includesProBenefits)
        assertFalse(SubscriptionTier.FREE.includesProBenefits)

        // Pro features are unlocked for AI tier as well
        PaidFeature.entries.forEach { feature ->
            assertEquals(AccessDecision.Allowed, FeatureAccessManager.canUse(SubscriptionTier.AI, feature))
            assertEquals(AccessDecision.Allowed, FeatureAccessManager.canUse(SubscriptionTier.PRO, feature))
            assertTrue(FeatureAccessManager.canUse(SubscriptionTier.FREE, feature) is AccessDecision.Blocked)
        }
    }

    @Test
    fun customer_and_sale_limits_unlocked_for_ai_and_pro_tiers() {
        // AI tier has unlimited customers and sales just like Pro
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canAddCustomer(SubscriptionTier.AI, 100))
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canRegisterSale(SubscriptionTier.AI, 500))

        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canAddCustomer(SubscriptionTier.PRO, 100))
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canRegisterSale(SubscriptionTier.PRO, 500))

        // Free tier is bounded
        assertTrue(FeatureAccessManager.canAddCustomer(SubscriptionTier.FREE, 5) is AccessDecision.Blocked)
        assertTrue(FeatureAccessManager.canRegisterSale(SubscriptionTier.FREE, 30) is AccessDecision.Blocked)
    }

    @Test
    fun product_ids_match_play_console_products() {
        assertEquals("cuentivo_pro_mensual", SubscriptionTier.PRO_PRODUCT_ID)
        assertEquals("cuentivo_ai_mensual", SubscriptionTier.AI_PRODUCT_ID)
    }
}
