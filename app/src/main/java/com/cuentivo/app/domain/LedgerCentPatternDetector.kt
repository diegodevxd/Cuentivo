package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.isRegisterableLedgerOperation

/**
 * Pure analyzer for centavos endings consistency across an imported page.
 *
 * Rules:
 * - Minimum readable amounts threshold: [MIN_READABLE_AMOUNTS] (5). Below 5 amounts, never flags.
 * - Dominant pattern threshold: 7 of 10 rows. If a single ending or standard pair (.00 / .90)
 *   accounts for >= 70% of rows, outliers with rare endings get flagged as unusual.
 * - Never alters operations, only returns suspicious row indices.
 * - Pure Kotlin, no Android Context.
 */
object LedgerCentPatternDetector {

    const val MIN_READABLE_AMOUNTS = 5
    fun findUnusualCentRows(operations: List<LedgerOperation>): Set<Int> {
        val amountsWithLineIndex = operations.mapNotNull { op ->
            val cents = op.amountCents?.toLongOrNull()
            if (cents != null && cents >= 0L) {
                op.lineIndex to cents
            } else {
                null
            }
        }

        if (amountsWithLineIndex.size < MIN_READABLE_AMOUNTS) {
            return emptySet()
        }

        val endings = amountsWithLineIndex.map { (lineIndex, cents) ->
            lineIndex to (cents % 100L).toInt()
        }

        val total = endings.size
        val countsByEnding = endings.groupingBy { it.second }.eachCount()

        // Check if standard round/.90 pricing dominates (.00 and .90 combined)
        val count00 = countsByEnding[0] ?: 0
        val count90 = countsByEnding[90] ?: 0
        val standardRoundCount = count00 + count90

        val dominantEndings: Set<Int> = when {
            standardRoundCount * 10 >= total * 7 && (count00 > 0 || count90 > 0) -> {
                setOfNotNull(if (count00 > 0) 0 else null, if (count90 > 0) 90 else null)
            }
            else -> {
                val mostFrequent = countsByEnding.maxByOrNull { it.value }
                if (mostFrequent != null && mostFrequent.value * 10 >= total * 7) {
                    setOf(mostFrequent.key)
                } else {
                    emptySet()
                }
            }
        }

        if (dominantEndings.isEmpty()) {
            return emptySet()
        }

        return endings.filter { it.second !in dominantEndings }.map { it.first }.toSet()
    }
}
