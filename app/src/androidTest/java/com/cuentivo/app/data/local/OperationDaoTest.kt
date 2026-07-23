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

    @Test fun cancellation_adds_a_compensating_movement_without_erasing_the_sale() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val items = listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000))
        val charge = AccountMovementEntity("movement-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)
        dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", sale.id, 1), sale, items, charge)
        val cancellation = AccountMovementEntity("cancellation-1", sale.businessId, sale.customerId, sale.id, null, "CANCELLATION", 1_000, "CREDIT", 2, sale.sellerUserId, charge.id, null)

        val result = dao.cancelSaleIdempotently(OperationEntity("cancel-key", "SALE_CANCELLATION", sale.id, 2), sale.id, cancellation)

        assertEquals("sale-1", result)
        assertEquals(0, dao.customerBalanceCents(sale.businessId, sale.customerId))
        assertEquals(2, dao.movementCountForSale(sale.id))
    }

    @Test fun payment_reversal_restores_debt_without_erasing_the_payment() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val charge = AccountMovementEntity("charge-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)
        dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", sale.id, 1), sale, listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000)), charge)
        val payment = PaymentEntity("payment-1", sale.businessId, sale.customerId, 1_000, "CASH", 2, null, null, sale.sellerUserId, 2, null)
        val paymentMovement = AccountMovementEntity("payment-movement-1", sale.businessId, sale.customerId, sale.id, payment.id, "PAYMENT", 1_000, "CREDIT", 2, sale.sellerUserId, null, null)
        dao.registerPaymentIdempotently(OperationEntity("payment-key", "PAYMENT", payment.id, 2), payment, emptyList(), paymentMovement)
        val refund = AccountMovementEntity("refund-1", sale.businessId, sale.customerId, sale.id, payment.id, "REFUND", 1_000, "DEBIT", 3, sale.sellerUserId, paymentMovement.id, null)

        val result = dao.cancelPaymentIdempotently(OperationEntity("refund-key", "PAYMENT_REVERSAL", payment.id, 3), payment.id, refund)

        assertEquals(payment.id, result)
        assertEquals(1_000, dao.customerBalanceCents(sale.businessId, sale.customerId))
        assertEquals(2, dao.movementCountForPayment(payment.id))
    }

    @Test fun report_totals_use_sales_payments_and_overdue_balances() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, 5, 1_000, 0, 1_000, false, null, 1, 1)
        val charge = AccountMovementEntity("charge-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)
        dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", sale.id, 1), sale, listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000)), charge)
        val payment = PaymentEntity("payment-1", sale.businessId, sale.customerId, 300, "CASH", 2, null, null, sale.sellerUserId, 2, null)
        val paymentMovement = AccountMovementEntity("payment-movement-1", sale.businessId, sale.customerId, sale.id, payment.id, "PAYMENT", 300, "CREDIT", 2, sale.sellerUserId, null, null)
        val allocation = PaymentAllocationEntity("allocation-1", payment.id, sale.id, 300)
        dao.registerPaymentIdempotently(OperationEntity("payment-key", "PAYMENT", payment.id, 2), payment, listOf(allocation), paymentMovement)

        assertEquals(1_000, dao.salesTotalCents("business-1", 1, 2))
        assertEquals(300, dao.collectedTotalCents("business-1", 1, 2))
        assertEquals(700, dao.businessReceivableCents("business-1"))
        assertEquals(700, dao.overdueBalanceCents("business-1", 6))
    }

    @Test fun customer_search_and_archive_are_isolated_by_business() = runBlocking {
        val dao = database.customerDao()
        val customer = CustomerEntity("customer-1", "business-1", "Ana L?pez", "ana lopez", "5551234", null, null, null, null, true, 1, 1, null)
        dao.insert(customer)
        assertEquals(1, dao.searchActive("business-1", "ana", "555").size)
        dao.archive(customer.id, 2)
        assertEquals(0, dao.searchActive("business-1", "ana", "555").size)
    }

    @Test fun product_search_and_archive_keep_catalog_entries_without_deleting_them() = runBlocking {
        val dao = database.productDao()
        val product = ProductEntity("product-1", "business-1", "Cuaderno", null, "CUA-1", "Papeler?a", 2_500, null, null, null, true, 1, 1, null)
        dao.insert(product)
        assertEquals(1, dao.searchActive("business-1", "cuad", "CUA").size)
        dao.archive(product.id, 2)
        assertEquals(0, dao.searchActive("business-1", "cuad", "CUA").size)
    }
}
