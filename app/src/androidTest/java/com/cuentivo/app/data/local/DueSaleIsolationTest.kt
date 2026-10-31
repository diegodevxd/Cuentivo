package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The alerts query must never pull in a customer from another business.
 *
 * This schema has no @ForeignKey anywhere — integrity lives in `require(...)` inside the FinancialDao
 * transactions — so nothing at the database level prevents a sale row from naming a customer id that
 * belongs to somebody else. A merge from a remote device, or a bug in a future write path, could
 * produce exactly that. The join in `salesWithDueDate` matches on businessId as well as customer id,
 * and these tests build the adversarial rows on purpose to prove it holds.
 */
@RunWith(AndroidJUnit4::class)
class DueSaleIsolationTest {
    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    private fun customer(id: String, businessId: String, name: String) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = name.lowercase(),
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

    private fun sale(id: String, businessId: String, customerId: String) = SaleEntity(
        id = id,
        businessId = businessId,
        customerId = customerId,
        sellerUserId = "owner-$businessId",
        saleNumber = "S-$id",
        saleDateEpochDay = 20_290,
        dueDateEpochDay = 20_295,
        subtotalCents = 50_000,
        discountCents = 0,
        totalCents = 50_000,
        isCanceled = false,
        notes = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    @Test fun each_business_only_sees_its_own_due_sales() = runBlocking {
        val dao = database.financialDao()
        database.customerDao().insert(customer("customer-a", "business-a", "Ana"))
        database.customerDao().insert(customer("customer-b", "business-b", "Beto"))
        dao.insertSale(sale("sale-a", "business-a", "customer-a"))
        dao.insertSale(sale("sale-b", "business-b", "customer-b"))

        assertEquals(listOf("Ana"), dao.salesWithDueDate("business-a").map { it.customerName })
        assertEquals(listOf("Beto"), dao.salesWithDueDate("business-b").map { it.customerName })
    }

    @Test fun a_sale_pointing_at_another_businesss_customer_produces_no_alert() = runBlocking {
        val dao = database.financialDao()
        // Adversarial but perfectly legal for this schema: business A's sale names business B's
        // customer. Joining on customer id alone would hand Beto's name to business A.
        database.customerDao().insert(customer("customer-b", "business-b", "Beto"))
        dao.insertSale(sale("sale-crossed", "business-a", "customer-b"))

        assertTrue(dao.salesWithDueDate("business-a").isEmpty())
        // And it does not leak the other way either: the sale belongs to A, so B must not see it.
        assertTrue(dao.salesWithDueDate("business-b").isEmpty())
    }

    @Test fun two_businesses_with_their_own_customers_never_cross() = runBlocking {
        val dao = database.financialDao()
        database.customerDao().insert(customer("customer-1", "business-a", "AnaDeA"))
        database.customerDao().insert(customer("customer-2", "business-b", "BetoDeB"))
        dao.insertSale(sale("sale-a", "business-a", "customer-1"))
        dao.insertSale(sale("sale-b", "business-b", "customer-2"))

        val forA = dao.salesWithDueDate("business-a")
        assertEquals(1, forA.size)
        assertEquals("AnaDeA", forA.single().customerName)
        assertEquals("customer-1", forA.single().customerId)
        // The customerId is what the alert taps through to, so pointing at the wrong business's
        // customer would open a stranger's statement, not just show the wrong name.
        assertTrue(forA.none { it.customerName.contains("DeB") })
    }
}
