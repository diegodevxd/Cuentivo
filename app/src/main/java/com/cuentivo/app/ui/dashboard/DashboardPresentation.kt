package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.domain.DueSale

internal data class CriticalCustomer(
    val customer: CustomerBalanceRow,
    val overdueCents: Long,
)

internal object DashboardPresentation {
    fun criticalCustomers(
        customers: List<CustomerBalanceRow>,
        dueSales: List<DueSale>,
        todayEpochDay: Long,
        limit: Int = 3,
    ): List<CriticalCustomer> {
        val overdueByCustomer = dueSales
            .filter { it.dueDateEpochDay < todayEpochDay }
            .groupBy { it.customerId }
            .mapValues { (_, rows) -> rows.sumOf { it.outstandingCents } }
        return customers
            .filter { it.balanceCents > 0 }
            .map { CriticalCustomer(it, overdueByCustomer[it.customer.id] ?: 0L) }
            .sortedWith(
                compareByDescending<CriticalCustomer> { it.overdueCents > 0 }
                    .thenByDescending { it.overdueCents }
                    .thenByDescending { it.customer.balanceCents }
            )
            .take(limit)
    }
}
