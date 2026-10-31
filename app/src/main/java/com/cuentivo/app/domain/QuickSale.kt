package com.cuentivo.app.domain

/**
 * One thing the new customer is taking with them, captured on the same form that creates them.
 *
 * The everyday case is one person walking in, being written down, and leaving with something on
 * credit. Doing that as three separate screens is why people give up halfway and end up with a
 * customer who owes money nobody recorded.
 */
data class QuickSaleLine(
    /** Identifies the row while the form is open. Supplied by the caller, so tests get fixed ids. */
    val lineId: String,
    /**
     * The catalog product this row came from, or null when it was written by hand.
     *
     * Null does not mean "no product": the host still reuses a catalog product whose name matches,
     * and mints one otherwise. What it means is that the person did not pick from the catalog, which
     * is the whole point for somebody who sells without keeping one.
     */
    val productId: String?,
    val productName: String,
    val unitPriceCents: Long,
    val quantity: Int,
    /** Only ever set for a product being created here; a catalog product already has its own. */
    val photoFileName: String?,
) {
    init {
        require(lineId.isNotBlank()) { "A quick sale line needs an id." }
        require(productName.isNotBlank()) { "A quick sale needs a product name." }
        require(unitPriceCents > 0) { "A quick sale needs a positive price." }
        require(quantity > 0) { "A quick sale needs a positive quantity." }
    }

    /** Integer cents throughout, like every other total in this app. */
    val totalCents: Long get() = quantity * unitPriceCents
}

/**
 * Everything the new customer is walking out with, as one sale.
 *
 * A list rather than a single line because a real order is five or more things, and the form used to
 * hold exactly one: anybody with a bigger order had to save the customer and start again elsewhere.
 *
 * [dueInDays] lives here and not on the line because the date is agreed for the sale, not per
 * product. Days rather than a date because this is the domain: resolving it against a calendar needs
 * a clock, and keeping that out is what lets this file run without a device. It exists at all because
 * a sale with no agreed date can never be late, and a sale that cannot be late is one the payment
 * score has to ignore.
 */
data class QuickSaleCart(
    val lines: List<QuickSaleLine> = emptyList(),
    val dueInDays: Long? = QuickSalePolicy.DEFAULT_DUE_DAYS,
) {
    init {
        require(dueInDays == null || dueInDays > 0) { "An agreed date cannot be today or earlier." }
    }

    val isEmpty: Boolean get() = lines.isEmpty()
    val totalCents: Long get() = lines.sumOf { it.totalCents }

    /**
     * Adds a row. A catalog product already in the cart bumps its quantity instead of repeating,
     * which is what tapping the same product twice means; a hand-written row never merges, because
     * typing the same thing twice is deliberate.
     */
    fun withLine(line: QuickSaleLine): QuickSaleCart {
        val existing = if (line.productId == null) -1 else lines.indexOfFirst {
            it.productId == line.productId && it.unitPriceCents == line.unitPriceCents
        }
        if (existing == -1) return copy(lines = lines + line)
        val merged = lines[existing].let { it.copy(quantity = it.quantity + line.quantity) }
        return copy(lines = lines.toMutableList().also { it[existing] = merged })
    }

    fun withoutLine(lineId: String): QuickSaleCart = copy(lines = lines.filterNot { it.lineId == lineId })

    /** The photos this cart is holding, so cancelling the form can clean up after itself. */
    fun photoFileNames(): List<String> = lines.mapNotNull { it.photoFileName }
}

/** What the row currently being typed amounts to. */
sealed interface QuickSaleEntry {
    /** Untouched. Nothing to add, and an empty cart still saves an ordinary customer. */
    data object Empty : QuickSaleEntry

    /**
     * Started but not usable yet — a name with no price, a price of zero, quantity blank.
     *
     * Distinct from [Empty] on purpose: someone who typed a product name and then hit Guardar meant
     * to record a sale, so the form holds them there instead of quietly saving a bare customer and
     * losing what they were selling.
     */
    data object Incomplete : QuickSaleEntry

    data class Ready(val line: QuickSaleLine) : QuickSaleEntry
}

/** Reads the row being typed. Pure, so the rule lives here and not inside a composable. */
object QuickSalePolicy {
    fun read(
        lineId: String,
        productId: String?,
        productName: String,
        priceText: String,
        quantityText: String,
        photoFileName: String?,
    ): QuickSaleEntry {
        val name = productName.trim()
        val price = priceText.trim().ifBlank { null }?.let(Money::positiveCentsFromPesos)
        val quantity = quantityText.trim().ifBlank { null }?.toIntOrNull()
        // A photo alone counts as started: the file already exists on disk, and treating it as
        // untouched would strand it there with nothing pointing at it.
        val touched = name.isNotEmpty() || priceText.isNotBlank() || photoFileName != null ||
            (quantityText.isNotBlank() && quantityText.trim() != DEFAULT_QUANTITY)
        return when {
            !touched -> QuickSaleEntry.Empty
            name.isEmpty() || price == null || quantity == null || quantity <= 0 -> QuickSaleEntry.Incomplete
            else -> QuickSaleEntry.Ready(QuickSaleLine(lineId, productId, name, price, quantity, photoFileName))
        }
    }

    /** The quantity the form starts with, so leaving it alone does not read as "started". */
    const val DEFAULT_QUANTITY = "1"

    /**
     * The plazo a quick sale starts with. Fifteen days, matching `NewSaleScreen`, because selling
     * fiado *means* agreeing on a date — starting at "sin fecha" is what left the score blind.
     */
    const val DEFAULT_DUE_DAYS = 15L
}
