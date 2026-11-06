package com.cuentivo.app.platform

import com.cuentivo.app.domain.StatementImageContent

/**
 * Lo que la app necesita del sistema para mandar algo afuera o abrir una app del teléfono.
 *
 * Es una interfaz y no un `expect`/`actual` a propósito: Android necesita una `Activity` y un
 * `requestCode` para recuperar la hoja de compartir, e iOS necesita una ventana que presentar. Cada
 * plataforma construye la suya con lo que tiene a mano.
 */
interface Sharer {
    fun shareText(subject: String, body: String)

    fun shareStatementImage(subject: String, shortText: String, fullText: String, content: StatementImageContent)

    /** Abre el marcador con el número escrito; nunca marca solo. */
    fun dial(phone: String)

    fun openUrl(url: String)
}
