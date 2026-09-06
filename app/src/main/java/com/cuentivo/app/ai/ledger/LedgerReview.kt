package com.cuentivo.app.ai.ledger

import com.cuentivo.app.domain.CustomerNameNormalizer
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
) {
    /**
     * Defensive copy to guarantee snapshot immutability regardless of caller list modifications.
     */
    fun deepCopy(): LedgerOperation = copy(
        uncertainFields = Collections.unmodifiableList(uncertainFields.toList()),
        unreadableFields = Collections.unmodifiableList(unreadableFields.toList()),
    )
}

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
    if (name.isNullOrEmpty() || name.length > MAX_LEDGER_CUSTOMER_NAME_LENGTH) {
        return false
    }
    val trimmedName = trimJsWhitespace(name)
    if (trimmedName.isEmpty()) {
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
) {
    /** Legacy alias for compatibility. */
    val totalCustomersCount: Int
        get() = visibleCustomersCount

    /** True if any operation has a blocking financial diagnostic. */
    val hasFinancialErrors: Boolean
        get() = financialDiagnostics.isNotEmpty()
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
)

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
    ): LedgerReview {
        // Defensive copy: isolate snapshot from any caller modifications
        val frozenOps: List<LedgerOperation> = Collections.unmodifiableList(
            operations.map { it.deepCopy() }
        )
        val frozenDiscards: List<LedgerDiscardDiagnostic> = Collections.unmodifiableList(
            discards.toList()
        )

        val frozenBalances: Map<String, Long> = Collections.unmodifiableMap(
            LinkedHashMap(existingCustomerBalances)
        )

        val linkedGroups = LinkedHashMap<String, MutableList<LedgerOperation>>()
        for (op in frozenOps) {
            val key = op.customerName?.let { CustomerNameNormalizer.key(it) } ?: ""
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
        )

        return LedgerReview(
            customerGroups = Collections.unmodifiableList(customerGroups.toList()),
            operations = frozenOps,
            discards = frozenDiscards,
            unreadTextRemaining = unreadTextRemaining,
            pageIncomplete = pageIncomplete,
            summary = summary,
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
