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
    fun payment_registration_is_unconditionally_allowed() {
        assertEquals(AccessDecision.Allowed, FeatureAccessManager.canRegisterPayment())
    }

    @Test
    fun ai_entry_policy_routes_correctly_for_all_tiers_and_transports() {
        // FREE and PRO tiers must ALWAYS route to Paywall, regardless of transport or service availability
        for (transport in listOf(true, false)) {
            for (available in listOf(true, false)) {
                assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.FREE, transport, available))
                assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.PRO, transport, available))
            }
        }

        // AI tier without transport routes to UnavailableInCurrentBuild
        assertEquals(AiEntryRoute.UnavailableInCurrentBuild, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = false, isServiceAvailable = true))
        assertEquals(AiEntryRoute.UnavailableInCurrentBuild, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = false, isServiceAvailable = false))

        // AI tier with transport and service enabled routes to Assistant
        assertEquals(AiEntryRoute.Assistant, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = true, isServiceAvailable = true))

        // AI tier with transport but remote service disabled routes to ServiceDisabled (maintenance) - never Paywall
        assertEquals(AiEntryRoute.ServiceDisabled, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = true, isServiceAvailable = false))
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
