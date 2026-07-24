package com.cuentivo.app.domain

import com.cuentivo.app.data.local.BusinessDao
import com.cuentivo.app.data.local.FinancialDao
import java.time.LocalDate

data class DashboardSnapshot(
    val businessId: String,
    val businessName: String,
    val receivableCents: Long,
    val soldCents: Long,
    val collectedCents: Long,
    val overdueCents: Long,
    val customersWithDebt: Long,
)

class DashboardRepository(
    private val businessDao: BusinessDao,
    private val financialDao: FinancialDao,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun load(): DashboardSnapshot? {
        val business = businessDao.first() ?: return null
        val today = todayProvider()
        return DashboardSnapshot(
            businessId = business.id,
            businessName = business.name,
            receivableCents = financialDao.businessReceivableCents(business.id),
            soldCents = financialDao.salesTotalCents(business.id, today.withDayOfMonth(1).toEpochDay(), today.toEpochDay()),
            collectedCents = financialDao.collectedTotalCents(business.id, today.withDayOfMonth(1).toEpochDay(), today.toEpochDay()),
            overdueCents = financialDao.overdueBalanceCents(business.id, today.toEpochDay()),
            customersWithDebt = financialDao.customersWithDebtCount(business.id),
        )
    }
}
