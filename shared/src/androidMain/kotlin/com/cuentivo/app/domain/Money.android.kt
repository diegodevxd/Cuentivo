package com.cuentivo.app.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * El formateo de dinero de Android, sin cambios respecto de lo que la app ya mostraba:
 * `NumberFormat` de moneda con locale es-MX y la moneda del negocio.
 *
 * Es el único punto donde un importe se convierte a decimal, y se hace con
 * `BigDecimal.valueOf(cents, 2)` — nunca con `Double`.
 */
internal actual fun formatMoney(cents: Long, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    format.currency = Currency.getInstance(currencyCode)
    return format.format(BigDecimal.valueOf(cents, 2))
}
