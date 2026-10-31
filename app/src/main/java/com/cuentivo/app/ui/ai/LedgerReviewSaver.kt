package com.cuentivo.app.ui.ai

import android.os.Bundle
import com.cuentivo.app.domain.LedgerReviewSchema
import com.cuentivo.app.ai.ledger.LedgerDetectedPageDate
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerLineItem
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerRegion
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.LedgerPageType
import com.cuentivo.app.ai.ledger.LedgerPageTotal
import com.cuentivo.app.ai.ledger.LedgerFieldConfidence
import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.ui.transaction.DueOption

/**
 * What a ledger review under way saves across Activity recreation, and nothing else.
 *
 * The rule this file follows: save what cannot be recalculated — the reading Gemini made of the
 * page plus whatever the person has corrected on top of it — and reload from Room everything that
 * is derivable. The 552 products, the customer candidates, the balances and the already-registered
 * rows are derivable; a Bundle is not a place they would fit, and exceeding the transaction limit
 * kills the app.
 *
 * Never saved: the image, anything base64, or anything that resembles a photo. The capture file
 * path is already `rememberSaveable` in the screen, and that is enough to keep showing crops.
 *
 * Restoring never calls the callable. The extraction result is here, in full, so the review comes
 * back exactly as it was left — including the corrections — without paying for the page twice.
 */
internal object LedgerReviewSaver {

    private const val KEY_SESSION_ID = "sessionId"
    private const val KEY_BUSINESS_ID = "businessId"
    private const val KEY_OPERATIONS = "operations"
    private const val KEY_ORIGINAL_OPERATIONS = "originalOperations"
    private const val KEY_DISCARDS = "discards"
    private const val KEY_UNREAD = "unreadTextRemaining"
    private const val KEY_INCOMPLETE = "pageIncomplete"
    private const val KEY_PAGE_DATE = "pageDateEpochDay"
    private const val KEY_TODAY = "todayEpochDay"
    private const val KEY_DETECTED_PAGE_DATE = "detectedPageDate"
    private const val KEY_DETECTED_PAGE_DATE_CONFIDENCE = "detectedPageDateConfidence"
    private const val KEY_INITIAL_DUPLICATE_COUNT = "initialDuplicateCount"
    private const val KEY_REVIEW_SCHEMA_VERSION = "reviewSchemaVersion"
    private const val KEY_PAGE_COMPLETENESS_CONFIRMED = "pageCompletenessConfirmed"
    private const val KEY_PAGE_TYPE = "pageType"
    private const val KEY_PAGE_TOTALS = "pageTotals"
    private const val KEY_DUE_DATE_DAYS = "dueDateDays"

    // Operation keys. Flat strings, one per field, so a malformed row can be skipped without
    // dragging the whole page down — but see writeOperations: a row that fails to round-trip
    // aborts the save instead of being silently dropped, because a review that comes back
    // missing rows the person already corrected is worse than one that comes back empty.
    private const val OP_LINE_INDEX = "lineIndex"
    private const val OP_KIND = "kind"
    private const val OP_CUSTOMER = "customer"
    private const val OP_CUSTOMER_ID = "customerId"
    private const val OP_AMOUNT = "amount"
    private const val OP_DESCRIPTION = "description"
    private const val OP_DATE = "date"
    private const val OP_UNCERTAIN = "uncertain"
    private const val OP_UNREADABLE = "unreadable"
    private const val OP_REGION = "region"
    private const val OP_CONFIDENCE = "confidence"
    private const val OP_SOURCES = "sources"
    private const val OP_RAW_TEXT = "rawText"
    private const val OP_ITEMS = "items"
    private const val OP_WARNINGS = "warnings"
    private const val OP_OBJECTION_CONFIRMED = "objectionConfirmed"
    private const val OP_OBJECTION_FINGERPRINT = "objectionConfirmedFingerprint"
    private const val OP_MARGIN_NOTE = "marginNote"

