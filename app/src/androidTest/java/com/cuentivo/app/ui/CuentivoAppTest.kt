package com.cuentivo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.ui.transaction.SELECTOR_SHEET_TAG
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CuentivoAppTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun sale_navigation_label_fits_at_narrow_and_labelled_widths() {
        val widthDp = mutableIntStateOf(360)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                Box(Modifier.width(widthDp.intValue.dp)) {
                    CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0))
                }
            }
        }

        listOf(360, 390, 411).forEach { targetWidthDp ->
            composeRule.runOnIdle { widthDp.intValue = targetWidthDp }
            composeRule.waitForIdle()

            if (targetWidthDp == 360) {
                // Safe insets can put a 360 dp root into compact mode; the sale action still spans
                // the row and must keep its complete label there too.
                composeRule.onNodeWithContentDescription("Inicio").assertIsDisplayed()
                composeRule.onAllNodesWithText("Inicio").assertCountEquals(0)
            } else {
                // At 390 and 411 dp the labelled row is in use, so its destination label is visible.
                composeRule.onNodeWithText("Inicio").assertIsDisplayed()
            }
            val saleAction = composeRule.onNodeWithContentDescription("Nueva venta")
            saleAction.assertIsDisplayed()
            saleAction.assertTextEquals("Nueva venta")
            val allocatedWidth = saleAction.fetchSemanticsNode().boundsInRoot.width
            assertTrue("a $targetWidthDp dp label should have at least 74 dp", allocatedWidth >= 74f)
        }
    }

    @Test fun dashboard_uses_the_localized_prior_debt_label_from_the_customer_ledger() {
        val priorDebt = DashboardActivity(
            id = "movement-prior-debt",
            customerId = "rosa",
            customerName = "Rosa Martinez",
            type = "CHARGE",
            direction = "DEBIT",
            amountCents = 15_000,
            occurredAtEpochMillis = 1,
            isPriorDebt = true,
        )
        composeRule.setContent {
            CuentivoApp(
                DashboardUiState.Content(
                    "business-1", "Mi negocio", 15_000, 0, 0, 0, 1,
                    recentActivity = listOf(priorDebt),
                ),
            )
        }

        composeRule.onNodeWithText("Deuda anterior").assertIsDisplayed()
        composeRule.onAllNodesWithText("Nueva venta").assertCountEquals(1)
    }

    @Test fun empty_dashboard_explains_how_to_start() {
        composeRule.setContent { CuentivoApp(DashboardUiState.NoBusiness) }

        composeRule.onNodeWithText("Crea tu primer negocio para comenzar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Inicio").assertIsDisplayed()
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
        composeRule.onNodeWithText("Quién te debe más").assertIsDisplayed()
        composeRule.onNodeWithText("Actividad reciente").assertIsDisplayed()
        // The top bar intentionally carries the "Tu cuenta" entry (shell), so the account sheet's
        // own marker is the check that it is not open on the dashboard.
        composeRule.onAllNodesWithText("Plan Gratis").assertCountEquals(0)
        composeRule.onNodeWithText("Registrar abono").performClick()
        composeRule.onNodeWithTag("main-destination-payments").assertIsSelected()
    }

    @Test fun dashboard_structural_sections_are_announced_as_headings() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        // TalkBack users navigate by headings; the dashboard's structural sections must expose
        // themselves as such instead of reading as one undifferentiated wall of text.
        composeRule.onNodeWithText("Quién te debe más").assert(isHeading())
        composeRule.onNodeWithText("Este mes").assert(isHeading())
        composeRule.onNodeWithText("Actividad reciente").assert(isHeading())
        // The shell reports the current destination as selected on the same tree.
        composeRule.onNodeWithTag("main-destination-home").assertIsSelected()
    }

    @Test fun dashboard_keeps_hero_payment_action_and_task_sections_reachable() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        // The hierarchy stays reachable: dominant receivable hero, its payment action, then the
        // task-oriented sections.
        composeRule.onNodeWithText("Saldo total por cobrar").assertIsDisplayed()
        composeRule.onNodeWithText("Registrar abono").assertIsDisplayed()
        composeRule.onNodeWithText("Quién te debe más").assertIsDisplayed()
        composeRule.onNodeWithText("Actividad reciente").assertIsDisplayed()
        // "Nueva venta" is permanently offered by the bottom bar; the dashboard does not duplicate
        // it with a second affordance of its own.
        composeRule.onAllNodesWithText("Nueva venta").assertCountEquals(1)
    }

    @Test fun compact_shell_has_four_destinations_sale_action_and_account_entry() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        listOf("Inicio", "Clientes", "Productos", "Abonos").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        composeRule.onAllNodesWithContentDescription("Nueva venta").assertCountEquals(1)
        composeRule.onAllNodesWithText("Vender").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Cuenta").performClick()
        composeRule.onNodeWithText("Plan Gratis").assertIsDisplayed()
    }

    @Test fun central_sale_action_marks_the_sale_and_returns_to_the_tab() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        composeRule.onNodeWithTag("main-destination-customers").performClick()
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        // The sale owns the screen, so the bar marks the sale action — not the tab the person came
        // from, which is what used to stay lit under a screen it did not own.
        composeRule.onNodeWithTag("main-action-new-sale").assertIsSelected()
        composeRule.onNodeWithTag("main-destination-customers").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").performClick()
        composeRule.onAllNodesWithContentDescription("Cerrar nueva venta").assertCountEquals(0)
        composeRule.onNodeWithTag("main-action-new-sale").assertIsNotSelected()
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
    }

    @Test fun a_customer_detail_marks_the_customers_tab_not_the_previous_one() {
        val row = rosaRow()
        composeRule.setContent {
            CuentivoApp(
                DashboardUiState.Content("business-1", "Mi negocio", 15_000, 0, 0, 0, 1),
                customers = listOf(row),
                statementCustomer = row,
            )
        }

        // Opened with Inicio as the underlying destination: the bar still marks Clientes, because
        // the account belongs to that tab, not to whichever one it was opened from.
        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
        composeRule.onNodeWithTag("main-destination-home").assertIsNotSelected()
    }

    @Test fun new_sale_without_customers_offers_to_add_one() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0), customers = emptyList())
        }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        composeRule.onNodeWithTag("selector-cliente").performClick()
        val customerSheet = composeRule.onNodeWithTag(SELECTOR_SHEET_TAG)
        customerSheet.assertIsDisplayed()
        composeRule.onNodeWithText(
            "Todavía no tienes clientes. Usa Agregar cliente para crear el primero.",
        ).assertIsDisplayed()
        // Scope the action to the open selector sheet; the sale form also has an Add customer
        // action behind it, so a global text match would let that unrelated button hide a regression.
        customerSheet.onChildren().assertAny(hasText("Agregar cliente"))
        customerSheet.onChildren().filterToOne(hasText("Agregar cliente")).performClick()
        composeRule.onNodeWithText("Nuevo cliente").assertIsDisplayed()
    }

    @Test fun adding_a_customer_from_the_sale_returns_to_it_with_them_selected() {
        val customers = mutableStateOf(emptyList<CustomerBalanceRow>())
        val savedToken = mutableIntStateOf(0)
        val lastCreated = mutableStateOf<String?>(null)
        composeRule.setContent {
            CuentivoApp(
                DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
                customers = customers.value,
                customerSavedToken = savedToken.intValue,
                lastCreatedCustomerId = lastCreated.value,
            )
        }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        composeRule.onNodeWithText("Agregar cliente").performClick()
        composeRule.onNodeWithText("Nuevo cliente").assertIsDisplayed()

        // The host's write landing, reproduced: the row appears and the tokens advance together.
        composeRule.runOnIdle {
            customers.value = listOf(rosaRow())
            lastCreated.value = "rosa"
            savedToken.intValue++
        }

        // Back in the sale, with the customer the form just created already selected.
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        composeRule.onNodeWithText("Rosa").assertIsDisplayed()
    }

    @Test fun large_system_text_keeps_every_navigation_action_accessible() {
        compactNavigationStaysAccessible(fontScale = 1.3f)
    }

    @Test fun reminder_setting_is_accessible_and_disabled_by_default() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        composeRule.onNodeWithContentDescription("Cuenta").performClick()

        // The reminder section sits below the subscription card, inside the sheet's own scroller.
        // The sheet's popup window is display-fragile when a previous test disposed it mid-open, so
        // the assertions pin semantics (reachable + off) rather than pixel visibility.
        composeRule.onNodeWithTag("account-sheet-scroll")
            .performScrollToNode(hasText("Recordatorios de cobro"))
        composeRule.onNodeWithText("Recordatorios de cobro").assertExists()
        composeRule.onNodeWithTag("reminder-enabled-switch").assertIsOff()
    }

    @Test fun reminder_deep_link_opens_customers_with_debt_filter() {
        composeRule.setContent {
            CuentivoApp(
                DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2),
                openOverdueCustomers = true,
                onOpenOverdueCustomersConsumed = {},
            )
        }

        composeRule.onNodeWithTag("main-destination-customers").assertIsSelected()
        // The notification tap lands on Clientes with the overdue chip already chosen.
        composeRule.onNodeWithText("En mora").assertIsSelected()
    }

    @Test fun very_large_system_text_keeps_every_navigation_action_accessible() {
        compactNavigationStaysAccessible(fontScale = 2.0f)
    }

    private fun compactNavigationStaysAccessible(fontScale: Float) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
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
        // Compact destinations are icon-only by design: their localized names reach TalkBack and
        // the tests through the content description, never through a visual label that could
        // truncate at these font scales.
        listOf("Inicio", "Clientes", "Productos", "Abonos").forEach { label ->
            composeRule.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    private fun rosaRow() = CustomerBalanceRow(
        customer = CustomerEntity(
            id = "rosa",
            businessId = "business-1",
            name = "Rosa",
            normalizedName = "rosa",
            phone = null,
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        ),
        balanceCents = 15_000,
    )
}
