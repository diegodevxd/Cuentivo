package com.cuentivo.app.domain

/**
 * Everything the "nuevo cliente" form can produce, in one value.
 *
 * A data class rather than six positional parameters on a lambda: the form grew a quick sale, an
 * opening balance and a "keep going into the cart" action, and by then the call site was a row of
 * nulls and booleans nobody could read.
 */
data class NewCustomerDraft(
    val name: String,
    val phone: String?,
    val creditLimitCents: Long?,
    /** Everything they walked out with, as one sale. Null when the optional section was untouched. */
    val quickSale: QuickSaleCart? = null,
    /**
     * What this person already owed before the app existed, in cents.
     *
     * People migrating from a notebook were recording this by inventing a product called "deuda
     * anterior", which polluted the catalog and did not say what it was. It is written as a real
     * sale with a single product-less line — see [OpeningBalance] — because everything that lets a
     * debt be collected (allocated payments, the debtor list, the statement, the reminder) hangs off
     * `sales`.
     */
    val openingBalanceCents: Long? = null,
    /**
     * El plazo acordado para la deuda anterior, en días desde hoy, o `null` para "Sin fecha".
     *
     * Una deuda anterior sin fecha no vence, no entra al recordatorio ni cuenta para la
     * calificación; el recorrido "Doña Mary" la encontró naciendo sin fecha (v44). Se usa el mismo
     * selector de la venta ([com.cuentivo.app.ui.transaction.DueOption]), con 15 marcado.
     */
    val openingBalanceDueInDays: Long? = null,
    /**
     * Whether the person asked to continue into Nueva venta with this customer already selected.
     *
     * The quick sale on this form holds exactly one product, and a real order is five or more.
     * Rather than growing a second cart inside a dialog, the form hands off to the one cart that
     * already exists.
     */
    val continueToSale: Boolean = false,
)