    /**
     * The part of a review that cannot be recomputed: what the model read, what the person edited,
     * and the session it belongs to. Balances, candidates and registered rows are deliberately
     * absent — they are reloaded from Room when the review is rebuilt.
     *
     * [originalOperations] is the reading as it arrived from the callable, frozen and never
     * edited: the corrections a person made are counted by comparing this against [operations],
     * and without it a recreation would carry only the edited version and count them wrong — or
     * not at all.
     */
    data class SavedLedgerReview(
        val sessionId: String,
        val businessId: String,
        val operations: List<LedgerOperation>,
        val originalOperations: List<LedgerOperation>,
        val discards: List<LedgerDiscardDiagnostic>,
        val unreadTextRemaining: Boolean,
        val pageIncomplete: Boolean,
        val pageDateEpochDay: Long?,
        val todayEpochDay: Long?,
        val detectedPageDate: LedgerDetectedPageDate?,
        val initialDuplicateCount: Int = 0,
        val reviewSchemaVersion: Int? = null,
        val isPageCompletenessConfirmed: Boolean = false,
        val pageType: LedgerPageType? = null,
        val pageTotals: List<LedgerPageTotal> = emptyList(),
        /**
         * El plazo de la página, en días, o `null` para "Sin fecha".
         *
         * Viaja en el Bundle como **texto** (`"15"` / cadena vacía) y no como `Long` ausente, y esa
         * elección es la que distingue dos casos que para `Bundle` son el mismo "no está":
         * un borrador guardado **antes** de que existiera el selector (clave ausente → vuelve con
         * 15, no con `null`) y la elección explícita "Sin fecha" (clave presente, vacía → `null`).
         * Al volver de la app el selector tiene que mostrar exactamente lo que se registrará.
         */
        val dueDateDays: Long? = DueOption.FIFTEEN.days,
    )

    fun toBundle(saved: SavedLedgerReview): Bundle {
        val bundle = Bundle()
        bundle.putInt(KEY_REVIEW_SCHEMA_VERSION, LedgerReviewSchema.CURRENT)
        bundle.putBoolean(KEY_PAGE_COMPLETENESS_CONFIRMED, saved.isPageCompletenessConfirmed)
        saved.operations.firstOrNull()?.let { }
        saved.pageType?.let { bundle.putString(KEY_PAGE_TYPE, it.name) }
        bundle.putParcelableArray(KEY_PAGE_TOTALS, saved.pageTotals.map { total -> Bundle().apply { putString("label", total.label); putLong("amount", total.amountCents); total.afterLineIndex?.let { putInt("after", it) } } }.toTypedArray())
        bundle.putString(KEY_SESSION_ID, saved.sessionId)
        bundle.putString(KEY_BUSINESS_ID, saved.businessId)
        bundle.putParcelableArray(KEY_OPERATIONS, saved.operations.map(::operationToBundle).toTypedArray())
        bundle.putParcelableArray(KEY_ORIGINAL_OPERATIONS, saved.originalOperations.map(::operationToBundle).toTypedArray())
        bundle.putParcelableArray(KEY_DISCARDS, saved.discards.map(::discardToBundle).toTypedArray())
        bundle.putBoolean(KEY_UNREAD, saved.unreadTextRemaining)
        bundle.putBoolean(KEY_INCOMPLETE, saved.pageIncomplete)
        bundle.putInt(KEY_INITIAL_DUPLICATE_COUNT, saved.initialDuplicateCount)
        saved.pageDateEpochDay?.let { bundle.putLong(KEY_PAGE_DATE, it) }
        // Siempre presente: cadena vacía = "Sin fecha" elegida a mano. Que la clave exista es lo
        // que permite distinguirla de un guardado anterior que no trae el campo.
        bundle.putString(KEY_DUE_DATE_DAYS, saved.dueDateDays?.toString() ?: "")
        saved.todayEpochDay?.let { bundle.putLong(KEY_TODAY, it) }
        saved.detectedPageDate?.let { detected ->
            bundle.putBundle(
                KEY_DETECTED_PAGE_DATE,
                Bundle().apply {
                    putInt("y", detected.date.year)
                    putInt("m", detected.date.month)
                    putInt("d", detected.date.day)
                    putDouble("c", detected.confidence)
                },
            )
        }
        return bundle
    }

