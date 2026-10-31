package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.OperationFinancialDiagnostic
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerFieldHealthTest {

    @Test
    fun `evaluates green for completely valid operation`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan Perez",
            amountCents = "15000",
            date = SafeDateCandidate(2026, 6, 15),
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        assertEquals(FieldHealthColor.GREEN, health.customerHealth)
        assertEquals(FieldHealthColor.GREEN, health.amountHealth)
        assertEquals(FieldHealthColor.GREEN, health.kindHealth)
        assertEquals(FieldHealthColor.GREEN, health.dateHealth)
        assertTrue(health.isAllGreen)
    }

    @Test
    fun `evaluates yellow for uncertain fields and red for unreadable fields`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan Perez",
            amountCents = "15000",
            date = SafeDateCandidate(2026, 6, 15),
            uncertainFields = listOf(UncertainLedgerField.AMOUNT),
            unreadableFields = listOf(UnreadableLedgerField.CUSTOMER),
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        assertEquals(FieldHealthColor.RED, health.customerHealth)
        assertEquals(FieldHealthColor.YELLOW, health.amountHealth)
        assertFalse(health.isAllGreen)
    }

    @Test
    fun `low confidence with empty uncertain and unreadable lists STILL evaluates green`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Maria Gomez",
            amountCents = "5000",
            date = SafeDateCandidate(2026, 6, 15),
            uncertainFields = emptyList(),
            unreadableFields = emptyList(),
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        // Empty lists are authoritative; confidence score does not override
        assertEquals(FieldHealthColor.GREEN, health.customerHealth)
        assertEquals(FieldHealthColor.GREEN, health.amountHealth)
        assertEquals(FieldHealthColor.GREEN, health.kindHealth)
        assertEquals(FieldHealthColor.GREEN, health.dateHealth)
    }

    @Test
    fun `row without proposedKind and empty lists evaluates kind as RED`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = null, // Missing kind
            customerName = "Maria Gomez",
            amountCents = "5000",
            date = SafeDateCandidate(2026, 6, 15),
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        assertEquals(FieldHealthColor.RED, health.kindHealth)
        assertFalse(health.isAllGreen)
    }

    @Test
    fun `financial diagnostic maps directly to Amount health RED`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.PAYMENT,
            customerName = "Carlos Ruiz",
            amountCents = "90000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val diags = listOf(
            OperationFinancialDiagnostic(lineIndex = 0, reason = FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT)
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = diags,
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = emptyList(),
        )

        assertEquals(FieldHealthColor.RED, health.amountHealth)
        assertFalse(health.isAllGreen)
    }

    @Test
    fun `customer confirmation objection demotes customerHealth to YELLOW and sets needsAttention`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Charly",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val objections = listOf(
            LedgerDeterministicObjection(
                lineIndex = 0,
                type = LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
            )
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = objections,
        )

        assertEquals(FieldHealthColor.YELLOW, health.customerHealth)
        assertFalse(health.isAllGreen)
        assertTrue(health.needsAttention)
    }

    @Test
    fun `price objection demotes amountHealth to YELLOW and sets needsAttention`() {
        val op = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Maria",
            amountCents = "44900",
            date = SafeDateCandidate(2026, 6, 15),
        )
        val objections = listOf(
            LedgerDeterministicObjection(
                lineIndex = 0,
                type = LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT,
            )
        )

        val health = LedgerFieldHealth.evaluate(
            op = op,
            financialDiagnostics = emptyList(),
            pageDateEpochDay = null,
            todayEpochDay = null,
            objections = objections,
        )

        assertEquals(FieldHealthColor.YELLOW, health.amountHealth)
        assertFalse(health.isAllGreen)
        assertTrue(health.needsAttention)
    }
}
