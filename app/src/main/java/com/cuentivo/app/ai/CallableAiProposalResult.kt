package com.cuentivo.app.ai

/**
 * Outcome of the debug-only gateway transport for the v2 typed proposal contract.
 *
 * [Proposal] carries an [AiProposal] (never an [AiIntent]); [Unavailable] is the fixed,
 * non-executable result for every failure, unexpected shape or exception. No error messages,
 * ids, balances, totals or raw text are exposed. Nothing here writes, executes or persists.
 */
sealed interface CallableAiProposalResult {

    data class Proposal(val proposal: AiProposal) : CallableAiProposalResult

    data object Unavailable : CallableAiProposalResult

    data object QuotaExhausted : CallableAiProposalResult

    data object RateLimited : CallableAiProposalResult

    data object Disabled : CallableAiProposalResult

    data object EntitlementRequired : CallableAiProposalResult
}

/** Maximum raw UTF-16 display-name length, congruent with the server contract B1. */
internal const val MAX_WIRE_DISPLAY_NAME_LENGTH = 80

/** Maximum sale lines per single initial proposal, congruent with B1. */
internal const val MAX_WIRE_SALE_LINES = 8

private val CANONICAL_CENTS_REGEX = Regex("^(0|[1-9][0-9]{0,17})$")

/**
 * Strict parser for the v2 callable response (B1 wire contract).
 *
 * Root shape must be exactly `contractVersion=2`, the matching `requestId`, a known `status` and
 * the `proposal` field. `AI_UNAVAILABLE` and all rejection reason statuses require `proposal: null`;
 * `AI_PROPOSAL` requires a proposal whose wire shape matches exactly one allowed kind with exact keys,
 * canonical centavo STRINGS, positive integral quantities, raw-bounded display names, valid calendar
 * dates and the exact per-query-type selector shape. Duplicate slots fail closed (never canonicalized).
 * Every unknown status, malformed response or non-null proposal on rejection returns
 * [CallableAiProposalResult.Unavailable].
 */
fun parseCallableProposalResponse(
    data: Any?,
    expectedRequestId: String,
): CallableAiProposalResult {
    val root = data as? Map<*, *> ?: return CallableAiProposalResult.Unavailable
    if (root.keys.map { it.toString() }.toSet() !=
        setOf("contractVersion", "requestId", "status", "proposal")
    ) {
        return CallableAiProposalResult.Unavailable
    }
    if ((root["contractVersion"] as? Number)?.toLong() != 2L) {
        return CallableAiProposalResult.Unavailable
    }
    if (root["requestId"] != expectedRequestId) {
        return CallableAiProposalResult.Unavailable
    }
    val status = root["status"]
    val proposalValue = root["proposal"]
    return when (status) {
        "AI_UNAVAILABLE" -> CallableAiProposalResult.Unavailable
        "AI_QUOTA_EXHAUSTED" -> if (proposalValue == null) CallableAiProposalResult.QuotaExhausted else CallableAiProposalResult.Unavailable
        "AI_RATE_LIMITED" -> if (proposalValue == null) CallableAiProposalResult.RateLimited else CallableAiProposalResult.Unavailable
        "AI_DISABLED" -> if (proposalValue == null) CallableAiProposalResult.Disabled else CallableAiProposalResult.Unavailable
        "AI_ENTITLEMENT_REQUIRED" -> if (proposalValue == null) CallableAiProposalResult.EntitlementRequired else CallableAiProposalResult.Unavailable
        "AI_PROPOSAL" -> {
            val proposal = parseProposalWire(proposalValue)
            if (proposal === null) {
                CallableAiProposalResult.Unavailable
            } else {
                CallableAiProposalResult.Proposal(proposal)
            }
        }
        else -> CallableAiProposalResult.Unavailable
    }
}

private fun parseProposalWire(value: Any?): AiProposal? {
    val map = value as? Map<*, *> ?: return null
    val keys = map.keys.map { it.toString() }.toSet()
    val kind = map["kind"] as? String ?: return null
    return when (kind) {
        "requires_clarification" -> parseClarification(map, keys)
        "create_sale" -> parseCreateSale(map, keys)
        "record_payment" -> parseRecordPayment(map, keys)
        "business_query" -> parseBusinessQuery(map)
        else -> null
    }
}

