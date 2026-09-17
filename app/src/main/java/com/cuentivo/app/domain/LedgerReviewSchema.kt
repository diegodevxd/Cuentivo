package com.cuentivo.app.domain

/** Sólo el esquema exacto puede registrar dinero; versiones nuevas pueden tener campos desconocidos. */
object LedgerReviewSchema {
    /** La versión 1 de la ronda anterior deja de ser registrable al cambiar el estado guardado. */
    const val CURRENT = 2

    fun canRegisterRestoredReview(savedVersion: Int?): Boolean =
        savedVersion == CURRENT
}
