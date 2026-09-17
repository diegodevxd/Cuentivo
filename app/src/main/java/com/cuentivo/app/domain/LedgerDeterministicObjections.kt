package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerOperation
import kotlin.math.abs

/**
 * Deterministic objections that can block a ledger row from being marked "ready"
 * and demote its field health to require human review (v41 Bloque 1).
 *
 * Rules:
 * - Deterministic: purely evaluated in Android domain, zero model dependency, zero network.
 * - Monotonic doubt: can only add doubt, never remove it.
 * - Blocks "ready" status in summary counts.
 * - Pure Kotlin, zero Android Context or R.string.
 * - Money arithmetic adheres to CLAUDE.md Rule 1: Zero Double/Float, exact integer Long arithmetic.
 */
enum class LedgerDeterministicObjectionType {
    /** Price differs by a factor of 10x or 0.1x from catalog price (decimal shift suspicion). */
    SUSPECT_DECIMAL_SHIFT,

    /** Price differs significantly (> 2x or < 0.5x) from catalog price. */
    PRICE_MISMATCH_SUSPECT,

    /** Cents ending deviates from the dominant cents pattern of the sheet. */
    UNUSUAL_CENTS,

    /** Line matches an existing customer but the name does not appear in rawText or was inherited. */
    CUSTOMER_CONFIRMATION_REQUIRED,
    PAGE_TOTAL_MISMATCH,
}

data class LedgerDeterministicObjection(
    val lineIndex: Int,
    val type: LedgerDeterministicObjectionType,
    val detail: String? = null,
    val declaredCents: Long? = null,
    val expectedCents: Long? = null,
)

object LedgerDeterministicObjections {

    private val PARENTHESES_REGEX = Regex("\\([^)]*\\)")

    /**
     * Las objeciones de una página, construidas desde las listas COMPLETAS de candidatos.
     *
     * Nunca desde los mapas de sugerencias: esos descartan a propósito las coincidencias exactas
     * (ofrecer "¿es Carmen?" a un renglón que dice Carmen es ruido), y por eso un renglón que
     * nombra exacto a una clienta existente se quedaba sin objeción justo en su caso real.
     */
    fun evaluate(
        operations: List<LedgerOperation>,
        existingCustomerCandidates: List<CustomerCandidate>,
        catalogProducts: List<ProductCandidate>,
        pageTotals: List<com.cuentivo.app.ai.ledger.LedgerPageTotal> = emptyList(),
    ): List<LedgerDeterministicObjection> {
        val existingNames = existingCustomerCandidates.mapTo(LinkedHashSet()) { CustomerNameNormalizer.key(it.name) }
        return evaluateWithContext(operations, productCandidatesByLine(operations, catalogProducts), existingNames, pageTotals)
    }

    fun blockedLineIndices(
        operations: List<LedgerOperation>,
        existingCustomerCandidates: List<CustomerCandidate>,
        catalogProducts: List<ProductCandidate>,
    ): Set<Int> = evaluate(operations, existingCustomerCandidates, catalogProducts)
        .mapTo(LinkedHashSet()) { it.lineIndex }

    private fun productCandidatesByLine(
        operations: List<LedgerOperation>,
        catalogProducts: List<ProductCandidate>,
    ): Map<Int, ProductCandidate> {
        if (catalogProducts.isEmpty()) return emptyMap()
        val byLine = LinkedHashMap<Int, ProductCandidate>()
        for (op in operations) {
            val queryText = when {
                !op.description.isNullOrBlank() -> op.description
                !op.rawText.isNullOrBlank() -> op.rawText
                else -> continue
            }
            val candidate = LedgerEntityMatcher.matchProduct(
                queryText, op.amountCents?.toLongOrNull(), catalogProducts, rawText = op.rawText,
            ).candidate ?: continue
            byLine[op.lineIndex] = candidate
        }
        return byLine
    }

