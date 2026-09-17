package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
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
