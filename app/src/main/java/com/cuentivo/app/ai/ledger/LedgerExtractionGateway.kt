package com.cuentivo.app.ai.ledger

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CALLABLE_NAME = "extractLedgerPage"
private const val REGION = "us-south1"
private const val CONTRACT_VERSION_FIELD = 3
private const val ACTION_VALUE = "extract_ledger_page"
private const val MAX_LEDGER_OPERATIONS_WIRE = 25

/** Exactly the keys contract v3 defines for an operation; anything else means we are not talking to it. */
private val OPERATION_ALLOWED_KEYS = setOf(
    "lineIndex",
    "proposedKind",
    "customerName",
    "amountCents",
    "description",
    "date",
    "uncertainFields",
    "unreadableFields",
    "region",
)

/** What the client is allowed to conclude from a page extraction attempt. */
sealed interface LedgerExtractionCallResult {
    data class Extracted(val extraction: LedgerExtractionPayload) : LedgerExtractionCallResult
    data object Unavailable : LedgerExtractionCallResult
    data object QuotaExhausted : LedgerExtractionCallResult
    data object RateLimited : LedgerExtractionCallResult
    data object Disabled : LedgerExtractionCallResult
    data object EntitlementRequired : LedgerExtractionCallResult
}

/** The operations the server read from the page, plus whether the page fitted. */
data class LedgerExtractionPayload(
    val operations: List<LedgerOperation>,
    val discards: List<LedgerDiscardDiagnostic>,
    val unreadTextRemaining: Boolean,
    val pageIncomplete: Boolean,
)

/**
 * Exact v3 payload. The image travels base64 encoded in the request body and is never written
 * anywhere by this class.
 */
internal fun buildLedgerCallablePayload(
    requestId: String,
    imageBase64: String,
    imageMimeType: String,
): Map<String, Any> = mapOf(
    "contractVersion" to CONTRACT_VERSION_FIELD,
    "requestId" to requestId,
    "action" to ACTION_VALUE,
    "imageBase64" to imageBase64,
    "imageMimeType" to imageMimeType,
)

/**
 * Strict v3 response parsing, in the same spirit as the v2 proposal parser: exact root keys, a
 * matching request id, and money read only as a string. Anything unexpected collapses to
 * [LedgerExtractionCallResult.Unavailable] instead of being half understood, because a page read
 * wrong is worse than a page not read.
 */
fun parseLedgerExtractionResponse(
    data: Any?,
    expectedRequestId: String,
): LedgerExtractionCallResult {
    val root = data as? Map<*, *> ?: return LedgerExtractionCallResult.Unavailable
    val expectedKeys = setOf(
        "contractVersion",
        "requestId",
        "status",
        "operations",
        "discards",
        "unreadTextRemaining",
        "pageIncomplete",
    )
    if (root.keys.map { it.toString() }.toSet() != expectedKeys) {
        return LedgerExtractionCallResult.Unavailable
    }
    // toLong() alone would let 3.9 through as 3: the version has to be the whole number 3.
    val version = root["contractVersion"] as? Number ?: return LedgerExtractionCallResult.Unavailable
    if (version.toDouble() != 3.0 || version.toLong() != 3L) {
        return LedgerExtractionCallResult.Unavailable
    }
    if (root["requestId"] != expectedRequestId) {
        return LedgerExtractionCallResult.Unavailable
    }
    val operationsValue = root["operations"]
    return when (root["status"]) {
        "AI_UNAVAILABLE" -> LedgerExtractionCallResult.Unavailable
        "AI_QUOTA_EXHAUSTED" ->
            if (operationsValue == null) LedgerExtractionCallResult.QuotaExhausted else LedgerExtractionCallResult.Unavailable
        "AI_RATE_LIMITED" ->
            if (operationsValue == null) LedgerExtractionCallResult.RateLimited else LedgerExtractionCallResult.Unavailable
        "AI_DISABLED" ->
            if (operationsValue == null) LedgerExtractionCallResult.Disabled else LedgerExtractionCallResult.Unavailable
        "AI_ENTITLEMENT_REQUIRED" ->
            if (operationsValue == null) LedgerExtractionCallResult.EntitlementRequired else LedgerExtractionCallResult.Unavailable
        "AI_PROPOSAL" -> {
            val operations = parseLedgerOperationsWire(operationsValue)
                ?: return LedgerExtractionCallResult.Unavailable
            val discards = parseLedgerDiscardsWire(root["discards"])
                ?: return LedgerExtractionCallResult.Unavailable
            val incomplete = root["pageIncomplete"] as? Boolean
                ?: return LedgerExtractionCallResult.Unavailable
            val unread = root["unreadTextRemaining"] as? Boolean
                ?: return LedgerExtractionCallResult.Unavailable
            LedgerExtractionCallResult.Extracted(
                LedgerExtractionPayload(
                    operations = operations,
                    discards = discards,
                    unreadTextRemaining = unread,
                    pageIncomplete = incomplete,
                ),
            )
        }
        else -> LedgerExtractionCallResult.Unavailable
    }
}

