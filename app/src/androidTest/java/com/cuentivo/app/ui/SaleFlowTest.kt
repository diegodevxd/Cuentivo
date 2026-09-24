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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.Money
import com.cuentivo.app.domain.SaleDraft
import com.cuentivo.app.domain.StatementCustomerRefresh
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.transaction.NEW_SALE_LIST_TAG
import com.cuentivo.app.ui.transaction.SALE_TOTAL_TAG
import kotlinx.coroutines.launch
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Task 7 behaviour for Nueva venta: how the task opens and closes, what the cart does, and exactly
 * which integers reach `onCreateSale`.
 *
 * Money is asserted through `Money.format` rather than hard-coded strings, so a locale change would
 * surface as a formatting decision instead of a mysteriously failing test.
 */
class SaleFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_central_action_opens_the_sale_task() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        // "Nueva venta" is also the bottom bar's own label, so the title alone cannot identify the
        // task. The close affordance is what only this screen has: the old "CAPTURA GUIADA" eyebrow
        // was removed in v44 T5, and this assertion outlived it (found red on the baseline, T6).
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").assertIsDisplayed()
    }

    @Test fun the_back_arrow_and_system_back_both_close_the_task() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        composeRule.onNodeWithContentDescription("Cerrar nueva venta").performClick()
        composeRule.onAllNodesWithContentDescription("Cerrar nueva venta").assertCountEquals(0)

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        Espresso.pressBack()
        composeRule.onAllNodesWithContentDescription("Cerrar nueva venta").assertCountEquals(0)
        // Closing the task returns to the destination underneath, never to a blank screen.
        composeRule.onNodeWithContentDescription("Inicio").assertIsDisplayed()
    }

    @Test fun closing_the_detail_after_registering_a_sale_does_not_reopen_the_empty_form() {
        // El host real abre el detalle de la clienta cuando la venta se registra; el harness lo
        // reproduce en su `onCreateSale`, no arranca con el detalle abierto.
        composeRule.setContent { Harness(openDetailOnSale = true) }

        openSale()
        chooseCustomer("Juan Pérez")
        chooseProduct("Café molido 500 g")
        addToCart()
        register()

        // El registro exitoso lleva al detalle de esa clienta, donde se ve cómo quedó la deuda.
        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()

        // Cerrar el detalle (la flecha de volver) no puede caer en un formulario de venta vacío: la
        // venta ya se registró y la tarea terminó. Antes quedaba el formulario debajo y reaparecía.
        composeRule.onNodeWithContentDescription("Volver").performClick()

        composeRule.onAllNodesWithText("Detalle de Cliente").assertCountEquals(0)
        // "Cerrar nueva venta" vive sólo en el formulario de venta: si sigue en cero, no reapareció
        // el formulario vacío que antes quedaba debajo del detalle.
        composeRule.onAllNodesWithContentDescription("Cerrar nueva venta").assertCountEquals(0)
        composeRule.onNodeWithTag("main-destination-home").assertIsSelected()
        composeRule.onNodeWithTag("main-action-new-sale").assertIsNotSelected()
    }

    /**
     * Regresión del hallazgo bloqueante de la v45: tras registrar una venta, el detalle de la
     * clienta mostraba el saldo viejo ("Te debe $150" después de vender $75 sobre $150).
     *
     * El harness controla el resultado que devolvería el ledger después de la venta; el refresco usa
     * `StatementCustomerRefresh.fromLedger` de producción y el detalle Compose real. MainActivity
     * conecta ese mismo método a `FinancialDao.customerBalanceCents`.
     */
    @Test fun el_detalle_tras_registrar_una_venta_muestra_el_saldo_nuevo() {
        composeRule.setContent { Harness(openDetailOnSale = true, ledgerBalanceAfterSale = 22_500) }

        openSale()
        chooseCustomer("Juan Pérez")
        chooseProduct("Café molido 500 g")
        addToCart()
        register()

        // El detalle se abre con el saldo releído del libro mayor, no con el de la fila capturada.
        composeRule.onNodeWithText("Detalle de Cliente").assertIsDisplayed()
        composeRule.onAllNodesWithText(Money.format(22_500), substring = true)[0].assertIsDisplayed()
        composeRule.onAllNodesWithText(Money.format(480_050), substring = true).assertCountEquals(0)
    }

    @Test fun opening_from_a_customer_preselects_that_customer() {
        composeRule.setContent { Harness(openCustomer = CUSTOMERS[1]) }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        composeRule.onNodeWithText("Juan Pérez").assertIsDisplayed()
        composeRule.onAllNodesWithText("Toca para elegir al cliente").assertCountEquals(0)
    }

    @Test fun a_preselection_that_is_no_longer_in_the_list_falls_back_to_the_empty_selector() {
        // An archived customer is gone from `customers` while the row the host still holds is not:
        // resolving by id is what keeps the form from offering somebody who cannot be sold to.
        composeRule.setContent { Harness(openCustomer = ARCHIVED) }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()

        composeRule.onNodeWithText("Toca para elegir al cliente").assertIsDisplayed()
        composeRule.onAllNodesWithText("Rosa Archivada").assertCountEquals(0)
    }

    @Test fun adding_a_product_with_a_quantity_produces_the_correct_line() {
        composeRule.setContent { Harness() }
        openSale()
        chooseProduct("Café molido 500 g")
        setQuantity("3")
        addToCart()

        assertSaleTextDisplayed("3 × Café molido 500 g")
        assertSaleTextDisplayed("${Money.format(12_500)} c/u")
        // 3 × $125.00. The line subtotal is not matched by text because the total prints the same
        // figure; the total is asked for by name instead.
        assertTotalIs(37_500)
    }

    @Test fun typing_a_large_quantity_directly_updates_the_cart_line() {
        composeRule.setContent { Harness() }
        openSale()
        chooseProduct("Café molido 500 g")
        setQuantity("50")
        addToCart()

        assertSaleTextDisplayed("50 × Café molido 500 g")
        assertSaleTextDisplayed("${Money.format(12_500)} c/u")
        // 50 × $125.00 = $6,250.00
        assertTotalIs(625_000)
    }

    @Test fun adding_several_products_keeps_every_line_in_the_cart() {
        composeRule.setContent { Harness() }
        openSale()
        chooseProduct("Café molido 500 g")
        addToCart()
        chooseProduct("Aceite vegetal 1 L")
        setQuantity("2")
        addToCart()

        assertSaleTextDisplayed("1 × Café molido 500 g")
        assertSaleTextDisplayed("2 × Aceite vegetal 1 L")
        assertSaleTextDisplayed(Money.format(9_600))
        // 12_500 + 9_600.
        assertTotalIs(22_100)
    }

    @Test fun removing_a_line_updates_the_total() {
        composeRule.setContent { Harness() }
        openSale()
        chooseProduct("Café molido 500 g")
        addToCart()
        chooseProduct("Aceite vegetal 1 L")
        setQuantity("2")
        addToCart()
        assertTotalIs(22_100)

        removeLine("Aceite vegetal 1 L")

        composeRule.onAllNodesWithText("2 × Aceite vegetal 1 L").assertCountEquals(0)
        assertTotalIs(12_500)
    }

    @Test fun a_discount_updates_the_total_without_mutating_state_during_composition() {
        composeRule.setContent { Harness() }
        openSale()
        chooseProduct("Café molido 500 g")
        addToCart()

        typeDiscount("25")

        // The draft the screen shows is derived from the cart plus this field. If the discount were
        // written back into remembered state while composing, the total and the subtotal caption
        // could not be guaranteed to settle on the same frame.
        assertTotalIs(10_000)
        assertSaleTextDisplayed("Antes del descuento: ${Money.format(12_500)}")
    }

    @Test fun every_due_option_produces_its_own_epoch_day() {
        composeRule.setContent { Harness() }
        // Registering resets the form, so all four options fit in one composition.
        listOf("Sin fecha", "7 días", "15 días", "30 días").forEach { chip ->
            openSale()
            chooseCustomer("Juan Pérez")
            chooseProduct("Café molido 500 g")
            addToCart()
            scrollSaleTo(chip)
            composeRule.onNodeWithText(chip).performClick()
            register()
        }

        val today = LocalDate.now().toEpochDay()
        composeRule.runOnIdle {
            assertEquals(
                listOf(null, today + 7, today + 15, today + 30),
                createdSales.map { it.second.dueDateEpochDay },
            )
        }
    }

    @Test fun the_callback_receives_the_exact_customer_and_draft_in_cents() {
        composeRule.setContent { Harness() }
        openSale()
        chooseCustomer("Juan Pérez")
        chooseProduct("Café molido 500 g")
        setQuantity("2")
        addToCart()
        typeDiscount("50.50")
        register()

        composeRule.runOnIdle {
            assertEquals(1, createdSales.size)
            val (customer, draft) = createdSales.single()
            assertEquals("juan", customer.customer.id)
            assertEquals(1, draft.lines.size)
            val line = draft.lines.single()
            assertEquals("p1", line.productId)
            assertEquals("Café molido 500 g", line.productName)
            assertEquals(2, line.quantity)
            assertEquals(12_500L, line.unitPriceCents)
            assertEquals(25_000L, draft.subtotalCents)
            assertEquals(5_050L, draft.discountCents)
            assertEquals(19_950L, draft.totalCents)
            assertEquals(LocalDate.now().plusDays(15).toEpochDay(), draft.dueDateEpochDay)
        }
    }

    @Test fun registering_stays_disabled_without_a_customer_or_with_an_invalid_cart() {
        composeRule.setContent { Harness() }
        openSale()

        // No customer and nothing in the cart.
        scrollSaleTo("Registrar venta")
        composeRule.onNodeWithText("Registrar venta").assertIsNotEnabled()

        // A cart alone is not enough, and the screen says which half is missing.
        chooseProduct("Café molido 500 g")
        addToCart()
        assertSaleTextDisplayed("Falta elegir al cliente")
        scrollSaleTo("Registrar venta")
        composeRule.onNodeWithText("Registrar venta").assertIsNotEnabled()

        // A customer alone is not enough either.
        removeLine("Café molido 500 g")
        chooseCustomer("Juan Pérez")
        scrollSaleTo("Registrar venta")
        composeRule.onNodeWithText("Registrar venta").assertIsNotEnabled()

        chooseProduct("Café molido 500 g")
        addToCart()
        scrollSaleTo("Registrar venta")
        composeRule.onNodeWithText("Registrar venta").assertIsEnabled()
    }

    private fun openSale() {
        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
    }

    private fun chooseCustomer(name: String) {
        openSelector("selector-cliente")
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    private fun chooseProduct(name: String) {
        openSelector("selector-producto")
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    /** The field already holds "1", and typing would append to it rather than replace it. */
    private fun setQuantity(value: String) {
        scrollSaleTo("Cantidad")
        composeRule.onNodeWithContentDescription("Cantidad").performTextClearance()
        composeRule.onNodeWithContentDescription("Cantidad").performTextInput(value)
    }

    private fun addToCart() {
        scrollSaleTo("Agregar al carrito")
        composeRule.onNodeWithText("Agregar al carrito").performClick()
        composeRule.waitForIdle()
    }

    private fun removeLine(productName: String) {
        composeRule
            .onNodeWithTag(NEW_SALE_LIST_TAG)
            .performScrollToNode(hasContentDescription("Quitar $productName"))
        composeRule.onNodeWithContentDescription("Quitar $productName").performClick()
        composeRule.waitForIdle()
    }

    private fun register() {
        scrollSaleTo("Registrar venta")
        composeRule.onNodeWithText("Registrar venta").performClick()
        composeRule.waitForIdle()
    }

    private fun typeDiscount(pesos: String) {
        scrollSaleTo("Descuento de la venta (opcional)")
        composeRule.onNodeWithText("Descuento de la venta (opcional)").performTextInput(pesos)
        composeRule.waitForIdle()
    }

    /** The one figure that is the sale's total, asked for by name rather than by its digits. */
    private fun assertTotalIs(cents: Long) {
        composeRule.onNodeWithTag(NEW_SALE_LIST_TAG).performScrollToNode(hasTestTag(SALE_TOTAL_TAG))
        composeRule.onNodeWithTag(SALE_TOTAL_TAG).assertTextEquals(Money.format(cents))
    }

    private fun assertSaleTextDisplayed(text: String) {
        scrollSaleTo(text)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun openSelector(tag: String) {
        composeRule.onNodeWithTag(NEW_SALE_LIST_TAG).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    /**
     * A lazy list has not composed what is below the fold, and at 320 dp most of this task is below
     * the fold, so every reach into it scrolls the list first.
     */
    private fun scrollSaleTo(text: String) {
        composeRule.onNodeWithTag(NEW_SALE_LIST_TAG).performScrollToNode(hasText(text))
    }

    /** What production would have written, recorded instead of performed. */
    private val createdSales = mutableListOf<Pair<CustomerBalanceRow, SaleDraft>>()

    @Composable
    private fun Harness(
        openCustomer: CustomerBalanceRow? = null,
        /**
         * When true the harness's `onCreateSale` reproduces what production does after a successful
         * registration: it opens that customer's detail. `statementCustomer` is host-owned, so the
         * harness owns it exactly as MainActivity does.
         */
        openDetailOnSale: Boolean = false,
        /**
         * Saldo controlado que representa la lectura posterior a la venta.
         */
        ledgerBalanceAfterSale: Long? = null,
    ) {
        var open by remember { mutableStateOf(openCustomer) }
        val statementScope = rememberCoroutineScope()
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 480_050, 0, 0, 0, 1),
            customers = CUSTOMERS,
            products = PRODUCTS,
            statementCustomer = open,
            onShowStatement = { open = it },
            onDismissStatement = { open = null },
            onCreateSale = { customer, draft ->
                createdSales += customer to draft
                if (openDetailOnSale) {
                    statementScope.launch {
                        open = StatementCustomerRefresh.fromLedger(
                            row = customer,
                            businessId = customer.customer.businessId,
                            customerBalanceCents = { _, _ -> ledgerBalanceAfterSale ?: customer.balanceCents },
                        )
                    }
                }
            },
        )
    }

    private companion object {
        val CUSTOMERS = listOf(
            customer("ana", "Ana López", 0),
            customer("juan", "Juan Pérez", 480_050),
        )

        /** Deliberately absent from [CUSTOMERS]: the archived-or-deleted case. */
        val ARCHIVED = customer("rosa", "Rosa Archivada", 12_000)

        val PRODUCTS = listOf(
            product("p1", "Café molido 500 g", 12_500),
            product("p2", "Aceite vegetal 1 L", 4_800),
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

        fun product(id: String, name: String, cents: Long) = ProductEntity(
            id = id,
            businessId = "business-1",
            name = name,
            description = null,
            sku = null,
            category = null,
            salePriceCents = cents,
            costPriceCents = null,
            stock = null,
            imageUri = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        )
    }
}
