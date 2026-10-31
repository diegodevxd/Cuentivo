package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerDao
import com.cuentivo.app.data.local.CustomerEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerRepositoryTest {

    private class FakeCustomerDao(
        private val customers: MutableList<CustomerEntity> = mutableListOf(),
    ) : CustomerDao {
        override suspend fun insert(customer: CustomerEntity) { customers.add(customer) }
        override suspend fun upsert(customer: CustomerEntity) {
            val idx = customers.indexOfFirst { it.id == customer.id }
            if (idx != -1) customers[idx] = customer else customers.add(customer)
        }
        override suspend fun update(customer: CustomerEntity) {
            val idx = customers.indexOfFirst { it.id == customer.id }
            if (idx != -1) customers[idx] = customer
        }
        override suspend fun searchActive(businessId: String, nameQuery: String, phoneQuery: String): List<CustomerEntity> {
            return customers.filter { c ->
                c.businessId == businessId && c.deletedAtEpochMillis == null && c.isActive && (
                    (nameQuery.isEmpty() && phoneQuery.isEmpty()) ||
                    (nameQuery.isNotEmpty() && c.normalizedName.contains(nameQuery)) ||
                    (phoneQuery.isNotEmpty() && (c.phone ?: "").contains(phoneQuery))
                )
            }.sortedBy { it.name }
        }
        override suspend fun activeWithBalances(businessId: String): List<CustomerBalanceRow> = emptyList()
        override suspend fun archive(customerId: String, archivedAtEpochMillis: Long) {
            val idx = customers.indexOfFirst { it.id == customerId }
            if (idx != -1) {
                customers[idx] = customers[idx].copy(isActive = false, deletedAtEpochMillis = archivedAtEpochMillis)
            }
        }
        override suspend fun byId(customerId: String): CustomerEntity? = customers.find { it.id == customerId }
        override suspend fun allForBackfill(businessId: String): List<CustomerEntity> = customers.filter { it.businessId == businessId }
    }

    private fun sampleCustomer(id: String, name: String, phone: String? = null) = CustomerEntity(
        id = id,
        businessId = "biz-1",
        name = name,
        normalizedName = CustomerNameNormalizer.key(name),
        phone = phone,
        email = null,
        address = null,
        notes = null,
        creditLimitCents = null,
        isActive = true,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        deletedAtEpochMillis = null,
    )

    @Test
    fun `searching non-existent name without digits returns empty list instead of all customers`() = runBlocking {
        val dao = FakeCustomerDao(
            mutableListOf(
                sampleCustomer("c1", "Carina", phone = null),
                sampleCustomer("c2", "Demian", phone = "5551234"),
            )
        )
        val repository = CustomerRepository(dao)

        val result = repository.search("biz-1", "fulano")
        assertTrue("Non-existent name must return empty list", result.isEmpty())
    }

    @Test
    fun `searching partial name finds matching customers`() = runBlocking {
        val dao = FakeCustomerDao(
            mutableListOf(
                sampleCustomer("c1", "Carina", phone = null),
                sampleCustomer("c2", "Demian", phone = "5551234"),
            )
        )
        val repository = CustomerRepository(dao)

        val result = repository.search("biz-1", "dem")
        assertEquals(1, result.size)
        assertEquals("c2", result.first().id)
    }

    @Test
    fun `searching digits finds customer by phone number`() = runBlocking {
        val dao = FakeCustomerDao(
            mutableListOf(
                sampleCustomer("c1", "Carina", phone = null),
                sampleCustomer("c2", "Demian", phone = "5551234"),
            )
        )
        val repository = CustomerRepository(dao)

        val result = repository.search("biz-1", "555")
        assertEquals(1, result.size)
        assertEquals("c2", result.first().id)
    }

    @Test
    fun `empty query continues to list all active customers`() = runBlocking {
        val dao = FakeCustomerDao(
            mutableListOf(
                sampleCustomer("c1", "Carina", phone = null),
                sampleCustomer("c2", "Demian", phone = "5551234"),
            )
        )
        val repository = CustomerRepository(dao)

        val result = repository.search("biz-1", "")
        assertEquals(2, result.size)
    }
}
