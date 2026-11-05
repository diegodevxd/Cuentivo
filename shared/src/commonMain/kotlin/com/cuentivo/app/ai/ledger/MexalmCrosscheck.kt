package com.cuentivo.app.ai.ledger

/**
 * Which part of a row the two readings disagreed about.
 *
 * The set is closed on purpose: a field nobody knows about is not "some difference", it is a
 * difference the app cannot explain, and pretending otherwise is how a badge ends up saying
 * "something" about a row.
 *
 * `MISSING_IN_LEDGER` means the page read a movement and the Ledger saw nothing there;
 * `MISSING_IN_GEMINI` means the other way around. Both are differences of *presence*, not of value.
 */
enum class MexalmCrosscheckDisagreementField {
    TYPE,
    CUSTOMER,
    AMOUNT,
    MISSING_IN_LEDGER,
    MISSING_IN_GEMINI,
    ;

    /**
     * ¿Es una diferencia de VALOR —el tipo, el cliente o el monto—?
     *
     * Las diferencias de PRESENCIA (el Ledger no vio el renglón, o lo vio de más) no son
     * diferencias de un renglón cuando el Ledger no leyó la página: son la página entera sin
     * certificar, y se dicen una sola vez. Las de valor sí son de un renglón concreto.
     */
    val isValueDifference: Boolean
        get() = this == TYPE || this == CUSTOMER || this == AMOUNT
}

/**
 * What ONE side wrote about a row.
 *
 * Never carries the text of the page: the server does not send it, and this class does not invent
 * it. The amount is read as whole centavos ([Long]) and never as a floating point number — the same
 * rule the rest of the app follows for money, and the reason the parser refuses a summary whose
 * amount arrives as anything but a canonical centavo string.
 */
data class MexalmCrosscheckOperationSummary(
    val proposedKind: String? = null,
    val customerName: String? = null,
    val amountCents: Long? = null,
)

/**
 * One difference between what the page read and what the Ledger read of the same text.
 *
 * [lineIndex] is the identity of the row: the same number the rows of the page carry, so a
 * disagreement can be pinned to the row it belongs to. The Ledger's side may be absent when the
 * Ledger saw no movement at all, and the page's side may be absent for the same reason.
 */
data class MexalmCrosscheckDisagreement(
    val lineIndex: Int,
    val field: MexalmCrosscheckDisagreementField,
    val gemini: MexalmCrosscheckOperationSummary? = null,
    val ledger: MexalmCrosscheckOperationSummary? = null,
)

/**
 * The cross-check block the server sends next to a page, when it sent one.
 *
 * **What this is:** a second reading of the same transcription, produced by the Ledger de MexaLM,
 * compared row by row against the reading that produced the page. It is a comment next to the page.
 *
 * **What this is NOT:** it is not a second decision about the money. Nothing here changes what gets
 * registered: the rows that get registered are the ones the page read, exactly as before this
 * block existed. A disagreement is information the person may look at, and nothing else.
 *
 * [agreed] is the only value that means "both sides say the same". `false` means the two sides did
 * not agree, and [disagreements] says where.
 */
data class MexalmCrosscheckPayload(
    val agreed: Boolean,
    val disagreements: List<MexalmCrosscheckDisagreement>,
    /** The Ledger's own status, verbatim. "OK" is the one that means it gave a verdict. */
    val ledgerStatus: String,
    /** Round trip measured by the server, in milliseconds. */
    val latencyMs: Long,
    /**
     * The rows the Ledger CONFIRMED: both sides stated the kind, the customer and the amount,
     * and all three matched.
     *
     * This is not the same as [agreed]. `agreed: true` means "there were no disagreements", and
     * that also happens when the Ledger had no opinion at all: a Ledger that answers
     * `customerName: null` everywhere disagrees with nothing, and with this list empty the client
     * knows that Ledger confirmed not a single row.
     *
     * Empty by default: an older server does not send the list, and its absence means "no rows
     * were confirmed", never "all of them were".
     */
    val confirmedLineIndices: Set<Int> = emptySet(),
) {
    /**
     * The rows that disagree, by line index.
     *
     * A count of rows is not a count of differences: one row can differ in the amount AND in the
     * customer. The summary the person reads says "N renglones distintos", so it has to count rows.
     */
    val disagreeingLineIndices: Set<Int>
        get() = disagreements.map { it.lineIndex }.toSet()

    /** The differences of one row, in the order the server sent them. */
    fun disagreementsFor(lineIndex: Int): List<MexalmCrosscheckDisagreement> =
        disagreements.filter { it.lineIndex == lineIndex }
}