private fun parseLedgerOperationsWire(value: Any?): List<LedgerOperation>? {
    val list = value as? List<*> ?: return null
    if (list.size > MAX_LEDGER_OPERATIONS_WIRE) return null
    val parsed = ArrayList<LedgerOperation>(list.size)
    var lastLineIndex = -1
    for (item in list) {
        val map = item as? Map<*, *> ?: return null
        if (map.keys.any { it.toString() !in OPERATION_ALLOWED_KEYS }) return null
        val rawLineIndex = map["lineIndex"] as? Number ?: return null
        // Truncating here would merge two rows into one identity, and the review edits and deletes
        // rows by that identity.
        if (rawLineIndex.toDouble() != rawLineIndex.toLong().toDouble()) return null
        val lineIndex = rawLineIndex.toInt()
        if (lineIndex <= lastLineIndex) return null
        lastLineIndex = lineIndex
        // Money arrives as a string and only as a string: a JSON number here is exactly how
        // fractional money would enter a system of whole centavos.
        val amount = when (val raw = map["amountCents"]) {
            null -> null
            is String -> raw
            else -> return null
        }
        val kind = when (val raw = map["proposedKind"]) {
            null -> null
            is String -> ProposedLedgerKind.entries.firstOrNull { it.name == raw } ?: return null
            else -> return null
        }
        val uncertain = when (val raw = map["uncertainFields"]) {
            null -> emptyList()
            is List<*> -> raw.map { field ->
                UncertainLedgerField.entries.firstOrNull { it.name == field } ?: return null
            }
            else -> return null
        }
        val unreadable = when (val raw = map["unreadableFields"]) {
            null -> emptyList()
            is List<*> -> raw.map { field ->
                UnreadableLedgerField.entries.firstOrNull { it.name == field } ?: return null
            }
            else -> return null
        }
        parsed += LedgerOperation(
            lineIndex = lineIndex,
            proposedKind = kind,
            customerName = map["customerName"] as? String,
            amountCents = amount,
            description = map["description"] as? String,
            date = parseDateWire(map["date"]),
            uncertainFields = uncertain,
            unreadableFields = unreadable,
            region = parseRegionWire(map["region"]),
        )
    }
    return parsed
}

private fun parseDateWire(value: Any?): SafeDateCandidate? {
    val map = value as? Map<*, *> ?: return null
    val year = (map["year"] as? Number)?.toInt() ?: return null
    val month = (map["month"] as? Number)?.toInt() ?: return null
    val day = (map["day"] as? Number)?.toInt() ?: return null
    return SafeDateCandidate(year, month, day)
}

private fun parseRegionWire(value: Any?): LedgerRegion? {
    val map = value as? Map<*, *> ?: return null
    val x = (map["x"] as? Number)?.toDouble() ?: return null
    val y = (map["y"] as? Number)?.toDouble() ?: return null
    val width = (map["width"] as? Number)?.toDouble() ?: return null
    val height = (map["height"] as? Number)?.toDouble() ?: return null
    return LedgerRegion(x, y, width, height)
}

private fun parseLedgerDiscardsWire(value: Any?): List<LedgerDiscardDiagnostic>? {
    val list = value as? List<*> ?: return null
    val parsed = ArrayList<LedgerDiscardDiagnostic>(list.size)
    for (item in list) {
        val map = item as? Map<*, *> ?: return null
        val inputIndex = (map["inputIndex"] as? Number)?.toInt() ?: return null
        val reasonName = map["reason"] as? String ?: return null
        val reason = LedgerDiscardReason.entries.firstOrNull { it.name == reasonName } ?: return null
        parsed += LedgerDiscardDiagnostic(
            inputIndex = inputIndex,
            lineIndex = (map["lineIndex"] as? Number)?.toInt(),
            reason = reason,
        )
    }
    return parsed
}

/**
 * Calls the regional callable with the exact v3 payload. Every failure collapses to
 * [LedgerExtractionCallResult.Unavailable] without logging the image or anything read from it.
 */
class LedgerExtractionGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
) {
    suspend fun extractPage(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
    ): LedgerExtractionCallResult {
        val payload = buildLedgerCallablePayload(requestId, imageBase64, imageMimeType)
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(CALLABLE_NAME).call(payload)
                parseLedgerExtractionResponse(Tasks.await(task).data, requestId)
            }
        } catch (_: Exception) {
            LedgerExtractionCallResult.Unavailable
        }
    }
}
