package com.cuentivo.app.ai.ledger

import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.domain.LedgerObjectionConfirmation
import java.util.Collections

enum class ProposedLedgerKind {
    PRIOR_DEBT,
    SALE,
    PAYMENT,
}

enum class UncertainLedgerField {
    AMOUNT,
    CUSTOMER,
    DATE,
    KIND,
}

enum class UnreadableLedgerField {
    AMOUNT,
    CUSTOMER,
    DATE,
    KIND,
}

enum class LedgerDiscardReason {
    MALFORMED,
    OUT_OF_ORDER,
    DUPLICATE_LINE_INDEX,
    INVALID_MONEY,
    INVALID_CUSTOMER,
    INVALID_UNCERTAIN_FIELDS,
    INVALID_UNREADABLE_FIELDS,
    DISALLOWED_PROPERTIES,
}

data class LedgerDiscardDiagnostic(
    val inputIndex: Int,
    val lineIndex: Int? = null,
    val reason: LedgerDiscardReason,
)

data class SafeDateCandidate(
    val year: Int,
    val month: Int,
    val day: Int,
)

data class LedgerRegion(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Immutable candidate ledger operation.
 *
 * Invariant parity with Contract v3:
 * A row with any unreadable fields or unresolved uncertain fields or invalid money/customer
 * cannot be registered directly.
 */
data class LedgerOperation(
    val lineIndex: Int,
    val proposedKind: ProposedLedgerKind? = null,
    val customerName: String? = null,
    val amountCents: String? = null,
    val description: String? = null,
    val date: SafeDateCandidate? = null,
    val uncertainFields: List<UncertainLedgerField> = emptyList(),
    val unreadableFields: List<UnreadableLedgerField> = emptyList(),
    val region: LedgerRegion? = null,
    val customerId: String? = null,
    /** How sure the model was of each field, when it said. Never decides anything on its own. */
    val confidence: LedgerFieldConfidence? = null,
    /** Where each field came from: read on the row, inherited from the one above, or the page date. */
    val sources: LedgerFieldSources? = null,
    /** The line as the model read it, kept as evidence of what the paper actually said. */
    val rawText: String? = null,
    val marginNote: String? = null,
    /** The products the model separated out of the line, when it managed to. */
    val items: List<LedgerLineItem> = emptyList(),
    /**
     * Why the model was uneasy about this line, in its own words.
     *
     * Advice, not a brake: the server already turns every warning that means "somebody has to look"
     * into an entry in [uncertainFields], where the registrability rule reads it. These are kept so
     * the screen can eventually say why a row is marked without asking the server again.
     */
    val warnings: List<String> = emptyList(),
    /** Whether an active objection on this row was explicitly reviewed and confirmed by the human. */
    val isObjectionConfirmed: Boolean = false,
    /**
     * Huella de los campos que se confirmaron. Si el renglón cambia después, la huella deja de
     * coincidir y la confirmación caduca: nadie confirmó lo que ahora dice.
     */
    val objectionConfirmedFingerprint: String? = null,
) {
    /**
     * Defensive copy to guarantee snapshot immutability regardless of caller list modifications.
     */
    fun deepCopy(): LedgerOperation = copy(
        uncertainFields = Collections.unmodifiableList(uncertainFields.toList()),
        unreadableFields = Collections.unmodifiableList(unreadableFields.toList()),
        items = Collections.unmodifiableList(items.toList()),
        warnings = Collections.unmodifiableList(warnings.toList()),
        isObjectionConfirmed = isObjectionConfirmed,
        objectionConfirmedFingerprint = objectionConfirmedFingerprint,
    )
}

/**
 * Where a field came from, which is not the same question as how sure the model was of it.
 *
 * A name inherited from a quote mark can be read perfectly and still be the wrong person, because
 * the mark is an assumption about the row above. Hiding that behind a confidence number would tell
 * the reviewer the wrong thing to check.
 */
enum class LedgerFieldSource {
    EXPLICIT,
    INHERITED_PREVIOUS_ROW,
    PAGE_DATE,
    INFERRED,
}

data class LedgerFieldSources(
    val customer: LedgerFieldSource? = null,
    val date: LedgerFieldSource? = null,
)

/** Per-field certainty in 0..1, absent when the model did not say. Absent is not zero. */
data class LedgerFieldConfidence(
    val customer: Double? = null,
    val concept: Double? = null,
    val amount: Double? = null,
    val date: Double? = null,
    val kind: Double? = null,
)

/** One product the model separated out of a line: "2 cocas 38" is quantity 2, subtotal "3800". */
data class LedgerLineItem(
    val description: String,
    val quantity: Int? = null,
    val subtotalCents: String? = null,
)

private val CANONICAL_LEDGER_CENTS_RE = Regex("^(0|[1-9][0-9]{0,17})$")
const val MAX_LEDGER_CUSTOMER_NAME_LENGTH = 80

/**
 * Tests whether a character is an ECMAScript whitespace or line terminator character:
 * - WhiteSpace: \t (0x09), \v (0x0B), \f (0x0C), ' ' (0x20), \u00A0, \u1680, \u2000..\u200A,
 *               \u202F, \u205F, \u3000, \uFEFF (BOM)
 * - LineTerminator: \n (0x0A), \r (0x0D), \u2028, \u2029
 */
fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680',
    in '\u2000'..'\u200A', '\u2028', '\u2029', '\u202F', '\u205F',
    '\u3000', '\uFEFF' -> true
    else -> false
}

