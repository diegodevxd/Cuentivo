package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerDetectedPageDate
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the review saver contract: what a restored review must contain, rebuilt from
 * the saved half through the real [LedgerReviewEngine] — not through a copy of the rule.
 *
 * The Bundle round trip itself cannot run on the JVM (this project has no Robolectric: Bundle is
 * a stub that throws), so it lives in the instrumented [com.cuentivo.app.ui.LedgerReviewSaverBundleTest].
 */
class LedgerReviewSaverTest {

    private fun reviewedOperation(lineIndex: Int, edited: Boolean = false) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = if (edited) "Rosa Martinez Corregida" else "Rosa Martnez",
        customerId = if (edited) "cust-rosa" else null,
        amountCents = if (edited) "38000" else "25000",
        description = "2 cocas",
        date = SafeDateCandidate(2026, 7, 30),
        uncertainFields = if (edited) emptyList() else listOf(UncertainLedgerField.CUSTOMER),
    )

    private fun savedReview(
        operations: List<LedgerOperation>,
        // El baseline por defecto es la lectura sin editar, que es lo que el modelo devolvio.
        originalOperations: List<LedgerOperation> = listOf(reviewedOperation(0, edited = false)),
    ) = LedgerReviewSaver.SavedLedgerReview(
        sessionId = "session-1",
        businessId = "business-1",
        operations = operations,
        discards = listOf(LedgerDiscardDiagnostic(inputIndex = 0, lineIndex = null, reason = LedgerDiscardReason.MALFORMED)),
        unreadTextRemaining = true,
        pageIncomplete = true,
        pageDateEpochDay = 20634L,
        todayEpochDay = 20700L,
        detectedPageDate = LedgerDetectedPageDate(SafeDateCandidate(2026, 7, 30), 0.95),
        originalOperations = originalOperations,
    )

    @Test
    fun `the rebuilt review keeps the corrections the person made`() {
        // The restore path rebuilds the derived half from the saved half; what must come back
        // verbatim is the person's work: the edited rows, the page date they chose, the session.
        val saved = savedReview(listOf(reviewedOperation(0, edited = true), reviewedOperation(1, edited = false)))

        val review = LedgerReviewSaver.rebuildReview(saved, existingCustomerBalances = mapOf("rosa martinez" to 5000L))

        assertEquals(saved.operations, review.operations)
        assertEquals(saved.pageDateEpochDay, review.pageDateEpochDay)
        assertEquals(saved.detectedPageDate, review.detectedPageDate)
        assertEquals(2, review.operations.size)
        // The edit, not the original reading: this is the line that fails if the saver ever
        // decides to re-extract or to keep the pre-edit snapshot.
        assertEquals("cust-rosa", review.operations[0].customerId)
        assertEquals("38000", review.operations[0].amountCents)
        assertTrue(review.operations[0].uncertainFields.isEmpty())
        // The untouched row keeps its doubt: restoring must not resolve doubts on its own.
        assertEquals(listOf(UncertainLedgerField.CUSTOMER), review.operations[1].uncertainFields)
    }

    @Test
    fun `a restored review is registerable only where the person made it so`() {
        // The invariant that matters at the far end: restoring cannot silently turn a doubtful
        // row into a registrable one. Row 0 was corrected by hand, row 1 was not.
        val saved = savedReview(listOf(reviewedOperation(0, edited = true), reviewedOperation(1, edited = false)))

        val review = LedgerReviewSaver.rebuildReview(saved, existingCustomerBalances = emptyMap())

        val registerable = review.operations.map { com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation(it) }
        assertEquals(listOf(true, false), registerable)
    }

    @Test
    fun `the saved shape carries the session and business the page belongs to`() {
        val saved = savedReview(listOf(reviewedOperation(0)))

        assertEquals("session-1", saved.sessionId)
        assertEquals("business-1", saved.businessId)
        // A page that did not fit and had unread text keeps saying so after a restore: those
        // flags decide whether the person is warned before confirming a half page.
        assertTrue(saved.pageIncomplete)
        assertTrue(saved.unreadTextRemaining)
    }
}
