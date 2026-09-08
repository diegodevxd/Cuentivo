package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation

/**
 * Which rows of a reviewed page have a product worth offering.
 *
 * The matching itself lives in [LedgerEntityMatcher]; this object decides *when* to ask it.
 *
 * Rules:
 * - Skipped if description is blank or null.
 * - Skipped if description already matches exact product name after normalization.
 * - Matcher receives description text and operation amount in centavos.
 * - Accepting a product proposal ONLY updates [LedgerOperation.description] to the product name.
 * - Never changes the operation amount, never sets a catalog product ID, never touches inventory.
 */
object LedgerProductMatching {

    fun matchesFor(
        operations: List<LedgerOperation>,
        candidates: List<ProductCandidate>,
    ): Map<Int, EntityMatchResult<ProductCandidate>> {
        if (candidates.isEmpty()) return emptyMap()
        val matches = LinkedHashMap<Int, EntityMatchResult<ProductCandidate>>()
        for (op in operations) {
            val description = op.description
            if (description.isNullOrBlank()) continue
            val amountCents = op.amountCents?.toLongOrNull()
            val result = LedgerEntityMatcher.matchProduct(description, amountCents, candidates)
            if (result.decision == MatchDecision.UNRESOLVED || result.candidate == null) continue
            // If description already matches exact product name after normalization, don't offer
            if (CustomerNameNormalizer.key(result.candidate.name) == CustomerNameNormalizer.key(description)) continue
            matches[op.lineIndex] = result
        }
        return matches
    }
}
