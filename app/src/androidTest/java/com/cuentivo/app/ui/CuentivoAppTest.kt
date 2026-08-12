package com.cuentivo.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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

        composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Nueva venta").assertIsDisplayed()
        composeRule.onNodeWithText("$125.00").assertIsDisplayed()
    }

    @Test fun dashboard_follows_stitch_payment_first_hierarchy() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        composeRule.onNodeWithText("Saldo total por cobrar").assertIsDisplayed()
        composeRule.onNodeWithText("Vence hoy").assertIsDisplayed()
        composeRule.onNodeWithText("Vencido").assertIsDisplayed()
        composeRule.onNodeWithText("Registrar abono").assertIsDisplayed()
        composeRule.onNodeWithText("Clientes críticos").assertIsDisplayed()
        composeRule.onNodeWithText("Actividad reciente").assertIsDisplayed()
        composeRule.onAllNodesWithText("Tu cuenta").assertCountEquals(0)
        composeRule.onNodeWithText("Registrar abono").performClick()
        composeRule.onNodeWithTag("main-destination-payments").assertIsSelected()
    }

    @Test fun compact_shell_has_four_destinations_sale_action_and_account_entry() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        listOf("Inicio", "Clientes", "Productos", "Abonos").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
        composeRule.onAllNodesWithText("Vender").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Cuenta").performClick()
        composeRule.onNodeWithText("Plan Gratis").assertIsDisplayed()
    }

    @Test fun central_sale_action_keeps_destination_selected_and_returns_to_it() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        composeRule.onNodeWithTag("main-destination-customers").performClick()
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").performClick()
        composeRule.onAllNodesWithContentDescription("Cerrar nueva venta").assertCountEquals(0)
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
    }

    @Test fun large_system_text_keeps_every_navigation_action_accessible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
            }
        }

        listOf("Inicio", "Clientes", "Productos", "Abonos", "Nueva venta").forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        }
        // The dashboard's own secondary action also reads "Nueva venta", so the label is counted
        // on the shell action node itself instead of globally: exactly one description and the
        // label text living on that same node prove the shell exposes it once.
        composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Nueva venta").assertTextEquals("Nueva venta")
    }
}
