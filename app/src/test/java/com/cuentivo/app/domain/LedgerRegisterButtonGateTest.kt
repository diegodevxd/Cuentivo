package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Registrar" sólo se activa cuando la página entera está resuelta.
 *
 * Mientras quede un renglón sin resolver, el botón dice cuántos faltan. Si se pudiera registrar la
 * parte resuelta, el conjunto escribiría distinto en cada intento — y ahí es donde un commit cuyo
 * resultado se perdió se repite y un cargo se escribe dos veces. Se acabó el "M omitidos": ya no se
 * omite nada en silencio.
 */
class LedgerRegisterButtonGateTest {

    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()
    private val existingCustomers = listOf(CustomerCandidate(id = "carmen-id", name = "Carmen"))

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa",
        amountCents = "25000",
        rawText = "1010 Cuaderno 250 Rosa",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    /** El nombre de la clienta existente sólo aparece al margen, entre paréntesis: hay objeción. */
    private fun objectedRow() = LedgerOperation(
        lineIndex = 1,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = "Carmen",
        amountCents = "14900",
        description = "Cuaderno",
        rawText = "1011 Cuaderno 149 Rosa (Carmen)",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    private fun summaryFor(operations: List<LedgerOperation>): LedgerImportSummaryResult {
        val review = LedgerReviewEngine.build(
            operations = operations,
            existingCustomerBalances = mapOf("carmen" to 20000L),
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
        )
        val objections = LedgerDeterministicObjections.evaluate(review.operations, existingCustomers, emptyList())
        return LedgerImportSummaryCalculator.calculate(review, objections)
    }

    @Test
    fun `con_un_renglon_sin_resolver_el_boton_esta_desactivado_y_dice_cuantos_faltan`() {
        val result = summaryFor(listOf(cleanRow(), objectedRow()))

        assertEquals(1, result.unresolvedCount)
        assertFalse("no se puede registrar media página", result.isConfirmButtonEnabled)
    }

    @Test
    fun `al_confirmar_la_objecion_el_boton_se_activa`() {
        val resolved = LedgerReviewEdits.confirmObjection(listOf(cleanRow(), objectedRow()), 1)
        val result = summaryFor(resolved)

        assertEquals(0, result.unresolvedCount)
        assertTrue("con todo resuelto, registrar tiene que poder pulsarse", result.isConfirmButtonEnabled)
    }

    @Test
    fun `al_borrar_el_renglon_dudoso_el_boton_tambien_se_activa`() {
        // Borrar es una forma de resolver: la persona decidió que ese renglón no va. El renglón
        // borrado ya no está en la revisión, así que la página queda entera.
        val afterRemoval = LedgerReviewEdits.removeRow(listOf(cleanRow(), objectedRow()), 1)
        val result = summaryFor(afterRemoval)

        assertEquals(0, result.unresolvedCount)
        assertTrue(result.isConfirmButtonEnabled)
    }

    @Test
    fun `un_renglon_ilegible_tambien_bloquea_la_pagina_entera`() {
        val unreadable = LedgerOperation(
            lineIndex = 2,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(com.cuentivo.app.ai.ledger.UnreadableLedgerField.AMOUNT),
        )
        val result = summaryFor(listOf(cleanRow(), unreadable))

        assertEquals(1, result.unresolvedCount)
        assertFalse(result.isConfirmButtonEnabled)
    }
}
