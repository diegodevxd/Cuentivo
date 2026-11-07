package com.cuentivo.app.sync

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.SyncOperationDao
import com.cuentivo.app.data.local.SyncOperationEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadJsonCompatibilityTest {

    private class CapturingDao : SyncOperationDao {
        val operations = mutableListOf<SyncOperationEntity>()

        override suspend fun enqueue(operation: SyncOperationEntity): Long {
            operations += operation
            return 1L
        }

        override suspend fun pending(limit: Int): List<SyncOperationEntity> = emptyList()

        override suspend fun remove(id: String) = Unit

        override suspend fun markFailure(id: String, error: String) = Unit

        override suspend fun reopenParkedOperations() = Unit

        override suspend fun reopenSettledPermissionDenials(maxAttempts: Int) = Unit

        override suspend fun parkExhausted(maxAttempts: Int) = Unit
    }

    private fun encoded(fields: Map<String, Any?>): JSONObject = runBlocking {
        val dao = CapturingDao()
        SyncQueue(dao, scheduleSync = {}).enqueue(
            businessId = "business-1",
            entityType = "CUSTOMER",
            entityId = "entity-1",
            revision = 1L,
            fields = fields,
        )
        JSONObject(dao.operations.single().payload)
    }

    private fun similar(actual: JSONObject, expected: JSONObject): Boolean {
        if (actual.length() != expected.length()) return false
        for (key in actual.keys()) {
            if (!expected.has(key)) return false
            val actualValue = actual.get(key)
            val expectedValue = expected.get(key)
            val matches = when {
                actualValue is JSONObject && expectedValue is JSONObject ->
                    similar(actualValue, expectedValue)
                actualValue is Number && expectedValue is Number ->
                    actualValue.toLong() == expectedValue.toLong()
                else -> actualValue == expectedValue
            }
            if (!matches) return false
        }
        return true
    }

    private fun assertSimilar(fields: Map<String, Any?>) {
        val actual = encoded(fields)
        // JSON-java omits null-valued map entries; Android's JSONObject keeps them as NULL.
        val expected = JSONObject(fields.mapValues { (_, value) -> value ?: JSONObject.NULL })
        assertTrue(similar(actual, expected))
    }

    private val business = BusinessEntity(
        id = "business-1",
        name = "Mi negocio",
        ownerUserId = "owner-1",
        currency = "MXN",
        timezone = "America/Mexico_City",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    private val member = BusinessMemberEntity(
        id = "business-1:owner-1",
        businessId = "business-1",
        userId = "owner-1",
        role = "OWNER",
        status = "ACTIVE",
        invitedAtEpochMillis = null,
        acceptedAtEpochMillis = 1L,
    )

    private val customer = CustomerEntity(
        id = "customer-1",
        businessId = "business-1",
        name = "Ana",
        normalizedName = "ana",
        phone = "555",
        email = null,
        address = null,
        notes = null,
        creditLimitCents = 500L,
        isActive = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        deletedAtEpochMillis = null,
    )

    private val product = ProductEntity(
        id = "product-1",
        businessId = "business-1",
        name = "Café",
        description = null,
        sku = null,
        category = null,
        salePriceCents = 100L,
        costPriceCents = null,
        stock = 5L,
        imageUri = null,
        isActive = true,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        deletedAtEpochMillis = null,
    )

    private val sale = SaleEntity(
        id = "sale-1",
        businessId = "business-1",
        customerId = "customer-1",
        sellerUserId = "owner-1",
        saleNumber = "V-1",
        saleDateEpochDay = 20_000L,
        dueDateEpochDay = null,
        subtotalCents = 100L,
        discountCents = 0L,
        totalCents = 100L,
        isCanceled = false,
        notes = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    private val saleItem = SaleItemEntity(
        id = "item-1",
        saleId = "sale-1",
        productId = null,
        productNameSnapshot = "Café",
        quantity = 1L,
        unitPriceCents = 100L,
        discountCents = 0L,
        subtotalCents = 100L,
    )

    private val payment = PaymentEntity(
        id = "payment-1",
        businessId = "business-1",
        customerId = "customer-1",
        amountCents = 50L,
        paymentMethod = "CASH",
        paymentDateEpochDay = 20_000L,
        reference = null,
        notes = null,
        createdByUserId = "owner-1",
        createdAtEpochMillis = 1L,
        canceledAtEpochMillis = null,
    )

    private val allocation = PaymentAllocationEntity(
        id = "allocation-1",
        paymentId = "payment-1",
        saleId = "sale-1",
        amountCents = 50L,
    )

    private val movement = AccountMovementEntity(
        id = "movement-1",
        businessId = "business-1",
        customerId = "customer-1",
        saleId = "sale-1",
        paymentId = null,
        type = "CHARGE",
        amountCents = 100L,
        direction = "DEBIT",
        occurredAtEpochMillis = 1L,
        createdByUserId = "owner-1",
        reversesMovementId = null,
        notes = null,
    )

    @Test
    fun `every payload builder matches the previous json encoding`() {
        assertSimilar(SyncPayloads.business(business))
        assertSimilar(SyncPayloads.member(member))
        assertSimilar(SyncPayloads.customer(customer))
        assertSimilar(SyncPayloads.product(product))
        assertSimilar(SyncPayloads.sale(sale))
        assertSimilar(SyncPayloads.saleItem(saleItem))
        assertSimilar(SyncPayloads.payment(payment))
        assertSimilar(SyncPayloads.allocation(allocation))
        assertSimilar(SyncPayloads.movement(movement))
    }

    @Test
    fun `difficult characters match the previous json encoding`() {
        val tricky = customer.copy(
            name = "Ana \"la jefa\"",
            phone = null,
            address = "Calle 1\\2",
            notes = "línea 1\nlínea 2\temoji 😀",
        )

        assertSimilar(SyncPayloads.customer(tricky))
    }
}
