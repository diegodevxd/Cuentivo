package com.cuentivo.app.domain

/**
 * One line of a sale being composed on screen, before anything is written.
 *
 * The product name and unit price are copied in rather than referenced: the sale must keep what was
 * charged that day even if the product is repriced tomorrow.
 */
data class SaleDraftLine(
    /**
     * Identifies this row while the cart is on screen. Separate from [productId] because a line
     * written by hand has no product at all, and the cart still has to be able to draw it and remove
     * it. Supplied by the caller so this file stays free of `UUID` and the tests get fixed ids.
     */
    val lineId: String,
    /**
     * The catalog product this line came from, or null when the seller typed the name and price by
     * hand. Nullable because `sale_items.productId` is nullable in Room and in `firestore.rules`:
     * somebody who sells door to door has no catalog, and requiring one is what made this app
     * unusable for them.
     */
    val productId: String?,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val discountCents: Long = 0,
) {
    init {
        require(lineId.isNotBlank()) { "A cart line needs an id." }
        require(productName.isNotBlank()) { "A cart line needs a name." }
        require(quantity > 0) { "Quantity must be positive." }
        require(unitPriceCents > 0) { "Unit price must be positive." }
        require(discountCents >= 0) { "Line discount cannot be negative." }
        require(discountCents <= quantity * unitPriceCents) { "Line discount cannot exceed the line." }
    }

    /** Exactly the arithmetic firestore.rules validates for a sale item. */
    val subtotalCents: Long = quantity * unitPriceCents - discountCents
}

/**
 * A sale in progress: its lines plus a whole-sale discount.
 *
 * Pure integer arithmetic, no Android, no rounding: every total here is a sum of cents.
 */
data class SaleDraft(
    val lines: List<SaleDraftLine> = emptyList(),
    val discountCents: Long = 0,
    val dueDateEpochDay: Long? = null,
) {
    /** Sum of the lines, which is what `sales.subtotalCents` must hold. */
    val subtotalCents: Long get() = lines.sumOf { it.subtotalCents }

    /** `totalCents == subtotalCents - discountCents`, the invariant the Firestore rules check. */
    val totalCents: Long get() = subtotalCents - discountCents

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Adding a product already in the cart bumps its quantity instead of repeating the row, which is
     * what a person tapping the same product twice means.
     *
     * A line written by hand never merges, even with an identical name: picking the same catalog
     * product twice is a slip of the finger, but typing the same thing twice is deliberate.
     */
    fun withLine(line: SaleDraftLine): SaleDraft {
        val existing = if (line.productId == null) -1 else lines.indexOfFirst {
            it.productId == line.productId && it.unitPriceCents == line.unitPriceCents
        }
        val merged = if (existing == -1) {
            lines + line
        } else {
            lines.toMutableList().also {
                val previous = it[existing]
                it[existing] = previous.copy(
                    quantity = previous.quantity + line.quantity,
                    discountCents = previous.discountCents + line.discountCents,
                )
            }
        }
        return copy(lines = merged)
    }

    /** Removes one row. By line id, not by product: a hand-written line has no product to name. */
    fun withoutLine(lineId: String): SaleDraft = copy(lines = lines.filterNot { it.lineId == lineId })

    /** A sale can be saved only when it has lines and the discount does not swallow the total. */
    fun validationError(): String? = when {
        isEmpty -> "Agrega al menos un producto"
        discountCents < 0 -> "El descuento no puede ser negativo"
        discountCents > subtotalCents -> "El descuento no puede ser mayor que la venta"
        totalCents <= 0 -> "El total debe ser mayor que cero"
        else -> null
    }
}
