package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.domain.AiLedgerBatchPlanner
import com.cuentivo.app.domain.BatchPlanningResult
import com.cuentivo.app.domain.OpeningBalance
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La deuda anterior que importa CuentivoAI desde una foto tiene que llevar el mismo marcador que la
 * que crea `OpeningBalance`, para que los lectores que ya existen la reconozcan.
 *
 * Esta prueba persiste en Room **lo que el plan real produjo** —no entidades a mano— y luego pregunta
 * con los métodos reales del DAO: el historial ([FinancialDao.priorDebtSaleIdsForCustomer]), el
 * clasificador de operaciones registradas ([FinancialDao.getRegisteredOperationsInRange]) y
 * "Vendido del mes" ([FinancialDao.salesTotalCents]). La venta normal queda como SALE y sí suma.
 */
@RunWith(AndroidJUnit4::class)
class AiLedgerPriorDebtMarkerRoomTest {

    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    private val businessId = "biz"
    private val userId = "owner"
    private val todayEpochDay = LocalDate.of(2026, 9, 6).toEpochDay()
    private val nowEpochMillis = 1_757_184_000_000L

    @Test fun la_deuda_anterior_del_plan_se_marca_y_la_venta_normal_no() = runBlocking {
        val dao = database.financialDao()
        val ops = listOf(
            LedgerOperation(
                lineIndex = 1,
                proposedKind = ProposedLedgerKind.PRIOR_DEBT,
                customerName = "Rosa Martinez",
                amountCents = "15000",
                // A description the model wrote, not the fixed "Saldo anterior": this is the shape
                // that was broken, where only the marker can tell the readers what this row is.
                description = "Aceite 1L",
                date = SafeDateCandidate(2026, 9, 1),
            ),
            LedgerOperation(
                lineIndex = 2,
                proposedKind = ProposedLedgerKind.SALE,
                customerName = "Rosa Martinez",
                amountCents = "2500",
                description = "Cuaderno",
                date = SafeDateCandidate(2026, 9, 2),
            ),
        )

        val result = AiLedgerBatchPlanner.plan(
            businessId = businessId,
            userId = userId,
            importSessionId = "session-room-marker",
            operations = ops,
            blockedLineIndices = emptySet(),
            existingCustomerMap = emptyMap(),
            existingOutstandingSalesByCustomerId = emptyMap(),
            pageDateEpochDay = null,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = nowEpochMillis,
        )
        assertTrue(result is BatchPlanningResult.Success)
        val plan = (result as BatchPlanningResult.Success).plan

        // Se persiste exactamente lo que el plan produjo, sin retocar campos.
        plan.newCustomers.forEach { dao.insertCustomer(it) }
        plan.sales.forEach { dao.insertSale(it) }
        dao.insertSaleItems(plan.saleItems)
        plan.chargeMovements.forEach { dao.insertMovement(it) }

        val customerId = plan.newCustomers.single().id
        val priorDebtSale = plan.sales.first { it.saleNumber == "SA-$nowEpochMillis-1" }
        val normalSale = plan.sales.first { it.saleNumber == "V-$nowEpochMillis-2" }
        assertEquals(OpeningBalance.NOTES_MARKER, priorDebtSale.notes)

        // 1. El historial de la clienta la marca como deuda anterior (y no a la venta normal).
        assertEquals(
            listOf(priorDebtSale.id),
            dao.priorDebtSaleIdsForCustomer(businessId, customerId),
        )

        // 2. El clasificador que usa la detección de duplicados la ve como PRIOR_DEBT.
        val min = plan.sales.minOf { it.saleDateEpochDay }
        val max = plan.sales.maxOf { it.saleDateEpochDay }
        val registered = dao.getRegisteredOperationsInRange(businessId, min, max)
        assertEquals("PRIOR_DEBT", registered.first { it.id == priorDebtSale.id }.kindName)
        assertEquals("SALE", registered.first { it.id == normalSale.id }.kindName)

        // 3. "Vendido" excluye la deuda anterior y sí cuenta la venta real.
        assertEquals(2_500L, dao.salesTotalCents(businessId, min, max))
        // La deuda existe y por eso suma en "por cobrar": no es venta, pero sí se cobra.
        assertEquals(17_500L, dao.businessReceivableCents(businessId))
    }
}
