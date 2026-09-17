package com.cuentivo.app.domain

import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UnreadableLedgerField
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una página se registra **entera o no se registra**.
 *
 * Todo aquí atraviesa `LedgerBatchCoordinator.confirm`, que es lo que la app ejecuta de verdad. El
 * defecto que estas pruebas cierran ya existía antes de v41 y no salta a la vista: si se puede
 * escribir un subconjunto de la página, ese subconjunto cambia entre intentos, y un commit cuyo
 * resultado se perdió (el proceso murió justo después de guardar) se repite al reintentar, porque la
 * clave sale distinta. Un cargo termina escrito dos veces.
 */
class LedgerSessionKeyIdempotencyTest {

    private val businessId = "biz-1"
    private val userId = "user-1"
    private val pageDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay()
    private val todayEpochDay = LocalDate.of(2026, 9, 16).toEpochDay()
    private val nowEpochMillis = 1_700_000_000_000L

    private fun sale(lineIndex: Int, name: String, amountCents: String) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = amountCents,
        rawText = "linea $lineIndex $name $amountCents",
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    /** Los planes que la escritura recibió, para poder mirar sus claves y su contenido. */
    private val writtenPlans = mutableListOf<PlannedLedgerBatch>()

    private suspend fun confirm(
        operations: List<LedgerOperation>,
        importSessionId: String,
        alreadyRecordedKeys: Set<String> = emptySet(),
    ): LedgerBatchConfirmation = LedgerBatchCoordinator.confirm(
        businessId = businessId,
        userId = userId,
        importSessionId = importSessionId,
        operations = operations,
        blockedLineIndices = emptySet(),
        modelSaysTextMayBeMissing = false,
        isPageCompletenessConfirmed = false,
        pageDateEpochDay = pageDateEpochDay,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        alreadyRecorded = { key -> key in alreadyRecordedKeys },
        loadCustomersByNormalizedName = { emptyMap() },
        loadOutstandingByCustomerId = { emptyMap() },
        write = { plan ->
            writtenPlans += plan
            ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
        },
    )

    @Test
    fun `un_reintento_de_la_misma_pagina_con_contenido_editado_no_escribe_nada`() {
        // Intento 1: la página se escribe. El proceso muere DESPUÉS del commit, así que la pantalla
        // nunca llega a procesar el resultado y la revisión se restaura sin saber que ya se guardó.
        val first = kotlinx.coroutines.runBlocking { confirm(listOf(sale(0, "Rosa", "25000")), "sesion-1") }
        assertEquals(LedgerBatchConfirmation.Recorded(1), first)
        assertEquals(1, writtenPlans.size)
        val keyThatLanded = writtenPlans.single().batchIdempotencyKey
        val totalThatLanded = writtenPlans.single().sales.single().totalCents

        // Intento 2: la persona corrige el importe y reintenta. La clave sale de la sesión, así que
        // el libro reconoce la página: no se escribe nada y la edición no se aplica.
        val edited = listOf(sale(0, "Rosa", "99000"))
        val second = kotlinx.coroutines.runBlocking {
            confirm(edited, "sesion-1", alreadyRecordedKeys = setOf(keyThatLanded))
        }

        assertEquals(LedgerBatchConfirmation.AlreadyRecorded(1), second)
        assertEquals("el reintento no puede volver a escribir", 1, writtenPlans.size)
        assertEquals("lo registrado es lo del primer intento, no lo editado", 25_000L, totalThatLanded)
    }

    @Test
    fun `misma_sesion_con_contenido_distinto_produce_la_misma_clave`() {
        kotlinx.coroutines.runBlocking { confirm(listOf(sale(0, "Rosa", "25000")), "sesion-1") }
        kotlinx.coroutines.runBlocking { confirm(listOf(sale(0, "Rosa", "99000")), "sesion-1") }

        assertEquals(2, writtenPlans.size)
        // El contenido SÍ cambió: sin esta comprobación, comparar las claves no probaría nada.
        assertNotEquals(
            "las dos páginas tienen que diferir en el contenido para que la prueba valga",
            writtenPlans[0].sales.single().totalCents,
            writtenPlans[1].sales.single().totalCents,
        )
        assertEquals(
            "editar el contenido no puede crear una página nueva",
            writtenPlans[0].batchIdempotencyKey,
            writtenPlans[1].batchIdempotencyKey,
        )
    }

    @Test
    fun `sesion_distinta_con_contenido_identico_produce_clave_distinta`() {
        val sameContent = listOf(sale(0, "Rosa", "25000"))
        kotlinx.coroutines.runBlocking { confirm(sameContent, "sesion-1") }
        kotlinx.coroutines.runBlocking { confirm(sameContent, "sesion-2") }

        assertEquals(2, writtenPlans.size)
        // Mismo contenido, a propósito: dos fotos distintas de la misma libreta son dos páginas.
        assertEquals(
            writtenPlans[0].sales.single().totalCents,
            writtenPlans[1].sales.single().totalCents,
        )
        assertNotEquals(
            "una segunda foto legítima tiene que poder escribirse",
            writtenPlans[0].batchIdempotencyKey,
            writtenPlans[1].batchIdempotencyKey,
        )
    }

