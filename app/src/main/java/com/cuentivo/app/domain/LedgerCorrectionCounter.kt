package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerFieldConfidence
import java.time.LocalDate

/**
 * How many fields the person actually corrected on an imported page, and how the model's
 * confidence lined up with those corrections.
 *
 * Pure: no Android, no Room, no clock. The original reading and the final one arrive as lists,
 * matched by lineIndex — the same identity the review edits by.
 *
 * THE FOUR WAYS TO COUNT WRONG, AND HOW EACH IS RESOLVED HERE:
 *
 * 1. Opening a row and saving without changing anything resolves the person's doubt but is not
 *    a correction. Counting it would inflate the number in exactly the direction that makes the
 *    second Gemini pass look necessary. Resolved: fields are compared by value, not by "the
 *    dialog was opened".
 *
 * 2. Changing and undoing returns to the original value. Comparing only the two ends does not
 *    see it — and must not: the final value is what the model gave. Resolved: only the ends are
 *    compared.
 *
 * 3. What the app normalizes on its own — the amount passing through LedgerAmountInput, a
 *    customer anchored by ACCEPTING A SUGGESTION — is not the person reading the paper.
 *    Resolved: accepting a suggestion IS a human act and counts as a correction; reformatting a
 *    number does not, because the comparison is on canonical value, not on spelling.
 *
 * 4. A row the person deleted counts as discarded, not corrected, even if it had been edited
 *    first. Resolved: deleted rows are excluded from the correction counts entirely.
 */
object LedgerCorrectionCounter {

    /** The five field kinds a person can correct, with their per-kind counts. */
    data class Corrections(
        val customer: Int = 0,
        val product: Int = 0,
        val amount: Int = 0,
        val date: Int = 0,
        val kind: Int = 0,
    ) {
        val total: Int get() = customer + product + amount + date + kind
    }

    /**
     * Counts corrections row by row. Rows present in [original] but absent from [final] were
     * deleted by the person and are reported as such, never as corrections.
     */
    fun count(
        original: List<LedgerOperation>,
        final: List<LedgerOperation>,
    ): Result {
        val finalByLineIndex = final.associateBy { it.lineIndex }
        val originalByLineIndex = original.associateBy { it.lineIndex }

        var customer = 0
        var product = 0
        var amount = 0
        var date = 0
        var kind = 0
        var rowsCorrected = 0
        val deletedRows = originalByLineIndex.keys - finalByLineIndex.keys

        for ((lineIndex, originalOp) in originalByLineIndex) {
            if (lineIndex in deletedRows) continue
            val finalOp = finalByLineIndex[lineIndex] ?: continue

            var rowCorrected = false
            if (customerChanged(originalOp, finalOp)) { customer++; rowCorrected = true }
            if (productChanged(originalOp, finalOp)) { product++; rowCorrected = true }
            if (amountChanged(originalOp, finalOp)) { amount++; rowCorrected = true }
            if (dateChanged(originalOp, finalOp)) { date++; rowCorrected = true }
            if (kindChanged(originalOp, finalOp)) { kind++; rowCorrected = true }
            if (rowCorrected) rowsCorrected++
        }

        return Result(
            corrections = Corrections(customer, product, amount, date, kind),
            rowsCorrected = rowsCorrected,
            rowsDeleted = deletedRows.size,
        )
    }

    data class Result(
        val corrections: Corrections,
        val rowsCorrected: Int,
        val rowsDeleted: Int,
    )

    // Canonical comparisons: two dates written differently are the same date, and two names that
    // differ only in accents or spaces already have a canonical form in CustomerNameNormalizer.

    private fun customerChanged(original: LedgerOperation, final: LedgerOperation): Boolean {
        // An anchor accepted from a suggestion is a human act: the id is what the money follows,
        // so picking it is a correction even when the visible name did not move.
        if (final.customerId != null && final.customerId != original.customerId) return true
        return canonicalName(original.customerName) != canonicalName(final.customerName)
    }

    private fun canonicalName(name: String?): String =
        name?.let { CustomerNameNormalizer.key(it) }.orEmpty()

    private fun productChanged(original: LedgerOperation, final: LedgerOperation): Boolean =
        canonicalDescription(original.description) != canonicalDescription(final.description)

    private fun canonicalDescription(description: String?): String =
        description?.trim().orEmpty()

