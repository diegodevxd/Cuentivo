package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test

/**
 * Verifies that recording payments/abonos is never paywalled or blocked on any subscription tier (FREE, PRO),
 * and confirms that the CuentivoAI paywall sheet opens exclusively when tapping the AI entry point.
 */
class PaymentRegressionAndAiPaywallFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun free_plan_payment_navigation_from_bottom_bar_enters_payments_without_paywall() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }

        // Tap bottom bar Abonos
        composeRule.onNodeWithContentDescription("Abonos").performClick()
        composeRule.waitForIdle()

        // Payments destination is reached directly
        composeRule.onNodeWithTag("selector-cliente").assertIsDisplayed()

        // Paywall is NOT displayed
        composeRule.onAllNodesWithText("CuentivoAI ejecuta por ti").assertCountEquals(0)
    }

    @Test
    fun free_plan_payment_navigation_from_customer_detail_enters_payments_without_paywall() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }

        // Open customer detail
        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.waitForIdle()

        // Tap "Registrar abono" from customer statement/detail
        composeRule.onNodeWithText("Registrar abono").performClick()
        composeRule.waitForIdle()

        // Preselected customer payment form is displayed
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onNodeWithText("Monto del abono").assertIsDisplayed()

        // Paywall is NOT displayed
        composeRule.onAllNodesWithText("CuentivoAI ejecuta por ti").assertCountEquals(0)
    }

    @Test
    fun pro_plan_payment_navigation_from_bottom_bar_enters_payments_without_paywall() {
        composeRule.setContent { Harness(SubscriptionTier.PRO) }

        composeRule.onNodeWithContentDescription("Abonos").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("selector-cliente").assertIsDisplayed()
        composeRule.onAllNodesWithText("CuentivoAI ejecuta por ti").assertCountEquals(0)
    }

    @Test
    fun pro_plan_payment_navigation_from_customer_detail_enters_payments_without_paywall() {
        composeRule.setContent { Harness(SubscriptionTier.PRO) }

        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Registrar abono").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onNodeWithText("Monto del abono").assertIsDisplayed()
        composeRule.onAllNodesWithText("CuentivoAI ejecuta por ti").assertCountEquals(0)
    }

    @Test
    fun free_plan_ai_entry_point_displays_ai_paywall() {
        composeRule.setContent { Harness(SubscriptionTier.FREE) }

        // Tap top bar CuentivoAI action
        composeRule.onNodeWithTag("global-ai-action").performClick()
        composeRule.waitForIdle()

        // AI Paywall is displayed with buy CTA
        composeRule.onNodeWithText("CuentivoAI ejecuta por ti").assertIsDisplayed()
        composeRule.onNodeWithText("Obtener CuentivoAI").assertIsDisplayed()
    }

    @Test
    fun pro_plan_ai_entry_point_displays_ai_paywall_with_upgrade_action() {
        composeRule.setContent { Harness(SubscriptionTier.PRO) }

        // Tap top bar CuentivoAI action
        composeRule.onNodeWithTag("global-ai-action").performClick()
        composeRule.waitForIdle()

        // AI Paywall is displayed with switch/upgrade CTA
        composeRule.onNodeWithText("CuentivoAI ejecuta por ti").assertIsDisplayed()
        composeRule.onNodeWithText("Cambiar a CuentivoAI").assertIsDisplayed()
    }

    @Composable
    private fun Harness(tier: SubscriptionTier) {
        var statementCustomer by remember { mutableStateOf<CustomerBalanceRow?>(null) }
        val customers = listOf(
            CustomerBalanceRow(
                customer = CustomerEntity(
                    id = "c1",
                    businessId = "b1",
                    name = "Juan Pérez",
                    normalizedName = "juan perez",
                    phone = "5512345678",
                    email = null,
                    address = null,
                    notes = null,
                    creditLimitCents = null,
                    isActive = true,
                    createdAtEpochMillis = 1000L,
                    updatedAtEpochMillis = 1000L,
                    deletedAtEpochMillis = null,
                ),
                balanceCents = 150_000L,
            )
        )

        CuentivoApp(
            state = DashboardUiState.Content("b1", "Mi negocio", 150_000L, 0, 0, 0, 1),
            customers = customers,
            tier = tier,
            statementCustomer = statementCustomer,
            onShowStatement = { statementCustomer = it },
            onDismissStatement = { statementCustomer = null },
        )
    }
}
