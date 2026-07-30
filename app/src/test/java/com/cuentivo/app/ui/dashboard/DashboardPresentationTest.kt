package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.DueSale
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPresentationTest {
    @Test
    fun overdue_customers_come_before_larger_current_debts() {
        val result = DashboardPresentation.criticalCustomers(
            customers = listOf(balance("large", 50_000), balance("late", 10_000)),
            dueSales = listOf(DueSale("sale", "late", "Late", 99, 4_000)),
            todayEpochDay = 100,
            limit = 3,
        )

        assertEquals(listOf("late", "large"), result.map { it.customer.customer.id })
    }

    private fun balance(id: String, cents: Long) = CustomerBalanceRow(
        customer = customer(id, "business-1", id.replaceFirstChar { it.uppercase() }),
        balanceCents = cents,
    )

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
}
