package com.cuentivo.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.domain.Alert
import com.cuentivo.app.domain.Insight
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The two halves of "what do I do today": the month's numbers on the dashboard, and collecting
 * from the alerts list without leaving it.
 */
class CollectionsAndReportTest {
    @get:Rule val composeRule = createComposeRule()

    private val reminded = mutableListOf<String>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun the_month_shows_what_was_sold_and_collected() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithText(context.getString(R.string.dashboard_month_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard-month").assertIsDisplayed()
        composeRule.onNodeWithText("$1,250.00").assertIsDisplayed()
        composeRule.onNodeWithText("$400.00").assertIsDisplayed()
    }

    /** Collecting used to mean leaving this list to hunt for the customer. */
    @Test fun an_alert_can_send_the_reminder_without_leaving_the_list() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithContentDescription(context.getString(R.string.nav_alerts_description)).performClick()

        composeRule.onAllNodesWithTag("alert-remind")[0]
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.runOnIdle { assertEquals(listOf("juan"), reminded) }
    }

    /** An alert about the plan belongs to nobody, so there is nobody to remind. */
    @Test fun an_alert_without_a_customer_offers_no_reminder() {
        composeRule.setContent { Harness(alerts = listOf(Alert("Plan", "Vas al límite", Alert.Severity.INFO))) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.nav_alerts_description)).performClick()

        composeRule.onAllNodesWithTag("alert-remind").assertCountEquals(0)
    }

    @androidx.compose.runtime.Composable
    private fun Harness(
        alerts: List<Alert> = listOf(
            Alert("Juan Pérez", "Debe $480.50 desde hace 3 días", Alert.Severity.OVERDUE, customerId = "juan"),
        ),
    ) {
        CuentivoApp(
            state = DashboardUiState.Content(
                businessId = "business-1",
                businessName = "Mi negocio",
                receivableCents = 48_050,
                soldCents = 125_000,
                collectedCents = 40_000,
                overdueCents = 0,
                customersWithDebt = 1,
                insights = listOf(Insight("Cobraste 20% más que el mes pasado", Insight.Tone.GOOD)),
            ),
            alerts = alerts,
            onRemindCustomer = { reminded += it },
        )
    }
}
