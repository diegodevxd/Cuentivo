package com.cuentivo.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.LedgerBatchCoordinator
import com.cuentivo.app.domain.SaleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restoring a review and confirming it must write exactly once.
 *
 * The batch key and the operations table already guard a double write, but the encargo asks for
 * the test anyway, and it is the right ask: restore-then-confirm is a path nobody walked before
 * this phase, and "the guard should catch it" is how the fifth green lie of this project started.
 * This runs the real production write — the same [LedgerBatchCoordinator.confirm] MainActivity
 * calls — against a real Room database, twice, and counts the movements that landed.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRestoreConfirmSingleWriteTest {

    private lateinit var database: CuentivoDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /** The write MainActivity performs when a reviewed page is confirmed. */
    private suspend fun confirmBatch(businessId: String, operations: List<LedgerOperation>, pageDateEpochDay: Long?): com.cuentivo.app.domain.LedgerBatchConfirmation {
        val dao = database.financialDao()
        val repository = SaleRepository(dao)
        val today = java.time.LocalDate.now().toEpochDay()
        return LedgerBatchCoordinator.confirm(
            businessId = businessId,
            userId = "user-1", importSessionId = "session-test",
            operations = operations,
            blockedLineIndices = emptySet(),
            // La hoja de estas pruebas la leyó el modelo entera; la duda de página tiene su propia
            // prueba en JVM (`LedgerPageCompletenessTest`).
            modelSaysTextMayBeMissing = false,
            isPageCompletenessConfirmed = false,
            pageDateEpochDay = pageDateEpochDay,
            todayEpochDay = today,
            nowEpochMillis = System.currentTimeMillis(),
            alreadyRecorded = { key -> dao.operationResultId(key) != null },
            loadCustomersByNormalizedName = {
                database.customerDao().activeWithBalances(businessId)
                    .associate { row -> row.customer.normalizedName to row.customer }
            },
            loadOutstandingByCustomerId = {
                database.customerDao().activeWithBalances(businessId).associate { row ->
                    row.customer.id to dao.outstandingSalesForCustomer(businessId, row.customer.id)
                        .map { com.cuentivo.app.domain.OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
                }
            },
            write = { plan -> repository.registerLedgerBatch(plan) },
        )
    }

    @Test
    fun confirming_a_restored_review_twice_writes_the_movements_once() = runBlocking {
        val businessId = "business-1"
        database.businessDao().insert(
            com.cuentivo.app.data.local.BusinessEntity(
                id = businessId,
                name = "Mi negocio",
                ownerUserId = "user-1",
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            ),
        )
        val pageDate = java.time.LocalDate.of(2026, 7, 30).toEpochDay()
        // The page a restored review would carry: one clean sale, dated by the page.
        val operations = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa Martinez",
                amountCents = "25000",
                date = null,
            ),
        )

        // First confirm: the write the person meant to make.
        val first = confirmBatch(businessId, operations, pageDate)
        assertNotNull("la primera confirmacion debe escribir", first)
        assertEquals(
            "la primera confirmacion escribe la pagina",
            com.cuentivo.app.domain.LedgerBatchConfirmation.Recorded(1),
            first,
        )

        val movementsAfterFirst = database.financialDao().allMovementsForBackfill(businessId).size
        assertEquals(1, movementsAfterFirst)

        // Second confirm: the same page again, as a restore-then-confirm would replay it. The
        // batch key must recognize it as the page that already landed.
        val second = confirmBatch(businessId, operations, pageDate)
        assertEquals(
            "la clave de la sesion reconoce la pagina ya escrita",
            com.cuentivo.app.domain.LedgerBatchConfirmation.AlreadyRecorded(1),
            second,
        )

        val movementsAfterSecond = database.financialDao().allMovementsForBackfill(businessId).size
        assertEquals(
            "restaurar y confirmar no puede escribir dos veces: nada se edita, se corrige con movimientos",
            1,
            movementsAfterSecond,
        )
    }

    /**
     * El camino para el que existe esta ronda: se escribe la página, la respuesta se pierde, se
     * restaura la revisión desde el `Bundle` y se reintenta.
     *
     * Lo que se comprueba es lo que el usuario vive: no se escribe nada nuevo, y la pantalla dice
     * "ya estaba registrada" en vez de "registradas". Decir "registradas" sobre algo que no se acaba
     * de registrar es un mensaje falso, aunque la reserva impida duplicar el dinero.
     */
    @Test
    fun a_retry_after_a_lost_response_restores_from_the_bundle_and_says_already_recorded() = runBlocking {
        val businessId = "business-1"
        database.businessDao().insert(
            com.cuentivo.app.data.local.BusinessEntity(
                id = businessId,
                name = "Mi negocio",
                ownerUserId = "user-1",
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            ),
        )
        val pageDate = java.time.LocalDate.of(2026, 7, 30).toEpochDay()
        val today = java.time.LocalDate.now().toEpochDay()
        val operations = listOf(
            LedgerOperation(
                lineIndex = 0,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa Martinez",
                amountCents = "25000",
                date = null,
            ),
        )

        // 1. La persona registra la página.
        assertEquals(
            com.cuentivo.app.domain.LedgerBatchConfirmation.Recorded(1),
            confirmBatch(businessId, operations, pageDate),
        )

        // 2. La respuesta se pierde: nadie procesa el resultado. Lo único que sobrevive es el guardado.
        val saved = com.cuentivo.app.ui.ai.LedgerReviewSaver.SavedLedgerReview(
            sessionId = "session-test",
            businessId = businessId,
            operations = operations,
            originalOperations = operations,
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            pageDateEpochDay = pageDate,
            todayEpochDay = today,
            detectedPageDate = null,
            reviewSchemaVersion = com.cuentivo.app.domain.LedgerReviewSchema.CURRENT,
        )
        val restored = com.cuentivo.app.ui.ai.LedgerReviewSaver.fromBundle(
            com.cuentivo.app.ui.ai.LedgerReviewSaver.toBundle(saved),
        )
        assertNotNull("el guardado tiene que volver del Bundle", restored)

        val restoredModel = com.cuentivo.app.ui.ai.AiDebugLabMachine.onRestoreLedgerReview(
            model = com.cuentivo.app.ui.ai.AiDebugLabModel(),
            sessionId = "session-test",
            businessId = businessId,
            saved = restored!!,
            existingCustomerBalances = emptyMap(),
            customerCandidates = emptyList(),
            productCandidates = emptyList(),
            registeredOperations = emptyList(),
        )

        // 3. Se reintenta con la misma sesión: el libro reconoce la página y no se escribe nada.
        val retry = confirmBatch(businessId, operations, pageDate)
        assertEquals(
            "el reintento tiene que reconocer la pagina ya escrita",
            com.cuentivo.app.domain.LedgerBatchConfirmation.AlreadyRecorded(1),
            retry,
        )
        assertEquals(
            "no se duplica ni un movimiento",
            1,
            database.financialDao().allMovementsForBackfill(businessId).size,
        )
        assertEquals("ni una venta", 1, database.financialDao().registeredSaleCount(businessId))

        // 4. Y la pantalla lo dice: "ya registrada", no "registradas".
        val confirming = com.cuentivo.app.ui.ai.AiDebugLabMachine.onConfirmLedger(restoredModel, "session-test", businessId)
        org.junit.Assert.assertTrue(
            "la revision restaurada tiene que poder arrancar el registro",
            confirming is com.cuentivo.app.ui.ai.AiLedgerConfirmOutcome.Started,
        )
        val started = confirming as com.cuentivo.app.ui.ai.AiLedgerConfirmOutcome.Started
        val afterResult = com.cuentivo.app.ui.ai.AiDebugLabMachine.onLedgerBatchResult(
            model = started.model,
            sessionId = "session-test",
            businessId = businessId,
            generation = started.generation,
            result = retry,
        )
        org.junit.Assert.assertTrue(
            "el mensaje tiene que decir que ya estaba registrada",
            afterResult.messages.any { it is com.cuentivo.app.ui.ai.AiChatMessage.AssistantLedgerAlreadyRecorded },
        )
    }
}
