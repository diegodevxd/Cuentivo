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
    private suspend fun confirmBatch(businessId: String, operations: List<LedgerOperation>, pageDateEpochDay: Long?): Int? {
        val dao = database.financialDao()
        val repository = SaleRepository(dao)
        val today = java.time.LocalDate.now().toEpochDay()
        return LedgerBatchCoordinator.confirm(
            businessId = businessId,
            userId = "user-1",
            operations = operations,
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
        assertEquals(1, first)

        val movementsAfterFirst = database.financialDao().allMovementsForBackfill(businessId).size
        assertEquals(1, movementsAfterFirst)

        // Second confirm: the same page again, as a restore-then-confirm would replay it. The
        // batch key must recognize it as the page that already landed.
        val second = confirmBatch(businessId, operations, pageDate)
        assertEquals("la clave del lote reconoce la pagina ya escrita", 1, second)

        val movementsAfterSecond = database.financialDao().allMovementsForBackfill(businessId).size
        assertEquals(
            "restaurar y confirmar no puede escribir dos veces: nada se edita, se corrige con movimientos",
            1,
            movementsAfterSecond,
        )
    }
}
