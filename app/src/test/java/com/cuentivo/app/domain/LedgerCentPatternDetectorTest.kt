package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCentPatternDetectorTest {

    @Test
    fun `fewer than 5 readable amounts never flags any row`() {
        val ops = listOf(
            op(0, "1000"), // .00
            op(1, "2000"), // .00
            op(2, "3000"), // .00
            op(3, "4091"), // .91 (outlier, but only 4 readable ops total)
        )

        val flagged = LedgerCentPatternDetector.findUnusualCentRows(ops)
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun `dominant 00 and 90 pattern flags rare 91 and 44 endings`() {
        // 10 rows: 5 with .00, 3 with .90, 1 with .91, 1 with .44
        // .00 + .90 = 8/10 (80% >= 70%)
        val ops = listOf(
            op(0, "1000"), // .00
            op(1, "2500"), // .00
            op(2, "3090"), // .90
            op(3, "4590"), // .90
            op(4, "5000"), // .00
            op(5, "6000"), // .00
            op(6, "7590"), // .90
            op(7, "8000"), // .00
            op(8, "9991"), // .91 -> outlier
            op(9, "4444"), // .44 -> outlier
        )

        val flagged = LedgerCentPatternDetector.findUnusualCentRows(ops)
        assertEquals(setOf(8, 9), flagged)
    }

    @Test
    fun `single dominant ending like 50 flags rare 00 ending`() {
        // 7 rows of .50 (7/8 = 87.5%), 1 row of .00
        val ops = (0..6).map { idx -> op(idx, "${(idx + 1) * 10}50") } + listOf(op(7, "10000"))

        val flagged = LedgerCentPatternDetector.findUnusualCentRows(ops)
        assertEquals(setOf(7), flagged)
    }

    @Test
    fun `no dominant pattern flags nothing`() {
        // Diverse endings: .10, .20, .30, .40, .50, .60
        val ops = listOf(
            op(0, "1010"),
            op(1, "1020"),
            op(2, "1030"),
            op(3, "1040"),
            op(4, "1050"),
            op(5, "1060"),
        )

        val flagged = LedgerCentPatternDetector.findUnusualCentRows(ops)
        assertTrue(flagged.isEmpty())
    }

    private fun op(lineIndex: Int, amountCents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Synthetic Customer $lineIndex",
        amountCents = amountCents,
    )
}
