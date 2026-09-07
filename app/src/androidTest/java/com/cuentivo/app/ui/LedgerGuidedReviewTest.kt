package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ui.ai.LedgerReviewContent
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * The way out of a page whose every row came back doubtful.
 *
 * Once the model was told what a real notebook looks like it started marking almost everything as
 * uncertain, which is correct -- a value it inherited from a quote mark is not a value it read. But
 * an uncertain row is not registerable until a person confirms it, so the screen sat there with the
 * button dead and "register 0 operations" written on it, and the person had no way of knowing that
 * the fix was to tap every row. Diego's words: "lo lee muy bien... solo que no se activa".
 *
 * The guarantee is not relaxed here: each row is still confirmed one at a time by a human. What is
 * tested is that there is a visible way through, and that walking it actually ends with a page that
 * can be registered.
 */
class LedgerGuidedReviewTest {
    @get:Rule val composeRule = createComposeRule()

    private val today = LocalDate.now().toEpochDay()

    private fun doubtfulSale(lineIndex: Int, name: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = "25000",
        date = SafeDateCandidate(2026, 9, 1),
        // What an inherited quote mark produces: read, but not vouched for.
        uncertainFields = listOf(UncertainLedgerField.CUSTOMER),
    )

    /**
     * Hosts the screen over the real edit functions, so the walk is exercised against the code that
     * production runs rather than against a rule rewritten inside the test.
     */
    @Composable
    private fun host(initial: List<LedgerOperation>) {
        var operations by remember { mutableStateOf(initial) }
        val review = LedgerReviewEngine.build(operations = operations, todayEpochDay = today)
        LedgerReviewContent(
            review = review,
            captureFile = null,
            onUpdateAmount = { line, amount -> operations = LedgerReviewEdits.applyAmount(operations, line, amount) },
            onUpdateCustomer = { line, name -> operations = LedgerReviewEdits.applyCustomer(operations, line, name) },
            onUpdateKind = { line, kind -> operations = LedgerReviewEdits.applyKind(operations, line, kind) },
            onUpdateDate = { line, date -> operations = LedgerReviewEdits.applyDate(operations, line, date, today) },
            onUpdatePageDate = { },
            onRemoveRow = { line -> operations = LedgerReviewEdits.removeRow(operations, line) },
            onConfirm = { },
            onCancel = { },
        )
    }

    @Test fun a_page_where_every_row_is_doubtful_offers_a_way_through_instead_of_a_dead_button() {
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
        composeRule.onNodeWithTag("ledger-start-review-button").assertIsDisplayed()
    }

    @Test fun walking_the_doubtful_rows_ends_with_a_page_that_can_be_registered() {
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        // First row open.
        composeRule.onNodeWithTag("ledger-edit-save-button").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        // Straight on to the second, without going back to hunt for it.
        composeRule.onNodeWithTag("ledger-edit-save-button").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_half_reviewed_page_registers_only_what_was_confirmed_and_says_the_rest_is_pending() {
        // A deliberate decision, written down because the opposite is defensible too. Leaving the
        // walk halfway does NOT block the page: the rows already confirmed can be registered, and
        // the doubtful ones are simply not imported. Blocking instead would mean one row nobody can
        // read makes the whole notebook page unregisterable.
        //
        // What must never happen is that this is quiet. So the button counts only what it will
        // actually write, and the banner keeps saying how many rows were left behind.
        composeRule.setContent { host(listOf(doubtfulSale(0, "Rosa"), doubtfulSale(1, "Mayra"))) }

        composeRule.onNodeWithTag("ledger-start-review-button").performClick()
        composeRule.onNodeWithTag("ledger-edit-save-button").performClick()
        composeRule.onNodeWithTag("ledger-edit-cancel-button").performClick()

        composeRule.onNodeWithTag("ledger-review-confirm-button")
            .assertIsEnabled()
            .assertTextContains("Crear 1 clientes y registrar 1 movimientos")
        // The row left behind is still announced, not swallowed.
        composeRule.onNodeWithTag("ledger-financial-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-start-review-button").assertIsDisplayed()
    }
}
