package com.cuentivo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID
import com.cuentivo.app.domain.CustomerNameNormalizer
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.ProductEntity
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
                val customers by produceState<List<CustomerBalanceRow>>(initialValue = emptyList(), state) {
                    value = (state as? DashboardUiState.Content)?.let { database.customerDao().activeWithBalances(it.businessId) } ?: emptyList()
                }
                val products by produceState<List<ProductEntity>>(initialValue = emptyList(), state) {
                    value = (state as? DashboardUiState.Content)?.let { database.productDao().searchActive(it.businessId, "", "") } ?: emptyList()
                }
                CuentivoApp(state, customers, products, onCreateBusiness = {
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        database.businessDao().insert(com.cuentivo.app.data.local.BusinessEntity(UUID.randomUUID().toString(), "Mi negocio", "local-owner", "MXN", "America/Mexico_City", now, now))
                        recreate()
                    }
                }, onCreateCustomer = { name ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        database.customerDao().insert(com.cuentivo.app.data.local.CustomerEntity(UUID.randomUUID().toString(), businessId, name, CustomerNameNormalizer.key(name), null, null, null, null, null, true, now, now, null))
                        recreate()
                    }
                }, onCreateProduct = { name, priceCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        database.productDao().insert(com.cuentivo.app.data.local.ProductEntity(UUID.randomUUID().toString(), businessId, name, null, null, null, priceCents, null, null, null, true, now, now, null))
                        recreate()
                    }
                })
            }
        }
    }
}
