package com.cuentivo.app.domain

import com.cuentivo.app.data.local.CustomerDao
import com.cuentivo.app.data.local.CustomerEntity

class CustomerRepository(private val customerDao: CustomerDao) {
    suspend fun create(customer: CustomerEntity) {
        validate(customer)
        customerDao.insert(customer)
    }

    suspend fun update(customer: CustomerEntity) {
        validate(customer)
        customerDao.update(customer)
    }

    suspend fun archive(customerId: String, archivedAtEpochMillis: Long) {
        require(customerId.isNotBlank())
        customerDao.archive(customerId, archivedAtEpochMillis)
    }

    suspend fun search(businessId: String, query: String): List<CustomerEntity> {
        require(businessId.isNotBlank())
        return customerDao.searchActive(businessId, CustomerNameNormalizer.key(query), query.filter(Char::isDigit))
    }

    private fun validate(customer: CustomerEntity) {
        require(customer.businessId.isNotBlank())
        require(customer.name.isNotBlank())
        require(customer.normalizedName.isNotBlank())
        require(customer.creditLimitCents == null || customer.creditLimitCents >= 0)
    }
}
