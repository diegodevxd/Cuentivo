package com.cuentivo.app.platform

/**
 * El pie de Android: el de siempre, byte a byte.
 *
 * La URL viene de aquí y no de un `const` privado del generador de textos porque iOS necesita decir
 * que **no** la tiene, y eso sólo se puede decidir por plataforma.
 */
actual object StoreListing {
    actual val footerLine: String = "Enviado con Cuentivo · Gratis en Google Play"

    actual val url: String? = "https://play.google.com/store/apps/details?id=com.cuentivo.app"
}
