package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.domain.DashboardActivity
import com.cuentivo.app.domain.DashboardSnapshot
import com.cuentivo.app.domain.DueSale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardViewModelTest {
    @Test
    fun no_business_becomes_an_empty_dashboard() = runBlocking {
        val viewModel = DashboardViewModel { null }

        assertEquals(DashboardUiState.NoBusiness, viewModel.load())
    }

    @Test
    fun snapshot_becomes_dashboard_content() = runBlocking {
        val viewModel = DashboardViewModel {
            DashboardSnapshot("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2)
        }

        assertEquals(12_500, (viewModel.load() as DashboardUiState.Content).receivableCents)
    }

    @Test
    fun snapshot_keeps_the_dashboard_feed_data() = runBlocking {
        val dueSale = DueSale("sale-1", "customer-1", "Ana", 100, 2_500)
        val activity = DashboardActivity(
            id = "movement-1",
            customerId = "customer-1",
            customerName = "Ana",
            type = "CHARGE",
            direction = "DEBIT",
            amountCents = 2_500,
            occurredAtEpochMillis = 1_000,
        )
        val viewModel = DashboardViewModel {
            DashboardSnapshot(
                businessId = "business-1",
                businessName = "Mi negocio",
                receivableCents = 12_500,
                soldCents = 8_000,
                collectedCents = 3_000,
                overdueCents = 500,
                customersWithDebt = 2,
                dueTodayCents = 2_500,
                dueSales = listOf(dueSale),
                recentActivity = listOf(activity),
            )
        }

        val content = viewModel.load() as DashboardUiState.Content

        assertEquals(2_500, content.dueTodayCents)
        assertEquals(listOf(dueSale), content.dueSales)
        assertEquals(listOf(activity), content.recentActivity)
    }
}