    @Test
    fun `una_reserva_perdida_se_reporta_como_ya_registrada_no_como_nueva`() {
        // Dos confirmaciones simultáneas pueden pasar las dos la consulta previa; la segunda pierde
        // la reserva dentro de la base y el DAO contesta AlreadyRecorded. Traducirlo a "Recorded"
        // diría "registradas" sobre algo que no se acaba de registrar: un mensaje falso, aunque el
        // dinero no se duplique. Y no se puede volver a encolar lo que no escribimos.
        var enqueued = 0
        val result = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = businessId,
                userId = userId,
                importSessionId = "sesion-1",
                operations = listOf(sale(0, "Rosa", "25000")),
                blockedLineIndices = emptySet(),
                modelSaysTextMayBeMissing = false,
                isPageCompletenessConfirmed = false,
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
                nowEpochMillis = nowEpochMillis,
                alreadyRecorded = { false },
                loadCustomersByNormalizedName = { emptyMap() },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan ->
                    ExpectedLedgerBatchResult.AlreadyRecorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
                },
                enqueue = { enqueued += 1 },
            )
        }

        assertEquals(LedgerBatchConfirmation.AlreadyRecorded(1), result)
        assertEquals("no se encola una página que no escribimos", 0, enqueued)
    }

    @Test
    fun `una_pagina_con_un_renglon_sin_resolver_se_rechaza_entera_y_no_escribe_nada`() {
        // Un renglón que nadie pudo leer junto a uno limpio. Antes se escribía el limpio y se omitía
        // el otro: eso es exactamente lo que hacía cambiar el conjunto entre intentos.
        val unreadable = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )

        val result = kotlinx.coroutines.runBlocking {
            confirm(listOf(sale(0, "Rosa", "25000"), unreadable), "sesion-1")
        }

        assertEquals(LedgerBatchConfirmation.Failed, result)
        assertTrue(
            "un lote con un renglón sin resolver no puede escribir nada",
            writtenPlans.isEmpty(),
        )
    }

    /**
     * Aísla la guarda del coordinador, que el planificador por sí solo tapa.
     *
     * Aquí el libro contesta que la página **ya está escrita**. Sin la guarda del coordinador el
     * resultado sería `AlreadyRecorded`, que la pantalla cerraría como un registro exitoso: una
     * página con un renglón sin resolver no se puede registrar, ni siquiera para decir que ya estaba.
     */
    @Test
    fun `el_coordinador_rechaza_la_pagina_sin_resolver_antes_de_mirar_el_libro`() {
        val unreadable = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )

        val result = kotlinx.coroutines.runBlocking {
            LedgerBatchCoordinator.confirm(
                businessId = businessId,
                userId = userId,
                importSessionId = "sesion-1",
                operations = listOf(sale(0, "Rosa", "25000"), unreadable),
                blockedLineIndices = emptySet(),
                modelSaysTextMayBeMissing = false,
                isPageCompletenessConfirmed = false,
                pageDateEpochDay = pageDateEpochDay,
                todayEpochDay = todayEpochDay,
                nowEpochMillis = nowEpochMillis,
                alreadyRecorded = { true },
                loadCustomersByNormalizedName = { emptyMap() },
                loadOutstandingByCustomerId = { emptyMap() },
                write = { plan ->
                    writtenPlans += plan
                    ExpectedLedgerBatchResult.Recorded(plan.batchIdempotencyKey, plan.executableOperationsCount)
                },
            )
        }

        assertEquals(LedgerBatchConfirmation.Failed, result)
        assertTrue("no puede escribir ni contestar que ya estaba", writtenPlans.isEmpty())
    }

    @Test
    fun `el_planificador_rechaza_el_lote_entero_y_nombra_los_renglones_sin_resolver`() {
        val unreadable = LedgerOperation(
            lineIndex = 2,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Juan",
            amountCents = null,
            unreadableFields = listOf(UnreadableLedgerField.AMOUNT),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "sesion-1",
            operations = listOf(sale(0, "Rosa", "25000"), unreadable),
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )

        assertTrue("debe rechazar, no saltar el renglón", result is BatchPlanningResult.Rejected)
        val rejection = (result as BatchPlanningResult.Rejected).rejections.single()
        assertTrue(rejection is BatchPlanningRejection.UnresolvedRows)
        assertEquals(listOf(2), (rejection as BatchPlanningRejection.UnresolvedRows).lineIndices)
    }
}