    private fun amountChanged(original: LedgerOperation, final: LedgerOperation): Boolean {
        // Canonical value, not spelling: "25000" and "25000" through LedgerAmountInput are the
        // same amount, and a reformatted number is not a person reading the paper.
        val originalCents = original.amountCents?.toLongOrNull()
        val finalCents = final.amountCents?.toLongOrNull()
        return originalCents != finalCents
    }

    private fun dateChanged(original: LedgerOperation, final: LedgerOperation): Boolean =
        canonicalEpochDay(original.date) != canonicalEpochDay(final.date)

    private fun canonicalEpochDay(date: com.cuentivo.app.ai.ledger.SafeDateCandidate?): Long? =
        date?.let { runCatching { LocalDate.of(it.year, it.month, it.day).toEpochDay() }.getOrNull() }

    private fun kindChanged(original: LedgerOperation, final: LedgerOperation): Boolean =
        original.proposedKind != final.proposedKind

    // ------------------------------------------------------------------
    // Confidence histogram
    // ------------------------------------------------------------------

    enum class ConfidenceBucket {
        HIGH,
        MID,
        LOW,
    }

    /**
     * Which bucket a confidence value falls into. Exact edges: 0.90 is HIGH, 0.70 is MID.
     * A field the model said nothing about (null confidence) falls in NO bucket — not in the
     * lowest one — because "the model did not say" is not "the model was unsure".
     */
    fun bucketOf(confidence: Double?): ConfidenceBucket? = when {
        confidence == null -> null
        confidence >= 0.90 -> ConfidenceBucket.HIGH
        confidence >= 0.70 -> ConfidenceBucket.MID
        else -> ConfidenceBucket.LOW
    }

    /**
     * The aggregated histogram for one import: per bucket, how many fields carried a confidence
     * in that bucket, and of those how many were corrected. No order, no per-row pairs — a pair
     * per row in order is a fingerprint of the page.
     */
    data class ConfidenceHistogram(
        val highFields: Int = 0,
        val highCorrected: Int = 0,
        val midFields: Int = 0,
        val midCorrected: Int = 0,
        val lowFields: Int = 0,
        val lowCorrected: Int = 0,
    )

    /**
     * Builds the histogram from the ORIGINAL reading's confidences — the calibration question is
     * "when the model said 0.95, was it right", and the answer is whether the person corrected
     * that field — crossed with the per-row correction outcome.
     */
    fun histogram(
        original: List<LedgerOperation>,
        final: List<LedgerOperation>,
    ): ConfidenceHistogram {
        val finalByLineIndex = final.associateBy { it.lineIndex }
        var high = 0; var highCorrected = 0
        var mid = 0; var midCorrected = 0
        var low = 0; var lowCorrected = 0

        for (originalOp in original) {
            val finalOp = finalByLineIndex[originalOp.lineIndex] ?: continue
            val confidence = originalOp.confidence ?: continue
            val correctedFields = correctedFieldNames(originalOp, finalOp)

            for ((field, value) in confidenceFields(confidence)) {
                val bucket = bucketOf(value) ?: continue
                val wasCorrected = field in correctedFields
                when (bucket) {
                    ConfidenceBucket.HIGH -> { high++; if (wasCorrected) highCorrected++ }
                    ConfidenceBucket.MID -> { mid++; if (wasCorrected) midCorrected++ }
                    ConfidenceBucket.LOW -> { low++; if (wasCorrected) lowCorrected++ }
                }
            }
        }
        return ConfidenceHistogram(
            highFields = high, highCorrected = highCorrected,
            midFields = mid, midCorrected = midCorrected,
            lowFields = low, lowCorrected = lowCorrected,
        )
    }

    private fun confidenceFields(confidence: LedgerFieldConfidence): List<Pair<String, Double>> =
        listOfNotNull(
            confidence.customer?.let { "customer" to it },
            confidence.concept?.let { "concept" to it },
            confidence.amount?.let { "amount" to it },
            confidence.date?.let { "date" to it },
            confidence.kind?.let { "kind" to it },
        )

    private fun correctedFieldNames(original: LedgerOperation, final: LedgerOperation): Set<String> {
        val corrected = mutableSetOf<String>()
        if (customerChanged(original, final)) corrected += "customer"
        if (productChanged(original, final)) corrected += "concept"
        if (amountChanged(original, final)) corrected += "amount"
        if (dateChanged(original, final)) corrected += "date"
        if (kindChanged(original, final)) corrected += "kind"
        return corrected
    }
}
