package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewSummary
import com.cuentivo.app.ai.ledger.OperationFinancialDiagnostic
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerImportSummaryCalculatorTest {

    @Test
    fun `summary counts 9 detected 1 ready 3 excluded as 1 ready and 5 pending review`() {
        val readyOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val nonReadyOps = (1..8).map { idx ->
            LedgerOperation(
                lineIndex = idx,
                proposedKind = null, // missing kind
                customerName = "Person $idx",
                amountCents = "1000",
            )
        }
        val operations = listOf(readyOp) + nonReadyOps // 9 ops in review.operations

        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 9,
            registerableOperationsCount = 1,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 8,
            salesCount = 1,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 3, // 3 excluded by model
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = operations,
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(9, result.detectedCount)
        assertEquals(1, result.readyCount)
        assertEquals(5, result.pendingReviewCount) // 9 - 1 - 3 = 5, NOT 8!
    }

    @Test
    fun `zero registrable operations disables confirm button`() {
        val nonReadyOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = null,
            customerName = "Pedro",
            amountCents = "1000",
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 0,
            visibleCustomersCount = 1,
            registerableOperationsCount = 0,
            executableOperationsCount = 0,
            unregisterableOperationsCount = 1,
            salesCount = 0,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(nonReadyOp),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(0, result.readyCount)
        assertFalse(result.isConfirmButtonEnabled)
    }

    @Test
    fun `canConfirm false with 1 ready operation disables confirm button without promising number`() {
        val readyOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val financialErrorOp = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = "Ana",
            amountCents = "50000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val diags = listOf(
            OperationFinancialDiagnostic(lineIndex = 1, reason = FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT)
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 2,
            visibleCustomersCount = 2,
            registerableOperationsCount = 2,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 0,
            salesCount = 1,
            paymentsCount = 1,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = diags,
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(readyOp, financialErrorOp),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertFalse(result.canConfirm)
        assertFalse(result.isConfirmButtonEnabled) // Disabled, promises no number!
    }

    @Test
    fun `discards without lineIndex are NOT counted in detectedCount`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val discards = listOf(
            LedgerDiscardDiagnostic(inputIndex = 1, lineIndex = null, reason = LedgerDiscardReason.MALFORMED)
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 1,
            registerableOperationsCount = 1,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 0,
            salesCount = 1,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 1,
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(op),
            discards = discards,
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(1, result.detectedCount) // Discards do NOT count in detectedCount
    }

    @Test
    fun `pageIncomplete requires notice without numerical guess`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 1,
            registerableOperationsCount = 1,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 0,
            salesCount = 1,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(op),
            discards = emptyList(),
            unreadTextRemaining = true,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertTrue(result.pageIncompleteNoticeRequired)
    }

    @Test
    fun `operation with financial error counts as pending review not ready`() {
        val financialErrorOp = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = "Ana",
            amountCents = "50000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val diags = listOf(
            OperationFinancialDiagnostic(lineIndex = 0, reason = FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT)
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 1,
            registerableOperationsCount = 1,
            executableOperationsCount = 0,
            unregisterableOperationsCount = 0,
            salesCount = 0,
            paymentsCount = 1,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = diags,
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(financialErrorOp),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(0, result.readyCount)
        assertEquals(1, result.pendingReviewCount)
    }

    @Test
    fun `deterministic objection blocks row from readyCount and adds to pendingReviewCount`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "44900", // Decimal shift suspect
            date = SafeDateCandidate(2026, 6, 15),
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 1,
            registerableOperationsCount = 1,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 0,
            salesCount = 1,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(op),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
        )

        val objections = listOf(
            LedgerDeterministicObjection(
                lineIndex = 0,
                type = LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT,
            )
        )

        val result = LedgerImportSummaryCalculator.calculate(review, objections)

        assertEquals(0, result.readyCount)
        assertEquals(1, result.pendingReviewCount)
        assertFalse(result.isConfirmButtonEnabled)
    }

    @Test
    fun `rows inheriting valid page date are ready and not doubtful for date`() {
        val opWithoutRowDate = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Carlos",
            amountCents = "1500",
            date = null, // Inherits page date
        )
        val summary = LedgerReviewSummary(
            newCustomersCount = 1,
            visibleCustomersCount = 1,
            registerableOperationsCount = 1,
            executableOperationsCount = 1,
            unregisterableOperationsCount = 0,
            salesCount = 1,
            paymentsCount = 0,
            priorDebtsCount = 0,
            excludedRowsCount = 0,
            financialDiagnostics = emptyList(),
        )
        val review = LedgerReview(
            customerGroups = emptyList(),
            operations = listOf(opWithoutRowDate),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            summary = summary,
            pageDateEpochDay = 20620L, // Valid page date
            todayEpochDay = 20620L,
        )

        val result = LedgerImportSummaryCalculator.calculate(review, emptyList())

        assertEquals(1, result.readyCount)
        assertEquals(0, result.pendingReviewCount)
        assertTrue(result.isConfirmButtonEnabled)

        // Field health for row inheriting page date must also be GREEN
        val health = LedgerFieldHealth.evaluate(
            op = opWithoutRowDate,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = 20620L,
            todayEpochDay = 20620L,
            objections = emptyList(),
        )
        assertEquals(FieldHealthColor.GREEN, health.dateHealth)
    }
}
