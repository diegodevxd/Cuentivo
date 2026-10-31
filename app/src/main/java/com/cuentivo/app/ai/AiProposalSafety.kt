package com.cuentivo.app.ai

/** Outcome of [SafeAiProposalValidator.validate]. No execute, confirm, DAO or persistence hooks. */
sealed interface AiProposalValidationResult {

    /** Proposal passed every check and may be shown to a human for preview. */
    data class Accepted(val proposal: AiProposal) : AiProposalValidationResult

    /** The proposal is invalid or outside the allow-list: never preview-ready. */
    data class Rejected(val reason: AiProposalRejectReason) : AiProposalValidationResult
}

/** Exact machine-readable reason for a rejection. */
enum class AiProposalRejectReason {
    UNSUPPORTED_SCHEMA_VERSION,
    BLANK_CUSTOMER,
    EMPTY_LINES,
    INVALID_LINE,
    NEGATIVE_AMOUNT,
    NEGATIVE_QUANTITY,
    INVALID_DATE,
    INVALID_QUERY_SHAPE,
    EMPTY_SLOTS,
}

/**
 * Pure, fail-closed, local safety gate for versioned [AiProposal]s.
 *
 * Accepts only deterministic candidates: nonblank display names, non-empty valid sale lines
 * (positive whole quantities, never-negative centavo amounts), non-negative declared payments
 * (zero allowed), valid structured dates, allow-listed query shapes and non-empty clarification
 * slots. It NEVER computes totals, balances, allocations or comparisons against declared amounts,
 * never writes/executes/persists, and never imports Room, Firestore, DAOs or SQL.
 */
object SafeAiProposalValidator {

    fun validate(proposal: AiProposal): AiProposalValidationResult {
        if (proposal.schemaVersion != 1) {
            return AiProposalValidationResult.Rejected(AiProposalRejectReason.UNSUPPORTED_SCHEMA_VERSION)
        }
        return when (proposal) {
            is CreateSaleProposal -> validateSale(proposal)
            is RecordPaymentProposal -> validatePayment(proposal)
            is BusinessQueryProposal -> validateQuery(proposal)
            is RequiresClarificationProposal -> validateClarification(proposal)
        }
    }

    private fun validateSale(
        proposal: CreateSaleProposal,
    ): AiProposalValidationResult {
        if (proposal.customerName.isBlank()) {
            return reject(AiProposalRejectReason.BLANK_CUSTOMER)
        }
        if (proposal.lines.isEmpty()) {
            return reject(AiProposalRejectReason.EMPTY_LINES)
        }
        if (proposal.declaredInitialPaymentCents < 0L) {
            return reject(AiProposalRejectReason.NEGATIVE_AMOUNT)
        }
        for (line in proposal.lines) {
            if (line.productName.isBlank()) {
                return reject(AiProposalRejectReason.INVALID_LINE)
            }
            if (line.quantity <= 0) {
                return reject(AiProposalRejectReason.NEGATIVE_QUANTITY)
            }
            if (line.unitPriceCents < 0L) {
                return reject(AiProposalRejectReason.NEGATIVE_AMOUNT)
            }
        }
        val promise = proposal.promisedPaymentCandidate
        if (promise is PromisedPaymentCandidate.DateCandidate &&
            !isValidDate(promise.year, promise.month, promise.day)
        ) {
            return reject(AiProposalRejectReason.INVALID_DATE)
        }
        return AiProposalValidationResult.Accepted(proposal)
    }

    private fun validatePayment(
        proposal: RecordPaymentProposal,
    ): AiProposalValidationResult {
        if (proposal.customerName.isBlank()) {
            return reject(AiProposalRejectReason.BLANK_CUSTOMER)
        }
        if (proposal.declaredAmountCents < 0L) {
            return reject(AiProposalRejectReason.NEGATIVE_AMOUNT)
        }
        return AiProposalValidationResult.Accepted(proposal)
    }

    private fun validateQuery(
        proposal: BusinessQueryProposal,
    ): AiProposalValidationResult {
        val shapeOk = when (proposal.queryType) {
            BusinessQueryType.CUSTOMER_BALANCE ->
                proposal.customerName != null &&
                    proposal.customerName.isNotBlank() &&
                    proposal.period == null

            BusinessQueryType.CUSTOMERS_WITH_DEBT ->
                proposal.customerName == null && proposal.period == null

            BusinessQueryType.SALES_FOR_PERIOD ->
                proposal.customerName == null &&
                    proposal.period != null &&
                    isValidPeriod(proposal.period)
        }
        return if (shapeOk) {
            AiProposalValidationResult.Accepted(proposal)
        } else {
            reject(AiProposalRejectReason.INVALID_QUERY_SHAPE)
        }
    }

    private fun validateClarification(
        proposal: RequiresClarificationProposal,
    ): AiProposalValidationResult {
        if (proposal.unresolvedSlots.isEmpty()) {
            return reject(AiProposalRejectReason.EMPTY_SLOTS)
        }
        return AiProposalValidationResult.Accepted(proposal)
    }

    private fun reject(reason: AiProposalRejectReason): AiProposalValidationResult =
        AiProposalValidationResult.Rejected(reason)

    // Deterministic calendar arithmetic only; no heuristics, no platform APIs.
    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean =
        year >= 1 && month in 1..12 && day in 1..daysInMonth(year, month)

    private fun isValidPeriod(period: AiPeriodCandidate): Boolean {
        if (!isValidDate(period.startYear, period.startMonth, period.startDay) ||
            !isValidDate(period.endYear, period.endMonth, period.endDay)
        ) {
            return false
        }
        // Lexicographic (year, month, day) comparison of raw Ints: no multiplication is used, so
        // large years near Int.MAX_VALUE can never overflow or invert the ordering.
        return compareDateComponents(
            period.startYear,
            period.startMonth,
            period.startDay,
            period.endYear,
            period.endMonth,
            period.endDay,
        ) <= 0
    }

    private fun compareDateComponents(
        y1: Int,
        m1: Int,
        d1: Int,
        y2: Int,
        m2: Int,
        d2: Int,
    ): Int = when {
        y1 != y2 -> y1.compareTo(y2)
        m1 != m2 -> m1.compareTo(m2)
        else -> d1.compareTo(d2)
    }
}
