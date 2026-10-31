package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.PaidFeature
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Task 8 behaviour for the plan section inside Cuenta.
 *
 * The limits are read from `FeatureAccessManager` rather than typed into the assertions, so
 * tightening the free plan stays the one-line change that file promises instead of quietly making
 * the interface lie.
 *
 * Nothing here touches Play Billing. `onUpgrade` is recorded, never performed: a test must not be
 * able to claim a purchase succeeded when `BillingGateway` never said so.
 */
class SubscriptionFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_free_plan_states_the_limits_it_actually_enforces() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }
        openAccount()

        inSheet("Plan Gratis").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        inSheet(
            context.getString(
                R.string.subscription_free_limits,
                FeatureAccessManager.FREE_CUSTOMER_LIMIT,
                FeatureAccessManager.FREE_SALE_LIMIT,
            )
        ).assertIsDisplayed()
    }

    @Test fun the_free_plan_names_what_pro_adds_from_the_paid_feature_list() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }
        openAccount()

        // The copy is the feature list, not a marketing paraphrase of it.
        inSheet(PaidFeature.CLOUD_BACKUP.explanation).assertIsDisplayed()
        inSheet(PaidFeature.PHOTOS.explanation).assertIsDisplayed()
    }

    @Test fun the_paid_plan_drops_the_upsell() {
        composeRule.setContent { Harness(SubscriptionTier.PRO) }
        openAccount()

        inSheet("Cuentivo Pro").assertIsDisplayed()
        composeRule.onAllNodesWithText("Suscribirme a Pro").assertCountEquals(0)
        composeRule.onNodeWithTag("account-sheet-scroll").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        inSheet("Tu suscripción se administra desde Google Play.").assertIsDisplayed()
    }

    @Test fun subscribing_calls_the_one_real_callback() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }
        openAccount()

        inSheet("Suscribirme a Pro").performClick()

        composeRule.runOnIdle { assertEquals(1, upgrades) }
    }

    /**
     * Guards against copy drifting ahead of the product. None of these exist: there is no restore
     * callback, no Business tier, no credits, and no price string anywhere in the app.
     */
    @Test fun nothing_is_offered_that_the_app_cannot_do() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }
        openAccount()

        listOf(
            "Restaurar compras",
            "Restaurar",
            "Business",
            "Créditos",
        ).forEach { absent ->
            composeRule.onAllNodesWithText(absent).assertCountEquals(0)
        }
    }

    /**
     * Cuenta is a scrolling sheet, and at 320 dp the plan section runs past its fold. Scrolling
     * first asserts what a person can actually reach rather than only what fits unscrolled.
     */
    private fun inSheet(text: String) = composeRule.run {
        onNodeWithTag("account-sheet-scroll").performScrollToNode(hasText(text))
        onNodeWithText(text)
    }

    private fun openAccount() {
        composeRule.onNodeWithContentDescription("Cuenta").performClick()
        composeRule.waitForIdle()
    }

    private var upgrades = 0

    @Composable
    private fun Harness(tier: SubscriptionTier) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 480_050, 0, 0, 0, 1),
            tier = tier,
            proPrice = "$99.00",
            aiPrice = "$299.00",
            onUpgrade = { upgrades++ },
        )
    }
}
