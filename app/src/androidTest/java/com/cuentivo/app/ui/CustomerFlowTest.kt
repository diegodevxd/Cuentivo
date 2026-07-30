package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test

/**
 * Task 5 behaviour: the directory, the detail task, and the two actions that launch a flow with a
 * customer already chosen.
 *
 * `statementCustomer` is owned by the host in production, so the harness below reproduces that
 * ownership rather than reaching inside `CuentivoApp`.
 */
class CustomerFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun customers_shows_the_reference_directory_header() {
        composeRule.setContent { Harness() }
        openCustomers()

        composeRule.onNodeWithText("Cartera").assertIsDisplayed()
        composeRule.onNodeWithText("Ana López").assertIsDisplayed()
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Nuevo cliente").assertIsDisplayed()
    }

    @Test fun search_narrows_the_directory_by_name() {
        composeRule.setContent { Harness() }
        openCustomers()

        composeRule.onNodeWithText("Buscar cliente...").performTextInput("Juan")

        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ana López").assertCountEquals(0)
    }

    @Test fun chips_filter_and_keep_a_48_dp_touch_target() {
        composeRule.setContent { Harness() }
        openCustomers()

        listOf("Todos", "Con saldo", "En mora").forEach { label ->
            composeRule.onNodeWithText(label)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }

        // Ana is settled, so "Con saldo" must drop her while keeping the debtors.
        composeRule.onNodeWithText("Con saldo").performClick()
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ana López").assertCountEquals(0)
    }

    @Test fun opening_a_customer_shows_the_detail_task_over_the_shell() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()
        composeRule.onNodeWithText("BALANCE TOTAL").assertIsDisplayed()
        composeRule.onNodeWithText("Historial de Movimientos").assertIsDisplayed()
        // The brand header steps aside, the bottom bar stays.
        composeRule.onAllNodesWithText("Cuentivo").assertCountEquals(0)
        composeRule.onNodeWithText("Inicio").assertIsDisplayed()
    }

    @Test fun the_back_arrow_returns_to_the_directory() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithContentDescription("Volver").performClick()

        composeRule.onNodeWithText("Cartera").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun the_history_reads_the_debt_not_the_till() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        // A purchase is debt for the customer, the opposite framing from the dashboard feed.
        composeRule.onNodeWithText("Compra").assertIsDisplayed()
        composeRule.onNodeWithText("Abono a cuenta").assertIsDisplayed()
        composeRule.onNodeWithText("Deuda").assertIsDisplayed()
    }

    @Test fun sharing_keeps_both_the_summary_and_the_csv() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Compartir").performClick()

        composeRule.onNodeWithText("Compartir resumen").assertIsDisplayed()
        composeRule.onNodeWithText("Compartir CSV").assertIsDisplayed()
    }

    @Test fun corrections_are_reachable_and_never_claim_data_is_deleted() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Corregir").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Nada se borra: se registra un movimiento que compensa el error y queda en el historial."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Venta del 14 jul").performScrollTo().assertIsDisplayed()
    }

    @Test fun registrar_abono_from_the_detail_preselects_that_customer() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Registrar abono").performClick()

        // Payments opened with the customer already chosen by the selector.
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun nueva_venta_from_the_detail_preselects_that_customer() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithTag("detail-new-sale").performScrollTo().performClick()

        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
    }

    @Test fun reaching_abonos_from_the_bar_does_not_inherit_an_old_preselection() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onNodeWithText("Registrar abono").performClick()

        // Leave and come back the manual way.
        composeRule.onNodeWithText("Inicio").performClick()
        composeRule.onNodeWithText("Abonos").performClick()

        composeRule.onAllNodesWithText("Juan Pérez").assertCountEquals(0)
    }

    @Test fun creating_and_editing_a_customer_remain_available() {
        composeRule.setContent { Harness() }
        openCustomers()

        composeRule.onNodeWithContentDescription("Nuevo cliente").performClick()
        composeRule.onNodeWithText("Nombre del cliente").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").performClick()

        composeRule.onNodeWithContentDescription("Opciones de Juan Pérez").performClick()
        composeRule.onNodeWithText("Editar").assertIsDisplayed()
        composeRule.onNodeWithText("Eliminar").assertIsDisplayed()
    }

    private fun openCustomers() {
        composeRule.onNodeWithText("Clientes").performClick()
    }

    @Composable
    private fun Harness() {
        var open by remember { mutableStateOf<CustomerBalanceRow?>(null) }
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 480_050, 0, 0, 0, 1),
            customers = CUSTOMERS,
            statementCustomer = open,
            statement = STATEMENT,
            cancelables = CANCELABLES,
            onShowStatement = { open = it },
            onDismissStatement = { open = null },
        )
    }

    private companion object {
        val CUSTOMERS = listOf(
            row("ana", "Ana López", 0),
            row("juan", "Juan Pérez", 480_050),
        )

        val STATEMENT = listOf(
            StatementEntry("2026-07-14", "CHARGE", "DEBIT", 500_000, "Pedido semanal"),
            StatementEntry("2026-07-21", "PAYMENT", "CREDIT", 19_950, null),
        )

        val CANCELABLES = listOf(
            CancelableItem(
                "s1",
                CancelableItem.Kind.SALE,
                "Venta del 14 jul",
                "Pedido semanal",
                500_000,
            ),
        )

        fun row(id: String, name: String, cents: Long) = CustomerBalanceRow(
            customer = CustomerEntity(
                id = id,
                businessId = "business-1",
                name = name,
                normalizedName = name.lowercase(),
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
            balanceCents = cents,
        )
    }
}
