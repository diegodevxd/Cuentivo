package com.cuentivo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import com.cuentivo.app.ui.theme.CuentivoTheme
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.domain.DashboardRepository
import com.cuentivo.app.ui.CuentivoApp
import com.cuentivo.app.ui.dashboard.DashboardUiState
import com.cuentivo.app.ui.dashboard.DashboardViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = Room.databaseBuilder(applicationContext, CuentivoDatabase::class.java, "cuentivo.db").build()
        val viewModel = DashboardViewModel(DashboardRepository(database.businessDao(), database.financialDao())::load)
        setContent {
            CuentivoTheme {
                val state by produceState<DashboardUiState>(initialValue = DashboardUiState.NoBusiness) { value = viewModel.load() }
                CuentivoApp(state)
            }
        }
    }
}
