package com.cuentivo.app.platform

/**
 * La firma de Cuentivo en lo que se comparte con un cliente.
 *
 * iOS no tiene Google Play: decirle "Gratis en Google Play" a alguien desde la app de iOS es falso y
 * es motivo de rechazo en la App Store. Y el enlace de la App Store no existe hasta el Día de pago,
 * así que por ahora iOS firma sin enlace. Android conserva el texto de siempre, byte a byte.
 */
expect object StoreListing {
    /** La línea que firma el pie. */
    val footerLine: String

    /** La URL de la tienda, o `null` si esta plataforma todavía no tiene una. */
    val url: String?
}
