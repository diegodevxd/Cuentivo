package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerLineItem
import java.math.BigDecimal

/**
 * Writes the products of a line into the one field that survives into the ledger: the description.
 *
 * The model can already split `"Juan: 2 cocas 38 + pan 25"` into items, and the planner still writes
 * a single uncatalogued line — so without this the split is read, shown, and then thrown away, and
 * Cuentivo can say Juan owes 63 but not what he took. Answering "qué se llevó" is half the point of
 * the feature.
 *
 * Real items on the sale would mean touching `UncatalogedSaleBuilder`, the planner and the way a
 * line becomes a `SaleItemEntity`. That is a bigger change than this release wants, so the products
 * are preserved as readable text now and can graduate to real items later without anybody having to
 * re-photograph a page: the text is the same information, just not queryable yet.
 *
 * The amount is never touched. This only ever writes words.
 */
object LedgerItemsDescription {

    /** Descriptions travel into `productNameSnapshot`, which the rest of the app treats as a name. */
    const val MAX_LENGTH = 80

    /**
     * A readable summary of [items], or null when there is nothing to say.
     *
     * A single item counts. It used to return null here on the reasoning that one product is what
     * the description would already say -- but the model may legitimately send items with no
     * description at all, and then the product was dropped and the line became "Venta en libreta".
     * Which caller prefers the summary over the description is the caller's decision, not this one's.
     */
    fun summarize(items: List<LedgerLineItem>): String? {
        if (items.isEmpty()) return null
        val parts = items.mapNotNull { item ->
            val name = item.description.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val quantity = item.quantity?.takeIf { it > 1 }
            val price = item.subtotalCents?.toLongOrNull()?.let { cents ->
                "$" + BigDecimal.valueOf(cents, 2).toPlainString()
            }
            buildString {
                if (quantity != null) {
                    append(quantity)
                    append(' ')
                }
                append(name)
                if (price != null) {
                    append(" (")
                    append(price)
                    append(')')
                }
            }
        }
        if (parts.isEmpty()) return null
        return truncate(parts.joinToString(separator = " + "))
    }

    /**
     * Cut on a whole product rather than mid-word, and say it was cut.
     *
     * A description silently sliced at 80 characters reads like the notebook only had those
     * products, which is a quieter kind of wrong than an obvious ellipsis.
     */
    private fun truncate(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val ellipsis = "…"
        val cutoff = MAX_LENGTH - ellipsis.length
        val lastSeparator = text.lastIndexOf(" + ", startIndex = cutoff)
        val head = if (lastSeparator > 0) text.substring(0, lastSeparator) else text.substring(0, cutoff)
        return head.trimEnd() + ellipsis
    }
}
