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
    /**
     * The dashboard of the business [userId] owns — never simply "the first business on the phone".
     *
     * Two accounts used to share one screen because this took whatever business Room held first, so
     * a second person signing in on the same device saw the first one's customers and balances.
     */
    suspend fun load(userId: String): DashboardSnapshot? {
        val business = businessDao.firstOwnedBy(userId) ?: return null
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
