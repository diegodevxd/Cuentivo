package com.cuentivo.app.ai.ledger

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CALLABLE_NAME = "extractLedgerPage"
private const val REGION = "us-south1"
private const val CONTRACT_VERSION_FIELD = 4
private const val ACTION_VALUE = "extract_ledger_page"
private const val MAX_LEDGER_OPERATIONS_WIRE = 25

/** Exactly the keys contract v3 defines for an operation; anything else means we are not talking to it. */
private val OPERATION_ALLOWED_KEYS_V3 = setOf(
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

/** Keys contract v4 defines for an operation. */
private val OPERATION_ALLOWED_KEYS_V4 = OPERATION_ALLOWED_KEYS_V3 + setOf(
    "confidence",
    "sources",
    "rawText",
    "items",
    "warnings",
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

/** The page date the server read off the sheet, with how sure it was. Never applied on its own. */
data class LedgerDetectedPageDate(
    val date: SafeDateCandidate,
    val confidence: Double,
)

/** The operations the server read from the page, plus whether the page fitted. */
data class LedgerExtractionPayload(
    val operations: List<LedgerOperation>,
    val discards: List<LedgerDiscardDiagnostic>,
    val unreadTextRemaining: Boolean,
    val pageIncomplete: Boolean,
    /** Present only in v4 answers that carried a readable page date; null in v3 and in pages without one. */
    val detectedPageDate: LedgerDetectedPageDate? = null,
    /** Which contract version the server answered with, for the local import metrics. */
    val contractVersion: Int = 0,
)

/**
 * The request. Asks for v4 — the version this build understands in full — while the parser below
 * still accepts v3, so rolling the server back leaves the installed app reading pages instead of
 * mute. The image travels base64 encoded in the request body and is never written anywhere by this
 * class.
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
 * Strict v3 and v4 response parsing, in the same spirit as the v2 proposal parser: a known version,
 * a bounded key set, a matching request id, and money read only as a string. Anything unexpected
 * collapses to [LedgerExtractionCallResult.Unavailable] instead of being half understood, because a
 * page read wrong is worse than a page not read.
 *
 * v3 is still accepted on purpose. This build asks for v4, but a server rolled back to v3 must leave
 * the app working rather than silent.
 */
fun parseLedgerExtractionResponse(
    data: Any?,
    expectedRequestId: String,
): LedgerExtractionCallResult {
    val root = data as? Map<*, *> ?: return LedgerExtractionCallResult.Unavailable
    val versionNumber = root["contractVersion"] as? Number ?: return LedgerExtractionCallResult.Unavailable
    // toLong() alone would let 3.9 through as 3: the version has to be a whole number.
    if (versionNumber.toDouble() != versionNumber.toLong().toDouble()) {
        return LedgerExtractionCallResult.Unavailable
    }
    val version = versionNumber.toInt()
    if (version != 3 && version != 4) {
        return LedgerExtractionCallResult.Unavailable
    }

    val baseKeys = setOf(
        "contractVersion",
        "requestId",
        "status",
        "operations",
        "discards",
        "unreadTextRemaining",
        "pageIncomplete",
    )
    val rootKeys = root.keys.map { it.toString() }.toSet()
    if (version == 3) {
        if (rootKeys != baseKeys) return LedgerExtractionCallResult.Unavailable
    } else {
        val allowedV4Keys = baseKeys + "pageDate"
        if (!allowedV4Keys.containsAll(rootKeys) || !rootKeys.containsAll(baseKeys)) {
            return LedgerExtractionCallResult.Unavailable
        }
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
            val operations = parseLedgerOperationsWire(operationsValue, version)
                ?: return LedgerExtractionCallResult.Unavailable
            val discards = parseLedgerDiscardsWire(root["discards"])
                ?: return LedgerExtractionCallResult.Unavailable
            val incomplete = root["pageIncomplete"] as? Boolean
                ?: return LedgerExtractionCallResult.Unavailable
            val unread = root["unreadTextRemaining"] as? Boolean
                ?: return LedgerExtractionCallResult.Unavailable
            // The page date is read leniently on purpose, like confidence: a malformed one is
            // dropped, not fatal. The server already validated the shape before sending it, and
            // the client never applies this date on its own — the worst a bad value can do is not
            // be offered. Refusing the whole page over it would trade twenty-four readable rows
            // for an advisory field.
            val detectedPageDate = if (version == 4) parseDetectedPageDateWire(root["pageDate"]) else null
            LedgerExtractionCallResult.Extracted(
                LedgerExtractionPayload(
                    operations = operations,
                    discards = discards,
                    unreadTextRemaining = unread,
                    pageIncomplete = incomplete,
                    detectedPageDate = detectedPageDate,
                    contractVersion = version,
                ),
            )
        }
        else -> LedgerExtractionCallResult.Unavailable
    }
}

private fun parseLedgerOperationsWire(value: Any?, contractVersion: Int): List<LedgerOperation>? {
    val list = value as? List<*> ?: return null
    if (list.size > MAX_LEDGER_OPERATIONS_WIRE) return null
    val parsed = ArrayList<LedgerOperation>(list.size)
    val allowedKeys = if (contractVersion == 3) OPERATION_ALLOWED_KEYS_V3 else OPERATION_ALLOWED_KEYS_V4
    var lastLineIndex = -1
    for (item in list) {
        val map = item as? Map<*, *> ?: return null
        if (map.keys.any { it.toString() !in allowedKeys }) return null
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
        // The v4 extras are kept, not dropped. They were arriving and being thrown away, so the
        // screen could not tell a name read clearly from one inherited off a quote mark -- which is
        // a different thing to check, and the one more likely to be the wrong person.
        val items = parseItemsWire(map["items"]) ?: return null

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
            confidence = parseConfidenceWire(map["confidence"]),
            sources = parseSourcesWire(map["sources"]),
            // Bounded exactly like the server bounds them. The server is the authority, but this
            // parser is what decides how much ends up in a Bundle at save time, and a Bundle that
            // grows past the transaction limit does not degrade -- it kills the app. Anything over
            // the cap is refused rather than trimmed: a silently shortened rawText is evidence that
            // no longer matches the paper it came from.
            rawText = (map["rawText"] as? String)?.also { if (it.length > MAX_RAW_TEXT_WIRE) return null },
            items = items,
            warnings = parseWarningsWire(map["warnings"]) ?: return null,
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

/**
 * Reads the v4 root `pageDate`: `{ date: {year, month, day}, confidence }`.
 *
 * Strict about the shape (exactly those two keys, a real calendar day, a finite confidence in
 * 0..1) but lenient about presence: anything wrong here yields null, never a refused page. The
 * caller only ever offers this date for a person to accept, so a missing or malformed one simply
 * means there is nothing to offer.
 */
private fun parseDetectedPageDateWire(value: Any?): LedgerDetectedPageDate? {
    val map = value as? Map<*, *> ?: return null
    if (map.keys.map { it.toString() }.toSet() != setOf("date", "confidence")) return null
    val date = parseDateWire(map["date"]) ?: return null
    // A day that does not exist on any calendar is not a date somebody wrote in a notebook.
    val real = runCatching { java.time.LocalDate.of(date.year, date.month, date.day) }.getOrNull()
        ?: return null
    val confidence = (map["confidence"] as? Number)?.toDouble() ?: return null
    if (!confidence.isFinite() || confidence < 0.0 || confidence > 1.0) return null
    return LedgerDetectedPageDate(date = date, confidence = confidence)
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

/**
 * Certainty is read leniently on purpose: a malformed number is dropped, not fatal.
 *
 * The server already rejects out-of-range confidence before it gets here, and nothing downstream is
 * allowed to decide anything from these numbers. Refusing a whole page because one advisory field
 * came back odd would trade a page of real work for a cosmetic detail.
 */
private fun parseConfidenceWire(value: Any?): LedgerFieldConfidence? {
    val map = value as? Map<*, *> ?: return null
    fun read(key: String): Double? =
        (map[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val confidence = LedgerFieldConfidence(
        customer = read("customer"),
        concept = read("concept"),
        amount = read("amount"),
        date = read("date"),
        kind = read("kind"),
    )
    return confidence.takeIf {
        it.customer != null || it.concept != null || it.amount != null ||
            it.date != null || it.kind != null
    }
}

private fun parseSourcesWire(value: Any?): LedgerFieldSources? {
    val map = value as? Map<*, *> ?: return null
    fun read(key: String): LedgerFieldSource? =
        (map[key] as? String)?.let { name -> LedgerFieldSource.entries.firstOrNull { it.name == name } }
    val sources = LedgerFieldSources(customer = read("customer"), date = read("date"))
    return sources.takeIf { it.customer != null || it.date != null }
}

/**
 * Items are read strictly, unlike confidence, because they carry money.
 *
 * Null means the whole page is refused. A subtotal that arrived as a JSON number, or as anything
 * but a canonical centavo string, is the one shape through which fractional money could enter a
 * ledger of whole centavos -- the same rule the amount itself already follows.
 */
private fun parseItemsWire(value: Any?): List<LedgerLineItem>? {
    if (value == null) return emptyList()
    val list = value as? List<*> ?: return null
    if (list.size > MAX_ITEMS_WIRE) return null
    val parsed = ArrayList<LedgerLineItem>(list.size)
    for (raw in list) {
        val map = raw as? Map<*, *> ?: return null
        val description = map["description"] as? String ?: return null
        if (description.isBlank() || description.length > MAX_LEDGER_CUSTOMER_NAME_LENGTH) return null
        val quantity = when (val q = map["quantity"]) {
            null -> null
            is Number -> if (q.toDouble() != q.toLong().toDouble() || q.toLong() <= 0L) return null else q.toInt()
            else -> return null
        }
        val subtotal = when (val sub = map["subtotalCents"]) {
            null -> null
            is String -> if (!CANONICAL_LEDGER_CENTS_WIRE_RE.matches(sub)) return null else sub
            else -> return null
        }
        parsed += LedgerLineItem(description = description, quantity = quantity, subtotalCents = subtotal)
    }
    return parsed
}

private val CANONICAL_LEDGER_CENTS_WIRE_RE = Regex("""^(0|[1-9][0-9]{0,17})$""")

/** Same ceiling the server applies to the transcribed line. */
private const val MAX_RAW_TEXT_WIRE = 200

/** A line with more products than this is not a line any more; the server never sends one. */
private const val MAX_ITEMS_WIRE = 25

/** The closed enum has nine values, so a longer list is not a list of warnings. */
private const val MAX_WARNINGS_WIRE = 16

/** Warnings are advice, but an unbounded list of them still lands in the saved state. */
private fun parseWarningsWire(value: Any?): List<String>? {
    if (value == null) return emptyList()
    val list = value as? List<*> ?: return null
    if (list.size > MAX_WARNINGS_WIRE) return null
    val parsed = ArrayList<String>(list.size)
    for (raw in list) {
        val text = raw as? String ?: return null
        if (text.isEmpty() || text.length > 64) return null
        parsed += text
    }
    return parsed
}
