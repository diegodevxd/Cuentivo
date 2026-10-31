package com.cuentivo.app.ai.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edits a user makes while reviewing a photographed page decide what gets written to the
 * ledger, so they are exercised here against production, never against a copy of the rule.
 */
class LedgerReviewEditsTest {

    private fun unreadableAmountRow(lineIndex: Int = 0) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = null,
        unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
    )

    @Test
    fun `filling an unreadable amount with a valid value makes the row registerable`() {
        val before = listOf(unreadableAmountRow())
        assertFalse(isRegisterableLedgerOperation(before[0]))

        val after = LedgerReviewEdits.applyAmount(before, lineIndex = 0, amountCents = "25000")

        assertEquals("25000", after[0].amountCents)
        assertTrue(after[0].unreadableFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `a correction that is still invalid keeps the mark and the row out`() {
        val after = LedgerReviewEdits.applyAmount(listOf(unreadableAmountRow()), 0, "250.75")

        assertEquals("250.75", after[0].amountCents)
        assertTrue(after[0].unreadableFields.contains(UnreadableLedgerField.AMOUNT))
        assertFalse(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `an amount of only whitespace never resolves the doubt`() {
        val after = LedgerReviewEdits.applyAmount(listOf(unreadableAmountRow()), 0, "   ")
        assertFalse(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `a customer name of only a byte order mark does not resolve the doubt`() {
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = null,
            amountCents = "1000",
            unreadableFields = listOf(UnreadableLedgerField.CUSTOMER),
        )
        val after = LedgerReviewEdits.applyCustomer(listOf(row), 0, "\uFEFF")

        assertTrue(after[0].unreadableFields.contains(UnreadableLedgerField.CUSTOMER))
        assertFalse(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `choosing a kind resolves it because the user decided`() {
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = null,
            customerName = "Juan",
            amountCents = "1000",
            uncertainFields = listOf(UncertainLedgerField.KIND),
        )
        val after = LedgerReviewEdits.applyKind(listOf(row), 0, ProposedLedgerKind.PAYMENT)

        assertEquals(ProposedLedgerKind.PAYMENT, after[0].proposedKind)
        assertTrue(after[0].uncertainFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `editing one row leaves the others untouched and does not mutate the original list`() {
        val original = listOf(unreadableAmountRow(0), unreadableAmountRow(1))
        val after = LedgerReviewEdits.applyAmount(original, lineIndex = 1, amountCents = "500")

        assertEquals(null, after[0].amountCents)
        assertEquals("500", after[1].amountCents)
        assertEquals(null, original[1].amountCents)
    }

    @Test
    fun `a date from the notebook resolves the doubt`() {
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "1000",
            unreadableFields = listOf(UnreadableLedgerField.DATE),
        )
        val after = LedgerReviewEdits.applyDate(
            listOf(row),
            lineIndex = 0,
            date = SafeDateCandidate(2026, 3, 14),
            todayEpochDay = java.time.LocalDate.of(2026, 9, 6).toEpochDay(),
        )

        assertEquals(SafeDateCandidate(2026, 3, 14), after[0].date)
        assertTrue(after[0].unreadableFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `a date in the future is not a notebook date and keeps the mark`() {
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "1000",
            unreadableFields = listOf(UnreadableLedgerField.DATE),
        )
        val after = LedgerReviewEdits.applyDate(
            listOf(row),
            lineIndex = 0,
            date = SafeDateCandidate(2027, 1, 1),
            todayEpochDay = java.time.LocalDate.of(2026, 9, 6).toEpochDay(),
        )

        assertTrue(after[0].unreadableFields.contains(UnreadableLedgerField.DATE))
        assertFalse(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `the thirty first of april is not a day and never resolves the doubt`() {
        val today = java.time.LocalDate.of(2026, 9, 6).toEpochDay()
        assertFalse(LedgerReviewEdits.isAcceptableDate(SafeDateCandidate(2026, 4, 31), today))
        assertTrue(LedgerReviewEdits.isAcceptableDate(SafeDateCandidate(2024, 2, 29), today))
        assertFalse(LedgerReviewEdits.isAcceptableDate(SafeDateCandidate(2026, 2, 29), today))
    }

    @Test
    fun `a date changed to tomorrow stops being confirmable`() {
        val today = java.time.LocalDate.of(2026, 9, 6)
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "1000",
            date = SafeDateCandidate(2026, 3, 14),
        )
        assertTrue(isRegisterableLedgerOperation(row))

        val after = LedgerReviewEdits.applyDate(
            listOf(row),
            lineIndex = 0,
            date = SafeDateCandidate(2026, 9, 7),
            todayEpochDay = today.toEpochDay(),
        )

        assertTrue(after[0].uncertainFields.contains(UncertainLedgerField.DATE))
        assertFalse(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `a row the notebook never dated stays confirmable`() {
        val today = java.time.LocalDate.of(2026, 9, 6).toEpochDay()
        val row = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Rosa",
            amountCents = "1000",
        )
        val after = LedgerReviewEdits.applyDate(listOf(row), 0, null, today)

        assertTrue(after[0].uncertainFields.isEmpty())
        assertTrue(isRegisterableLedgerOperation(after[0]))
    }

    @Test
    fun `removing a row drops only that row`() {
        val original = listOf(unreadableAmountRow(0), unreadableAmountRow(1), unreadableAmountRow(2))
        val after = LedgerReviewEdits.removeRow(original, lineIndex = 1)

        assertEquals(listOf(0, 2), after.map { it.lineIndex })
        assertEquals(3, original.size)
    }

    @Test
    fun `confirmObjection marks operation as confirmed`() {
        val original = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa",
                amountCents = "1000",
                isObjectionConfirmed = false,
            )
        )
        assertFalse(original[0].isObjectionConfirmed)

        val after = LedgerReviewEdits.confirmObjection(original, lineIndex = 0)

        assertTrue(after[0].isObjectionConfirmed)
        assertEquals(0, after[0].lineIndex)
    }
}
