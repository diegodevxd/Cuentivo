package com.cuentivo.app.domain

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle

/**
 * El formateo de dinero de iOS: mismo locale es-MX y misma moneda que Android.
 *
 * El importe se le pasa a `NSNumberFormatter` como `NSDecimalNumber` construido desde el texto
 * exacto de los centavos ("150.50"), no desde un `Double`: los centavos son enteros y el formateador
 * recibe el decimal ya exacto.
 */
internal actual fun formatMoney(cents: Long, currencyCode: String): String {
    val plain = decimalTextFromCents(cents)
    val formatter = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterCurrencyStyle
        locale = NSLocale(localeIdentifier = "es_MX")
        this.currencyCode = currencyCode
    }
    return formatter.stringFromNumber(NSDecimalNumber(string = plain)) ?: plain
}
