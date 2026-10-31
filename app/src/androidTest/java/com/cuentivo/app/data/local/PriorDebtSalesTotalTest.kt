package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.domain.OpeningBalance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Vendido" de un periodo no puede incluir la deuda anterior.
 *
 * El recorrido "Doña Mary" mostró $250 donde había $100 de ventas reales y $150 que la persona ya
 * debía desde antes de la app. La deuda anterior se guarda como una venta (para poder cobrarla),
 * pero no es una venta del mes (v44).
 *
 * Esta prueba ejerce la consulta **real** de [FinancialDao.salesTotalCents] sobre Room, con los dos
 * caminos por los que una deuda anterior puede existir: la fila nueva, con el marcador que ahora
 * escribe [OpeningBalance], y la fila ya guardada antes del cambio, que sólo se distingue por su
 * renglón sin producto llamado "Saldo anterior".
 */
@RunWith(AndroidJUnit4::class)
class PriorDebtSalesTotalTest {

    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    private val day = 20_290L

    private fun customer(id: String) = CustomerEntity(
        id = id,
        businessId = "biz",
        name = id,
        normalizedName = id,
        phone = null,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        deletedAtEpochMillis = null,
    )

    private fun sale(id: String, cents: Long, notes: String? = null, customerId: String = "rosa") = SaleEntity(
        id = id,
        businessId = "biz",
        customerId = customerId,
        sellerUserId = "owner",
        saleNumber = "V-$id",
        saleDateEpochDay = day,
        dueDateEpochDay = null,
        subtotalCents = cents,
        discountCents = 0,
        totalCents = cents,
        isCanceled = false,
        notes = notes,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun item(saleId: String, name: String, cents: Long, productId: String? = null) = SaleItemEntity(
        id = "item-$saleId",
        saleId = saleId,
        productId = productId,
        productNameSnapshot = name,
        quantity = 1,
        unitPriceCents = cents,
        discountCents = 0,
        subtotalCents = cents,
    )

    @Test fun la_deuda_anterior_nueva_no_cuenta_como_vendido() = runBlocking {
        val dao = database.financialDao()
        dao.insertCustomer(customer("rosa"))
        // Venta real del mes.
        dao.insertSale(sale("real", 10_000))
        dao.insertSaleItems(listOf(item("real", "Jabón", 10_000, productId = "prod-1")))
        dao.insertMovement(
            AccountMovementEntity("charge-real", "biz", "rosa", "real", null, "CHARGE", 10_000, "DEBIT", 1, "owner", null, null),
        )
        // Deuda anterior tal como la escribe producción: ya trae el marcador y su cargo.
        val opening = OpeningBalance.entry(
            saleId = "prior",
            itemId = "item-prior",
            chargeId = "charge-prior",
            businessId = "biz",
            customerId = "rosa",
            ownerUserId = "owner",
            amountCents = 15_000,
            todayEpochDay = day,
            nowEpochMillis = 1,
        )
        dao.insertSale(opening.sale)
        dao.insertSaleItems(listOf(opening.item))
        dao.insertMovement(opening.charge)

        assertEquals(10_000L, dao.salesTotalCents("biz", day, day))
        // Y sí cuenta en "por cobrar": la deuda existe, sólo no es venta del mes.
        assertEquals(25_000L, dao.businessReceivableCents("biz"))

        val activity = dao.recentDashboardActivity("biz", limit = 10).associateBy { it.id }
        assertTrue(activity.getValue("charge-prior").isPriorDebt)
        assertFalse(activity.getValue("charge-real").isPriorDebt)
    }

    @Test fun una_deuda_anterior_vieja_sin_marcador_tambien_se_excluye() = runBlocking {
        val dao = database.financialDao()
        dao.insertCustomer(customer("rosa"))
        dao.insertSale(sale("real", 10_000))
        dao.insertSaleItems(listOf(item("real", "Jabón", 10_000, productId = "prod-1")))
        // Fila ya guardada antes del cambio: sin marcador, sólo el renglón sin producto.
        dao.insertSale(sale("legacy", 15_000, notes = null))
        dao.insertSaleItems(listOf(item("legacy", OpeningBalance.LINE_NAME, 15_000, productId = null)))

        assertEquals(10_000L, dao.salesTotalCents("biz", day, day))
    }

    @Test fun una_venta_sin_producto_con_otro_nombre_si_cuenta() = runBlocking {
        val dao = database.financialDao()
        dao.insertCustomer(customer("rosa"))
        dao.insertSale(sale("manual", 8_000))
        dao.insertSaleItems(listOf(item("manual", "3 trapeadores", 8_000, productId = null)))

        assertEquals(8_000L, dao.salesTotalCents("biz", day, day))
    }
}
