package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capa de la máquina para la duda de hoja incompleta.
 *
 * La pantalla ya apaga el botón, pero esconder un botón no es un control: la transición tiene que
 * negarse por sí sola. Y la afirmación de la persona tiene que poder darse y sobrevivir.
 */
class LedgerPageCompletenessGateTest {

    private val sessionId = "sesion-1"
    private val businessId = "biz-1"
    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "1010 Cuaderno 250 Rosa",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun model(pageIncomplete: Boolean): AiDebugLabModel = AiDebugLabModel(
        state = AiDebugLabState.LedgerReviewing(
            sessionId = sessionId,
            businessId = businessId,
            review = LedgerReviewEngine.build(
                operations = listOf(cleanRow()),
                pageIncomplete = pageIncomplete,
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
            ),
            registeredOperations = emptyList(),
        ),
    )

    @Test
    fun `la_maquina_se_niega_a_registrar_una_hoja_dudosa_sin_confirmar`() {
        val model = model(pageIncomplete = true)
        val state = model.state as AiDebugLabState.LedgerReviewing
        assertFalse(state.isPageCompletenessConfirmed)

        assertEquals(
            AiLedgerConfirmOutcome.Ignored,
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId),
        )
    }

    @Test
    fun `tras_confirmar_la_hoja_la_maquina_arranca_el_registro`() {
        val confirmed = AiDebugLabMachine.onConfirmLedgerPageCompleteness(model(pageIncomplete = true), sessionId, businessId)
        val state = confirmed.state as AiDebugLabState.LedgerReviewing
        assertTrue("la afirmación de la persona tiene que quedar puesta", state.isPageCompletenessConfirmed)

        assertTrue(
            AiDebugLabMachine.onConfirmLedger(confirmed, sessionId, businessId) is AiLedgerConfirmOutcome.Started,
        )
    }

    @Test
    fun `una_hoja_sin_duda_no_necesita_confirmacion_para_arrancar`() {
        val model = model(pageIncomplete = false)

        assertTrue(
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId) is AiLedgerConfirmOutcome.Started,
        )
    }

    // ---- Renglones descartados por el parser (el punto 1) ----

    /**
     * Un renglón que el parser descartó no está en `operations`: si la máquina no mira `discards`, la
     * página se registra sin él y nadie se entera.
     */
    private fun modelWithDiscards(): AiDebugLabModel = AiDebugLabModel(
        state = AiDebugLabState.LedgerReviewing(
            sessionId = sessionId,
            businessId = businessId,
            review = LedgerReviewEngine.build(
                operations = listOf(cleanRow()),
                discards = listOf(
                    com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic(
                        inputIndex = 1,
                        reason = com.cuentivo.app.ai.ledger.LedgerDiscardReason.entries.first(),
                    ),
                ),
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
            ),
            registeredOperations = emptyList(),
        ),
    )

    @Test
    fun `la_maquina_se_niega_a_registrar_con_renglones_descartados_sin_confirmar`() {
        val model = modelWithDiscards()

        assertEquals(
            AiLedgerConfirmOutcome.Ignored,
            AiDebugLabMachine.onConfirmLedger(model, sessionId, businessId),
        )
    }

    @Test
    fun `tras_confirmar_la_pagina_con_descartes_la_maquina_arranca_el_registro`() {
        val confirmed = AiDebugLabMachine.onConfirmLedgerPageCompleteness(modelWithDiscards(), sessionId, businessId)

        assertTrue(
            AiDebugLabMachine.onConfirmLedger(confirmed, sessionId, businessId) is AiLedgerConfirmOutcome.Started,
        )
    }
}