    @Suppress("DEPRECATION")
    fun fromBundle(bundle: Bundle): SavedLedgerReview? {
        val sessionId = bundle.getString(KEY_SESSION_ID) ?: return null
        val businessId = bundle.getString(KEY_BUSINESS_ID) ?: return null
        // The typed variant of this call only exists from API 33 and the app ships to minSdk 24.
        val operationBundles = bundle.getParcelableArray(KEY_OPERATIONS) ?: return null
        val operations = operationBundles.mapNotNull { raw ->
            (raw as? Bundle)?.let(::operationFromBundle)
        }
        // A row that did not survive the trip means the save was written by a shape this build no
        // longer understands. Returning a partial review would let a person confirm a page that is
        // missing rows they already corrected, so the whole restore is refused instead.
        if (operations.size != operationBundles.size) return null
        // The original reading is saved by builds that have it. A save from an older shape has
        // none: rather than refusing the whole restore, the original falls back to the edited
        // operations, which counts zero corrections — the honest count for a page whose
        // before-state was never recorded.
        val originalBundles = bundle.getParcelableArray(KEY_ORIGINAL_OPERATIONS)
        val originalOperations = if (originalBundles == null) {
            operations
        } else {
            val parsed = originalBundles.mapNotNull { raw ->
                (raw as? Bundle)?.let(::operationFromBundle)
            }
            if (parsed.size != originalBundles.size) operations else parsed
        }
        val discardBundles = bundle.getParcelableArray(KEY_DISCARDS) ?: return null
        val discards = discardBundles.mapNotNull { raw ->
            (raw as? Bundle)?.let(::discardFromBundle)
        }
        if (discards.size != discardBundles.size) return null
        val detectedBundle = bundle.getBundle(KEY_DETECTED_PAGE_DATE)
        val detectedPageDate = detectedBundle?.let { detected ->
            LedgerDetectedPageDate(
                date = SafeDateCandidate(
                    year = detected.getInt("y"),
                    month = detected.getInt("m"),
                    day = detected.getInt("d"),
                ),
                confidence = detected.getDouble("c"),
            )
        }
        val initialDuplicateCount = bundle.getInt(KEY_INITIAL_DUPLICATE_COUNT, 0)
        return SavedLedgerReview(
            sessionId = sessionId,
            businessId = businessId,
            operations = operations,
            originalOperations = originalOperations,
            discards = discards,
            unreadTextRemaining = bundle.getBoolean(KEY_UNREAD),
            pageIncomplete = bundle.getBoolean(KEY_INCOMPLETE),
            pageDateEpochDay = if (bundle.containsKey(KEY_PAGE_DATE)) bundle.getLong(KEY_PAGE_DATE) else null,
            todayEpochDay = if (bundle.containsKey(KEY_TODAY)) bundle.getLong(KEY_TODAY) else null,
            detectedPageDate = detectedPageDate,
            initialDuplicateCount = initialDuplicateCount,
            reviewSchemaVersion = if (bundle.containsKey(KEY_REVIEW_SCHEMA_VERSION)) bundle.getInt(KEY_REVIEW_SCHEMA_VERSION) else null,
            isPageCompletenessConfirmed = bundle.getBoolean(KEY_PAGE_COMPLETENESS_CONFIRMED, false),
            pageType = bundle.getString(KEY_PAGE_TYPE)?.let { runCatching { LedgerPageType.valueOf(it) }.getOrNull() },
            pageTotals = (bundle.getParcelableArray(KEY_PAGE_TOTALS) ?: emptyArray()).mapNotNull { raw -> (raw as? Bundle)?.let { LedgerPageTotal(it.getString("label") ?: return@let null, it.getLong("amount"), if (it.containsKey("after")) it.getInt("after") else null) } },
            dueDateDays = if (bundle.containsKey(KEY_DUE_DATE_DAYS)) {
                bundle.getString(KEY_DUE_DATE_DAYS)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            } else {
                // Guardado anterior al selector: vuelve con 15 marcado, no en "Sin fecha". Si
                // volviera en null la persona registraría sin fecha lo que vio con fecha.
                DueOption.FIFTEEN.days
            },
        )
    }

    /** Rebuilds the derived half of the review from the saved half. Pure: no Room, no clock. */
    fun rebuildReview(
        saved: SavedLedgerReview,
        existingCustomerBalances: Map<String, Long>,
    ): LedgerReview = LedgerReviewEngine.build(
        operations = saved.operations,
        discards = saved.discards,
        unreadTextRemaining = saved.unreadTextRemaining,
        pageIncomplete = saved.pageIncomplete,
        existingCustomerBalances = existingCustomerBalances,
        pageDateEpochDay = saved.pageDateEpochDay,
        todayEpochDay = saved.todayEpochDay,
        detectedPageDate = saved.detectedPageDate,
    )

