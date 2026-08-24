package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import java.util.Collections

/** Result of matching a payment customer query against already repository-scoped candidate rows. */
sealed interface AiPaymentCustomerMatch {
    data object NotFound : AiPaymentCustomerMatch
    data class Exact(val customer: CustomerEntity) : AiPaymentCustomerMatch
    data class SelectionRequired(val candidates: List<CustomerEntity>) : AiPaymentCustomerMatch
}

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

/** Outcome of resolving a validated payment proposal against the local ledger. */
sealed interface AiPaymentPreviewResult {
    data object CustomerNotFound : AiPaymentPreviewResult
    data class CustomerSelectionRequired(val candidates: List<AiPaymentCustomerCandidate>) : AiPaymentPreviewResult
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
                AiPaymentCustomerMatch.SelectionRequired(Collections.unmodifiableList(candidates.toList()))
            }
            else -> AiPaymentCustomerMatch.SelectionRequired(Collections.unmodifiableList(candidates.toList()))
        }
    }
}
