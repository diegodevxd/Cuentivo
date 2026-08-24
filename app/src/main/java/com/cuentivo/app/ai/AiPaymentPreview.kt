package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import java.util.Collections

/** A safe-for-presentation candidate: local id and display name only, no financial data. */
data class AiPaymentCustomerCandidate(val customerId: String, val displayName: String)

/**
 * Resolved payment preview for one customer. Money is whole centavos in [Long]; the projected balance
 * is derived, never stored, and this preview exposes no confirmation or write path.
 */
data class AiResolvedPaymentPreview(
    val customerId: String,
    val customerName: String,
    val currentBalanceCents: Long,
    val declaredAmountCents: Long,
    val projectedBalanceCents: Long,
)

enum class AiPaymentBlockReason { NO_OUTSTANDING_DEBT, AMOUNT_EXCEEDS_DEBT }

/**
 * More than one payment candidate matches and a human must choose.
 *
 * Plain final class so construction always freezes [candidates]: every construction stores an
 * unmodifiable copy, so neither the original reference nor a `MutableList` cast can mutate the
 * result on the JVM.
 */
class SelectionRequired private constructor(
    rawCandidates: List<CustomerEntity>,
) : AiPaymentCustomerMatch {
    /** Truly unmodifiable: mutation attempts throw [UnsupportedOperationException]. */
    val candidates: List<CustomerEntity> = Collections.unmodifiableList(rawCandidates.toList())

    companion object {
        operator fun invoke(candidates: List<CustomerEntity>): SelectionRequired =
            SelectionRequired(rawCandidates = candidates)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is SelectionRequired && candidates == other.candidates)

    override fun hashCode(): Int = candidates.hashCode()

    override fun toString(): String = "SelectionRequired(candidates=$candidates)"
}

/**
 * More than one payment candidate matches and a human must choose (preview-result shape).
 *
 * Plain final class so construction always freezes [candidates]: every construction stores an
 * unmodifiable copy, so neither the original reference nor a `MutableList` cast can mutate the
 * result on the JVM.
 */
class CustomerSelectionRequired private constructor(
    rawCandidates: List<AiPaymentCustomerCandidate>,
) : AiPaymentPreviewResult {
    /** Truly unmodifiable: mutation attempts throw [UnsupportedOperationException]. */
    val candidates: List<AiPaymentCustomerCandidate> =
        Collections.unmodifiableList(rawCandidates.toList())

    companion object {
        operator fun invoke(candidates: List<AiPaymentCustomerCandidate>): CustomerSelectionRequired =
            CustomerSelectionRequired(rawCandidates = candidates)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is CustomerSelectionRequired && candidates == other.candidates)

    override fun hashCode(): Int = candidates.hashCode()

    override fun toString(): String = "CustomerSelectionRequired(candidates=$candidates)"
}

/** Result of matching a payment customer query against already repository-scoped candidate rows. */
sealed interface AiPaymentCustomerMatch {
    data object NotFound : AiPaymentCustomerMatch

    data class Exact(val customer: CustomerEntity) : AiPaymentCustomerMatch
}

/** Outcome of resolving a validated payment proposal against the local ledger. */
sealed interface AiPaymentPreviewResult {
    data object CustomerNotFound : AiPaymentPreviewResult

    data class Ready(val preview: AiResolvedPaymentPreview) : AiPaymentPreviewResult

    data class Blocked(val reason: AiPaymentBlockReason, val preview: AiResolvedPaymentPreview?) : AiPaymentPreviewResult

    data object Unavailable : AiPaymentPreviewResult
}

/**
 * Pure, deterministic candidate matcher. It never touches Android, Room or Firebase and can run on
 * the JVM. The repository seam is expected to supply rows already scoped to the active business and
 * filtered to active/non-deleted customers; this matcher does not filter by business itself.
 *
 * The query is normalized with the same function used to store names, so an exact match means the
 * normalized query equals exactly one candidate normalizedName. Any non-empty set without a unique
 * exact match requires human selection, including a single partial match.
 */
object AiPaymentCandidateMatcher {

    fun match(query: String, candidates: List<CustomerEntity>): AiPaymentCustomerMatch {
        val normalizedQuery = com.cuentivo.app.domain.CustomerNameNormalizer.key(query)
        val exactMatches = candidates.filter { it.normalizedName == normalizedQuery }
        return when (exactMatches.size) {
            1 -> AiPaymentCustomerMatch.Exact(exactMatches.first())
            0 -> if (candidates.isEmpty()) {
                AiPaymentCustomerMatch.NotFound
            } else {
                SelectionRequired(candidates)
            }
            else -> SelectionRequired(candidates)
        }
    }
}
