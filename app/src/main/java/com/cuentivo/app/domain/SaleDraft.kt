package com.cuentivo.app.domain

/**
 * One line of a sale being composed on screen, before anything is written.
 *
 * The product name and unit price are copied in rather than referenced: the sale must keep what was
 * charged that day even if the product is repriced tomorrow.
 */
data class SaleDraftLine(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val discountCents: Long = 0,
) {
    init {
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
     */
    fun withLine(line: SaleDraftLine): SaleDraft {
        val existing = lines.indexOfFirst { it.productId == line.productId && it.unitPriceCents == line.unitPriceCents }
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

    fun withoutProduct(productId: String): SaleDraft = copy(lines = lines.filterNot { it.productId == productId })

    /** A sale can be saved only when it has lines and the discount does not swallow the total. */
    fun validationError(): String? = when {
        isEmpty -> "Agrega al menos un producto"
        discountCents < 0 -> "El descuento no puede ser negativo"
        discountCents > subtotalCents -> "El descuento no puede ser mayor que la venta"
        totalCents <= 0 -> "El total debe ser mayor que cero"
        else -> null
    }
}
