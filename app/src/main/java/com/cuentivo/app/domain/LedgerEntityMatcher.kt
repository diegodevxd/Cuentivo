package com.cuentivo.app.domain

import kotlin.math.max
import kotlin.math.min

enum class MatchDecision {
    AUTOMATIC,
    SUGGESTED,
    UNRESOLVED,
}

data class CustomerCandidate(
    val id: String,
    val name: String,
    val hasRecentActivity: Boolean = false,
)

data class ProductCandidate(
    val id: String,
    val name: String,
    val salePriceCents: Long? = null,
    val sku: String? = null,
)

data class EntityMatchResult<T>(
    val candidate: T?,
    val score: Double,
    val decision: MatchDecision,
)

/**
 * Pure entity matcher for notebook import candidates (Fase 2 + v41 SKU & Price Objections).
 *
 * Rules:
 * - Matches notebook text candidates against existing Room customer and product lists.
 * - AUTOMATIC (score >= 0.90 AND margin over 2nd candidate >= 0.05).
 * - SUGGESTED (score 0.70..0.89).
 * - UNRESOLVED (< 0.70 or tie/insufficient margin between top candidates).
 * - NEVER changes operation amounts. The notebook amount always takes precedence.
 * - Pure Kotlin, zero Android Context, zero Room DAOs, zero network.
 */
object LedgerEntityMatcher {
    const val AUTOMATIC_THRESHOLD = 0.90
    const val SUGGESTED_THRESHOLD = 0.70
    const val MIN_MARGIN_FOR_AUTOMATIC = 0.05

    fun matchCustomer(
        queryName: String?,
        candidates: List<CustomerCandidate>,
    ): EntityMatchResult<CustomerCandidate> {
        if (queryName.isNullOrBlank() || candidates.isEmpty()) {
            return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        }
        val normQuery = CustomerNameNormalizer.key(queryName)
        if (normQuery.isBlank()) {
            return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        }

        val scored = candidates.map { candidate ->
            val score = scoreCustomer(normQuery, candidate)
            candidate to score
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        val bestScore = best.second

        if (bestScore < SUGGESTED_THRESHOLD) {
            return EntityMatchResult(null, bestScore, MatchDecision.UNRESOLVED)
        }

        val secondScore = if (scored.size >= 2) scored[1].second else 0.0
        val margin = bestScore - secondScore

        if (scored.size >= 2 && margin < 1e-6) {
            return EntityMatchResult(null, bestScore, MatchDecision.UNRESOLVED)
        }

        val decision = when {
            bestScore >= AUTOMATIC_THRESHOLD && margin >= MIN_MARGIN_FOR_AUTOMATIC -> MatchDecision.AUTOMATIC
            bestScore >= SUGGESTED_THRESHOLD -> MatchDecision.SUGGESTED
            else -> MatchDecision.UNRESOLVED
        }

        return EntityMatchResult(
            candidate = if (decision != MatchDecision.UNRESOLVED) best.first else null,
            score = bestScore,
            decision = decision,
        )
    }

    fun matchProduct(
        queryText: String?,
        operationAmountCents: Long?,
        candidates: List<ProductCandidate>,
        rawText: String? = null,
    ): EntityMatchResult<ProductCandidate> {
        if ((queryText.isNullOrBlank() && rawText.isNullOrBlank()) || candidates.isEmpty()) {
            return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        }

        // 1. Check for exact SKU token match in rawText or queryText
        val tokensToScan = buildList {
            if (!queryText.isNullOrBlank()) addAll(queryText.split("\\s+".toRegex()))
            if (!rawText.isNullOrBlank()) addAll(rawText.split("\\s+".toRegex()))
        }.map { CustomerNameNormalizer.key(it) }.filter { it.isNotBlank() }.toSet()

        for (candidate in candidates) {
            val candSku = candidate.sku?.trim()
            if (!candSku.isNullOrBlank()) {
                val normSku = CustomerNameNormalizer.key(candSku)
                if (normSku.isNotBlank() && normSku in tokensToScan) {
                    return EntityMatchResult(
                        candidate = candidate,
                        score = 1.0,
                        decision = MatchDecision.AUTOMATIC,
                    )
                }
            }
        }

        val normQuery = if (!queryText.isNullOrBlank()) CustomerNameNormalizer.key(queryText) else ""
        if (normQuery.isBlank()) {
            return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        }

        val scored = candidates.map { candidate ->
            val score = scoreProduct(normQuery, operationAmountCents, candidate)
            candidate to score
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return EntityMatchResult(null, 0.0, MatchDecision.UNRESOLVED)
        val bestScore = best.second

        if (bestScore < SUGGESTED_THRESHOLD) {
            return EntityMatchResult(null, bestScore, MatchDecision.UNRESOLVED)
        }

        val secondScore = if (scored.size >= 2) scored[1].second else 0.0
        val margin = bestScore - secondScore

        if (scored.size >= 2 && margin < 1e-6) {
            return EntityMatchResult(null, bestScore, MatchDecision.UNRESOLVED)
        }

        val decision = when {
            bestScore >= AUTOMATIC_THRESHOLD && margin >= MIN_MARGIN_FOR_AUTOMATIC -> MatchDecision.AUTOMATIC
            bestScore >= SUGGESTED_THRESHOLD -> MatchDecision.SUGGESTED
            else -> MatchDecision.UNRESOLVED
        }

        return EntityMatchResult(
            candidate = if (decision != MatchDecision.UNRESOLVED) best.first else null,
            score = bestScore,
            decision = decision,
        )
    }

    private fun scoreCustomer(normQuery: String, candidate: CustomerCandidate): Double {
        val normCand = CustomerNameNormalizer.key(candidate.name)
        if (normQuery == normCand) return 1.0

        val baseScore = computeTextSimilarity(normQuery, normCand)
        val recencyBoost = if (candidate.hasRecentActivity) 0.03 else 0.0
        return min(1.0, baseScore + recencyBoost)
    }

    private fun scoreProduct(
        normQuery: String,
        operationAmountCents: Long?,
        candidate: ProductCandidate,
    ): Double {
        val normCand = CustomerNameNormalizer.key(candidate.name)
        if (normQuery == normCand) return 1.0

        val baseTextScore = computeTextSimilarity(normQuery, normCand)
        val priceMatchBoost = if (operationAmountCents != null && candidate.salePriceCents != null && candidate.salePriceCents == operationAmountCents) 0.10 else 0.0
        return min(1.0, baseTextScore + priceMatchBoost)
    }

    private fun computeTextSimilarity(q: String, target: String): Double {
        if (q == target) return 1.0

        val qTokens = q.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val tTokens = target.split("\\s+".toRegex()).filter { it.isNotBlank() }

        if (qTokens.isNotEmpty() && qTokens.size <= tTokens.size) {
            var allMatched = true
            for (i in qTokens.indices) {
                if (tTokens[i].startsWith(qTokens[i])) {
                    // match
                } else {
                    allMatched = false
                    break
                }
            }
            if (allMatched) {
                val coverage = qTokens.sumOf { it.length }.toDouble() / max(1, tTokens.sumOf { it.length })
                return min(0.88, 0.80 + (0.08 * coverage))
            }
        }

        // Pure Levenshtein match for typos is capped at 0.88 so typos yield SUGGESTED and never AUTOMATIC
        val levSim = levenshteinSimilarity(q, target)
        return min(0.88, levSim)
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val dist = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (dist.toDouble() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prevDiag = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) {
                    prevDiag
                } else {
                    1 + min(prevDiag, min(dp[j], dp[j - 1]))
                }
                prevDiag = temp
            }
        }
        return dp[s2.length]
    }
}
