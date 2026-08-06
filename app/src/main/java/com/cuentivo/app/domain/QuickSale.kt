package com.cuentivo.app.domain

/**
 * A product the new customer is taking with them, captured on the same form that creates them.
 *
 * The everyday case is one person walking in, being written down, and leaving with something on
 * credit. Doing that as three separate screens is why people give up halfway and end up with a
 * customer who owes money nobody recorded.
 */
data class QuickSale(
    val productName: String,
    val unitPriceCents: Long,
    val quantity: Int,
    val photoFileName: String?,
) {
    init {
        require(productName.isNotBlank()) { "A quick sale needs a product name." }
        require(unitPriceCents > 0) { "A quick sale needs a positive price." }
        require(quantity > 0) { "A quick sale needs a positive quantity." }
    }

    /** Integer cents throughout, like every other total in this app. */
    val totalCents: Long get() = quantity * unitPriceCents
}

/** What the optional section of the customer form amounts to right now. */
sealed interface QuickSaleEntry {
    /** Untouched. The form saves an ordinary customer, exactly as it always has. */
    data object Empty : QuickSaleEntry

    /**
     * Started but not usable yet — a name with no price, a price of zero, quantity blank.
     *
     * Distinct from [Empty] on purpose: someone who typed a product name and then hit Guardar meant
     * to record a sale, so the form holds them there instead of quietly saving a bare customer and
     * losing what they were selling.
     */
    data object Incomplete : QuickSaleEntry

    data class Ready(val sale: QuickSale) : QuickSaleEntry
}

/** Reads the optional section. Pure, so the rule lives here and not inside a composable. */
object QuickSalePolicy {
    fun read(
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
            else -> QuickSaleEntry.Ready(QuickSale(name, price, quantity, photoFileName))
        }
    }

    /** The quantity the form starts with, so leaving it alone does not read as "started". */
    const val DEFAULT_QUANTITY = "1"
}
