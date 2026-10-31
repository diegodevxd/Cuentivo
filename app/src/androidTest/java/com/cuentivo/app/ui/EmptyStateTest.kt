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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Task 8: every empty state says the same kind of thing in the same shape — one title, one short
 * explanation, and an action only where a real callback exists.
 *
 * The last rule is the one that matters. An empty state offering a button that does nothing is
 * worse than one offering nothing at all, because it costs a tap to find that out.
 */
class EmptyStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_dashboard_sections_explain_themselves_without_offering_a_dead_action() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithText("Sin cobros urgentes").assertIsDisplayed()
        composeRule.onNodeWithText("Nadie tiene un pago vencido hoy.").assertIsDisplayed()
        composeRule.onNodeWithText("Aún no hay movimientos").assertIsDisplayed()
        composeRule.onNodeWithText("Aquí aparecerán tus ventas y abonos.").assertIsDisplayed()
    }

    @Test fun the_no_business_state_is_one_card_with_one_action() {
        composeRule.setContent { Harness(state = DashboardUiState.NoBusiness) }

        composeRule.onNodeWithText("Bienvenido a Cuentivo").assertIsDisplayed()
        composeRule.onNodeWithText("Crea tu primer negocio para comenzar").assertIsDisplayed()
        composeRule.onAllNodesWithText("Crear mi negocio").assertCountEquals(1)

        composeRule.onNodeWithText("Crear mi negocio").performClick()

        composeRule.runOnIdle { assertEquals(1, businessesCreated) }
    }

    @Test fun the_empty_customer_directory_offers_the_action_that_fills_it() {
        composeRule.setContent { Harness(customers = emptyList()) }
        composeRule.onNodeWithContentDescription("Clientes").performClick()

        composeRule.onNodeWithText("Aún no tienes clientes").assertIsDisplayed()
        composeRule.onNodeWithText("Agrega al primero para empezar a venderle fiado.").assertIsDisplayed()

        composeRule.onNodeWithText("Agregar cliente").performClick()

        // The one action opens the very form the floating button opens.
        composeRule.onNodeWithText("Nombre del cliente").assertIsDisplayed()
    }

    @Test fun a_search_with_no_match_is_not_an_invitation_to_create() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithContentDescription("Clientes").performClick()

        composeRule.onNodeWithText("Buscar cliente...").performTextInput("zzzz")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ningún cliente coincide").assertIsDisplayed()
        // Nothing is missing here: the fix is to change the search, not to create somebody.
        composeRule.onAllNodesWithText("Agregar cliente").assertCountEquals(0)
    }

    @Test fun the_empty_catalog_keeps_its_wording_and_gains_one_action() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithContentDescription("Productos").performClick()

        composeRule.onNodeWithText("Aún no tienes productos").assertIsDisplayed()

        composeRule.onNodeWithText("Agregar producto").performClick()

        composeRule.onNodeWithText("Nombre del producto").assertIsDisplayed()
    }

    @Test fun a_customer_with_no_history_says_so_plainly() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithText("Ana López").performClick()

        composeRule.onNodeWithText("Sin movimientos").assertIsDisplayed()
        composeRule
            .onNodeWithText("Las ventas y los abonos de este cliente aparecerán aquí.")
            .assertIsDisplayed()
    }

    private var businessesCreated = 0

    @Composable
    private fun Harness(
        state: DashboardUiState = EMPTY_SECTIONS,
        customers: List<CustomerBalanceRow> = CUSTOMERS,
    ) {
        var open by remember { mutableStateOf<CustomerBalanceRow?>(null) }
        CuentivoApp(
            state = state,
            customers = customers,
            statementCustomer = open,
            onShowStatement = { open = it },
            onDismissStatement = { open = null },
            onCreateBusiness = { businessesCreated++ },
        )
    }

    private companion object {
        val EMPTY_SECTIONS = DashboardUiState.Content(
            businessId = "business-1",
            businessName = "Mi negocio",
            receivableCents = 0,
            soldCents = 0,
            collectedCents = 0,
            overdueCents = 0,
            customersWithDebt = 0,
        )

        val CUSTOMERS = listOf(
            CustomerBalanceRow(
                customer = CustomerEntity(
                    id = "ana",
                    businessId = "business-1",
                    name = "Ana López",
                    normalizedName = "ana lopez",
                    phone = "5512345678",
                    email = null,
                    address = null,
                    notes = null,
                    creditLimitCents = null,
                    isActive = true,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                    deletedAtEpochMillis = null,
                ),
                balanceCents = 0,
            ),
        )
    }
}
