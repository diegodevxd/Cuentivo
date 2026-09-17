package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerPageType
import com.cuentivo.app.ai.ledger.LedgerReview
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una página que el modelo marca como incompleta **no se registra en silencio**.
 *
 * El modelo avisa "puede faltar texto" a menudo aunque haya leído todo, así que bloquearla para
 * siempre dejaría páginas imposibles de registrar. La salida es la misma que ya tienen las objeciones
 * de renglón: una confirmación explícita, aquí a nivel de página.
 *
 * Estas pruebas cubren las dos primeras capas (el botón y el coordinador) y la regla pura. La capa de
 * la máquina vive en `com.cuentivo.app.ui.ai.LedgerPageCompletenessGateTest`.
 */
class LedgerPageCompletenessTest {

    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()
    private val nowEpochMillis = 1_700_000_000_000L

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "1010 Cuaderno 250 Rosa",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun review(pageIncomplete: Boolean, unreadTextRemaining: Boolean = false) =
        LedgerReviewEngine.build(
            operations = listOf(cleanRow()),
            unreadTextRemaining = unreadTextRemaining,
            pageIncomplete = pageIncomplete,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )

    @Test
    fun `la regla pura solo exige confirmacion cuando el modelo dudo`() {
        assertTrue(LedgerPageCompleteness.isRegistrable(modelSaysTextMayBeMissing = false, isPageCompletenessConfirmed = false))
        assertFalse(LedgerPageCompleteness.isRegistrable(modelSaysTextMayBeMissing = true, isPageCompletenessConfirmed = false))
        assertTrue(LedgerPageCompleteness.isRegistrable(modelSaysTextMayBeMissing = true, isPageCompletenessConfirmed = true))
    }

    // ---- Los descartes del parser (el hueco que ya existía) ----

    private fun reviewWithDiscards(): LedgerReview = LedgerReviewEngine.build(
        operations = listOf(cleanRow()),
        discards = listOf(
            LedgerDiscardDiagnostic(
                inputIndex = 1,
                reason = LedgerDiscardReason.entries.first(),
            ),
        ),
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
    )

    @Test
    fun `un_renglon_descartado_exige_la_misma_confirmacion_de_pagina`() {
        // Sin esto, el renglón descartado DESAPARECE y la página se registra sin él: nadie se entera.
        val review = reviewWithDiscards()

        assertTrue(
            "un descarte tiene que contar como motivo",
            LedgerPageCompleteness.confirmationReasons(review).contains(LedgerPageConfirmationReason.UNREADABLE_ROWS),
        )
        assertTrue(LedgerPageCompleteness.requiresPageConfirmation(review))
    }

    @Test
    fun `sin_descartes_y_sin_duda_la_pagina_no_pide_nada`() {
        // La otra mitad: que esto no infle la duda de páginas limpias.
        val clean = review(pageIncomplete = false)

        assertTrue(LedgerPageCompleteness.confirmationReasons(clean).isEmpty())
        assertFalse(LedgerPageCompleteness.requiresPageConfirmation(clean))
    }

    @Test
    fun `el_boton_sigue_desactivado_por_un_descarte_hasta_que_se_confirma`() {
        val review = reviewWithDiscards()

        val before = LedgerImportSummaryCalculator.calculate(review, emptyList(), isPageCompletenessConfirmed = false)
        assertFalse("con un renglón descartado no se puede registrar", before.isConfirmButtonEnabled)

        val after = LedgerImportSummaryCalculator.calculate(review, emptyList(), isPageCompletenessConfirmed = true)
        assertTrue("con la palabra de la persona, sí", after.isConfirmButtonEnabled)
    }

    // ---- Una hoja que no es de cuentas (punto 4) ----

    @Test
    fun `una_hoja_que_no_es_de_cuentas_y_trae_renglones_pide_confirmacion`() {
        val priceList = LedgerReviewEngine.build(
            operations = listOf(cleanRow()),
            pageType = LedgerPageType.COST_OR_PRICE_LIST,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )

        assertTrue(
            LedgerPageCompleteness.confirmationReasons(priceList).contains(LedgerPageConfirmationReason.NOT_AN_ACCOUNTS_PAGE),
        )
        assertTrue(LedgerPageCompleteness.requiresPageConfirmation(priceList))
    }

    @Test
    fun `una_hoja_que_no_es_de_cuentas_sin_renglones_no_pide_nada`() {
        // Una lista de precios sin nada que registrar no necesita que nadie confirme nada.
        val emptyPriceList = LedgerReviewEngine.build(
            operations = emptyList(),
            pageType = LedgerPageType.COST_OR_PRICE_LIST,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )

        assertFalse(LedgerPageCompleteness.requiresPageConfirmation(emptyPriceList))
    }

