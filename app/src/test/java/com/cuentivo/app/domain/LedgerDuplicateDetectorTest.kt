package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerDuplicateDetectorTest {

    private val todayEpoch = 20612L // 2026-06-07

    @Test
    fun `same customer amount date and kind registered in Room flags as possible duplicate`() {
        val op = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
            description = "Protector Tapete",
        )
        val registered = listOf(
            RegisteredOperationProjection(
                id = "sale-100",
                customerId = "cust-1",
                customerName = "Rosa",
                amountCents = 25000L,
                effectiveDateEpochDay = todayEpoch,
                kind = ProposedLedgerKind.SALE,
                description = "Protector Tapete",
            )
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )

        assertEquals(1, matches.size)
        val match = matches[0]
        assertNotNull(match)
        assertEquals("sale-100", match?.matchedRegisteredId)
        assertEquals("Rosa", match?.customerName)
        assertEquals(25000L, match?.amountCents)
        assertEquals("Protector Tapete", match?.description)
    }

    @Test
    fun `same customer and amount but date outside 7 day window is NOT a duplicate`() {
        val op = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )
        val registered = listOf(
            RegisteredOperationProjection(
                id = "sale-100",
                customerId = "cust-1",
                customerName = "Rosa",
                amountCents = 25000L,
                effectiveDateEpochDay = todayEpoch - 10L, // 10 days ago (outside 7 day window)
                kind = ProposedLedgerKind.SALE,
            )
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `same amount and date but different customer is NOT a duplicate`() {
        val op = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )
        val registered = listOf(
            RegisteredOperationProjection(
                id = "sale-100",
                customerId = "cust-2",
                customerName = "Carlos",
                amountCents = 25000L,
                effectiveDateEpochDay = todayEpoch,
                kind = ProposedLedgerKind.SALE,
            )
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `flagged duplicate row is blocked until confirmed and registerable after`() {
        val op = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )
        val registered = listOf(
            RegisteredOperationProjection(
                id = "sale-100",
                customerId = "cust-1",
                customerName = "Rosa",
                amountCents = 25000L,
                effectiveDateEpochDay = todayEpoch,
                kind = ProposedLedgerKind.SALE,
            )
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )

        assertEquals(1, matches.size)
        // v44: el duplicado frena el renglón hasta que la persona confirme que es otra venta.
        assertEquals(
            "un duplicado sin confirmar deja el renglón sin resolver",
            setOf(0),
            LedgerDuplicateBlocking.blockedLineIndices(listOf(op), matches),
        )

        // Confirmar ("Sí, es otra venta") reutiliza la huella de las objeciones y lo desbloquea.
        val confirmed = LedgerReviewEdits.confirmObjection(listOf(op), 0)
        assertTrue(
            "tras confirmar, el renglón deja de estar bloqueado",
            LedgerDuplicateBlocking.blockedLineIndices(confirmed, matches).isEmpty(),
        )
        assertTrue(isRegisterableLedgerOperation(confirmed.single()))
    }

    @Test
    fun `empty history produces no duplicate matches`() {
        val op = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op),
            existingRegistered = emptyList(),
            todayEpochDay = todayEpoch,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `two identical rows on same page flag both rows as intra page duplicates and both stay blocked`() {
        val op1 = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )
        val op2 = LedgerOperation(
            lineIndex = 1,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )

        val matches = LedgerDuplicateDetector.evaluate(
            operations = listOf(op1, op2),
            existingRegistered = emptyList(),
            todayEpochDay = todayEpoch,
        )

        assertEquals(2, matches.size)
        assertEquals(1, matches[0]?.matchedLineIndex)
        assertEquals(0, matches[1]?.matchedLineIndex)

        // v44: los dos quedan frenados hasta que la persona confirme cada uno.
        assertEquals(
            setOf(0, 1),
            LedgerDuplicateBlocking.blockedLineIndices(listOf(op1, op2), matches),
        )
    }

    @Test
    fun `editing amount of flagged row clears the duplicate match`() {
        val opBefore = LedgerOperation(
            lineIndex = 0,
            customerName = "Rosa",
            amountCents = "25000",
            proposedKind = ProposedLedgerKind.SALE,
            date = SafeDateCandidate(year = 2026, month = 6, day = 7),
        )
        val registered = listOf(
            RegisteredOperationProjection(
                id = "sale-100",
                customerId = "cust-1",
                customerName = "Rosa",
                amountCents = 25000L,
                effectiveDateEpochDay = todayEpoch,
                kind = ProposedLedgerKind.SALE,
            )
        )

        val matchesBefore = LedgerDuplicateDetector.evaluate(
            operations = listOf(opBefore),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )
        assertEquals(1, matchesBefore.size)

        // User edits amount from 25000 to 30000 cents ($300)
        val opAfter = opBefore.copy(amountCents = "30000")
        val matchesAfter = LedgerDuplicateDetector.evaluate(
            operations = listOf(opAfter),
            existingRegistered = registered,
            todayEpochDay = todayEpoch,
        )
        assertTrue(matchesAfter.isEmpty())
    }
}
