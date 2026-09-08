package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldConfidence
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correction counter decides whether the second Gemini pass is ever justified, so the ways it
 * could lie are enumerated in the encargo and each has a test here.
 *
 * All comparisons run against the production counter — never a copy of its rule.
 */
class LedgerCorrectionCounterTest {

    private fun row(
        lineIndex: Int = 0,
        customer: String? = "Rosa Martinez",
        customerId: String? = null,
        amount: String? = "25000",
        description: String? = "coca",
        date: SafeDateCandidate? = SafeDateCandidate(2026, 7, 30),
        kind: ProposedLedgerKind? = ProposedLedgerKind.SALE,
        confidence: LedgerFieldConfidence? = null,
    ) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = kind,
        customerName = customer,
        customerId = customerId,
        amountCents = amount,
        description = description,
        date = date,
        confidence = confidence,
    )

    @Test
    fun `a real change of each field counts as a correction of that field`() {
        val original = listOf(row())
        val final = listOf(
            row(
                customer = "Maria Lopez",
                amount = "38000",
                description = "pan",
                date = SafeDateCandidate(2026, 7, 2),
                kind = ProposedLedgerKind.PAYMENT,
            ),
        )

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(1, result.corrections.customer)
        assertEquals(1, result.corrections.amount)
        assertEquals(1, result.corrections.product)
        assertEquals(1, result.corrections.date)
        assertEquals(1, result.corrections.kind)
        assertEquals(1, result.rowsCorrected)
    }

    @Test
    fun `opening a row and saving without changing anything is not a correction`() {
        // The dialog was opened, the person checked the row against the crop, and saved. The doubt
        // is resolved; the values are the model's. Counting this would inflate the number in
        // exactly the direction that makes the second pass look necessary.
        val original = listOf(row())
        val final = listOf(row())

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(0, result.corrections.total)
        assertEquals(0, result.rowsCorrected)
    }

    @Test
    fun `changing a value and undoing it does not count`() {
        // Only the ends are compared: the final value is what the model gave.
        val original = listOf(row(amount = "25000"))
        val final = listOf(row(amount = "25000"))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(0, result.corrections.amount)
    }

    @Test
    fun `a deleted row counts as discarded, not as corrected`() {
        val original = listOf(row(0), row(1))
        val final = listOf(row(0))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(1, result.rowsDeleted)
        assertEquals(0, result.rowsCorrected)
        assertEquals(0, result.corrections.total)
    }

    @Test
    fun `a row corrected and then deleted counts as discarded, not as both`() {
        val original = listOf(row(0, amount = "25000"))
        val final = emptyList<LedgerOperation>()

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(1, result.rowsDeleted)
        assertEquals(0, result.rowsCorrected)
        assertEquals(0, result.corrections.total)
    }

    @Test
    fun `accepting a customer suggestion counts as a correction`() {
        // Accepting a suggestion is a human act: the anchor is what the money follows. Even when
        // the visible name did not move, picking the person is a correction.
        val original = listOf(row(customer = "Rosa Martnez", customerId = null))
        val final = listOf(row(customer = "Rosa Martnez", customerId = "cust-rosa"))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(1, result.corrections.customer)
    }

    @Test
    fun `a reformatted amount is not a correction`() {
        // The app normalizes what the person types through LedgerAmountInput; the value is the
        // model's. Only a different amount counts.
        val original = listOf(row(amount = "25000"))
        val final = listOf(row(amount = "25000"))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(0, result.corrections.amount)
    }

    @Test
    fun `names differing only in accents or spaces are the same name`() {
        // The canonical form already exists in CustomerNameNormalizer; the counter must use it.
        val original = listOf(row(customer = "Rosa Martinez"))
        val final = listOf(row(customer = "Rosa  Martínez"))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(0, result.corrections.customer)
    }

    @Test
    fun `dates written differently that are the same day are the same date`() {
        // SafeDateCandidate is already a triple, so this guards the comparison being on the
        // calendar day and not on any spelling.
        val original = listOf(row(date = SafeDateCandidate(2026, 7, 30)))
        val final = listOf(row(date = SafeDateCandidate(2026, 7, 30)))

        val result = LedgerCorrectionCounter.count(original, final)

        assertEquals(0, result.corrections.date)
    }

    // ------------------------------------------------------------------
    // Confidence buckets
    // ------------------------------------------------------------------

    @Test
    fun `the bucket edges are exact - 0_90 is high and 0_70 is mid`() {
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.HIGH, LedgerCorrectionCounter.bucketOf(0.90))
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.MID, LedgerCorrectionCounter.bucketOf(0.70))
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.MID, LedgerCorrectionCounter.bucketOf(0.899))
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.LOW, LedgerCorrectionCounter.bucketOf(0.699))
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.HIGH, LedgerCorrectionCounter.bucketOf(1.0))
        assertEquals(LedgerCorrectionCounter.ConfidenceBucket.LOW, LedgerCorrectionCounter.bucketOf(0.0))
    }

    @Test
    fun `a field without confidence falls in no bucket, not in the lowest`() {
        // "The model did not say" is not "the model was unsure".
        assertEquals(null, LedgerCorrectionCounter.bucketOf(null))
    }

    @Test
    fun `the histogram counts fields per bucket and corrections among them`() {
        val original = listOf(
            row(
                0,
                confidence = LedgerFieldConfidence(
                    customer = 0.95, // high, corrected below
                    amount = 0.75, // mid, not corrected
                    date = 0.50, // low, not corrected
                    kind = null, // no bucket
                ),
            ),
            row(
                1,
                confidence = LedgerFieldConfidence(
                    customer = 0.92, // high, not corrected
                    amount = 0.71, // mid, corrected below
                ),
            ),
        )
        val final = listOf(
            row(0, customer = "Maria Lopez"), // customer corrected
            row(1, amount = "38000"), // amount corrected
        )

        val histogram = LedgerCorrectionCounter.histogram(original, final)

        // High: 0.95 (corrected) + 0.92 = 2 fields, 1 corrected.
        assertEquals(2, histogram.highFields)
        assertEquals(1, histogram.highCorrected)
        // Mid: 0.75 + 0.71 (corrected) = 2 fields, 1 corrected.
        assertEquals(2, histogram.midFields)
        assertEquals(1, histogram.midCorrected)
        // Low: 0.50 = 1 field, 0 corrected.
        assertEquals(1, histogram.lowFields)
        assertEquals(0, histogram.lowCorrected)
    }

    @Test
    fun `the histogram ignores rows the person deleted`() {
        val original = listOf(
            row(0, confidence = LedgerFieldConfidence(customer = 0.95)),
        )
        val final = emptyList<LedgerOperation>()

        val histogram = LedgerCorrectionCounter.histogram(original, final)

        assertEquals(0, histogram.highFields)
        assertEquals(0, histogram.highCorrected)
    }

    @Test
    fun `the histogram is aggregated and carries no per-row order`() {
        // Two rows with the same confidence profile, in different orders, must produce the same
        // histogram: a pair per row in order is a fingerprint of the page.
        val confidence = LedgerFieldConfidence(customer = 0.95, amount = 0.75)
        val rowsA = listOf(row(0, confidence = confidence), row(1, confidence = confidence))
        val rowsB = listOf(row(1, confidence = confidence), row(0, confidence = confidence))

        val histogramA = LedgerCorrectionCounter.histogram(rowsA, rowsA)
        val histogramB = LedgerCorrectionCounter.histogram(rowsB, rowsB)

        assertEquals(histogramA, histogramB)
    }

    @Test
    fun `a correction on a field the model was silent about lands in no bucket`() {
        // The person fixed it, but the calibration question is about fields the model DID score.
        val original = listOf(row(0, confidence = null))
        val final = listOf(row(0, customer = "Maria Lopez"))

        val histogram = LedgerCorrectionCounter.histogram(original, final)

        assertEquals(0, histogram.highFields + histogram.midFields + histogram.lowFields)
        assertTrue(LedgerCorrectionCounter.count(original, final).corrections.customer == 1)
    }
}
