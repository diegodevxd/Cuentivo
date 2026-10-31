package com.cuentivo.app.ai.ledger

import com.cuentivo.app.FUNCTIONS_REGION
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CALLABLE_NAME = "extractLedgerPage"
private const val CONTRACT_VERSION_FIELD = 5
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
private val OPERATION_ALLOWED_KEYS_V5 = OPERATION_ALLOWED_KEYS_V4 + "marginNote"

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
    val pageType: LedgerPageType? = null,
    val pageTotals: List<LedgerPageTotal> = emptyList(),
    /** Which contract version the server answered with, for the local import metrics. */
    val contractVersion: Int = 0,
    /**
     * The optional cross-check against the Ledger de MexaLM, when the server sent one.
     *
     * `null` in every answer that does not carry it: a server with the feature flag off sends the
     * page exactly as it always did, and a block that arrived malformed is dropped whole rather
     * than half understood. This is a comment next to the page — it changes nothing about what
     * gets registered.
     */
    val mexalmCrosscheck: MexalmCrosscheckPayload? = null,
)

/**
 * The request. Asks for v4 — the version this build understands in full — while the parser below
 * still accepts v3, so rolling the server back leaves the installed app reading pages instead of
 * mute. The image travels base64 encoded in the request body and is never written anywhere by this
 * class.
 *
 * `acceptsMexalmCrosscheck` es una CAPACIDAD, no un gusto: dice que este build sabe leer el bloque
 * `mexalmCrosscheck` sin rechazar la página. La app ≤ v48 no lo sabe —compara el juego de llaves de
 * la raíz y ante una llave desconocida tumba la página entera—, así que el servidor sólo manda el
 * bloque a quien lo pide. Mandarlo sin pedirlo no habría roto un adorno: habría dejado mudo el
 * escaneo de todos los clientes ya publicados.
 *
 * Va SIEMPRE a `true` y no depende de ninguna bandera local: quien decide si hay verificación es el
 * servidor. Este lado sólo declara lo que puede leer.
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
    "acceptsMexalmCrosscheck" to true,
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
    if (version !in setOf(3, 4, 5)) {
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
        // v3 demands the EXACT key set, so a v3 answer carrying `mexalmCrosscheck` is refused as a
        // whole page. That is deliberate and it is the only version where it happens: v3 is the
        // contract of the published builds that predate the block, they ask for v3 themselves, and
        // this app never does (it asks for v5 and retries with v4). The block is optional in v4/v5,
        // where its absence is the normal case.
        if (rootKeys != baseKeys) return LedgerExtractionCallResult.Unavailable
    } else {
        // `mexalmCrosscheck` is OPTIONAL: it is only there when the server compared the page with
        // the Ledger de MexaLM, and its absence is the answer of every server with the flag off.
        val allowedKeys = baseKeys + setOf("pageDate", "mexalmCrosscheck") +
            if (version == 5) setOf("pageType", "pageTotals") else emptySet()
        if (!allowedKeys.containsAll(rootKeys) || !rootKeys.containsAll(baseKeys)) {
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
            val detectedPageDate = if (version >= 4) parseDetectedPageDateWire(root["pageDate"]) else null
            // The cross-check block is read the same way the page date is read: strictly, but
            // leniently about its PRESENCE. A block that is malformed collapses to null and the
            // page is read anyway, because the block is a comment next to the page and a bad
            // comment must never cost the page.
            val mexalmCrosscheck =
                if (version >= 4) parseMexalmCrosscheckWire(root["mexalmCrosscheck"]) else null
            LedgerExtractionCallResult.Extracted(
                LedgerExtractionPayload(
                    operations = operations,
                    discards = discards,
                    unreadTextRemaining = unread,
                    pageIncomplete = incomplete,
                    detectedPageDate = detectedPageDate,
                    contractVersion = version,
                    pageType = if (version == 5) parsePageTypeWire(root["pageType"]) else null,
                    pageTotals = if (version == 5) parsePageTotalsWire(root["pageTotals"], operations.map { it.lineIndex }.toSet()) else emptyList(),
                    mexalmCrosscheck = mexalmCrosscheck,
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
    val allowedKeys = when (contractVersion) { 3 -> OPERATION_ALLOWED_KEYS_V3; 4 -> OPERATION_ALLOWED_KEYS_V4; else -> OPERATION_ALLOWED_KEYS_V5 }
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
            marginNote = if (contractVersion == 5) parseMarginNoteWire(map["marginNote"]) else null,
        )
    }
    return parsed
}

private fun parsePageTypeWire(value: Any?): LedgerPageType? = when (value as? String) {
    "ACCOUNTS" -> LedgerPageType.ACCOUNTS
    "COST_OR_PRICE_LIST" -> LedgerPageType.COST_OR_PRICE_LIST
    "OTHER" -> LedgerPageType.OTHER
    else -> null
}

private fun parseMarginNoteWire(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() && it.length <= 40 }

private fun parsePageTotalsWire(value: Any?, lineIndices: Set<Int>): List<LedgerPageTotal> {
    val list = value as? List<*> ?: return emptyList()
    if (list.size > 10) return emptyList()
    val used = mutableSetOf<Int>()
    return list.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        val label = map["label"] as? String ?: return@mapNotNull null
        if (label.isBlank() || label.length > 40) return@mapNotNull null
        val amount = map["amountCents"] as? String ?: return@mapNotNull null
        if (!CANONICAL_LEDGER_CENTS_WIRE_RE.matches(amount)) return@mapNotNull null
        val after = map["afterLineIndex"]?.let { (it as? Number)?.toInt() ?: return@mapNotNull null }
        if (after != null && (after !in lineIndices || !used.add(after))) return@mapNotNull null
        LedgerPageTotal(label, amount.toLong(), after)
    }
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

/** The exact keys the cross-check block defines. One key more and it is not this block. */
private val CROSSCHECK_ROOT_KEYS = setOf("agreed", "disagreements", "ledgerStatus", "latencyMs")
private val CROSSCHECK_DISAGREEMENT_KEYS = setOf("lineIndex", "field", "gemini", "ledger")
private val CROSSCHECK_SUMMARY_KEYS = setOf("proposedKind", "customerName", "amountCents")