private fun rawDisplayName(value: Any?): String? {
    val name = value as? String ?: return null
    if (name.length < 1 || name.length > MAX_WIRE_DISPLAY_NAME_LENGTH) {
        return null
    }
    if (name.trim().isEmpty()) {
        return null
    }
    return name
}

private fun integralInt(value: Any?, min: Int, max: Int): Int? {
    return when (value) {
        is Int -> if (value in min..max) value else null
        is Short -> value.toInt().takeIf { it in min..max }
        is Byte -> value.toInt().takeIf { it in min..max }
        is Long -> if (value in min.toLong()..max.toLong()) value.toInt() else null
        is Float -> {
            if (!value.isFinite()) {
                return null
            }
            val asLong = value.toLong()
            if (value == asLong.toFloat() && asLong in min.toLong()..max.toLong()) asLong.toInt() else null
        }
        is Double -> {
            if (!value.isFinite()) {
                return null
            }
            val asLong = value.toLong()
            if (value == asLong.toDouble() && asLong in min.toLong()..max.toLong()) asLong.toInt() else null
        }
        else -> null
    }
}

private fun canonicalCentsToLong(value: Any?): Long? {
    val text = value as? String ?: return null
    if (!CANONICAL_CENTS_REGEX.matches(text)) {
        return null
    }
    return text.toLongOrNull()
}

private fun isCalendarDate(year: Int, month: Int, day: Int): Boolean {
    if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1) {
        return false
    }
    val daysInMonth = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 0
    }
    return day <= daysInMonth
}

private fun parseDateCandidate(map: Map<*, *>): PromisedPaymentCandidate.DateCandidate? {
    if (map.keys.map { it.toString() }.toSet() != setOf("year", "month", "day")) {
        return null
    }
    val year = integralInt(map["year"], 1, 9999) ?: return null
    val month = integralInt(map["month"], 1, 12) ?: return null
    val day = integralInt(map["day"], 1, 31) ?: return null
    if (!isCalendarDate(year, month, day)) {
        return null
    }
    return PromisedPaymentCandidate.DateCandidate(year, month, day)
}

private fun parsePeriodCandidate(map: Map<*, *>): AiPeriodCandidate? {
    if (map.keys.map { it.toString() }.toSet() !=
        setOf("startYear", "startMonth", "startDay", "endYear", "endMonth", "endDay")
    ) {
        return null
    }
    val startYear = integralInt(map["startYear"], 1, 9999) ?: return null
    val startMonth = integralInt(map["startMonth"], 1, 12) ?: return null
    val startDay = integralInt(map["startDay"], 1, 31) ?: return null
    val endYear = integralInt(map["endYear"], 1, 9999) ?: return null
    val endMonth = integralInt(map["endMonth"], 1, 12) ?: return null
    val endDay = integralInt(map["endDay"], 1, 31) ?: return null
    if (!isCalendarDate(startYear, startMonth, startDay) ||
        !isCalendarDate(endYear, endMonth, endDay)
    ) {
        return null
    }
    if (startYear > endYear ||
        (startYear == endYear && startMonth > endMonth) ||
        (startYear == endYear && startMonth == endMonth && startDay > endDay)
    ) {
        return null
    }
    return AiPeriodCandidate(startYear, startMonth, startDay, endYear, endMonth, endDay)
}

private fun parseClarification(
    map: Map<*, *>,
    keys: Set<String>,
): AiProposal? {
    if (keys != setOf("kind", "reason", "unresolvedSlots")) {
        return null
    }
    val reason = when (val raw = map["reason"] as? String) {
        "AMBIGUOUS_CUSTOMER" -> ClarificationReason.AMBIGUOUS_CUSTOMER
        "AMBIGUOUS_PRODUCT" -> ClarificationReason.AMBIGUOUS_PRODUCT
        "MISSING_CUSTOMER" -> ClarificationReason.MISSING_CUSTOMER
        "MISSING_PRODUCT" -> ClarificationReason.MISSING_PRODUCT
        "MISSING_AMOUNT" -> ClarificationReason.MISSING_AMOUNT
        "UNKNOWN_INPUT" -> ClarificationReason.UNKNOWN_INPUT
        else -> return null
    }
    val rawSlots = map["unresolvedSlots"] as? List<*> ?: return null
    if (rawSlots.isEmpty()) {
        return null
    }
    val slots = mutableSetOf<UnresolvedSlot>()
    for (raw in rawSlots) {
        val slot = when (raw as? String) {
            "CUSTOMER" -> UnresolvedSlot.CUSTOMER
            "PRODUCT" -> UnresolvedSlot.PRODUCT
            "AMOUNT" -> UnresolvedSlot.AMOUNT
            "QUANTITY" -> UnresolvedSlot.QUANTITY
            "PERIOD" -> UnresolvedSlot.PERIOD
            else -> return null
        }
        if (!slots.add(slot)) {
            return null // duplicates fail closed, never canonicalized by the transport parser
        }
    }
    return RequiresClarificationProposal(reason, slots)
}

