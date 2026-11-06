package com.cuentivo.app.platform

/**
 * El pie de iOS: sin Google Play.
 *
 * Decirle "Gratis en Google Play" a un cliente desde la app de iOS es falso y es motivo de rechazo
 * en la App Store. El enlace de la App Store no existe hasta el Día de pago, así que por ahora iOS
 * firma sin enlace.
 */
actual object StoreListing {
    actual val footerLine: String = "Enviado con Cuentivo"

    actual val url: String? = null
}