/**
 * The keys the cross-check block MAY carry in addition to its base four.
 *
 * `confirmedLineIndexes` is the list of rows the Ledger confirmed. It is optional because an
 * older server does not send it, and its absence means "no rows were confirmed" — never "all of
 * them were". A block with any other extra key is not this block and collapses to null.
 */
private val CROSSCHECK_OPTIONAL_ROOT_KEYS = setOf("confirmedLineIndexes")

/**
 * Reads the optional cross-check block: the server's comparison between the page that was read and
 * the Ledger de MexaLM reading the same text, row by row.
 *
 * Strict about the SHAPE, lenient about the PRESENCE, and strict in the direction that loses
 * information instead of gaining it: anything unexpected collapses the WHOLE BLOCK to null and the
 * page is read anyway. The block is a comment next to the page, so a malformed comment must never
 * cost the page — and the page is what the person came for.
 *
 * The money is read as whole centavos and only as whole centavos. A summary whose amount arrives
 * as a JSON number is exactly how fractional money would enter a system of whole centavos, so the
 * block is dropped rather than the number truncated.
 */
private fun parseMexalmCrosscheckWire(value: Any?): MexalmCrosscheckPayload? {
    if (value == null) return null
    val map = value as? Map<*, *> ?: return null
    val keys = map.keys.map { it.toString() }.toSet()
    // The four base keys have to be there, and the only extra key allowed is the list of
    // confirmed rows. Anything else is not this block.
    if (!keys.containsAll(CROSSCHECK_ROOT_KEYS)) return null
    if (!CROSSCHECK_OPTIONAL_ROOT_KEYS.containsAll(keys - CROSSCHECK_ROOT_KEYS)) return null
    val agreed = map["agreed"] as? Boolean ?: return null
    val ledgerStatus = map["ledgerStatus"] as? String ?: return null
    if (ledgerStatus.isBlank() || ledgerStatus.length > MAX_LEDGER_STATUS_WIRE) return null
    val latency = (map["latencyMs"] as? Number) ?: return null
    // A fractional latency is not a latency: truncating it would report a time nobody measured.
    if (latency.toDouble() != latency.toLong().toDouble()) return null
    val latencyMs = latency.toLong()
    if (latencyMs < 0L || latencyMs > MAX_CROSSCHECK_LATENCY_WIRE) return null
    val disagreements = parseCrosscheckDisagreementsWire(map["disagreements"]) ?: return null
    // `agreed` and the differences have to agree with each other. Saying "both sides say the same"
    // while listing differences would show a person "de acuerdo" on a page that carries badges,
    // and there is no way to tell which of the two the server meant.
    if (agreed && disagreements.isNotEmpty()) return null
    val confirmedLineIndices = parseConfirmedLineIndexesWire(map["confirmedLineIndexes"]) ?: return null
    return MexalmCrosscheckPayload(
        agreed = agreed,
        disagreements = disagreements,
        ledgerStatus = ledgerStatus,
        latencyMs = latencyMs,
        confirmedLineIndices = confirmedLineIndices,
    )
}