    private fun operationToBundle(op: LedgerOperation): Bundle {
        val bundle = Bundle()
        bundle.putInt(OP_LINE_INDEX, op.lineIndex)
        op.proposedKind?.let { bundle.putString(OP_KIND, it.name) }
        op.customerName?.let { bundle.putString(OP_CUSTOMER, it) }
        op.customerId?.let { bundle.putString(OP_CUSTOMER_ID, it) }
        op.amountCents?.let { bundle.putString(OP_AMOUNT, it) }
        op.description?.let { bundle.putString(OP_DESCRIPTION, it) }
        op.date?.let { date ->
            bundle.putInt("$OP_DATE.y", date.year)
            bundle.putInt("$OP_DATE.m", date.month)
            bundle.putInt("$OP_DATE.d", date.day)
        }
        if (op.uncertainFields.isNotEmpty()) {
            bundle.putStringArray(OP_UNCERTAIN, op.uncertainFields.map { it.name }.toTypedArray())
        }
        if (op.unreadableFields.isNotEmpty()) {
            bundle.putStringArray(OP_UNREADABLE, op.unreadableFields.map { it.name }.toTypedArray())
        }
        op.region?.let { region ->
            bundle.putDouble("$OP_REGION.x", region.x)
            bundle.putDouble("$OP_REGION.y", region.y)
            bundle.putDouble("$OP_REGION.w", region.width)
            bundle.putDouble("$OP_REGION.h", region.height)
        }
        op.confidence?.let { confidence ->
            confidence.customer?.let { bundle.putDouble("$OP_CONFIDENCE.customer", it) }
            confidence.concept?.let { bundle.putDouble("$OP_CONFIDENCE.concept", it) }
            confidence.amount?.let { bundle.putDouble("$OP_CONFIDENCE.amount", it) }
            confidence.date?.let { bundle.putDouble("$OP_CONFIDENCE.date", it) }
            confidence.kind?.let { bundle.putDouble("$OP_CONFIDENCE.kind", it) }
        }
        op.sources?.let { sources ->
            sources.customer?.let { bundle.putString("$OP_SOURCES.customer", it.name) }
            sources.date?.let { bundle.putString("$OP_SOURCES.date", it.name) }
        }
        op.rawText?.let { bundle.putString(OP_RAW_TEXT, it) }
        if (op.items.isNotEmpty()) {
            bundle.putParcelableArray(OP_ITEMS, op.items.map(::itemToBundle).toTypedArray())
        }
        if (op.warnings.isNotEmpty()) {
            bundle.putStringArray(OP_WARNINGS, op.warnings.toTypedArray())
        }
        if (op.isObjectionConfirmed) {
            bundle.putBoolean(OP_OBJECTION_CONFIRMED, true)
        }
        op.objectionConfirmedFingerprint?.let { bundle.putString(OP_OBJECTION_FINGERPRINT, it) }
        op.marginNote?.let { bundle.putString(OP_MARGIN_NOTE, it) }
        return bundle
    }