/**
 * Trims leading and trailing ECMAScript whitespace and line terminator characters,
 * achieving 100% parity with JavaScript's String.prototype.trim().
 */
fun trimJsWhitespace(s: String): String {
    var start = 0
    while (start < s.length && isJsWhitespace(s[start])) {
        start++
    }
    var end = s.length
    while (end > start && isJsWhitespace(s[end - 1])) {
        end--
    }
    return s.substring(start, end)
}

/**
 * Validates whether a [LedgerOperation] satisfies all requirements for contract registration.
 *
 * Parity with contract.ts isRegisterableLedgerOperation:
 * - unreadableFields and uncertainFields must both be empty.
 * - proposedKind must be non-null (one of PRIOR_DEBT, SALE, PAYMENT).
 * - customerName must be non-blank after trim (including U+FEFF parity with JS trim) and <= 80 characters.
 * - amountCents must match canonical cents regex ^(0|[1-9][0-9]{0,17})$.
 */
fun isRegisterableLedgerOperation(op: LedgerOperation): Boolean {
    if (op.unreadableFields.isNotEmpty() || op.uncertainFields.isNotEmpty()) {
        return false
    }
    if (op.proposedKind == null) {
        return false
    }
    val name = op.customerName
    val hasValidName = !name.isNullOrEmpty() && name.length <= MAX_LEDGER_CUSTOMER_NAME_LENGTH && trimJsWhitespace(name).isNotEmpty()
    val hasValidCustomerId = !op.customerId.isNullOrBlank()
    if (!hasValidName && !hasValidCustomerId) {
        return false
    }
    val cents = op.amountCents
    if (cents.isNullOrEmpty() || !CANONICAL_LEDGER_CENTS_RE.matches(cents)) {
        return false
    }
    return true
}

/**
 * Reasons why an otherwise syntactically complete operation cannot be financially executed.
 */
enum class FinancialRejectionReason {
    /** Amount cannot be zero centavos in financial execution. */
    ZERO_AMOUNT,
    /** Cumulative debt transition exceeds 64-bit Long arithmetic bounds. */
    OVERFLOW,
    /** Payment without any existing or newly accrued debt. */
    PAYMENT_WITHOUT_DEBT,
    /** Payment amount exceeds the customer's total outstanding debt at this point. */
    PAYMENT_EXCEEDS_DEBT,
}

data class OperationFinancialDiagnostic(
    val lineIndex: Int,
    val reason: FinancialRejectionReason,
)

/**
 * Grouped customer operations with debt transition, financial diagnostics, and classification.
 */
data class CustomerReviewGroup(
    val customerName: String,
    val normalizedCustomerName: String,
    val isNew: Boolean,
    val previousBalanceCents: Long,
    val resultingBalanceCents: Long,
    val operations: List<LedgerOperation>,
    val executableOperations: List<LedgerOperation>,
    val financialDiagnostics: List<OperationFinancialDiagnostic>,
) {
    /** True if this group has at least one financially executable operation. */
    val willBeCreatedAsCustomer: Boolean
        get() = isNew && executableOperations.isNotEmpty()
}

/**
 * Summary metrics of the review, strictly bound to the edited version of the operations list.
 */