/**
 * The list of rows the Ledger confirmed.
 *
 * Absent means "none confirmed". A value that is not a list of whole, non-negative line indexes
 * is not that list, and a list that names a row twice or names a negative row is not one either:
 * the block collapses rather than be read half way.
 */
private fun parseConfirmedLineIndexesWire(value: Any?): Set<Int>? {
    if (value == null) return emptySet()
    val list = value as? List<*> ?: return null
    if (list.size > MAX_CROSSCHECK_CONFIRMED_WIRE) return null
    val parsed = LinkedHashSet<Int>(list.size)
    for (item in list) {
        val raw = item as? Number ?: return null
        if (raw.toDouble() != raw.toLong().toDouble()) return null
        val lineIndex = raw.toLong()
        if (lineIndex < 0L || lineIndex > Int.MAX_VALUE.toLong()) return null
        parsed += lineIndex.toInt()
    }
    return parsed
}

private fun parseCrosscheckDisagreementsWire(value: Any?): List<MexalmCrosscheckDisagreement>? {
    val list = value as? List<*> ?: return null
    if (list.size > MAX_CROSSCHECK_DISAGREEMENTS_WIRE) return null
    val parsed = ArrayList<MexalmCrosscheckDisagreement>(list.size)
    for (raw in list) {
        val map = raw as? Map<*, *> ?: return null
        val keys = map.keys.map { it.toString() }.toSet()
        if (!keys.containsAll(CROSSCHECK_DISAGREEMENT_REQUIRED_KEYS)) return null
        if (!CROSSCHECK_DISAGREEMENT_KEYS.containsAll(keys)) return null
        val rawLineIndex = map["lineIndex"] as? Number ?: return null
        // The line index is the identity of the row. A fractional one would name a row that does
        // not exist, and the badge would land nowhere.
        if (rawLineIndex.toDouble() != rawLineIndex.toLong().toDouble()) return null
        val lineIndexLong = rawLineIndex.toLong()
        // And one that does not fit in an Int would be truncated into a DIFFERENT row: the same
        // mistake as truncar un `lineIndex` de una operacion.
        if (lineIndexLong > Int.MAX_VALUE.toLong() || lineIndexLong < Int.MIN_VALUE.toLong()) return null
        val lineIndex = lineIndexLong.toInt()
        val field = MexalmCrosscheckDisagreementField.entries.firstOrNull { it.name == map["field"] }
            ?: return null
        // La ausencia del resumen de un lado es una cosa, y un resumen que no se puede leer es
        // otra. Si los dos dieran `null`, un monto que llega como número JSON se tomaría por "este
        // lado no dijo nada" y el bloque se leería a medias. Aquí la diferencia se nota: lo que
        // no se puede leer tumba el bloque entero.
        val geminiRaw = map["gemini"]
        val gemini = if (geminiRaw == null) {
            null
        } else {
            parseCrosscheckSummaryWire(geminiRaw) ?: return null
        }
        val ledgerRaw = map["ledger"]
        val ledger = if (ledgerRaw == null) {
            null
        } else {
            parseCrosscheckSummaryWire(ledgerRaw) ?: return null
        }
        parsed += MexalmCrosscheckDisagreement(
            lineIndex = lineIndex,
            field = field,
            gemini = gemini,
            ledger = ledger,
        )
    }
    return parsed
}

