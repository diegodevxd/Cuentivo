package com.cuentivo.app.ui.ai

import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.domain.LedgerBatchConfirmation
import com.cuentivo.app.ui.transaction.DueOption
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El plazo elegido en la revision de la libreta, a traves de la maquina de estados.
 *
 * Lo que se prueba aqui es el **hilo**: el valor que la persona marco tiene que sobrevivir a la
 * confirmacion y volver entero si la escritura falla. Si se cayera en algun tramo, la pantalla
 * mostraria un plazo y se registraria otro — o el 15 por defecto — sin que nadie lo viera.
 */
class LedgerDueDatePlazoStateTest {

    private val sessionId = "sesion-plazo"
    private val businessId = "biz-plazo"
    private val todayEpochDay = LocalDate.of(2026, 9, 22).toEpochDay()

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        date = SafeDateCandidate(2026, 9, 1),
    )

    /** Una revision ya abierta, con el plazo puesto a mano. */
    private fun reviewing(dueDateDays: Long?) = AiDebugLabModel(
        state = AiDebugLabState.LedgerReviewing(
            sessionId = sessionId,
            businessId = businessId,
            review = LedgerReviewEngine.build(
                operations = listOf(cleanRow()),
                todayEpochDay = todayEpochDay,
            ),
            // Obligatorio, no opcional: el default de `initialDuplicateCount` lee
            // `registeredOperations` (declarado despues) y sin este argumento llega null.
            registeredOperations = emptyList(),
            dueDateDays = dueDateDays,
        ),
    )

    @Test
    fun `una_pagina_recien_leida_arranca_con_15_marcado`() {
        // Por el camino real: extraccion, no construyendo el estado a mano.
        var model = AiDebugLabModel()
        model = AiDebugLabMachine.onStartLedgerExtraction(model, sessionId, businessId)
        model = AiDebugLabMachine.onLedgerExtractionResult(
            model = model,
            sessionId = sessionId,
            businessId = businessId,
            operations = listOf(cleanRow()),
            generation = model.generation,
            todayEpochDay = todayEpochDay,
        )

        val state = model.state as AiDebugLabState.LedgerReviewing
        assertEquals(
            "15 por defecto, igual que la venta manual: sin fecha no puede vencerse",
            DueOption.FIFTEEN.days,
            state.dueDateDays,
        )
    }

    @Test
    fun `el_plazo_que_la_persona_elige_queda_en_el_estado`() {
        val choosing30 = AiDebugLabMachine.onUpdateLedgerDueDateDays(reviewing(15L), sessionId, 30L)
        val state = choosing30.state as AiDebugLabState.LedgerReviewing

        assertEquals(30L, state.dueDateDays)

        val choosingNone = AiDebugLabMachine.onUpdateLedgerDueDateDays(choosing30, sessionId, null)
        assertNull(
            "sin fecha es una eleccion explicita, no la ausencia de valor",
            (choosingNone.state as AiDebugLabState.LedgerReviewing).dueDateDays,
        )
    }

    @Test
    fun `la_confirmacion_lleva_el_plazo_que_la_pantalla_mostraba`() {
        val outcome = AiDebugLabMachine.onConfirmLedger(reviewing(30L), sessionId, businessId)

        check(outcome is AiLedgerConfirmOutcome.Started) { "tenia que arrancar: $outcome" }
        val confirming = outcome.model.state as AiDebugLabState.LedgerConfirming
        assertEquals(
            "lo que se registra es lo que la persona vio al pulsar",
            30L,
            confirming.dueDateDays,
        )
    }

    @Test
    fun `un_fallo_de_escritura_devuelve_la_revision_con_el_mismo_plazo`() {
        val outcome = AiDebugLabMachine.onConfirmLedger(reviewing(7L), sessionId, businessId)
        check(outcome is AiLedgerConfirmOutcome.Started)

        val afterFailure = AiDebugLabMachine.onLedgerBatchResult(
            model = outcome.model,
            sessionId = sessionId,
            businessId = businessId,
            generation = outcome.generation,
            result = LedgerBatchConfirmation.Failed,
        )
        val reviewingAgain = afterFailure.state as AiDebugLabState.LedgerReviewing

        assertEquals(
            "tras un fallo la persona sigue viendo el plazo que habia elegido, no el 15 por defecto",
            7L,
            reviewingAgain.dueDateDays,
        )
    }

    @Test
    fun `un_borrador_anterior_al_selector_vuelve_con_15_y_no_en_null`() {
        // La pregunta 2 del encargo, sobre la maquina: el guardado no traia el campo (clave
        // ausente) y LedgerReviewSaver.fromBundle lo resuelve a 15. Aqui se fija que esa
        // resolucion es lo que la revision restaurada usa.
        val saved = LedgerReviewSaver.SavedLedgerReview(
            sessionId = sessionId,
            businessId = businessId,
            operations = listOf(cleanRow()),
            originalOperations = listOf(cleanRow()),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            detectedPageDate = null,
            // Sin dueDateDays: el default del propio guardado, que es 15.
        )

        assertEquals(DueOption.FIFTEEN.days, saved.dueDateDays)

        val restored = AiDebugLabMachine.onRestoreLedgerReview(
            model = AiDebugLabModel(),
            sessionId = sessionId,
            businessId = businessId,
            saved = saved,
            existingCustomerBalances = emptyMap(),
            customerCandidates = emptyList(),
            productCandidates = emptyList(),
            registeredOperations = emptyList(),
        )

        assertEquals(
            "al volver del borrador el selector tiene que estar en 15, no en null",
            DueOption.FIFTEEN.days,
            (restored.state as AiDebugLabState.LedgerReviewing).dueDateDays,
        )
    }
}
