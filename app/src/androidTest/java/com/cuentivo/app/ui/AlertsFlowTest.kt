package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Task 8 behaviour for Avisos, driven through the real `ModalBottomSheet` wrapper rather than the
 * extracted content, so opening, routing and dismissing are all covered as shipped.
 *
 * Severity must survive without colour. Every row carries a word for it, because a tint is the
 * first thing to disappear on a cheap screen in the sun — and the only thing a screen reader has.
 */
class AlertsFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_bell_opens_the_sheet_with_every_alert_in_order() {
        composeRule.setContent { Harness() }
        openAlerts()

        composeRule.onNodeWithText("Avisos").assertIsDisplayed()
        composeRule.onNodeWithText("Ana López").assertIsDisplayed()
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onNodeWithText("Te queda 1 cliente").assertIsDisplayed()
    }

    @Test fun each_severity_is_spelled_out_and_not_left_to_colour() {
        composeRule.setContent { Harness() }
        openAlerts()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.alerts_severity_overdue)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.alerts_severity_due_soon)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.alerts_severity_info)).assertIsDisplayed()
    }

    @Test fun tapping_an_alert_opens_that_exact_customer() {
        composeRule.setContent { Harness() }
        openAlerts()

        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.runOnIdle { assertEquals(listOf("juan"), opened.toList()) }
    }

    @Test fun an_alert_without_a_customer_has_no_navigation_action() {
        composeRule.setContent { Harness() }
        openAlerts()

        composeRule.onNodeWithText("Te queda 1 cliente").assertHasNoClickAction()
    }

    @Test fun an_empty_sheet_says_so_without_offering_a_fake_action() {
        composeRule.setContent { Harness(alerts = emptyList()) }
        openAlerts()

        composeRule.onNodeWithText("Nada pendiente").assertIsDisplayed()
        composeRule.onNodeWithText("Nadie se te ha atrasado.").assertIsDisplayed()
        // There is nothing to do about good news.
        composeRule.onAllNodesWithText("Ver clientes").assertCountEquals(0)
    }

    @Test fun the_sheet_closes_again() {
        composeRule.setContent { Harness() }
        openAlerts()
        composeRule.onNodeWithText("Avisos").assertIsDisplayed()

        composeRule.onNodeWithText("Juan Pérez").performClick()

        // Routing to a customer also closes the sheet; it is a place you check, not one you live in.
        composeRule.onAllNodesWithText("Avisos").assertCountEquals(0)
    }

    private fun openAlerts() {
        composeRule.onNodeWithContentDescription("Avisos").performClick()
        composeRule.waitForIdle()
    }

    private val opened = mutableListOf<String>()

    @Composable
    private fun Harness(alerts: List<Alert> = ALERTS) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 480_050, 0, 0, 0, 1),
            alerts = alerts,
            onOpenAlertCustomer = { opened += it },
        )
    }

    private companion object {
        val ALERTS = listOf(
            Alert(
                title = "Ana López",
                detail = "Debe \$1,200.00 desde hace 4 días.",
                severity = Alert.Severity.OVERDUE,
                customerId = "ana",
            ),
            Alert(
                title = "Juan Pérez",
                detail = "Le toca pagar hoy: \$800.00.",
                severity = Alert.Severity.DUE_SOON,
                customerId = "juan",
            ),
            Alert(
                title = "Te queda 1 cliente",
                detail = "El plan gratis permite 5 clientes.",
                severity = Alert.Severity.INFO,
            ),
        )
    }
}
