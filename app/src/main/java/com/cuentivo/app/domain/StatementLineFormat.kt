package com.cuentivo.app.domain

/**
 * La forma de una línea de movimiento, en un solo sitio.
 *
 * La usan el texto que se manda por WhatsApp y la imagen del estado de cuenta, y **tienen que decir
 * lo mismo**: si el signo o la etiqueta cambian, cambian en los dos a la vez. Antes vivía dentro de
 * `DebtorShareText` y estaba a punto de duplicarse.
 *
 * El signo lo da `direction`, no el tipo: lo que **aumenta** la deuda (`DEBIT`) va sin signo y lo que
 * la **baja** (`CREDIT`) lleva menos. Así una compra, un abono, una devolución, una cancelación y un
 * ajuste en cualquier dirección quedan bien solos, sin una lista que envejezca.
 *
 * El mensaje se lee **desde el lado del cliente**: para él una compra no es "menos" nada.
 */
internal object StatementLineFormat {

    /** `03/08/2026 · Compra · $799.00` */
    fun movementLine(entry: StatementEntry): String {
        val sign = if (entry.direction == "CREDIT") "−" else ""
        return "${displayDate(entry.dateIso)} · ${movementLabel(entry.type)} · $sign${Money.format(entry.amountCents)}"
    }

    /** Lo que se compró, cuando la hoja lo dice. Vacío o en blanco no es un detalle. */
    fun detailOrNull(entry: StatementEntry): String? = entry.detail?.takeIf { it.isNotBlank() }

    /** "2026-08-03" → "03/08/2026". Nobody outside a database reads ISO dates. */
    internal fun displayDate(dateIso: String): String {
        val parts = dateIso.split('-')
        return if (parts.size == 3 && parts[0].length == 4) "${parts[2]}/${parts[1]}/${parts[0]}" else dateIso
    }

    private fun movementLabel(type: String): String = when (type) {
        // "Compra", no "Venta": la palabra la lee el cliente, y para él fue una compra.
        "CHARGE" -> "Compra"
        "PAYMENT" -> "Abono"
        "ADJUSTMENT" -> "Ajuste"
        "REFUND" -> "Devolución"
        "CANCELLATION" -> "Cancelación"
        else -> type
    }
}
