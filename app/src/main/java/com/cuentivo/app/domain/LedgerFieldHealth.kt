package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.FinancialRejectionReason
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.OperationFinancialDiagnostic
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.ai.ledger.isJsWhitespace
import com.cuentivo.app.ai.ledger.trimJsWhitespace

enum class FieldHealthColor {
    GREEN,   // Resolved and satisfies isRegisterableLedgerOperation for this field
    YELLOW,  // Marked uncertain by AI model
    RED,     // Unreadable, missing/invalid, or financial error
}

/**
 * Whether a field was read off this row or carried over from somewhere else.
 *
 * Kept apart from the colour because it answers a different question. A name inherited from a quote
 * mark can be read perfectly -- high certainty, green by every other measure -- and still belong to
 * the wrong person, because the quote mark is an assumption about the row above. The reviewer needs
 * to check a different thing, so the screen has to say a different thing.
 */
enum class FieldOrigin {
    READ_HERE,
    INHERITED_FROM_ROW_ABOVE,
    FROM_PAGE_DATE,
    INFERRED,
}

data class OperationFieldHealth(
    val lineIndex: Int,
    val customerHealth: FieldHealthColor,
    val amountHealth: FieldHealthColor,
    val kindHealth: FieldHealthColor,
    val dateHealth: FieldHealthColor,
    val customerOrigin: FieldOrigin = FieldOrigin.READ_HERE,
    val dateOrigin: FieldOrigin = FieldOrigin.READ_HERE,
    val objections: List<LedgerDeterministicObjection> = emptyList(),
) {
    val isAllGreen: Boolean
        get() = customerHealth == FieldHealthColor.GREEN &&
                amountHealth == FieldHealthColor.GREEN &&
                kindHealth == FieldHealthColor.GREEN &&
                dateHealth == FieldHealthColor.GREEN &&
                objections.isEmpty()

    val needsAttention: Boolean
        get() = !isAllGreen ||
            customerOrigin != FieldOrigin.READ_HERE ||
            dateOrigin != FieldOrigin.READ_HERE ||
            objections.isNotEmpty()
}

/**
 * Below this, a field the model says it read is still shown as worth a second look.
 *
 * The number is never shown to anybody: a shopkeeper does not need to be told 0.58. It only decides
 * whether the chip asks for attention.
 */
const val LOW_CONFIDENCE_THRESHOLD = 0.70

private val CANONICAL_CENTS_REGEX = Regex("^(0|[1-9][0-9]{0,17})$")
private const val MAX_CUSTOMER_NAME_LEN = 80

/**
 * Pure evaluator for operation field health (Fase 3 + v41 Deterministic Objections).
 *
 * Derives field colors directly from the validity conditions of [isRegisterableLedgerOperation]
 * and financial diagnostics. Objections can demote fields to YELLOW or RED.
 */
object LedgerFieldHealth {