    private fun operationFromBundle(bundle: Bundle): LedgerOperation? {
        val lineIndex = runCatching { bundle.getInt(OP_LINE_INDEX) }.getOrNull() ?: return null
        val kind = bundle.getString(OP_KIND)?.let { name ->
            ProposedLedgerKind.entries.firstOrNull { it.name == name }
        }
        val date = if (bundle.containsKey("$OP_DATE.y")) {
            SafeDateCandidate(
                year = bundle.getInt("$OP_DATE.y"),
                month = bundle.getInt("$OP_DATE.m"),
                day = bundle.getInt("$OP_DATE.d"),
            )
        } else {
            null
        }
        val uncertain = bundle.getStringArray(OP_UNCERTAIN)?.mapNotNull { name ->
            UncertainLedgerField.entries.firstOrNull { it.name == name }
        }?.takeIf { it.size == bundle.getStringArray(OP_UNCERTAIN)?.size } ?: emptyList()
        val unreadable = bundle.getStringArray(OP_UNREADABLE)?.mapNotNull { name ->
            UnreadableLedgerField.entries.firstOrNull { it.name == name }
        }?.takeIf { it.size == bundle.getStringArray(OP_UNREADABLE)?.size } ?: emptyList()
        val region = if (bundle.containsKey("$OP_REGION.x")) {
            LedgerRegion(
                x = bundle.getDouble("$OP_REGION.x"),
                y = bundle.getDouble("$OP_REGION.y"),
                width = bundle.getDouble("$OP_REGION.w"),
                height = bundle.getDouble("$OP_REGION.h"),
            )
        } else {
            null
        }
        val confidence = if (bundle.containsKey("$OP_CONFIDENCE.customer") ||
            bundle.containsKey("$OP_CONFIDENCE.concept") ||
            bundle.containsKey("$OP_CONFIDENCE.amount") ||
            bundle.containsKey("$OP_CONFIDENCE.date") ||
            bundle.containsKey("$OP_CONFIDENCE.kind")
        ) {
            LedgerFieldConfidence(
                customer = optionalDouble(bundle, "$OP_CONFIDENCE.customer"),
                concept = optionalDouble(bundle, "$OP_CONFIDENCE.concept"),
                amount = optionalDouble(bundle, "$OP_CONFIDENCE.amount"),
                date = optionalDouble(bundle, "$OP_CONFIDENCE.date"),
                kind = optionalDouble(bundle, "$OP_CONFIDENCE.kind"),
            )
        } else {
            null
        }
        val sources = if (bundle.containsKey("$OP_SOURCES.customer") || bundle.containsKey("$OP_SOURCES.date")) {
            LedgerFieldSources(
                customer = bundle.getString("$OP_SOURCES.customer")?.let { name ->
                    LedgerFieldSource.entries.firstOrNull { it.name == name }
                },
                date = bundle.getString("$OP_SOURCES.date")?.let { name ->
                    LedgerFieldSource.entries.firstOrNull { it.name == name }
                },
            )
        } else {
            null
        }
        val itemBundles = bundle.getParcelableArray(OP_ITEMS)
        val items = itemBundles?.mapNotNull { raw -> (raw as? Bundle)?.let(::itemFromBundle) } ?: emptyList()
        if (itemBundles != null && items.size != itemBundles.size) return null
        return LedgerOperation(
            lineIndex = lineIndex,
            proposedKind = kind,
            customerName = bundle.getString(OP_CUSTOMER),
            customerId = bundle.getString(OP_CUSTOMER_ID),
            amountCents = bundle.getString(OP_AMOUNT),
            description = bundle.getString(OP_DESCRIPTION),
            date = date,
            uncertainFields = uncertain,
            unreadableFields = unreadable,
            region = region,
            confidence = confidence,
            sources = sources,
            rawText = bundle.getString(OP_RAW_TEXT),
            items = items,
            warnings = bundle.getStringArray(OP_WARNINGS)?.toList() ?: emptyList(),
            isObjectionConfirmed = bundle.getBoolean(OP_OBJECTION_CONFIRMED, false),
            objectionConfirmedFingerprint = bundle.getString(OP_OBJECTION_FINGERPRINT),
            marginNote = bundle.getString(OP_MARGIN_NOTE),
        )
    }

    private fun itemToBundle(item: LedgerLineItem): Bundle {
        val bundle = Bundle()
        bundle.putString("description", item.description)
        item.quantity?.let { bundle.putInt("quantity", it) }
        item.subtotalCents?.let { bundle.putString("subtotalCents", it) }
        return bundle
    }

    private fun itemFromBundle(bundle: Bundle): LedgerLineItem? {
        val description = bundle.getString("description") ?: return null
        return LedgerLineItem(
            description = description,
            quantity = if (bundle.containsKey("quantity")) bundle.getInt("quantity") else null,
            subtotalCents = bundle.getString("subtotalCents"),
        )
    }

    private fun discardToBundle(discard: LedgerDiscardDiagnostic): Bundle {
        val bundle = Bundle()
        bundle.putInt("inputIndex", discard.inputIndex)
        discard.lineIndex?.let { bundle.putInt("lineIndex", it) }
        bundle.putString("reason", discard.reason.name)
        return bundle
    }

    private fun discardFromBundle(bundle: Bundle): LedgerDiscardDiagnostic? {
        val inputIndex = runCatching { bundle.getInt("inputIndex") }.getOrNull() ?: return null
        val reasonName = bundle.getString("reason") ?: return null
        val reason = LedgerDiscardReason.entries.firstOrNull { it.name == reasonName } ?: return null
        return LedgerDiscardDiagnostic(
            inputIndex = inputIndex,
            lineIndex = if (bundle.containsKey("lineIndex")) bundle.getInt("lineIndex") else null,
            reason = reason,
        )
    }

    private fun optionalDouble(bundle: Bundle, key: String): Double? =
        if (bundle.containsKey(key)) bundle.getDouble(key) else null
}
