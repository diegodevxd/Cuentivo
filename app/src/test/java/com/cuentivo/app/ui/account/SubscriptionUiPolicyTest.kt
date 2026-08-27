package com.cuentivo.app.ui.account

import com.cuentivo.app.domain.AccessDecision
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.PaidFeature
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.MainDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUiPolicyTest {

    @Test
    fun payment_registration_and_navigation_never_blocked_or_paywalled_on_any_plan() {
        // Core invariant: recording payments/abonos must NEVER be paywalled, blocked, or intercepted under any tier.
        SubscriptionTier.entries.forEach { tier ->
            // Access evaluation on the tier
            val decision = PaymentNavigationPolicy.evaluatePaymentAccess(tier)
            assertEquals(AccessDecision.Allowed, decision)

            // Direct route destination
            assertEquals(MainDestination.PAYMENTS, PaymentNavigationPolicy.resolveDestination())

            // Never triggers or intercepts with paywall
            assertFalse(PaymentNavigationPolicy.isPaymentPaywalled(tier))
        }
    }

    @Test
    fun ai_entry_policy_routes_correctly_for_all_tiers_and_transports() {
        // FREE and PRO tiers must ALWAYS route to Paywall, regardless of whether a transport exists
        assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.FREE, hasTransport = true))
        assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.FREE, hasTransport = false))

        assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.PRO, hasTransport = true))
        assertEquals(AiEntryRoute.Paywall, AiEntryPolicy.route(SubscriptionTier.PRO, hasTransport = false))

        // AI tier routes to Assistant when transport is present (debug / T6)
        assertEquals(AiEntryRoute.Assistant, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = true))

        // AI tier routes to UnavailableInCurrentBuild when transport is null (release build prior to T6)
        // ensuring no silent dead-click / wall for paying subscribers
        assertEquals(AiEntryRoute.UnavailableInCurrentBuild, AiEntryPolicy.route(SubscriptionTier.AI, hasTransport = false))
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
