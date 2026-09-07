package com.cuentivo.app.ui

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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.ui.ai.LedgerReviewContent
import org.junit.Rule
import org.junit.Test

/**
 * The review screen is the last thing a person sees before a page of their paper notebook becomes
 * debt in the app, so what it refuses to do matters more than what it draws.
 */
class LedgerReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun sale(lineIndex: Int, name: String, cents: String, date: com.cuentivo.app.ai.ledger.SafeDateCandidate? = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1)) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        date = date,
    )

    @Test fun a_page_with_readable_rows_can_be_confirmed() {
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-op-row-0").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Test fun a_row_with_an_unreadable_amount_blocks_the_only_confirmation() {
        val review = LedgerReviewEngine.build(
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.SALE,
                    customerName = "Rosa",
                    amountCents = null,
                    date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
                    unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
                ),
            ),
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    @Test fun a_payment_without_prior_debt_is_refused_and_says_so() {
        val review = LedgerReviewEngine.build(
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.PAYMENT,
                    customerName = "Juan",
                    amountCents = "300000",
                    date = com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 9, 1),
                ),
                sale(1, "Rosa", "25000"),
            ),
        )

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { },
            )
        }

        composeRule.onNodeWithTag("ledger-financial-error-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    @Test fun discarding_the_page_reaches_the_caller() {
        var cancelled = false
        val review = LedgerReviewEngine.build(operations = listOf(sale(0, "Rosa", "25000")))

        composeRule.setContent {
            LedgerReviewContent(
                review = review,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { },
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { cancelled = true },
            )
        }

        composeRule.onNodeWithTag("ledger-review-cancel-button").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue("Discarding the page must reach the caller.", cancelled) }
    }

    @Test fun typing_partial_page_date_does_not_clear_or_reset_input() {
        val undatedSale = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "25000",
            date = null,
        )
        val validPageDate = java.time.LocalDate.of(2026, 6, 15).toEpochDay()
        val today = java.time.LocalDate.of(2026, 9, 1).toEpochDay()
        val initialReview = LedgerReviewEngine.build(
            operations = listOf(undatedSale),
            pageDateEpochDay = validPageDate,
            todayEpochDay = today,
        )

        composeRule.setContent {
            var currentReview by remember { mutableStateOf(initialReview) }
            LedgerReviewContent(
                review = currentReview,
                captureFile = null,
                onUpdateAmount = { _, _ -> },
                onUpdateCustomer = { _, _ -> },
                onUpdateKind = { _, _ -> },
                onUpdateDate = { _, _ -> },
                onUpdatePageDate = { newPageDate ->
                    currentReview = LedgerReviewEngine.build(
                        operations = currentReview.operations,
                        discards = currentReview.discards,
                        unreadTextRemaining = currentReview.unreadTextRemaining,
                        pageIncomplete = currentReview.pageIncomplete,
                        pageDateEpochDay = newPageDate,
                        todayEpochDay = currentReview.todayEpochDay,
                    )
                },
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { },
            )
        }

        val field = composeRule.onNodeWithTag("ledger-page-date-field")
        field.assertIsDisplayed()
        field.assertTextContains("2026-06-15")

        field.performTextReplacement("2026-06-")

        composeRule.runOnIdle { }

        field.assertTextContains("2026-06-")
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }
}
