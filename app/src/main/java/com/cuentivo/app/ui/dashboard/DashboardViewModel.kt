package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.DashboardSnapshot
import com.cuentivo.app.domain.DueSale
import com.cuentivo.app.domain.Insight

sealed interface DashboardUiState {
    data object NoBusiness : DashboardUiState

    data class Content(
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
    ) : DashboardUiState
}

class DashboardViewModel(private val loadDashboard: suspend () -> DashboardSnapshot?) {
    suspend fun load(): DashboardUiState = loadDashboard()?.let { snapshot ->
        DashboardUiState.Content(
            businessId = snapshot.businessId,
            businessName = snapshot.businessName,
            receivableCents = snapshot.receivableCents,
            soldCents = snapshot.soldCents,
            collectedCents = snapshot.collectedCents,
            overdueCents = snapshot.overdueCents,
            customersWithDebt = snapshot.customersWithDebt,
            insights = snapshot.insights,
            dueTodayCents = snapshot.dueTodayCents,
            dueSales = snapshot.dueSales,
            recentActivity = snapshot.recentActivity,
        )
    } ?: DashboardUiState.NoBusiness
}
