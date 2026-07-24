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
                }, onCreateSale = { customer, product ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val saleId = UUID.randomUUID().toString()
                        val sale = com.cuentivo.app.data.local.SaleEntity(saleId, businessId, customer.customer.id, "local-owner", "V-${now}", java.time.LocalDate.now().toEpochDay(), null, product.salePriceCents, 0, product.salePriceCents, false, null, now, now)
                        val item = com.cuentivo.app.data.local.SaleItemEntity(UUID.randomUUID().toString(), saleId, product.id, product.name, 1, product.salePriceCents, 0, product.salePriceCents)
                        val charge = com.cuentivo.app.data.local.AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, saleId, null, "CHARGE", product.salePriceCents, "DEBIT", now, "local-owner", null, null)
                        database.financialDao().createSaleIdempotently(com.cuentivo.app.data.local.OperationEntity("sale-$saleId", "SALE", saleId, now), sale, listOf(item), charge)
                        recreate()
                    }
                }, onCreatePayment = { customer, amountCents ->
                    val businessId = (state as? DashboardUiState.Content)?.businessId ?: return@CuentivoApp
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        val paymentId = UUID.randomUUID().toString()
                        val payment = com.cuentivo.app.data.local.PaymentEntity(paymentId, businessId, customer.customer.id, amountCents, "CASH", java.time.LocalDate.now().toEpochDay(), null, null, "local-owner", now, null)
                        val movement = com.cuentivo.app.data.local.AccountMovementEntity(UUID.randomUUID().toString(), businessId, customer.customer.id, null, paymentId, "PAYMENT", amountCents, "CREDIT", now, "local-owner", null, null)
                        database.financialDao().registerPaymentIdempotently(com.cuentivo.app.data.local.OperationEntity("payment-$paymentId", "PAYMENT", paymentId, now), payment, emptyList(), movement)
                        recreate()
                    }
                })
            }
        }
    }
}