data class LedgerReviewSummary(
    val newCustomersCount: Int,
    val visibleCustomersCount: Int,
    val registerableOperationsCount: Int,
    val executableOperationsCount: Int,
    val unregisterableOperationsCount: Int,
    val salesCount: Int,
    val paymentsCount: Int,
    val priorDebtsCount: Int,
    val excludedRowsCount: Int,
    val financialDiagnostics: List<OperationFinancialDiagnostic>,
    val pageDateEpochDay: Long? = null,
    val hasMissingDates: Boolean = false,
) {
    /** Legacy alias for compatibility. */
    val totalCustomersCount: Int
        get() = visibleCustomersCount

    /** True if any operation has a blocking financial diagnostic. */
    val hasFinancialErrors: Boolean
        get() = financialDiagnostics.isNotEmpty()

    /**
     * Whether this page can be confirmed at all. Same condition the machine enforces on the
     * confirm transition, exposed here so the screen mirrors the block instead of deciding it.
     */
    val canConfirm: Boolean
        get() = !hasFinancialErrors && executableOperationsCount > 0 && !hasMissingDates
}

/**
 * Pure review model derived deterministically from the operations list and customer balances.
 */
data class LedgerReview(
    val customerGroups: List<CustomerReviewGroup>,
    val operations: List<LedgerOperation>,
    val discards: List<LedgerDiscardDiagnostic>,
    val unreadTextRemaining: Boolean,
    val pageIncomplete: Boolean,
    val summary: LedgerReviewSummary,
    val pageDateEpochDay: Long? = null,
    val todayEpochDay: Long? = null,
    /**
     * The page date the server detected on the sheet, if it sent one that is a real, non-future
     * calendar day. Carried so the date card can offer it; never applied without a tap.
     */
    val detectedPageDate: LedgerDetectedPageDate? = null,
    val pageType: LedgerPageType? = null,
    val pageTotals: List<LedgerPageTotal> = emptyList(),
    /**
     * The cross-check against the Ledger de MexaLM, when the server sent one.
     *
     * Carried here — and not recomputed — because it belongs to the PAGE, not to the rows: every
     * edit rebuilds this object from the operations, and the block has to survive the rebuild the
     * same way the detected page date does.
     *
     * It is a comment next to the page. Nothing in the app reads it to change what gets registered.
     */
    val mexalmCrosscheck: MexalmCrosscheckPayload? = null,
)

enum class LedgerPageType { ACCOUNTS, COST_OR_PRICE_LIST, OTHER }
data class LedgerPageTotal(val label: String, val amountCents: Long, val afterLineIndex: Int? = null)

/**
 * Pure builder and state machine for ledger reviews.
 * Zero Android dependencies, zero Room, zero network, zero system clock calls.
 */
object LedgerReviewEngine {

