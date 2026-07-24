package com.cuentivo.app.ui.dashboard

import com.cuentivo.app.domain.DashboardSnapshot
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
}