    /**
     * Checks if [candidateWordTokens] appears in [textTokens] as a contiguous sub-sequence
     * of full tokens (words).
     */
    internal fun containsWordTokens(textTokens: List<String>, candidateWordTokens: List<String>): Boolean {
        if (candidateWordTokens.isEmpty()) return false
        if (candidateWordTokens.size > textTokens.size) return false

        val maxStart = textTokens.size - candidateWordTokens.size
        for (i in 0..maxStart) {
            var match = true
            for (j in candidateWordTokens.indices) {
                if (textTokens[i + j] != candidateWordTokens[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    /**
     * Evaluates deterministic objections for a page of operations.
     *
     * @param operations The operations on the page.
     * @param productCandidatesByLine Matched catalog products per lineIndex from the complete catalog.
     * @param existingCustomerNormalizedNames Set of normalized names of existing customers in the business.
     */
    private fun evaluateWithContext(
        operations: List<LedgerOperation>,
        productCandidatesByLine: Map<Int, ProductCandidate>,
        existingCustomerNormalizedNames: Set<String>,
        pageTotals: List<com.cuentivo.app.ai.ledger.LedgerPageTotal> = emptyList(),
    ): List<LedgerDeterministicObjection> {
        val objections = mutableListOf<LedgerDeterministicObjection>()

        // 1. Cent pattern objections across the page (excluding rows already confirmed by human)
        val unusualCentRowIndices = LedgerCentPatternDetector.findUnusualCentRows(operations)
        for (lineIndex in unusualCentRowIndices) {
            val isConfirmed = operations.firstOrNull { it.lineIndex == lineIndex }
                ?.let { LedgerObjectionConfirmation.isConfirmed(it) } == true
            if (!isConfirmed) {
                objections.add(
                    LedgerDeterministicObjection(
                        lineIndex = lineIndex,
                        type = LedgerDeterministicObjectionType.UNUSUAL_CENTS,
                    )
                )
            }
        }

        // 2. Per-row price and customer checks
        for (op in operations) {
            val lineIndex = op.lineIndex
            if (LedgerObjectionConfirmation.isConfirmed(op)) {
                // The user explicitly verified this line and affirmed "Sí, es correcto".
                // Objections are waived for this confirmed row.
                continue
            }

            // 1.1 Customer confirmation check:
            // If the row matches an existing customer:
            //   - Must check whether the customer name appears in the rawText of this specific row.
            //   - Marginal notes in parentheses e.g. "(Charly)" are stripped before checking.
            //   - Match must be whole words (tokens), not arbitrary substring ("dana" in "mariana").
            //   - Comillas ("〃"): If customer was inherited from previous row, only object if the
            //     inherited customer is an EXISTING customer.
            // When op.customerId is already set (user anchored), no objection.
            if (op.customerId.isNullOrBlank()) {
                val assignedName = op.customerName?.trim().orEmpty()
                if (assignedName.isNotEmpty()) {
                    val normAssigned = CustomerNameNormalizer.key(assignedName)
                    val isExistingCustomer = normAssigned in existingCustomerNormalizedNames
                    val isInherited = op.sources?.customer == LedgerFieldSource.INHERITED_PREVIOUS_ROW

                    if (isExistingCustomer) {
                        if (isInherited) {
                            objections.add(
                                LedgerDeterministicObjection(
                                    lineIndex = lineIndex,
                                    type = LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
                                    detail = assignedName,
                                )
                            )
                        } else {
                            // Strip parentheses to exclude marginal notes like (Charly)
                            val cleanRaw = op.rawText.orEmpty().replace(PARENTHESES_REGEX, " ")
                            val normRaw = CustomerNameNormalizer.key(cleanRaw)
                            val productEvidenceTokens = buildSet {
                                op.description?.let { addAll(CustomerNameNormalizer.key(it).split("\\s+".toRegex())) }
                                op.items.forEach { addAll(CustomerNameNormalizer.key(it.description).split("\\s+".toRegex())) }
                            }.filter { it.isNotBlank() }.toSet()
                            val rawTokens = normRaw.split("\\s+".toRegex())
                                .filter { it.isNotBlank() && it !in productEvidenceTokens }
                            val assignedTokens = normAssigned.split("\\s+".toRegex()).filter { it.isNotBlank() }

                            val nameInRaw = containsWordTokens(rawTokens, assignedTokens)
                            if (!nameInRaw) {
                                objections.add(
                                    LedgerDeterministicObjection(
                                        lineIndex = lineIndex,
                                        type = LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED,
                                        detail = assignedName,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 1.2 Price discrepancy check:
            // When a catalog product candidate has a known salePriceCents and the operation has an amountCents.
            // La comparación es contra el precio ESPERADO del renglón, no contra el precio unitario: una
            // cantidad ajusta lo que se espera (5 cuadernos de $20 son $100, y eso no es un error), y una
            // cantidad que no se puede saber no se revisa. Ver expectedTotals más abajo.
            val productCandidate = productCandidatesByLine[lineIndex]
            val catPriceCents = productCandidate?.salePriceCents
            val opPriceCents = op.amountCents?.toLongOrNull()

            if (productCandidate != null && catPriceCents != null && catPriceCents > 0L && opPriceCents != null && opPriceCents > 0L) {
                val expectedTotals = when {
                    op.items.isEmpty() -> listOf(catPriceCents to opPriceCents)
                    op.items.size == 1 -> {
                        val item = op.items.single()
                        val quantity = item.quantity
                        val expected = if (quantity != null && quantity >= 1) {
                            runCatching { Math.multiplyExact(quantity.toLong(), catPriceCents) }.getOrNull()
                        } else null
                        if (expected == null) emptyList() else listOf(expected to (item.subtotalCents?.toLongOrNull() ?: opPriceCents))
                    }
                    else -> op.items.mapNotNull { item ->
                        val quantity = item.quantity
                        val subtotal = item.subtotalCents?.toLongOrNull()
                        if (quantity == null || quantity < 1 || subtotal == null ||
                            CustomerNameNormalizer.key(item.description) != CustomerNameNormalizer.key(productCandidate.name)) null
                        else runCatching { Math.multiplyExact(quantity.toLong(), catPriceCents) }.getOrNull()?.let { it to subtotal }
                    }
                }
                for ((expected, total) in expectedTotals) {
                    if (total > 0L && expected > 0L && total != expected) {
                        // CLAUDE.md Rule 1: Zero Double / Float for money. Exact Long cross-multiplication:
                        // Decimal shift suspicion:
                        // 10x shift: opPrice ~ 10 * catPrice  <=> abs(opPrice - 10 * catPrice) <= catPrice / 10
                        // 0.1x shift: catPrice ~ 10 * opPrice <=> abs(catPrice - 10 * opPrice) <= opPrice / 10
                        val is10x = abs(total - 10L * expected) <= (expected / 10L)
                        val isTenth = abs(expected - 10L * total) <= (total / 10L)
                        val isDecimalShift = is10x || isTenth

                        if (isDecimalShift) {
                            objections.add(
                                LedgerDeterministicObjection(
                                    lineIndex = lineIndex,
                                    type = LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT,
                                    detail = productCandidate.name,
                                )
                            )
                        } else {
                            // Significant price mismatch (> 2x or < 0.5x):
                            // opPrice > 2 * catPrice  OR  catPrice > 2 * opPrice
                            val isMismatch = (total > 2L * expected) || (expected > 2L * total)
                            if (isMismatch) {
                                objections.add(
                                    LedgerDeterministicObjection(
                                        lineIndex = lineIndex,
                                        type = LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT,
                                        detail = productCandidate.name,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        for (total in pageTotals) {
            val block = operations.filter { total.afterLineIndex == null || it.lineIndex <= total.afterLineIndex }
            val expected = block.sumOf { op ->
                val cents = op.amountCents?.toLongOrNull() ?: 0L
                if (op.proposedKind == com.cuentivo.app.ai.ledger.ProposedLedgerKind.PAYMENT) -cents else cents
            }
            if (expected != total.amountCents) {
                block.forEach { op -> objections.add(LedgerDeterministicObjection(op.lineIndex, LedgerDeterministicObjectionType.PAGE_TOTAL_MISMATCH, total.label, total.amountCents, expected)) }
            }
        }
        return objections
    }
}