    /**
     * Builds a LedgerReview from a list of operations, discard diagnostics, flags,
     * and a map of known customer balances keyed by normalized customer name.
     *
     * Enforces snapshot immutability via defensive deep copies.
     */
    fun build(
        operations: List<LedgerOperation>,
        discards: List<LedgerDiscardDiagnostic> = emptyList(),
        unreadTextRemaining: Boolean = false,
        pageIncomplete: Boolean = false,
        existingCustomerBalances: Map<String, Long> = emptyMap(),
        pageDateEpochDay: Long? = null,
        todayEpochDay: Long? = null,
        detectedPageDate: LedgerDetectedPageDate? = null,
        pageType: LedgerPageType? = null,
        pageTotals: List<LedgerPageTotal> = emptyList(),
        mexalmCrosscheck: MexalmCrosscheckPayload? = null,
    ): LedgerReview {
        // El acuerdo del Ledger de MexaLM resuelve la duda de LECTURA de los renglones que él
        // confirmó, y sólo de ésos: el monto, el cliente y el tipo. Se aplica AQUÍ, y no en el
        // parser, porque el bloque pertenece a la página y cada edición vuelve a construir la
        // revisión —igual que la fecha detectada— y la resolución tiene que sobrevivir el rebuild.
        //
        // Lo que NO se toca: la fecha, las objeciones, los duplicados, el saldo, y el monto que
        // se registra. Un renglón confirmado deja de estar "por revisar"; no cambia lo que se
        // anota, y el dueño sigue pulsando el botón de siempre.
        val resolvedOps = operations.map { MexalmCrosscheckConfirmation.resolveReadingDoubts(it, mexalmCrosscheck) }

        // Defensive copy: isolate snapshot from any caller modifications
        val frozenOps: List<LedgerOperation> = Collections.unmodifiableList(
            resolvedOps.map { it.deepCopy() }
        )
        val frozenDiscards: List<LedgerDiscardDiagnostic> = Collections.unmodifiableList(
            discards.toList()
        )

        val frozenBalances: Map<String, Long> = Collections.unmodifiableMap(
            LinkedHashMap(existingCustomerBalances)
        )

        val linkedGroups = LinkedHashMap<String, MutableList<LedgerOperation>>()
        for (op in frozenOps) {
            val key = if (!op.customerId.isNullOrBlank()) {
                "id:${op.customerId}"
            } else {
                op.customerName?.let { CustomerNameNormalizer.key(it) } ?: ""
            }
            linkedGroups.getOrPut(key) { mutableListOf() }.add(op)
        }

        var salesCount = 0
        var paymentsCount = 0
        var priorDebtsCount = 0
        var registerableCount = 0
        var unregisterableCount = 0
        var executableCount = 0

        val customerGroups = mutableListOf<CustomerReviewGroup>()
        val allFinancialDiagnostics = mutableListOf<OperationFinancialDiagnostic>()

        for ((normKey, ops) in linkedGroups) {
            val isBlankKey = normKey.isEmpty()
            val isKnown = !isBlankKey && frozenBalances.containsKey(normKey)
            val isNew = !isBlankKey && !isKnown

            val previousBalance = if (!isBlankKey) {
                frozenBalances[normKey] ?: 0L
            } else {
                0L
            }

            var currentRunningBalance = previousBalance
            val groupExecutableOps = mutableListOf<LedgerOperation>()
            val groupFinancialDiagnostics = mutableListOf<OperationFinancialDiagnostic>()

            for (op in ops) {
                val registerable = isRegisterableLedgerOperation(op)
                if (registerable) {
                    registerableCount++
                } else {
                    unregisterableCount++
                    continue
                }

                when (op.proposedKind) {
                    ProposedLedgerKind.SALE -> {
                        val cents = op.amountCents?.toLongOrNull() ?: 0L
                        if (cents == 0L) {
                            val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.ZERO_AMOUNT)
                            groupFinancialDiagnostics.add(diag)
                            allFinancialDiagnostics.add(diag)
                        } else {
                            try {
                                currentRunningBalance = Math.addExact(currentRunningBalance, cents)
                                groupExecutableOps.add(op)
                                executableCount++
                                salesCount++
                            } catch (_: ArithmeticException) {
                                val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.OVERFLOW)
                                groupFinancialDiagnostics.add(diag)
                                allFinancialDiagnostics.add(diag)
                            }
                        }
                    }
                    ProposedLedgerKind.PRIOR_DEBT -> {
                        val cents = op.amountCents?.toLongOrNull() ?: 0L
                        if (cents == 0L) {
                            val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.ZERO_AMOUNT)
                            groupFinancialDiagnostics.add(diag)
                            allFinancialDiagnostics.add(diag)
                        } else {
                            try {
                                currentRunningBalance = Math.addExact(currentRunningBalance, cents)
                                groupExecutableOps.add(op)
                                executableCount++
                                priorDebtsCount++
                            } catch (_: ArithmeticException) {
                                val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.OVERFLOW)
                                groupFinancialDiagnostics.add(diag)
                                allFinancialDiagnostics.add(diag)
                            }
                        }
                    }
                    ProposedLedgerKind.PAYMENT -> {
                        val cents = op.amountCents?.toLongOrNull() ?: 0L
                        if (cents == 0L) {
                            val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.ZERO_AMOUNT)
                            groupFinancialDiagnostics.add(diag)
                            allFinancialDiagnostics.add(diag)
                        } else if (currentRunningBalance <= 0L) {
                            val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.PAYMENT_WITHOUT_DEBT)
                            groupFinancialDiagnostics.add(diag)
                            allFinancialDiagnostics.add(diag)
                        } else if (cents > currentRunningBalance) {
                            val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.PAYMENT_EXCEEDS_DEBT)
                            groupFinancialDiagnostics.add(diag)
                            allFinancialDiagnostics.add(diag)
                        } else {
                            try {
                                currentRunningBalance = Math.subtractExact(currentRunningBalance, cents)
                                groupExecutableOps.add(op)
                                executableCount++
                                paymentsCount++
                            } catch (_: ArithmeticException) {
                                val diag = OperationFinancialDiagnostic(op.lineIndex, FinancialRejectionReason.OVERFLOW)
                                groupFinancialDiagnostics.add(diag)
                                allFinancialDiagnostics.add(diag)
                            }
                        }
                    }
                    null -> {}
                }
            }

            val displayName = ops.mapNotNull { it.customerName?.let { name -> trimJsWhitespace(name) } }
                .firstOrNull { it.isNotEmpty() }
                ?: ""

            customerGroups.add(
                CustomerReviewGroup(
                    customerName = displayName,
                    normalizedCustomerName = normKey,
                    isNew = isNew,
                    previousBalanceCents = previousBalance,
                    resultingBalanceCents = currentRunningBalance,
                    operations = Collections.unmodifiableList(ops.toList()),
                    executableOperations = Collections.unmodifiableList(groupExecutableOps.toList()),
                    financialDiagnostics = Collections.unmodifiableList(groupFinancialDiagnostics.toList()),
                )
            )
        }

        val visibleCustomersCount = customerGroups.count { it.normalizedCustomerName.isNotEmpty() }
        val newCustomersCount = customerGroups.count { it.willBeCreatedAsCustomer }

        val allExecutableOps = customerGroups.flatMap { it.executableOperations }
        val isValidPageDate = pageDateEpochDay != null && pageDateEpochDay > 0 && (todayEpochDay == null || pageDateEpochDay <= todayEpochDay)
        val hasMissingDates = allExecutableOps.any { it.date == null } && !isValidPageDate

        val summary = LedgerReviewSummary(
            newCustomersCount = newCustomersCount,
            visibleCustomersCount = visibleCustomersCount,
            registerableOperationsCount = registerableCount,
            executableOperationsCount = executableCount,
            unregisterableOperationsCount = unregisterableCount,
            salesCount = salesCount,
            paymentsCount = paymentsCount,
            priorDebtsCount = priorDebtsCount,
            excludedRowsCount = frozenDiscards.size,
            financialDiagnostics = Collections.unmodifiableList(allFinancialDiagnostics.toList()),
            pageDateEpochDay = pageDateEpochDay,
            hasMissingDates = hasMissingDates,
        )

        return LedgerReview(
            customerGroups = Collections.unmodifiableList(customerGroups.toList()),
            operations = frozenOps,
            discards = frozenDiscards,
            unreadTextRemaining = unreadTextRemaining,
            pageIncomplete = pageIncomplete,
            summary = summary,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
            detectedPageDate = detectedPageDate,
            pageType = pageType,
            pageTotals = pageTotals,
            mexalmCrosscheck = mexalmCrosscheck,
        )
    }

    /**
     * Updates an operation in currentOperations with lineIndex matching, returning a brand new immutable list.
     */
    fun updateOperation(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        transform: (LedgerOperation) -> LedgerOperation,
    ): List<LedgerOperation> {
        return currentOperations.map { op ->
            if (op.lineIndex == lineIndex) {
                transform(op)
            } else {
                op
            }
        }
    }

    /**
     * Changes the proposed kind of the operation at lineIndex.
     */
    fun setOperationKind(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        newKind: ProposedLedgerKind,
    ): List<LedgerOperation> {
        return updateOperation(currentOperations, lineIndex) { op ->
            val updatedUncertain = op.uncertainFields - UncertainLedgerField.KIND
            val updatedUnreadable = op.unreadableFields - UnreadableLedgerField.KIND
            op.copy(
                proposedKind = newKind,
                uncertainFields = updatedUncertain,
                unreadableFields = updatedUnreadable,
            )
        }
    }

    /**
     * Edits the amount of the operation at lineIndex, automatically resolving AMOUNT uncertain/unreadable fields.
     */
    fun setOperationAmount(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        amountCents: String,
    ): List<LedgerOperation> {
        return updateOperation(currentOperations, lineIndex) { op ->
            val updatedUncertain = op.uncertainFields - UncertainLedgerField.AMOUNT
            val updatedUnreadable = op.unreadableFields - UnreadableLedgerField.AMOUNT
            op.copy(
                amountCents = amountCents,
                uncertainFields = updatedUncertain,
                unreadableFields = updatedUnreadable,
            )
        }
    }

    /**
     * Edits the customer name of the operation at lineIndex, automatically resolving CUSTOMER uncertain/unreadable fields.
     */
    fun setOperationCustomer(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        customerName: String,
    ): List<LedgerOperation> {
        return updateOperation(currentOperations, lineIndex) { op ->
            val updatedUncertain = op.uncertainFields - UncertainLedgerField.CUSTOMER
            val updatedUnreadable = op.unreadableFields - UnreadableLedgerField.CUSTOMER
            op.copy(
                customerName = customerName,
                uncertainFields = updatedUncertain,
                unreadableFields = updatedUnreadable,
            )
        }
    }

    /**
     * Writes the description a person picked from the catalogue, and nothing else.
     *
     * Deliberately narrow. The description is a label: it is not in either doubt list, it does not
     * decide whose debt this is, and it must not touch the amount — the notebook is what the money
     * comes from, and a catalogue price that disagrees with the paper is the catalogue being wrong
     * about this sale, not the paper.
     */
    fun setOperationDescription(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        description: String,
    ): List<LedgerOperation> = updateOperation(currentOperations, lineIndex) { op ->
        op.copy(description = description)
    }

    /**
     * Anchors a row to an existing customer, **and may only be called when a person accepted it**.
     *
     * The anchor is what decides whose debt this is, so a fuzzy match scoring 0.99 is not allowed to
     * call this on its own: showing a candidate on screen is not the same as somebody confirming it,
     * and a wrong anchor on a payment settles another person's real debt — which, in an append-only
     * ledger, is undone with a compensating movement and an awkward conversation.
     *
     * Because it is an explicit human confirmation, it does clear CUSTOMER from both lists, exactly
     * like editing the name by hand already does. The name read from the paper is kept untouched:
     * it is the evidence of what the notebook said, and the id is what the money follows.
     */
    fun setOperationCustomerAnchor(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        customerId: String,
        customerName: String? = null,
    ): List<LedgerOperation> {
        return updateOperation(currentOperations, lineIndex) { op ->
            val updatedUncertain = op.uncertainFields - UncertainLedgerField.CUSTOMER
            val updatedUnreadable = op.unreadableFields - UnreadableLedgerField.CUSTOMER
            op.copy(
                customerId = customerId,
                customerName = customerName ?: op.customerName,
                uncertainFields = updatedUncertain,
                unreadableFields = updatedUnreadable,
            )
        }
    }

    /**
     * Explicitly resolves an uncertain field for the operation at lineIndex.
     */
    fun resolveUncertainField(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
        field: UncertainLedgerField,
    ): List<LedgerOperation> {
        return updateOperation(currentOperations, lineIndex) { op ->
            op.copy(uncertainFields = op.uncertainFields - field)
        }
    }

    /**
     * Discards (removes) an operation from the review list.
     */
    fun removeOperation(
        currentOperations: List<LedgerOperation>,
        lineIndex: Int,
    ): List<LedgerOperation> {
        return currentOperations.filterNot { it.lineIndex == lineIndex }
    }
}