    fun evaluate(
        op: LedgerOperation,
        financialDiagnostics: List<OperationFinancialDiagnostic>,
        pageDateEpochDay: Long?,
        todayEpochDay: Long?,
        objections: List<LedgerDeterministicObjection>,
    ): OperationFieldHealth {
        val lineDiags = financialDiagnostics.filter { it.lineIndex == op.lineIndex }
        val hasFinancialError = lineDiags.isNotEmpty()
        val rowObjections = objections.filter { it.lineIndex == op.lineIndex }

        // 1. Customer Health
        val baseCustomerHealth = when {
            op.unreadableFields.contains(UnreadableLedgerField.CUSTOMER) -> FieldHealthColor.RED
            op.uncertainFields.contains(UncertainLedgerField.CUSTOMER) -> FieldHealthColor.YELLOW
            else -> {
                val name = op.customerName
                val hasValidName = !name.isNullOrEmpty() &&
                        name.length <= MAX_CUSTOMER_NAME_LEN &&
                        trimJsWhitespace(name).isNotEmpty()
                val hasValidCustomerId = !op.customerId.isNullOrBlank()
                if (hasValidName || hasValidCustomerId) FieldHealthColor.GREEN else FieldHealthColor.RED
            }
        }

        // 2. Amount Health (financial diagnostics map to Amount RED)
        val baseAmountHealth = when {
            hasFinancialError -> FieldHealthColor.RED
            op.unreadableFields.contains(UnreadableLedgerField.AMOUNT) -> FieldHealthColor.RED
            op.uncertainFields.contains(UncertainLedgerField.AMOUNT) -> FieldHealthColor.YELLOW
            else -> {
                val cents = op.amountCents
                val isValidCents = !cents.isNullOrEmpty() && CANONICAL_CENTS_REGEX.matches(cents)
                if (isValidCents) FieldHealthColor.GREEN else FieldHealthColor.RED
            }
        }

        // 3. Kind Health
        val kindHealth = when {
            op.unreadableFields.contains(UnreadableLedgerField.KIND) -> FieldHealthColor.RED
            op.uncertainFields.contains(UncertainLedgerField.KIND) -> FieldHealthColor.YELLOW
            op.proposedKind == null -> FieldHealthColor.RED
            else -> FieldHealthColor.GREEN
        }

        // 4. Date Health
        var dateHealth = when {
            op.unreadableFields.contains(UnreadableLedgerField.DATE) -> FieldHealthColor.RED
            op.uncertainFields.contains(UncertainLedgerField.DATE) -> FieldHealthColor.YELLOW
            else -> {
                val hasValidRowDate = op.date != null
                val hasValidPageDate = pageDateEpochDay != null &&
                        pageDateEpochDay > 0 &&
                        (todayEpochDay == null || pageDateEpochDay <= todayEpochDay)
                if (hasValidRowDate || hasValidPageDate) FieldHealthColor.GREEN else FieldHealthColor.RED
            }
        }

        // Certainty can only ever make a field look WORSE, never better. It demotes a green to a
        // yellow -- "the model read this but would not swear to it" -- and that is all. It cannot
        // clear a doubt, cannot turn a red into anything, and cannot make a row registerable:
        // isRegisterableLedgerOperation does not look at it and must not start. One authority for
        // what gets written, a second opinion only for what gets looked at.
        fun demote(color: FieldHealthColor, certainty: Double?): FieldHealthColor =
            if (color == FieldHealthColor.GREEN && certainty != null && certainty < LOW_CONFIDENCE_THRESHOLD) {
                FieldHealthColor.YELLOW
            } else {
                color
            }

        var customerHealth = demote(baseCustomerHealth, op.confidence?.customer)
        var amountHealth = demote(baseAmountHealth, op.confidence?.amount)

        // Objections can only demote:
        // Customer confirmation demotes Customer to YELLOW
        if (rowObjections.any { it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED }) {
            if (customerHealth == FieldHealthColor.GREEN) {
                customerHealth = FieldHealthColor.YELLOW
            }
        }

        // Price discrepancy, decimal shift, or unusual cents demotes Amount to YELLOW
        if (rowObjections.any {
            it.type == LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT ||
                it.type == LedgerDeterministicObjectionType.PRICE_MISMATCH_SUSPECT ||
                it.type == LedgerDeterministicObjectionType.UNUSUAL_CENTS
        }) {
            if (amountHealth == FieldHealthColor.GREEN) {
                amountHealth = FieldHealthColor.YELLOW
            }
        }

        // An impossible date demotes the Date chip: the year (or the age) of this row needs
        // a second look before it becomes "overdue since three years ago" (v44).
        if (rowObjections.any { it.type == LedgerDeterministicObjectionType.IMPLAUSIBLE_DATE }) {
            if (dateHealth == FieldHealthColor.GREEN) {
                dateHealth = FieldHealthColor.YELLOW
            }
        }

        fun originOf(source: com.cuentivo.app.ai.ledger.LedgerFieldSource?): FieldOrigin = when (source) {
            com.cuentivo.app.ai.ledger.LedgerFieldSource.INHERITED_PREVIOUS_ROW -> FieldOrigin.INHERITED_FROM_ROW_ABOVE
            com.cuentivo.app.ai.ledger.LedgerFieldSource.PAGE_DATE -> FieldOrigin.FROM_PAGE_DATE
            com.cuentivo.app.ai.ledger.LedgerFieldSource.INFERRED -> FieldOrigin.INFERRED
            else -> FieldOrigin.READ_HERE
        }

        return OperationFieldHealth(
            lineIndex = op.lineIndex,
            customerHealth = customerHealth,
            amountHealth = amountHealth,
            kindHealth = demote(kindHealth, op.confidence?.kind),
            dateHealth = demote(dateHealth, op.confidence?.date),
            customerOrigin = originOf(op.sources?.customer),
            dateOrigin = originOf(op.sources?.date),
            objections = rowObjections,
        )
    }
}
