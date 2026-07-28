package com.cuentivo.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test

class CuentivoAppTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun empty_dashboard_explains_how_to_start() {
        composeRule.setContent { CuentivoApp(DashboardUiState.NoBusiness) }

        composeRule.onNodeWithText("Crea tu primer negocio para comenzar").assertIsDisplayed()
        composeRule.onNodeWithText("Inicio").assertIsDisplayed()
    }

    @Test fun populated_dashboard_shows_exactly_one_primary_sale_action() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        // Exactly one, on purpose. The redesign removed a full-width "Nueva venta" button that did
        // the same thing as the floating action button; two ways to start one task is clutter, and
        // this assertion is what stops the second one coming back.
        //
        // Matched by content description rather than text: the FAB is an icon, and its description
        // is what a screen reader announces — so this checks the action is reachable, not just drawn.
        composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Nueva venta").assertIsDisplayed()
        composeRule.onNodeWithText("$125.00").assertIsDisplayed()
    }
}