/**
 * Edits the user makes on the review, as pure list transformations.
 *
 * These live here and not inside the Composable on purpose: whether an edit resolves the doubt on a
 * row decides whether that row gets registered, and this project's rule is that a test must be able
 * to invoke that decision without a device. The screen calls these and draws the result.
 *
 * Filling a field always stores what the user typed. The doubt on that field is cleared **only when
 * the new value would actually be accepted**, so a row corrected with garbage keeps its mark and
 * still does not enter. That asymmetry is the point: the user can see their correction on screen
 * without it silently becoming registrable.
 */
object LedgerReviewEdits {

    private fun replace(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        edit: (LedgerOperation) -> LedgerOperation,
    ): List<LedgerOperation> = operations.map { if (it.lineIndex == lineIndex) edit(it) else it }

    /**
     * True when this amount would satisfy the same money rule the executors apply.
     *
     * Zero is canonical for the contract but the executors reject it, so a row corrected to "0"
     * keeps its mark: otherwise the doubt would look resolved on screen and the row would still be
     * refused at the end, with nothing pointing at the field that caused it.
     */
    fun isAcceptableAmount(amountCents: String?): Boolean =
        !amountCents.isNullOrEmpty() &&
            amountCents != "0" &&
            CANONICAL_LEDGER_CENTS_RE.matches(amountCents)

