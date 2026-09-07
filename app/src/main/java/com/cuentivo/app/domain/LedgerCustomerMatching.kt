package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerOperation

/**
 * Which rows of a reviewed page have a customer worth offering, and which are on their own.
 *
 * The matching itself lives in [LedgerEntityMatcher]; this is the part that decides *when* to ask
 * it, and it exists as its own object so the answer can be tested without a screen.
 *
 * Two rows are deliberately skipped:
 *
 * - One already anchored. The person picked somebody, and re-suggesting would invite them to
 *   overwrite a decision they already made with a guess.
 * - One whose customer the model declared unreadable. There is no text to match against, so any
 *   candidate would be the app inventing a name out of an empty field — the exact failure this
 *   whole feature refuses to commit.
 */
object LedgerCustomerMatching {

    fun matchesFor(
        operations: List<LedgerOperation>,
        candidates: List<CustomerCandidate>,
    ): Map<Int, EntityMatchResult<CustomerCandidate>> {
        if (candidates.isEmpty()) return emptyMap()
        val matches = LinkedHashMap<Int, EntityMatchResult<CustomerCandidate>>()
        for (op in operations) {
            if (!op.customerId.isNullOrBlank()) continue
            val name = op.customerName
            if (name.isNullOrBlank()) continue
            val result = LedgerEntityMatcher.matchCustomer(name, candidates)
            if (result.decision == MatchDecision.UNRESOLVED || result.candidate == null) continue
            // An exact match adds nothing: the planner already resolves those by normalized name,
            // and offering "is this Rosa?" over a row that plainly says Rosa is noise.
            if (CustomerNameNormalizer.key(result.candidate.name) == CustomerNameNormalizer.key(name)) continue
            matches[op.lineIndex] = result
        }
        return matches
    }
}