private fun parseSaleLine(value: Any?): AiProposalSaleLine? {
    val map = value as? Map<*, *> ?: return null
    if (map.keys.map { it.toString() }.toSet() != setOf("productName", "quantity", "unitPriceCents")) {
        return null
    }
    val productName = rawDisplayName(map["productName"]) ?: return null
    val quantity = integralInt(map["quantity"], 1, Int.MAX_VALUE) ?: return null
    val unitPriceCents = canonicalCentsToLong(map["unitPriceCents"]) ?: return null
    return AiProposalSaleLine(productName, quantity, unitPriceCents)
}

private fun parseCreateSale(
    map: Map<*, *>,
    keys: Set<String>,
): AiProposal? {
    val allowed = setOf("kind", "customerName", "lines", "declaredInitialPaymentCents", "promisedPaymentDate")
    if (!keys.all { it in allowed } ||
        !allowed.containsAll(setOf("customerName", "lines", "declaredInitialPaymentCents"))
    ) {
        return null
    }
    val customerName = rawDisplayName(map["customerName"]) ?: return null
    val rawLines = map["lines"] as? List<*> ?: return null
    if (rawLines.isEmpty() || rawLines.size > MAX_WIRE_SALE_LINES) {
        return null
    }
    val lines = rawLines.map { parseSaleLine(it) ?: return null }
    val declaredCents = canonicalCentsToLong(map["declaredInitialPaymentCents"]) ?: return null
    val promisedMap = map["promisedPaymentDate"]
    val promised = if (promisedMap === null) {
        null
    } else {
        parseDateCandidate(promisedMap as? Map<*, *> ?: return null) ?: return null
    }
    return CreateSaleProposal(customerName, lines, declaredCents, promised)
}

private fun parseRecordPayment(
    map: Map<*, *>,
    keys: Set<String>,
): AiProposal? {
    if (keys != setOf("kind", "customerName", "declaredAmountCents")) {
        return null
    }
    val customerName = rawDisplayName(map["customerName"]) ?: return null
    val declaredCents = canonicalCentsToLong(map["declaredAmountCents"]) ?: return null
    return RecordPaymentProposal(customerName, declaredCents)
}

private fun parseBusinessQuery(map: Map<*, *>): AiProposal? {
    val keys = map.keys.map { it.toString() }.toSet()
    val queryType = when (val raw = map["queryType"] as? String) {
        "CUSTOMER_BALANCE" -> BusinessQueryType.CUSTOMER_BALANCE
        "CUSTOMERS_WITH_DEBT" -> BusinessQueryType.CUSTOMERS_WITH_DEBT
        "SALES_FOR_PERIOD" -> BusinessQueryType.SALES_FOR_PERIOD
        else -> return null
    }
    return when (queryType) {
        BusinessQueryType.CUSTOMER_BALANCE -> {
            if (keys != setOf("kind", "queryType", "customerName")) {
                return null
            }
            val customerName = rawDisplayName(map["customerName"]) ?: return null
            BusinessQueryProposal(BusinessQueryType.CUSTOMER_BALANCE, customerName = customerName)
        }
        BusinessQueryType.CUSTOMERS_WITH_DEBT -> {
            if (keys != setOf("kind", "queryType")) {
                return null
            }
            BusinessQueryProposal(BusinessQueryType.CUSTOMERS_WITH_DEBT)
        }
        BusinessQueryType.SALES_FOR_PERIOD -> {
            if (keys != setOf("kind", "queryType", "period")) {
                return null
            }
            val period = parsePeriodCandidate(map["period"] as? Map<*, *> ?: return null)
                ?: return null
            BusinessQueryProposal(BusinessQueryType.SALES_FOR_PERIOD, period = period)
        }
    }
}
