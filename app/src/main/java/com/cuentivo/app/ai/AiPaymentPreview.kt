package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import java.util.Collections
import java.util.UUID

/** Result of matching a payment customer query against already repository-scoped candidate rows. */
sealed interface AiPaymentCustomerMatch {
    data object NotFound : AiPaymentCustomerMatch

    data class Exact(val customer: CustomerEntity) : AiPaymentCustomerMatch

    /**
     * More than one candidate matches and a human must choose.
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
            this === other ||
                (other is SelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "SelectionRequired(candidates=$candidates)"
    }
}

/** A safe-for-presentation candidate: local id and display name only, no financial data. */
data class AiPaymentCustomerCandidate(val customerId: String, val displayName: String)

/**
 * Resolved payment preview for one customer. Money is whole centavos in [Long]; the projected balance
 * is derived, never stored, and this preview exposes no confirmation or write path.
 */
data class AiResolvedPaymentPreview(
    val confirmationKey: String,
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

    /**
     * More than one candidate matches and a human must choose; never auto-selected.
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
            operator fun invoke(
                candidates: List<AiPaymentCustomerCandidate>,
            ): CustomerSelectionRequired = CustomerSelectionRequired(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is CustomerSelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "CustomerSelectionRequired(candidates=$candidates)"
    }

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
                AiPaymentCustomerMatch.SelectionRequired(candidates)
            }
            else -> AiPaymentCustomerMatch.SelectionRequired(candidates)
        }
    }
}

/**
 * Read-only resolver that turns a validated payment proposal into a deterministic preview against
 * the local ledger. It never writes, never confirms and never exposes a write path.
 *
 * Fail-closed rules: blank businessId or any seam exception returns [AiPaymentPreviewResult.Unavailable]
 * without fabricating data; a non-positive declared amount returns Unavailable because the proposal
 * validator should have already enforced this boundary; zero/non-positive balance blocks with
 * NO_OUTSTANDING_DEBT; an amount greater than the balance blocks with AMOUNT_EXCEEDS_DEBT and the
 * negative projected result available only for display — it never becomes executable credit.
 */
class AiPaymentPreviewResolver(
    private val searchCustomers: suspend (businessId: String, query: String) -> List<CustomerEntity>,
    private val loadBalanceCents: suspend (businessId: String, customerId: String) -> Long,
    private val newConfirmationKey: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun resolve(
        businessId: String,
        proposal: RecordPaymentProposal,
    ): AiPaymentPreviewResult = try {
        resolveInternal(businessId, proposal)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        AiPaymentPreviewResult.Unavailable
    }

    private suspend fun resolveInternal(
        businessId: String,
        proposal: RecordPaymentProposal,
    ): AiPaymentPreviewResult {
        if (businessId.isBlank()) {
            return AiPaymentPreviewResult.Unavailable
        }
        if (proposal.declaredAmountCents <= 0L) {
            return AiPaymentPreviewResult.Unavailable
        }
        val candidates = searchCustomers(businessId, proposal.customerName)
        val match = AiPaymentCandidateMatcher.match(proposal.customerName, candidates)
        if (match is AiPaymentCustomerMatch.NotFound) {
            return AiPaymentPreviewResult.CustomerNotFound
        }
        if (match is AiPaymentCustomerMatch.SelectionRequired) {
            return AiPaymentPreviewResult.CustomerSelectionRequired(
                match.candidates.map { AiPaymentCustomerCandidate(it.id, it.name) },
            )
        }
        val exact = match as AiPaymentCustomerMatch.Exact
        val customer = exact.customer
        val currentBalanceCents = loadBalanceCents(businessId, customer.id)
        if (currentBalanceCents <= 0L) {
            return AiPaymentPreviewResult.Blocked(AiPaymentBlockReason.NO_OUTSTANDING_DEBT, null)
        }
        val projected = Math.subtractExact(currentBalanceCents, proposal.declaredAmountCents)
        if (projected < 0L) {
            return AiPaymentPreviewResult.Blocked(
                AiPaymentBlockReason.AMOUNT_EXCEEDS_DEBT,
                AiResolvedPaymentPreview("", customer.id, customer.name, currentBalanceCents, proposal.declaredAmountCents, projected),
            )
        }
        val key = newConfirmationKey().trim()
        if (key.isBlank()) {
            return AiPaymentPreviewResult.Unavailable
        }
        return AiPaymentPreviewResult.Ready(
            AiResolvedPaymentPreview(key, customer.id, customer.name, currentBalanceCents, proposal.declaredAmountCents, projected),
        )
    }
}
