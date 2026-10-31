package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.StatementCustomerRefresh
import com.cuentivo.app.R
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.transaction.PAYMENT_BALANCE_TAG
import com.cuentivo.app.ui.transaction.PAYMENT_LIST_TAG
import com.cuentivo.app.ui.transaction.PAYMENT_RESULT_TAG
import kotlinx.coroutines.launch
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task 7 behaviour for Abonos: who is offered first, what the preview promises, and the exact cents
 * handed to `onCreatePayment`.
 *
 * One test deliberately overpays. The ledger allows it and keeps the resulting credit, so this
 * suite pins that behaviour rather than quietly adding a rule the financial layer never had.
 */
class PaymentFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_customer_who_owes_the_most_is_offered_first() {
        composeRule.setContent { Harness() }
        openPayments()
        composeRule.onNodeWithTag("selector-cliente").performClick()

        val juan = composeRule.onNodeWithText("Juan Pérez").fetchSemanticsNode().positionInRoot.y
        val beto = composeRule.onNodeWithText("Beto Ruiz").fetchSemanticsNode().positionInRoot.y
        val ana = composeRule.onNodeWithText("Ana López").fetchSemanticsNode().positionInRoot.y

        // 480_050 > 90_000 > 0.
        assertTrue("Juan ($juan) must precede Beto ($beto)", juan < beto)
        assertTrue("Beto ($beto) must precede Ana ($ana)", beto < ana)
    }

    @Test fun the_preselection_from_the_detail_shows_that_customer() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithText("Juan Pérez").performClick()

        composeRule.onNodeWithText("Registrar abono").performClick()

        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onNodeWithText(currentBalanceLabel()).assertIsDisplayed()
        composeRule.onNodeWithTag(PAYMENT_BALANCE_TAG).assertTextEquals(Money.format(480_050))
    }

    @Test fun the_resulting_balance_follows_the_amount() {
        composeRule.setContent { Harness() }
        openPayments()
        choose("Juan Pérez")

        typeAmount("100")

        composeRule.onNodeWithText("Saldo resultante").assertIsDisplayed()
        composeRule.onNodeWithTag(PAYMENT_BALANCE_TAG).assertTextEquals(Money.format(480_050))
        composeRule.onNodeWithTag(PAYMENT_RESULT_TAG).assertTextEquals(Money.format(470_050))

        composeRule.onNodeWithText("Monto del abono").performTextClearance()
        typeAmount("200.50")

        // 480_050 − 20_050.
        composeRule.onNodeWithTag(PAYMENT_RESULT_TAG).assertTextEquals(Money.format(460_000))
    }

    /**
     * Regresión del hallazgo bloqueante de la v45, camino del abono: tras registrar $100 sobre una
     * deuda de $225, el detalle mostraba "Te debe $225" en vez de $125.
     *
     * El harness controla el resultado que devolvería el ledger después del abono; el refresco usa
     * `StatementCustomerRefresh.fromLedger` de producción y el detalle Compose real. MainActivity
     * conecta ese mismo método a `FinancialDao.customerBalanceCents`.
     */
    @Test fun el_detalle_tras_registrar_un_abono_muestra_el_saldo_nuevo() {
        composeRule.setContent { Harness(openDetailOnPayment = true, ledgerBalanceAfterPayment = 12_500) }
        openPayments()
        choose("Juan Pérez")
        typeAmount("100")

        save()

        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()
        composeRule.onAllNodesWithText(Money.format(12_500), substring = true)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText(Money.format(480_050), substring = true).assertCountEquals(0)
    }

    @Test fun the_callback_receives_the_exact_cents() {
        composeRule.setContent { Harness() }
        openPayments()
        choose("Juan Pérez")
        typeAmount("100.55")

        save()

        composeRule.runOnIdle { assertEquals(listOf("juan" to 10_055L), payments.toList()) }
    }

    @Test fun overpaying_is_still_allowed_and_previews_a_negative_balance() {
        composeRule.setContent { Harness() }
        openPayments()
        choose("Juan Pérez")

        // 1_000_000 cents against a 480_050 debt.
        typeAmount("10000")

        composeRule.onNodeWithTag(PAYMENT_RESULT_TAG).assertTextEquals(Money.format(-519_950))
        composeRule.onNodeWithText("Queda saldo a favor del cliente.").assertIsDisplayed()
        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").assertIsEnabled()

        save()

        composeRule.runOnIdle { assertEquals(listOf("juan" to 1_000_000L), payments.toList()) }
    }

    @Test fun an_empty_zero_or_invalid_amount_disables_saving() {
        composeRule.setContent { Harness() }
        openPayments()
        choose("Juan Pérez")

        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").assertIsNotEnabled()

        typeAmount("0")
        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").assertIsNotEnabled()

        // A third decimal is not a rounding question, it is money this system cannot hold.
        composeRule.onNodeWithText("Monto del abono").performTextClearance()
        typeAmount("1.234")
        composeRule.onNodeWithText("Monto no válido").assertIsDisplayed()
        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").assertIsNotEnabled()

        composeRule.onNodeWithText("Monto del abono").performTextClearance()
        typeAmount("50")
        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").assertIsEnabled()
    }

    @Test fun saving_clears_the_customer_and_the_amount() {
        composeRule.setContent { Harness() }
        openPayments()
        choose("Juan Pérez")
        typeAmount("100")

        save()

        composeRule.onNodeWithText("Toca para elegir al cliente").assertIsDisplayed()
        composeRule.onNodeWithText("Escribe el monto en pesos, por ejemplo 150.50").assertIsDisplayed()
        // With nobody chosen there is no balance panel to show.
        composeRule.onAllNodesWithText(currentBalanceLabel()).assertCountEquals(0)
    }

    /** The balance panel label, read from resources so the assertion stays aligned with the UI. */
    private fun currentBalanceLabel(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.payment_current_balance)

    private fun openPayments() {
        composeRule.onNodeWithContentDescription("Abonos").performClick()
    }

    private fun choose(name: String) {
        composeRule.onNodeWithTag("selector-cliente").performClick()
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    private fun typeAmount(value: String) {
        composeRule.onNodeWithText("Monto del abono").performTextInput(value)
        composeRule.waitForIdle()
    }

    private fun save() {
        scrollPaymentTo("Guardar abono")
        composeRule.onNodeWithText("Guardar abono").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollPaymentTo(text: String) {
        composeRule.onNodeWithTag(PAYMENT_LIST_TAG).performScrollToNode(hasText(text))
    }

    /** What production would have written, recorded instead of performed. */
    private val payments = mutableListOf<Pair<String, Long>>()

    @Composable
    private fun Harness(
        /**
         * Cuando es true, el `onCreatePayment` del harness reproduce lo que hace producción tras un
         * abono exitoso: abre el detalle de esa clienta. `statementCustomer` es del host, así que el
         * harness lo posee igual que `MainActivity`.
         */
        openDetailOnPayment: Boolean = false,
        /** Saldo controlado que representa la lectura posterior al abono. */
        ledgerBalanceAfterPayment: Long? = null,
    ) {
        var open by remember { mutableStateOf<CustomerBalanceRow?>(null) }
        val statementScope = rememberCoroutineScope()
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 570_050, 0, 0, 0, 2),
            customers = CUSTOMERS,
            statementCustomer = open,
            onShowStatement = { open = it },
            onDismissStatement = { open = null },
            onCreatePayment = { customer, cents ->
                payments += customer.customer.id to cents
                if (openDetailOnPayment) {
                    statementScope.launch {
                        open = StatementCustomerRefresh.fromLedger(
                            row = customer,
                            businessId = customer.customer.businessId,
                            customerBalanceCents = { _, _ -> ledgerBalanceAfterPayment ?: customer.balanceCents },
                        )
                    }
                }
            },
        )
    }

    private companion object {
        // Deliberately not in debt order, so the screen has to do the sorting.
        val CUSTOMERS = listOf(
            customer("ana", "Ana López", 0),
            customer("beto", "Beto Ruiz", 90_000),
            customer("juan", "Juan Pérez", 480_050),
        )

        fun customer(id: String, name: String, cents: Long) = CustomerBalanceRow(
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
