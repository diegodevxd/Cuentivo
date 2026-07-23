package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class OperationDaoTest {
    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CuentivoDatabase::class.java).build()
    }
    @After fun tearDown() = database.close()

    @Test fun repeated_key_keeps_original_result() = runBlocking {
        val dao = database.operationDao()
        dao.insertIfAbsent(OperationEntity("key", "PAYMENT", "first", 1))
        dao.insertIfAbsent(OperationEntity("key", "PAYMENT", "second", 2))
        assertEquals("first", dao.resultIdFor("key"))
    }

    @Test fun repeated_sale_key_does_not_create_a_second_charge() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val items = listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000))
        val charge = AccountMovementEntity("movement-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)

        val firstResult = dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", sale.id, 1), sale, items, charge)
        val retryResult = dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", "sale-2", 2), sale.copy(id = "sale-2"), items.map { it.copy(id = "item-2", saleId = "sale-2") }, charge.copy(id = "movement-2", saleId = "sale-2"))

        assertEquals("sale-1", firstResult)
        assertEquals("sale-1", retryResult)
        assertEquals(1_000, dao.customerBalanceCents("business-1", "customer-1"))
        assertEquals(1_000, dao.businessReceivableCents("business-1"))
    }
}
