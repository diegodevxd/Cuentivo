package com.cuentivo.app.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.OutstandingSale
import com.cuentivo.app.domain.SaleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LedgerBatchPageDateIsolationTest {

    private lateinit var database: CuentivoDatabase
    private lateinit var saleRepository: SaleRepository

    private val businessId = "biz-pagedate-test"
    private val userId = "user-pagedate-test"
    private val todayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay()
    private val nowEpochMillis = 1_757_200_000_000L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
        saleRepository = SaleRepository(database.financialDao())

        database.businessDao().insert(
            BusinessEntity(
                id = businessId,
                name = "Abarrotes Fecha Pagina",
                ownerUserId = userId,
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = 10_000L,
                updatedAtEpochMillis = 10_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun existingCustomers(): Map<String, CustomerEntity> =
        database.customerDao().activeWithBalances(businessId).associate { it.customer.normalizedName to it.customer }

    private suspend fun outstandingByCustomer(): Map<String, List<OutstandingSale>> =
        database.customerDao().activeWithBalances(businessId).associate { row ->
            row.customer.id to database.financialDao()
                .outstandingSalesForCustomer(businessId, row.customer.id)
                .map { OutstandingSale(it.id, it.outstandingCents, it.dueDateEpochDay, it.saleDateEpochDay) }
        }

    @Test
    fun chosen_page_date_reaches_sale_date_in_room_for_undated_operation() = runBlocking {
        val chosenPageDate = LocalDate.of(2026, 6, 15).toEpochDay()

        val undatedSale = LedgerOperation(
            lineIndex = 0,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Marta",
            amountCents = "15000",
            rawText = "Marta 150", // el nombre tiene que verse en la hoja, como en el papel
            date = null, // missing row date
        )

        val datedSale = LedgerOperation(
            lineIndex = 1,
            proposedKind = ProposedLedgerKind.SALE,
            customerName = "Pedro",
            amountCents = "20000",
            rawText = "Pedro 200",
            date = SafeDateCandidate(2026, 5, 20), // explicit row date
        )

        val recordedCount = com.cuentivo.app.domain.LedgerBatchCoordinator.confirm(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-test",
            operations = listOf(undatedSale, datedSale),
            blockedLineIndices = emptySet(),
            modelSaysTextMayBeMissing = false,
            isPageCompletenessConfirmed = false,
            pageDateEpochDay = chosenPageDate,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
            alreadyRecorded = { key -> database.financialDao().operationResultId(key) != null },
            loadCustomersByNormalizedName = { existingCustomers() },
            loadOutstandingByCustomerId = { outstandingByCustomer() },
            write = { plan -> saleRepository.registerLedgerBatch(plan) },
        )

        assertEquals(
            "la página entera se escribió: las dos ventas",
            com.cuentivo.app.domain.LedgerBatchConfirmation.Recorded(2),
            recordedCount,
        )

        val martaCust = database.customerDao().activeWithBalances(businessId).first { it.customer.normalizedName == "marta" }
        val martaSales = database.financialDao().outstandingSalesForCustomer(businessId, martaCust.customer.id)
        assertEquals(1, martaSales.size)
        assertEquals(chosenPageDate, martaSales.first().saleDateEpochDay)

        val pedroCust = database.customerDao().activeWithBalances(businessId).first { it.customer.normalizedName == "pedro" }
        val pedroSales = database.financialDao().outstandingSalesForCustomer(businessId, pedroCust.customer.id)
        assertEquals(1, pedroSales.size)
        assertEquals(LocalDate.of(2026, 5, 20).toEpochDay(), pedroSales.first().saleDateEpochDay)
    }
}
