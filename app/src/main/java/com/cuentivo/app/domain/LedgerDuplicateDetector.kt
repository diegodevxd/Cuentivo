package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import kotlin.math.abs

/**
 * Projection of an existing registered sale, payment, or prior debt entry loaded from Room.
 */
data class RegisteredOperationProjection(
    val id: String,
    val customerId: String,
    val customerName: String,
    val amountCents: Long,
    val effectiveDateEpochDay: Long,
    val kind: ProposedLedgerKind,
    val description: String? = null,
)

/**
 * Result of duplicate detection for a ledger operation row.
 */
data class LedgerDuplicateMatch(
    val lineIndex: Int,
    val matchedRegisteredId: String? = null,
    val matchedLineIndex: Int? = null,
    val customerName: String,
    val amountCents: Long,
    val effectiveDateEpochDay: Long,
    val kind: ProposedLedgerKind,
    val description: String? = null,
)

/**
 * Pure evaluator for detecting potential duplicate ledger operations (Fase 4).
 *
 * Rules:
 * - A row is flagged as a possible duplicate IF AND ONLY IF all four signals match:
 *   1. Same customer (by anchored customerId OR by normalized customer name).
 *   2. Same amount in cents.
 *   3. Effective date within ±7 days window around the row's effective date.
 *   4. Same operation type (SALE, PAYMENT, PRIOR_DEBT).
 * - Canceled entries from Room do NOT count.
 * - The description field does NOT create a match by itself; it enriches the warning message.
 * - Flagging as possible duplicate NEVER blocks or disables registration.
 * - Two identical rows on the same page are both flagged, but neither is blocked.
 * - Zero Android Context, zero Room DAOs, zero network.
 */
object LedgerDuplicateDetector {

    const val DATE_WINDOW_DAYS = 7L

    fun evaluate(
        operations: List<LedgerOperation>,
        existingRegistered: List<RegisteredOperationProjection>,
        pageDateEpochDay: Long? = null,
        todayEpochDay: Long? = null,
    ): Map<Int, LedgerDuplicateMatch> {
        val matches = LinkedHashMap<Int, LedgerDuplicateMatch>()

        for (i in operations.indices) {
            val op = operations[i]
            val opAmount = op.amountCents?.toLongOrNull() ?: continue
            val opKind = op.proposedKind ?: continue

            val opEffectiveDate = resolveEffectiveDate(op.date, pageDateEpochDay, todayEpochDay) ?: continue

            val opNormCustomer = CustomerNameNormalizer.key(op.customerName.orEmpty())
            if (opNormCustomer.isEmpty() && op.customerId.isNullOrBlank()) continue

            // 1. Check against existing registered Room entries
            val matchedRegistered = existingRegistered.firstOrNull { reg ->
                reg.kind == opKind &&
                        reg.amountCents == opAmount &&
                        abs(reg.effectiveDateEpochDay - opEffectiveDate) <= DATE_WINDOW_DAYS &&
                        sameCustomer(op, reg)
            }

            if (matchedRegistered != null) {
                matches[op.lineIndex] = LedgerDuplicateMatch(
                    lineIndex = op.lineIndex,
                    matchedRegisteredId = matchedRegistered.id,
                    customerName = matchedRegistered.customerName,
                    amountCents = matchedRegistered.amountCents,
                    effectiveDateEpochDay = matchedRegistered.effectiveDateEpochDay,
                    kind = matchedRegistered.kind,
                    description = matchedRegistered.description,
                )
                continue
            }

            // 2. Check intra-page duplicates (another row on the same page with identical signals)
            val intraPageMatch = operations.firstOrNull { other ->
                other.lineIndex != op.lineIndex &&
                        other.proposedKind == opKind &&
                        other.amountCents?.toLongOrNull() == opAmount &&
                        sameCustomer(op, other) &&
                        resolveEffectiveDate(other.date, pageDateEpochDay, todayEpochDay) == opEffectiveDate
            }

            if (intraPageMatch != null) {
                matches[op.lineIndex] = LedgerDuplicateMatch(
                    lineIndex = op.lineIndex,
                    matchedLineIndex = intraPageMatch.lineIndex,
                    customerName = op.customerName.orEmpty(),
                    amountCents = opAmount,
                    effectiveDateEpochDay = opEffectiveDate,
                    kind = opKind,
                    description = intraPageMatch.description ?: op.description,
                )
            }
        }

        return matches
    }

    private fun sameCustomer(op: LedgerOperation, reg: RegisteredOperationProjection): Boolean {
        if (!op.customerId.isNullOrBlank() && op.customerId == reg.customerId) return true
        val opNorm = CustomerNameNormalizer.key(op.customerName.orEmpty())
        val regNorm = CustomerNameNormalizer.key(reg.customerName)
        return opNorm.isNotEmpty() && opNorm == regNorm
    }

    private fun sameCustomer(op: LedgerOperation, other: LedgerOperation): Boolean {
        if (!op.customerId.isNullOrBlank() && op.customerId == other.customerId) return true
        val opNorm = CustomerNameNormalizer.key(op.customerName.orEmpty())
        val otherNorm = CustomerNameNormalizer.key(other.customerName.orEmpty())
        return opNorm.isNotEmpty() && opNorm == otherNorm
    }

    private fun resolveEffectiveDate(
        dateCandidate: SafeDateCandidate?,
        pageDateEpochDay: Long?,
        todayEpochDay: Long?,
    ): Long? {
        if (dateCandidate != null) {
            val candidateEpoch = runCatching {
                java.time.LocalDate.of(dateCandidate.year, dateCandidate.month, dateCandidate.day).toEpochDay()
            }.getOrNull() ?: return null
            if (candidateEpoch <= 0 || (todayEpochDay != null && candidateEpoch > todayEpochDay)) return null
            return candidateEpoch
        }
        if (pageDateEpochDay != null && pageDateEpochDay > 0 && (todayEpochDay == null || pageDateEpochDay <= todayEpochDay)) {
            return pageDateEpochDay
        }
        return null
    }
}
