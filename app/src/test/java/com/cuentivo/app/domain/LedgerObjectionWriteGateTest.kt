package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerObjectionWriteGateTest {
    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()
    private val nowEpochMillis = 1_700_000_000_000L
    private val existingCustomers = listOf(CustomerCandidate(id = "carmen-id", name = "Carmen"))

    private fun cleanRow() = LedgerOperation(
        lineIndex = 0, proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa", amountCents = "25000",
        rawText = "1010 Cuaderno 250 Rosa", date = null,
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    // Igual que el caso real: el nombre de la clienta que ya existe sólo aparece al margen,
    // entre paréntesis, y el renglón viene asignado a ella.
    private fun marginalNoteRow(confirmed: Boolean = false) = LedgerOperation(
        lineIndex = 1, proposedKind = ProposedLedgerKind.SALE,
        customerName = "Carmen", amountCents = "14900", description = "Cuaderno",
        rawText = "1011 Cuaderno 149 Rosa (Carmen)", date = null,
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
        isObjectionConfirmed = confirmed,
    )

    private fun existingCarmen() = CustomerEntity(
        id = "carmen-id", businessId = "biz-1", name = "Carmen", normalizedName = "carmen",
        phone = null, email = null, address = null, notes = null, creditLimitCents = 0L,
        isActive = true, createdAtEpochMillis = nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis, deletedAtEpochMillis = null,
    )

    private suspend fun confirm(
        operations: List<LedgerOperation>,
        blocked: Set<Int>,
        capture: (PlannedLedgerBatch) -> Unit,
    ): LedgerBatchConfirmation = LedgerBatchCoordinator.confirm(
        businessId = "biz-1", userId = "user-1", importSessionId = "session-test", operations = operations,
        blockedLineIndices = blocked, pageDateEpochDay = pageDateEpochDay,
        modelSaysTextMayBeMissing = false, isPageCompletenessConfirmed = false,
        todayEpochDay = todayEpochDay, nowEpochMillis = nowEpochMillis,
        alreadyRecorded = { false },
        loadCustomersByNormalizedName = { mapOf("carmen" to existingCarmen()) },
        loadOutstandingByCustomerId = { emptyMap() },
        write = { plan -> capture(plan); ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount) },
    )

    @Test
    fun `renglon_con_objecion_sin_confirmar_no_se_escribe_y_el_resto_si`() {
        val operations = listOf(cleanRow(), marginalNoteRow())
        val objections = LedgerDeterministicObjections.evaluate(operations, existingCustomers, emptyList())
        val blocked = LedgerDeterministicObjections.blockedLineIndices(operations, existingCustomers, emptyList())
        assertEquals(setOf(1), blocked)
        assertTrue(objections.any { it.lineIndex == 1 && it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED })

        var captured: PlannedLedgerBatch? = null
        assertEquals(LedgerBatchConfirmation.Failed, kotlinx.coroutines.runBlocking { confirm(operations, blocked) { captured = it } })
        assertNull(captured)
    }

    @Test
    fun `el_mismo_renglon_tras_confirmObjection_si_se_escribe`() {
        val operations = LedgerReviewEdits.confirmObjection(listOf(cleanRow(), marginalNoteRow()), 1)
        val blocked = LedgerDeterministicObjections.blockedLineIndices(operations, existingCustomers, emptyList())
        var captured: PlannedLedgerBatch? = null
        assertEquals(LedgerBatchConfirmation.Recorded(2), kotlinx.coroutines.runBlocking { confirm(operations, blocked) { captured = it } })
        val plan = checkNotNull(captured)
        assertEquals(2, plan.sales.size)
        assertTrue(plan.sales.any { it.customerId == "carmen-id" })
    }

    @Test
    fun `el_planificador_se_niega_a_escribir_un_renglon_bloqueado`() {
        val result = AiLedgerBatchPlanner.plan(
            businessId = "biz-1", userId = "user-1", importSessionId = "session-test",
            operations = listOf(cleanRow(), marginalNoteRow(confirmed = true)),
            blockedLineIndices = setOf(1),
            existingCustomerMap = mapOf("carmen" to existingCarmen()),
            existingOutstandingSalesByCustomerId = emptyMap(), pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay, nowEpochMillis = nowEpochMillis,
        )
        assertTrue(result is BatchPlanningResult.Rejected)
        assertTrue((result as BatchPlanningResult.Rejected).rejections.single() is BatchPlanningRejection.UnresolvedRows)
    }

    @Test
    fun `una_pagina_con_un_renglon_sin_resolver_no_se_registra_ni_se_escribe`() {
        val review = LedgerReviewEngine.build(
            operations = listOf(cleanRow(), marginalNoteRow()),
            existingCustomerBalances = mapOf("carmen" to 20000L),
            pageDateEpochDay = pageDateEpochDay, todayEpochDay = todayEpochDay,
        )
        val objections = LedgerDeterministicObjections.evaluate(review.operations, existingCustomers, emptyList())
        val summary = LedgerImportSummaryCalculator.calculate(review, objections)
        assertEquals(1, summary.unresolvedCount)
        assertFalse("mientras falte un renglón, registrar no se puede pulsar", summary.isConfirmButtonEnabled)

        val blocked = LedgerDeterministicObjections.blockedLineIndices(review.operations, existingCustomers, emptyList())
        var captured: PlannedLedgerBatch? = null
        val recorded = kotlinx.coroutines.runBlocking { confirm(review.operations, blocked) { captured = it } }
        assertEquals(LedgerBatchConfirmation.Failed, recorded)
        assertNull("rechazar es no escribir nada, no escribir la parte buena", captured)
    }

    /**
     * Aísla la guarda del coordinador, que el planificador por sí solo tapa: sin este caso, quitar
     * el filtro de `LedgerBatchCoordinator.confirm` deja la suite en verde.
     */
    @Test
    fun `un_lote_ya_registrado_no_cuenta_los_renglones_bloqueados`() {
        val operations = listOf(cleanRow(), marginalNoteRow())
        val blocked = LedgerDeterministicObjections.blockedLineIndices(operations, existingCustomers, emptyList())
        assertEquals(setOf(1), blocked)

        var captured: PlannedLedgerBatch? = null
        val recorded = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = "biz-1", userId = "user-1", importSessionId = "session-test", operations = operations,
                blockedLineIndices = blocked, pageDateEpochDay = pageDateEpochDay,
        modelSaysTextMayBeMissing = false, isPageCompletenessConfirmed = false,
                todayEpochDay = todayEpochDay, nowEpochMillis = nowEpochMillis,
                // La libreta ya se había escrito: el lote se reconoce y se contestan cuántas
                // operaciones se escribieron de verdad, que no son las bloqueadas.
                alreadyRecorded = { true },
                loadCustomersByNormalizedName = { mapOf("carmen" to existingCarmen()) },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan -> captured = plan; ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount) },
            )
        }

        assertEquals(LedgerBatchConfirmation.Failed, recorded)
        assertNull("un lote ya escrito no se replanifica ni se reescribe", captured)
    }

    @Test
    fun `editar_el_importe_despues_de_confirmar_vuelve_a_bloquear_el_renglon`() {
        val product = ProductCandidate("p1", "Cuaderno", 14900L)
        val confirmed = LedgerReviewEdits.confirmObjection(listOf(marginalNoteRow()), 1)
        assertTrue(LedgerDeterministicObjections.blockedLineIndices(confirmed, existingCustomers, listOf(product)).isEmpty())
        val edited = LedgerReviewEdits.applyAmount(confirmed, 1, "149000")
        val objections = LedgerDeterministicObjections.evaluate(edited, existingCustomers, listOf(product))
        val blocked = LedgerDeterministicObjections.blockedLineIndices(edited, existingCustomers, listOf(product))
        assertTrue(objections.any { it.type == LedgerDeterministicObjectionType.SUSPECT_DECIMAL_SHIFT })
        assertTrue(1 in blocked)
        var captured: PlannedLedgerBatch? = null
        assertEquals(LedgerBatchConfirmation.Failed, kotlinx.coroutines.runBlocking { confirm(edited, blocked) { captured = it } })
        assertNull(captured)
    }

    @Test
    fun `editar_la_clienta_despues_de_confirmar_vuelve_a_bloquear_el_renglon`() {
        val confirmed = LedgerReviewEdits.confirmObjection(listOf(marginalNoteRow()), 1)
        val edited = LedgerReviewEdits.applyCustomer(confirmed, 1, "Lucia")
        val customers = existingCustomers + CustomerCandidate("lucia-id", "Lucia")
        val blocked = LedgerDeterministicObjections.blockedLineIndices(edited, customers, emptyList())
        assertEquals(setOf(1), blocked)
        assertTrue(LedgerDeterministicObjections.evaluate(edited, customers, emptyList()).any {
            it.type == LedgerDeterministicObjectionType.CUSTOMER_CONFIRMATION_REQUIRED
        })
    }

    // ---- v44: fecha imposible bloquea el registro hasta confirmar o corregir ----

    private val hoyEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()

    private fun impossibleDateRow() = LedgerOperation(
        lineIndex = 1, proposedKind = ProposedLedgerKind.SALE,
        customerName = "Rosa", amountCents = "25000",
        rawText = "Rosa 250", date = SafeDateCandidate(2023, 3, 1),
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    @Test
    fun `un_renglon_con_fecha_imposible_no_se_registra_hasta_que_se_confirma_o_se_corrige`() {
        val operations = listOf(cleanRow(), impossibleDateRow())
        val page = LocalDate.of(2026, 9, 10).toEpochDay()
        val blocked = LedgerDeterministicObjections.blockedLineIndices(
            operations, existingCustomers, emptyList(),
            pageDateEpochDay = page, todayEpochDay = hoyEpochDay,
        )
        assertEquals("la fecha 2023 deja el renglón sin resolver", setOf(1), blocked)

        var captured: PlannedLedgerBatch? = null
        assertEquals(
            LedgerBatchConfirmation.Failed,
            kotlinx.coroutines.runBlocking { confirm(operations, blocked) { captured = it } },
        )
        assertNull("sin confirmar no se escribe nada", captured)

        // Corregir la fecha (2026, mismo año que la página, dentro de los 180 días) la levanta.
        val corrected = LedgerReviewEdits.applyDate(operations, 1, SafeDateCandidate(2026, 9, 10), hoyEpochDay)
        val blockedAfterEdit = LedgerDeterministicObjections.blockedLineIndices(
            corrected, existingCustomers, emptyList(),
            pageDateEpochDay = page, todayEpochDay = hoyEpochDay,
        )
        assertTrue("corregir la fecha despeja el renglón", blockedAfterEdit.isEmpty())
        assertEquals(
            LedgerBatchConfirmation.Recorded(2),
            kotlinx.coroutines.runBlocking { confirm(corrected, blockedAfterEdit) { captured = it } },
        )
        assertNotNull(captured)
    }

    @Test
    fun `confirmar_la_objecion_de_fecha_tambien_desbloquea_el_renglon`() {
        val operations = listOf(cleanRow(), impossibleDateRow())
        val page = LocalDate.of(2026, 9, 10).toEpochDay()
        val confirmed = LedgerReviewEdits.confirmObjection(operations, 1)
        val blocked = LedgerDeterministicObjections.blockedLineIndices(
            confirmed, existingCustomers, emptyList(),
            pageDateEpochDay = page, todayEpochDay = hoyEpochDay,
        )
        assertTrue("confirmar la objeción levanta el bloqueo", blocked.isEmpty())

        var captured: PlannedLedgerBatch? = null
        assertEquals(
            LedgerBatchConfirmation.Recorded(2),
            kotlinx.coroutines.runBlocking { confirm(confirmed, blocked) { captured = it } },
        )
        assertNotNull("la libreta vieja de verdad se puede registrar tras confirmar", captured)
    }
}
