package com.cuentivo.app.domain

import com.cuentivo.app.data.local.BusinessDao
import com.cuentivo.app.data.local.FinancialDao
import java.time.LocalDate

data class DashboardActivity(
    val id: String,
    val customerId: String,
    val customerName: String,
    val type: String,
    val direction: String,
    val amountCents: Long,
    val occurredAtEpochMillis: Long,
    /**
     * True when this charge is the debt the customer already had before the app (v45). Inicio must
     * call it "Deuda anterior", the same word the customer's own history uses, instead of "Nueva
     * venta" — the same ledger row cannot read two different ways on two screens.
     */
    val isPriorDebt: Boolean = false,
)

data class DashboardSnapshot(
    val businessId: String,
    val businessName: String,
    val receivableCents: Long,
    val soldCents: Long,
    val collectedCents: Long,
    val overdueCents: Long,
    val customersWithDebt: Long,
    val insights: List<Insight> = emptyList(),
    val dueTodayCents: Long = 0L,
    val dueSales: List<DueSale> = emptyList(),
    val recentActivity: List<DashboardActivity> = emptyList(),
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

        val monthStart = today.withDayOfMonth(1)
        // Same stretch of the previous month, not the whole thing. On the 3rd, three days of
        // collection compared against a full previous month would read as a catastrophic drop and
        // the number would be a lie. minusMonths also clamps the day, so 31 March lands on 28 Feb.
        val previousEnd = today.minusMonths(1)
        val previousStart = previousEnd.withDayOfMonth(1)

        val soldCents = financialDao.salesTotalCents(business.id, monthStart.toEpochDay(), today.toEpochDay())
        val collectedCents = financialDao.collectedTotalCents(business.id, monthStart.toEpochDay(), today.toEpochDay())
        val overdueCents = financialDao.overdueBalanceCents(business.id, today.toEpochDay())
        val dueRows = financialDao.salesWithDueDate(business.id)
        val dueSales = dueRows.map {
            DueSale(
                saleId = it.saleId,
                customerId = it.customerId,
                customerName = it.customerName,
                dueDateEpochDay = it.dueDateEpochDay,
                outstandingCents = it.outstandingCents,
            )
        }
        val dueTodayCents = dueSales
            .filter { it.dueDateEpochDay == today.toEpochDay() }
            .sumOf { it.outstandingCents }
        val recentActivity = financialDao.recentDashboardActivity(business.id, 5).map {
            DashboardActivity(
                id = it.id,
                customerId = it.customerId,
                customerName = it.customerName,
                type = it.type,
                direction = it.direction,
                amountCents = it.amountCents,
                occurredAtEpochMillis = it.occurredAtEpochMillis,
                isPriorDebt = it.isPriorDebt,
            )
        }

        val currentTotals = PeriodTotals(soldCents, collectedCents, overdueCents)
        val previousTotals = PeriodTotals(
            soldCents = financialDao.salesTotalCents(business.id, previousStart.toEpochDay(), previousEnd.toEpochDay()),
            collectedCents = financialDao.collectedTotalCents(business.id, previousStart.toEpochDay(), previousEnd.toEpochDay()),
            overdueCents = 0L,
        )

        return DashboardSnapshot(
            businessId = business.id,
            businessName = business.name,
            receivableCents = financialDao.businessReceivableCents(business.id),
            soldCents = soldCents,
            collectedCents = collectedCents,
            overdueCents = overdueCents,
            customersWithDebt = financialDao.customersWithDebtCount(business.id),
            insights = BusinessInsights.compare(currentTotals, previousTotals),
            dueTodayCents = dueTodayCents,
            dueSales = dueSales,
            recentActivity = recentActivity,
        )
    }
}
