package com.cuentivo.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La marca de versión del estado guardado de una revisión.
 *
 * Un guardado sin marca viene de una versión que calculaba la clave de idempotencia con el
 * contenido de la página. Con esa clave no se puede saber qué se escribió, así que la revisión no se
 * puede registrar: el dinero va antes que conservar un borrador.
 *
 * La decisión es pura a propósito. El round-trip real de `Bundle` no puede correr aquí (este
 * proyecto no tiene Robolectric: `Bundle` es un stub que truena), y vive en la instrumentada
 * `com.cuentivo.app.ui.LedgerReviewSaverBundleTest`. Lo que se prueba aquí es la regla.
 */
class LedgerReviewSchemaTest {

    @Test
    fun `un_guardado_sin_marca_no_se_puede_registrar`() {
        assertFalse(LedgerReviewSchema.canRegisterRestoredReview(null))
    }

    @Test
    fun `un_guardado_con_la_marca_actual_si_se_puede_registrar`() {
        assertTrue(LedgerReviewSchema.canRegisterRestoredReview(LedgerReviewSchema.CURRENT))
    }

    @Test
    fun `una_marca_mas_vieja_que_la_actual_no_se_puede_registrar`() {
        assertFalse(LedgerReviewSchema.canRegisterRestoredReview(LedgerReviewSchema.CURRENT - 1))
    }

    @Test
    fun `un_estado_de_la_version_2_ya_no_se_puede_registrar`() {
        // El estado guardado por la versión 2 no llevaba `pageType`, `pageTotals` ni `marginNote`, así
        // que restaurarlo perdería el tipo de hoja y los subtotales declarados —y con ellos la duda que
        // justifican— y la página se registraría como si nada faltara. Se descarta: el lado seguro.
        assertFalse(LedgerReviewSchema.canRegisterRestoredReview(2))
    }

    @Test
    fun `una_marca_mas_nueva_que_la_actual_no_se_puede_registrar`() {
        // Sólo la versión exacta. Un estado guardado por una versión más nueva puede traer campos que
        // esta no entiende, y esto decide si se escribe dinero: ante la duda, no se escribe.
        assertFalse(LedgerReviewSchema.canRegisterRestoredReview(LedgerReviewSchema.CURRENT + 1))
    }
}
