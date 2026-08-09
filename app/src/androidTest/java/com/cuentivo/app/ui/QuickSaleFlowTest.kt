package com.cuentivo.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.R
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.QuickSaleCart
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The "someone new walked in and took a jabón" path: one form instead of three screens.
 */
class QuickSaleFlowTest {
    @get:Rule val composeRule = createComposeRule()

    private val created = mutableListOf<Pair<String, QuickSaleCart?>>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun a_customer_with_a_product_reports_both() {
        composeRule.setContent { Harness() }
        openNewCustomerForm()

        composeRule.onNodeWithTag("quick-sale-product").assertIsDisplayed()
        typeInto("customer-name-field", "Carmen")
        typeInto("quick-sale-product", "Jabón Zote")
        typeInto("quick-sale-price", "25.50")
        composeRule.onNodeWithTag("quick-sale-quantity").performTextReplacement("2")

        composeRule.onNodeWithText(context.getString(R.string.form_save)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, created.size)
            assertEquals("Carmen", created[0].first)
            // The row was never added by hand, so this also pins the behaviour that a filled row
            // still counts when Guardar is pressed straight away — the way this form always worked.
            val cart = created[0].second!!
            val line = cart.lines.single()
            assertEquals("Jabón Zote", line.productName)
            assertEquals(2_550, line.unitPriceCents)
            assertEquals(2, line.quantity)
            assertEquals(5_100, cart.totalCents)
            // The plazo is left untouched, so it must arrive as the default rather than as no date.
            assertEquals(15L, cart.dueInDays)
        }
    }

    /** Leaving the section alone must still save an ordinary customer, exactly as before. */
    @Test fun a_customer_without_a_product_reports_no_sale() {
        composeRule.setContent { Harness() }
        openNewCustomerForm()
        typeInto("customer-name-field", "Carmen")

        composeRule.onNodeWithText(context.getString(R.string.form_save)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, created.size)
            assertEquals(null, created[0].second)
        }
    }

    /**
     * The case that would quietly lose data: a product typed with no price. Saving there would
     * store a bare customer and drop what they were selling, so Guardar refuses instead.
     */
    @Test fun a_product_without_a_price_blocks_saving() {
        composeRule.setContent { Harness() }
        openNewCustomerForm()
        typeInto("customer-name-field", "Carmen")
        typeInto("quick-sale-product", "Jabón Zote")

        composeRule.onNodeWithText(context.getString(R.string.form_save)).assertIsNotEnabled()

        typeInto("quick-sale-price", "25")
        composeRule.onNodeWithText(context.getString(R.string.form_save)).assertIsEnabled()
    }

    /** Correcting a name later is not the moment to sell something. */
    @Test fun the_edit_form_does_not_offer_it() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithTag("main-destination-customers").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.customers_options_description, "Ana López"))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.customers_edit)).performClick()

        composeRule.onAllNodesWithTag("quick-sale-product").assertCountEquals(0)
    }

    private fun typeInto(tag: String, text: String) {
        composeRule.onNodeWithTag(tag).performTextInput(text)
    }

    private fun openNewCustomerForm() {
        composeRule.onNodeWithTag("main-destination-customers").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.customers_new_customer_description))
            .performClick()
    }

    @androidx.compose.runtime.Composable
    private fun Harness() {
        CuentivoApp(
            state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 1),
            customers = listOf(customerRow()),
            onCreateCustomer = { draft -> created += draft.name to draft.quickSale },
        )
    }

    private fun customerRow() = CustomerBalanceRow(
        customer = CustomerEntity(
            id = "ana",
            businessId = "business-1",
            name = "Ana López",
            normalizedName = "ana lopez",
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
        balanceCents = 0,
    )
}
