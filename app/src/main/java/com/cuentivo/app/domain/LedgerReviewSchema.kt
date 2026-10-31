package com.cuentivo.app.domain

/** Sólo el esquema exacto puede registrar dinero; versiones nuevas pueden tener campos desconocidos. */
object LedgerReviewSchema {
    /**
     * La 3 es la primera que guarda `pageType`, `pageTotals` y `marginNote`.
     *
     * Subirla deja **no registrables** las revisiones guardadas por la 1 y por la 2 — se descartan, y
     * la pantalla ya ofrece descartarlas. Es el lado seguro a propósito: una revisión restaurada de la
     * versión 2 perdería el tipo de hoja y los subtotales declarados, y con ellos la duda que
     * justifican, así que se registraría como si nada faltara. Antes una página perdida que un cargo
     * silencioso al cliente equivocado.
     */
    const val CURRENT = 3

    fun canRegisterRestoredReview(savedVersion: Int?): Boolean =
        savedVersion == CURRENT
}