/**
 * What ONE side wrote about a row. The text of the page never arrives here, and neither does
 * anything that is not a number of cents, a name or a kind.
 */
private fun parseCrosscheckSummaryWire(value: Any?): MexalmCrosscheckOperationSummary? {
    if (value == null) return null
    val map = value as? Map<*, *> ?: return null
    if (!CROSSCHECK_SUMMARY_KEYS.containsAll(map.keys.map { it.toString() }.toSet())) return null
    val proposedKind = map["proposedKind"]?.let { it as? String ?: return null }
    val customerName = map["customerName"]?.let { it as? String ?: return null }
    if (proposedKind != null && proposedKind.length > MAX_CROSSCHECK_KIND_WIRE) return null
    if (customerName != null && customerName.length > MAX_CROSSCHECK_CUSTOMER_NAME_WIRE) return null
    val amountCents = map["amountCents"]?.let { raw ->
        val text = raw as? String ?: return null
        if (!CANONICAL_LEDGER_CENTS_WIRE_RE.matches(text)) return null
        text.toLong()
    }
    return MexalmCrosscheckOperationSummary(
        proposedKind = proposedKind,
        customerName = customerName,
        amountCents = amountCents,
    )
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
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) {
    suspend fun extractPage(
        requestId: String,
        imageBase64: String,
        imageMimeType: String,
    ): LedgerExtractionCallResult =
        extractPageWithFallback { version -> callRemote(requestId, imageBase64, imageMimeType, version) }

    private suspend fun callRemote(requestId: String, imageBase64: String, imageMimeType: String, version: Int): LedgerExtractionCallResult {
        val payload = buildLedgerCallablePayload(requestId, imageBase64, imageMimeType).toMutableMap().apply { put("contractVersion", version) }
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
 * Pide la página con el contrato v5 y, si esa versión no contesta, **reintenta UNA sola vez con la 4**.
 *
 * Un despliegue llega a las instancias por partes, así que un teléfono v41 puede encontrarse con un
 * servidor que todavía no habla v5. Reintentar aquí es seguro y por eso se permite: extraer **lee** una
 * foto y no escribe dinero — al revés que el camino de escritura, donde reintentar a ciegas duplica un
 * cargo. Y es exactamente UN reintento: cada llamada al modelo se paga.
 *
 * Vive fuera de la clase a propósito: así la prueba la puede invocar sin construir un
 * `FirebaseFunctions`, que en JVM no existe. Es la misma salida que el proyecto pide cuando lo que hay
 * que probar queda encerrado donde la prueba no llega.
 */
internal suspend fun extractPageWithFallback(
    call: suspend (Int) -> LedgerExtractionCallResult,
): LedgerExtractionCallResult {
    val first = try {
        call(5)
    } catch (_: Exception) {
        LedgerExtractionCallResult.Unavailable
    }
    if (first !is LedgerExtractionCallResult.Unavailable) return first
    return try {
        call(4)
    } catch (_: Exception) {
        LedgerExtractionCallResult.Unavailable
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

/** A page has at most 25 rows and a row can differ in three fields: 75 is already generous. */
private const val MAX_CROSSCHECK_DISAGREEMENTS_WIRE = 100

/**
 * Tope de renglones confirmados que un bloque puede nombrar. Una página tiene 25 renglones como
 * máximo; el tope es sólo un límite de memoria para un bloque corrupto.
 */
private const val MAX_CROSSCHECK_CONFIRMED_WIRE = 64

/** The Ledger's status is a short word, not a sentence. */
private const val MAX_LEDGER_STATUS_WIRE = 64

/** The kind of a movement is a word from a closed list. */
private const val MAX_CROSSCHECK_KIND_WIRE = 40

/** A customer's name is a name, not the text of the page. */
private const val MAX_CROSSCHECK_CUSTOMER_NAME_WIRE = 120

/** A round trip that took more than a day is not a round trip. */
private const val MAX_CROSSCHECK_LATENCY_WIRE = 86_400_000L

/** The keys a difference has to carry: which row, and which field of it. */
private val CROSSCHECK_DISAGREEMENT_REQUIRED_KEYS = setOf("lineIndex", "field")

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
