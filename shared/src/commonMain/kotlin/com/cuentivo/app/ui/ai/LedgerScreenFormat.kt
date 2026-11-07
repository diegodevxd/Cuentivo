package com.cuentivo.app.ui.ai

import kotlin.io.encoding.Base64

fun encodeLedgerImage(bytes: ByteArray): String = Base64.Default.encode(bytes)

fun ledgerIsoDate(year: Int, month: Int, day: Int): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

fun formatCents(cents: Long, absolute: Boolean = false): String {
    val digits = cents.toString().removePrefix("-").padStart(3, '0')
    val sign = if (cents < 0 && !absolute) "-" else ""
    return "$" + sign + digits.dropLast(2) + "." + digits.takeLast(2)
}
