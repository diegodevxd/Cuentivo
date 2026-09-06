package com.cuentivo.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    private fun sale(lineIndex: Int, name: String, cents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
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
                onRemoveRow = { },
                onConfirm = { },
                onCancel = { cancelled = true },
            )
        }

        composeRule.onNodeWithTag("ledger-review-cancel-button").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue("Discarding the page must reach the caller.", cancelled) }
    }
}