    /** True when this name would satisfy the same customer rule the executors apply. */
    fun isAcceptableCustomer(customerName: String?): Boolean =
        !customerName.isNullOrEmpty() &&
            customerName.length <= MAX_LEDGER_CUSTOMER_NAME_LENGTH &&
            trimJsWhitespace(customerName).isNotEmpty()

    fun applyAmount(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        amountCents: String,
    ): List<LedgerOperation> = replace(operations, lineIndex) { op ->
        val accepted = isAcceptableAmount(amountCents)
        op.copy(
            amountCents = amountCents,
            uncertainFields = if (accepted) op.uncertainFields - UncertainLedgerField.AMOUNT else op.uncertainFields,
            unreadableFields = if (accepted) op.unreadableFields - UnreadableLedgerField.AMOUNT else op.unreadableFields,
        )
    }

    fun applyCustomer(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        customerName: String,
    ): List<LedgerOperation> = replace(operations, lineIndex) { op ->
        val accepted = isAcceptableCustomer(customerName)
        op.copy(
            customerName = customerName,
            uncertainFields = if (accepted) op.uncertainFields - UncertainLedgerField.CUSTOMER else op.uncertainFields,
            unreadableFields = if (accepted) op.unreadableFields - UnreadableLedgerField.CUSTOMER else op.unreadableFields,
        )
    }