    @Test
    fun `una_hoja_de_cuentas_normal_no_pide_confirmacion_por_su_tipo`() {
        val accounts = LedgerReviewEngine.build(
            operations = listOf(cleanRow()),
            pageType = LedgerPageType.ACCOUNTS,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )

        assertFalse(LedgerPageCompleteness.confirmationReasons(accounts).contains(LedgerPageConfirmationReason.NOT_AN_ACCOUNTS_PAGE))
        assertFalse(LedgerPageCompleteness.requiresPageConfirmation(accounts))
    }

    @Test
    fun `texto sin leer tambien cuenta como duda del modelo`() {
        assertTrue(LedgerPageCompleteness.modelSaysTextMayBeMissing(review(pageIncomplete = false, unreadTextRemaining = true)))
        assertTrue(LedgerPageCompleteness.modelSaysTextMayBeMissing(review(pageIncomplete = true)))
        assertFalse(LedgerPageCompleteness.modelSaysTextMayBeMissing(review(pageIncomplete = false)))
    }

    // ---- Capa 1: el botón ("Registrar" desactivado y dice por qué) ----

    @Test
    fun `sin_confirmar_la_hoja_el_boton_no_se_activa`() {
        val withDoubt = review(pageIncomplete = true)
        val result = LedgerImportSummaryCalculator.calculate(withDoubt, emptyList(), isPageCompletenessConfirmed = false)

        assertTrue("el aviso tiene que seguir saliendo", result.pageIncompleteNoticeRequired)
        assertFalse("una hoja sin confirmar no se puede registrar", result.isConfirmButtonEnabled)
    }

    @Test
    fun `al_confirmar_la_hoja_el_boton_se_activa`() {
        val withDoubt = review(pageIncomplete = true)
        val result = LedgerImportSummaryCalculator.calculate(withDoubt, emptyList(), isPageCompletenessConfirmed = true)

        assertTrue(result.isConfirmButtonEnabled)
        assertTrue(result.isPageCompletenessConfirmed)
    }

    @Test
    fun `una_hoja_que_el_modelo_no_dudo_no_necesita_confirmacion`() {
        val clean = review(pageIncomplete = false)
        val result = LedgerImportSummaryCalculator.calculate(clean, emptyList(), isPageCompletenessConfirmed = false)

        assertFalse(result.pageIncompleteNoticeRequired)
        assertTrue(result.isConfirmButtonEnabled)
    }

    // ---- Capa 2: el coordinador (parámetro obligatorio) ----

    private suspend fun confirm(
        modelSaysTextMayBeMissing: Boolean,
        isPageCompletenessConfirmed: Boolean,
    ): Pair<LedgerBatchConfirmation, Int> {
        var writes = 0
        val result = LedgerBatchCoordinator.confirm(
            businessId = "biz-1",
            userId = "user-1",
            importSessionId = "sesion-1",
            operations = listOf(cleanRow()),
            blockedLineIndices = emptySet(),
            modelSaysTextMayBeMissing = modelSaysTextMayBeMissing,
            isPageCompletenessConfirmed = isPageCompletenessConfirmed,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            alreadyRecorded = { false },
            loadCustomersByNormalizedName = { emptyMap() },
            loadOutstandingByCustomerId = { emptyMap() },
            write = { plan ->
                writes += 1
                ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
            },
        )
        return result to writes
    }

    @Test
    fun `el_coordinador_rechaza_una_hoja_dudosa_sin_confirmar_y_no_escribe`() {
        val (result, writes) = kotlinx.coroutines.runBlocking {
            confirm(modelSaysTextMayBeMissing = true, isPageCompletenessConfirmed = false)
        }

        assertEquals(LedgerBatchConfirmation.Failed, result)
        assertEquals("no puede escribir nada", 0, writes)
    }

    @Test
    fun `el_coordinador_escribe_cuando_la_persona_confirmo_la_hoja`() {
        val (result, writes) = kotlinx.coroutines.runBlocking {
            confirm(modelSaysTextMayBeMissing = true, isPageCompletenessConfirmed = true)
        }

        assertEquals(LedgerBatchConfirmation.Recorded(1), result)
        assertEquals(1, writes)
    }

    @Test
    fun `el_coordinador_escribe_cuando_el_modelo_no_dudo_aunque_nadie_confirmara`() {
        val (result, writes) = kotlinx.coroutines.runBlocking {
            confirm(modelSaysTextMayBeMissing = false, isPageCompletenessConfirmed = false)
        }

        assertEquals(LedgerBatchConfirmation.Recorded(1), result)
        assertEquals(1, writes)
    }
}
