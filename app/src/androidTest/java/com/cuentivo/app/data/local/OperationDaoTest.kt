package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun sale_creation_rejects_invalid_sale_and_charge_payloads() = runBlocking {
        val dao = database.financialDao()
        val baseSale = SaleEntity("sale-base", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val items = listOf(SaleItemEntity("item-base", baseSale.id, null, "Producto", 1, 1_000, 0, 1_000))

        suspend fun attempt(sale: SaleEntity, charge: AccountMovementEntity, key: String) {
            try {
                dao.createSaleIdempotently(OperationEntity(key, "SALE", sale.id, 1), sale, items.map { it.copy(saleId = sale.id) }, charge)
                throw AssertionError("Expected sale validation failure for $key")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }

        attempt(baseSale.copy(id = "sale-zero", totalCents = 0), AccountMovementEntity("m-zero", "business-1", "customer-1", "sale-zero", null, "CHARGE", 0, "DEBIT", 1, "owner-1", null, null), "sale-zero-key")
        attempt(baseSale.copy(id = "sale-negative", totalCents = -1), AccountMovementEntity("m-negative", "business-1", "customer-1", "sale-negative", null, "CHARGE", 1, "DEBIT", 1, "owner-1", null, null), "sale-negative-key")
        attempt(baseSale.copy(id = "sale-type"), AccountMovementEntity("m-type", "business-1", "customer-1", "sale-type", null, "PAYMENT", 1_000, "DEBIT", 1, "owner-1", null, null), "sale-type-key")
        attempt(baseSale.copy(id = "sale-direction"), AccountMovementEntity("m-direction", "business-1", "customer-1", "sale-direction", null, "CHARGE", 1_000, "CREDIT", 1, "owner-1", null, null), "sale-direction-key")
        attempt(baseSale.copy(id = "sale-amount"), AccountMovementEntity("m-amount", "business-1", "customer-1", "sale-amount", null, "CHARGE", 999, "DEBIT", 1, "owner-1", null, null), "sale-amount-key")
        attempt(baseSale.copy(id = "sale-business"), AccountMovementEntity("m-business", "business-2", "customer-1", "sale-business", null, "CHARGE", 1_000, "DEBIT", 1, "owner-1", null, null), "sale-business-key")
        attempt(baseSale.copy(id = "sale-customer"), AccountMovementEntity("m-customer", "business-1", "customer-2", "sale-customer", null, "CHARGE", 1_000, "DEBIT", 1, "owner-1", null, null), "sale-customer-key")
        attempt(baseSale.copy(id = "sale-id"), AccountMovementEntity("m-id", "business-1", "customer-1", "other-sale", null, "CHARGE", 1_000, "DEBIT", 1, "owner-1", null, null), "sale-id-key")
    }

    @Test fun payment_registration_rejects_invalid_payment_movement_payloads() = runBlocking {
        val dao = database.financialDao()
        val payment = PaymentEntity("payment-invalid", "business-1", "customer-1", 500, "CASH", 1, null, null, "owner-1", 1, null)

        suspend fun attempt(movement: AccountMovementEntity, key: String) {
            try {
                dao.registerPaymentIdempotently(OperationEntity(key, "PAYMENT", payment.id, 1), payment, emptyList(), movement)
                throw AssertionError("Expected payment validation failure for $key")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }

        attempt(AccountMovementEntity("pm-type", "business-1", "customer-1", null, payment.id, "CHARGE", 500, "CREDIT", 1, "owner-1", null, null), "payment-type-key")
        attempt(AccountMovementEntity("pm-direction", "business-1", "customer-1", null, payment.id, "PAYMENT", 500, "DEBIT", 1, "owner-1", null, null), "payment-direction-key")
        attempt(AccountMovementEntity("pm-zero", "business-1", "customer-1", null, payment.id, "PAYMENT", 0, "CREDIT", 1, "owner-1", null, null), "payment-zero-key")
        attempt(AccountMovementEntity("pm-negative", "business-1", "customer-1", null, payment.id, "PAYMENT", -1, "CREDIT", 1, "owner-1", null, null), "payment-negative-key")
        attempt(AccountMovementEntity("pm-amount", "business-1", "customer-1", null, payment.id, "PAYMENT", 499, "CREDIT", 1, "owner-1", null, null), "payment-amount-key")
        attempt(AccountMovementEntity("pm-business", "business-2", "customer-1", null, payment.id, "PAYMENT", 500, "CREDIT", 1, "owner-1", null, null), "payment-business-key")
        attempt(AccountMovementEntity("pm-customer", "business-1", "customer-2", null, payment.id, "PAYMENT", 500, "CREDIT", 1, "owner-1", null, null), "payment-customer-key")
        attempt(AccountMovementEntity("pm-id", "business-1", "customer-1", null, "other-payment", "PAYMENT", 500, "CREDIT", 1, "owner-1", null, null), "payment-id-key")
    }

    @Test fun reserved_sale_retry_returns_result_before_validating_new_payload() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-reserved", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val charge = AccountMovementEntity("charge-reserved", "business-1", "customer-1", sale.id, null, "CHARGE", 1_000, "DEBIT", 1, "owner-1", null, null)
        dao.createSaleIdempotently(OperationEntity("reserved-key", "SALE", sale.id, 1), sale, emptyList(), charge)

        val retry = dao.createSaleIdempotently(
            OperationEntity("reserved-key", "SALE", "other-sale", 2),
            sale.copy(id = "other-sale", totalCents = 0),
            emptyList(),
            charge.copy(id = "other-charge", saleId = "other-sale", amountCents = 0),
        )

        assertEquals(sale.id, retry)
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

    @Test fun fully_paid_sale_cannot_be_canceled_with_a_zero_amount_movement() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-fully-paid", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val charge = AccountMovementEntity("charge-fully-paid", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)
        dao.createSaleIdempotently(
            OperationEntity("sale-fully-paid-key", "SALE", sale.id, 1), sale,
            listOf(SaleItemEntity("item-fully-paid", sale.id, null, "Producto", 1, 1_000, 0, 1_000)), charge,
        )
        val payment = PaymentEntity("payment-fully-paid", sale.businessId, sale.customerId, 1_000, "CASH", 2, null, null, sale.sellerUserId, 2, null)
        dao.registerPaymentIdempotently(
            OperationEntity("payment-fully-paid-key", "PAYMENT", payment.id, 2), payment,
            listOf(PaymentAllocationEntity("allocation-fully-paid", payment.id, sale.id, 1_000)),
            AccountMovementEntity("payment-movement-fully-paid", sale.businessId, sale.customerId, sale.id, payment.id, "PAYMENT", 1_000, "CREDIT", 2, sale.sellerUserId, null, null),
        )

        try {
            dao.cancelSaleIdempotently(
                OperationEntity("cancel-fully-paid-key", "SALE_CANCELLATION", sale.id, 3), sale.id,
                AccountMovementEntity("zero-cancellation", sale.businessId, sale.customerId, sale.id, null, "CANCELLATION", 0, "CREDIT", 3, sale.sellerUserId, charge.id, null),
            )
            throw AssertionError("Expected cancellation of a fully paid sale to be rejected.")
        } catch (_: IllegalArgumentException) {
            assertEquals(false, dao.saleForId(sale.id)?.isCanceled)
            assertEquals(2, dao.movementCountForSale(sale.id))
            assertEquals(0, dao.customerBalanceCents(sale.businessId, sale.customerId))
        }
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

    @Test fun payment_reversal_is_rejected_after_its_sale_is_canceled() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        val charge = AccountMovementEntity("charge-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null)
        dao.createSaleIdempotently(OperationEntity("sale-key", "SALE", sale.id, 1), sale, listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000)), charge)
        val payment = PaymentEntity("payment-1", sale.businessId, sale.customerId, 300, "CASH", 2, null, null, sale.sellerUserId, 2, null)
        val paymentMovement = AccountMovementEntity("payment-movement-1", sale.businessId, sale.customerId, sale.id, payment.id, "PAYMENT", 300, "CREDIT", 2, sale.sellerUserId, null, null)
        dao.registerPaymentIdempotently(
            OperationEntity("payment-key", "PAYMENT", payment.id, 2), payment,
            listOf(PaymentAllocationEntity("allocation-1", payment.id, sale.id, 300)), paymentMovement,
        )
        dao.cancelSaleIdempotently(
            OperationEntity("cancel-key", "SALE_CANCELLATION", sale.id, 3), sale.id,
            AccountMovementEntity("cancellation-1", sale.businessId, sale.customerId, sale.id, null, "CANCELLATION", 700, "CREDIT", 3, sale.sellerUserId, charge.id, null),
        )
        val refund = AccountMovementEntity("refund-1", sale.businessId, sale.customerId, sale.id, payment.id, "REFUND", 300, "DEBIT", 4, sale.sellerUserId, paymentMovement.id, null)

        try {
            dao.cancelPaymentIdempotently(OperationEntity("refund-key", "PAYMENT_REVERSAL", payment.id, 4), payment.id, refund)
            throw AssertionError("Expected payment reversal to be rejected after sale cancellation.")
        } catch (_: IllegalArgumentException) {
            assertEquals(null, dao.paymentForId(payment.id)?.canceledAtEpochMillis)
            assertEquals(1, dao.movementCountForPayment(payment.id))
            assertEquals(0, dao.customerBalanceCents(sale.businessId, sale.customerId))
        }
    }

    @Test fun correction_payments_include_canceled_sale_block_and_exclude_foreign_business() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-key", "SALE", sale.id, 1), sale,
            listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000)),
            AccountMovementEntity("charge-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null),
        )
        val payment = PaymentEntity("payment-1", sale.businessId, sale.customerId, 300, "CASH", 2, "ref-1", null, sale.sellerUserId, 2, null)
        dao.registerPaymentIdempotently(
            OperationEntity("payment-key", "PAYMENT", payment.id, 2), payment,
            listOf(PaymentAllocationEntity("allocation-1", payment.id, sale.id, 300)),
            AccountMovementEntity("payment-movement-1", sale.businessId, sale.customerId, sale.id, payment.id, "PAYMENT", 300, "CREDIT", 2, sale.sellerUserId, null, null),
        )
        dao.cancelSaleIdempotently(
            OperationEntity("cancel-key", "SALE_CANCELLATION", sale.id, 3), sale.id,
            AccountMovementEntity("cancellation-1", sale.businessId, sale.customerId, sale.id, null, "CANCELLATION", 700, "CREDIT", 3, sale.sellerUserId, "charge-1", null),
        )
        val foreignPayment = PaymentEntity("payment-foreign", "business-2", "customer-1", 100, "CASH", 4, null, null, sale.sellerUserId, 4, null)
        dao.registerPaymentIdempotently(
            OperationEntity("foreign-payment-key", "PAYMENT", foreignPayment.id, 4), foreignPayment, emptyList(),
            AccountMovementEntity("foreign-payment-movement", "business-2", "customer-1", null, foreignPayment.id, "PAYMENT", 100, "CREDIT", 4, sale.sellerUserId, null, null),
        )

        val rows = dao.correctionPaymentsForCustomer("business-1", "customer-1")

        assertEquals(listOf("payment-1"), rows.map { it.id })
        assertEquals(true, rows.single().blockedByCanceledSale)
    }

    @Test fun correction_payment_without_allocation_is_not_blocked() = runBlocking {
        val dao = database.financialDao()
        val payment = payment("payment-unallocated", "business-1", "customer-1", null)
        dao.adoptPayment(payment)

        val row = dao.correctionPaymentsForCustomer("business-1", "customer-1").single()

        assertFalse(row.blockedByCanceledSale)
    }

    @Test fun correction_payment_allocated_to_active_sale_is_not_blocked() = runBlocking {
        val dao = database.financialDao()
        val sale = sale("sale-active", "business-1", "customer-1", false)
        val payment = payment("payment-active-sale", "business-1", "customer-1", null)
        dao.adoptSale(sale)
        dao.adoptPayment(payment)
        dao.adoptAllocation(PaymentAllocationEntity("allocation-active", payment.id, sale.id, 100))

        val row = dao.correctionPaymentsForCustomer("business-1", "customer-1").single()

        assertFalse(row.blockedByCanceledSale)
    }

    @Test fun correction_payment_allocated_to_active_and_canceled_sales_is_blocked() = runBlocking {
        val dao = database.financialDao()
        val activeSale = sale("sale-active", "business-1", "customer-1", false)
        val canceledSale = sale("sale-canceled", "business-1", "customer-1", true)
        val payment = payment("payment-mixed-sales", "business-1", "customer-1", null)
        dao.adoptSale(activeSale)
        dao.adoptSale(canceledSale)
        dao.adoptPayment(payment)
        dao.adoptAllocation(PaymentAllocationEntity("allocation-active", payment.id, activeSale.id, 100))
        dao.adoptAllocation(PaymentAllocationEntity("allocation-canceled", payment.id, canceledSale.id, 100))

        val row = dao.correctionPaymentsForCustomer("business-1", "customer-1").single()

        assertTrue(row.blockedByCanceledSale)
    }

    @Test fun canceled_correction_payment_is_absent() = runBlocking {
        val dao = database.financialDao()
        dao.adoptPayment(payment("payment-canceled", "business-1", "customer-1", 99))

        assertTrue(dao.correctionPaymentsForCustomer("business-1", "customer-1").isEmpty())
    }

    @Test fun correction_payment_linked_to_canceled_sale_of_another_customer_is_not_blocked() = runBlocking {
        val dao = database.financialDao()
        val sale = sale("sale-other-customer", "business-1", "customer-2", true)
        val payment = payment("payment-other-customer", "business-1", "customer-1", null)
        dao.adoptSale(sale)
        dao.adoptPayment(payment)
        dao.adoptAllocation(PaymentAllocationEntity("allocation-other-customer", payment.id, sale.id, 100))

        val row = dao.correctionPaymentsForCustomer("business-1", "customer-1").single()

        assertFalse(row.blockedByCanceledSale)
    }

    @Test fun correction_payment_linked_to_canceled_sale_of_another_business_is_not_blocked() = runBlocking {
        val dao = database.financialDao()
        val sale = sale("sale-other-business", "business-2", "customer-1", true)
        val payment = payment("payment-artificial-link", "business-1", "customer-1", null)
        dao.adoptSale(sale)
        dao.adoptPayment(payment)
        dao.adoptAllocation(PaymentAllocationEntity("allocation-artificial-link", payment.id, sale.id, 100))

        val row = dao.correctionPaymentsForCustomer("business-1", "customer-1").single()

        assertFalse(row.blockedByCanceledSale)
    }

    @Test fun repeated_adjustment_key_creates_only_one_auditable_movement() = runBlocking {
        val dao = database.financialDao()
        val adjustment = AccountMovementEntity(
            id = "adjustment-1", businessId = "business-1", customerId = "customer-1",
            saleId = null, paymentId = null, type = "ADJUSTMENT", amountCents = 250,
            direction = "DEBIT", occurredAtEpochMillis = 1, createdByUserId = "owner-1",
            reversesMovementId = null, notes = "Cargo por entrega",
        )

        val first = dao.registerAdjustmentIdempotently(
            OperationEntity("adjustment-key", "ADJUSTMENT", adjustment.id, 1), adjustment,
        )
        val retry = dao.registerAdjustmentIdempotently(
            OperationEntity("adjustment-key", "ADJUSTMENT", "adjustment-2", 2),
            adjustment.copy(id = "adjustment-2"),
        )

        assertEquals("adjustment-1", first)
        assertEquals("adjustment-1", retry)
        assertEquals(250, dao.customerBalanceCents("business-1", "customer-1"))
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

    @Test fun dashboard_queries_select_the_local_business_and_count_customers_in_debt() = runBlocking {
        database.businessDao().insert(BusinessEntity("business-1", "Mi negocio", "owner-1", "MXN", "America/Mexico_City", 1, 1))
        database.financialDao().insertMovement(
            AccountMovementEntity("charge-1", "business-1", "customer-1", null, null, "CHARGE", 500, "DEBIT", 1, "owner-1", null, null),
        )

        assertEquals("Mi negocio", database.businessDao().first()?.name)
        assertEquals(1, database.financialDao().customersWithDebtCount("business-1"))
    }

    @Test fun recent_activity_is_newest_first_and_business_scoped() = runBlocking {
        val dao = database.financialDao()
        database.customerDao().insert(customer("ana", "business-1", "Ana"))
        database.customerDao().insert(customer("beto", "business-2", "Beto"))
        dao.insertMovement(movement("old", "business-1", "ana", 100, 10))
        dao.insertMovement(movement("new", "business-1", "ana", 200, 20))
        dao.insertMovement(movement("foreign", "business-2", "beto", 999, 30))

        val result = dao.recentDashboardActivity("business-1", 5)

        assertEquals(listOf("new", "old"), result.map { it.id })
        assertEquals(listOf("Ana", "Ana"), result.map { it.customerName })
    }

    @Test fun payment_allocation_cannot_exceed_sale_outstanding_balance() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "S-1", 1, null, 1_000, 0, 1_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-key", "SALE", sale.id, 1), sale,
            listOf(SaleItemEntity("item-1", sale.id, null, "Producto", 1, 1_000, 0, 1_000)),
            AccountMovementEntity("charge-1", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 1_000, "DEBIT", 1, sale.sellerUserId, null, null),
        )
        val payment = PaymentEntity("payment-1", sale.businessId, sale.customerId, 1_100, "CASH", 2, null, null, sale.sellerUserId, 2, null)
        val movement = AccountMovementEntity("payment-movement-1", sale.businessId, sale.customerId, null, payment.id, "PAYMENT", 1_100, "CREDIT", 2, sale.sellerUserId, null, null)

        try {
            dao.registerPaymentIdempotently(
                OperationEntity("payment-key", "PAYMENT", payment.id, 2), payment,
                listOf(PaymentAllocationEntity("allocation-1", payment.id, sale.id, 1_100)), movement,
            )
            throw AssertionError("Expected an allocation validation failure.")
        } catch (_: IllegalArgumentException) {
            assertEquals(1_000, dao.customerBalanceCents(sale.businessId, sale.customerId))
        }
    }

    @Test fun customer_search_and_archive_are_isolated_by_business() = runBlocking {
        val dao = database.customerDao()
        val customer = CustomerEntity("customer-1", "business-1", "Ana L?pez", "ana lopez", "5551234", null, null, null, null, true, 1, 1, null)
        dao.insert(customer)
        assertEquals(1, dao.searchActive("business-1", "ana", "555").size)
        dao.archive(customer.id, 2)
        assertEquals(0, dao.searchActive("business-1", "ana", "555").size)
    }

    @Test fun archived_customer_does_not_reserve_its_normalized_name() = runBlocking {
        val dao = database.customerDao()
        val archived = CustomerEntity("customer-archived", "business-1", "Diego", "diego", null, null, null, null, null, true, 1, 1, null)
        val replacement = CustomerEntity("customer-new", "business-1", "DIEGO", "diego", null, null, null, null, null, true, 3, 3, null)

        dao.insert(archived)
        dao.archive(archived.id, 2)
        dao.insert(replacement)

        assertEquals(listOf(replacement.id), dao.searchActive("business-1", "diego", "").map { it.id })
        assertEquals(2, dao.allForBackfill("business-1").size)
    }

    @Test fun customer_searchActive_matches_exact_and_partial_names_without_empty_phone_wildcard_leaks() = runBlocking {
        val dao = database.customerDao()
        val c1 = CustomerEntity("c-1", "business-1", "Carina", "carina", null, null, null, null, null, true, 1, 1, null)
        val c2 = CustomerEntity("c-2", "business-1", "Demian", "demian", "5551234", null, null, null, null, true, 1, 1, null)
        val cForeign = CustomerEntity("c-foreign", "business-2", "Demian", "demian", "5551234", null, null, null, null, true, 1, 1, null)
        val cInactive = CustomerEntity("c-inactive", "business-1", "Demian Inactive", "demian inactive", "5559999", null, null, null, null, false, 1, 1, null)
        val cDeleted = CustomerEntity("c-deleted", "business-1", "Demian Deleted", "demian deleted", "5558888", null, null, null, null, true, 1, 1, 100L)

        dao.insert(c1)
        dao.insert(c2)
        dao.insert(cForeign)
        dao.insert(cInactive)
        dao.insert(cDeleted)

        // 1. Non-existent name without digits returns empty list (the bug that matched all rows via empty phone LIKE '%%')
        val nonExistent = dao.searchActive("business-1", "fulano", "")
        assertEquals(0, nonExistent.size)

        // 2. Partial name matches only matching active customers in the same business
        val partialName = dao.searchActive("business-1", "dem", "")
        assertEquals(listOf("c-2"), partialName.map { it.id })

        // 3. Search with digits matches by phone number
        val byPhone = dao.searchActive("business-1", "555", "555")
        assertEquals(listOf("c-2"), byPhone.map { it.id })

        // 4. Both empty queries list all active customers in the business (contract preserved)
        val allActive = dao.searchActive("business-1", "", "")
        assertEquals(listOf("c-1", "c-2"), allActive.map { it.id })
    }

    @Test fun product_search_and_archive_keep_catalog_entries_without_deleting_them() = runBlocking {
        val dao = database.productDao()
        val product = ProductEntity("product-1", "business-1", "Cuaderno", null, "CUA-1", "Papeler?a", 2_500, null, null, null, true, 1, 1, null)
        dao.insert(product)
        assertEquals(1, dao.searchActive("business-1", "cuad", "CUA").size)
        dao.archive(product.id, 2)
        assertEquals(0, dao.searchActive("business-1", "cuad", "CUA").size)
    }

    @Test fun migration_execution_persists_a_valid_plan_atomically() = runBlocking {
        val plan = com.cuentivo.app.domain.LegacyMigrationPlanner.plan("business-1", "owner-1", listOf(com.cuentivo.app.domain.LegacyMovimiento(10, "Ana", "pedido", 1_000, null, 10, null), com.cuentivo.app.domain.LegacyMovimiento(11, "Ana", "pago", 200, null, 11, null)))
        val repository = com.cuentivo.app.domain.LegacyMigrationRepository(database)
        val report = repository.execute(plan)

        assertEquals(1, report.sales)
        assertEquals(1, report.payments)
        assertEquals(800, database.financialDao().customerBalanceCents("business-1", "legacy-customer-10"))
        assertEquals("MIGRATION_EXECUTED", database.auditDao().forBusiness("business-1").single().action)
    }

    /**
     * The arithmetic that justifies crediting the previous total in full instead of the unpaid
     * remainder: $400 sold, $200 collected, corrected to $500, so $300 is owed. Crediting only the
     * $200 outstanding would leave the customer owing $500 for a sale they part-paid.
     */
    @Test fun replacing_a_partly_paid_sale_leaves_the_exact_balance() = runBlocking {
        val dao = database.financialDao()
        val previous = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "V-1", 1, null, 40_000, 0, 40_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-1-key", "SALE", previous.id, 1),
            previous,
            listOf(SaleItemEntity("item-1", previous.id, "product-1", "Jabón", 1, 40_000, 0, 40_000)),
            AccountMovementEntity("charge-1", "business-1", "customer-1", previous.id, null, "CHARGE", 40_000, "DEBIT", 1, "owner-1", null, null),
        )
        val paid = PaymentEntity("payment-1", "business-1", "customer-1", 20_000, "CASH", 1, null, null, "owner-1", 1, null)
        dao.registerPaymentIdempotently(
            OperationEntity("payment-1-key", "PAYMENT", paid.id, 1),
            paid,
            listOf(PaymentAllocationEntity("allocation-1", paid.id, previous.id, 20_000)),
            AccountMovementEntity("movement-payment-1", "business-1", "customer-1", null, paid.id, "PAYMENT", 20_000, "CREDIT", 1, "owner-1", null, null),
        )
        assertEquals(20_000, dao.customerBalanceCents("business-1", "customer-1"))

        val corrected = previous.copy(id = "sale-2", subtotalCents = 50_000, totalCents = 50_000)
        val result = dao.replaceSaleIdempotently(
            OperationEntity("replace-1-key", "SALE", corrected.id, 2),
            previous.id,
            AccountMovementEntity("cancel-1", "business-1", "customer-1", previous.id, null, "CANCELLATION", 40_000, "CREDIT", 2, "owner-1", null, "Venta corregida"),
            corrected,
            listOf(SaleItemEntity("item-2", corrected.id, "product-1", "Jabón", 1, 50_000, 0, 50_000)),
            AccountMovementEntity("charge-2", "business-1", "customer-1", corrected.id, null, "CHARGE", 50_000, "DEBIT", 2, "owner-1", null, null),
            listOf(PaymentAllocationEntity("allocation-2", paid.id, corrected.id, 20_000)),
        )

        assertEquals("sale-2", result)
        assertEquals(30_000, dao.customerBalanceCents("business-1", "customer-1"))
        assertTrue(requireNotNull(dao.saleForId(previous.id)).isCanceled)
        // The collected money follows the corrected sale, so it does not look unpaid.
        assertEquals(20_000, dao.activeAllocatedPaymentCents(corrected.id))
    }

    @Test fun replaying_a_correction_returns_the_same_sale_without_charging_twice() = runBlocking {
        val dao = database.financialDao()
        val previous = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "V-1", 1, null, 10_000, 0, 10_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-1-key", "SALE", previous.id, 1),
            previous,
            listOf(SaleItemEntity("item-1", previous.id, "product-1", "Jabón", 1, 10_000, 0, 10_000)),
            AccountMovementEntity("charge-1", "business-1", "customer-1", previous.id, null, "CHARGE", 10_000, "DEBIT", 1, "owner-1", null, null),
        )

        suspend fun correct(saleId: String, suffix: String): String {
            val corrected = previous.copy(id = saleId, subtotalCents = 12_000, totalCents = 12_000)
            return dao.replaceSaleIdempotently(
                OperationEntity("replace-1-key", "SALE", saleId, 2),
                previous.id,
                AccountMovementEntity("cancel-$suffix", "business-1", "customer-1", previous.id, null, "CANCELLATION", 10_000, "CREDIT", 2, "owner-1", null, "Venta corregida"),
                corrected,
                listOf(SaleItemEntity("item-$suffix", saleId, "product-1", "Jabón", 1, 12_000, 0, 12_000)),
                AccountMovementEntity("charge-$suffix", "business-1", "customer-1", saleId, null, "CHARGE", 12_000, "DEBIT", 2, "owner-1", null, null),
                emptyList(),
            )
        }

        assertEquals("sale-2", correct("sale-2", "a"))
        assertEquals("sale-2", correct("sale-3", "b"))
        assertEquals(12_000, dao.customerBalanceCents("business-1", "customer-1"))
    }

    @Test fun a_correction_refuses_a_canceled_sale_a_partial_credit_and_overcarried_payments() = runBlocking {
        val dao = database.financialDao()
        val previous = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "V-1", 1, null, 10_000, 0, 10_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-1-key", "SALE", previous.id, 1),
            previous,
            listOf(SaleItemEntity("item-1", previous.id, "product-1", "Jabón", 1, 10_000, 0, 10_000)),
            AccountMovementEntity("charge-1", "business-1", "customer-1", previous.id, null, "CHARGE", 10_000, "DEBIT", 1, "owner-1", null, null),
        )
        val corrected = previous.copy(id = "sale-2", subtotalCents = 12_000, totalCents = 12_000)
        val items = listOf(SaleItemEntity("item-2", corrected.id, "product-1", "Jabón", 1, 12_000, 0, 12_000))
        val charge = AccountMovementEntity("charge-2", "business-1", "customer-1", corrected.id, null, "CHARGE", 12_000, "DEBIT", 2, "owner-1", null, null)

        suspend fun attempt(key: String, cancellationCents: Long, carried: List<PaymentAllocationEntity>) {
            try {
                dao.replaceSaleIdempotently(
                    OperationEntity(key, "SALE", corrected.id, 2),
                    previous.id,
                    AccountMovementEntity("cancel-$key", "business-1", "customer-1", previous.id, null, "CANCELLATION", cancellationCents, "CREDIT", 2, "owner-1", null, "Venta corregida"),
                    corrected,
                    items,
                    charge,
                    carried,
                )
                throw AssertionError("Expected the correction to be refused for $key")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }

        // Anything short of the full previous total would count the paid part twice.
        attempt("partial-credit", 6_000, emptyList())
        // Nothing was ever collected on this sale, so nothing can be carried onto its replacement.
        attempt("overcarried", 10_000, listOf(PaymentAllocationEntity("allocation-x", "payment-x", corrected.id, 5_000)))
        assertEquals(10_000, dao.customerBalanceCents("business-1", "customer-1"))
        assertFalse(requireNotNull(dao.saleForId(previous.id)).isCanceled)
    }

    private fun customer(id: String, businessId: String, name: String) = CustomerEntity(
        id, businessId, name, name.lowercase(), null, null, null, null, null,
        true, 1, 1, null,
    )

    private fun sale(id: String, businessId: String, customerId: String, isCanceled: Boolean) = SaleEntity(
        id, businessId, customerId, "owner-1", id, 1, null, 1_000, 0, 1_000,
        isCanceled, if (isCanceled) "canceled" else null, 1, 1,
    )

    private fun payment(id: String, businessId: String, customerId: String, canceledAt: Long?) = PaymentEntity(
        id, businessId, customerId, 100, "CASH", 2, null, null, "owner-1", 2, canceledAt,
    )

    @Test fun a_canceled_payment_does_not_count_as_money_collected_in_the_score() = runBlocking {
        val dao = database.financialDao()
        val sale = SaleEntity("sale-1", "business-1", "customer-1", "owner-1", "V-1", 1, 30, 10_000, 0, 10_000, false, null, 1, 1)
        dao.createSaleIdempotently(
            OperationEntity("sale-1-key", "SALE", sale.id, 1),
            sale,
            listOf(SaleItemEntity("item-1", sale.id, "product-1", "Jabón", 1, 10_000, 0, 10_000)),
            AccountMovementEntity("charge-1", "business-1", "customer-1", sale.id, null, "CHARGE", 10_000, "DEBIT", 1, "owner-1", null, null),
        )
        val payment = PaymentEntity("payment-1", "business-1", "customer-1", 10_000, "CASH", 28, null, null, "owner-1", 2, null)
        dao.registerPaymentIdempotently(
            OperationEntity("payment-1-key", "PAYMENT", payment.id, 2),
            payment,
            listOf(PaymentAllocationEntity("allocation-1", payment.id, sale.id, 10_000)),
            AccountMovementEntity("movement-1", "business-1", "customer-1", null, payment.id, "PAYMENT", 10_000, "CREDIT", 2, "owner-1", null, null),
        )

        val settled = dao.saleHistoryForScores("business-1").single()
        assertEquals(10_000, settled.allocatedCents)
        assertEquals(28L, settled.lastPaymentDateEpochDay)

        dao.cancelPaymentIdempotently(
            OperationEntity("refund-1-key", "PAYMENT", payment.id, 3),
            payment.id,
            AccountMovementEntity("refund-1", "business-1", "customer-1", null, payment.id, "REFUND", 10_000, "DEBIT", 3, "owner-1", null, "Cheque devuelto"),
        )

        // The allocation row is still there — nothing financial is ever deleted — so a score that
        // read it without checking the payment would call this customer settled and punctual.
        val reversed = dao.saleHistoryForScores("business-1").single()
        assertEquals(0, reversed.allocatedCents)
        assertEquals(null, reversed.lastPaymentDateEpochDay)
    }

    @Test fun the_score_query_never_reaches_into_another_business() = runBlocking {
        val dao = database.financialDao()
        val mine = SaleEntity("sale-mine", "business-1", "customer-1", "owner-1", "V-1", 1, 30, 10_000, 0, 10_000, false, null, 1, 1)
        val theirs = mine.copy(id = "sale-theirs", businessId = "business-2")
        listOf(mine, theirs).forEachIndexed { index, sale ->
            dao.createSaleIdempotently(
                OperationEntity("key-$index", "SALE", sale.id, 1),
                sale,
                listOf(SaleItemEntity("item-$index", sale.id, "product-1", "Jabón", 1, 10_000, 0, 10_000)),
                AccountMovementEntity("charge-$index", sale.businessId, sale.customerId, sale.id, null, "CHARGE", 10_000, "DEBIT", 1, "owner-1", null, null),
            )
        }

        // One row, and it is this business's. Nothing in the schema has a @ForeignKey, so the
        // businessId filter is the only thing keeping another shop's history out of this score.
        val rows = dao.saleHistoryForScores("business-1")
        assertEquals(1, rows.size)
        assertEquals("customer-1", rows.single().customerId)
        assertEquals(10_000, rows.single().totalCents)
    }

    private fun movement(
        id: String,
        businessId: String,
        customerId: String,
        cents: Long,
        occurredAt: Long,
    ) = AccountMovementEntity(
        id, businessId, customerId, null, null, "CHARGE", cents, "DEBIT",
        occurredAt, "owner", null, null,
    )
}