    /**
     * Choosing a kind resolves it by definition: the user picked one, so there is nothing left in
     * doubt. Gemini proposes, the user decides.
     */
    /**
     * True when this date could be a date written in a notebook: a real calendar day, not in the
     * future. Today is passed in rather than read: this layer has no clock, which is what lets it
     * be tested without a device.
     */
    fun isAcceptableDate(date: SafeDateCandidate?, todayEpochDay: Long): Boolean {
        if (date == null) return false
        val day = runCatching {
            java.time.LocalDate.of(date.year, date.month, date.day)
        }.getOrNull() ?: return false
        return day.toEpochDay() <= todayEpochDay
    }

    /**
     * A date the person typed while looking at the page. A date in the future is not a date from a
     * notebook, so it is stored but never resolves the doubt.
     */
    fun applyDate(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        date: SafeDateCandidate?,
        todayEpochDay: Long,
    ): List<LedgerOperation> = replace(operations, lineIndex) { op ->
        // A row the notebook simply did not date is legitimate: the contract makes date optional
        // and step 5 resolves it with the date chosen for the page. What is never legitimate is a
        // date that does not exist or has not happened yet.
        val accepted = date == null || isAcceptableDate(date, todayEpochDay)
        op.copy(
            date = date,
            // A rejected date does not merely fail to clear the mark: it adds one. Otherwise a row
            // whose date Gemini read cleanly could be edited to tomorrow, or emptied, and stay
            // confirmable because there was no doubt to preserve.
            uncertainFields = if (accepted) {
                op.uncertainFields - UncertainLedgerField.DATE
            } else {
                (op.uncertainFields + UncertainLedgerField.DATE).distinct()
            },
            unreadableFields = if (accepted) op.unreadableFields - UnreadableLedgerField.DATE else op.unreadableFields,
        )
    }

    fun applyKind(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        kind: ProposedLedgerKind,
    ): List<LedgerOperation> = replace(operations, lineIndex) { op ->
        op.copy(
            proposedKind = kind,
            uncertainFields = op.uncertainFields - UncertainLedgerField.KIND,
            unreadableFields = op.unreadableFields - UnreadableLedgerField.KIND,
        )
    }

    fun applyDescription(
        operations: List<LedgerOperation>,
        lineIndex: Int,
        description: String?,
    ): List<LedgerOperation> = replace(operations, lineIndex) { op ->
        op.copy(description = description)
    }

    /**
     * Explicit human confirmation for a row with active deterministic objections ("Sí, es correcto").
     * Clears the objection block so that the operation can become registerable and plannable.
     */
    fun confirmObjection(
        operations: List<LedgerOperation>,
        lineIndex: Int,
    ): List<LedgerOperation> = replace(operations, lineIndex) { LedgerObjectionConfirmation.confirming(it) }

    /**
     * Drops a row the user does not want to import. Nothing financial is being deleted here: this
     * is a draft that has not been written yet.
     */
    fun removeRow(
        operations: List<LedgerOperation>,
        lineIndex: Int,
    ): List<LedgerOperation> = operations.filterNot { it.lineIndex == lineIndex }
}
