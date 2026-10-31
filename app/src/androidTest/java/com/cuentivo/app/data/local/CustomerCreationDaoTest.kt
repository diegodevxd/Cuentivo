package com.cuentivo.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.domain.CustomerNameNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the customer list reads back straight after a create.
 *
 * `activeWithBalances` LEFT JOINs the ledger, and a customer nobody has sold anything to has no
 * movements at all. That join has to yield the row with a zero balance rather than dropping it — if
 * it dropped it, a freshly created customer would be invisible no matter what the screen did.
 */
@RunWith(AndroidJUnit4::class)
class CustomerCreationDaoTest {
    private lateinit var database: CuentivoDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test fun a_customer_with_no_movements_still_comes_back_with_a_zero_balance() = runBlocking {
        val dao = database.customerDao()
        dao.insert(customer("ana", "Ana López"))

        val rows = dao.activeWithBalances(BUSINESS)

        assertEquals(listOf("ana"), rows.map { it.customer.id })
        assertEquals("Ana López", rows.single().customer.name)
        assertEquals(0L, rows.single().balanceCents)
    }

    @Test fun another_business_never_sees_it() = runBlocking {
        val dao = database.customerDao()
        dao.insert(customer("ana", "Ana López"))
        dao.insert(customer("beto", "Beto Ruiz", businessId = "business-2"))

        assertEquals(listOf("ana"), dao.activeWithBalances(BUSINESS).map { it.customer.id })
    }

    /** Names are normalized for search only; `CustomerEntity.id` remains the sole identity. */
    @Test fun customers_with_the_same_normalized_name_are_both_allowed() = runBlocking {
        val dao = database.customerDao()
        dao.insert(customer("first", "José Pérez"))
        dao.insert(customer("second", "jose perez"))

        assertEquals(listOf("first", "second"), dao.activeWithBalances(BUSINESS).map { it.customer.id })
    }

    private fun customer(id: String, name: String, businessId: String = BUSINESS) = CustomerEntity(
        id = id,
        businessId = businessId,
        name = name,
        normalizedName = CustomerNameNormalizer.key(name),
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

    private companion object {
        const val BUSINESS = "business-1"
    }
}
