package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.espresso.Espresso
import android.content.res.Configuration
import java.util.Locale
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.customer.DETAIL_LIST_TAG
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithText("Te debe").assertIsDisplayed()
        composeRule.onNodeWithText("Historial de Movimientos").assertIsDisplayed()
        // The top header steps aside, the bottom bar stays. Asserted against the account label the
        // header actually shows: it used to look for "Cuentivo", and once the wordmark left that
        // row the assertion would have passed for the wrong reason — the text was simply gone from
        // the whole app, so it proved nothing about this screen.
        composeRule.onAllNodesWithText("Tu cuenta").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Inicio").assertIsDisplayed()
    }

    @Test fun the_back_arrow_returns_to_the_directory() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithContentDescription("Volver").performClick()

        composeRule.onNodeWithText("Cartera").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun tapping_the_current_tab_from_the_detail_returns_to_the_directory() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()

        // Clientes is already the current tab, so the tap has nowhere to navigate: it goes back to
        // the root of that tab, which is the list. Before, it left the detail open and did nothing.
        composeRule.onNodeWithContentDescription("Clientes").performClick()

        composeRule.onNodeWithText("Cartera").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun the_history_reads_the_debt_not_the_till() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        // A purchase is debt for the customer, the opposite framing from the dashboard feed.
        composeRule.onNodeWithText("Venta").assertIsDisplayed()
        composeRule.onNodeWithText("Abono a cuenta").assertIsDisplayed()
        composeRule.onNodeWithText("Deuda").assertIsDisplayed()
    }

    @Test fun sharing_keeps_both_the_summary_and_the_csv() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Compartir").performClick()

        composeRule.onNodeWithText("Compartir resumen").assertIsDisplayed()
        composeRule.onNodeWithText("Compartir en Excel (CSV)").assertIsDisplayed()
    }

    @Test fun corrections_are_reachable_and_never_claim_data_is_deleted() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        // Corrections start collapsed behind their own "Ver" toggle; reaching them proves the
        // section exists, expands, and explains itself without claiming data is deleted.
        scrollDetailTo("Correcciones y ajustes")
        composeRule.onNodeWithText("Correcciones y ajustes").assertIsDisplayed()
        composeRule.onNodeWithText("Ver").performClick()
        composeRule
            .onNodeWithText(
                "Nada se borra: se registra un movimiento que compensa el error y queda en el historial."
            )
            .assertIsDisplayed()
        scrollDetailTo("Venta registrada")
        composeRule.onNodeWithText("Venta registrada").assertIsDisplayed()
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

    @Test fun the_central_action_continues_the_open_customers_thread() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun the_central_action_starts_clean_when_no_detail_is_open() {
        composeRule.setContent { Harness() }
        openCustomers()
        // Open and leave a customer, then start a sale the global way.
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onNodeWithContentDescription("Volver").performClick()

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
        composeRule.onAllNodesWithText("Juan Pérez").assertCountEquals(0)
    }

    @Test fun system_back_closes_the_detail() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithText("Cartera").assertIsDisplayed()
        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
    }

    @Test fun the_phone_action_hands_over_the_exact_number() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithContentDescription("Llamar a Juan Pérez").performClick()

        composeRule.runOnIdle { assertEquals(listOf("5512345678"), dialed) }
    }

    @Test fun each_share_option_calls_its_own_callback() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Compartir").performClick()
        composeRule.onNodeWithText("Compartir resumen").performClick()
        composeRule.onNodeWithText("Compartir").performClick()
        composeRule.onNodeWithText("Compartir en Excel (CSV)").performClick()

        composeRule.runOnIdle { assertEquals(listOf("text", "csv"), shared) }
    }

    @Test fun cancelling_only_fires_after_the_confirmation() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        expandCorrections()
        scrollDetailTo("Venta registrada")
        composeRule.onAllNodesWithText("Cancelar")[0].performClick()
        // The dialog is up but nothing has been written yet.
        composeRule.runOnIdle { assertEquals(emptyList<String>(), canceled) }
        composeRule.onNodeWithText("No").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), canceled) }

        scrollDetailTo("Venta registrada")
        composeRule.onAllNodesWithText("Cancelar")[0].performClick()
        composeRule.onNodeWithText("Sí, cancelar").performClick()

        composeRule.runOnIdle { assertEquals(listOf("sale:s1"), canceled) }
    }

    @Test fun reaching_abonos_from_the_bar_does_not_inherit_an_old_preselection() {
        composeRule.setContent { Harness() }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onNodeWithText("Registrar abono").performClick()

        // Leave and come back the manual way.
        composeRule.onNodeWithContentDescription("Inicio").performClick()
        composeRule.onNodeWithContentDescription("Abonos").performClick()

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

    @Test fun customer_credit_is_presented_as_credit_not_negative_debt() {
        composeRule.setContent { Harness(balanceCents = -20_000, statement = CREDIT_STATEMENT) }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onAllNodesWithText("Saldo a favor")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("$200.00", substring = true)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("-$200.00").assertCountEquals(0)
        composeRule.onAllNodesWithText("Debe hoy").assertCountEquals(0)
        composeRule.onAllNodesWithText("Quedó debiendo").assertCountEquals(0)
    }

    @Test fun english_credit_copy_does_not_use_spanish_labels() {
        composeRule.setContent { Harness(balanceCents = -20_000, statement = CREDIT_STATEMENT, localeTag = "en-US") }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        composeRule.onAllNodesWithText("Customer credit", substring = true)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("$200.00", substring = true)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("Saldo a favor").assertCountEquals(0)
    }

    @Test fun identical_payments_have_deterministic_labels_and_second_can_be_cancelled() {
        val payments = listOf(
            CorrectionCandidate.Payment("p1", 10_000, 20_000, 1, "CASH", null, 1, 2, false),
            CorrectionCandidate.Payment("p2", 10_000, 20_000, 2, "CASH", null, 2, 2, false),
        )
        composeRule.setContent { Harness(candidates = payments) }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        expandCorrections()
        scrollDetailTo("Abono 2 de 2")
        composeRule.onNodeWithText("Abono 2 de 2").assertIsDisplayed()
        composeRule.onAllNodesWithText("Abono 1 de 2").assertCountEquals(1)
        composeRule.onNodeWithText("Abono 2 de 2").performClick()
        composeRule.onNodeWithText("Sí, cancelar").performClick()
        composeRule.runOnIdle { assertEquals(listOf("payment:p2"), canceled) }
    }

    @Test fun payment_linked_to_cancelled_sale_cannot_be_cancelled() {
        composeRule.setContent {
            Harness(candidates = listOf(CorrectionCandidate.Payment("p1", 10_000, 20_000, 1, "CASH", null, 1, 1, true)))
        }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        expandCorrections()
        scrollDetailTo("Abono 1 de 1")
        composeRule.onNodeWithText("Abono 1 de 1").assertIsDisplayed()
        composeRule.onNodeWithText("no se puede cancelar", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Abono 1 de 1").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), canceled) }
        composeRule.onAllNodesWithText("Cancelar").assertCountEquals(0)
    }

    @Test fun payment_correction_separates_business_date_from_registration_time() {
        val businessDate = java.time.LocalDate.of(2026, 7, 21).toEpochDay()
        composeRule.setContent {
            Harness(candidates = listOf(CorrectionCandidate.Payment("p-date", 10_000, businessDate, 1L, "CASH", "Caja", 1, 1, false)))
        }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        expandCorrections()
        scrollDetailTo("Abono 1 de 1")
        composeRule.onAllNodesWithText("21 jul 2026", substring = true)[0].assertIsDisplayed()
        composeRule.onNodeWithText("Registrado", substring = true).assertIsDisplayed()
    }

    @Test fun partial_sale_correction_explains_all_amounts_and_keeps_payments() {
        composeRule.setContent {
            Harness(candidates = listOf(CorrectionCandidate.Sale("s2", "V-2", 20_000, 100_000, 30_000, 70_000)))
        }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        expandCorrections()
        scrollDetailTo("Venta registrada")
        composeRule.onAllNodesWithText("Cancelar")[0].performClick()
        composeRule.onAllNodesWithText("$1,000.00", substring = true)[1].assertIsDisplayed()
        composeRule.onAllNodesWithText("$300.00", substring = true)[1].assertIsDisplayed()
        composeRule.onAllNodesWithText("$700.00", substring = true)[1].assertIsDisplayed()
        composeRule.onNodeWithText("Los abonos registrados se conservan", substring = true).assertIsDisplayed()
    }

    /**
     * Correcting is the first thing offered on a sale, ahead of cancelling: fixing a wrong product
     * is what people actually came to do, and it no longer costs them a cancel plus a re-entry.
     */
    @Test fun a_sale_offers_editing_it_without_cancelling_it() {
        composeRule.setContent {
            Harness(candidates = listOf(CorrectionCandidate.Sale("s2", "V-2", 20_000, 100_000, 0, 100_000)))
        }
        openCustomers()
        composeRule.onNodeWithText("Juan Pérez").performClick()
        expandCorrections()
        scrollDetailTo("Venta registrada")

        composeRule.onNodeWithContentDescription("Editar la venta V-2").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("s2"), editedSales)
            assertEquals(emptyList<String>(), canceled)
        }
    }

    /** Lazy lists only compose what is near the viewport, so the list itself must be scrolled. */
    private fun scrollDetailTo(text: String) {
        composeRule.onNodeWithTag(DETAIL_LIST_TAG).performScrollToNode(hasText(text))
    }

    /** Corrections start collapsed behind their "Ver" toggle; correction-row assertions expand first. */
    private fun expandCorrections() {
        scrollDetailTo("Correcciones y ajustes")
        composeRule.onNodeWithText("Ver").performClick()
    }

    private fun openCustomers() {
        composeRule.onNodeWithTag("main-destination-customers").performClick()
    }

    /** What production would have been asked to do, recorded instead of performed. */
    private val dialed = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private val canceled = mutableListOf<String>()
    private val editedSales = mutableListOf<String>()

    @Composable
    private fun Harness(
        balanceCents: Long = 480_050,
        candidates: List<CorrectionCandidate> = CANCELABLES,
        statement: List<StatementEntry> = STATEMENT,
        localeTag: String = "es-MX",
    ) {
        var open by remember { mutableStateOf<CustomerBalanceRow?>(null) }
        val baseContext = LocalContext.current
        val configuration = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(localeTag))
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
        ) {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", balanceCents, 0, 0, 0, 1),
            customers = CUSTOMERS.map { if (it.customer.id == "juan") it.copy(balanceCents = balanceCents) else it },
            statementCustomer = open,
            statement = statement,
            cancelables = candidates,
            onShowStatement = { open = it },
            onDismissStatement = { open = null },
            onCallCustomer = { dialed += it },
            onShareStatementText = { shared += "text" },
            onShareStatementCsv = { shared += "csv" },
            onCancelSale = { canceled += "sale:" + it.id },
            onEditSale = { editedSales += it.id },
            onCancelPayment = { canceled += "payment:" + it.id },
        )
        }
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
        val CREDIT_STATEMENT = listOf(
            StatementEntry("2026-07-14", "CHARGE", "DEBIT", 500_000, "Pedido semanal"),
            StatementEntry("2026-07-21", "PAYMENT", "CREDIT", 520_000, null),
        )

        val CANCELABLES = listOf(
            CorrectionCandidate.Sale("s1", "Venta del 14 jul", 20_000, 500_000, 0, 500_000),
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
